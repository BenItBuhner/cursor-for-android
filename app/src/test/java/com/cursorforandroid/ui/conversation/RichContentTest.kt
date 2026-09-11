package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.ActivityStep
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.ToolTruncation
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a tool line opens onto once the stream has carried a payload: the diff, the file, the subagent, the question. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RichContentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(item: TimelineItem) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column(Modifier.verticalScroll(rememberScrollState())) { TimelineItemView(item) }
            }
        }
    }

    private fun call(id: String, kind: ToolKind, summary: String, detail: String? = summary, payload: ToolPayload? = null, status: String = ToolCall.STATUS_COMPLETED, truncated: ToolTruncation? = null, linesAdded: Int? = null, linesRemoved: Int? = null) =
        ToolCall(id, kind.name, kind, status, summary, detail = detail, payload = payload, truncated = truncated, linesAdded = linesAdded, linesRemoved = linesRemoved)

    private fun group(vararg steps: ActivityStep) = ActivityGroup("g1", steps.toList())

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * A lone tool call other than a read sits behind the group's summary row, so opening its line takes two taps:
     * the summary (whose verb is [headerVerb]) and then the line (whose verb is [lineVerb]). When the two verbs read
     * the same, the line is the second node of that text.
     */
    private fun openLine(headerVerb: String, lineVerb: String) {
        compose.onNodeWithText(headerVerb).performClick()
        val expected = if (headerVerb == lineVerb) 2 else 1
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(lineVerb)).fetchSemanticsNodes().size == expected }
        compose.onAllNodesWithText(lineVerb)[expected - 1].performClick()
    }

    @Test
    fun `an edit opens onto its diff, coloured by line`() {
        val diff = ToolPayload.FileDiff("app/src/Main.kt", "@@ -1,2 +1,3 @@\n-val a = 1\n+val a = 2\n+val b = 3\n context", linesAdded = 2, linesRemoved = 1)
        show(group(call("e1", ToolKind.Edit, "Main.kt", "app/src/Main.kt", diff, linesAdded = 2, linesRemoved = 1)))

        // The edit sits behind its summary row; nothing of the diff is on screen yet.
        compose.onNodeWithText("Edited").assertIsDisplayed()
        assertThat(compose.onAllNodesWithTag("diff-block").fetchSemanticsNodes()).isEmpty()

        openLine("Edited", "Edited")
        compose.onNodeWithTag("diff-block").assertIsDisplayed()
        compose.onNodeWithText("+val a = 2").assertIsDisplayed()
        compose.onNodeWithText("-val a = 1").assertIsDisplayed()
        compose.onNodeWithText("@@ -1,2 +1,3 @@").assertIsDisplayed()
        // The header of the block names the file and repeats its counts (the lines already carry them).
        assertThat(compose.onAllNodesWithText("+2").fetchSemanticsNodes().size).isAtLeast(3)
        assertThat(shown("app/src/Main.kt")).isTrue()
    }

    @Test
    fun `a read opens onto the file, folding a long one behind show more`() {
        val text = (1..80).joinToString("\n") { "line $it" }
        val file = ToolPayload.FileContent("docs/long.txt", text, ToolPayload.FileContent.Kind.Read, totalLines = 80)
        show(group(call("r1", ToolKind.Read, "long.txt", "docs/long.txt", file)))

        // A lone read is a line of its own, not grouped.
        compose.onNodeWithText("Read").performClick()
        compose.onNodeWithTag("file-card").assertIsDisplayed()
        compose.onNodeWithText("Read · 80 lines").assertIsDisplayed()
        compose.onNodeWithText("line 1").assertIsDisplayed()
        assertThat(shown("line 79")).isFalse()
        compose.onNodeWithText("Show 20 more lines").performScrollTo().performClick()
        compose.waitUntil(10_000) { shown("line 80") }
        assertThat(shown("Show 20 more lines")).isFalse()
    }

    @Test
    fun `a written file says it was written and a cut one says it was cut`() {
        val file = ToolPayload.FileContent("notes.md", "# Notes", ToolPayload.FileContent.Kind.Written, totalLines = 1, truncated = true)
        show(group(call("w1", ToolKind.Create, "notes.md", "notes.md", file)))
        openLine("Created", "Created")
        compose.onNodeWithText("Wrote · 1 line").assertIsDisplayed()
        compose.onNodeWithText("… file cut short by the stream").assertIsDisplayed()
    }

    @Test
    fun `a pending question is a card of its own, with the choices and where to answer`() {
        val question = ToolPayload.Question(
            title = "Before I start",
            questions = listOf(ToolPayload.Question.Item("q1", "Which theme should the panel use?", listOf(ToolPayload.Question.Option("a", "Dark"), ToolPayload.Question.Option("b", "Light")))),
        )
        show(group(call("q1", ToolKind.Question, "", detail = null, payload = question, status = ToolCall.STATUS_RUNNING)))

        // Whether or not the group is open, the question is on screen.
        compose.onNodeWithTag("question-card").assertIsDisplayed()
        compose.onNodeWithText("Waiting for your answer").assertIsDisplayed()
        compose.onNodeWithText("Which theme should the panel use?").assertIsDisplayed()
        compose.onNodeWithText("Dark").assertIsDisplayed()
        compose.onNodeWithText("Light").assertIsDisplayed()
        assertThat(shown("Answering needs Cursor's own app or Extended mode")).isTrue()
    }

    @Test
    fun `an answered question opens onto what was picked`() {
        val item = ToolPayload.Question.Item("q1", "Ship it?", listOf(ToolPayload.Question.Option("y", "Yes"), ToolPayload.Question.Option("n", "No")))
        val question = ToolPayload.Question(questions = listOf(item), answers = listOf(ToolPayload.Question.Answer("q1", listOf("y"))))
        show(group(call("q1", ToolKind.Question, "1 question", detail = null, payload = question)))

        assertThat(compose.onAllNodesWithTag("question-card").fetchSemanticsNodes()).isEmpty()
        openLine("Asked", "Asked")
        compose.onNodeWithTag("question-card").assertIsDisplayed()
        compose.onNodeWithText("Ship it?").assertIsDisplayed()
        assertThat(shown("Waiting for your answer")).isFalse()
    }

    @Test
    fun `a subagent opens onto its transcript path`() {
        val subagent = ToolPayload.Subagent("Audit the tests", agentId = "bc-sub-1", transcriptPath = "/home/u/.cursor/projects/w/agent-transcripts/x.json", durationMs = 61_000, subagentType = "explore")
        show(group(call("t1", ToolKind.Task, "Audit the tests", detail = "Go through the suite", payload = subagent)))
        openLine("Explored", "Completed task")
        compose.onNodeWithTag("subagent-card").assertIsDisplayed()
        assertThat(shown("explore subagent · ran 1m 1s")).isTrue()
        assertThat(shown("agent-transcripts/x.json")).isTrue()
    }

    @Test
    fun `a call the stream cut short says so instead of looking empty`() {
        show(group(call("s1", ToolKind.Shell, "Run the tests", detail = "./gradlew test", truncated = ToolTruncation(result = true))))
        openLine("Ran", "Ran")
        compose.onNodeWithText("The output was too large for the stream").assertIsDisplayed()
    }

    @Test
    fun `media sits under the group whether or not it is open`() {
        val image = ToolPayload.GeneratedImage("/workspace/cat.png", "a cat", src = "data:image/png;base64,iVBORw0KGgo=")
        show(
            group(
                call("r1", ToolKind.Read, "a.kt"),
                call("r2", ToolKind.Read, "b.kt"),
                call("r3", ToolKind.Read, "c.kt"),
                call("i1", ToolKind.Image, "a cat", detail = "a cat", payload = image),
            ),
        )
        // Three reads and an image are grouped, and closed; the picture still shows under the summary.
        compose.onNodeWithText("Explored").assertIsDisplayed()
        compose.onNodeWithTag("media-strip").assertIsDisplayed()
        compose.onNode(hasText("cat.png")).assertIsDisplayed()
    }
}
