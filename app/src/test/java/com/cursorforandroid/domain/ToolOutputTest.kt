package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ToolOutputTest {

    private fun shellOutput(text: String) = shell(mapOf("stdout" to text))

    private fun shellWith(stdout: String, stderr: String) = shell(mapOf("stdout" to stdout, "stderr" to stderr))

    private fun shell(fields: Map<String, String>) =
        ToolOutput.from(ToolKind.Shell, null, JsonObject(fields.mapValues { JsonPrimitive(it.value) })).output!!

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

    @Test
    fun `a command that printed megabytes on one line is held to what a row shows`() {
        val result = shellOutput("x".repeat(5_000_000))
        assertThat(result).startsWith("x".repeat(ToolOutput.MAX_OUTPUT_CHARS))
        assertThat(result.length).isLessThan(ToolOutput.MAX_OUTPUT_CHARS + 64)
        assertThat(result).endsWith("… 4996000 more characters")
    }

    @Test
    fun `a command that printed a hundred thousand lines is held to what a row shows`() {
        val result = shellOutput((1..100_000).joinToString("\n") { "line $it" })
        assertThat(result.lines()).hasSize(ToolOutput.MAX_OUTPUT_LINES + 1)
        assertThat(result).startsWith("line 1\nline 2\n")
        assertThat(result).endsWith("… 99960 more lines")
    }

    @Test
    fun `the error stream follows the output`() {
        assertThat(shellWith("total 8", "warn")).isEqualTo("total 8\nwarn")
    }

    @Test
    fun `an output that already fills the row leaves no room for the error stream`() {
        val result = shellWith("x".repeat(5_000_000), "warn")
        assertThat(result).doesNotContain("warn")
        assertThat(result.length).isLessThan(ToolOutput.MAX_OUTPUT_CHARS + 64)
    }

    @Test
    fun `what a row opens onto is held to the same size`() {
        assertThat(ToolOutput.detail("git status")).isEqualTo("git status")
        val bounded = ToolOutput.detail("x".repeat(1_000_000))!!
        assertThat(bounded).startsWith("x".repeat(ToolOutput.MAX_DETAIL_CHARS))
        assertThat(bounded.length).isLessThan(ToolOutput.MAX_DETAIL_CHARS + 64)
        assertThat(ToolOutput.detail(null)).isNull()
    }
}
