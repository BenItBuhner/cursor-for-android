package com.cursorforandroid.data.api.proto

import com.cursorforandroid.data.api.proto.ProtoWire.Kind
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * The protobuf wire reader against the `agent.v1` schemas: a step written the way the account's blobs are comes back
 * as the proto3 JSON the payload mappers read; a field the schema does not know is kept by number rather than lost;
 * bytes that are not a message are refused rather than read into nonsense.
 */
class ProtoWireTest {

    @Test
    fun `a send_message step decodes to the JSON the coordinator's message is read from`() {
        val step = buildJsonObject {
            put("toolCall", buildJsonObject {
                put("sendMessageToolCall", buildJsonObject {
                    put("args", buildJsonObject { put("text", buildJsonObject { put("content", "Done: the table is filed.") }) })
                    put("result", buildJsonObject { put("success", buildJsonObject { put("timestamp", "1789341071043"); put("messageId", "msg_01") }) })
                })
                put("toolCallId", "toolu_1_1")
                put("startedAtMs", "1789341070000")
            })
        }
        val bytes = ProtoEncoder.encode(step, AgentSchemas.CONVERSATION_STEP)
        val decoded = ProtoWire.decode(bytes, AgentSchemas.CONVERSATION_STEP)
        val call = decoded["toolCall"]!!.jsonObject
        assertThat(call["toolCallId"]!!.jsonPrimitive.content).isEqualTo("toolu_1_1")
        assertThat(call["startedAtMs"]!!.jsonPrimitive.content).isEqualTo("1789341070000")
        val message = call["sendMessageToolCall"]!!.jsonObject
        assertThat(message["args"]!!.jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("Done: the table is filed.")
        assertThat(message["result"]!!.jsonObject["success"]!!.jsonObject["messageId"]!!.jsonPrimitive.content).isEqualTo("msg_01")
        assertThat(decoded.containsKey(ProtoWire.UNKNOWN_FIELDS)).isFalse()
    }

    @Test
    fun `a turn structure round-trips its blob ids, repeated indices and the user message's mode`() {
        val turn = buildJsonObject {
            put("agentConversationTurn", buildJsonObject {
                put("userMessage", BlobIds.id("um-3"))
                put("steps", buildJsonArray { add(JsonPrimitive(BlobIds.id("step-3-0"))); add(JsonPrimitive(BlobIds.id("step-3-1"))) })
                put("sendMessageStepIndices", buildJsonArray { add(JsonPrimitive(1)) })
                put("requestId", "req-3")
            })
        }
        val decoded = ProtoWire.decode(ProtoEncoder.encode(turn, AgentSchemas.CONVERSATION_TURN), AgentSchemas.CONVERSATION_TURN)
        val agent = decoded["agentConversationTurn"]!!.jsonObject
        assertThat(agent["userMessage"]!!.jsonPrimitive.content).isEqualTo(BlobIds.id("um-3"))
        assertThat(agent["steps"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly(BlobIds.id("step-3-0"), BlobIds.id("step-3-1")).inOrder()
        assertThat(agent["sendMessageStepIndices"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("1")
        val message = buildJsonObject { put("text", "Where are we?"); put("messageId", "m"); put("mode", AgentSchemas.AGENT_MODE_PROJECT); put("turnSteer", true) }
        val user = ProtoWire.decode(ProtoEncoder.encode(message, AgentSchemas.USER_MESSAGE), AgentSchemas.USER_MESSAGE)
        assertThat(user["mode"]!!.jsonPrimitive.content).isEqualTo("6")
        assertThat(user["turnSteer"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(user["text"]!!.jsonPrimitive.content).isEqualTo("Where are we?")
    }

    @Test
    fun `a field the schema does not know is kept by number, and a variant it does not know keeps its name`() {
        val step = buildJsonObject { put("assistantMessage", buildJsonObject { put("text", "Noted.") }) }
        val unknown = listOf(Triple(99, Kind.STRING, JsonPrimitive("later") as kotlinx.serialization.json.JsonElement), Triple(100, Kind.INT32, JsonPrimitive(7) as kotlinx.serialization.json.JsonElement))
        val decoded = ProtoWire.decode(ProtoEncoder.encode(step, AgentSchemas.CONVERSATION_STEP, unknown), AgentSchemas.CONVERSATION_STEP)
        assertThat(decoded["assistantMessage"]!!.jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("Noted.")
        assertThat(decoded[ProtoWire.UNKNOWN_FIELDS]!!.jsonPrimitive.content).isEqualTo("99:len=5,100:varint")
        // A tool the desktop knows and this build has no schema for: named, with its fields kept by number.
        val call = buildJsonObject { put("toolCall", buildJsonObject { put("writeCanvasToolCall", JsonObject(emptyMap())); put("toolCallId", "c-1") }) }
        val bytes = ProtoEncoder.encode(call, AgentSchemas.CONVERSATION_STEP)
        val read = ProtoWire.decode(bytes, AgentSchemas.CONVERSATION_STEP)["toolCall"]!!.jsonObject
        assertThat(read.keys).containsAtLeast("writeCanvasToolCall", "toolCallId")
    }

    @Test
    fun `bytes that are not a message are refused`() {
        val garbage = "not a protobuf message at all".toByteArray()
        val result = runCatching { ProtoWire.decode(garbage, AgentSchemas.CONVERSATION_STEP) }
        assertThat(result.exceptionOrNull()).isInstanceOf(ProtoWire.MalformedException::class.java)
        // A message whose length runs past the end.
        val cut = ProtoEncoder.encode(buildJsonObject { put("assistantMessage", buildJsonObject { put("text", "a long enough text to cut") }) }, AgentSchemas.CONVERSATION_STEP).copyOfRange(0, 6)
        assertThat(runCatching { ProtoWire.decode(cut, AgentSchemas.CONVERSATION_STEP) }.exceptionOrNull()).isInstanceOf(ProtoWire.MalformedException::class.java)
    }

    @Test
    fun `every tool variant of the desktop's oneof is named and reads its id`() {
        for ((no, name) in AgentSchemas.TOOL_VARIANTS) {
            val call = buildJsonObject { put("toolCall", buildJsonObject { put(AgentSchemas.variantKey(name), JsonObject(emptyMap())); put("toolCallId", "c-$no") }) }
            val read = ProtoWire.decode(ProtoEncoder.encode(call, AgentSchemas.CONVERSATION_STEP), AgentSchemas.CONVERSATION_STEP)["toolCall"]!!.jsonObject
            assertThat(read.keys).contains(AgentSchemas.variantKey(name))
            assertThat(read["toolCallId"]!!.jsonPrimitive.content).isEqualTo("c-$no")
        }
        assertThat(AgentSchemas.TOOL_CALL.fields[55]!!.name).isEqualTo("sendMessageToolCall")
        assertThat(AgentSchemas.TOOL_CALL.fields[74]!!.name).isEqualTo("sendToAgentToolCall")
    }

    private object BlobIds {
        fun id(name: String): String = java.util.Base64.getEncoder().encodeToString(name.toByteArray())
    }


    @Suppress("unused")
    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }
}
