package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.ActivityStep
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.Subagent
import com.cursorforandroid.domain.SubagentsCard
import com.cursorforandroid.domain.SummaryRow
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object TimelineBuilder {

    /**
     * Interleaves the legacy transcript with v1 runs. Each `user_message` begins a run, so a run footer
     * ("Worked 3m 5s" + branches) is placed right before the next user message, and after the final assistant
     * message when that run has finished.
     *
     * The transcript only carries text. When a run's stream has been replayed (or is being followed live), its
     * items in [traces] — thinking, tool calls, subagents, the assistant text and the footer — stand in for the
     * transcript's replies to that prompt. The images a prompt carried live in [attachments] (kept on this device,
     * keyed by run ID) and are attached to the user message that started each run.
     */
    fun fromHistory(
        messages: List<V0ConversationMessageDto>,
        runs: List<RunDto>,
        traces: Map<String, List<TimelineItem>> = emptyMap(),
        attachments: Map<String, List<MessageAttachment>> = emptyMap(),
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
            ordered.forEach { run -> closeRun(run, resultReply(run)) }
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
                    items += UserMessage(msg.id, msg.text, run?.let { parseIsoMillis(it.createdAt) }, attachments = run?.let { attachments[it.id] } ?: emptyList())
                }
                else -> replies += AssistantMessage(msg.id, msg.text)
            }
        }
        if (userIndex >= 0) closeRun(ordered.getOrNull(userIndex), replies) else items += replies
        // Runs without a matching transcript message (e.g. transcript truncated) still surface their result.
        ordered.drop(userIndex + 1).forEach { run -> closeRun(run, resultReply(run)) }
        return items.withUniqueIds()
    }

    /**
     * Ids double as `LazyColumn` keys and `rememberSaveable` keys, both of which abort on a repeat, and the ones
     * above come straight from the transcript and run records; a repeated server id gets a positional suffix.
     */
    fun List<TimelineItem>.withUniqueIds(): List<TimelineItem> {
        val seen = HashSet<String>(size)
        return map { item ->
            var id = item.id
            var n = 1
            while (!seen.add(id)) id = "${item.id}#${++n}"
            if (id == item.id) item else item.withId(id)
        }
    }

    private fun TimelineItem.withId(id: String): TimelineItem = when (this) {
        is UserMessage -> copy(id = id)
        is AssistantMessage -> copy(id = id)
        is SummaryRow -> copy(id = id)
        is ActivityGroup -> copy(id = id)
        is SubagentsCard -> copy(id = id)
        is NoticeCard -> copy(id = id)
        is RunFooter -> copy(id = id)
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
     * Accumulates the SSE events of one run into timeline items. The agent's reasoning and tool calls between two
     * messages collect, in order, into one [ActivityGroup]. [timed] measures how long each thought streamed for
     * ("thought for 3s"); turn it off when the events are a replay rather than happening now.
     *
     * A stream [RunStreamEvent.Error] is about the connection, not the run — the run keeps going on the server while
     * the client reconnects — so it adds nothing to the items. Its message is kept: when the run then ends in
     * `ERROR` without a final text, it is the only account of what went wrong (the run record carries none).
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
        private var streamError: RunStreamEvent.Error? = null
        var status: RunStatus = RunStatus.RUNNING
            private set
        var finished: Boolean = false
            private set
        /** Content events applied so far (text, thinking, tool calls): how far along the run's story this is. */
        var applied: Int = 0
            private set

        fun snapshot(): List<TimelineItem> = items.toList()

        private fun nextId(prefix: String) = "$prefix-$runId-${seq++}"

        fun apply(event: RunStreamEvent) {
            when (event) {
                is RunStreamEvent.Status -> status = event.status
                is RunStreamEvent.Assistant -> { applied++; appendAssistant(event.text) }
                is RunStreamEvent.Thinking -> { applied++; appendThinking(event.text) }
                is RunStreamEvent.ToolCall -> { applied++; applyTool(event.call) }
                is RunStreamEvent.Result -> finish(event)
                is RunStreamEvent.Error -> if (!event.isExpired) streamError = event
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

        /**
         * Index of the group still collecting steps: the latest one, unless a message or notice has closed it since.
         * Subagent cards sit after the group they were delegated from and leave it open, so the agent's own work
         * between two replies stays one row however often it delegates.
         */
        private fun openGroupIndex(): Int {
            val idx = items.indexOfLast { it is ActivityGroup }
            if (idx < 0) return -1
            for (i in idx + 1 until items.size) if (items[i] !is SubagentsCard) return -1
            return idx
        }

        /** Replaces the last step of the open group, which is what a streaming thought always is. */
        private fun replaceLastStep(idx: Int, group: ActivityGroup, step: ActivityStep) {
            items[idx] = group.copy(steps = group.steps.dropLast(1) + step)
        }

        private fun addStep(step: ActivityStep) {
            val idx = openGroupIndex()
            val group = items.getOrNull(idx) as? ActivityGroup
            if (group != null) items[idx] = group.copy(steps = group.steps + step) else items += ActivityGroup(nextId("activity"), listOf(step))
        }

        private fun appendThinking(text: String) {
            if (text.isEmpty()) return
            val idx = openGroupIndex()
            val group = items.getOrNull(idx) as? ActivityGroup
            val last = group?.steps?.lastOrNull()
            if (group != null && last is ThinkingBlock && last.isStreaming) {
                replaceLastStep(idx, group, last.copy(text = last.text + text))
            } else {
                thinkingStartedAt = nowProvider()
                addStep(ThinkingBlock(text, isStreaming = true))
            }
        }

        private fun closeThinking() {
            val idx = openGroupIndex()
            val group = items.getOrNull(idx) as? ActivityGroup ?: return
            val last = group.steps.lastOrNull() as? ThinkingBlock ?: return
            if (!last.isStreaming) return
            val started = thinkingStartedAt?.takeIf { timed }
            val duration = started?.let { ((nowProvider() - it) / 1000).coerceAtLeast(1) }
            replaceLastStep(idx, group, last.copy(isStreaming = false, durationSeconds = duration))
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
            // A status update lands on the call it reports on, wherever that is; a new call joins the open group.
            val idx = items.indexOfLast { it is ActivityGroup && it.calls.any { c -> c.callId == call.callId } }
            if (idx >= 0) {
                val group = items[idx] as ActivityGroup
                items[idx] = group.copy(steps = group.steps.map { if (it is ToolCall && it.callId == call.callId) call else it })
            } else {
                addStep(call)
            }
        }

        /**
         * The run is over, so nothing in it is still happening: text and thinking stop streaming, and a tool call or
         * subagent the stream never reported the end of (its `completed` event was lost with the connection, or the
         * run was cut short) stops spinning. A run that finished did finish its tools; any other ending interrupted them.
         */
        private fun closeStreaming(status: RunStatus) {
            closeThinking()
            val toolStatus = if (status == RunStatus.FINISHED) ToolCall.STATUS_COMPLETED else ToolCall.STATUS_INTERRUPTED
            val subagentStatus = if (status == RunStatus.FINISHED) "Done" else "Stopped"
            items.replaceAll {
                when (it) {
                    is AssistantMessage -> it.copy(isStreaming = false)
                    is ActivityGroup -> it.copy(
                        steps = it.steps.map { step ->
                            when {
                                step is ThinkingBlock && step.isStreaming -> step.copy(isStreaming = false)
                                step is ToolCall && step.isRunning -> step.copy(status = toolStatus)
                                else -> step
                            }
                        },
                    )
                    is SubagentsCard -> if (it.subagents.any { s -> s.status == "Running" }) it.copy(subagents = it.subagents.map { s -> if (s.status == "Running") s.copy(status = subagentStatus) else s }) else it
                    else -> it
                }
            }
        }

        private fun finish(event: RunStreamEvent.Result) {
            closeStreaming(event.status)
            status = event.status
            finished = true
            val finalText = event.text?.trim().orEmpty()
            placeFinalReply(finalText)
            if (event.status == RunStatus.ERROR) {
                // The record has no reason of its own for a failed run; the stream's last error is the only account.
                val reason = finalText.ifBlank { streamError?.message?.ifBlank { null } ?: streamError?.code }
                items += NoticeCard(nextId("notice"), "Run failed", reason, NoticeTone.Error)
            }
            if (event.status == RunStatus.CANCELLED) items += NoticeCard(nextId("notice"), "Run cancelled", null, NoticeTone.Warning)
            items += RunFooter(nextId("run"), runId, event.status, event.durationMs, event.git.toBranches())
        }

        /**
         * The `result` carries the final reply verbatim. When the stream already delivered it as assistant deltas
         * nothing is added; when it did not — the stream broke and the outcome was read from the run record, or
         * the reply only ever came with the result, after intermediate remarks between tool calls — it is appended,
         * and a reply the stream cut off half-way is completed in place.
         */
        private fun placeFinalReply(finalText: String) {
            if (finalText.isEmpty()) return
            val wanted = normalize(finalText)
            if (items.any { it is AssistantMessage && normalize(it.markdown) == wanted }) return
            val last = items.lastOrNull()
            if (last is AssistantMessage && wanted.startsWith(normalize(last.markdown))) {
                items[items.lastIndex] = last.copy(markdown = finalText)
                return
            }
            items += AssistantMessage(nextId("asst"), finalText)
        }

        private fun normalize(text: String) = text.trim().replace(WHITESPACE, " ")
    }

    private val WHITESPACE = Regex("\\s+")
}
