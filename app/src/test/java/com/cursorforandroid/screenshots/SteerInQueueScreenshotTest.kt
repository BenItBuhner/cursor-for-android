package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.FollowUpRepository
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.SteerPhase
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.composerDockPadding
import com.cursorforandroid.ui.conversation.QueueCardWords
import com.cursorforandroid.ui.conversation.QueueStack
import com.cursorforandroid.ui.conversation.QueuedFollowUpCard
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Steering a queued message into the turn under way, frame by frame, over a transcript and the composer: the card
 * waiting with its up arrow; its glyphs dimmed as being sent, like a held retry's, and "Steering…" with a ring
 * under the line while the account takes it; "Steered" with a check until the transcript shows it; then — the bubble in the transcript — the card fading and folding away, nothing jumping; and
 * the two ways a steer comes back to its card: the account refusing it, and no account to steer through. Then a deck
 * with its front card steering, and the light theme. Same device qualifiers as [QueueStripScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SteerInQueueScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val now = 1_800_000_000_000L

    private val transcript = listOf<TimelineItem>(
        UserMessage("u1", "Move the settings screen to the new design tokens and keep the goldens green."),
        AssistantMessage("a1", "Swapping the hard-coded colours in `SettingsScreen.kt` for the theme's tokens. The toggles and the section headers are done; the diagnostics rows are next, then I'll record the goldens again."),
    )
    private val first = QueuedFollowUp("q-1", "Also check the release build once the goldens pass", queuedAtMillis = 1_000L)
    private val second = QueuedFollowUp("q-2", "Then open a draft PR and tag me for review", queuedAtMillis = 2_000L)

    @Before
    fun pinClock() {
        AppClock.nowMillis = { now }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** The clock is stopped (a ring never idles): [millis] of it run, then the frame composed. */
    private fun advance(millis: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private var items by mutableStateOf(transcript)
    private var queue by mutableStateOf(listOf<QueuedFollowUp>())
    private var stacked by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun scene(mode: ThemeMode = ThemeMode.Dark) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    val colors = CursorTheme.colors
                    Column(Modifier.fillMaxSize().background(colors.canvas)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                items.forEach { TimelineItemView(it) }
                            }
                        }
                        Column(Modifier.fillMaxWidth().composerDockPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                            QueueStack(
                                keys = queue.map { it.id },
                                stacked = stacked,
                                onStackedChange = { stacked = it },
                                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                                animate = { true },
                                gapBelow = 4.dp,
                            ) { index, face ->
                                QueuedFollowUpCard(queue[index], index + 1, queue.size, emptyMap(), {}, {}, {}, flights = null, face = face, steers = true)
                            }
                            ComposerBox(
                                value = "",
                                onValueChange = {},
                                placeholder = "Follow up (sends when the turn ends)…",
                                onSend = {},
                                isRunning = true,
                                onStop = {},
                                modelLabel = "Claude Fable 5.1",
                                onModel = {},
                                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                            )
                        }
                    }
                }
            }
        }
        advance(1_000)
    }

    @Test
    fun steerLifecycle() {
        queue = listOf(first, second)
        scene()
        capture("920_steer_queue_waiting")

        queue = listOf(first.copy(isSending = true, steer = SteerPhase.STEERING), second)
        advance(600)
        compose.onNodeWithText(QueueCardWords.STEERING).assertExists()
        capture("921_steer_queue_steering")

        queue = listOf(first.copy(isSending = true, steer = SteerPhase.STEERED, steerFollowupId = "fu-1"), second)
        advance(600)
        compose.onNodeWithText(QueueCardWords.STEERED).assertExists()
        capture("922_steer_queue_steered")

        // The transcript files the steer: the bubble and the card's going are one frame.
        items = transcript + UserMessage("fu-1", first.text)
        queue = listOf(second)
        advance(64)
        capture("923_steer_queue_leaving")
        advance(96)
        capture("924_steer_queue_folding")
        advance(800)
        capture("925_steer_queue_left")
    }

    @Test
    fun steerRefused() {
        queue = listOf(first.copy(steerError = "Couldn't steer: the agent is not accepting follow-ups right now."), second)
        scene()
        capture("926_steer_queue_refused")
    }

    @Test
    fun steerWithoutAccount() {
        queue = listOf(first.copy(steerError = FollowUpRepository.STEER_NEEDS_EXTENDED), second)
        scene()
        capture("927_steer_queue_needs_extended")
    }

    @Test
    fun steerFromTheDeck() {
        queue = listOf(
            first.copy(isSending = true, steer = SteerPhase.STEERING),
            second,
            QueuedFollowUp("q-3", "And bump the version once it is green", queuedAtMillis = 3_000L),
            QueuedFollowUp("q-4", "Post the before and after screenshots in the thread", queuedAtMillis = 4_000L),
        )
        stacked = true
        scene()
        capture("928_steer_queue_deck_steering")
    }

    @Test
    fun steerLight() {
        queue = listOf(first.copy(isSending = true, steer = SteerPhase.STEERED, steerFollowupId = "fu-1"), second)
        scene(ThemeMode.Light)
        capture("929_steer_queue_steered_light")
    }
}
