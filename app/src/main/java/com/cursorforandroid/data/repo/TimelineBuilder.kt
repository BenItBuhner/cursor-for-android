package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.DateHeader
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.Subagent
import com.cursorforandroid.domain.SubagentsCard
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolActivity
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.ZoneId

object TimelineBuilder {

    /**
     * Interleaves the legacy transcript with v1 runs. Each `user_message` begins a run, so a run footer
     * ("Worked 3m 5s" + branches) is placed right before the next user message, and after the final assistant
     * message when that run has finished.
     *
     * The transcript only carries text. When a run's stream has been replayed (or is being followed live), its
     * items in [traces] — thinking, tool calls, subagents, the assistant text and the footer — stand in for the
     * transcript's replies to that prompt.
     */
    fun fromHistory(
        messages: List<V0ConversationMessageDto>,
        runs: List<RunDto>,
        traces: Map<String, List<TimelineItem>> = emptyMap(),
        nowMillis: Long = AppClock.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TimelineItem> {
        val ordered = runs.sortedBy { parseIsoMillis(it.createdAt) }
        val items = mutableListOf<TimelineItem>()

        /** Everything a run produced after its prompt: the trace when there is one, else the text replies + footer. */
        fun closeRun(run: RunDto?, replies: List<TimelineItem>) {
            val trace = run?.let { traces[it.id] }
            if (trace != null) {
                items += trace
                return
            }
            items += replies
            if (run != null && run.statusEnum().isTerminal) items += footer(run)
        }

        fun resultReply(run: RunDto) = listOfNotNull(run.result?.takeIf { it.isNotBlank() }?.let { AssistantMessage("res-${run.id}", it) })

        if (messages.isEmpty()) {
            ordered.forEach { run ->
                items += DateHeader("hdr-${run.id}", TimeFormat.conversationStamp(parseIsoMillis(run.createdAt), nowMillis, zone))
                closeRun(run, resultReply(run))
            }
            return items
        }
        var userIndex = -1
        var replies = mutableListOf<TimelineItem>()
        messages.forEach { msg ->
            when (msg.type) {
                "user_message" -> {
                    if (userIndex >= 0) closeRun(ordered.getOrNull(userIndex), replies) else items += replies
                    replies = mutableListOf()
                    userIndex++
                    val run = ordered.getOrNull(userIndex)
                    val stamp = run?.let { parseIsoMillis(it.createdAt) }
                    if (stamp != null) items += DateHeader("hdr-${msg.id}", TimeFormat.conversationStamp(stamp, nowMillis, zone))
                    items += UserMessage(msg.id, msg.text, stamp)
                }
                else -> replies += AssistantMessage(msg.id, msg.text)
            }
        }
        if (userIndex >= 0) closeRun(ordered.getOrNull(userIndex), replies) else items += replies
        // Runs without a matching transcript message (e.g. transcript truncated) still surface their result.
        ordered.drop(userIndex + 1).forEach { run -> closeRun(run, resultReply(run)) }
        return items
    }

    fun footer(run: RunDto) = RunFooter(
        id = "run-${run.id}",
        runId = run.id,
        status = run.statusEnum(),
        durationMs = run.durationMs,
        branches = run.git.toBranches(),
    )

    fun toolCall(dto: SseToolCallDto): ToolCall {
        val kind = ToolNames.kindOf(dto.name)
        return ToolCall(
            callId = dto.callId,
            name = dto.name,
            kind = kind,
            status = dto.status,
            summary = summarizeArgs(kind, dto.args),
            args = dto.args,
            result = dto.result,
        )
    }

    private val ARG_KEYS = listOf(
        "path", "target_file", "file", "relative_workspace_path", "target_directory", "pattern", "query", "search_term",
        "command", "description", "url", "glob_pattern", "name", "prompt",
    )

    fun summarizeArgs(kind: ToolKind, args: JsonElement?): String {
        val obj = args as? JsonObject ?: return prettyToolFallback(kind)
        for (key in ARG_KEYS) {
            val value = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) return value.lines().first().take(140)
        }
        return prettyToolFallback(kind)
    }

    private fun prettyToolFallback(kind: ToolKind) = when (kind) {
        ToolKind.Read -> "file"
        ToolKind.List -> "directory"
        ToolKind.Search -> "codebase"
        ToolKind.Edit -> "file"
        ToolKind.Shell -> "command"
        ToolKind.Web -> "web"
        ToolKind.Task -> "subagent"
        ToolKind.Mcp -> "MCP tool"
        ToolKind.Other -> "tool"
    }

    fun subagent(dto: SseToolCallDto): Subagent {
        val obj = dto.args as? JsonObject
        val kind = (obj?.get("subagent_type") as? JsonPrimitive)?.contentOrNull ?: "Agent"
        val title = (obj?.get("description") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("prompt") as? JsonPrimitive)?.contentOrNull?.lines()?.first()?.take(80)
            ?: "Subagent"
        return Subagent(
            id = dto.callId,
            title = title,
            kind = kind.replaceFirstChar { it.uppercase() }.let { if (it == "Explore") "Explorer" else it },
            status = if (dto.status == "running") "Running" else "Done",
        )
    }

