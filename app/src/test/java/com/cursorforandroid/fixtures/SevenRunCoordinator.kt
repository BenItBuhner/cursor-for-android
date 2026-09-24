package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.UserMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bennett's 2026-09-19 frame (`internal/reference/duplicate-coordinator-messages/`, the "Polymarket Bot Scaling &
 * Research" Project): seven consecutive runs of a coordinator, the coordinator's word to the user in runs 1, 2 and 6
 * alone. Runs 3, 4 and 5 were worker-completion turns that edited the notes and addressed workers and said nothing
 * to the user; run 7 is the user's prompt, under way. The phone drew run 2's message four times — once per run of 2
 * to 5, each over that run's own footer ("Worked 20s · 1 edit · 2 agents · 2 notes", 21s, 22s, 2m 7s).
 *
 * Both sources the app reads are given here for every run: the account's record of the turn (`FetchBackgroundComposer`
 * steps: the prompt, the narration, each call whole with its result) and the run's own log (the documented stream's
 * events, call ids in the stream's positional form `turn-N:step:M:tool`). With [leak] the log of each silent run
 * carries run 2's `SendMessage` again, ahead of the run's own events — the same call, id and result included — which
 * is what the frame's four copies are evidence of: a run that sent nothing replaying the last message as if it were
 * its own. Nothing in the record says so for those turns: they hold no `SendMessage` call at all.
 */
object SevenRunCoordinator {
    const val AGENT_ID = "bc-polymarket"
    const val AGENT_NAME = "Polymarket Bot Scaling & Research"

    const val M1 = "Both deep books are registered with their counts and boundaries, the recorders are back on the tape, and PR #6 is in review; the cancel-latency notes are already in."
    const val M2 = "Done: one message, queued as its next turn (it is mid-turn on the outage now, so a steer would have interrupted that). It replaces everything you cleared and states plainly that the queue was cleared and nothing else is pending.\n\n" +
        "Contents, in priority order: outage follow-through (components back?, tape window marked, no fills or kills on stale data, the 17:09Z Slack probe reading explained); the second deep book at the first clean calm point after recovery, with PR #6 offered beside its own excludeBooks knob; counts and boundaries as registered. Then, marked not-foreground, the cancel-latency research with the 150 ms hold stated correctly and the report's 50 ms figure flagged as its one error, plus PR #7 filed as superseded. No coordinator questions added."
    const val M6 = "The usage limit hit again at 19:18Z and stopped the primary before it started the consolidated message you cleared the queue for. The message is still queued and untouched; it runs the moment the limit clears (the cycle resets today, or sooner if you raise the spend limit). The lock recorder's agent is idle (its recorder process keeps running on its VM); it also needs the reset before it can write the 22:30Z MLB mark. Your machines are unaffected: the six books, the recorders, the timers, and the daily chain run without the agent. When you clear the limit, tell me and I will confirm it picked up."
    const val USER_PROMPT = "The limits that were hit are now gone and you can continue, check and make sure all is well thx."

    /** The stream's id for the coordinator's message of run 2, as its own log and the silent runs' logs both carry it. */
    const val M2_CALL_ID = "turn-1:step:1:tool"
    const val M2_MESSAGE_ID = "msg_02Done"

    private const val PRIMARY = "bc-00000001-poly-primary-worker-000000000001"
    private const val RECORDER = "bc-00000002-poly-lock-recorder-00000000002"

    /** One run of the seven: its place, its prompt, how long it ran (null: still running), its log and its record turn. */
    class Run(
        val id: String,
        val index: Int,
        val prompt: String,
        val durationMs: Long?,
        /** The run's log as the documented stream replays it, `Done` included for a finished run. */
        val events: List<RunStreamEvent>,
        /** The turn as the account's record holds it: the prompt step first. */
        val record: List<JsonObject>,
    ) {
        val isUser: Boolean get() = index == 7
        val status: String get() = if (durationMs == null) "RUNNING" else "FINISHED"
    }

