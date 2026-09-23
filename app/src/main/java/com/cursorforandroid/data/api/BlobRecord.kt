package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.domain.StepShape
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The account's blob-backed record of a chat read into the steps the step-indexed record used to give
 * (`HeadlessAgenticComposerResponse`, see [HeadlessStep]), so everything that read that record — the turn splitter,
 * the replay through the stream's accumulator, the payload mappers, the shape diagnostics — reads this one unchanged.
 *
 * What Cursor removed (`FetchBackgroundComposer`, September 2026) the desktop had left long before: its client reads
 * `GetLatestAgentConversationState` for the turn list — each turn a blob id (`ConversationStateStructure.turns[]`)
 * — and `GetBlobForAgentKV` for every blob, decoding them with its generated protobuf classes:
 * `agent.v1.ConversationTurnStructure { agent_conversation_turn { user_message: blob id, steps[]: blob ids,
 * send_message_step_indices[] } }`, `agent.v1.UserMessage { text, mode, is_simulated_msg, turn_steer, … }`,
 * `agent.v1.ConversationStep { assistant_message | tool_call: agent.v1.ToolCall | thinking_message }`. The
 * schemas are in [AgentSchemas]; a step in a shape they do not know keeps its unknown fields for the diagnostics
 * and is shown as far as it was read — never as another step's words.
 */
object BlobRecord {

    /** How many blobs one turn read cost, and how many the state answer had carried already. */
    class Counter {
        val blobs = AtomicInteger()
        val prefetched = AtomicInteger()
    }

    /** Where a turn read takes its blobs from (see `HeadlessConversationApi.readTurns`). */
    interface Source {
        /**
         * The blob: as held — a prefetched partial copy too, unless [whole] — or from the network. Throws the
         * record's refusal; `not_found` and an answer without a blob are the blob missing (see [read]).
         */
        suspend fun blob(id: String, whole: Boolean = false): ByteArray

        /** The blob when it is held, in memory or on disk, the network not asked; null otherwise. */
        suspend fun held(id: String): BlobCache.Held?

        /** The blob [id] was read as a turn's structure or its prompt: a partial copy of it is whole (see [BlobCache.confirm]). */
        suspend fun confirm(id: String)
    }

    /**
     * What one turn read came to: its steps (the prompt first), whether every step was read, what the turn's
     * structure lists ([stepTotal] steps, [messageSteps] of them the coordinator's messages to the user; null when the
     * structure could not be read), and the blobs asked for and answered as missing or unreadable — the drift check's
     * inputs (see `RecordPager.Raw.drift`).
     */
    class Read(
        val steps: List<HeadlessStep>,
        val complete: Boolean,
        val stepTotal: Int?,
        val messageSteps: Int?,
        val asked: Int,
        val missing: Int,
        val lastMissing: ConnectRpcException?,
        /**
         * Pieces the server failed to give after their retries (a 5xx, a dropped connection; see [ServerRetry]) and
         * the last such failure: left for later, the turn not complete — never the read's failure.
         */
        val unavailable: Int = 0,
        val lastUnavailable: Throwable? = null,
        /** The turn's structure was read: what the turn is, if not yet all it holds. */
        val readable: Boolean = true,
    )

    /** The blobs a read asked for and the ones it found missing or unreadable, and the last answer that said so. */
    private class Tally {
        val asked = AtomicInteger()
        val missing = AtomicInteger()
        @Volatile var last: ConnectRpcException? = null
        val unavailable = AtomicInteger()
        @Volatile var lastUnavailable: Throwable? = null

        fun unreadable(what: String) {
            missing.incrementAndGet()
            last = ConnectRpcException(200, ConnectRpcException.UNREADABLE_ANSWER, "A blob Cursor's GetBlobForAgentKV gave could not be read as $what.", path = ConnectRpc.path(HeadlessConversationApi.SERVICE, HeadlessConversationApi.BLOB_METHOD))
        }
    }

    /**
     * The steps of turn [index] from its blob [turnBlobId], the prompt first: the turn's structure, then its user
     * message and every step read through [read] — side by side, the caller's [read] bounding how many at once. A
     * blob that cannot be decoded is a step saying so in its shape, and the turn goes on without it.
     */
    suspend fun turn(index: Int, turnBlobId: String, read: suspend (String) -> ByteArray): List<HeadlessStep> =
        read(index, turnBlobId, object : Source {
            override suspend fun blob(id: String, whole: Boolean): ByteArray = read(id)
            override suspend fun held(id: String): BlobCache.Held? = null
            override suspend fun confirm(id: String) = Unit
        }).steps

