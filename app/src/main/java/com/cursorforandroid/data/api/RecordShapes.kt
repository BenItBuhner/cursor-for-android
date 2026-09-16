package com.cursorforandroid.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The redacted shape of a JSON value, for the transcript diagnostics (see `StepShape`): every key with the type of
 * its value and never the value itself, except an enum-like token under a key that names a kind of thing — the
 * tool's `name` or `tool` enum on a call, an `agentMode`, a `type`, a `case` — which is what the dump exists to show.
 *
 * The grammar, so a dump can be read back into a fixture of the same shape (`ShapeFixtures` in the tests):
 *
 * ```
 * value  := object | array | "null" | "true" | "false" | "num" | "str(" length ")" | token | "…"
 * object := "{" [pair ("," pair)*] [",+" omitted] "}"          -- keys in the order they came, at most MAX_KEYS of them
 * pair   := key ":" value
 * array  := "[" size ":" value "]" | "[0]"                      -- the first element's shape stands for the rest
 * token  := [A-Za-z][A-Za-z0-9_.:/-]{0,47}                    -- an enum-like string, shown only under an allowlisted key
 * ```
 *
 * A string is `str(n)` with its length in characters; a number is `num` whatever it holds (an id, a count, a time);
 * a nesting deeper than [MAX_DEPTH] is `…`.
 */
object RecordShapes {

    /** Values are described this deep; the record's deepest useful key (`toolCall.sendMessageParams.text.content`) is four down. */
    const val MAX_DEPTH = 6
    /** Keys per object; a result object with hundreds of fields is cut with `,+n`. */
    const val MAX_KEYS = 32

    /** Keys whose string values are enum-like tokens by construction, shown verbatim at any depth when they read as one. */
    private val ENUM_KEYS = setOf("type", "case", "kind", "status", "mode", "agentMode", "role", "style", "thinkingStyle", "toolName", "delivery", "lifecycle", "workflowStatus", "statusType")
    /** Keys that name the tool on a call — only there (a `name` deeper down is a worker's or a file's, and stays a length). */
    private val NAME_KEYS = setOf("name", "tool")
    /** How deep a call's own keys sit: the step object is 0, the call object 1, the call's keys' values 2. */
    private const val CALL_KEY_DEPTH = 2

    private val TOKEN = Regex("^[A-Za-z][A-Za-z0-9_.:/-]{0,47}$")
    private val KEY_CHARS = Regex("[^A-Za-z0-9_$.-]")

    fun describe(element: JsonElement): String = buildString { describe(element, null, 0, this) }

    private fun describe(element: JsonElement, key: String?, depth: Int, out: StringBuilder) {
        when (element) {
            JsonNull -> out.append("null")
            is JsonPrimitive -> when {
                element.isString -> {
                    val value = element.content
                    if (key != null && showsValue(key, depth) && TOKEN.matches(value)) out.append(value) else out.append("str(").append(value.length).append(')')
                }
                element.booleanOrNull != null -> out.append(element.content)
                else -> out.append("num")
            }
            is JsonArray -> {
                if (element.isEmpty()) {
                    out.append("[0]")
                } else {
                    out.append('[').append(element.size).append(':')
                    if (depth >= MAX_DEPTH) out.append('\u2026') else describe(element[0], null, depth + 1, out)
                    out.append(']')
                }
            }
            is JsonObject -> {
                if (depth >= MAX_DEPTH) {
                    out.append("{\u2026}")
                    return
                }
                out.append('{')
                var n = 0
                for ((k, v) in element) {
                    if (n == MAX_KEYS) {
                        out.append(",+").append(element.size - n)
                        break
                    }
                    if (n > 0) out.append(',')
                    out.append(k.replace(KEY_CHARS, "_")).append(':')
                    describe(v, k, depth + 1, out)
                    n++
                }
                out.append('}')
            }
        }
    }

    private fun showsValue(key: String, depth: Int): Boolean = key in ENUM_KEYS || (key in NAME_KEYS && depth <= CALL_KEY_DEPTH)
}
