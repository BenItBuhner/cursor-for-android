package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.WorkerStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Everything between two messages behind one line: closed, the summary alone; open, the sequence verbatim — each
 * thought, each tool call as the line it was, each of a coordinator's notes — and a stretch of one step drawn as
 * that step. The coordinator's updates and its worker's card stand outside, as rows of their own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StretchViewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(rows: List<TranscriptRow>, coordinatorMode: Boolean = true) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides TranscriptControls(coordinatorMode = coordinatorMode)) {
                    androidx.compose.foundation.layout.Column { rows.forEach { TranscriptRowView(it) } }
                }
            }
        }
    }

    private val update = ToolCall("c2", "SendMessage", ToolKind.Coordinator, "completed", "PR #215 is merged.", payload = ToolPayload.CoordinatorMessage("PR #215 is **merged**."))
    private val created = ToolCall(
        "c9", "create_agent", ToolKind.Coordinator, "completed", "Stripe webhook handler",
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOf(WorkerStatus(agentId = "bc-w2", name = "Stripe webhook handler")), text = "Handle the webhooks.", title = "Stripe webhook handler"),
    )

    /** A coordinator's turn: a note, a thought, a message to a worker and an edit before its update; then the worker it created and the footer. */
    private val items: List<TimelineItem> = listOf(
        UserMessage("u1", "Where do we stand?"),
        AssistantMessage("a1", "Checking the board before answering."),
        ActivityGroup(
            "g1",
            listOf(
                ThinkingBlock("The aggregation landed; check the webhook handler."),
                ToolCall("c1", "send_to_agent", ToolKind.Coordinator, "completed", "bc-w1", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus(agentId = "bc-w1", name = "Usage events aggregation")), text = "Rebase onto main.", note = "Delivered as followup")),
                ToolCall("c3", "edit_file", ToolKind.Edit, "completed", "notes.md", detail = "notes.md", linesAdded = 2, linesRemoved = 1),
                update,
                created,
            ),
        ),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 108_000, emptyList()),
    )

    @Test
    fun `closed, a stretch is one line, open, it is the sequence verbatim, and the message and the card stand outside`() {
        val rows = TranscriptRows.of(items, coordinatorMode = true)
        assertThat(rows.map { it::class.simpleName }).containsExactly("Item", "Stretch", "Message", "Worker", "Stretch").inOrder()
        show(rows)
        // The summary alone: nothing of the sequence is on screen.
        compose.onNodeWithText("1 edit").assertIsDisplayed()
        compose.onNodeWithText("1 agent · 1 thought · 1 note").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Checking the board before answering.").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("The aggregation landed; check the webhook handler.").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Edited").fetchSemanticsNodes()).isEmpty()
        // The update and the worker's card are rows of their own, visible whatever the stretch does.
        compose.onNodeWithText("merged", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("worker-card").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Coordinator").fetchSemanticsNodes()).isEmpty()
        // The footer after the card is a stretch of one entry: drawn as the footer.
        compose.onNodeWithText("Worked").assertIsDisplayed()
        compose.onNodeWithText("1m 48s").assertIsDisplayed()

        // Open: the note, the thought, the row to the worker and the edit's line, in order, each as it always was.
        compose.onNodeWithText("1 edit").performClick()
        compose.onNodeWithTag("stretch-steps", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Checking the board before answering.").assertIsDisplayed()
        compose.onNodeWithText("The aggregation landed; check the webhook handler.").assertIsDisplayed()
        compose.onNodeWithText("Messaged").assertIsDisplayed()
        compose.onNodeWithText("Usage events aggregation").assertIsDisplayed()
        compose.onNodeWithText("Edited").assertIsDisplayed()
        compose.onNodeWithText("notes.md").assertIsDisplayed()
        // The edits' line counts sit on the summary and on the edit's own line.
        assertThat(compose.onAllNodesWithText("+2").fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun `a live stretch reads Working, and in an agent's chat the replies are the rows`() {
        val agentItems = listOf(
            UserMessage("u1", "Fix the flaky test."),
            ActivityGroup("g1", listOf(ThinkingBlock("Reading the test first."), ToolCall("r1", "read_file", ToolKind.Read, "completed", "FlakyTest.kt"), ToolCall("r2", "run_terminal_cmd", ToolKind.Shell, "running", "gradle test"))),
            AssistantMessage("a1", "Running the suite now.", isStreaming = true),
        )
        val rows = TranscriptRows.of(agentItems, coordinatorMode = false, runActive = true)
        show(rows, coordinatorMode = false)
        // The reply is the message; the stretch before it is closed — the newest thing on screen is the reply, not the stretch.
        compose.onNodeWithText("Running the suite now.").assertIsDisplayed()
        compose.onNodeWithText("1 file").assertIsDisplayed()
        compose.onNodeWithText("1 command · 1 thought").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Working").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the newest stretch of a running run shimmers Working`() {
        val agentItems = listOf(
            UserMessage("u1", "Fix the flaky test."),
            ActivityGroup("g1", listOf(ThinkingBlock("Reading the test first."), ToolCall("r1", "read_file", ToolKind.Read, "completed", "FlakyTest.kt"), ToolCall("r2", "run_terminal_cmd", ToolKind.Shell, "running", "gradle test"))),
        )
        show(TranscriptRows.of(agentItems, coordinatorMode = false, runActive = true), coordinatorMode = false)
        compose.onNodeWithText("Working").assertIsDisplayed()
        compose.onNodeWithText("1 file · 1 command · 1 thought").assertIsDisplayed()
        compose.onNodeWithText("Working").performClick()
        compose.onNodeWithText("Reading the test first.").assertIsDisplayed()
        compose.onNodeWithText("Running").assertIsDisplayed()
    }

    @Test
    fun `a stretch of one step is that step`() {
        val one = listOf(UserMessage("u1", "Look."), ActivityGroup("g1", listOf(ToolCall("r1", "read_file", ToolKind.Read, "completed", "A.kt"))), AssistantMessage("a1", "Nothing to do."))
        show(TranscriptRows.of(one, coordinatorMode = false), coordinatorMode = false)
        compose.onNodeWithText("Read").assertIsDisplayed()
        compose.onNodeWithText("A.kt").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasTestTag("stretch")).fetchSemanticsNodes()).isEmpty()
    }
}
