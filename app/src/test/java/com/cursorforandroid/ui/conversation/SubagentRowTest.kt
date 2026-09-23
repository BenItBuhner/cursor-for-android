package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentCall
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.WorkerStatus
import com.cursorforandroid.fixtures.LiveModelCatalog
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
 * The subagent row on screen, for every source and state: the title, the model muted after it with Fast, the
 * placement glyph, the line under it and where a tap goes — and that line moving as the child works, each status
 * held long enough to be read.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SubagentRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val models = LiveModelCatalog.models

    private fun agent(id: String, status: RunStatus?, pending: Boolean = false, modelId: String? = null) = Agent(
        id = id, name = "Listed $id", lifecycle = AgentLifecycle.ACTIVE, runStatus = status, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/$id", createdAtMillis = 1L, updatedAtMillis = 2L, latestRunId = "run-$id", repoUrl = null, startingRef = null,
        hasPendingInteraction = pending, modelId = modelId,
    )

    private fun controls(
        agents: Map<String, Agent> = emptyMap(),
        activity: Map<String, MutableStateFlow<SubagentChild?>> = emptyMap(),
        runs: Map<String, SubagentChild> = emptyMap(),
        placement: SubagentPlacement? = SubagentPlacement.Cloud,
        navigate: Boolean = true,
    ) = TranscriptControls(
        onOpenAgent = if (navigate) ({ opened += it }) else null,
        agentById = { agents[it] },
        models = models,
        placement = placement,
        subagentActivity = { id -> activity[id] ?: MutableStateFlow(null) },
        subagentRuns = runs,
    )

    private fun show(call: ToolCall, controls: TranscriptControls = controls()) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides controls) { SubagentRowView(call, SubagentCall.of(call)!!) }
            }
        }
    }

    private fun task(status: String = ToolCall.STATUS_RUNNING, agentId: String? = null, model: String? = "composer-2.5", environment: String? = null, type: String? = null) = ToolCall(
        "t1", "task", ToolKind.Task, status, "CursorBench chart hover highlight",
        payload = ToolPayload.Subagent("CursorBench chart hover highlight", agentId = agentId, model = model, environment = environment, subagentType = type),
    )

    private fun created(agentId: String? = "bc-w1", status: String = ToolCall.STATUS_COMPLETED) = ToolCall(
        "c1", "create_agent", ToolKind.Coordinator, status, "Build Projects under Extended mode",
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOfNotNull(agentId?.let { WorkerStatus(it) }), text = "Fix the Projects list.", title = "Build Projects under Extended mode", model = "claude-opus-5-5-high"),
    )

    private fun sent(delivery: ToolPayload.WorkerAction.Delivery, title: String? = "Rebase onto main") = ToolCall(
        "s1", "send_to_agent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "bc-w1",
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus("bc-w1")), text = "PR #215 is merged; rebase.", title = title, delivery = delivery),
    )

    private fun stopped() = ToolCall(
        "x1", "stop_agent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "bc-w1",
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Stopped, listOf(WorkerStatus("bc-w1"))),
    )

    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)

    @Test
    fun `a task subagent at work - its title, its model with Fast, and planning under it`() {
        show(task())
        text("CursorBench chart hover highlight").assertIsDisplayed()
        text("Composer 2.5").assertIsDisplayed()
        compose.onNodeWithTag("subagent-fast", useUnmergedTree = true).assertIsDisplayed()
        text(SubagentRows.PLANNING).assertIsDisplayed()
        // It runs in its parent's VM: no placement glyph. Nothing to open but what the task said.
        assertThat(compose.onAllNodes(hasContentDescription("Cloud"), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag("subagent-row").assertIsDisplayed()
    }

    @Test
    fun `a task in a VM of its own shows the cloud, reads its record's step, and opens its chat`() {
        show(
            task(agentId = "bc-t1", environment = "SUBAGENT_EXECUTION_ENVIRONMENT_CLOUD", type = "explore"),
            controls(runs = mapOf("t1" to SubagentChild(SubagentChild.Status.Running, step = "Reading the chart code"))),
        )
        text("Explorer").assertIsDisplayed()
        compose.onNode(hasContentDescription("Cloud"), useUnmergedTree = true).assertExists()
        text("Reading the chart code").assertIsDisplayed()
        compose.onNodeWithTag("subagent-row").performClick()
        assertThat(opened).containsExactly("bc-t1")
    }

    @Test
    fun `a worker created reads the model it was asked for, its live state, and opens the worker`() {
        show(created(), controls(agents = mapOf("bc-w1" to agent("bc-w1", RunStatus.RUNNING))))
        text("Build Projects under Extended mode").assertIsDisplayed()
        compose.onNodeWithTag("subagent-model", useUnmergedTree = true).assertIsDisplayed()
        assertThat(compose.onAllNodes(hasContentDescription("Cloud"), useUnmergedTree = true).fetchSemanticsNodes()).hasSize(1)
        text(SubagentRows.PLANNING).assertIsDisplayed()
        compose.onNodeWithTag("subagent-row").performClick()
        assertThat(opened).containsExactly("bc-w1")
    }

    @Test
    fun `a worker still being created is starting up and opens onto its prompt`() {
        show(created(agentId = null, status = ToolCall.STATUS_RUNNING))
        text(SubagentRows.STARTING).assertIsDisplayed()
        compose.onNodeWithTag("subagent-row").performClick()
        text("Fix the Projects list.").assertIsDisplayed()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `a steer and a queued message are the worker's row, titled with the message`() {
        show(sent(ToolPayload.WorkerAction.Delivery.Followup), controls(agents = mapOf("bc-w1" to agent("bc-w1", RunStatus.RUNNING, modelId = "composer-2.5"))))
        text("Rebase onto main").assertIsDisplayed()
        text("Composer 2.5").assertIsDisplayed()
        compose.onNodeWithTag("subagent-row").performClick()
        assertThat(opened).containsExactly("bc-w1")
    }

    @Test
    fun `a queued message with no title is the desktop's Agent follow-up, done once its worker is`() {
        show(sent(ToolPayload.WorkerAction.Delivery.Queue, title = null), controls(agents = mapOf("bc-w1" to agent("bc-w1", RunStatus.FINISHED))))
        text(SubagentCall.FOLLOW_UP).assertIsDisplayed()
        text(SubagentRows.COMPLETED).assertIsDisplayed()
    }

    @Test
    fun `a stop names the worker and reads stopped`() {
        show(stopped(), controls(agents = mapOf("bc-w1" to agent("bc-w1", RunStatus.CANCELLED))))
        text("Listed bc-w1").assertIsDisplayed()
        text(SubagentRows.STOPPED).assertIsDisplayed()
    }

    @Test
    fun `an errored worker stops with an error, and one waiting on the reader says so`() {
        show(created(), controls(agents = mapOf("bc-w1" to agent("bc-w1", RunStatus.ERROR))))
        text(SubagentRows.STOPPED_WITH_ERROR).assertIsDisplayed()
        compose.onNode(hasContentDescription("Subagent Build Projects under Extended mode, ${SubagentRows.STOPPED_WITH_ERROR}")).assertExists()
    }

    @Test
    fun `a worker waiting on the reader reads waiting for input`() {
        show(created(), controls(agents = mapOf("bc-w1" to agent("bc-w1", RunStatus.RUNNING, pending = true))))
        text(SubagentRows.WAITING).assertIsDisplayed()
    }

    @Test
    fun `without a way to navigate even a cloud worker's row opens onto its prompt`() {
        show(created(), controls(agents = mapOf("bc-w1" to agent("bc-w1", RunStatus.FINISHED)), navigate = false))
        compose.onNodeWithTag("subagent-row").performClick()
        text("Fix the Projects list.").assertIsDisplayed()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `the action line updates live as the child works, each status held before the next rolls in`() {
        val live = MutableStateFlow<SubagentChild?>(SubagentChild(SubagentChild.Status.Running))
        compose.mainClock.autoAdvance = false
        show(created(), controls(agents = mapOf("bc-w1" to agent("bc-w1", RunStatus.RUNNING)), activity = mapOf("bc-w1" to live)))
        compose.mainClock.advanceTimeBy(100)
        text(SubagentRows.PLANNING).assertExists()

        // Past the hold: the new action rolls in at once.
        compose.mainClock.advanceTimeBy(1_500)
        live.value = SubagentChild(SubagentChild.Status.Running, action = "Editing Chart.kt")
        compose.mainClock.advanceTimeBy(400)
        text("Editing Chart.kt").assertExists()
        assertThat(compose.onAllNodesWithText(SubagentRows.PLANNING, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()

        // Two more inside the hold: the first waits its turn and is skipped, the newest shows once the hold is up.
        live.value = SubagentChild(SubagentChild.Status.Running, action = "Searching web compose shimmer")
        live.value = SubagentChild(SubagentChild.Status.Running, action = "Thinking")
        compose.mainClock.advanceTimeBy(300)
        text("Editing Chart.kt").assertExists()
        compose.mainClock.advanceTimeBy(1_200)
        text("Thinking").assertExists()
        assertThat(compose.onAllNodesWithText("Searching web compose shimmer", useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()

        // The child finishes: the row settles.
        compose.mainClock.advanceTimeBy(1_500)
        live.value = SubagentChild(SubagentChild.Status.Succeeded)
        compose.mainClock.advanceTimeBy(400)
        text(SubagentRows.COMPLETED).assertExists()
    }
}
