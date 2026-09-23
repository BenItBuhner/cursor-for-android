package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.random.Random

/**
 * Bennett's 2026-09-23 frame (`internal/reference/big-project-beta-2026-09-23-0028/full.png`): a long-running Project
 * whose transcript showed "Older messages" and one "10 events" stretch, nothing else. Two thousand turns over two
 * days — a prompt of the user's every fifty, every other turn a worker's report, a subscribed pull request's change,
 * or a goal's continuation — most of which the coordinator answered without a word to the user: its notes read and
 * edited, a worker told what comes next, a status checked, or nothing at all. The newest [SILENT_TAIL] turns are all
 * such silent reports, so the newest ten turns hold no message; the user's last prompt is [SILENT_TAIL] + a few dozen
 * turns up. Run logs older than [LOG_RETENTION_MS] are gone, as the server's are after about a day and a half.
 *
 * Every source per turn in the wire's shapes, as [LongProject] has them: the account's record (legacy steps, which
 * `BlobFixtures` turns into the blob-backed record the fault server serves), the run's log, the `/v0` transcript, the
 * run records.
 */
object BigProject {
    const val AGENT_ID = "bc-big-coordinator-000000000000000000000001"
    const val AGENT_NAME = "Polymarket Bot"
    const val TURNS = 2_000
    /** Every [USER_EVERY]th turn is the user's, from the first. */
    const val USER_EVERY = 50
    const val TURN_SPACING_MS = 90_000L
    /** The newest turns, every one a worker's report the coordinator answered silently. */
    const val SILENT_TAIL = 14
    /** About one injected turn in this many carries a message to the user. */
    const val MESSAGE_ODDS = 9
    /** How long a run's log is kept on the server (the export of 2026-09-20: about 31 hours). */
    const val LOG_RETENTION_MS = 31 * 3_600_000L

    val WORKERS = (1..12).map { "bc-%08d-big-worker-%024d".format(it, it) }

    class Turn(
        val index: Int,
        val runId: String,
        val prompt: String,
        val isUser: Boolean,
        val startedAt: Long,
        val durationMs: Long,
        /** The coordinator's word to the user in this turn, or null for a silent one. */
        val message: String?,
        val narration: List<String>,
        val record: List<JsonObject>,
        val log: List<Pair<String, String>>,
        /** Kinds of the turn's calls, for tests that count what should be on screen. */
        val calls: List<String>,
    ) {
        val endedAt: Long get() = startedAt + durationMs
    }

    private class Call(val turn: Int, val step: Int, val name: String, val args: JsonObject, val result: JsonObject) {
        val streamId: String get() = "turn-$turn:step:$step:tool"
        val recordId: String get() = "toolu_big_${turn}_$step"
        val streamName: String get() = when (name) { "SendMessage" -> "sendMessage"; "send_to_agent" -> "sendToAgent"; "get_agent_status" -> "getAgentStatus"; else -> name }
    }

    fun userPrompt(n: Int): String = USER_PROMPTS[n % USER_PROMPTS.size] + " (#$n)"
    fun messageText(n: Int): String = MESSAGES[n % MESSAGES.size] + " [$n]"

