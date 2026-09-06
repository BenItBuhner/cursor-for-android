package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.DateHeader
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.TimeFormat
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * Owns the transcript + live stream for each open agent. History comes from `/v0/agents/{id}/conversation`
 * and `/v1/agents/{id}/runs`; anything happening right now arrives over the run's SSE stream.
 */
class ConversationRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val prefs: PreferencesStore,
) {
    private inner class Entry(agentId: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(ConversationState(agentId))
        var history: List<TimelineItem> = emptyList()
        var live: TimelineBuilder.LiveRun? = null
        var streamJob: Job? = null
        var attached = 0
    }

    private val entries = mutableMapOf<String, Entry>()

    private fun entry(agentId: String): Entry = synchronized(entries) { entries.getOrPut(agentId) { Entry(agentId) } }

    fun state(agentId: String): StateFlow<ConversationState> = entry(agentId).state.asStateFlow()

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
            e.state.update { it.copy(isStreaming = false) }
        }
    }

    fun forget(agentId: String) {
        synchronized(entries) { entries.remove(agentId) }?.scope?.cancel()
    }

    fun reload(agentId: String) {
        val e = entry(agentId)
        e.streamJob?.cancel()
        e.scope.launch { load(e, agentId) }
    }

    private suspend fun load(e: Entry, agentId: String) {
        val api = session.current.api
        e.state.update { it.copy(isLoading = true, error = null) }
        try {
            coroutineScope {
                val conversation = async { runCatching { api.conversationV0(agentId) } }
                val runs = async { runCatching { api.listRuns(agentId, limit = 50).items } }
                val detail = async { agents.loadDetail(agentId) }
                val convResult = conversation.await()
                val runList = runs.await().getOrElse { emptyList() }
                detail.await()
                val messages = convResult.getOrNull()?.messages ?: emptyList()
                val transcriptUnavailable = convResult.isFailure && convResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                val latest = runList.maxByOrNull { parseIsoMillis(it.createdAt) }
                val latestStatus = latest?.statusEnum()
                e.history = TimelineBuilder.fromHistory(messages, runList)
                e.live = null
                e.state.update {
                    it.copy(
                        items = e.history,
                        isLoading = false,
                        error = if (convResult.isFailure && runList.isEmpty()) convResult.exceptionOrNull()?.userMessage() else null,
                        activeRunId = latest?.id,
                        runStatus = latestStatus,
                        transcriptUnavailable = transcriptUnavailable && messages.isEmpty(),
                    )
                }
                agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                if (latest != null && latestStatus?.isActive == true) startStreaming(e, agentId, latest)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            e.state.update { it.copy(isLoading = false, error = t.userMessage()) }
        }
    }

    private fun startStreaming(e: Entry, agentId: String, run: RunDto) {
        e.streamJob?.cancel()
        val live = TimelineBuilder.LiveRun(run.id)
        e.live = live
        e.state.update { it.copy(activeRunId = run.id, runStatus = RunStatus.parse(run.status), isStreaming = true, items = e.history + live.snapshot()) }
        e.streamJob = e.scope.launch {
            try {
                session.current.streamer.stream(agentId, run.id).collect { event ->
                    live.apply(event)
                    e.state.update { it.copy(items = e.history + live.snapshot(), runStatus = live.status) }
                    if (event is RunStreamEvent.Result) onRunFinished(agentId, event)
                }
            } finally {
                if (live.finished) {
                    e.history = e.history + live.snapshot()
                    e.live = null
                }
                e.state.update { it.copy(isStreaming = false, items = e.history + (e.live?.snapshot() ?: emptyList())) }
                if (!live.finished) {
                    // Stream ended without a terminal event (expired, unauthorized, …): fall back to polling the run once.
                    runCatching { session.current.api.getRun(agentId, run.id) }.getOrNull()?.let { latest ->
                        if (latest.statusEnum().isTerminal) {
                            e.history = e.history + live.snapshot() + TimelineBuilder.footer(latest)
                            e.live = null
                            e.state.update { it.copy(items = e.history, runStatus = latest.statusEnum()) }
                            agents.patch(agentId) { a -> a.copy(runStatus = latest.statusEnum(), lifecycle = AgentLifecycle.IDLE, durationMs = latest.durationMs, branches = latest.git.toBranches().ifEmpty { a.branches }) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun onRunFinished(agentId: String, event: RunStreamEvent.Result) {
        val now = System.currentTimeMillis()
        agents.patch(agentId) { a ->
            a.copy(
                runStatus = event.status,
                lifecycle = AgentLifecycle.IDLE,
                durationMs = event.durationMs ?: a.durationMs,
                branches = event.git.toBranches().ifEmpty { a.branches },
                summary = event.text ?: a.summary,
                updatedAtMillis = now,
            )
        }
        prefs.markRead(agentId, now)
    }

    suspend fun sendFollowUp(agentId: String, text: String): Result<Unit> {
        val e = entry(agentId)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val now = System.currentTimeMillis()
        val optimistic = listOf(
            DateHeader("hdr-local-$now", TimeFormat.conversationStamp(now)),
            UserMessage("local-$now", trimmed, now),
        )
        // Fold any finished live output into history before appending the new prompt.
        e.live?.takeIf { it.finished }?.let { e.history = e.history + it.snapshot(); e.live = null }
        e.history = e.history + optimistic
        e.state.update { it.copy(items = e.history + (e.live?.snapshot() ?: emptyList()), error = null) }

        val result = agents.followUp(agentId, trimmed)
        return result.fold(
            onSuccess = { run ->
                startStreaming(e, agentId, run)
                Result.success(Unit)
            },
            onFailure = { t ->
                e.history = e.history.filterNot { it.id == "hdr-local-$now" || it.id == "local-$now" }
                e.state.update { it.copy(items = e.history + (e.live?.snapshot() ?: emptyList()), error = t.userMessage()) }
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
}
