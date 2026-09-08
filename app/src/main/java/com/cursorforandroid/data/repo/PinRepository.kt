package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

data class PinSyncState(
    /** Pins are being kept in step with the Cursor account: the setting is on and this is a real backend. */
    val active: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncedAtMillis: Long? = null,
    /** Why the last sync did not go through, for Settings; null while syncing works or has not been tried. */
    val error: String? = null,
    /** Pin changes made here that the server has not acknowledged yet. */
    val pendingCount: Int = 0,
)

/**
 * Keeps the sidebar's pins in step with the Cursor account, the way the desktop Agents window and the iOS app share
 * them. The account's list ([PinsApi.pinnedIds]) is the source of truth; what the UI reads stays
 * [PreferencesStore.localAgentState]'s pinned set, which this repository writes.
 *
 * A pin toggled here is applied at once and sent to the server; until the server has acknowledged it, it is kept
 * as a pending change (on disk, so it survives a restart) and wins over the server's answer, and the next sync
 * replays it. The first sync of an account pushes the pins made on this device before syncing existed — only for
 * agents the account can see, so leftovers of another account never travel. Pinned agents the list window no longer
 * includes are fetched by id so a pin is never silently dropped.
 */
class PinRepository(
    private val session: SessionManager,
    private val prefs: PreferencesStore,
    private val agents: AgentRepository,
    private val api: PinsApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = AppClock::now,
    private val maxMaterialized: Int = MAX_MATERIALIZED,
) {
    private val _state = MutableStateFlow(PinSyncState())
    val state: StateFlow<PinSyncState> = _state.asStateFlow()

    private val syncMutex = Mutex()

    /** Set after a failure retrying cannot fix (a rejected key, a device policy); cleared by a sign-in or the setting. */
    @Volatile private var halted = false
    private val settingWatcher = AtomicReference<Job?>(null)

    init {
        scope.launch {
            // Every completed list fetch (not the disk copy) is the cue: the account's pins are read alongside its
            // agents. Nothing here reads the preferences before that: constructing the graph must stay side-effect free.
            agents.refreshCompleted.filter { it > 0L }.collect {
                watchSetting()
                sync()
            }
        }
        scope.launch { session.backend.drop(1).collect { reset() } }
    }

    /** Turning the setting on syncs right away, and forgives an earlier permanent failure. Started once, lazily. */
    private fun watchSetting() {
        if (settingWatcher.get() != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            prefs.pinSyncEnabled.distinctUntilChanged().drop(1).filter { it }.collect {
                halted = false
                sync()
            }
        }
        if (settingWatcher.compareAndSet(null, job)) job.start() else job.cancel()
    }

    /** On sign-out or a backend switch: nothing of the previous account's sync survives. */
    fun reset() {
        halted = false
        _state.value = PinSyncState()
    }

    /**
     * Pins or unpins [agentId]. The change shows immediately; the server hears about it now, or, when it cannot be
     * reached, at the next sync. The result says whether the server has it — the pin itself has already been applied.
     */
    suspend fun toggle(agentId: String): Result<Unit> {
        val wasPinned = agentId in prefs.localAgentState.first().pinnedIds
        prefs.togglePinned(agentId)
        if (!eligible()) return Result.success(Unit)
        prefs.setPendingPinChange(agentId, !wasPinned)
        return runCatching {
            if (wasPinned) api.unpin(listOf(agentId)) else api.pin(listOf(agentId))
        }.onSuccess {
            prefs.setPendingPinChange(agentId, null)
            _state.update { it.copy(active = true, lastSyncedAtMillis = now(), error = null, pendingCount = pendingCount()) }
        }.onFailure { t ->
            if (t is CancellationException) throw t
            if (t is ConnectRpcException && !t.isUnauthenticated && t.httpCode in 400..499) {
                // The server will not take this change (the agent is gone, or not the account's); do not keep asking.
                prefs.setPendingPinChange(agentId, null)
            }
            noteFailure(t)
        }.map { }
    }

    /**
     * One round with the server: replays pending changes (and, the first time, the pins made here before syncing
     * existed), then adopts the account's pin list. Returns the failure when a step could not reach or convince the
     * server; pending changes are kept for the next round.
     */
    suspend fun sync(): Result<Unit> = syncMutex.withLock {
        if (!eligible()) {
            _state.update { it.copy(active = false, isSyncing = false) }
            return Result.success(Unit)
        }
        _state.update { it.copy(active = true, isSyncing = true) }
        try {
            val known = agents.state.value.agents.mapTo(HashSet()) { it.id }
            val migrating = !prefs.pinsMigrated.first()
            val pending = prefs.pendingPinChanges.first().toMutableMap()
            if (migrating) {
                prefs.localAgentState.first().pinnedIds.filter { it in known && it !in pending }.forEach { pending[it] = true }
            }
            val refused = replay(pending)
            if (migrating) prefs.setPinsMigrated(true)

            val server = api.pinnedIds()
            if (server.loaded) {
                // Changes made while this round was in flight are not in the server's answer yet; they win.
                val late = prefs.pendingPinChanges.first()
                val merged = (server.ids + late.filterValues { it }.keys) - late.filterValues { !it }.keys
                if (merged != prefs.localAgentState.first().pinnedIds) prefs.setPinnedIds(merged)
                materialize(merged.filterNot { it in known })
            }
            _state.update {
                it.copy(isSyncing = false, lastSyncedAtMillis = now(), error = refused?.let(::describe), pendingCount = pendingCount())
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            noteFailure(t)
            Result.failure(t)
        }
    }

    /**
     * Sends the pending changes, pins first. A change the server refuses outright (the agent is gone, or not the
     * account's) is dropped rather than retried forever; the first such refusal is returned for the status line.
     */
    private suspend fun replay(pending: Map<String, Boolean>): ConnectRpcException? {
        if (pending.isEmpty()) return null
        val refusedPin = send(pending.filterValues { it }.keys) { api.pin(it) }
        val refusedUnpin = send(pending.filterValues { !it }.keys) { api.unpin(it) }
        return refusedPin ?: refusedUnpin
    }

    private suspend fun send(ids: Set<String>, call: suspend (Set<String>) -> Unit): ConnectRpcException? {
        if (ids.isEmpty()) return null
        val refused = try {
            call(ids)
            null
        } catch (e: ConnectRpcException) {
            if (e.isUnauthenticated || e.httpCode !in 400..499) throw e
            e
        }
        prefs.clearPendingPinChanges(ids)
        return refused
    }

    /** Rows for pinned agents the list window does not include, so the Pinned group is complete. */
    private suspend fun materialize(ids: List<String>) {
        ids.take(maxMaterialized).forEach { id -> agents.loadDetail(id) }
    }

    private suspend fun eligible(): Boolean = !session.isDemo && !halted && prefs.pinSyncEnabled.first()

    private suspend fun pendingCount(): Int = prefs.pendingPinChanges.first().size

    private suspend fun noteFailure(t: Throwable) {
        if (t is SessionUnavailableException && t.isPermanent) halted = true
        _state.update { it.copy(isSyncing = false, error = describe(t), pendingCount = pendingCount()) }
    }

    private fun describe(t: Throwable): String = when (t) {
        is SessionUnavailableException -> t.message ?: "Cursor couldn't start a session for this key."
        is ConnectRpcException -> "Cursor refused the pin change (${t.message})."
        is IOException -> t.userMessage()
        else -> t.message ?: "Couldn't sync pins."
    }

    private companion object {
        /** Pinned agents outside the list window fetched per sync (two requests each). */
        const val MAX_MATERIALIZED = 12
    }
}