    /**
     * The seven runs, oldest first, each prompt stamped from [firstAt] a minute apart. [leak] puts run 2's message
     * into the logs of runs 3, 4 and 5; without it the logs carry each run's own events alone.
     */
    fun runs(firstAt: Long, leak: Boolean = true): List<Run> {
        val m2Args = message(M2)
        val m2Result = sent(M2_MESSAGE_ID)
        val leaked = if (leak) listOf(RunStreamEvent.ToolCall(SseToolCallDto(M2_CALL_ID, "sendMessage", ToolCall.STATUS_COMPLETED, m2Args, m2Result))) else emptyList()
        var at = firstAt
        fun stamp(): Long = at.also { at += 60_000L }
        return listOf(
            // Run 1: the earlier update — the message, then two workers addressed and two notes; 59 s.
            run(
                id = "run-1", index = 1, prompt = injected("Register the two deep books", PRIMARY), at = stamp(), durationMs = 59_000L,
                narration = listOf("Both books registered; the recorders are on the tape again.", "Bennett has the state of the books and the review."),
                calls = listOf(
                    call(0, 1, "sendMessage", message(M1), sent("msg_01Books")),
                    call(0, 2, "sendToAgent", toAgent(PRIMARY, "Confirm the counts and boundaries as registered."), delivered(PRIMARY)),
                    call(0, 3, "sendToAgent", toAgent(RECORDER, "Resume the tape once the components are back."), delivered(RECORDER)),
                ),
                messageCalls = 1,
            ),
            // Run 2: the message the frame repeats, then a worker addressed, the notes edited, a status check, two notes; 20 s.
            run(
                id = "run-2", index = 2, prompt = injected("Clear the queue and consolidate", PRIMARY), at = stamp(), durationMs = 20_000L,
                narration = listOf("Queue cleared; one consolidated message queued for the primary.", "Bennett is told what was queued and in what order."),
                calls = listOf(
                    call(1, 1, "sendMessage", m2Args, m2Result),
                    call(1, 2, "sendToAgent", toAgent(PRIMARY, "Outage follow-through first, then the second deep book, then the cancel-latency research."), delivered(PRIMARY)),
                    call(1, 3, "search_replace", edit("notes.md"), edited(1, 1)),
                    call(1, 4, "getAgentStatus", status(PRIMARY), statuses(PRIMARY)),
                ),
                messageCalls = 1,
            ),
            // Runs 3, 4 and 5: silent turns — the notes edited, workers addressed or checked, nothing to the user.
            run(
                id = "run-3", index = 3, prompt = injected("Outage follow-through", PRIMARY), at = stamp(), durationMs = 21_000L,
                narration = listOf("The primary is back on the tape window; the notes carry the 17:09Z reading.", "Nothing for Bennett in this one."),
                calls = listOf(
                    call(2, 1, "search_replace", edit("notes.md"), edited(1, 1)),
                    call(2, 2, "sendToAgent", toAgent(PRIMARY, "Mark the tape window before the next fill."), delivered(PRIMARY)),
                    call(2, 3, "getAgentStatus", status(PRIMARY), statuses(PRIMARY)),
                ),
                leaked = leaked,
            ),
            run(
                id = "run-4", index = 4, prompt = injected("Second deep book registered", PRIMARY), at = stamp(), durationMs = 22_000L,
                narration = listOf("Second book registered at the calm point; PR #6 stays beside its knob."),
                calls = listOf(
                    call(3, 1, "search_replace", edit("notes.md"), edited(1, 1)),
                    call(3, 2, "sendToAgent", toAgent(PRIMARY, "Hold PR #6 until the excludeBooks knob lands."), delivered(PRIMARY)),
                    call(3, 3, "getAgentStatus", status(PRIMARY), statuses(PRIMARY)),
                ),
                leaked = leaked,
            ),
            run(
                id = "run-5", index = 5, prompt = injected("Cancel-latency research", RECORDER), at = stamp(), durationMs = 127_000L,
                narration = listOf("The 150 ms hold is stated correctly now; PR #7 filed as superseded."),
                calls = listOf(
                    call(4, 1, "search_replace", edit("notes.md"), edited(1, 1)),
                    call(4, 2, "sendToAgent", toAgent(RECORDER, "File PR #7 as superseded by the corrected report."), delivered(RECORDER)),
                ),
                leaked = leaked,
            ),
            // Run 6: the next update — the message, then the notes edited and one note; 1 m 1 s.
            run(
                id = "run-6", index = 6, prompt = injected("Usage limit reached", PRIMARY), at = stamp(), durationMs = 61_000L,
                narration = listOf("The primary is stopped on the limit; the queued message waits for the reset."),
                calls = listOf(
                    call(5, 1, "sendMessage", message(M6), sent("msg_06Limit")),
                    call(5, 2, "search_replace", edit("notes.md"), edited(2, 1)),
                ),
                messageCalls = 1,
            ),
            // Run 7: the user's prompt, under way — five workers addressed and a progress step, no end yet.
            run(
                id = "run-7", index = 7, prompt = USER_PROMPT, at = stamp(), durationMs = null,
                narration = emptyList(),
                calls = listOf(PRIMARY, RECORDER, "bc-00000003-poly-book-a", "bc-00000004-poly-book-b", "bc-00000005-poly-timer").mapIndexed { i, worker ->
                    call(6, i + 1, "sendToAgent", toAgent(worker, "The limit is cleared: resume where you stopped and report."), delivered(worker))
                } + call(6, 6, "update_current_step", buildJsonObject { put("current_step", "Resuming the workers") }, buildJsonObject { put("success", buildJsonObject {}) }),
            ),
        )
    }