    /** The turns, oldest first, the first started at [firstAt]; fixed seed, so every run of a test sees the same Project. */
    fun turns(firstAt: Long, turns: Int = TURNS, seed: Int = 23): List<Turn> {
        val random = Random(seed)
        var lastMessage: Call? = null
        var messages = 0
        val out = ArrayList<Turn>(turns)
        for (i in 1..turns) {
            val isUser = (i - 1) % USER_EVERY == 0 && i <= turns - SILENT_TAIL
            val startedAt = firstAt + (i - 1) * TURN_SPACING_MS
            val inTail = i > turns - SILENT_TAIL
            val calls = ArrayList<Call>()
            val narration = ArrayList<String>()
            var message: String? = null
            val worker = WORKERS[random.nextInt(WORKERS.size)]
            val prompt: String
            if (isUser) {
                prompt = userPrompt(i / USER_EVERY)
                message = messageText(messages++)
                calls += Call(i - 1, 1, "SendMessage", sendMessageArgs(message), sent("msg_big_$i"))
                calls += Call(i - 1, 2, "send_to_agent", toAgent(worker, "Bennett's word: ${prompt.take(60)}"), delivered(worker))
            } else {
                prompt = when {
                    i % 11 == 0 -> github(i, startedAt)
                    i % 97 == 0 -> goal(startedAt)
                    else -> workerReport("Report $i", worker, startedAt, detailChars = if (i % 5 == 0) 3_000 else 400)
                }
                if (!inTail && random.nextInt(MESSAGE_ODDS) == 0) {
                    message = messageText(messages++)
                    calls += Call(i - 1, 1, "read_file", buildJsonObject { put("target_file", "/cursor/stores/bc-big/notes.md") }, readResult(random, 1_200))
                    calls += Call(i - 1, 2, "SendMessage", sendMessageArgs(message), sent("msg_big_$i"))
                } else {
                    when (random.nextInt(10)) {
                        in 0..3 -> {
                            calls += Call(i - 1, 1, "read_file", buildJsonObject { put("target_file", "/cursor/stores/bc-big/notes.md") }, readResult(random, 1_200))
                            calls += Call(i - 1, 2, "search_replace", edit("notes.md"), edited(1, 1))
                            calls += Call(i - 1, 3, "send_to_agent", toAgent(worker, "Next: the order book depth check, then the fee table."), delivered(worker))
                        }
                        // A research report read whole: a payload of tens of thousands of characters.
                        4 -> {
                            calls += Call(i - 1, 1, "read_file", buildJsonObject { put("target_file", "/cursor/stores/bc-big/internal/research-$i.md") }, readResult(random, 18_000))
                            calls += Call(i - 1, 2, "search_replace", edit("notes.md"), edited(3, 2))
                        }
                        in 5..6 -> calls += Call(i - 1, 1, "get_agent_status", status(worker), statuses(worker))
                        7 -> narration += REMARKS[random.nextInt(REMARKS.size)]
                        // Nothing at all: the model ended the turn without a call or a word.
                        else -> Unit
                    }
                }
            }
            val record = ArrayList<JsonObject>()
            record += buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", startedAt.toString()) }) }
            val runId = "run-big-$i"
            val log = ArrayList<Pair<String, String>>()
            log += "status" to """{"runId":"$runId","status":"RUNNING"}"""
            // A silent run's log carries the last message sent ahead of its own events, as the real ones do (#228).
            if (message == null) lastMessage?.let { call -> log += "tool_call" to toolCallEvent(call.streamId, "sendMessage", "completed", call.args, call.result) }
            for (c in calls) {
                record += buildJsonObject { put("toolCall", buildJsonObject { put("tool", "CLIENT_SIDE_TOOL_V2_UNSPECIFIED"); put("toolCallId", c.recordId); put("name", c.name); put("rawArgs", c.args.toString()); put("modelCallId", "model_big_${c.turn}_${c.step}") }) }
                record += buildJsonObject { put("finalToolResult", buildJsonObject { put("toolCallId", c.recordId); put("result", if (c.name == "SendMessage") buildJsonObject {} else c.result) }) }
                log += "tool_call" to toolCallEvent(c.streamId, c.streamName, "running", c.args, null)
                log += "tool_call" to toolCallEvent(c.streamId, c.streamName, "completed", c.args, c.result)
                if (c.name == "SendMessage") lastMessage = c
            }
            narration.forEach { text ->
                record += buildJsonObject { put("text", text) }
                log += "assistant" to """{"text":${quote(text)}}"""
            }
            val durationMs = 20_000L + random.nextLong(0, 50_000L)
            record += buildJsonObject { put("text", ""); put("isMessageDone", true) }
            log += "result" to """{"runId":"$runId","status":"FINISHED","text":"","durationMs":$durationMs}"""
            out += Turn(i, runId, prompt, isUser, startedAt, durationMs, message, narration, record, log, calls.map { it.name })
        }
        return out
    }

