package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.SseToolCallTruncationDto
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import kotlinx.serialization.json.JsonElement

/**
 * Turns the account's copy of a chat's transcript ([ConversationRecordApi]) into the traces the conversation renders,
 * the same way the documented stream's events are: each turn's thoughts, tool calls (with their payloads) and reply
 * accumulate through [TimelineBuilder.LiveRun], so a turn read from the account looks exactly like one replayed from
 * the run's log. Turns pair with runs by position, oldest first, as the `/v0` transcript's prompts do.
 */
object HeadlessTranscript {

    /**
     * One turn of the record: the prompt that started it and everything the agent did until the next one.
     * [projectMode] says the prompt was sent in Project mode (`agent_mode = AGENT_MODE_PROJECT`): the chat is a coordinator's.
     */
    class Turn(val prompt: String?, val steps: List<HeadlessStep>, val projectMode: Boolean = false)

    /**
     * The last [count] turns of the record, oldest first, read from its end a page at a time until [count] whole turns
     * are in hand (or the record's start is reached). One small probe learns the record's length first. Null when
     * the record is empty.
     */
    suspend fun tailTurns(api: ConversationRecordApi, agentId: String, count: Int, pageSize: Int = PAGE_SIZE): List<Turn>? {
        if (count <= 0) return emptyList()
        val probe = api.fetch(agentId, startIndex = 0, limit = 1)
        val total = probe.totalResponses
        if (total <= 0) return null
        val steps = ArrayDeque<HeadlessStep>()
        var start = total
        var pages = 0
        // A turn is whole once the prompt that starts it is in hand: [count] + 1 prompts bound [count] whole turns.
        while (start > 0 && pages < MAX_PAGES && steps.count { it.userMessage != null } <= count) {
            val from = (start - pageSize).coerceAtLeast(0)
            val page = api.fetch(agentId, startIndex = from, limit = start - from)
            if (page.steps.isEmpty() && from > 0) break
            page.steps.asReversed().forEach { steps.addFirst(it) }
            start = from
            pages++
        }
        val turns = split(steps.toList())
        return turns.takeLast(count)
    }

    /** The record cut into turns at its prompts; steps before the first prompt belong to a turn with none. */
    fun split(steps: List<HeadlessStep>): List<Turn> {
        val turns = ArrayList<Turn>()
        var prompt: String? = null
        var projectMode = false
        var current = ArrayList<HeadlessStep>()
        var started = false
        for (step in steps) {
            if (step.userMessage != null) {
                if (started) turns += Turn(prompt, current, projectMode)
                prompt = step.userMessage
                projectMode = step.projectMode
                current = ArrayList()
                started = true
                continue
            }
            current += step
            started = true
        }
        if (started) turns += Turn(prompt, current, projectMode)
        return turns
    }

    /**
     * The trace of [run] from its [turn]: the record's steps replayed through the same accumulator the stream's events
     * go through, the footer the run's own record gives. Payloads are read off the arguments and result as the
     * stream's `tool_call` events' are (see [ToolCallMapper]).
     */
    fun trace(turn: Turn, run: RunDto, images: GeneratedImageSink? = null): List<TimelineItem> {
        val live = replay(turn, run.id, images)
        live.apply(RunStreamEvent.Result(run.id, run.statusEnum(), run.result, run.durationMs, run.git))
        // The accumulator's footer is what a stream's result event gives; the run's record is the authority here.
        return live.snapshot().filterNot { it is RunFooter } + TimelineBuilder.footer(run)
    }

    /**
     * The turn's trace without a footer, its items named after [key]: what the record says the agent did, closed as
     * a finished turn (a reply the record ends on is complete, a call without its result stays as the record left
     * it). The footer is the run's, or the timing's, to add when either is known (see [RecordTranscript]).
     */
    fun body(turn: Turn, key: String, images: GeneratedImageSink? = null): List<TimelineItem> {
        val live = replay(turn, key, images)
        live.apply(RunStreamEvent.Result(key, RunStatus.FINISHED, null, null, null))
        return live.snapshot().filterNot { it is RunFooter }
    }

    /**
     * What the record has said of one tool call so far: its name and arguments from whichever of its steps carried
     * them, and the pieces of arguments a streamed call — a coordinator's `SendMessage`, written out as the model
     * produces it — arrived in, joined until they read as JSON.
     */
    private class KnownCall {
        var name: String = ""
        var args: JsonElement? = null
        val pieces = StringBuilder()

        /** Whether the call's arguments were streamed and never came whole: the body is not in the record as read. */
        val partial: Boolean get() = args == null && pieces.isNotEmpty()

        fun take(call: HeadlessToolCall) {
            if (call.name.isNotBlank()) name = call.name
            if (call.args != null) args = call.args
            call.rawArgs?.let { piece ->
                // A piece that repeats what came before, with more, is the whole so far; anything else is the next piece.
                if (piece.startsWith(pieces)) pieces.setLength(0)
                pieces.append(piece)
                if (args == null) runCatching { CursorJson.parseToJsonElement(pieces.toString()) }.getOrNull()?.let { args = it }
            }
        }

        /** The stream's event for the call as known: its arguments, or the word that they were cut, when they never came whole. */
        fun event(callId: String, status: String, result: JsonElement? = null): SseToolCallDto =
            SseToolCallDto(callId, name, status, args, result, truncated = if (partial) SseToolCallTruncationDto(args = true) else null)
    }

    private fun replay(turn: Turn, key: String, images: GeneratedImageSink?): TimelineBuilder.LiveRun {
        val live = TimelineBuilder.LiveRun(key, timed = false, images = images)
        val calls = HashMap<String, KnownCall>()
        for (step in turn.steps) {
            when {
                step.thinking != null -> live.apply(RunStreamEvent.Thinking(step.thinking))
                step.text != null -> live.apply(RunStreamEvent.Assistant(step.text))
                step.toolCall != null -> {
                    val known = calls.getOrPut(step.toolCall.callId) { KnownCall() }
                    known.take(step.toolCall)
                    // A piece with nothing new to say — a streamed call's progress, its name and arguments already
                    // known — is not a step of its own; the call stands as it is until the next one adds to it.
                    if (known.name.isBlank() && known.args == null && known.pieces.isEmpty()) continue
                    live.apply(RunStreamEvent.ToolCall(known.event(step.toolCall.callId, ToolCall.STATUS_RUNNING)))
                }
                step.toolResult != null -> {
                    val known = calls[step.toolResult.callId] ?: KnownCall()
                    live.apply(RunStreamEvent.ToolCall(known.event(step.toolResult.callId, ToolCall.STATUS_COMPLETED, step.toolResult.result)))
                }
            }
        }
        return live
    }

    private const val PAGE_SIZE = 200
    /** Pages read back from the record's end for one window at most: a turn is rarely more than a page or two of steps. */
    private const val MAX_PAGES = 12
}
