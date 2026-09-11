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

        /** Roughly a forty-line terminal at a hundred columns. */
        const val MAX_OUTPUT_CHARS = 4_000

        /** What a row opens onto above its output — a command, an MCP call's arguments — held to the same size. */
        const val MAX_DETAIL_CHARS = 4_000

        /** What a built call opens onto; the reading of [raw] happened when the call was built (see [from]). */
        fun of(call: ToolCall): ToolOutput = ToolOutput(call.detail, call.output, call.exitCode)

        /**
         * Reads a result payload for what a row of [kind] will show, clipped to [MAX_OUTPUT_LINES]. Called once,
         * while the call is built, so nothing but the clipped text outlives the event.
         */
        fun from(kind: ToolKind, detail: String?, raw: JsonElement?): ToolOutput {
            val result = raw as? JsonObject
            return when (kind) {
                ToolKind.Shell -> {
                    val out = shellText(result?.deepString("stdout", "output", "content"), result?.deepString("stderr")?.takeIf { it.isNotBlank() })
                    ToolOutput(detail, out, result?.deepInt("exitCode", "exit_code"))
                }
                ToolKind.Mcp -> ToolOutput(detail, mcpText(result)?.let(::clip))
                else -> ToolOutput(detail, null)
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

        /**
         * A command's output with its error stream after it. Once the output alone is longer than a row can show the
         * two are not joined: the error stream could not appear either way, and joining would copy the whole payload
         * only for [clip] to drop all but its head.
         */
        private fun shellText(stdout: String?, stderr: String?): String? {
            val text = when {
                stdout == null -> stderr ?: return null
                stderr == null || stdout.length > MAX_OUTPUT_CHARS -> stdout
                else -> "$stdout\n$stderr"
            }
            return clip(text).ifBlank { null }
        }

        /**
         * The head of [text]: at most [MAX_OUTPUT_LINES] lines and [MAX_OUTPUT_CHARS] characters, with what was left
         * out noted. Nothing beyond the characters that could be shown is copied, so a payload of megabytes is read
         * once and costs no more than a short one.
         */
        private fun clip(text: String): String {
            var end = text.length
            while (end > 0 && text[end - 1].isWhitespace()) end--

            // Past the character bound nothing can be shown, so the line bound is only looked for within it.
            var newlines = 0
            var lineEnd = -1
            var i = 0
            while (i < end && i < MAX_OUTPUT_CHARS) {
                val isNewline = text[i] == '\n'
                i++
                if (isNewline && ++newlines == MAX_OUTPUT_LINES) {
                    lineEnd = i - 1
                    break
                }
            }
            if (lineEnd < 0) return cut(text, end, MAX_OUTPUT_CHARS)

            while (i < end) {
                if (text[i] == '\n') newlines++
                i++
            }
            val head = text.substring(0, lineEnd) + "\n… ${newlines + 1 - MAX_OUTPUT_LINES} more lines"
            return cut(head, head.length, MAX_OUTPUT_CHARS)
        }

        /** What a row opens onto, held to [MAX_DETAIL_CHARS]: a call can name a command or an argument of any size. */
        fun detail(text: String?): String? = text?.let { cut(it, it.length, MAX_DETAIL_CHARS) }

        /** [text] up to [end], cut down to [limit] characters without splitting a surrogate pair. */
        private fun cut(text: String, end: Int, limit: Int): String {
            if (end <= limit) return text.substring(0, end)
            var cutAt = limit
            if (cutAt > 0 && Character.isHighSurrogate(text[cutAt - 1])) cutAt--
            return text.substring(0, cutAt) + "… ${end - cutAt} more characters"
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