    /**
     * One of the Project's workers' own chat (one of [WORKERS]), the kind Bennett opened on 2026-09-23 when the notice
     * read "HTTP 502": [turns] turns, each the coordinator's word as its prompt — an ordinary prompt, not Project
     * mode, for the worker is not a coordinator — a read of the notes, an edit, and the worker's reply.
     */
    fun workerTurns(firstAt: Long, turns: Int = 40, seed: Int = 7): List<Turn> {
        val random = Random(seed)
        return (1..turns).map { i ->
            val startedAt = firstAt + (i - 1) * TURN_SPACING_MS
            val prompt = "Next: the order book depth check on market ${100 + i}, then the fee table (#$i)."
            val reply = "Market ${100 + i}: the book is ${2 + i % 5} levels deep within 1% of the mid; the fee table row is filed."
            val calls = listOf(
                Call(i - 1, 1, "read_file", buildJsonObject { put("target_file", "/cursor/stores/bc-big/notes.md") }, readResult(random, 1_200)),
                Call(i - 1, 2, "search_replace", edit("notes.md"), edited(1, 1)),
            )
            val runId = "run-worker-$i"
            val record = ArrayList<JsonObject>()
            record += buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt); put("createdAt", startedAt.toString()) }) }
            val log = ArrayList<Pair<String, String>>()
            log += "status" to """{"runId":"$runId","status":"RUNNING"}"""
            for (c in calls) {
                record += buildJsonObject { put("toolCall", buildJsonObject { put("tool", "CLIENT_SIDE_TOOL_V2_UNSPECIFIED"); put("toolCallId", "toolu_worker_${c.turn}_${c.step}"); put("name", c.name); put("rawArgs", c.args.toString()); put("modelCallId", "model_worker_${c.turn}_${c.step}") }) }
                record += buildJsonObject { put("finalToolResult", buildJsonObject { put("toolCallId", "toolu_worker_${c.turn}_${c.step}"); put("result", c.result) }) }
                log += "tool_call" to toolCallEvent(c.streamId, c.streamName, "running", c.args, null)
                log += "tool_call" to toolCallEvent(c.streamId, c.streamName, "completed", c.args, c.result)
            }
            record += buildJsonObject { put("text", reply) }
            log += "assistant" to """{"text":${quote(reply)}}"""
            val durationMs = 20_000L + random.nextLong(0, 50_000L)
            record += buildJsonObject { put("text", ""); put("isMessageDone", true) }
            log += "result" to """{"runId":"$runId","status":"FINISHED","text":${quote(reply)},"durationMs":$durationMs}"""
            Turn(i, runId, prompt, isUser = true, startedAt, durationMs, message = null, narration = listOf(reply), record, log, calls.map { it.name })
        }
    }

    /** The `/v0` transcript: each turn's prompt as a `user_message`, its narration as the `assistant_message`. */
    fun v0Transcript(turns: List<Turn>): List<V0ConversationMessageDto> = turns.flatMap { turn ->
        listOf(V0ConversationMessageDto("${turn.runId}-u", "user_message", turn.prompt)) +
            turn.narration.mapIndexed { i, text -> V0ConversationMessageDto("${turn.runId}-a$i", "assistant_message", text) }
    }

    fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    /** Whether [turn]'s log is still on the server at [now]. */
    fun retained(turn: Turn, now: Long): Boolean = now - turn.endedAt < LOG_RETENTION_MS

    private fun workerReport(title: String, worker: String, at: Long, detailChars: Int): String {
        val detail = StringBuilder()
        while (detail.length < detailChars) detail.append("The market scan found the spread within bounds; the fee table is filed under research. ")
        return "<timestamp>${Instant.ofEpochMilli(at)}</timestamp>\n<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n" +
            "<task>\nkind: subagent\nstatus: success\ntask_id: $worker\ntitle: $title\ntool_call_id: toolu_notify_${title.hashCode().toUInt()}\nagent_id: $worker\ndetail: This is the last output of the subagent:\n$detail\nAgent ID: $worker ($title)\n</task>\n</system_notification>\n" +
            "<user_query>Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required. Don't repeat the same confirmation every time.</user_query>"
    }

    private fun github(i: Int, at: Long): String =
        "<timestamp>${Instant.ofEpochMilli(at)}</timestamp>\n<system_notification source=\"github\" pr=\"https://github.com/BenItBuhner/codex-poly-bot/pull/${40 + i % 30}\" action=\"synchronize\" sender=\"cursor[bot]\">\nA subscribed pull request was updated.\n</system_notification>\n" +
            "<user_query>Perform any necessary follow-up actions in response to the notification above. If no follow-up work is needed, no further action is required.</user_query>"

    private fun goal(at: Long): String =
        "<timestamp>${Instant.ofEpochMilli(at)}</timestamp>\n<system_notification source=\"goal\">\nContinue working toward the active thread goal.\n<objective>Keep the bot's paper trading within the risk limits and report weekly.</objective>\n</system_notification>"

    private fun toolCallEvent(callId: String, name: String, status: String, args: JsonObject, result: JsonObject?): String =
        buildJsonObject { put("callId", callId); put("name", name); put("status", status); put("args", args); if (result != null) put("result", result) }.toString()

    private fun sendMessageArgs(text: String): JsonObject = buildJsonObject { put("text", buildJsonObject { put("content", text) }) }
    private fun sent(messageId: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("timestamp", "1789341071043"); put("messageId", messageId) }) }
    private fun toAgent(worker: String, text: String): JsonObject = buildJsonObject { put("agentId", worker); put("message", text); put("delivery", "queue"); put("title", text.take(40)) }
    private fun delivered(worker: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("workerBcId", worker); put("deliveredAs", "queue"); put("message", "Delivered as queue") }) }
    private fun status(worker: String): JsonObject = buildJsonObject { put("agentIds", buildJsonArray { add(JsonPrimitive(worker)) }) }
    private fun statuses(worker: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("workers", buildJsonArray { add(buildJsonObject { put("bcId", worker); put("name", "Scanner"); put("lifecycle", "ACTIVE"); put("turnInFlight", false) }) }) }) }
    private fun edit(path: String): JsonObject = buildJsonObject { put("file_path", "/cursor/stores/bc-big/$path"); put("old_string", "- [ ] fee table"); put("new_string", "- [x] fee table") }
    private fun edited(added: Int, removed: Int): JsonObject = buildJsonObject { put("success", buildJsonObject { put("linesAdded", added); put("linesRemoved", removed); put("diffString", "--- a/notes.md\n+++ b/notes.md\n@@ -1 +1 @@\n-- [ ] fee table\n+- [x] fee table\n") }) }

    private fun readResult(random: Random, chars: Int): JsonObject {
        val sb = StringBuilder(chars + 64)
        while (sb.length < chars) sb.append(WORDS[random.nextInt(WORDS.size)]).append(if (random.nextInt(14) == 0) ".\n" else " ")
        return buildJsonObject { put("success", buildJsonObject { put("contents", sb.toString()); put("totalLines", sb.count { it == '\n' }) }) }
    }

    private fun quote(text: String): String = Json.encodeToString(String.serializer(), text)

    private val WORDS = listOf("market", "spread", "order", "book", "fee", "table", "position", "risk", "limit", "paper", "trade", "the", "a", "of", "and", "with", "shows", "lists", "holds")

    private val REMARKS = listOf(
        "Noted; the scanner carries on with the order book.",
        "Filed under research; nothing for Bennett in this one.",
        "Notes updated; the next worker starts on the fee table.",
    )

    private val USER_PROMPTS = listOf(
        "Where are we on the paper trading run?",
        "Tighten the risk limits and tell me what changes.",
        "Summarise what the scanners found this week.",
        "Pause the fee work; the order book check comes first.",
    )

    private val MESSAGES = listOf(
        "The paper run is at day four: three positions open, all inside the limits, the largest at 2% of the book. The scanners report the spread steady; nothing needs you today.",
        "Risk limits tightened: the per-market cap is now 1.5% and the daily loss stop 3%. Two positions were trimmed to fit; the notes carry the before and after.",
        "This week: forty-one markets scanned, six flagged for thin books, two entered. The fee table is complete and filed under research.",
        "The fee work is paused; the order book check is running on the twelve most liquid markets and reports within the hour.",
    )
}
