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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A Project coordinator's chat rendered from the wire shapes as they really arrive (see [CoordinatorFixtures]): the
 * turn Cursor injected when a subagent finished — its instruction to the model hidden, the coordinator's brief remark
 * folded under the row — then the turn of the reference frame replayed from the documented stream's frames: the
 * coordinator's `SendMessage` updates as the messages — plain replies, read off `args.text.content` — and everything
 * between two of them (the injected turn's line and its run's footer, its notes, its thought, its message to a
 * worker, its edit) behind one summary line each. The chat is read as a coordinator's from this content alone; no
 * list row says so. Written to `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CoordinatorTranscriptScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    /** The injected turn's row says how long ago it was; pinned at two days after it so the frame reads the same every run. */
    @Before
    fun pinClock() {
        AppClock.nowMillis = { 1_789_340_700_000L + 2 * 24 * 3_600_000L + 3_600_000L }
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

    /** The workers the coordinator's calls name, as the list would hold them: the rows the cards and rows read their names from. */
    private val workers = mapOf(
        "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671" to agent("bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671", "Land merge train and prep release", RunStatus.FINISHED),
        "bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724" to agent("bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724", "Build Projects under Extended mode", RunStatus.RUNNING),
    )

    private fun agent(id: String, name: String, status: RunStatus) = Agent(
        id = id, name = name, lifecycle = if (status == RunStatus.RUNNING) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE, runStatus = status, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/$id", createdAtMillis = 1L, updatedAtMillis = 2L, latestRunId = "run-$id", repoUrl = "https://github.com/BenItBuhner/cursor-for-android", startingRef = "main",
    )

    /** The stream's turn, frame for frame, without the frames that show a message's edge cases (an attachment, a truncated one, a failed one). */
    private fun streamedTurn(): List<TimelineItem> {
        val source = Buffer().writeUtf8(CoordinatorFixtures.text("coordinator_run.sse"))
        val live = TimelineBuilder.LiveRun("run-coord-001", timed = false)
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            val event = (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.event ?: continue
            if (event is RunStreamEvent.ToolCall && event.call.callId in setOf("c5", "c6", "c7", "c9", "c10")) continue
            live.apply(event)
        }
        return live.snapshot()
    }

    /** The injected turn before it, from the real transcript, with the coordinator's brief remark and its footer. */
    private fun injectedTurn(): List<TimelineItem> {
        val injected = SystemNotifications.parse("m-inject", CoordinatorFixtures.injectedTurn("subagent_completion_necessary_follow_up"), 1_789_340_700_000L)!!
        return injected.items + AssistantMessage("asst-inject", "Noted; the release worker is done, nothing else needs doing before the cut.") + RunFooter("run-inject", "run-inject", RunStatus.FINISHED, 41_000, emptyList())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun coordinatorTranscriptFromTheLiveShapes() {
        val items = injectedTurn() + streamedTurn()
        // No list row, no account record: the content alone reads the chat as a coordinator's.
        assertThat(CoordinatorTranscript.hasCoordinatorContent(items)).isTrue()
        val shown = CoordinatorTranscript.present(items, coordinatorMode = true)
        // The instruction to the model is not a prompt; the remark is folded under the row; the updates are the messages.
        assertThat(shown.filterIsInstance<com.cursorforandroid.domain.UserMessage>()).isEmpty()
        assertThat(shown.filterIsInstance<SystemNotification>().single().narration).startsWith("Noted;")
        assertThat(shown.filterIsInstance<ActivityGroup>().flatMap { it.calls }.count { it.payload is ToolPayload.CoordinatorMessage }).isEqualTo(3)

        // The rows the list draws: each update a reply of its own, everything between two of them one summary line.
        val rows = TranscriptRows.of(shown, coordinatorMode = true)
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).hasSize(3)
        assertThat(rows.filterIsInstance<TranscriptRow.Stretch>().filter { it.single == null }.map { it.summary.text })
            .containsExactly("Worked 41s · 1 event · 1 agent · 1 thought", "1 edit · 1 note").inOrder()

        val controls = TranscriptControls(onOpenAgent = {}, agentById = { workers[it] }, coordinatorMode = true)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides controls) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rows.forEach { TranscriptRowView(it) }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("PR #215 is merged.").fetchSemanticsNodes().isNotEmpty() }
        capture("61_coordinator_transcript_live_shape")
    }
}