    /** The ids of the runs whose record turn holds no `SendMessage`: the ones the app asks the log for. */
    val SILENT_RUNS = setOf("run-3", "run-4", "run-5")

    /** [run]'s log replayed through the accumulator the stream's events go through: its trace, footer included when it ended. */
    fun logItems(run: Run): List<TimelineItem> {
        val live = TimelineBuilder.LiveRun(run.id, timed = false)
        run.events.forEach { if (it != RunStreamEvent.Done) live.apply(it) }
        return live.snapshot()
    }

    /**
     * The chat as the conversation would hold it from the runs' logs alone: each run's trace, after its prompt as
     * its row when [prompts] (an injected turn as its notification, the user's as their message) — the documented
     * path lays runs the transcript has no prompt for bare, which is [prompts] false.
     */
    fun transcript(runs: List<Run>, prompts: Boolean, firstAt: Long): List<TimelineItem> = runs.flatMap { run ->
        val at = firstAt + run.index * 60_000L
        val head = if (!prompts) emptyList() else SystemNotifications.parse("prompt-${run.id}", run.prompt, at)?.items ?: listOf(UserMessage("prompt-${run.id}", run.prompt, at))
        head + logItems(run)
    }

    private class Call(val turn: Int, val step: Int, val name: String, val args: JsonObject, val result: JsonObject) {
        val streamId: String get() = "turn-$turn:step:$step:tool"
        val recordId: String get() = "toolu_${turn}_${step}_${name.take(4)}"
    }

    private fun call(turn: Int, step: Int, name: String, args: JsonObject, result: JsonObject) = Call(turn, step, name, args, result)