    /**
     * Turn [index] from its blob [turnBlobId] through [source], to [plan]: the structure, the prompt (its text from a
     * blob of its own when the message keeps it there), and the steps the plan reads — every one for
     * [TurnPlan.FULL], a partial copy read again whole; for [TurnPlan.MESSAGES] the coordinator's messages and any
     * step already held. A step of the turn left for later is not a step of it yet: the read says it is not complete.
     */
    suspend fun read(index: Int, turnBlobId: String, source: Source, plan: TurnPlan = TurnPlan.FULL): Read = coroutineScope {
        val tally = Tally()
        val turn = fetch(source, turnBlobId, tally)
        if (turn === LATER) {
            // The server failed to give the turn's structure: nothing of the turn is known yet, and it is read again later.
            val blank = listOf(HeadlessStep(shape = StepShape(0, "blob:turn[unavailable]", "unavailable"), turnIndex = index))
            return@coroutineScope Read(blank, complete = false, stepTotal = null, messageSteps = null, asked = tally.asked.get(), missing = tally.missing.get(), lastMissing = tally.last, unavailable = tally.unavailable.get(), lastUnavailable = tally.lastUnavailable, readable = false)
        }
        val turnBytes = turn
        val turnJson = turnBytes?.let { decodeOrNull(it, AgentSchemas.CONVERSATION_TURN) }
        if (turnBytes != null && turnJson == null) tally.unreadable("agent.v1.ConversationTurnStructure")
        if (turnJson != null) source.confirm(turnBlobId)
        val agent = turnJson?.get("agentConversationTurn") as? JsonObject
        if (agent == null) {
            // A shell turn, or a shape this build does not read: a blank step carrying what the blob held.
            val blank = listOf(HeadlessStep(shape = StepShape(0, "blob:turn[unread]", turnJson?.let { RecordShapes.describe(it) } ?: "unreadable"), turnIndex = index))
            return@coroutineScope Read(blank, complete = true, stepTotal = null, messageSteps = null, asked = tally.asked.get(), missing = tally.missing.get(), lastMissing = tally.last)
        }
        val promptId = agent.string("userMessage")
        val stepIds = (agent["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotBlank() } } ?: emptyList()
        val messageIndices = (agent["sendMessageStepIndices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet() ?: emptySet()
        // The prompt, or [LATER] when the server failed to give it: the turn is shown without it until it is read again.
        val prompt = async {
            promptId?.let { id ->
                val bytes = fetch(source, id, tally) ?: return@let null
                if (bytes === LATER) return@let LATER
                val message = decodeOrNull(bytes, AgentSchemas.USER_MESSAGE)
                if (message == null) tally.unreadable("agent.v1.UserMessage") else source.confirm(id)
                message?.let { withBlobText(it, source, tally) }
            }
        }
        // Null for a step left for later, a Pair of its id and what it decoded to (null: unreadable) otherwise.
        val decodedSteps = stepIds.mapIndexed { i, id ->
            async {
                val bytes = when {
                    plan == TurnPlan.FULL -> fetch(source, id, tally, whole = true)
                    i in messageIndices -> fetch(source, id, tally)
                    else -> source.held(id)?.bytes ?: return@async null
                }
                if (bytes === LATER) return@async null
                val decoded = bytes?.let { decodeOrNull(it, AgentSchemas.CONVERSATION_STEP) }
                if (bytes != null && decoded == null) tally.unreadable("agent.v1.ConversationStep")
                id to decoded
            }
        }.awaitAll()
        val out = ArrayList<HeadlessStep>(stepIds.size * 2 + 2)
        var position = 0
        val message = prompt.await()
        var complete = message !== LATER
        when {
            promptId == null || message === LATER -> Unit
            message == null -> out += HeadlessStep(shape = StepShape(position++, "blob:user_message[unreadable]", "unreadable"), turnIndex = index)
            else -> out += promptStep(message as JsonObject, position++, index)
        }
        decodedSteps.forEachIndexed { i, entry ->
            if (entry == null) {
                complete = false
                return@forEachIndexed
            }
            val step = entry.second
            if (step == null) {
                out += HeadlessStep(shape = StepShape(position++, "blob:step[unreadable]", "unreadable"), turnIndex = index)
                return@forEachIndexed
            }
            val shape = RecordShapes.describe(step)
            when {
                step["assistantMessage"] is JsonObject -> {
                    val text = (step["assistantMessage"] as JsonObject).string("text") ?: ""
                    out += HeadlessStep(text = text, shape = StepShape(position++, "blob:assistant_message", shape), turnIndex = index)
                }
                step["thinkingMessage"] is JsonObject -> {
                    val text = (step["thinkingMessage"] as JsonObject).string("text") ?: ""
                    out += HeadlessStep(thinking = text, shape = StepShape(position++, "blob:thinking_message", shape), turnIndex = index)
                }
                step["toolCall"] is JsonObject -> out += toolCallSteps(step["toolCall"] as JsonObject, position, index, shape, stepIndexInTurn = i).also { position += it.size }
                else -> out += HeadlessStep(shape = StepShape(position++, "blob:step[unread]", shape), turnIndex = index)
            }
        }
        // A turn is never without a step: the turn splitter cuts at them, and a turn with none would not be one.
        if (out.isEmpty()) out += HeadlessStep(shape = StepShape(0, if (complete) "blob:turn[empty]" else "blob:turn[pending]", "{}"), turnIndex = index)
        Read(
            out, complete, stepTotal = stepIds.size, messageSteps = messageIndices.count { it in stepIds.indices },
            asked = tally.asked.get(), missing = tally.missing.get(), lastMissing = tally.last, unavailable = tally.unavailable.get(), lastUnavailable = tally.lastUnavailable,
        )
    }

    /** What [fetch] answers for a piece the server failed to give after its retries: read again later, not missing. */
    private val LATER = ByteArray(0)

    /**
     * A prompt that keeps its text in a blob of its own (`text_blob_id`, a long prompt's): the text read from there —
     * the blob's bytes as UTF-8 text, or, when they read as a message, its `text` — under the key [promptStep] reads.
     * Unchanged when the message carries its text, or the blob cannot be had.
     */
    private suspend fun withBlobText(message: JsonObject, source: Source, tally: Tally): JsonObject {
        if (message.string("text") != null || message.string("richText") != null) return message
        val id = message.string("textBlobId") ?: return message
        val bytes = fetch(source, id, tally) ?: return message
        val text = textOf(bytes) ?: return message
        source.confirm(id)
        return JsonObject(message + ("text" to JsonPrimitive(text)) + (TEXT_FROM_BLOB to JsonPrimitive(true)))
    }

    /** The text a prompt's text blob holds: the bytes as UTF-8 when they are text, else a message's `text` field. */
    internal fun textOf(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
        val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        if (text != null && text.isNotBlank() && text.none { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) return text
        val asMessage = runCatching { ProtoWire.decode(bytes, AgentSchemas.USER_MESSAGE) }.getOrNull()
        return asMessage?.string("text")
    }

    /** The prompt step: `agent.v1.UserMessage`'s text, its mode (Project or not), and whether it was steered into a running turn. */
    private fun promptStep(message: JsonObject, position: Int, index: Int): HeadlessStep {
        val text = message.string("text") ?: message.string("richText") ?: ""
        val mode = (message["mode"] as? JsonPrimitive)
        val project = mode?.intOrNull == AgentSchemas.AGENT_MODE_PROJECT || mode?.contentOrNull?.uppercase()?.let { it == "PROJECT" || it.endsWith("_PROJECT") } == true
        val steer = (message["turnSteer"] as? JsonPrimitive)?.booleanOrNull == true
        val fromBlob = (message[TEXT_FROM_BLOB] as? JsonPrimitive)?.booleanOrNull == true
        val branch = when {
            text.isBlank() && message.string("textBlobId") != null -> "blob:user_message[text-in-blob]"
            fromBlob -> "blob:user_message[text-blob]"
            steer -> "blob:user_message[steer]"
            else -> "blob:user_message"
        }
        return HeadlessStep(userMessage = text.ifBlank { null }, projectMode = project, shape = StepShape(position, branch, RecordShapes.describe(JsonObject(message - TEXT_FROM_BLOB))), turnIndex = index, steer = steer)
    }

    /** The key [withBlobText] marks a prompt with whose text came from its text blob. */
    private const val TEXT_FROM_BLOB = "_textFromBlob"

    /**
     * A tool call step as two of the step-indexed record's: the call with its arguments, and its result when the
     * step carries one. The variant the oneof filled names the tool (`sendMessageToolCall` → `send_message`, the
     * spelling `ToolNames` reads); the call's id is the `ToolCall`'s own, else the one its arguments carry, else a
     * name made of the turn and the step so the pair still meets.
     */
    private fun toolCallSteps(call: JsonObject, position: Int, index: Int, shape: String, stepIndexInTurn: Int): List<HeadlessStep> {
        val variant = AgentSchemas.TOOL_VARIANTS.entries.firstOrNull { (_, name) -> call[AgentSchemas.variantKey(name)] is JsonObject }
        val body = variant?.let { call[AgentSchemas.variantKey(it.value)] as JsonObject }
        val args = body?.get("args")
        val result = body?.get("result")
        val callId = call.string("toolCallId")
            ?: (args as? JsonObject)?.string("toolCallId")
            ?: "turn-$index:step:$stepIndexInTurn:tool"
        val name = variant?.value?.let { displayName(it) } ?: call.keys.firstOrNull { it.endsWith("ToolCall") }?.removeSuffix("ToolCall")?.let(::snakeCase) ?: ""
        val source = "id=${if (call.string("toolCallId") != null) "toolCallId" else if ((args as? JsonObject)?.string("toolCallId") != null) "args" else "position"} name=${if (variant != null) "variant" else if (name.isNotBlank()) "key" else "blank"} args=${if (args is JsonObject) "json" else if (args != null) "value" else "none"}"
        val steps = ArrayList<HeadlessStep>(2)
        steps += HeadlessStep(toolCall = HeadlessToolCall(callId, name, args, source = source), shape = StepShape(position, "blob:tool_call[$source]", shape), turnIndex = index)
        if (result != null) steps += HeadlessStep(toolResult = HeadlessToolResult(callId, result), shape = StepShape(position + 1, "blob:tool_result", "{result:${RecordShapes.describe(result)}}"), turnIndex = index)
        return steps
    }

    /**
     * The name a tool goes by on the documented stream's `tool_call` events, for the variant the record's oneof
     * filled: `shell` → `run_terminal_cmd`, `read` → `read_file`, so a call reads the same from either source (the
     * diagnostics list names, and `ToolNames` reads both spellings). A variant without one keeps its own name.
     */
    fun displayName(variant: String): String = DISPLAY_NAMES[variant] ?: variant

    private val DISPLAY_NAMES = mapOf(
        "shell" to "run_terminal_cmd", "read" to "read_file", "edit" to "edit_file", "delete" to "delete_file", "grep" to "grep_search",
        "glob" to "glob_file_search", "ls" to "list_dir", "sem_search" to "codebase_search", "update_todos" to "todo_write", "read_lints" to "read_lints",
        "write_shell_stdin" to "write_shell_stdin", "web_fetch" to "web_fetch", "fetch" to "fetch",
    )

    /**
     * One blob, or null for a blob the account no longer has (`not_found`) or answered with nothing readable: the
     * step is shown as unreadable and the turn goes on. Any other refusal — the session, a rate limit, the network —
     * is the record's refusal and is thrown for the caller to say.
     */
    private suspend fun fetch(source: Source, id: String, tally: Tally, whole: Boolean = false): ByteArray? = try {
        tally.asked.incrementAndGet()
        source.blob(id, whole)
    } catch (e: IOException) {
        when {
            e is ConnectRpcException && (e.httpCode == 404 || e.code == "not_found" || e.isUnreadableAnswer) -> {
                tally.missing.incrementAndGet()
                tally.last = e
                null
            }
            // The server's own failure, its retries spent (see HeadlessConversationApi.fetchBlob): this piece waits, the rest go on.
            ServerRetry.isTransient(e) -> {
                tally.unavailable.incrementAndGet()
                tally.lastUnavailable = e
                LATER
            }
            else -> throw e
        }
    }

    private fun decodeOrNull(bytes: ByteArray, schema: ProtoWire.Schema): JsonObject? = runCatching { ProtoWire.decode(bytes, schema) }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun snakeCase(camel: String): String = camel.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    /** The JSON of one decoded blob, for tests and the verification harness. */
    fun decode(bytes: ByteArray, schema: ProtoWire.Schema): JsonElement = ProtoWire.decode(bytes, schema)
}
