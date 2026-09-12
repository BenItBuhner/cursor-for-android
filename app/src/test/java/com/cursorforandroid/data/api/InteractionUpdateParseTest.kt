package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The `interaction_update` event, in the shape `@cursor/sdk`'s `InteractionUpdate` gives it: only its tool-call
 * updates are delivered, everything else it duplicates from the simplified events is ignored (and still resumed
 * past), and the `tool_call` event's `truncated` flag is read.
 */
class InteractionUpdateParseTest {

    @Test
    fun `a tool-call-completed update is delivered with its typed tool call`() {
        val data = """
            {"type":"tool-call-completed","callId":"call-7","modelCallId":"m-1","toolCall":{"type":"edit","args":{"path":"app/src/Main.kt"},
             "result":{"status":"success","value":{"linesAdded":3,"linesRemoved":1,"diffString":"@@ -1,2 +1,4 @@\n-a\n+b\n+c\n+d"}}}}
        """.trimIndent()
        val parsed = SseParser.parse(SseFrame("interaction_update", "10-0", data))
        val event = (parsed as SseParser.Parsed.Delivered).event as RunStreamEvent.Interaction
        assertThat(event.update.isToolCallCompleted).isTrue()
        assertThat(event.update.callId).isEqualTo("call-7")
        val toolCall = event.update.toolCall!!
        assertThat(toolCall.type).isEqualTo("edit")
        assertThat((toolCall.args as JsonObject)["path"]!!.jsonPrimitive.content).isEqualTo("app/src/Main.kt")
        val value = toolCall.result!!.jsonObject["value"]!!.jsonObject
        assertThat(value["diffString"]!!.jsonPrimitive.content).startsWith("@@ -1,2 +1,4 @@")
    }

    @Test
    fun `a tool-call-started update is delivered too`() {
        val data = """{"type":"tool-call-started","callId":"call-1","toolCall":{"type":"write","args":{"path":"notes.md","fileText":"# Notes"}}}"""
        val event = SseParser.toEvent(SseFrame("interaction_update", null, data)) as RunStreamEvent.Interaction
        assertThat(event.update.isToolCallStarted).isTrue()
        assertThat(event.update.toolCall!!.type).isEqualTo("write")
    }

    @Test
    fun `updates that are not about a tool call are ignored, not undecodable`() {
        for (data in listOf(
            """{"type":"text-delta","text":"hello"}""",
            """{"type":"thinking-delta","text":"hmm"}""",
            """{"type":"turn-ended","inputTokens":1,"outputTokens":2}""",
            """{"type":"step-started","stepId":3}""",
            // A tool-call update with no call to attach to says nothing this client can use.
            """{"type":"tool-call-completed"}""",
            "{}",
        )) {
            assertThat(SseParser.parse(SseFrame("interaction_update", "1-0", data))).isEqualTo(SseParser.Parsed.Ignored)
        }
    }

    @Test
    fun `a malformed update is undecodable`() {
        assertThat(SseParser.parse(SseFrame("interaction_update", "1-0", "{not json"))).isEqualTo(SseParser.Parsed.Undecodable)
    }

    @Test
    fun `the tool_call event's truncation flag is read`() {
        val data = """{"callId":"call-2","name":"read_file","status":"completed","args":{"path":"big.log"},"truncated":{"result":true}}"""
        val event = SseParser.toEvent(SseFrame("tool_call", null, data)) as RunStreamEvent.ToolCall
        assertThat(event.call.truncated!!.result).isTrue()
        assertThat(event.call.truncated!!.args).isFalse()
        assertThat(event.call.result).isNull()

        val plain = SseParser.toEvent(SseFrame("tool_call", null, """{"callId":"c","name":"read_file","status":"running"}""")) as RunStreamEvent.ToolCall
        assertThat(plain.call.truncated).isNull()
    }
}
