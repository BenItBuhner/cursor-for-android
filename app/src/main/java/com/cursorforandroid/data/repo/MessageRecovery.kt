package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The body of a coordinator's message read out of arguments that do not parse: the pieces of a streamed
 * `SendMessage` the record never completed, a fragment cut mid-string, a serialization this build has not seen. The
 * record is the one copy of the message once the run's log has expired, so a body that can be read leniently is
 * shown — marked as recovered — rather than dropped with the call.
 *
 * Four readings, tried in order: the text as JSON (it was whole after all); the text closed as JSON — every open
 * string and bracket shut, a dangling comma or colon dropped — for a fragment cut short; the value of a `content`,
 * `message` or `text` string key found by regex, for text that is not JSON around a JSON-like core; and, when the
 * text has no JSON in it at all, the text itself, since the message is the tool's only textual argument.
 */
object MessageRecovery {

    /** The body read, and how: `json`, `repaired`, `regex` or `plain`. */
    data class Recovered(val text: String, val method: String)

    fun recover(joined: String): Recovered? {
        val text = joined.trim()
        if (text.isEmpty()) return null
        asJson(text)?.let { return Recovered(it, "json") }
        asJson(close(text))?.let { return Recovered(it, "repaired") }
        byRegex(text)?.let { return Recovered(it, "regex") }
        if (text.none { it == '{' || it == '}' || it == '"' }) return Recovered(text, "plain")
        return null
    }

    /** The message under the keys a coordinator's message tool uses, when [text] parses as a JSON object. */
    private fun asJson(text: String): String? {
        val element = runCatching { CursorJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return ToolPayloads.coordinatorMessage(element)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * [text] closed as JSON: a string left open gets its quote, then every bracket still open is shut in order. A
     * comma or colon the cut left dangling is dropped first (a colon's value stands in as an empty string).
     */
    internal fun close(text: String): String {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString -> when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' || c == '[' -> stack.addLast(c)
                c == '}' || c == ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        val out = StringBuilder(text)
        if (escaped) out.setLength(out.length - 1)
        if (inString) out.append('"')
        val tail = out.trimEnd()
        when (tail.lastOrNull()) {
            ',' -> out.setLength(tail.length - 1)
            ':' -> out.append("\"\"")
        }
        while (stack.isNotEmpty()) out.append(if (stack.removeLast() == '{') '}' else ']')
        return out.toString()
    }

    /**
     * The value of the first `content`, else `message`, else `text` key whose value is a string: everything after
     * its opening quote up to the closing one, or to the end of the text when the cut came first, JSON escapes read
     * as the characters they stand for.
     */
    private fun byRegex(text: String): String? {
        for (key in KEYS) {
            val open = Regex("\"$key\"\\s*:\\s*\"").find(text) ?: continue
            val raw = stringBody(text, open.range.last + 1)
            val value = unescape(raw)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return value
        }
        return null
    }

    /** The characters of a JSON string from [start] to its closing quote, or to the end when there is none; a trailing lone backslash dropped. */
    private fun stringBody(text: String, start: Int): String {
        var i = start
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> return text.substring(start, i)
            }
            i++
        }
        val body = text.substring(start)
        return if (escaped) body.dropLast(1) else body
    }

    private fun unescape(raw: String): String? {
        (runCatching { CursorJson.parseToJsonElement("\"$raw\"") }.getOrNull() as? JsonPrimitive)?.contentOrNull?.let { return it }
        // An escape the cut left unfinished: what can be read literally.
        return raw.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\").takeIf { it.isNotEmpty() }
    }

    private val KEYS = listOf("content", "message", "text")
}
