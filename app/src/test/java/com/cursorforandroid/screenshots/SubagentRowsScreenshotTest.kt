package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentCall
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.SubagentLook
import com.cursorforandroid.domain.SubagentModel
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.WorkerStatus
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.SubagentRowContent
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Subagent rows as Cursor's desktop draws them (internal/reference/desktop-subagent-row.png), in dark and light:
 * every state side by side; a Project coordinator creating, steering, queueing and stopping its workers; and the
 * reference's own turn, an agent handing chart work to a cloud subagent. Written to `screenshots/` and compared
 * pixel for pixel in CI (`verifyRoborazziDebug`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SubagentRowsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val models = LiveModelCatalog.models
    private val at = 1_789_340_000_000L

    @Before
    fun pinClock() {
        AppClock.nowMillis = { at + 10 * 60_000L }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(mode: ThemeMode, controls: TranscriptControls = TranscriptControls(), content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides controls) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    // -- every state ----------------------------------------------------------------------------------------------

    private val composerFast = SubagentModel("Composer 2.5", fast = true)
    private val opus = SubagentModel("Claude Opus 5.5 High")
    private val cloud = SubagentPlacement.Cloud

    private data class State(val caption: String, val title: String, val look: SubagentLook, val model: SubagentModel?, val placement: SubagentPlacement?)

    private val states = listOf(
        State("Task subagent · working, its step", "CursorBench chart hover highlight", SubagentLook(SubagentLook.Indicator.Running, "Implement bidirectional hover linking", active = true), composerFast, cloud),
        State("Task subagent · in the parent's VM, thinking", "Explore the evals page", SubagentLook(SubagentLook.Indicator.Running, SubagentRows.THINKING, active = true), SubagentModel("Explorer"), null),
        State("CreateAgent · starting up", "Fix the Projects list leak", SubagentLook(SubagentLook.Indicator.Running, SubagentRows.STARTING, active = true), opus, cloud),
        State("SendToAgent steer · working, its action", "Keep workers out of Today", SubagentLook(SubagentLook.Indicator.Running, "Editing AgentListOrganizer.kt", active = true), composerFast, cloud),
        State("SendToAgent queue · planning", SubagentCall.FOLLOW_UP, SubagentLook(SubagentLook.Indicator.Running, SubagentRows.PLANNING, active = true), opus, cloud),
        State("Waiting on the reader", "Pricing page copy", SubagentLook(SubagentLook.Indicator.Attention, SubagentRows.WAITING, attention = true), composerFast, cloud),
        State("Finished", "Land merge train and prep release", SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.COMPLETED), opus, cloud),
        State("Errored", "Flaky screenshot hunt", SubagentLook(SubagentLook.Indicator.Error, SubagentRows.STOPPED_WITH_ERROR), composerFast, cloud),
        State("StopAgent · stopped", "Old release worker", SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.STOPPED, dimmed = true), null, cloud),
        State("CreateAgent · failed to start", "Webhooks on a self-hosted machine", SubagentLook(SubagentLook.Indicator.Finished, SubagentRows.COULD_NOT_START, dimmed = true), null, SubagentPlacement.Machine),
        State("CreateAgent · cancelled before it started", SubagentCall.NEW_AGENT, SubagentLook(SubagentLook.Indicator.Error, SubagentRows.CANCELLED), null, cloud),
    )

    private fun gallery(mode: ThemeMode, name: String) {
        show(mode) {
            states.forEach { state ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.caption, style = CursorTheme.typography.tiny, color = CursorTheme.colors.textQuaternary)
                    SubagentRowContent(state.title, state.look, state.model, state.placement)
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Implement bidirectional hover linking").fetchSemanticsNodes().isNotEmpty() }
        capture(name)
    }

    @Test
    fun statesDark() = gallery(ThemeMode.Dark, "300_subagent_row_states_dark")

    @Test
    fun statesLight() = gallery(ThemeMode.Light, "301_subagent_row_states_light")

    // -- a coordinator and its workers -----------------------------------------------------------------------------

    private fun agent(id: String, name: String, status: RunStatus, modelId: String? = null, pending: Boolean = false) = Agent(
        id = id, name = name, lifecycle = AgentLifecycle.ACTIVE, runStatus = status, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/$id", createdAtMillis = at, updatedAtMillis = at, latestRunId = "run-$id", repoUrl = null, startingRef = null,
        modelId = modelId, hasPendingInteraction = pending,
    )

    private fun create(callId: String, agentId: String?, name: String, prompt: String, model: String?, status: String = ToolCall.STATUS_COMPLETED, isError: Boolean = false) = ToolCall(
        callId, "create_agent", ToolKind.Coordinator, status, name, isError = isError,
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOfNotNull(agentId?.let { WorkerStatus(it, name) }), text = prompt, title = name, reported = agentId != null, model = model),
        linkedAgentIds = listOfNotNull(agentId),
    )

    private fun send(callId: String, agentId: String, title: String?, message: String, delivery: ToolPayload.WorkerAction.Delivery) = ToolCall(
        callId, "send_to_agent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, agentId,
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus(agentId)), text = message, title = title, delivery = delivery),
        linkedAgentIds = listOf(agentId),
    )

    private val coordinatorItems: List<TimelineItem> = listOf(
        UserMessage("u1", "Split the Projects work: one worker on the list leak, one on the release. Keep me posted.", at),
        ActivityGroup(
            "g1",
            listOf(
                ThinkingBlock("Two independent tracks: a worker for each.", durationSeconds = 2),
                create("c1", "bc-w1", "Fix the Projects list leak", "Workers leak into Today; keep them under their Project.", "composer-2.5"),
                create("c2", "bc-w2", "Land merge train and prep release", "Merge #113 and cut v0.3.4.", "claude-opus-5-5-high"),
                create("c3", null, "Webhooks on a self-hosted machine", "Handle the Stripe webhooks.", null, isError = true),
            ),
        ),
        ActivityGroup("g2", listOf(send("s1", "bc-w1", "Keep workers out of Today", "Also keep workers out of Today's section.", ToolPayload.WorkerAction.Delivery.Followup))),
        ActivityGroup("g3", listOf(send("s2", "bc-w2", null, "When the train lands, tag v0.3.4.", ToolPayload.WorkerAction.Delivery.Queue))),
        ActivityGroup(
            "g4",
            listOf(
                ToolCall(
                    "x1", "stop_agent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "bc-w3",
                    payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Stopped, listOf(WorkerStatus("bc-w3"))), linkedAgentIds = listOf("bc-w3"),
                ),
                ToolCall("m1", "SendMessage", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "Both tracks are running", payload = ToolPayload.CoordinatorMessage("Both tracks are running: the list leak is being fixed and the release is queued behind the merge train. I stopped the old release worker.")),
            ),
        ),
    )

    private val workers = mapOf(
        "bc-w1" to agent("bc-w1", "Fix the Projects list leak", RunStatus.RUNNING, "composer-2.5"),
        "bc-w2" to agent("bc-w2", "Land merge train and prep release", RunStatus.RUNNING, "claude-opus-5-5-high"),
        "bc-w3" to agent("bc-w3", "Old release worker", RunStatus.CANCELLED),
    )

    private val workerActivity = mapOf(
        "bc-w1" to SubagentChild(SubagentChild.Status.Running, action = "Editing AgentListOrganizer.kt"),
        "bc-w2" to SubagentChild(SubagentChild.Status.Running, step = "Waiting on CI for #113"),
    )

    private fun coordinator(mode: ThemeMode, name: String) {
        val presented = TranscriptPresenter().present(coordinatorItems, coordinatorMode = true, runActive = false)
        assertThat(presented.rows.filterIsInstance<TranscriptRow.Subagent>().map { it.subagent.source }).containsExactly(
            SubagentCall.Source.Created, SubagentCall.Source.Created, SubagentCall.Source.Created, SubagentCall.Source.Steered, SubagentCall.Source.Queued, SubagentCall.Source.Stopped,
        ).inOrder()
        val controls = TranscriptControls(
            onOpenAgent = {}, agentById = { workers[it] }, coordinatorMode = true, models = models, subagents = presented.subagents, placement = SubagentPlacement.Cloud,
            subagentActivity = { id -> MutableStateFlow(workerActivity[id]) },
        )
        show(mode, controls) { presented.rows.forEach { TranscriptRowView(it) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Editing AgentListOrganizer.kt").fetchSemanticsNodes().isNotEmpty() }
        capture(name)
    }

    @Test
    fun coordinatorDark() = coordinator(ThemeMode.Dark, "302_subagent_rows_coordinator_dark")

    @Test
    fun coordinatorLight() = coordinator(ThemeMode.Light, "303_subagent_rows_coordinator_light")

    // -- the reference's turn: an agent spawning a cloud subagent ------------------------------------------------------

    private val agentItems: List<TimelineItem> = listOf(
        UserMessage("u1", "Update the evals page /in-cloud so hovered models highlight in the chart", at),
        ActivityGroup("g0", listOf(ThinkingBlock("Look at the evals page first.", durationSeconds = 1))),
        AssistantMessage("a1", "Exploring the evals page and chart implementation, then spawning a cloud subagent with that context."),
        ActivityGroup(
            "g1",
            listOf(
                ToolCall("r1", "read", ToolKind.Read, ToolCall.STATUS_COMPLETED, "CursorBenchResults.tsx"),
                ToolCall("r2", "read", ToolKind.Read, ToolCall.STATUS_COMPLETED, "Chart.tsx"),
                ToolCall("r3", "read", ToolKind.Read, ToolCall.STATUS_COMPLETED, "page.tsx"),
                ToolCall("r4", "read", ToolKind.Read, ToolCall.STATUS_COMPLETED, "Leaderboard.tsx"),
                ToolCall("g1s", "grep", ToolKind.Grep, ToolCall.STATUS_COMPLETED, "hoveredId"),
                ToolCall("g2s", "grep", ToolKind.Grep, ToolCall.STATUS_COMPLETED, "onMouseEnter"),
                ToolCall("sh1", "shell", ToolKind.Shell, ToolCall.STATUS_COMPLETED, "git log --oneline -5"),
            ),
        ),
        AssistantMessage("a2", "Spawning a cloud subagent to finish chart highlighting when hovering table rows (partial work is already in the branch)."),
        ActivityGroup(
            "g2",
            listOf(
                ToolCall(
                    "t1", "task", ToolKind.Task, ToolCall.STATUS_RUNNING, "CursorBench chart hover highlight",
                    payload = ToolPayload.Subagent("CursorBench chart hover highlight", agentId = "bc-cb1", model = "composer-2.5", environment = "SUBAGENT_EXECUTION_ENVIRONMENT_CLOUD", isBackground = true),
                ),
            ),
        ),
        ActivityGroup("g3", listOf(ThinkingBlock("It runs in the background; say so.", durationSeconds = 1))),
        AssistantMessage("a3", "A cloud agent is working on this in the background.\n\n**Goal:** When you hover a model in the leaderboard table on `/cursorbench`, the chart should clearly highlight that model.\n\nI'll report back when the cloud run finishes."),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 38_000, emptyList(), endedAtMillis = at + 38_000),
    )

    private fun agentTurn(mode: ThemeMode, name: String) {
        val presented = TranscriptPresenter().present(agentItems, coordinatorMode = false, runActive = false)
        assertThat(presented.rows.filterIsInstance<TranscriptRow.Subagent>().single().subagent.source).isEqualTo(SubagentCall.Source.Task)
        val controls = TranscriptControls(
            onOpenAgent = {}, agentById = { null }, models = models, subagents = presented.subagents, placement = SubagentPlacement.Cloud,
            subagentActivity = { MutableStateFlow(SubagentChild(SubagentChild.Status.Running, step = "Implement bidirectional hover linking")) },
        )
        show(mode, controls) { presented.rows.forEach { TranscriptRowView(it) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Implement bidirectional hover linking").fetchSemanticsNodes().isNotEmpty() }
        capture(name)
    }

    @Test
    fun agentDark() = agentTurn(ThemeMode.Dark, "304_subagent_row_agent_dark")

    @Test
    fun agentLight() = agentTurn(ThemeMode.Light, "305_subagent_row_agent_light")
}
