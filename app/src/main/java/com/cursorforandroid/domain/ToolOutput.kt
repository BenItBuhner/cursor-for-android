package com.cursorforandroid.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a tool call opens onto when tapped, the way a shell call on the desktop opens onto its terminal and an MCP call
 * onto its input and output. The result payloads are tool-specific and unstable, so the text is looked for under the
 * names the public stream, the desktop build and the SDK use, and a call whose result says nothing shows only what
 * it was asked ([ToolCall.detail]).
 */
data class ToolOutput(
    /** The command, the path, the query, the MCP input: what the tool was asked, as one block. */
    val input: String?,
    /** What came back: the command's output, the MCP tool's text; null when nothing was kept. */
    val output: String?,
    /** A command's exit code, when reported. */
    val exitCode: Int? = null,
) {
    val isEmpty: Boolean get() = input == null && output == null && exitCode == null

    companion object {
        /** Lines of output kept before the rest is elided. */
        const val MAX_OUTPUT_LINES = 40

        fun of(call: ToolCall): ToolOutput {
            val result = call.result as? JsonObject
            return when (call.kind) {
                ToolKind.Shell -> {
                    val out = listOfNotNull(result?.deepString("stdout", "output", "content"), result?.deepString("stderr")?.takeIf { it.isNotBlank() })
                        .joinToString("\n").ifBlank { null }
                    ToolOutput(call.detail, out?.let(::clip), result?.deepInt("exitCode", "exit_code"))
                }
                ToolKind.Mcp -> ToolOutput(call.detail, mcpText(result)?.let(::clip))
                else -> ToolOutput(call.detail, null)
            }
        }

        /** An MCP result's text: `value.result`, or the `text` of each `content` item, or the error. */
        private fun mcpText(result: JsonObject?): String? {
            if (result == null) return null
            result.deepString("error")?.let { return it }
            val content = result.deep("content", 0) as? JsonArray
            if (content != null) {
                val texts = content.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    (obj["text"] as? JsonPrimitive)?.contentOrNull ?: ((obj["text"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                }
                if (texts.isNotEmpty()) return texts.joinToString("\n").trim()
            }
            return result.deepString("result", "output", "text")
        }

        private fun clip(text: String): String {
            val lines = text.trimEnd().lines()
            if (lines.size <= MAX_OUTPUT_LINES) return text.trimEnd()
            return lines.take(MAX_OUTPUT_LINES).joinToString("\n") + "\n… ${lines.size - MAX_OUTPUT_LINES} more lines"
        }

        private fun JsonObject.deepString(vararg keys: String): String? {
            for (key in keys) (deep(key, 0) as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) return it }
            return null
        }

        private fun JsonObject.deepInt(vararg keys: String): Int? {
            for (key in keys) (deep(key, 0) as? JsonPrimitive)?.intOrNull?.let { return it }
            return null
        }

        /** The value under [key] within three levels: results wrap their fields in `success` / `value` / `internal`. */
        private fun JsonObject.deep(key: String, depth: Int): JsonElement? {
            this[key]?.let { return it }
            if (depth >= 3) return null
            for (value in values) if (value is JsonObject) value.deep(key, depth + 1)?.let { return it }
            return null
        }
    }
}
