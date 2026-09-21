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
        var blobs = 0
        var prefetched = 0
    }

    /**
     * The steps of turn [index] from its blob [turnBlobId], the prompt first: the turn's structure, then its user
     * message and every step read through [read] — side by side, the caller's [read] bounding how many at once. A
     * blob that cannot be decoded is a step saying so in its shape, and the turn goes on without it.
     */
    suspend fun turn(index: Int, turnBlobId: String, read: suspend (String) -> ByteArray): List<HeadlessStep> = coroutineScope {
        val turnJson = fetch(read, turnBlobId)?.let { decodeOrNull(it, AgentSchemas.CONVERSATION_TURN) }
        val agent = turnJson?.get("agentConversationTurn") as? JsonObject
        if (agent == null) {
            // A shell turn, or a shape this build does not read: a blank step carrying what the blob held.
            return@coroutineScope listOf(HeadlessStep(shape = StepShape(0, "blob:turn[unread]", turnJson?.let { RecordShapes.describe(it) } ?: "unreadable"), turnIndex = index))
        }
        val promptId = agent.string("userMessage")
        val stepIds = (agent["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotBlank() } } ?: emptyList()
        val prompt = async { promptId?.let { id -> fetch(read, id)?.let { decodeOrNull(it, AgentSchemas.USER_MESSAGE) } } }
        val decodedSteps = stepIds.map { id -> async { fetch(read, id)?.let { decodeOrNull(it, AgentSchemas.CONVERSATION_STEP) } } }.awaitAll()
        val out = ArrayList<HeadlessStep>(stepIds.size * 2 + 2)
        var position = 0
        val message = prompt.await()
        when {
            promptId == null -> Unit
            message == null -> out += HeadlessStep(shape = StepShape(position++, "blob:user_message[unreadable]", "unreadable"), turnIndex = index)
            else -> out += promptStep(message, position++, index)
        }
        decodedSteps.forEachIndexed { i, step ->
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
        out
    }

    /** The prompt step: `agent.v1.UserMessage`'s text, its mode (Project or not), and whether it was steered into a running turn. */
    private fun promptStep(message: JsonObject, position: Int, index: Int): HeadlessStep {
        val text = message.string("text") ?: message.string("richText") ?: ""
        val mode = (message["mode"] as? JsonPrimitive)
        val project = mode?.intOrNull == AgentSchemas.AGENT_MODE_PROJECT || mode?.contentOrNull?.uppercase()?.let { it == "PROJECT" || it.endsWith("_PROJECT") } == true
        val steer = (message["turnSteer"] as? JsonPrimitive)?.booleanOrNull == true
        val branch = when {
            text.isBlank() && message.string("textBlobId") != null -> "blob:user_message[text-in-blob]"
            steer -> "blob:user_message[steer]"
            else -> "blob:user_message"
        }
        return HeadlessStep(userMessage = text.ifBlank { null }, projectMode = project, shape = StepShape(position, branch, RecordShapes.describe(message)), turnIndex = index, steer = steer)
    }

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
    private suspend fun fetch(read: suspend (String) -> ByteArray, id: String): ByteArray? = try {
        read(id)
    } catch (e: ConnectRpcException) {
        if (e.httpCode == 404 || e.code == "not_found" || e.isUnreadableAnswer) null else throw e
    }

    private fun decodeOrNull(bytes: ByteArray, schema: ProtoWire.Schema): JsonObject? = runCatching { ProtoWire.decode(bytes, schema) }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun snakeCase(camel: String): String = camel.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    /** The JSON of one decoded blob, for tests and the verification harness. */
    fun decode(bytes: ByteArray, schema: ProtoWire.Schema): JsonElement = ProtoWire.decode(bytes, schema)
}
