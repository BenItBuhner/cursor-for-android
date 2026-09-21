package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoEncoder
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.data.repo.MessageRecovery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * The account's blob-backed record as the fault server serves it, made from the step-indexed record the fixtures
 * already write (`HeadlessAgenticComposerResponse` JSON, see `SevenRunCoordinator`, `LongProject`): each turn a
 * blob (`agent.v1.ConversationTurnStructure`), its prompt a blob (`agent.v1.UserMessage`), each step a blob
 * (`agent.v1.ConversationStep`), all protobuf binary named by an id the state's `turns[]` carries — the shape
 * Cursor 3.21.16's desktop reads (see `BlobRecord`). One source of truth for both paths, so a test that scripts the
 * legacy steps scripts the blobs with them.
 */
object BlobFixtures {

    /** A chat's blobs by id (base64, as Connect JSON writes `bytes`), and its turns' ids oldest first. */
    class Record(val turnIds: List<String>, val blobs: Map<String, ByteArray>) {
        val turnCount: Int get() = turnIds.size
    }

    /** The turn structures of [steps] as legacy steps cut at their prompts: one entry per turn, its steps in order. */
    fun turns(steps: List<JsonObject>): List<List<JsonObject>> {
        val out = ArrayList<MutableList<JsonObject>>()
        for (step in steps) {
            if (step.containsKey("humanMessage") || step.containsKey("userMessage") || out.isEmpty()) out.add(ArrayList())
            out.last().add(step)
        }
        return out
    }

    /**
     * The blob-backed record of the legacy [steps], read through the app's own reader of that wire
     * (`HeadlessConversationApi.parseStep`): a turn's calls come together by id — the name and arguments from
     * whichever step carried them, a streamed call's pieces joined until they read as JSON, the result paired on —
     * into one `ConversationStep` each, as the account stores them whole. A turn's `error` step has no blob to go
     * in (the blob-backed record keeps a turn's failure on the agent's state, not in the turn), and is left out.
     * [unknownStepFields] adds fields no schema knows to every step blob, the way a server ahead of this build
     * would; [omitPromptOfTurn] leaves that turn's user-message blob out.
     */
    fun record(steps: List<JsonObject>, unknownStepFields: Boolean = false, omitPromptOfTurn: Int = -1): Record {
        val blobs = LinkedHashMap<String, ByteArray>()
        val turnIds = ArrayList<String>()
        val parsed = steps.mapIndexed { i, json -> HeadlessConversationApi.parseStep(json, i) }
        HeadlessTranscript.split(parsed).forEachIndexed { t, turn ->
            var promptId: String? = null
            if (turn.prompt != null && t != omitPromptOfTurn) {
                val message = buildJsonObject {
                    put("text", turn.prompt)
                    put("messageId", "msg-$t")
                    if (turn.projectMode) put("mode", AgentSchemas.AGENT_MODE_PROJECT)
                    if (turn.steer) put("turnSteer", true)
                }
                val bytes = ProtoEncoder.encode(message, AgentSchemas.USER_MESSAGE)
                promptId = id(bytes)
                blobs[promptId] = bytes
            }
            // The turn's calls by id, at the place of their first step; text and thoughts at theirs.
            val calls = LinkedHashMap<String, Call>()
            val order = ArrayList<Any>()
            for (step in turn.steps) {
                when {
                    step.toolCall != null -> {
                        val call = calls.getOrPut(step.toolCall.callId) { order += step.toolCall.callId; Call() }
                        call.take(step.toolCall)
                    }
                    step.toolResult != null -> calls.getOrPut(step.toolResult.callId) { order += step.toolResult.callId; Call() }.result = step.toolResult.result
                    !step.text.isNullOrEmpty() -> order += step
                    step.thinking != null -> order += step
                }
            }
            calls.values.forEach { it.settle() }
            // A nameless call whose pieces were a streamed message's (see HeadlessTranscript.recover): not a call of its own.
            val named = order.filter { it !is String || calls.getValue(it).name.isNotBlank() || calls.getValue(it).args != null }
            val stepIds = ArrayList<String>()
            named.forEach { entry ->
                val step: JsonObject = when (entry) {
                    is String -> toolCallStep(entry, calls.getValue(entry))
                    is HeadlessStep -> if (entry.thinking != null) buildJsonObject { put("thinkingMessage", buildJsonObject { put("text", entry.thinking) }) }
                    else buildJsonObject { put("assistantMessage", buildJsonObject { put("text", entry.text!!) }) }
                    else -> error("unreachable")
                }
                val unknown = if (unknownStepFields) listOf(Triple(99, ProtoWire.Kind.STRING, JsonPrimitive("a field this build does not know") as JsonElement)) else emptyList()
                val bytes = ProtoEncoder.encode(step, AgentSchemas.CONVERSATION_STEP, unknown)
                // Named by content, as the account names its blobs: the same step is the same blob, a step that changed a new one.
                val stepId = id(bytes)
                blobs[stepId] = bytes
                stepIds += stepId
            }
            val messageIndices = named.withIndex().filter { (_, e) -> e is String && variantOf(calls.getValue(e).name) == "send_message" }.map { it.index }
            val structure = buildJsonObject {
                put("agentConversationTurn", buildJsonObject {
                    promptId?.let { put("userMessage", it) }
                    put("steps", JsonArray(stepIds.map { JsonPrimitive(it) }))
                    put("sendMessageStepIndices", JsonArray(messageIndices.map { JsonPrimitive(it) }))
                    put("requestId", "req-$t")
                })
            }
            val turnBytes = ProtoEncoder.encode(structure, AgentSchemas.CONVERSATION_TURN)
            val turnId = id(turnBytes)
            blobs[turnId] = turnBytes
            turnIds += turnId
        }
        return Record(turnIds, blobs)
    }

