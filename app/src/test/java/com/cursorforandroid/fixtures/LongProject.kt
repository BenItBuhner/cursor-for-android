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
 * Bennett's 2026-09-20 frame (`internal/reference/empty-transcript-reload-2026-09-20/full.png`): a Project of 240
 * turns, four and a half hours of a coordinator working through its workers' reports — 225 injected turns, 15 of
 * the user's — the newest turn under way. Every source the app reads is here for every turn, in the shapes the
 * wire has (see `SevenRunCoordinator`): the account's record of the turn (`FetchBackgroundComposer` steps, the
 * prompt with `AGENT_MODE_PROJECT`, each call whole with its result, the narration, `isMessageDone`), the run's own
 * log (the documented stream's events, a silent run's log carrying the last message sent as the real ones do), the
 * `/v0` transcript (the prompt, the narration as the assistant's text), and the run records.
 *
 * The record is heavy the way a real one is: a `read_file` result of tens of thousands of characters in every
 * seventh turn, a notes edit with its diff in most, the coordinator's messages a few hundred characters.
 */
object LongProject {
    const val AGENT_ID = "bc-shopify-competitor"
    const val AGENT_NAME = "Shopify Competitor"
    const val TURNS = 240
    /** Every [USER_EVERY]th turn is the user's, from the first: 15 of 240. */
    const val USER_EVERY = 16
    const val PRIMARY = "bc-00000001-shop-primary-worker-00000000001"
    const val RESEARCH = "bc-00000002-shop-research-worker-0000000002"
    const val TURN_SPACING_MS = 67_000L

    class Turn(
        val index: Int,
        val runId: String,
        val prompt: String,
        val isUser: Boolean,
        val startedAt: Long,
        /** Null for the newest turn, which is under way. */
        val durationMs: Long?,
        /** The coordinator's word to the user in this turn, or null for a silent one. */
        val message: String?,
        val narration: List<String>,
        /** The record's steps for the turn, the prompt first. */
        val record: List<JsonObject>,
        /** The run's log as the documented stream serves it: `event` to `data` pairs. */
        val log: List<Pair<String, String>>,
        /**
         * The run has no message in the `/v0` transcript at all — a turn resumed after a usage limit, a turn the
         * account opened on its own: Bennett's Polymarket Project had 26 such runs among 324 (2026-09-21). The
         * record still carries the turn; the documented transcript does not.
         */
        val promptless: Boolean = false,
    ) {
        val status: String get() = if (durationMs == null) "RUNNING" else "FINISHED"
        val endedAt: Long get() = startedAt + (durationMs ?: 0L)
    }

    private class Call(val turn: Int, val step: Int, val name: String, val args: JsonObject, val result: JsonObject) {
        val streamId: String get() = "turn-$turn:step:$step:tool"
        val recordId: String get() = "toolu_${turn}_${step}"
        val streamName: String get() = when (name) { "SendMessage" -> "sendMessage"; "send_to_agent" -> "sendToAgent"; "get_agent_status" -> "getAgentStatus"; else -> name }
    }

    fun userMessageText(userIndex: Int): String = USER_PROMPTS[userIndex % USER_PROMPTS.size]

    fun messageText(userIndex: Int): String = MESSAGES[userIndex % MESSAGES.size]

    /** The [TURNS] turns, oldest first, the first started at [firstAt]; a fixed seed so every run of a test sees the same chat. */
    fun turns(
        firstAt: Long,
        turns: Int = TURNS,
        seed: Int = 20,
        /** Which turns (1-based) the `/v0` transcript has no message for (see [Turn.promptless]); never a user's turn. */
        promptless: (Int) -> Boolean = { false },
        /** The newest turn is the user's, whatever [USER_EVERY] says: the live turn started on the reader's own prompt. */
        lastIsUser: Boolean = false,
    ): List<Turn> {
        val random = Random(seed)
        var lastMessage: Pair<Call, JsonObject>? = null
        val out = ArrayList<Turn>(turns)
        for (i in 1..turns) {
            val isUser = (i - 1) % USER_EVERY == 0 || (lastIsUser && i == turns)
            val userIndex = (i - 1) / USER_EVERY
            val startedAt = firstAt + (i - 1) * TURN_SPACING_MS
            val running = i == turns
            val calls = ArrayList<Call>()
            val narration = ArrayList<String>()
            var message: String? = null
            if (isUser) {
                message = messageText(userIndex)
                calls += Call(i - 1, 1, "SendMessage", sendMessageArgs(message), sent("msg_${i}_user"))
                calls += Call(i - 1, 2, "send_to_agent", toAgent(PRIMARY, "Bennett's word on the storefront: ${message.take(80)}"), delivered(PRIMARY))
                narration += "Bennett has the state of the storefront and the research; the primary is told what comes next."
            } else {
                when (random.nextInt(10)) {
                    // Most worker reports: the notes edited, the worker told what comes next, a remark.
                    in 0..5 -> {
                        calls += Call(i - 1, 1, "search_replace", edit("notes.md"), edited(1, 1))
                        calls += Call(i - 1, 2, "send_to_agent", toAgent(if (random.nextBoolean()) PRIMARY else RESEARCH, "Next: the checkout flow comparison, then the pricing table."), delivered(PRIMARY))
                        narration += REMARKS[random.nextInt(REMARKS.size)]
                    }
                    // A report read whole — a payload of tens of thousands of characters in the record and the log.
                    6 -> {
                        calls += Call(i - 1, 1, "read_file", buildJsonObject { put("target_file", "/cursor/stores/bc-shop/internal/research-$i.md") }, readResult(random, 24_000))
                        calls += Call(i - 1, 2, "search_replace", edit("notes.md"), edited(3, 2))
                        narration += "The research report is read and filed; the notes carry its three findings."
                    }
                    // A status check and nothing else.
                    7 -> {
                        calls += Call(i - 1, 1, "get_agent_status", status(PRIMARY), statuses(PRIMARY))
                        narration += "The primary is mid-turn on the pricing table; nothing to do until it reports."
                    }
                    // Narration alone: the record kept the words and no calls.
                    else -> narration += "Noted; nothing for Bennett in this one."
                }
            }
            val record = ArrayList<JsonObject>()
            record += buildJsonObject { put("humanMessage", buildJsonObject { put("text", if (isUser) userMessageText(userIndex) else injected("Worker report $i", if (i % 3 == 0) RESEARCH else PRIMARY, startedAt)); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", startedAt.toString()) }) }
            val log = ArrayList<Pair<String, String>>()
            val runId = "run-$i"
            log += "status" to """{"runId":"$runId","status":"RUNNING"}"""
            // A silent run's log carries the last message sent ahead of its own events, as the real ones do (#228).
            if (!isUser && lastMessage != null) {
                val (call, result) = lastMessage
                log += "tool_call" to toolCallEvent(call.streamId, "sendMessage", "completed", call.args, result)
            }
            for (c in calls) {
                record += buildJsonObject { put("toolCall", buildJsonObject { put("tool", "CLIENT_SIDE_TOOL_V2_UNSPECIFIED"); put("toolCallId", c.recordId); put("name", c.name); put("rawArgs", c.args.toString()); put("modelCallId", "model_${c.turn}_${c.step}") }) }
                record += buildJsonObject { put("finalToolResult", buildJsonObject { put("toolCallId", c.recordId); put("result", if (c.name == "SendMessage") buildJsonObject {} else c.result) }) }
                log += "tool_call" to toolCallEvent(c.streamId, c.streamName, "running", c.args, null)
                log += "tool_call" to toolCallEvent(c.streamId, c.streamName, "completed", c.args, c.result)
                if (c.name == "SendMessage") lastMessage = c to c.result
            }
            narration.forEach { text ->
                record += buildJsonObject { put("text", text) }
                log += "assistant" to """{"text":${quote(text)}}"""
            }
            val durationMs = if (running) null else 35_000L + random.nextLong(0, 35_000L)
            if (!running) {
                record += buildJsonObject { put("text", ""); put("isMessageDone", true) }
                log += "result" to """{"runId":"$runId","status":"FINISHED","text":"","durationMs":$durationMs}"""
            }
            out += Turn(i, runId, if (isUser) userMessageText(userIndex) else (record.first()["humanMessage"] as JsonObject)["text"]!!.let { (it as JsonPrimitive).content }, isUser, startedAt, durationMs, message, narration, record, log, promptless = !isUser && promptless(i))
        }
        return out
    }

    /** The `/v0` transcript: each turn's prompt as a `user_message`, its narration as the `assistant_message`; a prompt-less turn (see [Turn.promptless]) has nothing in it. */
    fun v0Transcript(turns: List<Turn>): List<V0ConversationMessageDto> = turns.filterNot { it.promptless }.flatMap { turn ->
        listOf(V0ConversationMessageDto("${turn.runId}-u", "user_message", turn.prompt)) +
            turn.narration.mapIndexed { i, text -> V0ConversationMessageDto("${turn.runId}-a$i", "assistant_message", text) }
    }

    /** The run's `result` as `/v1` reports it for a finished run: its final text, the same words as the transcript's last reply to the turn. */
    fun result(turn: Turn): String? = if (turn.durationMs == null) null else turn.narration.lastOrNull()

    fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    /** A worker-completion turn as Cursor injects it, under the shorter instruction template. */
    fun injected(title: String, worker: String, at: Long): String =
        "<timestamp>${Instant.ofEpochMilli(at)}</timestamp>\n<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n" +
            "<task>\nkind: subagent\nstatus: success\ntask_id: $worker\ntitle: $title\ntool_call_id: toolu_notify_${title.hashCode().toUInt()}\nagent_id: $worker\ndetail: This is the last output of the subagent:\n$title is done; the notes carry the details.\nAgent ID: $worker ($title)\n</task>\n</system_notification>\n" +
            "<user_query>Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required. If you mention an agent or subagent in your response, link it with the `[Name](id)` Don't use generic label such as `[agent]`, `[worker]`, or `[subagent]`. Don't repeat the same confirmation every time.</user_query>"

    private fun toolCallEvent(callId: String, name: String, status: String, args: JsonObject, result: JsonObject?): String =
        buildJsonObject { put("callId", callId); put("name", name); put("status", status); put("args", args); if (result != null) put("result", result) }.toString()

    private fun sendMessageArgs(text: String): JsonObject = buildJsonObject { put("text", buildJsonObject { put("content", text) }) }
    private fun sent(messageId: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("timestamp", "1789341071043"); put("messageId", messageId) }) }
    private fun toAgent(worker: String, text: String): JsonObject = buildJsonObject { put("agentId", worker); put("message", text); put("delivery", "queue"); put("title", text.take(40)) }
    private fun delivered(worker: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("workerBcId", worker); put("deliveredAs", "queue"); put("message", "Delivered as queue") }) }
    private fun status(worker: String): JsonObject = buildJsonObject { put("agentIds", buildJsonArray { add(JsonPrimitive(worker)) }) }
    private fun statuses(worker: String): JsonObject = buildJsonObject { put("success", buildJsonObject { put("workers", buildJsonArray { add(buildJsonObject { put("bcId", worker); put("name", "Primary"); put("lifecycle", "ACTIVE"); put("turnInFlight", true) }) }) }) }
    private fun edit(path: String): JsonObject = buildJsonObject { put("file_path", "/cursor/stores/bc-shop/$path"); put("old_string", "- [ ] checkout"); put("new_string", "- [x] checkout") }
    private fun edited(added: Int, removed: Int): JsonObject = buildJsonObject { put("success", buildJsonObject { put("linesAdded", added); put("linesRemoved", removed); put("diffString", "--- a/notes.md\n+++ b/notes.md\n@@ -1 +1 @@\n-- [ ] checkout\n+- [x] checkout\n") }) }

    /** A `read_file` result of about [chars] characters of prose: what a research report costs the record. */
    private fun readResult(random: Random, chars: Int): JsonObject {
        val sb = StringBuilder(chars + 64)
        while (sb.length < chars) sb.append(WORDS[random.nextInt(WORDS.size)]).append(if (random.nextInt(14) == 0) ".\n" else " ")
        return buildJsonObject { put("success", buildJsonObject { put("contents", sb.toString()); put("totalLines", sb.count { it == '\n' }) }) }
    }

    private fun quote(text: String): String = Json.encodeToString(String.serializer(), text)

    private val WORDS = listOf("checkout", "storefront", "pricing", "theme", "cart", "conversion", "shipping", "catalog", "variant", "inventory", "merchant", "plan", "tier", "trial", "webhook", "the", "a", "of", "and", "with", "compares", "shows", "lists", "costs", "offers")

    private val REMARKS = listOf(
        "Noted; the primary carries on with the checkout comparison.",
        "The report is filed under research; nothing for Bennett in this one.",
        "Notes updated; the next worker starts on the pricing table.",
        "Nothing to route: the worker's findings are already in the notes.",
    )

    private val USER_PROMPTS = listOf(
        "Where are we on the storefront comparison?",
        "Focus on checkout first, then pricing. Report when the table is ready.",
        "Good. Now compare their shipping options with ours and file it.",
        "Pause the theme work and put everything on the pricing table until it is done.",
        "What does the research say about their free tier?",
    )

    private val MESSAGES = listOf(
        "The storefront comparison is halfway: the checkout flows are mapped side by side (theirs is one step shorter, ours asks for the phone number first), the theme catalogue is inventoried, and the pricing table is being built by the research worker now. Next report when the table has every tier.",
        "Checkout is done and filed under research: three findings, the shortest path is theirs by one screen, and ours loses the guest option at the payment step. Pricing is next; the primary is already on the table and will report within the hour.",
        "Shipping is compared and filed: they offer free shipping over a threshold on every tier, we do on two. The table is in the notes with the thresholds side by side. The theme work stays paused as you asked.",
        "Everything is on the pricing table now: four tiers each side, the free tier's limits, the trial lengths, and what each tier adds. The primary is finishing the per-seat column; the research worker is verifying the numbers against their pricing page.",
        "Their free tier caps at one hundred products and no custom domain; ours has no product cap and the domain. The research worker's write-up is in the notes with the page it read. Nothing else is pending.",
    )
}
