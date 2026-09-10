package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.ActivityStep
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolOutput
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The agent's work as rows: Cursor's "Thought 3s", the "Explored …" summary, and the tool lines behind it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ActivityGroupViewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(item: TimelineItem) {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { TimelineItemView(item) } }
    }

    /** [result] is the stream's payload, read for what the row shows as the call is built. */
    private fun call(kind: ToolKind, summary: String, server: String? = null, detail: String? = null, status: String = "completed", result: String? = null): ToolCall {
        val out = ToolOutput.from(kind, detail, result?.let(Json::parseToJsonElement))
        return ToolCall("c-$summary", kind.name, kind, status, summary, server = server, detail = detail, output = out.output, exitCode = out.exitCode)
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun group(vararg steps: ActivityStep) = ActivityGroup("g1", steps.toList())

    @Test
    fun `the work sits behind its summary and opens onto one line per step`() {
        show(
            group(
                ThinkingBlock("Where is the picker?", durationSeconds = 3),
                call(ToolKind.Read, "README.md", detail = "README.md"),
                call(ToolKind.Grep, "TODO in src", detail = "TODO in src"),
                ThinkingBlock("Found it."),
                call(ToolKind.Mcp, "list_pull_requests", server = "Github"),
            ),
        )
        // The thought before the tools is its own closed row; the work its own summary.
        compose.onNodeWithText("Thought").assertIsDisplayed()
        compose.onNodeWithText("3s").assertIsDisplayed()
        compose.onNodeWithText("Explored").assertIsDisplayed()
        compose.onNodeWithText("README.md, 1 search, 1 tool").assertIsDisplayed()
        assertThat(shown("Where is the picker?")).isFalse()
        assertThat(shown("Grepped")).isFalse()

        compose.onNodeWithText("Explored").performClick()
        compose.onNodeWithText("Read").assertIsDisplayed()
        compose.onNodeWithText("README.md").assertIsDisplayed()
        compose.onNodeWithText("Grepped").assertIsDisplayed()
        compose.onNodeWithText("TODO in src").assertIsDisplayed()
        compose.onNodeWithText("Found it.").assertIsDisplayed()
        compose.onNodeWithText("Ran").assertIsDisplayed()
        compose.onNodeWithText("list_pull_requests in Github").assertIsDisplayed()

        compose.onNodeWithText("Thought").performClick()
        compose.onNodeWithText("Where is the picker?").assertIsDisplayed()
    }

    @Test
    fun `one or two bare reads are lines of their own, with no row to open`() {
        show(group(call(ToolKind.Read, "Timeline.kt"), call(ToolKind.List, "src")))
        compose.onNodeWithText("Read").assertIsDisplayed()
        compose.onNodeWithText("Timeline.kt").assertIsDisplayed()
        compose.onNodeWithText("Listed").assertIsDisplayed()
        compose.onNodeWithText("src").assertIsDisplayed()
        assertThat(shown("Explored")).isFalse()
    }

    @Test
    fun `a command opens onto its terminal and a failed edit reads as attempted`() {
        show(
            group(
                call(ToolKind.Shell, "./gradlew test", detail = "./gradlew test", result = """{"success":{"exitCode":1,"stdout":"3 tests failed","stderr":""}}"""),
                ToolCall("e1", "edit_file", ToolKind.Edit, "completed", "A.kt", isError = true),
                ToolCall("e2", "edit_file", ToolKind.Edit, "completed", "B.kt", linesAdded = 12, linesRemoved = 3),
            ),
        )
        compose.onNodeWithText("Edited").assertIsDisplayed()
        compose.onNodeWithText("2 files, ran 1 command").assertIsDisplayed()
        compose.onNodeWithText("Edited").performClick()
        compose.onNodeWithText("Ran").assertIsDisplayed()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("attempted").assertIsDisplayed()
        compose.onNodeWithText("+12").assertIsDisplayed()
        compose.onNodeWithText("-3").assertIsDisplayed()
        assertThat(shown("3 tests failed")).isFalse()

        compose.onNodeWithText("Ran").performClick()
        compose.onNodeWithText("$ ./gradlew test").assertIsDisplayed()
        compose.onNodeWithText("3 tests failed").assertIsDisplayed()
        compose.onNodeWithText("exit code 1").assertIsDisplayed()
    }

    @Test
    fun `an opened tool output stays with its call when a step arrives ahead of it`() {
        fun shell(name: String) = call(ToolKind.Shell, name, detail = "echo $name", result = """{"success":{"exitCode":0,"stdout":"$name spoke","stderr":""}}""")
        val original = listOf(shell("one"), shell("two"), shell("three"))
        var steps by mutableStateOf<List<ActivityStep>>(original)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { TimelineItemView(ActivityGroup("g1", steps)) }
        }

        compose.onNodeWithText(ActivityGroup("g1", original).header.action).performClick()
        compose.onNodeWithText("two").performClick()
        assertThat(shown("two spoke")).isTrue()
        assertThat(shown("one spoke")).isFalse()

        steps = listOf(shell("zero")) + original
        compose.waitForIdle()

        assertThat(shown("two spoke")).isTrue()
        assertThat(shown("one spoke")).isFalse()
        assertThat(shown("zero spoke")).isFalse()
    }

    @Test
    fun `a thought being written is read along and closes once done`() {
        show(group(ThinkingBlock("Let me look at the repo.", isStreaming = true)))
        compose.onNodeWithText("Thinking").assertIsDisplayed()
        compose.onNodeWithText("Let me look at the repo.").assertIsDisplayed()
    }
}
