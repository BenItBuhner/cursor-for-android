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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One shared live stream per (agent, run). The conversation screen and the live-notification monitor both
 * subscribe here, so a run is streamed exactly once no matter how many observers it has. The hub also owns the
 * terminal bookkeeping every observer relied on: when a stream is gone for good it polls the run until it is
 * terminal, it folds the outcome into the cached agent row, and it reports the finish on [finishes] so the
 * transcript learns of it even when no screen was watching.
 *
 * A stream is a connection, not the run: it drops when the phone changes networks, when the agent's machine wakes
 * from hibernation after a follow-up (`stream_unavailable`), or when the server's side of it fails
 * (`upstream_error`). None of that ends the run, so none of it ends the hub's interest. While a run has subscribers
 * the hub keeps coming back — first with the run record, which is the only thing that can say the run is over,
 * then with a fresh connection resumed from the last event it saw, waiting a little longer each time.
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
    /** How many reads of a status this build does not recognise are tolerated before the run is settled anyway. */
    private val maxUnrecognisedPolls: Int = 3,
    private val releaseGraceMs: Long = 5_000L,
    /** First wait before reconnecting to a dropped stream; doubles per consecutive drop up to [reconnectMaxMs]. */
    private val reconnectBaseMs: Long = 1_000L,
    private val reconnectMaxMs: Long = 30_000L,
    /** How long a rebuilt pass may hold the published trace still before it is let through anyway (see [Entry.catchUp]). */
    private val catchUpTimeoutMs: Long = 20_000L,
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
        /**
         * The terminal `result` arrived on the stream itself, so [items] are the run's whole story. False when the
         * stream broke and the outcome was read from the run record instead: whatever happened in between never
         * arrived, and a [replay] reads the retained log from the start to fill it in.
         */
        val streamed: Boolean = false,
        /**
         * The connection dropped and the hub is on its way back to it. [items] are the story up to the drop; the run
         * itself is still going as far as anyone knows. Cleared by the first event of the next connection.
         */
        val reconnecting: Boolean = false,
    ) {
        /** True when [items] are the run's complete story as the stream told it, ending with its own `result` event. */
        val hasTrace: Boolean get() = finished && !expired && streamed
    }

    private inner class Entry(val agentId: String, val runId: String, startedAt: Long) {
        var live = TimelineBuilder.LiveRun(runId, nowProvider = nowProvider, startedAtMillis = startedAt)
        val state = MutableStateFlow(Snapshot(agentId = agentId, runId = runId, startedAtMillis = startedAt))
        var subscribers = 0
        var job: Job? = null
        var releaseJob: Job? = null
        /**
         * Content events the previous connection had applied. A connection that starts over replays the run from its
         * first event, and nothing is published until it has caught up with this, so the reader never sees the trace
         * collapse and grow back.
         */
        var catchUp = 0
        /** When [catchUp] was armed; a replay that never reaches it must not hold the trace still for ever. */
        var catchUpArmedAt = 0L
    }

    /** What one connection to the stream came to. */
    private class Pass {
        /** The connection delivered part of the run's story (text, thinking or a tool call), not just framing. */
        var progressed = false
        /** Why the connection ended before the run did; null when the run finished, or when the flow just ended. */
        var error: RunStreamEvent.Error? = null
    }

    private val entries = LinkedHashMap<String, Entry>()

    private val _finishes = MutableSharedFlow<Snapshot>(extraBufferCapacity = 64)
    /**
     * The terminal snapshot of every run this hub followed live to its end — through its own `result` or, after
     * the stream broke, through the run record. Reading history ([replay]) is not a finish and is not reported.
     */
    val finishes: SharedFlow<Snapshot> = _finishes.asSharedFlow()

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
     * its own `result` is answered from memory; otherwise the stream is replayed once, and the result is kept for
     * the next caller. Check [Snapshot.hasTrace] on the answer: [Snapshot.expired] means the log is gone for good,
     * and neither flag means the stream could not be read right now (a later call tries again).
     */
    suspend fun replay(agentId: String, runId: String, startedAtMillis: Long? = null): Snapshot {
        var snapshot = pass(agentId, runId, startedAtMillis)
        // A live pass that ended on the run record left an incomplete trace; a second pass reads the retained log whole.
        if (snapshot.finished && !snapshot.streamed && !snapshot.expired) snapshot = pass(agentId, runId, startedAtMillis)
        return snapshot
    }

    private suspend fun pass(agentId: String, runId: String, startedAtMillis: Long?): Snapshot {
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

    /**
     * The mode of a pass is the mode of the subscriber that started it: a live subscriber follows the run and
     * settles its outcome, a replay reads history. A subscriber joining a pass of the other kind rides along.
     */
    private fun acquire(agentId: String, runId: String, startedAtMillis: Long?, replay: Boolean): Entry = synchronized(entries) {
        evictIfNeeded()
        val entry = entries.getOrPut(key(agentId, runId)) { Entry(agentId, runId, startedAtMillis ?: nowProvider()) }
        if (startedAtMillis != null && startedAtMillis < entry.state.value.startedAtMillis) {
            entry.state.update { it.copy(startedAtMillis = startedAtMillis) }
        }
        entry.subscribers++
        entry.releaseJob?.cancel()
        entry.releaseJob = null
        val snapshot = entry.state.value
        // A run followed live to an outcome read from the record has an incomplete trace; only a replay reads the
        // log again for it. An expired log stays expired: only live subscribers have anything left to learn (through
        // polling).
        val incomplete = !snapshot.finished || (replay && !snapshot.streamed)
        if (entry.job == null && incomplete && !(replay && snapshot.expired)) {
            // A fresh connection replays the run from its first event, so start from an empty accumulator. Replayed
            // events arrive as fast as the network delivers them, so thinking durations measured against the clock
            // would be fiction; only a live pass times them.
            restartAccumulator(entry, timed = !replay)
            entry.job = scope.launch { stream(entry, historical = replay) }
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

    /**
     * Follows the run until it finishes or the job is cancelled. Every connection that ends early is answered the
     * same way: the run record decides whether there is anything left to stream; if there is, the next connection
     * resumes where the last one stopped (or starts over when the server rejected that position).
     *
     * A [historical] pass reads a finished run's log and makes one attempt, publishing only its end, so a failed
     * read never replaces a snapshot with a fragment: connection trouble is not part of the run's story, an expired
     * log is reported instead of polled around, and the agent row is left alone. History that cannot be read right
     * now is simply not there yet, and the caller asks again later.
     */
    private suspend fun stream(entry: Entry, historical: Boolean) {
        val self = currentCoroutineContext()[Job]
        val backend = session.current
        var resumeFrom: String? = null
        var drops = 0
        try {
            while (currentCoroutineContext().isActive && owns(entry, self) && !entry.live.finished) {
                val pass = follow(entry, self, backend, resumeFrom, historical)
                if (!owns(entry, self)) break
                if (entry.live.finished || historical) break
                if (pass.error?.isFatal == true) {
                    // This stream will never say more (its log expired, or we may not read it); only the record can
                    // tell how the run ends.
                    pollUntilTerminal(entry, self, backend)
                    break
                }
                // The connection is gone for now. Whether the run is too, the record knows; if not, come back to the
                // stream — right away after a healthy connection, more patiently after repeated failures.
                if (settleFromRecord(entry, self, backend) == Record.Over) break
                drops = if (pass.progressed) 1 else drops + 1
                resumeFrom = pass.error?.resumeFrom
                if (resumeFrom == null) restartAccumulator(entry, timed = true)
                entry.state.update { it.copy(reconnecting = true) }
                delay(reconnectDelay(drops))
            }
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            // The record could not be read and the loop gave up on this pass; a later subscriber starts a fresh one.
        }
        // Whether the run finished or the loop ended, a later subscriber may start a fresh connection.
        synchronized(entries) { if (entry.job === self) entry.job = null }
    }

    /**
     * One connection: applies what arrives and reports how it ended. Never throws except to cancel.
     *
     * The accumulator is taken once, here: cancelling a coroutine does not stop it, so a pass whose subscriber left
     * can still be inside this collect when the next one restarts the stream, and it must not write the story of an
     * abandoned connection into the accumulator that replaced its own. [owns] keeps it out of the entry as well.
     */
    private suspend fun follow(entry: Entry, self: Job?, backend: CursorBackend, resumeFrom: String?, historical: Boolean): Pass {
        val pass = Pass()
        val live = entry.live
        try {
            backend.streamer.stream(entry.agentId, entry.runId, resumeFrom).collect { event ->
                if (!owns(entry, self)) return@collect
                when (event) {
                    is RunStreamEvent.Error -> {
                        // The last event of the pass. An expired log is a fact about the run; the rest is about the
                        // connection, and the accumulator only keeps the message in case the run ends in ERROR.
                        pass.error = event
                        if (event.isExpired) entry.state.update { it.copy(expired = true) }
                        live.apply(event)
                    }
                    is RunStreamEvent.Result -> {
                        live.apply(event)
                        finish(entry, event, historical, streamed = true)
                    }
                    else -> {
                        if (event is RunStreamEvent.Assistant || event is RunStreamEvent.Thinking || event is RunStreamEvent.ToolCall) pass.progressed = true
                        live.apply(event)
                        if (!historical) publish(entry)
                    }
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            // A streamer that blew up mid-pass is a dropped connection whose position is unknown.
            pass.error = pass.error ?: RunStreamEvent.Error("stream_failed", t.message ?: "The run's stream failed.", resumeFrom = null)
        }
        return pass
    }

    /** What one read of the run record said about whether there is still anything to follow. */
    private enum class Record { Over, Running, Unreadable, Unrecognised }

    /**
     * Reads the run record and, when the run is over, finishes the entry from it: the final reply, duration and
     * branches the record carries stand in for the `result` event the stream never delivered.
     */
    private suspend fun settleFromRecord(entry: Entry, self: Job?, backend: CursorBackend): Record {
        val run = runCatching { backend.api.getRun(entry.agentId, entry.runId) }.getOrNull() ?: return Record.Unreadable
        val status = run.statusEnum()
        if (status.isActive) return Record.Running
        if (!status.isTerminal) return Record.Unrecognised
        if (!owns(entry, self)) return Record.Over
        val result = RunStreamEvent.Result(run.id, status, run.result, run.durationMs, run.git)
        entry.live.apply(result)
        // The record says when the run ended; that, not the moment this connection happened to read it, is the finish.
        finish(entry, result, historical = false, streamed = false, finishedAtMillis = parseIsoMillis(run.updatedAt).takeIf { it > 0 })
        return Record.Over
    }

    /**
     * For runs whose stream is gone for good: the record is the only source left, so it is read until the run is
     * terminal, for as long as anyone is subscribed. Giving up earlier would leave a screen saying "Working…" about
     * a run that finished an hour ago.
     *
     * A record that keeps reporting a status this build does not know is the exception. It is neither running nor
     * over as far as the client can tell, so following it forever would poll for the life of the process and leave
     * the screen working; after [maxUnrecognisedPolls] such reads the run is settled as [RunStatus.UNKNOWN].
     */
    private suspend fun pollUntilTerminal(entry: Entry, self: Job?, backend: CursorBackend) {
        entry.state.update { it.copy(reconnecting = false) }
        var unrecognised = 0
        while (currentCoroutineContext().isActive && owns(entry, self) && !entry.live.finished) {
            when (settleFromRecord(entry, self, backend)) {
                Record.Over -> return
                Record.Unrecognised -> if (++unrecognised >= maxUnrecognisedPolls) return settleUnrecognised(entry, self)
                else -> unrecognised = 0
            }
            delay(pollIntervalMs)
        }
    }

    /** Ends a run the server keeps describing in terms this build cannot read: the trace stands, the turn is over. */
    private fun settleUnrecognised(entry: Entry, self: Job?) {
        if (!owns(entry, self)) return
        val result = RunStreamEvent.Result(entry.runId, RunStatus.UNKNOWN, text = null, durationMs = null, git = null)
        entry.live.apply(result)
        finish(entry, result, historical = false, streamed = false)
    }

    /**
     * The next connection replays the run from its first event, so the story is rebuilt from nothing; what is
     * published stays as it is until the rebuild has caught up (see [Entry.catchUp]).
     */
    private fun restartAccumulator(entry: Entry, timed: Boolean) {
        entry.catchUp = entry.live.applied
        entry.catchUpArmedAt = nowProvider()
        entry.live = TimelineBuilder.LiveRun(entry.runId, timed = timed, nowProvider = nowProvider, startedAtMillis = entry.state.value.startedAtMillis)
    }

    /** True while this coroutine is still the entry's stream; a cancelled one may not touch anything shared. */
    private fun owns(entry: Entry, self: Job?): Boolean = synchronized(entries) { entry.job === self }

    private fun reconnectDelay(drops: Int): Long = (reconnectBaseMs shl (drops - 1).coerceIn(0, 10)).coerceAtMost(reconnectMaxMs)

    private fun publish(entry: Entry) {
        // A pass that replays fewer events than the last one applied would otherwise hold the trace still — and
        // "Reconnecting…" on — for the rest of the run, although the stream is perfectly healthy.
        if (entry.live.applied < entry.catchUp && nowProvider() - entry.catchUpArmedAt < catchUpTimeoutMs) return
        entry.catchUp = 0
        entry.state.update { it.copy(items = entry.live.snapshot(), status = entry.live.status, eventCount = it.eventCount + 1, reconnecting = false) }
    }

    /**
     * Publishes the terminal snapshot first, then patches the agent row with the same timestamp, so a subscriber
     * that reacts to `finished` before the row changes still knows the `updatedAt` the list is about to show. The
     * finish is reported last, once both are in place. The moment of the finish is [finishedAtMillis] when the caller
     * read it off the run record, else now — right for a result that just arrived on a stream being followed live,
     * and the only choice when a stream's `result` carries no timestamp. The stamp is what the row shows until the
     * next list refresh reconciles it with the server's `updatedAt` (see `reconcileUpdatedAt`), so a finish read off
     * an old record puts the row where the server has it, not at the top of the list.
     *
     * A replay changes nothing about the agent: the row already reflects this run, or a later one. Nor does a result
     * that arrives once the row has moved on to a newer run (a record read after a follow-up was sent, a stream read
     * to its end after the list picked up a run started elsewhere): marking that row idle and finished would show the
     * running follow-up as done. Only the branches, which are per-agent state, are still worth taking.
     */
    private fun finish(entry: Entry, result: RunStreamEvent.Result, historical: Boolean, streamed: Boolean, finishedAtMillis: Long? = null) {
        val finishedAt = finishedAtMillis ?: nowProvider()
        entry.catchUp = 0
        entry.state.update {
            it.copy(
                items = entry.live.snapshot(),
                status = result.status,
                finished = true,
                eventCount = it.eventCount + 1,
                result = result,
                // A replay of a run that already finished here keeps the moment it did.
                finishedAtMillis = it.finishedAtMillis ?: finishedAt,
                streamed = streamed,
                reconnecting = false,
            )
        }
        if (historical) return
        agents.patch(entry.agentId) { a ->
            if (a.latestRunId != null && a.latestRunId != entry.runId) return@patch a.copy(branches = a.branches.ifEmpty { result.git.toBranches() })
            a.copy(
                runStatus = result.status,
                lifecycle = AgentLifecycle.IDLE,
                durationMs = result.durationMs ?: a.durationMs,
                branches = result.git.toBranches().ifEmpty { a.branches },
                summary = result.text?.takeIf { it.isNotBlank() } ?: a.summary,
                updatedAtMillis = finishedAt,
            )
        }
        _finishes.tryEmit(entry.state.value)
    }

    private companion object {
        const val MAX_ENTRIES = 32
    }
}
