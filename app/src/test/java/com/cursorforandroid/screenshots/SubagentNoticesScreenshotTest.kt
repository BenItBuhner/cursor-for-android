package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.domain.SystemNotifications
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
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The reports of a Project coordinator's subagents and workers as they come in after its answer (Bennett's chat):
 * inside the group of work they arrived in, the run of them behind one line. A cloud subagent reported twice, a
 * second cloud subagent, a worker whose notice the coordinator answered with a remark, and a local subagent that
 * failed — closed, then open. Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SubagentNoticesScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val at = 1_789_340_000_000L
    private val minute = 60_000L

    @Before
    fun pinClock() {
        AppClock.nowMillis = { at + 14 * minute }
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

    private fun task(id: String, title: String, model: String, agentId: String? = null, environment: String) = ToolCall(
        id, "task", ToolKind.Task, ToolCall.STATUS_COMPLETED, title,
        payload = ToolPayload.Subagent(title, agentId = agentId, model = model, environment = environment, isBackground = true),
    )

    private fun create(id: String, agentId: String, name: String, model: String) = ToolCall(
        id, "create_agent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, name,
        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOf(WorkerStatus(agentId, name)), text = name, title = name, reported = true, model = model),
        linkedAgentIds = listOf(agentId),
    )

    /** One injected turn as Cursor writes it: the finished task's header and report, then its instruction to the model. */
    private fun notice(id: String, minutes: Long, title: String, detail: String, kind: String = "subagent", status: String = "success", agentId: String? = null, callId: String? = null): List<TimelineItem> {
        val header = listOfNotNull("kind: $kind", "status: $status", "title: $title", callId?.let { "tool_call_id: $it" }, agentId?.let { "agent_id: $it" })
        val hint = agentId?.let { "\n\nAgent ID: $it" }.orEmpty()
        val text = "<timestamp>Thursday, Sep 24, 2026</timestamp>\n<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n" +
            "<task>\n${header.joinToString("\n")}\ndetail: This is the last output of the $kind:\n\n$detail$hint\n</task>\n</system_notification>\n" +
            "<user_query>Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required.</user_query>"
        return SystemNotifications.parse(id, text, at + minutes * minute)!!.items
    }

    private fun silent(id: String, minutes: Long) = RunFooter("$id-footer", "run-$id", RunStatus.FINISHED, 4_000, emptyList(), endedAtMillis = at + minutes * minute + 4_000)

    private val items: List<TimelineItem> = listOf(
        UserMessage("u1", "Is the edge real, and is it big enough to fund? Put a number on it.", at - 2 * minute),
        ActivityGroup(
            "g1",
            listOf(
                ThinkingBlock("Two checks in the cloud, the rebate tiers here, the ledger to a worker.", durationSeconds = 2),
                task("t1", "Track big-pool lone quoters daily", "composer-2.5", agentId = "bc-q1", environment = "SUBAGENT_EXECUTION_ENVIRONMENT_CLOUD"),
                task("t2", "Record Kalshi LIP side coverage", "claude-opus-5-5-high", agentId = "bc-k2", environment = "SUBAGENT_EXECUTION_ENVIRONMENT_CLOUD"),
                task("t3", "Price the maker rebate tiers", "gpt-5.6-terra-high", environment = "SUBAGENT_EXECUTION_ENVIRONMENT_LOCAL"),
                create("c1", "bc-f1", "Backfill the fills ledger", "composer-2.5"),
                ToolCall(
                    "m1", "SendMessage", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "The edge",
                    payload = ToolPayload.CoordinatorMessage("So: the edge is very likely real and very likely large enough. The remaining risk is execution at breadth, which is the part paper can only take so far and a small funded book settles. I expect \$1,000 a day."),
                ),
            ),
        ),
        AssistantMessage("a1", "Answered: yes, very likely, with the number unchanged at 70 and the remaining 30 named as execution-at-breadth questions rather than existence questions. Nothing on the board changed."),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 63_000, emptyList(), endedAtMillis = at + 63_000),
    ) +
        notice("n1", 8, "Track big-pool lone quoters daily", "Seven lone quoters hold 41% of big-pool depth; the daily tracker is committed.", agentId = "bc-q1", callId = "t1") + silent("n1", 8) +
        notice("n2", 10, "Record Kalshi LIP side coverage", "Coverage recorded for both sides of 212 markets.", agentId = "bc-k2", callId = "t2") + silent("n2", 10) +
        notice("n3", 11, "Record Kalshi LIP side coverage", "Coverage re-recorded after the 14:00 roll; 3 markets flipped sides.", agentId = "bc-k2", callId = "t2") + silent("n3", 11) +
        notice("n4", 12, "Backfill the fills ledger", "Backfilled 18 months of fills; PR #88 is merged.", kind = "worker", agentId = "bc-f1") +
        listOf(AssistantMessage("n4-remark", "Noted: the ledger is complete back to March 2025."), silent("n4", 12)) +
        notice("n5", 12, "Price the maker rebate tiers", "The rebate schedule endpoint returned 403 for every tier after two retries.", status = "error", callId = "t3") + silent("n5", 12)

    private fun agent(id: String, name: String, modelId: String) = Agent(
        id = id, name = name, lifecycle = AgentLifecycle.ACTIVE, runStatus = RunStatus.FINISHED, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/$id", createdAtMillis = at, updatedAtMillis = at, latestRunId = "run-$id", repoUrl = null, startingRef = null,
        modelId = modelId,
    )

    private val agents = mapOf(
        "bc-q1" to agent("bc-q1", "Track big-pool lone quoters daily", "composer-2.5"),
        "bc-k2" to agent("bc-k2", "Record Kalshi LIP side coverage", "claude-opus-5-5-high"),
        "bc-f1" to agent("bc-f1", "Backfill the fills ledger", "composer-2.5"),
    )

    private val presented by lazy { TranscriptPresenter().present(items, coordinatorMode = true, runActive = false) }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show() {
        val controls = TranscriptControls(
            onOpenAgent = {}, agentById = { agents[it] }, coordinatorMode = true, models = LiveModelCatalog.models,
            subagents = presented.subagents, placement = SubagentPlacement.Cloud,
        )
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides controls) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        presented.rows.forEach { TranscriptRowView(it) }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("I expect", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Opens both stretches through their own lines — the calls that started the children, then their notices — and the group of notices. */
    private fun openNotices() {
        val lines = compose.onAllNodes(hasClickAction() and hasParent(hasTestTag("stretch")))
        repeat(lines.fetchSemanticsNodes().size) { lines[it].performClick() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("event-group")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("Show events").performClick()
        compose.waitForIdle()
    }

    @Test
    fun closed() {
        // The notices are inside the group of work they arrived in — the stretch after the answer — behind one line.
        val stretches = presented.rows.filterIsInstance<TranscriptRow.Stretch>()
        assertThat(stretches.map { it.summary.text }).containsExactly("4 agents · 1 thought", "Worked 1m 23s · 5 events · 1 note").inOrder()
        val group = stretches.last().entries.filterIsInstance<TranscriptRow.Entry.Events>().single().group
        assertThat(group.summary.text).isEqualTo("5 events · 3 subagents · 1 worker · 4m span")
        assertThat(presented.rows.none { it is TranscriptRow.Event || it is TranscriptRow.Events }).isTrue()
        show()
        compose.onAllNodes(hasTestTag("event-row")).assertCountEquals(0)
        capture("343_subagent_notices_closed")
    }

    @Test
    fun opened() {
        show()
        openNotices()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("subagent-notice"), useUnmergedTree = true).fetchSemanticsNodes().size == 4 }
        // Each notice is its subagent's row, as the call that started it is drawn: the repeat once, no count, no age.
        listOf("Track big-pool lone quoters daily", "Record Kalshi LIP side coverage", "Backfill the fills ledger").forEach { title ->
            compose.onAllNodesWithContentDescription("Subagent $title, Completed").assertCountEquals(2)
        }
        compose.onAllNodesWithContentDescription("Subagent Price the maker rebate tiers, Stopped with error").assertCountEquals(2)
        assertThat(compose.onAllNodesWithText("\u00D72").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText(" \u00B7 completed").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasTestTag("event-time"), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Noted: the ledger is complete back to March 2025.").assertExists()
        capture("344_subagent_notices_open")
    }
}
