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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.data.repo.TraceStatus
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.cursorforandroid.fixtures.RecordFixtures
import com.cursorforandroid.ui.components.ShimmerText
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.OlderTurnsRow
import com.cursorforandroid.ui.conversation.TraceStatusRow
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The 240-turn Project of Bennett's 2026-09-20 frame (see [LongProject]) reopened a moment after it was left, drawn
 * two ways: as 0.3.46 drew it — the chat read again from the network, the fallback's newest runs bare while their
 * logs replay and the older turns page in behind the spinner ("Loading older…", "Loading the activity of 9 turns…",
 * one stretch, "Working…") — and as it is drawn now: the transcript as the reader left it, from memory, the
 * coordinator's messages and the user's prompt among its rows, nothing loading. Written to `screenshots/` and
 * compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class LongProjectReopenScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val firstAt = 1_800_000_000_000L - LongProject.TURNS * LongProject.TURN_SPACING_MS
    private val turns = LongProject.turns(firstAt)

    @Before
    fun pinClock() {
        AppClock.nowMillis = { turns.last().startedAt + 90_000L }
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

    private fun run(turn: LongProject.Turn): RunDto =
        RunDto(id = turn.runId, agentId = LongProject.AGENT_ID, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)

    /** 0.3.46's reopen: the documented fallback's newest ten runs, bare — no transcript yet, no trace yet. */
    private fun rowsBefore(): List<TranscriptRow> {
        val newest = turns.takeLast(10)
        val items = TimelineBuilder.fromHistory(emptyList(), newest.map(::run))
        return TranscriptPresenter().present(items, coordinatorMode = true, runActive = true).rows
    }

    /** The reopen now: the newest thirty turns as the reader left them, from the record, the live turn's story last. */
    private fun rowsAfter(): List<TranscriptRow> {
        val kept = turns.takeLast(30)
        val steps = RecordFixtures.steps(JsonArray(kept.flatMap { it.record }))
        val cut = HeadlessTranscript.split(steps)
        val items = ArrayList<TimelineItem>()
        cut.forEachIndexed { i, turn ->
            val source = kept[i]
            turn.prompt?.let { prompt -> items += SystemNotifications.parse("rec-prompt-$i", prompt, source.startedAt)?.items ?: listOf(UserMessage("rec-prompt-$i", prompt, source.startedAt)) }
            items += HeadlessTranscript.body(turn, "rec-$i")
            source.durationMs?.let { items += RunFooter("rec-footer-$i", source.runId, RunStatus.FINISHED, it, emptyList()) }
        }
        return TranscriptPresenter().present(items, coordinatorMode = true, runActive = true).rows
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true)) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun longProjectReopenBefore() {
        val rows = rowsBefore()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).isEmpty()
        assertThat(rows.filterIsInstance<TranscriptRow.Stretch>()).hasSize(1)
        show {
            OlderTurnsRow(isLoading = true, onLoad = {})
            TraceStatusRow(TraceStatus(shown = 0, pending = 9, expired = 0, failed = 0), onRetry = {})
            rows.forEach { TranscriptRowView(it) }
            ShimmerText("Working…", style = CursorTheme.typography.base)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading older…").fetchSemanticsNodes().isNotEmpty() }
        capture("98_long_project_reopen_before")
    }

    @Test
    fun longProjectReopenAfter() {
        val rows = rowsAfter()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).isNotEmpty()
        assertThat(rows.any { it is TranscriptRow.Item && it.item is UserMessage }).isTrue()
        // The newest of the rows: what the screen opens on, bottom-anchored — the reports and the stretches between
        // them, the workers the coordinator queued work for among their steps, as the desktop's work group holds them.
        val shown = rows.takeLast(6)
        assertThat(shown.filterIsInstance<TranscriptRow.Stretch>().sumOf { it.subagents.size }).isAtLeast(2)
        show { shown.forEach { TranscriptRowView(it) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("stretch")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("loading-older")).assertCountEquals(0)
        capture("99_long_project_reopen_after")
    }
}
