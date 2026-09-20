package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoEncoder
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
     * The blob-backed record of the legacy [steps]. [unknownStepFields] adds fields no schema knows to every step
     * blob, the way a server ahead of this build would; [omitPrompt] leaves the [omitPromptOfTurn]th turn's
     * user-message blob out, the way a prompt whose text lives in a blob of its own reads.
     */
    fun record(steps: List<JsonObject>, unknownStepFields: Boolean = false, omitPromptOfTurn: Int = -1): Record {
        val blobs = LinkedHashMap<String, ByteArray>()
        val turnIds = ArrayList<String>()
        turns(steps).forEachIndexed { t, turn ->
            val prompt = turn.firstOrNull { it.containsKey("humanMessage") || it.containsKey("userMessage") }
            var promptId: String? = null
            if (prompt != null && t != omitPromptOfTurn) {
                val human = (prompt["humanMessage"] ?: prompt["userMessage"]) as JsonObject
                val text = human["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val mode = human["agentMode"]?.jsonPrimitive?.contentOrNull
                val message = buildJsonObject {
                    put("text", text)
                    put("messageId", "msg-$t")
                    if (mode == "AGENT_MODE_PROJECT") put("mode", AgentSchemas.AGENT_MODE_PROJECT)
                    if (human["turnSteer"]?.jsonPrimitive?.contentOrNull == "true") put("turnSteer", true)
                }
                val bytes = ProtoEncoder.encode(message, AgentSchemas.USER_MESSAGE)
                promptId = id(bytes)
                blobs[promptId] = bytes
            }
            // Tool calls and their results pair by id into one step, at the call's place.
            val calls = LinkedHashMap<String, Pair<JsonObject, JsonElement?>>()
            val order = ArrayList<Any>()
            for (step in turn) {
                when {
                    step.containsKey("toolCall") -> {
                        val call = step["toolCall"]!!.jsonObject
                        val id = call["toolCallId"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (id !in calls) { calls[id] = call to null; order += id }
                    }
                    step.containsKey("finalToolResult") -> {
                        val result = step["finalToolResult"]!!.jsonObject
                        val id = result["toolCallId"]?.jsonPrimitive?.contentOrNull ?: continue
                        calls[id]?.let { calls[id] = it.first to result["result"] }
                    }
                    step.containsKey("text") && step["isMessageDone"]?.jsonPrimitive?.contentOrNull != "true" && step["text"]!!.jsonPrimitive.content.isNotEmpty() -> order += step
                    step.containsKey("thinking") -> order += step
                }
            }
            val stepIds = ArrayList<String>()
            order.forEachIndexed { i, entry ->
                val step: JsonObject = when (entry) {
                    is String -> {
                        val (call, result) = calls.getValue(entry)
                        toolCallStep(entry, call, result)
                    }
                    is JsonObject -> if (entry.containsKey("thinking")) buildJsonObject { put("thinkingMessage", buildJsonObject { put("text", entry["thinking"]!!.jsonObject["text"]?.jsonPrimitive?.content ?: "") }) }
                    else buildJsonObject { put("assistantMessage", buildJsonObject { put("text", entry["text"]!!.jsonPrimitive.content) }) }
                    else -> error("unreachable")
                }
                val unknown = if (unknownStepFields) listOf(Triple(99, com.cursorforandroid.data.api.proto.ProtoWire.Kind.STRING, JsonPrimitive("a field this build does not know") as JsonElement)) else emptyList()
                val bytes = ProtoEncoder.encode(step, AgentSchemas.CONVERSATION_STEP, unknown)
                // Named by content, as the account names its blobs: the same step is the same blob, a step that changed a new one.
                val stepId = id(bytes)
                blobs[stepId] = bytes
                stepIds += stepId
            }
            val messageIndices = order.withIndex().filter { (_, e) -> e is String && variantOf(calls.getValue(e).first) == "send_message" }.map { it.index }
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

    /** `agent.v1.ConversationStep { tool_call: ToolCall { <variant>ToolCall { args, result }, tool_call_id } }` from a legacy call and its result. */
    private fun toolCallStep(id: String, call: JsonObject, result: JsonElement?): JsonObject {
        val variant = variantOf(call)
        val args = call["rawArgs"]?.jsonPrimitive?.contentOrNull?.let { runCatching { CursorJson.parseToJsonElement(it) }.getOrNull() as? JsonObject } ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("toolCall", buildJsonObject {
                put(AgentSchemas.variantKey(variant), buildJsonObject {
                    put("args", renamed(args, variant))
                    if (result is JsonObject) put("result", renamed(result, variant))
                })
                put("toolCallId", id)
            })
        }
    }

    /** The `agent.v1.ToolCall` variant a legacy call's name maps to. */
    fun variantOf(call: JsonObject): String = when (call["name"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
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
