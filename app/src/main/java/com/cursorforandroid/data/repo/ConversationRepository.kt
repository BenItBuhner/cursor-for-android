package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.StagedAttachments
import com.cursorforandroid.domain.DateHeader
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
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
import kotlinx.coroutines.flow.transformWhile
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
 * Owns the transcript for each open agent. History comes from `/v0/agents/{id}/conversation` and
 * `/v1/agents/{id}/runs`, with the images of each prompt filled in from the on-device [AttachmentStore]; anything
 * happening right now arrives through the [LiveRunHub], which shares one SSE stream per run with the
 * live-notification monitor.
 */
class ConversationRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val prefs: PreferencesStore,
    private val hub: LiveRunHub,
    private val attachments: AttachmentStore,
) {
    private inner class Entry(agentId: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(ConversationState(agentId))
        var history: List<TimelineItem> = emptyList()
        /** Items of the run in progress; folded into [history] the moment the run finishes. */
        var liveItems: List<TimelineItem> = emptyList()
        var streamJob: Job? = null
        var attached = 0
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
                val stored = async { runCatching { attachments.forAgent(agentId) }.getOrDefault(emptyMap()) }
                val convResult = conversation.await()
                val runList = runs.await().getOrElse { emptyList() }
                detail.await()
                val messages = convResult.getOrNull()?.messages ?: emptyList()
                val transcriptUnavailable = convResult.isFailure && convResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                val latest = runList.maxByOrNull { parseIsoMillis(it.createdAt) }
                val latestStatus = latest?.statusEnum()
                e.history = TimelineBuilder.fromHistory(messages, runList, stored.await())
                e.liveItems = emptyList()
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
        e.liveItems = emptyList()
        e.state.update { it.copy(activeRunId = run.id, runStatus = RunStatus.parse(run.status), isStreaming = true, items = e.history) }
        e.streamJob = e.scope.launch {
            val startedAt = parseIsoMillis(run.createdAt).takeIf { it > 0 }
            hub.snapshots(agentId, run.id, startedAt)
                .transformWhile { snapshot -> emit(snapshot); !snapshot.finished }
                .collect { snapshot ->
                    if (snapshot.finished) {
                        e.history = e.history + snapshot.items
                        e.liveItems = emptyList()
                        e.state.update { it.copy(items = e.history, runStatus = snapshot.status, isStreaming = false) }
                        // The screen is open, so the finished turn counts as read. finishedAtMillis is the updatedAt
                        // the hub writes to the agent row, whether or not that patch has landed yet.
                        prefs.markRead(agentId, snapshot.finishedAtMillis ?: AppClock.now())
                    } else {
                        e.liveItems = snapshot.items
                        e.state.update { it.copy(items = e.history + snapshot.items, runStatus = snapshot.status, isStreaming = true) }
                    }
                }
        }
    }

    suspend fun sendFollowUp(agentId: String, text: String, images: List<PromptImage> = emptyList()): Result<Unit> {
        val e = entry(agentId)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val now = AppClock.now()
        val localId = "local-$now"
        // Written before the request so the bubble shows its images from the first frame, like the text. Storage
        // trouble costs the previews, never the send.
        val staged = runCatching { attachments.stage(images) }.getOrDefault(StagedAttachments.EMPTY)
        val optimistic = listOf(
            DateHeader("hdr-$localId", TimeFormat.conversationStamp(now)),
            UserMessage(localId, trimmed, now, staged.attachments),
        )
        e.history = e.history + optimistic
        e.state.update { it.copy(items = e.history + e.liveItems, error = null) }

        val result = agents.followUp(agentId, trimmed, images)
        return result.fold(
            onSuccess = { run ->
                // Filed under the run so the next history load finds them; the bubble follows the files to their new paths.
                val kept = runCatching { attachments.commit(agentId, run.id, staged) }.getOrDefault(staged.attachments)
                e.history = e.history.map { if (it.id == localId && it is UserMessage) it.copy(attachments = kept) else it }
                startStreaming(e, agentId, run)
                Result.success(Unit)
            },
            onFailure = { t ->
                attachments.discard(staged)
                e.history = e.history.filterNot { it.id == "hdr-$localId" || it.id == localId }
                e.state.update { it.copy(items = e.history + e.liveItems, error = t.userMessage()) }
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
