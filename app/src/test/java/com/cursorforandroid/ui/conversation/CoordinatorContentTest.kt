package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
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
 * A Project coordinator's transcript as first-class items: a card per worker created — the list's live name and
 * state, a way to its chat — status rows per worker checked on, compact rows for a message or a stop, the
 * coordinator's own words to the user as a plain reply, and a worker's completion notice opening the worker's chat.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CoordinatorContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun agent(id: String, name: String, status: RunStatus?, pending: Boolean = false) = Agent(
        id = id, name = name, lifecycle = AgentLifecycle.ACTIVE, runStatus = status, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/$id", createdAtMillis = 1L, updatedAtMillis = 2L, latestRunId = "run-$id", repoUrl = null, startingRef = null,
        hasPendingInteraction = pending,
    )

    private val live = mapOf(
        "bc-w1" to agent("bc-w1", "Usage events aggregation (renamed)", RunStatus.RUNNING),
        "bc-w3" to agent("bc-w3", "Pricing page", RunStatus.ERROR, pending = true),
    )

    private fun show(item: TimelineItem, controls: TranscriptControls = TranscriptControls(onOpenAgent = { opened += it }, agentById = { live[it] })) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides controls) { TimelineItemView(item) }
            }
        }
    }

    private fun created(callId: String, name: String, agentId: String?, prompt: String = "Do the thing.", status: String = "completed") = ToolCall(
        callId, "create_agent", ToolKind.Coordinator, status, name,
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOf(WorkerStatus(agentId = agentId, name = name)), text = prompt, title = name),
        linkedAgentIds = listOfNotNull(agentId),
    )

    @Test
    fun `a worker created is a card with the list's live name and state that opens the worker`() {
        show(ActivityGroup("g", listOf(ThinkingBlock("Start the independent tracks.", durationSeconds = 2), created("k1", "Usage events aggregation", "bc-w1"))))
        // The list's row supersedes the coordinator's word on the name; its state is the pill.
        compose.onNodeWithText("Usage events aggregation (renamed)").assertIsDisplayed()
        compose.onNodeWithText("Working").assertIsDisplayed()
        compose.onNodeWithText("Do the thing.").assertIsDisplayed()
        // Not folded behind an "Explored" header: the card is the point.
        assertThat(compose.onAllNodesWithText("Explored", substring = true).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag("worker-card").performClick()
        assertThat(opened).containsExactly("bc-w1")
    }

    @Test
    fun `a worker the list has not loaded shows the coordinator's word, and one still being created says so`() {
        show(ActivityGroup("g", listOf(created("k1", "Stripe webhook handler", "bc-w2"), created("k2", "Rollout plan", null, status = "running"))))
        compose.onNodeWithText("Stripe webhook handler").assertIsDisplayed()
        compose.onNodeWithText("Creating Rollout plan").assertIsDisplayed()
        // No state known for either: no pill; the loaded worker still opens.
        assertThat(compose.onAllNodesWithText("Working").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Stripe webhook handler").performClick()
        assertThat(opened).containsExactly("bc-w2")
    }

    @Test
    fun `a status check is one row per worker, a message a compact row that opens onto its text`() {
        val status = ToolCall(
            "k3", "get_agent_status", ToolKind.Coordinator, "completed", "2 agents",
            payload = ToolPayload.WorkerAction(
                ToolPayload.WorkerAction.Kind.Status,
                listOf(
                    WorkerStatus("bc-w1", "Usage events aggregation", lifecycle = "ACTIVE", turnInFlight = false, lastTurnStatus = "FINISHED", prUrl = "https://github.com/o/r/pull/215"),
                    WorkerStatus("bc-w3", "Pricing page", turnInFlight = true),
                    WorkerStatus("bc-w9", "Unknown worker", lastTurnStatus = "FINISHED"),
                ),
            ),
        )
        val messaged = ToolCall(
            "k4", "send_to_agent", ToolKind.Coordinator, "completed", "bc-w1",
            payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus("bc-w1")), text = "Rebase onto main.", title = "Rebase", note = "Delivered as followup"),
        )
        show(ActivityGroup("g", listOf(status, messaged)))
        compose.onNodeWithText("Checked agents").assertIsDisplayed()
        compose.onNodeWithText("3 agents").assertIsDisplayed()
        // The list's live state wins over the coordinator's report: bc-w1 is working here, bc-w3 needs input.
        compose.onAllNodesWithText("Usage events aggregation (renamed)")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Working")[0].assertIsDisplayed()
        compose.onNodeWithText("Needs input").assertIsDisplayed()
        // A worker the list does not hold reads as the coordinator saw it.
        compose.onNodeWithText("Unknown worker").assertIsDisplayed()
        compose.onNodeWithText("Finished").assertIsDisplayed()
        compose.onNodeWithText("Unknown worker").performClick()
        assertThat(opened).containsExactly("bc-w9")
        // The message: verb, worker, delivery; its text and the way to the worker behind it.
        compose.onNodeWithText("Messaged").assertIsDisplayed()
        compose.onNodeWithText("· Delivered as followup").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Rebase onto main.").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Messaged").performClick()
        compose.onNodeWithText("Rebase onto main.").assertIsDisplayed()
        compose.onNodeWithText("Open agent").performClick()
        assertThat(opened).containsExactly("bc-w9", "bc-w1").inOrder()
    }

    /** The coordinator's turn of the reference screenshot: the update it sent, and the note it wrote after. */
    private val sentUpdate = ToolCall(
        "c2", "SendMessage", ToolKind.Coordinator, "completed", "The keyboard worker merged #113",
        payload = ToolPayload.CoordinatorMessage("The keyboard worker merged #113 with four root causes fixed; **v0.3.4** is being cut from it, and the board is updated."),
    )
    private val note = AssistantMessage("a2", "The missing-Projects regression is routed as urgent to the Projects worker; Bennett has both tracks, and the board reflects them.")

    @Test
    fun `in a coordinator's chat the SendMessage update is the message and the plain reply folds under Background`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides TranscriptControls(coordinatorMode = true)) {
                    Column {
                        TimelineItemView(ActivityGroup("g", listOf(sentUpdate)))
                        TimelineItemView(note)
                    }
                }
            }
        }
        // The update, in full, with its markdown — and no bare "Send message" row.
        compose.onNodeWithTag("coordinator-message", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("and the board is updated.", substring = true).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Send message", substring = true).fetchSemanticsNodes()).isEmpty()
        // The note is a closed "Background" row: its text is not on screen until the row is opened.
        compose.onNodeWithTag("coordinator-background").assertIsDisplayed()
        compose.onNodeWithText("Background").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Bennett has both tracks", substring = true).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithText("Bennett has both tracks", substring = true).assertIsDisplayed()
        // And closes again.
        compose.onNodeWithText("Background").performClick()
        assertThat(compose.onAllNodesWithText("Bennett has both tracks", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a coordinator's reply still streaming reads Working, and one with nothing in it has nothing to open`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides TranscriptControls(coordinatorMode = true)) {
                    Column {
                        TimelineItemView(AssistantMessage("s1", "Checking the board", isStreaming = true))
                        TimelineItemView(AssistantMessage("s2", "   "))
                    }
                }
            }
        }
        compose.onNodeWithText("Working").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Checking the board", substring = true).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Background").fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun `in an agent's chat the reply is the message, as it was`() {
        show(note)
        compose.onNodeWithText("Bennett has both tracks", substring = true).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Background").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasTestTag("coordinator-background")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the coordinator's words to the user read as a plain reply, with nothing to set them apart`() {
        val said = ToolCall("k5", "send_to_user", ToolKind.Coordinator, "completed", "PR #215 is merged.", payload = ToolPayload.CoordinatorMessage("PR #215 is **merged**. One decision is open."))
        show(ActivityGroup("g", listOf(said)))
        compose.onNodeWithTag("coordinator-message", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("One decision is open.", substring = true).assertIsDisplayed()
        // No label, no rule, no glyph: the message is the reply, as an agent's is.
        assertThat(compose.onAllNodesWithText("Coordinator").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Sent message", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `without a way to navigate the cards are read-only, and a stale trace still reads as a line`() {
        show(ActivityGroup("g", listOf(created("k1", "Usage events aggregation", "bc-w1"))), TranscriptControls())
        compose.onNodeWithText("Usage events aggregation").assertIsDisplayed()
        compose.onNodeWithTag("worker-card").performClick()
        assertThat(opened).isEmpty()
        assertThat(compose.onAllNodesWithText("Open agent").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a trace kept before the coordinator's tools had payloads still reads as its plain line`() {
        show(ActivityGroup("g", listOf(ToolCall("k0", "create_agent", ToolKind.Other, "completed", "Webhooks", linkedAgentIds = listOf("bc-w1")))))
        // Folded behind the step header as any lone tool call of no special kind is; the line reads with its verb.
        compose.onNodeWithText("Explored").performClick()
        compose.onNode(hasText("Created agent")).assertIsDisplayed()
        compose.onNode(hasText("Webhooks")).assertIsDisplayed()
        assertThat(compose.onAllNodes(hasTestTag("worker-card")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a coordinator's message whose body did not reach the device says so, dimmed, in the reply's place`() {
        val missing = ToolCall("k6", "SendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("", missing = true))
        show(ActivityGroup("g", listOf(missing)))
        compose.onNodeWithTag("coordinator-message-missing", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(MISSING_MESSAGE).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Coordinator").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Sent message", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a remark folded under an injected turn's row opens with the row, under the report`() {
        show(
            SystemNotification(
                id = "n2", kind = SystemNotification.Kind.Subagent, title = "Subagent completed", summary = "Land merge train and prep release",
                body = "The merge train landed: #113 merged, v0.3.4 tagged.", tone = NoticeTone.Success, raw = "<system_notification>…</system_notification>",
                narration = "Noted; the release worker is done and nothing else needs doing.",
            ),
        )
        compose.onNodeWithText("Land merge train and prep release").assertIsDisplayed()
        compose.onNodeWithText(" \u00B7 completed").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Noted; the release worker is done and nothing else needs doing.").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Land merge train and prep release").performClick()
        compose.onNodeWithText("The merge train landed: #113 merged, v0.3.4 tagged.").assertIsDisplayed()
        compose.onNodeWithTag("notification-narration", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Noted; the release worker is done and nothing else needs doing.").assertIsDisplayed()
    }

    @Test
    fun `a notice with only a remark folded under it opens onto the remark alone`() {
        show(
            SystemNotification(
                id = "n3", kind = SystemNotification.Kind.Other, title = "GitHub notification", summary = "#59 · synchronize · cursor[bot]",
                body = null, tone = NoticeTone.Neutral, raw = "<system_notification source=\"github\">…</system_notification>", narration = "Acknowledged.",
            ),
        )
        // "#59 · synchronize · cursor[bot]": the pull request, the action, the actor, no "GitHub notification" label.
        compose.onNodeWithText("#59").assertIsDisplayed()
        compose.onNodeWithText(" \u00B7 synchronize").assertIsDisplayed()
        compose.onNodeWithText(" \u00B7 cursor[bot]").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("GitHub notification").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("#59").performClick()
        compose.onNodeWithText("Acknowledged.").assertIsDisplayed()
    }

    @Test
    fun `a worker's completion notice is a compact row whose button opens the worker's chat`() {
        show(
            SystemNotification(
                id = "n1", kind = SystemNotification.Kind.Worker, title = "Worker completed", summary = "Usage events aggregation",
                body = "Added the aggregates. PR #215 merged.", tone = NoticeTone.Success, raw = "<system_notification>…</system_notification>", agentId = "bc-w1",
            ),
        )
        compose.onNodeWithText("Worker completed").assertIsDisplayed()
        compose.onNodeWithText("Usage events aggregation").assertIsDisplayed()
        // The row opens the report; the button at its end opens the worker.
        compose.onNodeWithText("Worker completed").performClick()
        compose.onNodeWithText("Added the aggregates. PR #215 merged.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open agent").performClick()
        assertThat(opened).containsExactly("bc-w1")
    }
}
