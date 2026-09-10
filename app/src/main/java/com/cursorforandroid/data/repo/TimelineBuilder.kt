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
import com.cursorforandroid.domain.SummaryRow
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock

object TimelineBuilder {

    /**
     * Interleaves the legacy transcript with v1 runs. Each `user_message` begins a run, so a run footer
     * ("Worked 3m 5s") is placed right before the next user message, and after the final assistant
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
        /** Runs whose prompt the server has not filed yet (placeholders): their user message reads as pending. */
        pending: Set<String> = emptySet(),
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
            // Anything but a running turn is over as far as this build can tell, including a status it cannot read:
            // a footer says so, where none would leave the turn looking unfinished forever.
            if (run != null && !run.statusEnum().isActive) items += footer(run)
        }

        fun resultReply(run: RunDto) = listOfNotNull(run.result?.takeIf { it.isNotBlank() }?.let { AssistantMessage("res-${run.id}", it) })

        if (messages.isEmpty()) {
            ordered.forEach { run -> closeRun(run, resultReply(run)) }
            return items.withUniqueIds()
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
                    val startedAt = run?.let { parseIsoMillis(it.createdAt) }
                    // A turn Cursor injected (a goal continuing, a subagent's report) starts a run like any prompt,
                    // but is shown as the notification it is rather than as something the user said.
                    items += SystemNotifications.parse(msg.id, msg.text, startedAt)?.items
                        ?: listOf(UserMessage(msg.id, msg.text, startedAt, attachments = run?.let { attachments[it.id] } ?: emptyList(), isPending = run != null && run.id in pending))
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
     *
     * [taken] are the ids of a list this one is appended to, so appending a tail to a prefix already made unique
     * gives the same result as making the whole thing unique in one pass.
     */
    fun List<TimelineItem>.withUniqueIds(taken: Set<String> = emptySet()): List<TimelineItem> {
        val seen: HashSet<String> = if (taken.isEmpty()) HashSet(size) else HashSet(taken)
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
        is NoticeCard -> copy(id = id)
        is SystemNotification -> copy(id = id)
        is RunFooter -> copy(id = id)
    }

    fun footer(run: RunDto) = RunFooter(
        id = "run-${run.id}",
        runId = run.id,
        status = run.statusEnum(),
        durationMs = run.durationMs,
        branches = run.git.toBranches(),
    )

    /** The [ToolCall] a `tool_call` event shows as; see [ToolCallMapper] for the wording and for what is not kept. */
    fun toolCall(dto: SseToolCallDto): ToolCall = ToolCallMapper.from(dto)

    /**
     * Accumulates the SSE events of one run into timeline items. The agent's reasoning and tool calls between two
     * messages collect, in order, into one [ActivityGroup] — a subagent it delegates to is a tool call like any other,
     * as it is on the desktop. [timed] measures how long each thought streamed for ("Thought 3s"); turn it off when
     * the events are a replay rather than happening now.
     *
     * A stream [RunStreamEvent.Error] is about the connection, not the run — the run keeps going on the server while
     * the client reconnects — so it adds nothing to the items. Its message is kept: when the run then ends in
     * `ERROR` without a final text, it is the only account of what went wrong (the run record carries none).
     */
    class LiveRun(
        private val runId: String,
        private val timed: Boolean = true,
        /** When the run started, for the footer of a run whose outcome reports no duration of its own. */
        private val startedAtMillis: Long? = null,
        private val nowProvider: () -> Long = AppClock::now,
    ) {
        private val items = mutableListOf<TimelineItem>()
        private var seq = 0
        private var thinkingStartedAt: Long? = null
        private var streamError: RunStreamEvent.Error? = null
        /**
         * Text arrives a token at a time, so it accumulates in a builder and is written into its item only when
         * something is about to read it. Materialising the reply on every delta copies the whole of it each time,
         * which over a long answer costs more than everything else the run does put together.
         */
        private var assistantText = StringBuilder()
        private var assistantIndex = -1
        private var assistantPending = false
        private var thinkingText = StringBuilder()
        private var thinkingGroup = -1
        private var thinkingPending = false
        /** The agent's to-do list as of its last update, so the next update can say what changed. */
        private var todos: List<ToolCallMapper.Todo>? = null

        /**
         * Where each tool call's group sits in [items]. A call is reported at least twice — running, then its
         * outcome — and the update has to find the group it went into; without this, every one of those events walks
         * the whole transcript, so streaming a run costs more the longer the chat already is. Items are only ever
         * appended or replaced in place, so an index once taken stays right.
         */
        private val callGroup = HashMap<String, Int>()
        var status: RunStatus = RunStatus.RUNNING
            private set
        var finished: Boolean = false
            private set
        /** Content events applied so far (text, thinking, tool calls): how far along the run's story this is. */
        var applied: Int = 0
            private set

        fun snapshot(): List<TimelineItem> {
            flushText()
            return items.toList()
        }

        private fun nextId(prefix: String) = "$prefix-$runId-${seq++}"

        private fun flushText() {
            flushAssistant()
            flushThinking()
        }

        private fun flushAssistant() {
            if (!assistantPending) return
            assistantPending = false
            val last = items.getOrNull(assistantIndex) as? AssistantMessage ?: return
            items[assistantIndex] = last.copy(markdown = assistantText.toString())
        }

        private fun flushThinking() {
            if (!thinkingPending) return
            thinkingPending = false
            val group = items.getOrNull(thinkingGroup) as? ActivityGroup ?: return
            val last = group.steps.lastOrNull() as? ThinkingBlock ?: return
            items[thinkingGroup] = group.copy(steps = group.steps.dropLast(1) + last.copy(text = thinkingText.toString()))
        }

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
            if (last is AssistantMessage && last.isStreaming && assistantIndex == items.lastIndex) {
                assistantText.append(text)
                assistantPending = true
            } else {
                flushAssistant()
                assistantText = StringBuilder(text)
                assistantIndex = items.size
                items += AssistantMessage(nextId("asst"), text, isStreaming = true)
            }
        }

        /** Index of the group still collecting steps: the latest one, unless a message or notice has closed it since. */
        private fun openGroupIndex(): Int = if (items.lastOrNull() is ActivityGroup) items.lastIndex else -1

        /** Replaces the last step of the open group, which is what a streaming thought always is. */
        private fun replaceLastStep(idx: Int, group: ActivityGroup, step: ActivityStep) {
            items[idx] = group.copy(steps = group.steps.dropLast(1) + step)
        }

        private fun addStep(step: ActivityStep) {
            flushThinking()
            val idx = openGroupIndex()
            val group = items.getOrNull(idx) as? ActivityGroup
            if (group != null) items[idx] = group.copy(steps = group.steps + step) else items += ActivityGroup(nextId("activity"), listOf(step))
            if (step is ToolCall) callGroup[step.callId] = items.lastIndex
        }

        private fun appendThinking(text: String) {
            if (text.isEmpty()) return
            val idx = openGroupIndex()
            val group = items.getOrNull(idx) as? ActivityGroup
            val last = group?.steps?.lastOrNull()
            if (group != null && last is ThinkingBlock && last.isStreaming && thinkingGroup == idx) {
                thinkingText.append(text)
                thinkingPending = true
            } else {
                thinkingStartedAt = nowProvider()
                addStep(ThinkingBlock(text, isStreaming = true))
                thinkingText = StringBuilder(text)
                thinkingGroup = openGroupIndex()
            }
        }

        private fun closeThinking() {
            flushThinking()
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
            val call = ToolCallMapper.from(dto, todos)
            if (!call.isRunning) ToolCallMapper.todos(dto)?.let { todos = it }
            // A status update lands on the call it reports on, wherever that is; a new call joins the open group.
            val idx = callGroup[call.callId] ?: -1
            if (idx >= 0) {
                val group = items[idx] as ActivityGroup
                items[idx] = group.copy(steps = group.steps.map { if (it is ToolCall && it.callId == call.callId) call else it })
            } else {
                addStep(call)
            }
        }

        /**
         * The run is over, so nothing in it is still happening: text and thinking stop streaming, and a tool call the
         * stream never reported the end of (its `completed` event was lost with the connection, or the run was cut
         * short) stops spinning. A run that finished did finish its tools; any other ending interrupted them.
         */
        private fun closeStreaming(status: RunStatus) {
            closeThinking()
            flushAssistant()
            val toolStatus = if (status == RunStatus.FINISHED) ToolCall.STATUS_COMPLETED else ToolCall.STATUS_INTERRUPTED
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
            // The notification computes a duration from the run's own timestamps when the outcome carries none; the
            // footer said nothing at all about the same run. Only for a run ending now: a replay's clock is not its.
            val elapsed = startedAtMillis?.takeIf { timed && it > 0 }?.let { nowProvider() - it }?.takeIf { it > 0 }
            items += RunFooter(nextId("run"), runId, event.status, event.durationMs ?: elapsed, event.git.toBranches())
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