    /** One call as the legacy steps told it: name, arguments (whole, or joined from a streamed call's pieces), result. */
    private class Call {
        var name = ""
        var args: JsonObject? = null
        val pieces = StringBuilder()
        var result: JsonElement? = null

        fun take(call: HeadlessToolCall) {
            if (call.name.isNotBlank()) name = call.name
            (call.args as? JsonObject)?.let { args = it }
            call.rawArgs?.let { piece ->
                if (piece.startsWith(pieces)) pieces.setLength(0)
                pieces.append(piece)
            }
        }

        /** The pieces as the arguments once whole; read leniently when they never parse (the account stores the message whole). */
        fun settle() {
            if (args != null || pieces.isEmpty()) return
            args = runCatching { CursorJson.parseToJsonElement(pieces.toString()) }.getOrNull() as? JsonObject
                ?: MessageRecovery.recover(pieces.toString())?.let { read -> buildJsonObject { put("text", buildJsonObject { put("content", read.text) }) } }
        }
    }

    /** `agent.v1.ConversationStep { tool_call: ToolCall { <variant>ToolCall { args, result }, tool_call_id } }` from a legacy call. */
    private fun toolCallStep(id: String, call: Call): JsonObject {
        val variant = variantOf(call.name)
        val args = call.args ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("toolCall", buildJsonObject {
                put(AgentSchemas.variantKey(variant), buildJsonObject {
                    put("args", renamed(args, variant))
                    (call.result as? JsonObject)?.let { put("result", renamed(it, variant)) }
                })
                put("toolCallId", id)
            })
        }
    }

    /** The `agent.v1.ToolCall` variant a legacy call's name maps to. */
    fun variantOf(name: String): String = when (name.lowercase()) {
        "sendmessage", "send_message" -> "send_message"
        "send_to_user", "sendtouser" -> "send_to_user"
        "send_to_agent", "sendtoagent" -> "send_to_agent"
        "get_agent_status", "getagentstatus" -> "get_agent_status"
        "create_agent", "createagent" -> "create_agent"
        "stop_agent", "stopagent" -> "stop_agent"
        "read_agent_transcript", "readagenttranscript" -> "read_agent_transcript"
        "read_file", "read_file_v2", "read" -> "read"
        "search_replace", "edit_file", "edit_file_v2", "edit" -> "edit"
        "run_terminal_cmd", "run_terminal_command_v2", "shell" -> "shell"
        "grep_search", "grep" -> "grep"
        "list_dir", "ls" -> "ls"
        "update_current_step", "communicate_update" -> "communicate_update"
        "todo_write", "update_todos" -> "update_todos"
        "task", "task_v2" -> "task"
        "mcp", "call_mcp_tool" -> "mcp"
        "ask_question" -> "ask_question"
        else -> "mcp"
    }

    /** The legacy fixtures' argument and result keys under the `agent.v1` names, where they differ. */
    private fun renamed(json: JsonObject, variant: String): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in json) {
            val name = when (key) {
                "target_file", "file_path" -> "path"
                else -> key
            }
            out[name] = when {
                key == "success" && value is JsonObject -> renamed(value, variant)
                else -> value
            }
        }
        if (variant == "read" && out["contents"] != null) out["content"] = out.remove("contents")!!
        return JsonObject(out)
    }

    /** A blob id as the state carries it: the blob's content hash (the account names blobs by content), base64. */
    fun id(bytes: ByteArray): String = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(20))

    /** The id of the [index]th step blob of turn [t] of [record]: for tests that want to break one blob in particular. */
    fun stepId(record: Record, t: Int, index: Int): String {
        val turn = com.cursorforandroid.data.api.proto.ProtoWire.decode(record.blobs.getValue(record.turnIds[t]), AgentSchemas.CONVERSATION_TURN)
        return (turn["agentConversationTurn"]!!.jsonObject["steps"] as JsonArray)[index].jsonPrimitive.content
    }
}
