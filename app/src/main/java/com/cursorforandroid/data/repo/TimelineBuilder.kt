package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseInteractionUpdateDto
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
        /** The prompt, by position among [messages]' prompts, the first of [runs] belongs to: the prompts before it have no run in hand. */
        firstRunAt: Int = 0,
        /**
         * Runs whose entry in [traces] is the story their stream told before the follow ended, not the whole trace
         * (see `ConversationRepository.Entry.partial`): the transcript's replies for the run join it — the words the
         * stream never delivered — and the run's footer closes it when the run is over.
         */
        partial: Set<String> = emptySet(),
    ): List<TimelineItem> {
        val ordered = runs.sortedBy { parseIsoMillis(it.createdAt) }
        /** The run of the [index]th prompt, when it is in hand. */
        fun runAt(index: Int): RunDto? = ordered.getOrNull(index - firstRunAt)
        val items = mutableListOf<TimelineItem>()

        /** Everything a run produced after its prompt: the trace when there is one, else the text replies + footer. */
        fun closeRun(run: RunDto?, replies: List<TimelineItem>) {
            val trace = run?.let { traces[it.id] }
            if (trace != null) {
                items += if (run.id in partial) withReplies(trace, replies) else trace
                // A whole trace ends on its own footer. The story a stream told before it broke does not: the run
                // that ended meanwhile (by its record) gets the record's footer under it, like a run without a trace.
                if (!run.statusEnum().isActive && items.none { it is RunFooter && it.runId == run.id }) items += footer(run)
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
                    if (userIndex >= 0) closeRun(runAt(userIndex), replies) else items += replies
                    replies = mutableListOf()
                    userIndex++
                    val run = runAt(userIndex)
                    val startedAt = run?.let { parseIsoMillis(it.createdAt) }
                    // A turn Cursor injected (a goal continuing, a subagent's report) starts a run like any prompt,
                    // but is shown as the notification it is rather than as something the user said.
                    items += SystemNotifications.parse(msg.id, msg.text, startedAt)?.items
                        ?: listOf(UserMessage(msg.id, msg.text, startedAt, attachments = run?.let { attachments[it.id] } ?: emptyList(), isPending = run != null && run.id in pending))
                }
                else -> replies += AssistantMessage(msg.id, msg.text)
            }
        }
        if (userIndex >= 0) closeRun(runAt(userIndex), replies) else items += replies
        // Runs without a matching transcript message (e.g. transcript truncated) still surface their result.
        ordered.drop((userIndex + 1 - firstRunAt).coerceAtLeast(0)).forEach { run -> closeRun(run, resultReply(run)) }
        return items.withUniqueIds()
    }

    /**
     * A run's story so far with the transcript's [replies] for the run: the words the stream never delivered join
     * the calls and thoughts it did. A reply the story already holds is not said twice, and a reply the story holds
     * cut off — the stream's copy a prefix of the transcript's whole — gives way to the whole. Footers and notices
     * among [replies] follow as they are.
     */
    fun withReplies(story: List<TimelineItem>, replies: List<TimelineItem>): List<TimelineItem> {
        if (replies.isEmpty()) return story
        val told = story.filterIsInstance<AssistantMessage>().map { normalizeText(it.markdown) }.filter { it.isNotEmpty() }
        val texts = replies.filterIsInstance<AssistantMessage>().map { normalizeText(it.markdown) }.filter { it.isNotEmpty() }
        if (told.isEmpty() || texts.isEmpty()) return story + replies
        // A story message the transcript completes (the stream's copy cut off ahead of the transcript's whole) gives
        // way to the whole; a reply the story already has, whole or ahead of it, is not said again. The story's own
        // instances stand wherever the words agree, so nothing on screen is redrawn for the same words.
        val kept = story.filterNot { item -> item is AssistantMessage && normalizeText(item.markdown).let { mine -> mine.isNotEmpty() && texts.any { it != mine && it.startsWith(mine) } } }
        val added = replies.filterNot { item -> item is AssistantMessage && normalizeText(item.markdown).let { theirs -> theirs.isNotEmpty() && told.any { it.startsWith(theirs) } } }
        return kept + added
    }

    private fun normalizeText(text: String) = text.trim().replace(WHITESPACE, " ")

    /**
     * Ids double as `LazyColumn` keys and `rememberSaveable` keys, both of which abort on a repeat, and the ones
     * above come straight from the transcript and run records; a repeated server id gets a positional suffix.
     *
     * [taken] are the ids of a list this one is appended to, so appending a tail to a prefix already made unique
     * gives the same result as making the whole thing unique in one pass.
     */
    fun List<TimelineItem>.withUniqueIds(taken: Set<String> = emptySet()): List<TimelineItem> {
        val seen: HashSet<String> = if (taken.isEmpty()) HashSet(size * 2) else HashSet(taken)
        // The usual case — no id repeats — is answered with the list itself: the callers keep it as it is, and what
        // reads it downstream can tell an unchanged item by identity.
        var first = -1
        for (i in indices) {
            if (!seen.add(this[i].id)) { first = i; break }
        }
        if (first < 0) return this
        // From the first repeat on: its id is in [seen] already, so the loop below gives it its suffix.
        val out = ArrayList<TimelineItem>(size)
        for (i in 0 until first) out += this[i]
        for (i in first until size) {
            val item = this[i]
            var id = item.id
            var n = 1
            while (!seen.add(id)) id = "${item.id}#${++n}"
            out += if (id == item.id) item else item.withId(id)
        }
        return out
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
        // A run that is over was last written to when it ended; a run still going has no end yet.
        endedAtMillis = parseIsoMillis(run.updatedAt).takeIf { it > 0 && run.statusEnum().isTerminal },
        // The record's own word on a failure, when it carries one: the run's final text.
        reason = run.result?.trim()?.takeIf { it.isNotEmpty() && run.statusEnum() == RunStatus.ERROR },
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
        /** When the run started, for the footer of a run the stream saw finish without reporting a duration. */
        private val startedAtMillis: Long? = null,
        /** Where the bytes of an image the agent generates are kept; without one only a small image is kept, inline. */
        private val images: GeneratedImageSink? = null,
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
                // Not counted: the simplified `tool_call` it accompanies is the content event, and a reconnect
                // catches up on those; this only adds to the call it names.
                is RunStreamEvent.Interaction -> applyInteraction(event.update)
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
            val call = ToolCallMapper.from(dto, todos, images)
            if (!call.isRunning) ToolCallMapper.todos(dto)?.let { todos = it }
            // A status update lands on the call it reports on, wherever that is; a new call joins the open group.
            // What an earlier event kept for the call — the payload its `interaction_update` carried, the output the
            // running event had — stays unless this event brings its own.
            replaceCall(call.callId) { existing -> call.inheriting(existing) } ?: addStep(call)
        }

        /**
         * The SDK-shape update for a tool call: joined to the call by id whichever of the two events came first. It
         * carries the typed arguments and result the simplified event may have left out, so the call takes from it
         * whatever it lacks — the payload above all, the output and line counts too — and, when no `tool_call` event
         * has named the call yet, stands in for one until it does.
         */
        private fun applyInteraction(update: SseInteractionUpdateDto) {
            val callId = update.callId ?: return
            val toolCall = update.toolCall ?: return
            val status = if (update.isToolCallCompleted) ToolCall.STATUS_COMPLETED else ToolCall.STATUS_RUNNING
            val dto = SseToolCallDto(callId, toolCall.type, status, toolCall.args, toolCall.result)
            val fromUpdate = ToolCallMapper.from(dto, todos, images)
            replaceCall(callId) { existing ->
                existing.copy(
                    // A completion the simplified event has not reported yet stops the row spinning; it will say the same.
                    status = if (existing.isRunning && !fromUpdate.isRunning) fromUpdate.status else existing.status,
                    payload = fromUpdate.payload ?: existing.payload,
                    output = existing.output ?: fromUpdate.output,
                    exitCode = existing.exitCode ?: fromUpdate.exitCode,
                    linesAdded = existing.linesAdded ?: fromUpdate.linesAdded,
                    linesRemoved = existing.linesRemoved ?: fromUpdate.linesRemoved,
                    isError = existing.isError || fromUpdate.isError,
                )
            } ?: run {
                closeThinking()
                addStep(fromUpdate)
            }
        }

        /** Replaces the call [callId] wherever it sits with [transform] of it; null when no group holds it. */
        private inline fun replaceCall(callId: String, transform: (ToolCall) -> ToolCall): Unit? {
            val idx = callGroup[callId] ?: return null
            val group = items[idx] as ActivityGroup
            items[idx] = group.copy(steps = group.steps.map { if (it is ToolCall && it.callId == callId) transform(it) else it })
            return Unit
        }

        /** This call with whatever [previous] kept that this event did not bring. */
        private fun ToolCall.inheriting(previous: ToolCall): ToolCall = copy(
            payload = payload ?: previous.payload,
            output = output ?: previous.output,
            exitCode = exitCode ?: previous.exitCode,
            linesAdded = linesAdded ?: previous.linesAdded,
            linesRemoved = linesRemoved ?: previous.linesRemoved,
            truncated = truncated ?: previous.truncated,
        )

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
            // A failure is the server's — its status for the run, and no other word — and the footer carries the
            // reason: the final text, else the last error the stream (or the account's record, see
            // HeadlessTranscript) gave. Said as a line of its own by the rows (see TranscriptRows), never a banner;
            // a cancel is the user's — their next message, their stop — and the footer says so quietly.
            val reason = if (event.status == RunStatus.ERROR) finalText.ifBlank { streamError?.message?.ifBlank { null } ?: streamError?.code } else null
            // The duration is the outcome's own. Only a run the stream itself saw finish, watched from its start,
            // gets the clock's word when the outcome carries none. Never an outcome read off a record, and never an
            // end other than finished: "Cancelled after 30s" was this device's clock — from opening the stream to
            // reading a stale record — read as the turn's.
            val elapsed = startedAtMillis?.takeIf { timed && it > 0 && !event.fromRecord && event.status == RunStatus.FINISHED }?.let { nowProvider() - it }?.takeIf { it > 0 }
            // No end time here: the run record's `updatedAt` gives it when the chat is read again (see [footer]),
            // and a footer stamped with this device's clock would differ from the record's copy of the same run.
            items += RunFooter(nextId("run"), runId, event.status, event.durationMs ?: elapsed, event.git.toBranches(), reason = reason)
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
