package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.RecordShapes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Record fixtures from the transcript diagnostics' shape dump (see `RecordShapes` for the grammar): a dump line
 * such as `4023 tool_call[id=toolCallId name=name args=piece] {toolCall:{toolCallId:str(16),name:SendMessage,rawArgs:str(88),isStreaming:true}}`
 * is read back into a response of the same shape — every string a placeholder of the same length, every number 0,
 * every token itself — with the values a test needs (a `rawArgs` that has to read as JSON) put in by path. So a
 * dump a user sends of a record this build misreads becomes a test of that record's shape, values or no values.
 */
object ShapeFixtures {

    /** A dump line's parts: the step's index, the parser branch the dump named, and the shape. */
    data class Line(val index: Int, val branch: String, val shape: String)

    /** `<index> <branch>[<detail with spaces>] {<shape>}`: the branch's bracket may hold spaces (`tool_call[id=toolCallId name=name args=piece]`). */
    private val STEP_LINE = Regex("""^\s*(\d+)\s+([^\s\[{]+(?:\[[^\]]*])?)\s+(\{.*})\s*$""")

    /** What stands in for each character of a string the dump gave as a length: never a letter, so no key reads it as a token. */
    const val PLACEHOLDER = "\u00B7"

    /** The step lines of a dump, turn headers and call lines left out. */
    fun lines(dump: String): List<Line> = dump.lineSequence().mapNotNull { line ->
        val m = STEP_LINE.matchEntire(line) ?: return@mapNotNull null
        Line(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3])
    }.toList()

    /**
     * The responses a dump describes, each with the shape its line gives, [values] filling the keys named by path
     * (`toolCall.rawArgs`) for the line at the given position where a placeholder would not do.
     */
    fun responses(dump: String, values: (line: Int, path: String) -> JsonElement? = { _, _ -> null }): List<JsonObject> =
        lines(dump).mapIndexed { n, line -> skeleton(line.shape) { path -> values(n, path) } as JsonObject }

    /** The steps the app's own reader makes of a dump's responses (see [HeadlessConversationApi.parseStep]), indexed as the dump's lines are. */
    fun steps(dump: String, values: (line: Int, path: String) -> JsonElement? = { _, _ -> null }): List<HeadlessStep> {
        val indices = lines(dump).map { it.index }
        return responses(dump, values).mapIndexed { n, json -> HeadlessConversationApi.parseStep(json, indices[n]) }
    }

    /** A JSON value of [shape], [values] standing in where it answers for a path. */
    fun skeleton(shape: String, values: (path: String) -> JsonElement? = { null }): JsonElement = Parser(shape, values).value("")

    /** The shape of [element] as the dump writes it: the inverse of [skeleton] for everything but the values put in. */
    fun describe(element: JsonElement): String = RecordShapes.describe(element)

    private class Parser(private val text: String, private val values: (String) -> JsonElement?) {
        private var i = 0

        fun value(path: String): JsonElement {
            values(path)?.let { override ->
                skip(path)
                return override
            }
            return read(path)
        }

        /** Consumes the value at the cursor without building it. */
        private fun skip(path: String) {
            read(path, build = false)
        }

        private fun read(path: String, build: Boolean = true): JsonElement {
            return when {
                peek('{') -> obj(path, build)
                peek('[') -> arr(path, build)
                startsWith("str(") -> {
                    i += 4
                    val n = number()
                    expect(')')
                    // A placeholder no key shows as a token, so the skeleton's shape is the dump's again.
                    if (build) JsonPrimitive(PLACEHOLDER.repeat(n)) else JsonNull
                }
                startsWith("num") -> { i += 3; JsonPrimitive(0) }
                startsWith("null") -> { i += 4; JsonNull }
                startsWith("true") -> { i += 4; JsonPrimitive(true) }
                startsWith("false") -> { i += 5; JsonPrimitive(false) }
                startsWith("\u2026") -> { i += 1; JsonNull }
                else -> {
                    val start = i
                    while (i < text.length && text[i] !in ",}]") i++
                    JsonPrimitive(text.substring(start, i))
                }
            }
        }

        private fun obj(path: String, build: Boolean): JsonElement {
            expect('{')
            val out = LinkedHashMap<String, JsonElement>()
            while (!peek('}')) {
                if (peek(',')) { i++; continue }
                if (peek('+')) {
                    // ",+n": keys the dump left out; nothing to build for them.
                    i++
                    number()
                    continue
                }
                if (startsWith("\u2026")) { i++; continue }
                val start = i
                while (text[i] != ':') i++
                val key = text.substring(start, i)
                expect(':')
                val child = if (path.isEmpty()) key else "$path.$key"
                val v = if (build) value(child) else read(child, build = false)
                if (build) out[key] = v
            }
            expect('}')
            return JsonObject(out)
        }

        private fun arr(path: String, build: Boolean): JsonElement {
            expect('[')
            val n = number()
            if (peek(']')) {
                i++
                return JsonArray(emptyList())
            }
            expect(':')
            val element = read("$path[]", build)
            expect(']')
            return JsonArray(List(n) { element })
        }

        private fun number(): Int {
            val start = i
            while (i < text.length && text[i].isDigit()) i++
            return text.substring(start, i).toInt()
        }

        private fun peek(c: Char): Boolean = i < text.length && text[i] == c
        private fun startsWith(s: String): Boolean = text.startsWith(s, i)
        private fun expect(c: Char) {
            check(peek(c)) { "expected '$c' at $i in $text" }
            i++
        }
    }
}
