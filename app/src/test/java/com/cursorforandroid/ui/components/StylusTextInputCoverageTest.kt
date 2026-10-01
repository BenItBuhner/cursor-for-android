package com.cursorforandroid.ui.components

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Every text field of the app takes an S Pen as a finger, read off the sources: each `BasicTextField` sits directly in
 * a [StylusTextInput] block, and its file gives the field's box [stylusWriting]. A field outside the block is back to
 * Compose's own handwriting detector, which takes a pen tap that moves as it lifts, a press that wavers before it is
 * held and every drag as writing, and drops them under an IME that cannot write; a field without the box takes a pen
 * only on its glyphs. The next field added without them fails here instead.
 */
class StylusTextInputCoverageTest {

    private val sources = File(System.getProperty("user.dir"), "src/main/java").normalize()

    @Test
    fun `every text field in the app takes the pen as a finger`() {
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertWithMessage("the app sources at $sources").that(files.size).isGreaterThan(50)
        val fields = files.sumOf { file -> Regex("""(?<![A-Za-z])BasicTextField\(""").findAll(code(file.readText())).count() }
        assertWithMessage("text fields found").that(fields).isAtLeast(14)
        val misses = files.flatMap { file -> unguarded(file.relativeTo(sources).path, file.readText()) }
        assertWithMessage("text fields without StylusTextInput { } around them or stylusWriting() on their box").that(misses).isEmpty()
    }

    @Test
    fun `the scan flags a bare field and a field without a box, and passes a guarded one`() {
        val bare = """
            Row(Modifier.stylusWriting()) {
                BasicTextField(value, onValueChange, modifier = Modifier.weight(1f))
            }
        """.trimIndent()
        assertThat(unguarded("Bare.kt", bare)).containsExactly("Bare.kt:2: BasicTextField( outside StylusTextInput { }")

        val nested = """
            Row(Modifier.stylusWriting()) {
                StylusTextInput {
                    Box { BasicTextField(value, onValueChange) }
                }
            }
        """.trimIndent()
        assertThat(unguarded("Nested.kt", nested)).containsExactly("Nested.kt:3: BasicTextField( outside StylusTextInput { }")

        val boxless = """
            StylusTextInput {
                BasicTextField(value, onValueChange)
            }
        """.trimIndent()
        assertThat(unguarded("Boxless.kt", boxless)).containsExactly("Boxless.kt: BasicTextField( without stylusWriting() on its box")

        val guarded = """
            // A comment naming BasicTextField( is not a call.
            Row(Modifier.stylusWriting(handwriting = false)) {
                StylusTextInput {
                    BasicTextField(
                        value = "{ not a block",
                        onValueChange = { key = it },
                        decorationBox = { inner -> Box { inner() } },
                    )
                }
            }
        """.trimIndent()
        assertThat(unguarded("Guarded.kt", guarded)).isEmpty()
    }

    /** `path:line: problem` for each text field in [text] the pen does not reach as a finger. */
    private fun unguarded(path: String, text: String): List<String> {
        val code = code(text)
        val calls = Regex("""(?<![A-Za-z])BasicTextField\(""").findAll(code).map { it.range.first }.toList()
        if (calls.isEmpty()) return emptyList()
        fun lineOf(index: Int) = code.substring(0, index).count { it == '\n' } + 1
        val out = calls.filterNot { at -> enclosingBlockOpener(code, at).endsWith("StylusTextInput") }
            .map { at -> "$path:${lineOf(at)}: BasicTextField( outside StylusTextInput { }" }
            .toMutableList()
        if ("stylusWriting(" !in code) out += "$path: BasicTextField( without stylusWriting() on its box"
        return out
    }

    /** [text] with its comment lines blanked, so a comment naming a call is not one. */
    private fun code(text: String): String =
        text.lines().joinToString("\n") { line -> line.trimStart().let { if (it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")) "" else line } }

    /** What stands before the innermost `{` still open at [at], trimmed: the call whose trailing lambda holds it. */
    private fun enclosingBlockOpener(code: String, at: Int): String {
        val open = ArrayDeque<Int>()
        var inString = false
        var i = 0
        while (i < at) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '{' -> open.addLast(i)
                !inString && c == '}' -> open.removeLastOrNull()
            }
            i++
        }
        val brace = open.lastOrNull() ?: return ""
        return code.substring(0, brace).trimEnd()
    }
}
