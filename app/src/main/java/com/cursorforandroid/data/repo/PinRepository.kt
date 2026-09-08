package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AccountList
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
 * them. The account's list ([PinsApi.list]) is the source of truth; what the UI reads stays
 * [PreferencesStore.localAgentState]'s pinned set, which this repository writes.
 *
 * A pin toggled here is applied at once and sent to the server; until the server has acknowledged it, it is kept
 * as a pending change (on disk, so it survives a restart) and wins over the server's answer, and the next sync
 * replays it. The first sync of an account pushes the pins made on this device before syncing existed — only for
 * agents the account can see, so leftovers of another account never travel. Pinned agents the list window no longer
 * includes are fetched by id so a pin is never silently dropped.
 *
 * The same list read says where each agent's pull request stands; every read is handed to [onList] so that goes
 * where it belongs ([PullRequestRepository]) without a second request, whether or not the pins are being synced.
 */
class PinRepository(
    private val session: SessionManager,
    private val prefs: PreferencesStore,
    private val agents: AgentRepository,
    private val api: PinsApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = AppClock::now,
    private val maxMaterialized: Int = MAX_MATERIALIZED,
    private val onList: suspend (AccountList) -> Unit = {},
) {
    private val _state = MutableStateFlow(PinSyncState())
    val state: StateFlow<PinSyncState> = _state.asStateFlow()

    /** One round with the server at a time: a pin tap never overtakes, or is overtaken by, a sync. */
    private val serverMutex = Mutex()

    /** Orders the local flip against its revision, so the revisions describe the order the taps landed in. */
    private val localMutex = Mutex()

    /**
     * Bumped by every local pin change. A change is only forgotten once the server has acknowledged the revision
     * that is still the current one: a tap made while the call was in flight is a newer wish and is replayed.
     */
    private val revision = AtomicLong()
    private val revisions = ConcurrentHashMap<String, Long>()

    /** Bumped by [reset]; nothing started for the previous account may write state, preferences or rows after it. */
    private val generation = AtomicInteger()

    /** Where the account's rounds run, so [reset] can cancel one half-way through without taking the collectors with it. */
    @Volatile private var work: CoroutineScope = workScope()

    /** Pinned ids whose fetch failed, tried again after the ones never tried, so one bad id starves none. */
    private val deferred = LinkedHashSet<String>()

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

    /**
     * On sign-out or a backend switch: nothing of the previous account's sync survives. The round in flight is
     * cancelled and, since a response can already be on its way back, the generation it captured is invalidated so
     * it cannot write the previous account's pins into this one's state, preferences or rows.
     */
    fun reset() {
        generation.incrementAndGet()
        work.cancel()
        work = workScope()
        halted = false
        revisions.clear()
        synchronized(deferred) { deferred.clear() }
        _state.value = PinSyncState()
    }

    private fun workScope(): CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /**
     * Pins or unpins [agentId]. The change shows immediately; the server hears about it now, or, when it cannot be
     * reached, at the next sync. The result says whether the server has it — the pin itself has already been applied.
     */
    suspend fun toggle(agentId: String): Result<Unit> {
        val eligible = eligible()
        // The flip and the wish it records are one transaction: two taps in quick succession cannot both read the
        // state before the other's flip has landed. The revision then orders the wishes they left behind.
        val tapRevision = localMutex.withLock {
            prefs.togglePinnedAwaitingServer(agentId, recordPending = eligible)
            revision.incrementAndGet().also { revisions[agentId] = it }
        }
        if (!eligible) return Result.success(Unit)
        val startedIn = generation.get()
        return serverMutex.withLock {
            // What the wish is now, not what it was at the tap: a tap that landed while this one waited its turn is
            // the state the server should end up in, and it makes no difference which of the two gets here first.
            val wish = prefs.pendingPinChanges.first()[agentId]
            val sentUnder = revisions[agentId] ?: tapRevision
            // Nothing left to say: a call that got here first already sent the final wish and had it acknowledged.
            if (wish == null) return@withLock Result.success(Unit)
            runCatching {
                if (wish) api.pin(listOf(agentId)) else api.unpin(listOf(agentId))
            }.onSuccess {
                if (generation.get() != startedIn) return@onSuccess
                acknowledge(mapOf(agentId to wish), sentUnder)
                _state.update { it.copy(active = true, lastSyncedAtMillis = now(), error = null, pendingCount = pendingCount()) }
            }.onFailure { t ->
                if (t is CancellationException) throw t
                if (generation.get() != startedIn) return@onFailure
                if (t is ConnectRpcException && !t.isUnauthenticated && t.httpCode in 400..499) {
                    // The server will not take this change (the agent is gone, or not the account's); do not keep asking.
                    acknowledge(mapOf(agentId to wish), sentUnder)
                }
                noteFailure(t)
            }.map { }
        }
    }

    /**
     * One round with the server: replays pending changes (and, the first time, the pins made here before syncing
     * existed), then adopts the account's pin list. Returns the failure when a step could not reach or convince the
     * server; pending changes are kept for the next round.
     */
    suspend fun sync(): Result<Unit> {
        val round = work.async { round() }
        return try {
            round.await()
        } catch (e: CancellationException) {
            // A reset cancelled the round; the caller is owed nothing else. Its own cancellation still propagates.
            currentCoroutineContext().ensureActive()
            Result.success(Unit)
        }
    }

    private suspend fun round(): Result<Unit> = serverMutex.withLock {
        if (!sessionUsable()) {
            _state.update { it.copy(active = false, isSyncing = false) }
            return Result.success(Unit)
        }
        val startedIn = generation.get()
        // The account list is read either way: it also carries the pull request states (see [onList]). Only the pins
        // themselves are subject to the setting.
        val pinsEnabled = prefs.pinSyncEnabled.first()
        _state.update { it.copy(active = pinsEnabled, isSyncing = pinsEnabled) }
        try {
            val known = agents.state.value.agents.mapTo(HashSet()) { it.id }
            var refused: ConnectRpcException? = null
            if (pinsEnabled) {
                val migrating = !prefs.pinsMigrated.first()
                val pending = prefs.pendingPinChanges.first().toMutableMap()
                if (migrating) {
                    prefs.localAgentState.first().pinnedIds.filter { it in known && it !in pending }.forEach { pending[it] = true }
                }
                refused = replay(pending, startedIn)
                if (generation.get() != startedIn) return Result.success(Unit)
                if (migrating) prefs.setPinsMigrated(true)
            }

            val list = api.list()
            if (generation.get() != startedIn) return Result.success(Unit)
            runCatching { onList(list) }.onFailure { if (it is CancellationException) throw it }
            if (!pinsEnabled) return Result.success(Unit)
            val server = list.pinned
            if (server.loaded) {
                // Changes made while this round was in flight are not in the server's answer yet; they win.
                val late = prefs.pendingPinChanges.first()
                val merged = (server.ids + late.filterValues { it }.keys) - late.filterValues { !it }.keys
                if (generation.get() != startedIn) return Result.success(Unit)
                if (merged != prefs.localAgentState.first().pinnedIds) prefs.setPinnedIds(merged)
                materialize(merged.filterNot { it in known }, startedIn)
            }
            if (generation.get() != startedIn) return Result.success(Unit)
            _state.update {
                it.copy(isSyncing = false, lastSyncedAtMillis = now(), error = refused?.let(::describe), pendingCount = pendingCount())
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (generation.get() != startedIn) return Result.success(Unit)
            noteFailure(t)
            Result.failure(t)
        }
    }

    /**
     * Sends the pending changes, pins first. A change the server refuses outright (the agent is gone, or not the
     * account's) is dropped rather than retried forever; the first such refusal is returned for the status line.
     */
    private suspend fun replay(pending: Map<String, Boolean>, startedIn: Int): ConnectRpcException? {
        if (pending.isEmpty()) return null
        val refusedPin = send(pending.filterValues { it }, startedIn) { api.pin(it.keys) }
        val refusedUnpin = send(pending.filterValues { !it }, startedIn) { api.unpin(it.keys) }
        return refusedPin ?: refusedUnpin
    }

    private suspend fun send(
        wishes: Map<String, Boolean>,
        startedIn: Int,
        call: suspend (Map<String, Boolean>) -> Unit,
    ): ConnectRpcException? {
        if (wishes.isEmpty()) return null
        // Captured before the call: a tap that lands while it is in flight raises these and keeps its wish pending.
        val sent = wishes.keys.associateWith { revisions[it] ?: 0L }
        val refused = try {
            call(wishes)
            null
        } catch (e: ConnectRpcException) {
            if (e.isUnauthenticated || e.httpCode !in 400..499) throw e
            e
        }
        if (generation.get() != startedIn) return refused
        prefs.clearAcknowledgedPinChanges(wishes.filterKeys { (revisions[it] ?: 0L) == sent.getValue(it) })
        return refused
    }

    /** Forgets [wishes] the server took, unless a later tap has raised the revision they were sent under. */
    private suspend fun acknowledge(wishes: Map<String, Boolean>, sentUnder: Long) {
        val stillCurrent = wishes.filterKeys { (revisions[it] ?: 0L) == sentUnder }
        if (stillCurrent.isNotEmpty()) prefs.clearAcknowledgedPinChanges(stillCurrent)
    }

    /**
     * Rows for pinned agents the list window does not include, so the Pinned group is complete. More of them than
     * one round's budget are worked through over successive rounds; an id whose fetch failed goes behind the ones
     * that have not been tried yet, so it never keeps them from arriving.
     */
    private suspend fun materialize(ids: List<String>, startedIn: Int) {
        val order = synchronized(deferred) {
            deferred.retainAll(ids.toSet())
            ids.filterNot { it in deferred } + deferred.toList()
        }
        for (id in order.take(maxMaterialized)) {
            if (generation.get() != startedIn) return
            val loaded = agents.loadDetail(id).isSuccess
            synchronized(deferred) {
                deferred -= id
                if (!loaded) deferred += id
            }
        }
    }

    /** The account service can be asked at all: a real backend, and no failure that retrying cannot fix. */
    private fun sessionUsable(): Boolean = !session.isDemo && !halted

    private suspend fun eligible(): Boolean = sessionUsable() && prefs.pinSyncEnabled.first()

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
