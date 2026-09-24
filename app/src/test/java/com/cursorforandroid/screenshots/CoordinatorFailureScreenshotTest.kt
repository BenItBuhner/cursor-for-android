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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
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
 * Bennett's v0.3.39 coordinator, whose turn an infrastructure error cut off mid-tool-call ("Tool result not found")
 * and whose run the server recorded as failed (`record_error_continued.json`, read through the app's own parser):
 * first as the chat stood right after — the failure one compact line of its own under the turn's stretch, the
 * server's reason and the time on it, the chat's state — then as it stands once the conversation went on: the same
 * failure a line inside its stretch, the summary ending "· failed", the stretch opened to show it; the turn before,
 * whose run logged the same error and finished, saying nothing of failure. No banner in either. Written to
 * `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CoordinatorFailureScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val fixture = CoordinatorFixtures.json("record_error_continued.json")
    private val promptTimes = fixture.getValue("responses").jsonArray.mapNotNull { it.jsonObject["humanMessage"]?.jsonObject?.get("createdAt")?.jsonPrimitive?.long }

    @Before
    fun pinClock() {
        AppClock.nowMillis = { promptTimes.last() + 35 * 60_000L }
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

    /** The record's turns with the runs' footers as the server has them: A finished (the error on the way), B failed on it, C finished. */
    private fun items(turns: Int): List<TimelineItem> {
        val record = HeadlessTranscript.split(RecordFixtures.steps("record_error_continued.json")).take(turns)
        val statuses = listOf(RunStatus.FINISHED to 253_000L, RunStatus.ERROR to 41_000L, RunStatus.FINISHED to 30_000L)
        val out = ArrayList<TimelineItem>()
        record.forEachIndexed { index, turn ->
            val at = promptTimes[index]
            turn.prompt?.let { out += SystemNotifications.parse("rec-prompt-$index", it, at)?.items ?: listOf(UserMessage("rec-prompt-$index", it, at)) }
            out += HeadlessTranscript.body(turn, "rec-$index")
            val (status, duration) = statuses[index]
            val reason = HeadlessTranscript.errorMessage(turn).takeIf { status == RunStatus.ERROR }
            out += RunFooter("rec-footer-$index", "run-$index", status, duration, emptyList(), endedAtMillis = at + duration, reason = reason)
        }
        return out
    }

    private fun rows(turns: Int): List<TranscriptRow> = TranscriptRows.of(CoordinatorTranscript.present(items(turns), coordinatorMode = true), coordinatorMode = true)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(rows: List<TranscriptRow>) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, coordinatorMode = true)) {
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
    fun coordinatorRunFailed() {
        val rows = rows(turns = 2)
        // The newest run failed and nothing came after: its line stands after the stretch; turn A says nothing of failure.
        val failure = rows.last() as TranscriptRow.Failure
        assertThat(failure.footer.reason).isEqualTo("Tool result not found for toolu_status_01")
        assertThat(rows.filterIsInstance<TranscriptRow.Stretch>().map { it.summary.text }).containsExactly("2 agents · 1 note", "Worked 4m 13s · 1 note", "Worked 41s · 1 agent · 1 note").inOrder()
        assertThat(rows.none { it is TranscriptRow.Item && it.item is NoticeCard }).isTrue()
        show(rows)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Run failed").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("run-failure")).assertCountEquals(1)
        capture("90_coordinator_run_failed")
    }

    @Test
    fun coordinatorRunFailedMovedOn() {
        val rows = rows(turns = 3)
        assertThat(rows.none { it is TranscriptRow.Failure }).isTrue()
        val failed = rows.filterIsInstance<TranscriptRow.Stretch>().single { it.failures.isNotEmpty() }
        assertThat(failed.summary.text).isEqualTo("Worked 41s · 1 agent · 1 note · failed")
        show(rows)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Worked 41s").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("run-failure")).assertCountEquals(0)
        compose.onAllNodesWithText("Worked 41s").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("run-failure")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Run failed").assertExists()
        capture("91_coordinator_run_failed_moved_on")
    }
}
