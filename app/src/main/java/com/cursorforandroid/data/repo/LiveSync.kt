package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.Agent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background live sync (Settings › Experimental): the chats the reader is most likely to open next are kept current
 * while the app is on screen, so opening one paints the turn as it stands and reads nothing.
 *
 * Which chats ([select]): every running chat, the Project coordinators whose workers are running (their reports
 * land as coordinator turns), and the few chats opened last — up to [maxHeld], coordinators first. A chat that
 * stops running keeps its hold for [lingerMs], for the coordinator's next turn and the steer that follows a finish.
 * Idle chats hold nothing open: the sidebar's own refresh names the chat that starts running, and it is held then.
 *
 * What a hold costs is the chat's run stream while it runs — one SSE stream per running chat, multiplexed on the
 * API's one HTTP/2 connection, bytes the turn would cost when opened anyway, and read once (a stream picked up
 * again resumes from its last event, see `LiveRunHub`) — plus a load when the hold starts, taken one chat at a
 * time so the chat the reader opens is never queued behind them. A held chat at rest holds no stream at all: the
 * account's live watch is kept for the chats on screen (see `ConversationRepository.hold`).
 *
 * Not while the app is in the background: [BACKGROUND_GRACE_MS] after it leaves the screen every hold goes, and the
 * streams with them; a chat running meanwhile is caught up by its delta when the app comes back.
 */
class LiveSync(
    private val target: Target,
    private val scope: CoroutineScope,
    private val maxHeld: Int = MAX_HELD,
    private val lingerMs: Long = LINGER_MS,
    private val backgroundGraceMs: Long = BACKGROUND_GRACE_MS,
    private val settleTimeoutMs: Long = SETTLE_TIMEOUT_MS,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    /** What a hold is taken on: the conversations, seen through the three calls sync needs. */
    interface Target {
        /** Idempotent: holding a chat already held changes nothing. */
        fun hold(agentId: String)
        fun release(agentId: String)
        /** Returns once the chat's hold has done its first load (or failed it). */
        suspend fun settled(agentId: String)
    }

    private val held = LinkedHashSet<String>()
    private val runningUntil = HashMap<String, Long>()
    private val recent = ArrayDeque<String>()
    private val _heldIds = MutableStateFlow<Set<String>>(emptySet())
    /** The chats held right now, for Settings' diagnostics and the tests. */
    val heldIds: StateFlow<Set<String>> = _heldIds.asStateFlow()
    private var job: Job? = null
    /** What sync is doing, for a crash report: not started, off, on, or in the background. */
    @Volatile var mode: String = "not started"
        private set

    /** A screen opened [agentId]: it is among the recent chats kept current after it is left. */
    fun opened(agentId: String) = synchronized(this) {
        recent.remove(agentId)
        recent.addFirst(agentId)
        while (recent.size > RECENT) recent.removeLast()
    }

    /**
     * Runs sync for as long as [scope] lives: holds follow the list while [enabled] and [foreground] both say so.
     * Call once.
     */
    fun start(agents: Flow<List<Agent>>, enabled: Flow<Boolean>, foreground: Flow<Boolean>) {
        if (job != null) return
        job = scope.launch {
            val active = combine(enabled.distinctUntilChanged(), foreground.distinctUntilChanged()) { on, fore -> on to fore }
            combine(active, agents) { mode, list -> mode to list }.collectLatest { (mode, list) ->
                val (on, fore) = mode
                this@LiveSync.mode = if (!on) "off" else if (fore) "on" else "background"
                when {
                    !on -> apply(emptyList())
                    !fore -> {
                        // A quick switch to another app tears nothing down.
                        delay(backgroundGraceMs)
                        apply(emptyList())
                    }
                    else -> apply(select(list))
                }
            }
        }
    }

    /** The chats to hold for [list], now; see the class comment for the policy. */
    fun select(list: List<Agent>): List<String> {
        val now = nowProvider()
        val live = list.filter { !it.isArchived }
        val byId = live.associateBy { it.id }
        val running = live.filter { it.isRunning }.sortedByDescending { it.updatedAtMillis }
        val recentIds = synchronized(this) {
            running.forEach { runningUntil[it.id] = now + lingerMs }
            runningUntil.entries.removeAll { (id, until) -> until < now || id !in byId }
            recent.toList()
        }
        val lingering = synchronized(this) { runningUntil.keys.toList() }
            .filter { id -> byId[id]?.isRunning == false }
            .sortedByDescending { byId[it]?.updatedAtMillis ?: 0L }
        val coordinators = running.mapNotNull { it.parent?.id }.distinct().filter { it in byId } +
            running.filter { it.isProjectRoot }.map { it.id }
        return (coordinators + running.map { it.id } + recentIds.filter { it in byId } + lingering)
            .distinct()
            .take(maxHeld)
    }

    private suspend fun apply(wanted: List<String>) {
        val drop = synchronized(this) { held.filter { it !in wanted } }
        drop.forEach { id ->
            target.release(id)
            synchronized(this) { held.remove(id) }
        }
        publish()
        // One new hold at a time, each after the last one's first load: their reads never crowd out the chat the
        // reader opens (a new list cancels what is left, see [start]).
        for (id in wanted) {
            // Held already: asked again, which costs nothing, for the conversations may have been reset since (a sign-out).
            if (synchronized(this) { id in held }) { target.hold(id); continue }
            target.hold(id)
            synchronized(this) { held += id }
            publish()
            withTimeoutOrNull(settleTimeoutMs) { target.settled(id) }
        }
    }

    private fun publish() {
        _heldIds.value = synchronized(this) { held.toSet() }
    }

    companion object {
        /** Holds at once. The conversations keep 24 chats in memory; the rest stay for the chats the reader opens. */
        const val MAX_HELD = 20
        /** Chats opened last that are kept current after they are left. */
        const val RECENT = 3
        const val LINGER_MS = 120_000L
        const val BACKGROUND_GRACE_MS = 60_000L
        const val SETTLE_TIMEOUT_MS = 20_000L
    }
}