    /**
     * Accumulates the SSE events of one run into timeline items. [timed] measures how long each thinking block
     * streamed for ("Thought for 3s"); turn it off when the events are a replay rather than happening now.
     */
    class LiveRun(
        private val runId: String,
        private val timed: Boolean = true,
        private val nowProvider: () -> Long = AppClock::now,
    ) {
        private val items = mutableListOf<TimelineItem>()
        private var seq = 0
        private var thinkingStartedAt: Long? = null
        private var assistantText = StringBuilder()
        var status: RunStatus = RunStatus.RUNNING
            private set
        var finished: Boolean = false
            private set

        fun snapshot(): List<TimelineItem> = items.toList()

        private fun nextId(prefix: String) = "$prefix-$runId-${seq++}"

        fun apply(event: RunStreamEvent) {
            when (event) {
                is RunStreamEvent.Status -> status = event.status
                is RunStreamEvent.Assistant -> appendAssistant(event.text)
                is RunStreamEvent.Thinking -> appendThinking(event.text)
                is RunStreamEvent.ToolCall -> applyTool(event.call)
                is RunStreamEvent.Result -> finish(event)
                is RunStreamEvent.Error -> {
                    closeStreaming()
                    items += NoticeCard(nextId("err"), "Stream error", event.message.ifBlank { event.code }, NoticeTone.Error)
                }
                RunStreamEvent.Heartbeat, RunStreamEvent.Done -> Unit
            }
        }

        private fun appendAssistant(text: String) {
            if (text.isEmpty()) return
            closeThinking()
            val last = items.lastOrNull()
            if (last is AssistantMessage && last.isStreaming) {
                assistantText.append(text)
                items[items.lastIndex] = last.copy(markdown = assistantText.toString())
            } else {
                assistantText = StringBuilder(text)
                items += AssistantMessage(nextId("asst"), text, isStreaming = true)
            }
        }

        private fun appendThinking(text: String) {
            if (text.isEmpty()) return
            val last = items.lastOrNull()
            if (last is ThinkingBlock && last.isStreaming) {
                items[items.lastIndex] = last.copy(text = last.text + text)
            } else {
                thinkingStartedAt = nowProvider()
                items += ThinkingBlock(nextId("think"), text, isStreaming = true)
            }
        }

        private fun closeThinking() {
            val idx = items.indexOfLast { it is ThinkingBlock }
            if (idx < 0) return
            val block = items[idx] as ThinkingBlock
            if (!block.isStreaming) return
            val started = thinkingStartedAt?.takeIf { timed }
            val duration = started?.let { ((nowProvider() - it) / 1000).coerceAtLeast(1) }
            items[idx] = block.copy(isStreaming = false, durationSeconds = duration)
            thinkingStartedAt = null
        }

        private fun applyTool(dto: SseToolCallDto) {
            closeThinking()
            val kind = ToolNames.kindOf(dto.name)
            if (kind == ToolKind.Task) {
                val sub = subagent(dto)
                val idx = items.indexOfLast { it is SubagentsCard }
                val card = items.getOrNull(idx) as? SubagentsCard
                if (card != null && (idx == items.lastIndex || card.subagents.any { it.id == sub.id })) {
                    val list = if (card.subagents.any { it.id == sub.id }) card.subagents.map { if (it.id == sub.id) sub else it } else card.subagents + sub
                    items[idx] = card.copy(subagents = list)
                } else {
                    items += SubagentsCard(nextId("subs"), listOf(sub))
                }
                return
            }
            val call = toolCall(dto)
            val idx = items.indexOfLast { it is ToolActivity }
            val activity = items.getOrNull(idx) as? ToolActivity
            val existing = activity?.calls?.any { it.callId == call.callId } == true
            if (activity != null && (idx == items.lastIndex || existing)) {
                val calls = if (existing) activity.calls.map { if (it.callId == call.callId) call else it } else activity.calls + call
                items[idx] = activity.copy(calls = calls)
            } else {
                items += ToolActivity(nextId("tools"), listOf(call))
            }
        }

        private fun closeStreaming() {
            closeThinking()
            items.replaceAll {
                when (it) {
                    is AssistantMessage -> it.copy(isStreaming = false)
                    is ThinkingBlock -> it.copy(isStreaming = false)
                    else -> it
                }
            }
        }

        private fun finish(event: RunStreamEvent.Result) {
            closeStreaming()
            status = event.status
            finished = true
            val finalText = event.text?.trim().orEmpty()
            val hasAssistant = items.any { it is AssistantMessage && it.markdown.isNotBlank() }
            if (finalText.isNotEmpty() && !hasAssistant) items += AssistantMessage(nextId("asst"), finalText)
            if (event.status == RunStatus.ERROR) items += NoticeCard(nextId("notice"), "Run failed", finalText.ifBlank { null }, NoticeTone.Error)
            if (event.status == RunStatus.CANCELLED) items += NoticeCard(nextId("notice"), "Run cancelled", null, NoticeTone.Warning)
            items += RunFooter(nextId("run"), runId, event.status, event.durationMs, event.git.toBranches())
        }
    }
}
