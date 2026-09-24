package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.WorkerStatus
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.fixtures.LongProject
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Subagent rows inside the group of tool calls they were made among, as Cursor's desktop keeps them (its work group
 * folds a task's row in with the rest, Cursor 3.21.18): hidden while the group is closed, in their place when it is
 * open, the group whole around them. An agent's turn that ran an explorer and left a cloud subagent working, and a
 * Project coordinator's turn that started two workers and stopped a third — closed, where the group's line says
 * how many still work ("1 working", "2 Working" and where the newest stands) rather than "Worked", then open; and a
 * small Project opened through the real pipeline under each transcript engine, its newest group open. The
 * coordinator's `SendMessage` update stays a reply of its own. Written to `screenshots/` and compared pixel for
 * pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SubagentGroupsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val models = LiveModelCatalog.models
    private val at = 1_789_340_000_000L

    @Before
    fun pinClock() {
        AppClock.nowMillis = { at + 10 * 60_000L }
    }

    @After
    fun unpinClock() {
        rig?.close()
        server?.close()
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Opens every stretch on screen, or the newest [last] of them, through its own line, as the reader would. */
    private fun open(last: Int = Int.MAX_VALUE) {
        val lines = compose.onAllNodes(hasClickAction() and hasParent(hasTestTag("stretch")))
        val count = lines.fetchSemanticsNodes().size
        for (i in (count - last).coerceAtLeast(0) until count) lines[i].performClick()
        compose.waitForIdle()
    }

    // -- an agent's turn and a coordinator's, closed and open ---------------------------------------------------

    private fun read(id: String, file: String) = ToolCall(id, "read", ToolKind.Read, ToolCall.STATUS_COMPLETED, file)

    private val agentItems: List<TimelineItem> = listOf(
        UserMessage("u1", "Make hovered models highlight in the evals chart, and find out why the legend ignores hover.", at),
        ActivityGroup(
            "g1",
            listOf(
                ThinkingBlock("Look at the evals page first.", durationSeconds = 1),
                read("r1", "CursorBenchResults.tsx"),
                read("r2", "Chart.tsx"),
                ToolCall("s1", "grep", ToolKind.Grep, ToolCall.STATUS_COMPLETED, "hoveredId"),
                ToolCall(
                    "e1", "task", ToolKind.Task, ToolCall.STATUS_COMPLETED, "Map the legend's hover state",
                    payload = ToolPayload.Subagent("Map the legend's hover state", subagentType = "explore", environment = "SUBAGENT_EXECUTION_ENVIRONMENT_LOCAL"),
                ),
                read("r3", "Legend.tsx"),
                ToolCall("x1", "shell", ToolKind.Shell, ToolCall.STATUS_COMPLETED, "git log --oneline -5"),
                ToolCall(
                    "t1", "task", ToolKind.Task, ToolCall.STATUS_RUNNING, "CursorBench chart hover highlight",
                    payload = ToolPayload.Subagent("CursorBench chart hover highlight", agentId = "bc-cb1", model = "composer-2.5", environment = "SUBAGENT_EXECUTION_ENVIRONMENT_CLOUD", isBackground = true),
                ),
            ),
        ),
        AssistantMessage("a1", "The legend never subscribes to the hovered id; a cloud agent is wiring both up in the background and I'll report back when it lands."),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 38_000, emptyList(), endedAtMillis = at + 38_000),
    )

    private fun create(callId: String, agentId: String, name: String, prompt: String, model: String) = ToolCall(
        callId, "create_agent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, name,
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOf(WorkerStatus(agentId, name)), text = prompt, title = name, reported = true, model = model),
        linkedAgentIds = listOf(agentId),
    )

    private val coordinatorItems: List<TimelineItem> = listOf(
        UserMessage("u2", "Split the Projects work: one worker on the list leak, one on the release. Stop the old release worker.", at + 60_000L),
        ActivityGroup(
            "g2",
            listOf(
                ThinkingBlock("Two independent tracks, a worker for each; the old one goes.", durationSeconds = 2),
                create("c1", "bc-w1", "Fix the Projects list leak", "Workers leak into Today; keep them under their Project.", "composer-2.5"),
                create("c2", "bc-w2", "Land merge train and prep release", "Merge #113 and cut v0.3.4.", "claude-opus-5-5-high"),
                ToolCall(
                    "k1", "stop_agent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "bc-w3",
                    payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Stopped, listOf(WorkerStatus("bc-w3"))), linkedAgentIds = listOf("bc-w3"),
                ),
                ToolCall("m1", "SendMessage", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "Both tracks are running", payload = ToolPayload.CoordinatorMessage("Both tracks are running: the list leak is being fixed and the release waits on the merge train. The old release worker is stopped.")),
            ),
        ),
        RunFooter("f2", "run-2", RunStatus.FINISHED, 45_000, emptyList(), endedAtMillis = at + 105_000L),
    )

    private fun agent(id: String, name: String, status: RunStatus, modelId: String? = null) = Agent(
        id = id, name = name, lifecycle = AgentLifecycle.ACTIVE, runStatus = status, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/$id", createdAtMillis = at, updatedAtMillis = at, latestRunId = "run-$id", repoUrl = null, startingRef = null,
        modelId = modelId,
    )

    private val workers = mapOf(
        "bc-w1" to agent("bc-w1", "Fix the Projects list leak", RunStatus.RUNNING, "composer-2.5"),
        "bc-w2" to agent("bc-w2", "Land merge train and prep release", RunStatus.RUNNING, "claude-opus-5-5-high"),
        "bc-w3" to agent("bc-w3", "Old release worker", RunStatus.CANCELLED),
    )

    private val activity = mapOf(
        "bc-cb1" to SubagentChild(SubagentChild.Status.Running, step = "Implement bidirectional hover linking"),
        "bc-w1" to SubagentChild(SubagentChild.Status.Running, action = "Editing AgentListOrganizer.kt"),
        "bc-w2" to SubagentChild(SubagentChild.Status.Running, step = "Waiting on CI for #113"),
    )

    private val agentRows by lazy { TranscriptPresenter().present(agentItems, coordinatorMode = false, runActive = false) }
    private val coordinatorRows by lazy { TranscriptPresenter().present(coordinatorItems, coordinatorMode = true, runActive = false) }

    private fun controls(presented: TranscriptPresenter.Presented, coordinator: Boolean) = TranscriptControls(
        onOpenAgent = {}, agentById = { workers[it] }, coordinatorMode = coordinator, models = models, subagents = presented.subagents, placement = SubagentPlacement.Cloud,
        subagentActivity = { id -> MutableStateFlow(activity[id]) },
    )

    private fun turns() {
        show {
            CompositionLocalProvider(LocalTranscriptControls provides controls(agentRows, coordinator = false)) {
                agentRows.rows.forEach { TranscriptRowView(it) }
            }
            CompositionLocalProvider(LocalTranscriptControls provides controls(coordinatorRows, coordinator = true)) {
                coordinatorRows.rows.forEach { TranscriptRowView(it) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Both tracks are running", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits until each of [texts] is on screen: the children's own lines, rolled in after the row's first. */
    private fun awaitShown(vararg texts: String) = texts.forEach { text ->
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * The rows hold every subagent inside a stretch and no subagent splits one — never two stretches side by side —
     * while the coordinator's `SendMessage` updates stay rows of their own.
     */
    private fun assertGrouped(rows: List<TranscriptRow>, subagents: Int) {
        rows.zipWithNext().forEach { (a, b) -> assertThat(a is TranscriptRow.Stretch && b is TranscriptRow.Stretch).isFalse() }
        val stretches = rows.filterIsInstance<TranscriptRow.Stretch>()
        assertThat(stretches.sumOf { it.subagents.size }).isAtLeast(subagents)
        assertThat(stretches.flatMap { it.entries }.none { it is TranscriptRow.Entry.Call && it.call.payload is ToolPayload.CoordinatorMessage }).isTrue()
    }

    @Test
    fun closed() {
        assertThat(agentRows.rows.map { it::class }).containsExactly(TranscriptRow.Item::class, TranscriptRow.Stretch::class, TranscriptRow.Item::class, TranscriptRow.Stretch::class).inOrder()
        assertThat(coordinatorRows.rows.map { it::class }).containsExactly(TranscriptRow.Item::class, TranscriptRow.Stretch::class, TranscriptRow.Message::class, TranscriptRow.Stretch::class).inOrder()
        assertGrouped(agentRows.rows, subagents = 2)
        assertGrouped(coordinatorRows.rows, subagents = 3)
        turns()
        // Closed, the rows are behind their groups' lines, which count what still works.
        awaitShown("1 working", "2 Working", "Waiting on CI for #113")
        compose.onAllNodes(hasTestTag("subagent-row")).assertCountEquals(0)
        capture("339_subagent_groups_closed")
    }

    @Test
    fun opened() {
        turns()
        open()
        awaitShown("Implement bidirectional hover linking", "Editing AgentListOrganizer.kt", "Waiting on CI for #113")
        // Open, each row is in its place among the calls it was made between.
        compose.onAllNodes(hasTestTag("subagent-row")).assertCountEquals(5)
        val tops = listOf("Chart.tsx", "Map the legend's hover state", "Legend.tsx", "CursorBench chart hover highlight")
            .map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertThat(tops).isInStrictOrder()
        capture("340_subagent_groups_open")
    }

    // -- a small Project through the real pipeline, under each engine ---------------------------------------------

    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L
    /** Six finished turns of the Project: the user's, then its workers' reports. */
    private val projectTurns = LongProject.turns(firstAt, turns = 7).dropLast(1)
    private var server: FaultServer? = null
    private var rig: FaultRig? = null

    private fun serve(): FaultServer = FaultServer(rttMillis = 40L..90L).start().also { server ->
        projectTurns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = LongProject.result(turn))
            server.logs[turn.runId] = turn.log
        }
        val newest = projectTurns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "IDLE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[agentId] = LongProject.v0Transcript(projectTurns)
        server.records[agentId] = projectTurns.flatMap { it.record }
        this.server = server
    }

    /** The Project as the pipeline renders it under [engine], settled: nothing loading, the traces read, the items still. */
    private fun project(engine: TranscriptEngine): List<TranscriptRow> = runBlocking {
        rig?.close()
        server?.close()
        val server = serve()
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig-$engine"), readTimeoutMs = 8_000L, extended = true, engine = engine).also {
            it.now = projectTurns.last().endedAt + 60_000L
            rig = it
        }
        val state = { rig.conversations.state(agentId).value }
        rig.conversations.attach(agentId)
        rig.awaitUntil(60_000) { state().let { !it.isLoading && it.items.isNotEmpty() } }
        rig.awaitUntil(90_000) {
            val asked = server.seen.size
            val items = state().items
            delay(1_500)
            server.seen.size == asked && state().items == items && !state().isLoadingOlder && state().traceStatus.pending == 0
        }
        val settled: ConversationState = state()
        TranscriptPresenter().present(settled.items, coordinatorMode = true, runActive = false).rows
    }

    private fun projectFrame(engine: TranscriptEngine, name: String) {
        AppClock.nowMillis = { projectTurns.last().endedAt + 60_000L }
        val rows = project(engine)
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).isNotEmpty()
        assertGrouped(rows, subagents = 2)
        show {
            CompositionLocalProvider(LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true)) {
                rows.forEach { TranscriptRowView(it) }
            }
        }
        open(last = 1)
        capture(name)
    }

    @Test
    fun projectOnStable() = projectFrame(TranscriptEngine.STABLE, "341_subagent_groups_stable")

    @Test
    fun projectOnBeta() = projectFrame(TranscriptEngine.BETA, "342_subagent_groups_beta")

    /** A row as its shape: the kind, a stretch's line and where each of its subagents came from. */
    private fun shape(row: TranscriptRow): String = when (row) {
        is TranscriptRow.Stretch -> "stretch:${row.summary.text}:${row.subagents.map { it.subagent?.source }}"
        else -> row::class.simpleName!!
    }

    @Test
    fun bothEnginesDrawTheSameGroups() {
        AppClock.nowMillis = { projectTurns.last().endedAt + 60_000L }
        val stable = project(TranscriptEngine.STABLE)
        val beta = project(TranscriptEngine.BETA)
        assertThat(beta.map(::shape)).isEqualTo(stable.map(::shape))
    }
}
