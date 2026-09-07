package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ConversationState(
    val agentId: String,
    val items: List<TimelineItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val activeRunId: String? = null,
    val runStatus: RunStatus? = null,
    val isStreaming: Boolean = false,
    val transcriptUnavailable: Boolean = false,
)

/**
 * Owns the transcript for each open agent. History comes from `/v0/agents/{id}/conversation` and
 * `/v1/agents/{id}/runs`; anything happening right now arrives through the [LiveRunHub], which shares one SSE
 * stream per run with the live-notification monitor.
 *
 * The legacy transcript is text only. Every run's thinking, tool calls and subagents live in its event log, which
 * the hub replays for finished runs as long as the API retains it, so the same trace the live view showed is
 * there to dig into after the fact. Runs whose log has expired keep their text.
 */
class ConversationRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val prefs: PreferencesStore,
    private val hub: LiveRunHub,
) {
    /**
     * Everything shown for one agent is derived from three inputs, so a trace that lands, a follow-up that is sent
     * or a live snapshot that arrives all rebuild the same way. Mutations happen under the entry's monitor.
     */
    private inner class Entry(agentId: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(ConversationState(agentId))
        /** The legacy transcript, plus an optimistic prompt until the server confirms the follow-up. */
        var messages: List<V0ConversationMessageDto> = emptyList()
        /** The v1 runs, plus a placeholder for a follow-up in flight. */
        var runs: List<RunDto> = emptyList()
        /** Per run: the items its stream produced, replayed for finished runs and updated live for the active one. */
        var traces: Map<String, List<TimelineItem>> = emptyMap()
        var streamJob: Job? = null
        var traceJob: Job? = null
        var attached = 0

        fun items(): List<TimelineItem> = TimelineBuilder.fromHistory(messages, runs, traces)
    }

    private val entries = mutableMapOf<String, Entry>()

    private fun entry(agentId: String): Entry = synchronized(entries) { entries.getOrPut(agentId) { Entry(agentId) } }

    fun state(agentId: String): StateFlow<ConversationState> = entry(agentId).state.asStateFlow()

    /** True while at least one conversation screen shows this agent. */
    fun isAttached(agentId: String): Boolean = synchronized(entries) { (entries[agentId]?.attached ?: 0) > 0 }

    /** Attach a screen. Loads history on first attach and keeps streaming while at least one screen is attached. */
    fun attach(agentId: String) {
        val e = entry(agentId)
        e.attached++
        if (e.attached == 1) {
            e.scope.launch { load(e, agentId) }
        }
    }

    fun detach(agentId: String) {
        val e = entry(agentId)
        e.attached = (e.attached - 1).coerceAtLeast(0)
        if (e.attached == 0) {
            e.streamJob?.cancel()
            e.streamJob = null
            e.traceJob?.cancel()
            e.traceJob = null
            e.state.update { it.copy(isStreaming = false) }
        }
    }

    fun forget(agentId: String) {
        synchronized(entries) { entries.remove(agentId) }?.scope?.cancel()
    }

    fun reload(agentId: String) {
        val e = entry(agentId)
        e.streamJob?.cancel()
        e.traceJob?.cancel()
        e.scope.launch { load(e, agentId) }
    }

    /** Rebuilds the visible items from the entry's inputs after [mutate] has changed them. */
    private inline fun Entry.publish(mutate: Entry.() -> Unit = {}, transform: ConversationState.() -> ConversationState = { this }) {
        synchronized(this) {
            mutate()
            state.update { it.copy(items = items()).transform() }
        }
    }

    private suspend fun load(e: Entry, agentId: String) {
        val api = session.current.api
        e.state.update { it.copy(isLoading = true, error = null) }
        try {
            coroutineScope {
                val conversation = async { runCatching { api.conversationV0(agentId) } }
                val runPage = async { runCatching { api.listRuns(agentId, limit = 50).items } }
                val detail = async { agents.loadDetail(agentId) }
                val convResult = conversation.await()
                val runList = runPage.await().getOrElse { emptyList() }
                detail.await()
                val transcript = convResult.getOrNull()?.messages ?: emptyList()
                val transcriptUnavailable = convResult.isFailure && convResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                val latest = runList.maxByOrNull { parseIsoMillis(it.createdAt) }
                val latestStatus = latest?.statusEnum()
                // Traces already known are kept: a finished run's log does not change, and the active run's is
                // overwritten by the first live snapshot.
                e.publish(
                    mutate = {
                        messages = transcript
                        runs = runList
                    },
                    transform = {
                        copy(
                            isLoading = false,
                            error = if (convResult.isFailure && runList.isEmpty()) convResult.exceptionOrNull()?.userMessage() else null,
                            activeRunId = latest?.id,
                            runStatus = latestStatus,
                            transcriptUnavailable = transcriptUnavailable && transcript.isEmpty(),
                        )
                    },
                )
                agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                if (latest != null && latestStatus?.isActive == true) startStreaming(e, agentId, latest)
                loadTraces(e, agentId, runList.filter { it.statusEnum().isTerminal })
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            e.state.update { it.copy(isLoading = false, error = t.userMessage()) }
        }
    }

    /**
     * Replays the retained stream of every finished run that has no trace yet, newest first: the retention window
     * is time-based, so once one run's log has expired every older run's has too and the rest are skipped. A few
     * workers pull from the ordered queue, so the newest runs are always the first ones asked.
     */
    private fun loadTraces(e: Entry, agentId: String, finishedRuns: List<RunDto>) {
        e.traceJob?.cancel()
        val pending = synchronized(e) { finishedRuns.filter { it.id !in e.traces } }.sortedByDescending { parseIsoMillis(it.createdAt) }
        if (pending.isEmpty()) return
        e.traceJob = e.scope.launch {
            val next = AtomicInteger(0)
            val newestExpired = AtomicLong(Long.MIN_VALUE)
            repeat(minOf(MAX_PARALLEL_REPLAYS, pending.size)) {
                launch {
                    while (true) {
                        val run = pending.getOrNull(next.getAndIncrement()) ?: return@launch
                        val createdAt = parseIsoMillis(run.createdAt)
                        if (createdAt < newestExpired.get()) continue
                        val snapshot = hub.replay(agentId, run.id, createdAt.takeIf { it > 0 })
                        when {
                            snapshot.hasTrace -> e.publish(mutate = { traces = traces + (run.id to snapshot.items) })
                            snapshot.expired -> newestExpired.updateAndGet { maxOf(it, createdAt) }
                        }
                    }
                }
            }
        }
    }

    private fun startStreaming(e: Entry, agentId: String, run: RunDto) {
        e.streamJob?.cancel()
        e.publish(transform = { copy(activeRunId = run.id, runStatus = RunStatus.parse(run.status), isStreaming = true) })
        e.streamJob = e.scope.launch {
            val startedAt = parseIsoMillis(run.createdAt).takeIf { it > 0 }
            hub.snapshots(agentId, run.id, startedAt)
                .transformWhile { snapshot -> emit(snapshot); !snapshot.finished }
                .collect { snapshot ->
                    // The live snapshot is this run's trace; the builder places it after the run's prompt.
                    e.publish(
                        mutate = { traces = traces + (run.id to snapshot.items) },
                        transform = { copy(runStatus = snapshot.status, isStreaming = !snapshot.finished) },
                    )
                    if (snapshot.finished) {
                        // The screen is open, so the finished turn counts as read. finishedAtMillis is the updatedAt
                        // the hub writes to the agent row, whether or not that patch has landed yet.
                        prefs.markRead(agentId, snapshot.finishedAtMillis ?: AppClock.now())
                    }
                }
        }
    }

    suspend fun sendFollowUp(agentId: String, text: String, images: List<PromptImage> = emptyList()): Result<Unit> {
        val e = entry(agentId)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val now = AppClock.now()
        // The prompt shows up right away, paired with a placeholder run so it gets a timestamp like every other turn.
        val localId = "local-$now"
        val nowIso = Instant.ofEpochMilli(now).toString()
        val placeholder = RunDto(id = localId, agentId = agentId, status = RunStatus.CREATING.name, createdAt = nowIso, updatedAt = nowIso)
        e.publish(
            mutate = {
                messages = messages + V0ConversationMessageDto(localId, "user_message", trimmed)
                runs = runs + placeholder
            },
            transform = { copy(error = null) },
        )

        val result = agents.followUp(agentId, trimmed, images)
        return result.fold(
            onSuccess = { run ->
                e.publish(mutate = { runs = runs.map { if (it.id == localId) run else it } })
                startStreaming(e, agentId, run)
                Result.success(Unit)
            },
            onFailure = { t ->
                e.publish(
                    mutate = {
                        messages = messages.filterNot { it.id == localId }
                        runs = runs.filterNot { it.id == localId }
                    },
                    transform = { copy(error = t.userMessage()) },
                )
                Result.failure(t)
            },
        )
    }

    suspend fun cancelActiveRun(agentId: String): Result<Unit> {
        val e = entry(agentId)
        val runId = e.state.value.activeRunId ?: return Result.failure(IllegalStateException("No active run."))
        return agents.cancelRun(agentId, runId).onSuccess {
            e.state.update { it.copy(runStatus = RunStatus.CANCELLED) }
        }
    }

    fun clearError(agentId: String) = entry(agentId).state.update { it.copy(error = null) }

    fun resetAll() {
        synchronized(entries) {
            entries.values.forEach { it.scope.cancel() }
            entries.clear()
        }
    }

    private companion object {
        /** Finished runs replayed at once; older logs mostly answer with `410 stream_expired`, which is cheap. */
        const val MAX_PARALLEL_REPLAYS = 3
    }
}
