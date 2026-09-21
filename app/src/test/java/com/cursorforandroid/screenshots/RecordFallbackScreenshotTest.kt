package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.repo.RecordFallback
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.fixtures.LongProject
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.RECORD_FALLBACK_DETAIL
import com.cursorforandroid.ui.conversation.RECORD_FALLBACK_TITLE
import com.cursorforandroid.ui.conversation.RecordFallbackRow
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorDimens
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
 * The account's record refused and the documented endpoints standing in, said as such (see
 * `ConversationState.recordFallback`): the newest runs of the 240-turn Project bare, and over the composer a card
 * naming the refusal in the server's words and what is on screen because of it, with Retry and the diagnostics as
 * buttons inside it and an X in its corner — what Bennett's 2026-09-20 frames showed without a word, then as bare
 * lines over the box, then as a card with no way to put it away; and the same screen with the X tapped. Written to
 * `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RecordFallbackScreenshotTest {

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

    private val fallback = RecordFallback("Rate limited by Cursor: Too many requests. Try again in 2 s.", sinceMillis = AppClock.now(), readMillis = 640L, retryAfterMillis = 2_000L)

    /** The newest runs of the Project bare, and under them the dock: the record's card while [shown], and the composer. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen(shown: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
        val newest = turns.takeLast(10)
        val runs = newest.map { RunDto(id = it.runId, agentId = LongProject.AGENT_ID, status = it.status, createdAt = LongProject.iso(it.startedAt), updatedAt = LongProject.iso(it.endedAt), durationMs = it.durationMs, result = null) }
        val rows = TranscriptPresenter().present(TimelineBuilder.fromHistory(emptyList(), runs), coordinatorMode = true, runActive = true).rows
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true)) {
                Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rows.forEach { TranscriptRowView(it) }
                    }
                    // The dock as the chat lays it out: the record's card first, over the box, in the gutter.
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = CursorDimens.composerGutter).padding(bottom = CursorDimens.composerBottomGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (shown) RecordFallbackRow(fallback, onRetry = onRetry, onShareDiagnostics = {}, onDismiss = onDismiss, modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp))
                        ComposerBox(
                            value = "",
                            onValueChange = {},
                            placeholder = "Follow up (queues on your account)…",
                            onSend = {},
                            isRunning = true,
                            onStop = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun recordFallbackRow() {
        var retried = 0
        compose.setContent { Screen(shown = true, onRetry = { retried++ }, onDismiss = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("$RECORD_FALLBACK_TITLE: ${fallback.reason}").assertExists()
        compose.onNodeWithText(RECORD_FALLBACK_DETAIL).assertExists()
        compose.onNodeWithTag("record-fallback-retry").performClick()
        assertThat(retried).isEqualTo(1)
        // The X stands in the card's top-right corner, beside the title's first line and clear of its two lines.
        val close = compose.onNodeWithTag("load-notice-dismiss").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("load-notice-title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(title.right).isAtMost(close.left)
        assertThat(close.top).isLessThan(title.top + title.height / 2)
        capture("100_record_fallback_row")
    }

    /**
     * The X tapped: the card is gone, the dock is the composer alone under the transcript, and nothing else moved —
     * the screen real estate Bennett's 2026-09-20 frame asked for back.
     */
    @Test
    fun recordFallbackDismissed() {
        var shown by mutableStateOf(true)
        compose.setContent { Screen(shown = shown, onRetry = {}, onDismiss = { shown = false }) }
        compose.waitForIdle()
        compose.onNodeWithTag("load-notice-dismiss").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("record-fallback").assertCountEquals(0)
        compose.onNodeWithText("Follow up (queues on your account)…").assertExists()
        capture("108_record_fallback_dismissed")
    }
}
