package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.SseToolCallTruncationDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CallShape
import com.cursorforandroid.domain.ProjectDiagnostics
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StepShape
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TurnShape
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
     * [promptShape] is the prompt step's shape, kept for the diagnostics (see [shape]).
     */
    class Turn(
        val prompt: String?,
        val steps: List<HeadlessStep>,
        val projectMode: Boolean = false,
        val promptShape: StepShape? = null,
        /** The turn's whole-chat index when the record was read by turns (see [HeadlessStep.turnIndex]); null for the step-indexed record. */
        val turnIndex: Int? = null,
        /** The prompt was delivered into the turn under way rather than starting one (see [HeadlessStep.steer]). */
        val steer: Boolean = false,
    )

    /**
     * The last [count] turns of the record, oldest first, read from its end a page at a time until [count] whole turns
     * are in hand (or the record's start is reached). One small probe learns the record's length first. Null when
     * the record is empty.
     */
    suspend fun tailTurns(api: ConversationRecordApi, agentId: String, count: Int, pageSize: Int = PAGE_SIZE): List<Turn>? {
        if (count <= 0) return emptyList()
        // The blob-backed record: the newest [count] turns by their blobs (see RecordPager.tailTurns).
        if (api.readsTurns) return RecordPager.tailTurns(api, agentId, count)?.let { split(it.steps) }
        TranscriptPerf.session(agentId).network("record")
        val probe = api.fetch(agentId, startIndex = 0, limit = 1)
        val total = probe.totalResponses
        if (total <= 0) return null
        val steps = ArrayDeque<HeadlessStep>()
        var start = total
        var pages = 0
        // A turn is whole once the prompt that starts it is in hand: [count] + 1 prompts bound [count] whole turns.
        while (start > 0 && pages < MAX_PAGES && steps.count { it.userMessage != null } <= count) {
            val from = (start - pageSize).coerceAtLeast(0)
            TranscriptPerf.session(agentId).network("record")
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
        var promptShape: StepShape? = null
        var projectMode = false
        var steer = false
        var turnIndex: Int? = null
        var current = ArrayList<HeadlessStep>()
        var started = false
        for (step in steps) {
            // A new turn at a prompt — and, for a record read by turns, at a step of the next turn whatever it is: a
            // turn whose prompt could not be read is still a turn of its own, not the previous turn's tail.
            val newTurn = step.userMessage != null || (step.turnIndex != null && turnIndex != null && step.turnIndex != turnIndex)
            if (newTurn) {
                if (started) turns += Turn(prompt, current, projectMode, promptShape, turnIndex, steer)
                prompt = step.userMessage
                promptShape = step.shape?.takeIf { step.userMessage != null }
                projectMode = step.projectMode
                steer = step.steer
                turnIndex = step.turnIndex
                current = ArrayList()
                started = true
                if (step.userMessage != null) continue
            }
            if (turnIndex == null) turnIndex = step.turnIndex
            current += step
            started = true
        }
        if (started) turns += Turn(prompt, current, projectMode, promptShape, turnIndex, steer)
        return turns
    }

    /**
     * The trace of [run] from its [turn]: the record's steps replayed through the same accumulator the stream's events
     * go through, the footer the run's own record gives. Payloads are read off the arguments and result as the
     * stream's `tool_call` events' are (see [ToolCallMapper]).
     */
    fun trace(turn: Turn, run: RunDto, images: GeneratedImageSink? = null): List<TimelineItem> {
        val replayed = replay(turn, run.id, images)
        replayed.live.apply(RunStreamEvent.Result(run.id, run.statusEnum(), run.result, run.durationMs, run.git))
        // The accumulator's footer is what a stream's result event gives; the run's record is the authority here —
        // except for the reason of a failure the record does not carry, which the accumulator read off the
        // record's error step (see [errorMessage]).
        val items = replayed.items()
        val footer = TimelineBuilder.footer(run)
        val reason = footer.reason ?: items.filterIsInstance<RunFooter>().lastOrNull()?.reason
        return items.filterNot { it is RunFooter } + (if (footer.isFailure && reason != null) footer.copy(reason = reason) else footer)
    }

    /**
     * The turn's trace without a footer, its items named after [key]: what the record says the agent did, closed as
     * a finished turn (a reply the record ends on is complete, a call without its result stays as the record left
     * it). The footer is the run's, or the timing's, to add when either is known (see [RecordTranscript]) — and so
     * is the word that the turn failed: an `error` step in the record ([errorMessage]) is the server's account of
     * what went wrong, not of how the run ended; a run that logged one and went on finished, and only the run's own
     * status makes the turn a failure (0.3.26–0.3.40 read the step as one and showed "Run failed" for turns that continued).
     */
    fun body(turn: Turn, key: String, images: GeneratedImageSink? = null): List<TimelineItem> {
        val replayed = replay(turn, key, images)
        replayed.live.apply(RunStreamEvent.Result(key, RunStatus.FINISHED, null, null, null))
        return replayed.items().filterNot { it is RunFooter }
    }

    /**
     * The last error the record logged for the turn (`HeadlessAgenticComposerResponse.error.message`), for the
     * footer's reason when the run's own record says it failed (see `RecordTurn.errorMessage`). Null for a turn
     * without one.
     */
    fun errorMessage(turn: Turn): String? = turn.steps.lastOrNull { it.error != null }?.error?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The turn's calls as the replay resolves them, and its steps' shapes, for the transcript diagnostics (see
     * [TurnShape]): the same joining of pieces [body] does, with no items built.
     */
    fun shape(turn: Turn, stepIndex: Int): TurnShape {
        val calls = LinkedHashMap<String, KnownCall>()
        for (step in turn.steps) {
            step.toolCall?.let { calls.getOrPut(it.callId) { KnownCall() }.take(it) }
            step.toolResult?.let { calls.getOrPut(it.callId) { KnownCall() }.result = true }
        }
        recover(calls)
        val prompt = when {
            turn.prompt == null -> "none"
            SystemNotifications.isInjected(turn.prompt) -> "injected"
            else -> "user"
        }
        val steps = ArrayList<StepShape>(turn.steps.size + 1)
        turn.steps.forEach { step -> step.shape?.let { steps += it } }
        // The prompt's own step is the turn's first; its shape is kept on the turn (see [split]).
        turn.promptShape?.let { steps.add(0, it) }
        return TurnShape(
            stepIndex = stepIndex,
            prompt = prompt,
            projectMode = turn.projectMode,
            steps = steps,
            calls = calls.map { (id, known) -> CallShape(ProjectDiagnostics.tail(id), known.name, known.steps, known.argsWord(), known.result) },
        )
    }

    /**
     * What the record has said of one tool call so far: its name and arguments from whichever of its steps carried
     * them, and the pieces of arguments a streamed call — a coordinator's `SendMessage`, written out as the model
     * produces it — arrived in, joined until they read as JSON. A message call whose pieces never do is read
     * leniently at the end of the turn ([recover]).
     */
    private class KnownCall {
        var name: String = ""
        var args: JsonElement? = null
        val pieces = StringBuilder()
        /** How many steps carried the call, and how many of them a piece of its arguments. */
        var steps = 0
        var pieceCount = 0
        /** Whether the arguments came from joining pieces, and how many it took. */
        var joinedFrom = 0
        /** The lenient reading of pieces that never read as JSON, when one was made (see [MessageRecovery]), and how many pieces it read. */
        var recovered: MessageRecovery.Recovered? = null
        var recoveredPieces = 0
        /** A nameless call whose pieces were read as a message call's (see [recover]): not a call of its own. */
        var absorbed = false
        /** Whether a result was recorded for the call, and the last status and result it was replayed with. */
        var result = false
        var lastStatus: String = ToolCall.STATUS_RUNNING
        var lastResult: JsonElement? = null

        /** Whether the call's arguments were streamed and never came whole: the body is not in the record as read. */
        val partial: Boolean get() = args == null && pieces.isNotEmpty()

        /** The coordinator's word to the user, under any spelling of its tool's name. */
        val isUserMessage: Boolean get() = ToolNames.coordinatorTool(name) == ToolNames.USER_MESSAGE_TOOL

        fun take(call: HeadlessToolCall) {
            steps++
            if (call.name.isNotBlank()) name = call.name
            if (call.args != null) args = call.args
            call.rawArgs?.let { piece ->
                pieceCount++
                // A piece that repeats what came before, with more, is the whole so far; anything else is the next piece.
                if (piece.startsWith(pieces)) pieces.setLength(0)
                pieces.append(piece)
                // Joined pieces are the arguments once they read as a JSON object — an object, since the lenient
                // parser would take a bare word of prose for a string.
                if (args == null) runCatching { CursorJson.parseToJsonElement(pieces.toString()) }.getOrNull()?.takeIf { it is JsonObject }?.let { args = it; joinedFrom = pieceCount }
            }
        }

        /** The stream's event for the call as known: its arguments, or the word that they were cut, when they never came whole. */
        fun event(callId: String, status: String, result: JsonElement? = null): SseToolCallDto {
            lastStatus = status
            if (result != null) lastResult = result
            return SseToolCallDto(callId, name, status, args, result, truncated = if (partial) SseToolCallTruncationDto(args = true) else null)
        }

        /** How the arguments came together, for the diagnostics (see [CallShape.args]). */
        fun argsWord(): String = when {
            absorbed -> "absorbed"
            recovered != null -> "recovered(${recovered!!.method},$recoveredPieces)"
            args != null && joinedFrom > 0 -> "joined($joinedFrom)"
            args != null -> if (args is JsonObject) "json" else "value"
            pieces.isNotEmpty() -> "partial($pieceCount)"
            else -> "none"
        }
    }

    /** What [recover] did: the message calls given a body, and the nameless calls whose pieces it was read from. */
    private class Recovery(val recovered: Set<String>, val absorbed: Set<String>) {
        val isEmpty: Boolean get() = recovered.isEmpty() && absorbed.isEmpty()
    }

    /** The accumulator with what the replay recovered, so the items can be marked (see [items]). */
    private class Replayed(val live: TimelineBuilder.LiveRun, val recovery: Recovery) {
        /** The accumulator's items: a recovered message's payload saying so, the calls it absorbed gone. */
        fun items(): List<TimelineItem> {
            val items = live.snapshot()
            if (recovery.isEmpty) return items
            return items.mapNotNull { item ->
                if (item !is ActivityGroup || item.calls.none { it.callId in recovery.recovered || it.callId in recovery.absorbed }) return@mapNotNull item
                val steps = item.steps.mapNotNull { step ->
                    if (step !is ToolCall) return@mapNotNull step
                    if (step.callId in recovery.absorbed) return@mapNotNull null
                    val payload = step.payload as? ToolPayload.CoordinatorMessage
                    // The body is in hand now: the word that the arguments were cut, kept from the earlier events, goes.
                    if (step.callId in recovery.recovered && payload != null) step.copy(payload = payload.copy(recovered = true), truncated = null) else step
                }
                if (steps.isEmpty()) null else item.copy(steps = steps)
            }
        }
    }

    private fun replay(turn: Turn, key: String, images: GeneratedImageSink?): Replayed {
        val live = TimelineBuilder.LiveRun(key, timed = false, images = images)
        val calls = LinkedHashMap<String, KnownCall>()
        for (step in turn.steps) {
            when {
                step.thinking != null -> live.apply(RunStreamEvent.Thinking(step.thinking))
                step.text != null -> live.apply(RunStreamEvent.Assistant(step.text))
                step.error != null -> live.apply(RunStreamEvent.Error(RECORD_ERROR, step.error))
                step.toolCall != null -> {
                    val known = calls.getOrPut(step.toolCall.callId) { KnownCall() }
                    known.take(step.toolCall)
                    // A piece with nothing new to say — a streamed call's progress, its name and arguments already
                    // known — is not a step of its own; the call stands as it is until the next one adds to it.
                    if (known.name.isBlank() && known.args == null && known.pieces.isEmpty()) continue
                    live.apply(RunStreamEvent.ToolCall(known.event(step.toolCall.callId, ToolCall.STATUS_RUNNING)))
                }
                step.toolResult != null -> {
                    val known = calls.getOrPut(step.toolResult.callId) { KnownCall() }
                    known.result = true
                    live.apply(RunStreamEvent.ToolCall(known.event(step.toolResult.callId, ToolCall.STATUS_COMPLETED, step.toolResult.result)))
                }
            }
        }
        // A message call whose arguments never read as JSON is read leniently now that every piece of the turn is
        // in hand, and replayed once more with what was read: the row shows the body, marked as recovered.
        val recovery = recover(calls)
        for ((id, known) in calls) {
            if (id in recovery.recovered) live.apply(RunStreamEvent.ToolCall(known.event(id, known.lastStatus, known.lastResult)))
        }
        return Replayed(live, recovery)
    }

    /**
     * The coordinator's message calls whose arguments did not parse, read leniently (see [MessageRecovery]): from
     * the call's own pieces first, else from every fragment of the turn that reached no named call, joined in the
     * order they came — a streamed call's pieces filed under another id than the call's. A nameless call whose
     * pieces were read so is absorbed: it was never a call of its own.
     */
    private fun recover(calls: Map<String, KnownCall>): Recovery {
        val wanting = calls.filterValues { it.isUserMessage && it.args == null }
        if (wanting.isEmpty()) return Recovery(emptySet(), emptySet())
        val recovered = LinkedHashSet<String>()
        val absorbed = LinkedHashSet<String>()
        val strays = calls.filter { (id, known) -> id !in wanting && known.name.isBlank() && known.pieces.isNotEmpty() }
        val strayText by lazy { strays.values.joinToString("") { it.pieces } }
        for ((id, known) in wanting) {
            val own = MessageRecovery.recover(known.pieces.toString())
            val read = own ?: MessageRecovery.recover(strayText) ?: continue
            known.args = buildJsonObject { put("text", buildJsonObject { put("content", read.text) }) }
            known.recovered = read
            known.recoveredPieces = if (own != null) known.pieceCount else strays.values.sumOf { it.pieceCount }
            if (own == null) {
                strays.values.forEach { it.absorbed = true }
                absorbed += strays.keys
            }
            recovered += id
        }
        return Recovery(recovered, absorbed)
    }

    /** The code of the stream error a record's `error` step is replayed as: the server's reason, not a connection's. */
    const val RECORD_ERROR = "record_error"

    private const val PAGE_SIZE = 200
    /** Pages read back from the record's end for one window at most: a turn is rarely more than a page or two of steps. */
    private const val MAX_PAGES = 12
}