    private fun run(id: String, index: Int, prompt: String, at: Long, durationMs: Long?, narration: List<String>, calls: List<Call>, leaked: List<RunStreamEvent> = emptyList(), messageCalls: Int = 0): Run {
        val events = ArrayList<RunStreamEvent>()
        events += RunStreamEvent.Status(id, RunStatus.RUNNING)
        events += leaked
        // The first call (a run's message comes first), a note, the rest of the calls, the last note: the frame's
        // every stretch has its notes after the run's message, and two notes with a call between them read as two.
        calls.forEachIndexed { i, c ->
            events += RunStreamEvent.ToolCall(SseToolCallDto(c.streamId, c.name, ToolCall.STATUS_RUNNING, c.args))
            events += RunStreamEvent.ToolCall(SseToolCallDto(c.streamId, c.name, ToolCall.STATUS_COMPLETED, c.args, c.result))
            if (i == 0 && narration.size > 1) events += RunStreamEvent.Assistant(narration.first())
        }
        (if (narration.size > 1) narration.drop(1) else narration).forEach { events += RunStreamEvent.Assistant(it) }
        if (durationMs != null) {
            events += RunStreamEvent.Result(id, RunStatus.FINISHED, "", durationMs, null)
            events += RunStreamEvent.Done
        }
        val record = ArrayList<JsonObject>()
        record += buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", at.toString()) }) }
        calls.forEachIndexed { i, c ->
            record += buildJsonObject { put("toolCall", buildJsonObject { put("tool", "CLIENT_SIDE_TOOL_V2_UNSPECIFIED"); put("toolCallId", c.recordId); put("name", recordName(c.name)); put("rawArgs", c.args.toString()); put("modelCallId", "model_${c.turn}_${c.step}") }) }
            record += buildJsonObject { put("finalToolResult", buildJsonObject { put("toolCallId", c.recordId); put("result", if (c.name == "sendMessage") buildJsonObject {} else c.result) }) }
            if (i == 0 && narration.size > 1) record += buildJsonObject { put("text", narration.first()) }
        }
        (if (narration.size > 1) narration.drop(1) else narration).forEach { record += buildJsonObject { put("text", it) } }
        if (durationMs != null) record += buildJsonObject { put("text", ""); put("isMessageDone", true) }
        check(calls.count { it.name == "sendMessage" } == messageCalls) { "$id carries ${calls.count { it.name == "sendMessage" }} messages, not $messageCalls" }
        return Run(id, index, prompt, durationMs, events, record)
    }

    /** The record's model-facing spelling of a coordinator tool: `SendMessage`, `send_to_agent`, `get_agent_status`. */
    private fun recordName(stream: String): String = when (stream) {
        "sendMessage" -> "SendMessage"
        "sendToAgent" -> "send_to_agent"
        "getAgentStatus" -> "get_agent_status"
        else -> stream
    }

    /** A worker-completion turn as Cursor injects it, under the shorter of its two instruction templates. */
    fun injected(title: String, worker: String): String =
        "<timestamp>Saturday, Sep 19, 2026, 7:00 PM (UTC)</timestamp>\n<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n" +
            "<task>\nkind: subagent\nstatus: success\ntask_id: $worker\ntitle: $title\ntool_call_id: toolu_notify_${title.hashCode().toUInt()}\nagent_id: $worker\ndetail: This is the last output of the subagent:\n$title is done; the notes carry the details.\nAgent ID: $worker ($title)\n</task>\n</system_notification>\n" +
            "<user_query>Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required. If you mention an agent or subagent in your response, link it with the `[Name](id)` Don't use generic label such as `[agent]`, `[worker]`, or `[subagent]`. Don't repeat the same confirmation every time.</user_query>"

    private fun message(text: String): JsonObject = buildJsonObject { put("text", buildJsonObject { put("content", text) }) }
    private fun sent(messageId: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("timestamp", "1789341071043"); put("messageId", messageId) }) }
    private fun toAgent(worker: String, text: String): JsonObject = buildJsonObject { put("agentId", worker); put("message", text); put("delivery", "queue"); put("title", text.take(40)) }
    private fun delivered(worker: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("workerBcId", worker); put("deliveredAs", "queue"); put("message", "Delivered as queue") }) }
    private fun status(worker: String): JsonObject = buildJsonObject { put("agentIds", buildJsonArray { add(JsonPrimitive(worker)) }) }
    private fun statuses(worker: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("workers", buildJsonArray { add(buildJsonObject { put("bcId", worker); put("name", "Primary"); put("lifecycle", "ACTIVE"); put("turnInFlight", true) }) }) }) }
    private fun edit(path: String): JsonObject = buildJsonObject { put("file_path", "/cursor/stores/bc-poly/$path"); put("old_string", "- [ ] queue"); put("new_string", "- [x] queue") }
    private fun edited(added: Int, removed: Int): JsonObject = buildJsonObject { put("success", buildJsonObject { put("linesAdded", added); put("linesRemoved", removed) }) }
}
