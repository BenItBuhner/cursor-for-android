package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ToolOutputTest {

    private fun shellOutput(text: String) = ToolOutput.from(
        ToolKind.Shell,
        null,
        Json.parseToJsonElement("""{"stdout":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}"""),
    ).output!!

    @Test
    fun `line limit unchanged for 41 lines`() {
        val text = (1..41).joinToString("\n") { "line $it" }
        assertThat(shellOutput(text)).endsWith("… 1 more lines")
    }

    @Test
    fun `character limit on one long line`() {
        val text = "x".repeat(10_000)
        assertThat(shellOutput(text)).isEqualTo("x".repeat(ToolOutput.MAX_OUTPUT_CHARS) + "… 6000 more characters")
    }

    @Test
    fun `character limit not applied below threshold`() {
        val text = "x".repeat(3_999)
        assertThat(shellOutput(text)).isEqualTo(text)
    }

    @Test
    fun `character cut backs off inside surrogate pair`() {
        val text = "x".repeat(ToolOutput.MAX_OUTPUT_CHARS - 1) + "😀"
        val result = shellOutput(text)
        assertThat(result).isEqualTo("x".repeat(ToolOutput.MAX_OUTPUT_CHARS - 1) + "… 2 more characters")
    }
}
