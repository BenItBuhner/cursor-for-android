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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.fixtures.SevenRunCoordinator
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
 * Bennett's 2026-09-19 frame put right (internal/reference/duplicate-coordinator-messages/, see
 * [SevenRunCoordinator]): the seven runs' logs as the phone read them on the documented path — the silent runs'
 * logs carrying run 2's message again — drawn as the three messages the coordinator sent, each once, with one
 * stretch between two of them summing the runs it covers ("Worked 3m 10s") and the running run's stretch live.
 * Written to `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CoordinatorMessagesOnceScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val firstAt = 1_789_340_000_000L

    @Before
    fun pinClock() {
        AppClock.nowMillis = { firstAt + 9 * 60_000L }
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

    private fun rows(): List<TranscriptRow> {
        val items = SevenRunCoordinator.transcript(SevenRunCoordinator.runs(firstAt), prompts = false, firstAt)
        return TranscriptPresenter().present(items, coordinatorMode = true, runActive = true).rows
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(rows: List<TranscriptRow>) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true)) {
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
    fun coordinatorMessagesOnce() {
        val rows = rows()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>().map { (it.call.payload as ToolPayload.CoordinatorMessage).message })
            .containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
        val stretches = rows.filterIsInstance<TranscriptRow.Stretch>()
        assertThat(stretches.map { it.summary.text }).containsExactly("Worked 59s · 2 agents · 2 notes", "Worked 3m 10s · 1 edit · 7 agents · 6 notes", "Working · 1 edit · 5 agents · 1 note").inOrder()
        show(rows)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Worked 3m 10s").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("coordinator-message")).assertCountEquals(3)
        capture("97_coordinator_messages_once")
    }
}
