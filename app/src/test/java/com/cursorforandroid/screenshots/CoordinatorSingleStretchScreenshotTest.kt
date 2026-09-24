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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.fixtures.RecordFixtures
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Bennett's coordinator chat as v0.3.21 lost it (internal/reference/coordinator-0.3.21-missing-updates.jpg), read
 * from the account's record through the app's own parser (see [CoordinatorFixtures], `record_coordinator_pages.json`):
 * his message, the coordinator's update — recorded whole, in streamed pieces, streamed back — as the message it is,
 * and everything between two messages as one stretch: "Worked 29m 48s · 148 events", the wall behind one line
 * inside it; then the stretch opened. Written to `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CoordinatorSingleStretchScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val lastPromptAt = CoordinatorFixtures.json("record_coordinator_pages.json").getValue("responses").jsonArray
        .mapNotNull { it.jsonObject["humanMessage"]?.jsonObject?.get("createdAt")?.jsonPrimitive?.long }.max()

    @Before
    fun pinClock() {
        AppClock.nowMillis = { lastPromptAt + 25 * 60_000L }
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

    private val worker = Agent(
        id = "bc-00000002-1a2d-5218-8e2e-7464ea74f671", name = "Render kitchen v2 shard R6-1of2", lifecycle = AgentLifecycle.ACTIVE, runStatus = RunStatus.RUNNING, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/bc-00000002-1a2d-5218-8e2e-7464ea74f671", createdAtMillis = 1L, updatedAtMillis = 2L, latestRunId = "run-w", repoUrl = "https://github.com/BenItBuhner/revenue-scaling-pipeline", startingRef = "main",
    )

    private fun items(): List<TimelineItem> = RecordFixtures.items("record_coordinator_pages.json", firstAt = lastPromptAt - 4 * 3_600_000L)

    private fun rows(): List<TranscriptRow> = TranscriptRows.of(CoordinatorTranscript.present(items(), coordinatorMode = true), coordinatorMode = true)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(rows: List<TranscriptRow>) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(
                    LocalRippleConfiguration provides null,
                    LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { worker.takeIf { a -> a.id == it } }, coordinatorMode = true, subagents = SubagentRows.index(rows)),
                ) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rows.forEach { TranscriptRowView(it) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun coordinatorSingleStretch() {
        val rows = rows()
        // The three updates are the messages; every silent turn, note and footer between two of them is one stretch.
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).hasSize(3)
        assertThat(rows.none { it is TranscriptRow.Event || it is TranscriptRow.Events }).isTrue()
        val wall = rows.filterIsInstance<TranscriptRow.Stretch>().single { it.eventCount == 148 }
        assertThat(wall.summary.text).isEqualTo("Worked 29m 48s · 148 events")
        assertThat(wall.listed.map { it::class.simpleName }).containsExactly("Events")
        show(rows)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Worked 29m 48s").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("event-row")).assertCountEquals(0)
        // The worker the list holds as running was messaged again in the last stretch: that row speaks for it, and
        // only its stretch still reads as working; the one it was created in has settled.
        compose.onAllNodesWithText("1 Working").assertCountEquals(1)
        capture("74_coordinator_single_stretch")
    }

    @Test
    fun coordinatorSingleStretchOpened() {
        show(rows())
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Worked 29m 48s").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Worked 29m 48s").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("event-group")).fetchSemanticsNodes().size == 1 }
        capture("75_coordinator_single_stretch_open")
    }
}
