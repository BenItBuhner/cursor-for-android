package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One shared live stream per (agent, run). The conversation screen and the live-notification monitor both
 * subscribe here, so a run is streamed exactly once no matter how many observers it has. The hub also owns the
 * terminal bookkeeping every observer relied on: when a stream ends without a `result` it polls the run until it
 * is terminal, and it folds the outcome into the cached agent row.
 *
 * The same endpoint serves runs that already finished: the API keeps each run's event log for a retention window
 * and replays it from the first event on a fresh connection, so [replay] rebuilds the thinking / tool / subagent
 * trace of a finished run without any of the live bookkeeping.
 */
class LiveRunHub(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val nowProvider: () -> Long = AppClock::now,
    private val pollIntervalMs: Long = 20_000L,
    private val releaseGraceMs: Long = 5_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Everything known about a run right now. Items are the same timeline entries the conversation renders. */
    data class Snapshot(
        val agentId: String,
        val runId: String,
        val items: List<TimelineItem> = emptyList(),
        val status: RunStatus = RunStatus.RUNNING,
        val finished: Boolean = false,
        /** Stream events applied so far; zero means nothing has arrived for this run yet. */
        val eventCount: Int = 0,
        /** Best-effort start of the run: the caller's timestamp (run `createdAt`) or the first observation. */
        val startedAtMillis: Long,
        val result: RunStreamEvent.Result? = null,
        val finishedAtMillis: Long? = null,
        /**
         * The run's event log is past its retention window (`410 stream_expired`). A live subscriber still gets the
         * terminal state through polling; a [replay] has nothing to rebuild the trace from.
         */
        val expired: Boolean = false,
    ) {
        /** True when [items] are the run's story as the stream told it, ending with its own `result` event. */
        val hasTrace: Boolean get() = finished && !expired
    }

    /**
     * [replay] entries read history rather than follow a run: connection trouble is not part of the run's story,
     * an expired log is reported instead of polled around, and the agent row is left alone.
     */
    private inner class Entry(val agentId: String, val runId: String, startedAt: Long, val replay: Boolean) {
        var live = newAccumulator()
        val state = MutableStateFlow(Snapshot(agentId = agentId, runId = runId, startedAtMillis = startedAt))
        var subscribers = 0
        var job: Job? = null
        var releaseJob: Job? = null

        // A replay arrives as fast as the network delivers it, so thinking durations measured against the clock
        // would be fiction; the accumulator leaves them out.
        fun newAccumulator() = TimelineBuilder.LiveRun(runId, timed = !replay, nowProvider = nowProvider)
    }

    private val entries = LinkedHashMap<String, Entry>()

    init {
        scope.launch { session.backend.drop(1).collect { resetAll() } }
    }

    /**
     * Snapshots of one run, starting with whatever is already known. Collecting keeps the underlying stream alive;
     * the stream is released (after a short grace period) once the last collector leaves.
     */
    fun snapshots(agentId: String, runId: String, startedAtMillis: Long? = null): Flow<Snapshot> = flow {
        val entry = acquire(agentId, runId, startedAtMillis, replay = false)
        try {
            emitAll(entry.state)
        } finally {
            release(entry)
        }
    }

    /**
     * The timeline of a run that already finished, rebuilt from its retained event log. A run this hub followed to
     * its end is answered from memory; otherwise the stream is replayed once, and the result is kept for the next
     * caller. Check [Snapshot.hasTrace] on the answer: [Snapshot.expired] means the log is gone for good, and
     * neither flag means the stream could not be read right now (a later call tries again).
     */
    suspend fun replay(agentId: String, runId: String, startedAtMillis: Long? = null): Snapshot {
        val entry = acquire(agentId, runId, startedAtMillis, replay = true)
        try {
            entry.job?.join()
            return entry.state.value
        } finally {
            release(entry)
        }
    }

    fun current(agentId: String, runId: String): Snapshot? = synchronized(entries) { entries[key(agentId, runId)]?.state?.value }

    fun resetAll() {
        val old = synchronized(entries) { entries.values.toList().also { entries.clear() } }
        old.forEach { it.job?.cancel(); it.releaseJob?.cancel() }
    }

    private fun key(agentId: String, runId: String) = "$agentId/$runId"

    /** The mode is fixed when the entry is created: a live subscriber joining a replay (or vice versa) rides along. */
    private fun acquire(agentId: String, runId: String, startedAtMillis: Long?, replay: Boolean): Entry = synchronized(entries) {
        evictIfNeeded()
        val entry = entries.getOrPut(key(agentId, runId)) { Entry(agentId, runId, startedAtMillis ?: nowProvider(), replay) }
        if (startedAtMillis != null && startedAtMillis < entry.state.value.startedAtMillis) {
            entry.state.update { it.copy(startedAtMillis = startedAtMillis) }
        }
        entry.subscribers++
        entry.releaseJob?.cancel()
        entry.releaseJob = null
        val snapshot = entry.state.value
        // An expired log stays expired: only live subscribers have anything left to learn (through polling).
        if (entry.job == null && !snapshot.finished && !(entry.replay && snapshot.expired)) {
            // A fresh connection replays the run from its first event, so start from an empty accumulator.
            entry.live = entry.newAccumulator()
            entry.job = scope.launch { stream(entry) }
        }
        entry
    }

    private fun release(entry: Entry) = synchronized(entries) {
        entry.subscribers = (entry.subscribers - 1).coerceAtLeast(0)
        if (entry.subscribers == 0 && entry.job != null) {
            entry.releaseJob = scope.launch {
                delay(releaseGraceMs)
                synchronized(entries) {
                    if (entry.subscribers == 0) {
                        entry.job?.cancel()
                        entry.job = null
                    }
                }
            }
        }
    }

    private fun evictIfNeeded() {
        if (entries.size < MAX_ENTRIES) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && entries.size >= MAX_ENTRIES) {
            val candidate = iterator.next().value
            if (candidate.subscribers == 0 && candidate.job == null) iterator.remove()
        }
    }

    private suspend fun stream(entry: Entry) {
        val self = currentCoroutineContext()[Job]
        val backend = session.current
        try {
            backend.streamer.stream(entry.agentId, entry.runId).collect { event ->
                // An expired stream is not part of the run's story; the poll below reads the terminal state instead.
                if (event is RunStreamEvent.Error && event.code == "stream_expired") {
                    entry.state.update { it.copy(expired = true) }
                    return@collect
                }
                // Nor is a connection problem while reading history: the caller keeps the transcript and retries later.
                if (entry.replay && event is RunStreamEvent.Error) return@collect
                entry.live.apply(event)
                if (event is RunStreamEvent.Result) finish(entry, event) else publish(entry)
            }
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            // Treated like a stream that ended early: fall through to polling.
        }
        if (!entry.live.finished && !entry.replay && currentCoroutineContext().isActive) pollUntilTerminal(entry)
        // Whether the run finished or polling gave up, a later subscriber may start a fresh connection.
        synchronized(entries) { if (entry.job === self) entry.job = null }
    }

    private suspend fun pollUntilTerminal(entry: Entry) {
        val api = session.current.api
        var attempts = 0
        while (currentCoroutineContext().isActive && !entry.live.finished && attempts < MAX_POLL_ATTEMPTS) {
            val run = runCatching { api.getRun(entry.agentId, entry.runId) }.getOrNull()
            if (run != null && run.statusEnum().isTerminal) {
                val result = RunStreamEvent.Result(run.id, run.statusEnum(), run.result, run.durationMs, run.git)
                entry.live.apply(result)
                finish(entry, result)
                return
            }
            attempts++
            delay(pollIntervalMs)
        }
    }

    private fun publish(entry: Entry) {
        entry.state.update { it.copy(items = entry.live.snapshot(), status = entry.live.status, eventCount = it.eventCount + 1) }
    }

    /**
     * Publishes the terminal snapshot first, then patches the agent row with the same timestamp, so a subscriber
     * that reacts to `finished` before the row changes still knows the `updatedAt` the list is about to show.
     * A replay changes nothing about the agent: the row already reflects this run, or a later one.
     */
    private fun finish(entry: Entry, result: RunStreamEvent.Result) {
        val now = nowProvider()
        entry.state.update {
            it.copy(items = entry.live.snapshot(), status = result.status, finished = true, eventCount = it.eventCount + 1, result = result, finishedAtMillis = now)
        }
        if (entry.replay) return
        agents.patch(entry.agentId) { a ->
            a.copy(
                runStatus = result.status,
                lifecycle = AgentLifecycle.IDLE,
                durationMs = result.durationMs ?: a.durationMs,
                branches = result.git.toBranches().ifEmpty { a.branches },
                summary = result.text?.takeIf { it.isNotBlank() } ?: a.summary,
                updatedAtMillis = now,
            )
        }
    }

    private companion object {
        const val MAX_ENTRIES = 32
        const val MAX_POLL_ATTEMPTS = 45
    }
}
