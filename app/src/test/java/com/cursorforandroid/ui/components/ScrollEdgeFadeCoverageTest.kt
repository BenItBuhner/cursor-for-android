package com.cursorforandroid.ui.components

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Every vertically scrolling surface of the app wears the edge fade, read off the sources: a column scrolls through
 * [fadingVerticalScroll] (never a bare `verticalScroll`), and a lazy list or grid either is a [FadingLazyColumn] or
 * names [scrollEdgeFade] among its arguments. Settings was a bare `verticalScroll` whose content cut off flat under the
 * header while the transcript faded: the fade was opt-in per list, and a screen that never opted in went unnoticed.
 * The next one fails here instead.
 *
 * Glance's `LazyColumn` (the home-screen widget) is a RemoteViews list that Compose modifiers never reach, so files
 * built on `androidx.glance` are out of scope.
 */
class ScrollEdgeFadeCoverageTest {

    private val sources = File(System.getProperty("user.dir"), "src/main/java").normalize()

    @Test
    fun `no scrolling column or lazy list in the app cuts its content off flat`() {
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertWithMessage("the app sources at $sources").that(files.size).isGreaterThan(50)
        val misses = files.flatMap { file -> unfaded(file.relativeTo(sources).path, file.readText()) }
        assertWithMessage("scroll containers without the edge fade (use fadingVerticalScroll / FadingLazyColumn / scrollEdgeFade)").that(misses).isEmpty()
    }

    @Test
    fun `settings and its subpages scroll through the fade`() {
        listOf("SettingsScreen.kt", "SettingsDebugSheet.kt", "WhatsNewScreen.kt", "ExtendedModeSettings.kt").forEach { name ->
            val text = File(sources, "com/cursorforandroid/ui/settings/$name").readText()
            assertWithMessage(name).that(text).contains("fadingVerticalScroll(")
        }
    }

    @Test
    fun `the scan flags a bare verticalScroll and an unfaded lazy list, and passes the faded ones`() {
        val bare = """
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) { items(rows) { Text(it) } }
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.testTag("grid(1)")) { }
        """.trimIndent()
        assertThat(unfaded("Bare.kt", bare)).containsExactly(
            "Bare.kt:1: .verticalScroll(",
            "Bare.kt:2: LazyColumn(",
            "Bare.kt:3: LazyVerticalGrid(",
        ).inOrder()

        val faded = """
            // A comment naming .verticalScroll( is not a call.
            Column(Modifier.fillMaxSize().fadingVerticalScroll(surface = colors.canvas)) { }
            FadingLazyColumn(Modifier.fillMaxWidth()) { items(rows) { Text("(a row)") } }
            LazyColumn(
                Modifier.fillMaxSize().scrollEdgeFade(list, surface = colors.canvas).horizontalScroll(sideways),
                state = list,
            ) { }
        """.trimIndent()
        assertThat(unfaded("Faded.kt", faded)).isEmpty()

        assertThat(unfaded("Widget.kt", "import androidx.glance.appwidget.lazy.LazyColumn\nLazyColumn(body) { }")).isEmpty()
    }

    /** `path:line: call` for each scroll container in [text] that does not fade its edges. */
    private fun unfaded(path: String, text: String): List<String> {
        if (path.endsWith("ScrollEdgeFade.kt") || "import androidx.glance" in text) return emptyList()
        val code = text.lines().joinToString("\n") { line -> line.trimStart().let { if (it.startsWith("//") || it.startsWith("*")) "" else line } }
        fun lineOf(index: Int) = code.substring(0, index).count { it == '\n' } + 1
        val out = ArrayList<Pair<Int, String>>()
        Regex("""\.verticalScroll\(""").findAll(code).forEach { m -> out += m.range.first to "$path:${lineOf(m.range.first)}: .verticalScroll(" }
        Regex("""(?<![A-Za-z])(LazyColumn|LazyVerticalGrid|LazyVerticalStaggeredGrid)\(""").findAll(code).forEach { m ->
            val args = arguments(code, m.range.last)
            if ("scrollEdgeFade(" !in args) out += m.range.first to "$path:${lineOf(m.range.first)}: ${m.groupValues[1]}("
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** The text between the parenthesis at [open] and its match, string literals skipped so a `"("` cannot unbalance it. */
    private fun arguments(code: String, open: Int): String {
        var depth = 0
        var inString = false
        var i = open
        while (i < code.length) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> if (--depth == 0) return code.substring(open + 1, i)
            }
            i++
        }
        return code.substring(open + 1)
    }
}
