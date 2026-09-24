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
import com.cursorforandroid.domain.SubagentChild
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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Everything between two messages behind one line: closed, the summary alone; open, the sequence verbatim — each
 * thought, each tool call as the line it was, each subagent as its row, each of a coordinator's notes — and a
 * stretch of one step drawn as that step. The coordinator's updates stand outside, as rows of their own.
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

    /** A coordinator's turn: a note, a thought and an edit; then a message to a worker, its update, the worker it created and the footer. */
    private val items: List<TimelineItem> = listOf(
        UserMessage("u1", "Where do we stand?"),
        AssistantMessage("a1", "Checking the board before answering."),
        ActivityGroup(
            "g1",
            listOf(
                ThinkingBlock("The aggregation landed; check the webhook handler."),
                ToolCall("c3", "edit_file", ToolKind.Edit, "completed", "notes.md", detail = "notes.md", linesAdded = 2, linesRemoved = 1),
                ToolCall("c1", "send_to_agent", ToolKind.Coordinator, "completed", "bc-w1", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus(agentId = "bc-w1", name = "Usage events aggregation")), text = "Rebase onto main.", note = "Delivered as followup")),
                update,
                created,
            ),
        ),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 108_000, emptyList()),
    )

    @Test
    fun `closed, a stretch is one line, open, it is the sequence verbatim with its subagents' rows, and the message stands outside`() {
        val rows = TranscriptRows.of(items, coordinatorMode = true)
        // The subagents split nothing: one stretch before the update, one after it.
        assertThat(rows.map { it::class.simpleName }).containsExactly("Item", "Stretch", "Message", "Stretch").inOrder()
        show(rows)
        // The summary alone: nothing of the sequence is on screen, the subagents' rows included.
        compose.onNodeWithText("1 edit").assertIsDisplayed()
        compose.onNodeWithText("1 agent · 1 thought · 1 note").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Checking the board before answering.").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("The aggregation landed; check the webhook handler.").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Edited").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasTestTag("subagent-row")).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Agent follow-up").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Stripe webhook handler").fetchSemanticsNodes()).isEmpty()
        // The update stands on its own, visible whatever the stretches do.
        compose.onNodeWithText("merged", substring = true).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Coordinator").fetchSemanticsNodes()).isEmpty()
        // The worker it created and the footer after it: one line, the worker counted.
        compose.onNodeWithText("Worked 1m 48s").assertIsDisplayed()
        compose.onNodeWithText("1 agent").assertIsDisplayed()

        // Open: the note, the thought, the edit's line and the message to the worker, in order, each as it always was.
        compose.onNodeWithText("1 edit").performClick()
        compose.onNodeWithTag("stretch-steps", useUnmergedTree = true).assertIsDisplayed()
        val order = listOf("Checking the board before answering.", "The aggregation landed; check the webhook handler.", "Edited", "Agent follow-up")
        val tops = order.map { text -> compose.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertThat(tops).isInStrictOrder()
        compose.onNodeWithText("notes.md").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasTestTag("subagent-row")).fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithText("Stripe webhook handler").fetchSemanticsNodes()).isEmpty()
        // The edits' line counts sit on the summary and on the edit's own line.
        assertThat(compose.onAllNodesWithText("+2").fetchSemanticsNodes()).hasSize(2)
        // The second opens onto the worker it created, then the run's end.
        compose.onNodeWithText("Worked 1m 48s").performClick()
        compose.onNodeWithText("Stripe webhook handler").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasTestTag("subagent-row")).fetchSemanticsNodes()).hasSize(2)
        // Closed again, the rows go with the rest.
        compose.onNodeWithText("1 edit").performClick()
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText("Agent follow-up").fetchSemanticsNodes()).isEmpty()
    }

    /** An agent's turn that ended with its cloud task still at work, the task between two reads. */
    private val taskTurn: List<TimelineItem> = listOf(
        UserMessage("u1", "Hand the chart work to a cloud agent."),
        ActivityGroup(
            "g1",
            listOf(
                ToolCall("r1", "read_file", ToolKind.Read, "completed", "Chart.tsx"),
                ToolCall(
                    "t1", "task", ToolKind.Task, ToolCall.STATUS_COMPLETED, "CursorBench chart hover highlight",
                    payload = ToolPayload.Subagent("CursorBench chart hover highlight", agentId = "bc-cb1", isBackground = true),
                ),
                ToolCall("r2", "read_file", ToolKind.Read, "completed", "Legend.tsx"),
            ),
        ),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 38_000, emptyList()),
    )

    /** [items] with the task's child standing as [child] says. */
    private fun showTurn(coordinatorMode: Boolean, child: MutableStateFlow<SubagentChild?>, items: List<TimelineItem> = taskTurn) {
        val controls = TranscriptControls(coordinatorMode = coordinatorMode, subagentActivity = { child })
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides controls) {
                    androidx.compose.foundation.layout.Column { TranscriptRows.of(items, coordinatorMode = coordinatorMode).forEach { TranscriptRowView(it) } }
                }
            }
        }
    }

    @Test
    fun `a task call its ended run left running, with nothing known of the child, does not hold the stretch open`() {
        val stale = taskTurn.map { item ->
            if (item !is ActivityGroup) item else item.copy(steps = item.steps.map { step -> if (step is ToolCall && step.callId == "t1") step.copy(status = ToolCall.STATUS_RUNNING) else step })
        }
        showTurn(coordinatorMode = true, MutableStateFlow(null), stale)
        compose.onNodeWithText("Worked 38s").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("1 Working").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText(com.cursorforandroid.domain.SubagentRows.PLANNING).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a closed stretch whose subagent still works counts it as working, and settles once it is done`() {
        val child = MutableStateFlow<SubagentChild?>(SubagentChild(SubagentChild.Status.Running, step = "Implement bidirectional hover linking"))
        showTurn(coordinatorMode = false, child)
        // The run has ended; the stretch has not: the desktop's "1 working", the counts after it, the row hidden.
        compose.onNodeWithText("1 working").assertIsDisplayed()
        compose.onNodeWithText("2 files · 1 agent").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Implement bidirectional hover linking").fetchSemanticsNodes()).isEmpty()
        // Open, the row reads where the task stands, between the reads it came between.
        compose.onNodeWithText("1 working").performClick()
        val tops = listOf("Chart.tsx", "CursorBench chart hover highlight", "Legend.tsx").map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertThat(tops).isInStrictOrder()
        compose.onNodeWithText("Implement bidirectional hover linking").assertIsDisplayed()
        // The task finishes: the stretch settles on the run's footer.
        child.value = SubagentChild(SubagentChild.Status.Succeeded)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Worked 38s").fetchSemanticsNodes().isNotEmpty() }
        assertThat(compose.onAllNodesWithText("1 working").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `in a Project's chat a closed stretch says where its newest working subagent stands`() {
        val child = MutableStateFlow<SubagentChild?>(SubagentChild(SubagentChild.Status.Running, action = "Editing Chart.tsx"))
        showTurn(coordinatorMode = true, child)
        compose.onNodeWithText("1 Working").assertIsDisplayed()
        compose.onNodeWithText("Editing Chart.tsx").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasTestTag("subagent-row")).fetchSemanticsNodes()).isEmpty()
        // Waiting on the reader still counts; the line says so.
        child.value = SubagentChild(SubagentChild.Status.Running, waiting = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(com.cursorforandroid.domain.SubagentRows.WAITING).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 Working").assertIsDisplayed()
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
