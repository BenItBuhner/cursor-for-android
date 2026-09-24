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
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.composerDockPadding
import com.cursorforandroid.ui.conversation.CATCH_UP_TEST_TAG
import com.cursorforandroid.ui.conversation.CatchUpIndicator
import com.cursorforandroid.ui.conversation.CatchUpPull
import com.cursorforandroid.ui.conversation.CatchUpPullReveal
import com.cursorforandroid.ui.conversation.CatchUpPullThreshold
import com.cursorforandroid.ui.conversation.CatchUpStatus
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.rememberCatchUpReveal
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
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
 * The pull to catch up at the bottom of a transcript, over the follow-up composer, one frame per state of its tab:
 * partway out from under the composer's top edge with the finger ("Pull to catch up"), all the way out past the
 * threshold ("Release to catch up"), then let go and kept out for the answer — "Catching up…", the server's pause
 * being waited out, "1 new" with the turn started elsewhere on screen, "Up to date", and a failure in the words the
 * app has for it. The transcript is lifted clear of the tab and the tab sits at the composer's width, as the screen
 * does both (see ConversationScreen).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h520dp-night-420dpi")
class CatchUpScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val now = 1_800_000_000_000L

    private val shown = listOf<TimelineItem>(
        UserMessage("u1", "Scan the order book for markets that closed in the last hour and flag any fee mismatches."),
        AssistantMessage("a1", "Scanned 42 markets that closed in the last hour. Two fee mismatches: **market 187** charged 2.1% against a 2.0% table, and **market 193** charged the maker fee to the taker. Both are in `reports/fees.md`."),
    )
    private val elsewhere = UserMessage("u2", "From the desktop: check the fee table for market 200 too, then report back.")

    @Before
    fun pinClock() {
        AppClock.nowMillis = { now }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    /** The clock is stopped (the spinner never idles): what was just set is taken up, then composed in a frame. */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun catchUpIndicatorStates() {
        var items by mutableStateOf(shown)
        var status by mutableStateOf<CatchUpStatus>(CatchUpStatus.Idle)
        val pull = with(compose.density) { CatchUpPull(CatchUpPullThreshold.toPx(), CatchUpPullReveal.toPx()) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    val reveal = rememberCatchUpReveal(pull, status)
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                            Column(
                                Modifier.fillMaxSize().graphicsLayer { translationY = -reveal.value }.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                items.forEach { TimelineItemView(it) }
                            }
                            CatchUpIndicator(
                                pull,
                                status,
                                reveal,
                                onDismiss = {},
                                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = CursorDimens.composerGutter).widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth(),
                            )
                        }
                        Column(Modifier.fillMaxWidth().composerDockPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                            ComposerBox(
                                value = "",
                                onValueChange = {},
                                placeholder = "Follow up…",
                                onSend = {},
                                modelLabel = "Claude Fable 5.1",
                                onModel = {},
                                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        pull.stretch(pull.thresholdPx * 0.3f)
        frame()
        compose.onNodeWithText("Pull to catch up").assertExists()
        capture("440_catch_up_pulling")

        pull.stretch(pull.thresholdPx * 0.9f)
        frame()
        compose.onNodeWithText("Release to catch up").assertExists()
        capture("441_catch_up_armed")

        pull.release()
        status = CatchUpStatus.Checking
        frame()
        compose.onNodeWithText("Catching up…").assertExists()
        capture("442_catch_up_checking")

        status = CatchUpStatus.Waiting(now + 7_000L)
        frame()
        compose.onNodeWithText("Cursor asked for a pause · catching up in 7 s").assertExists()
        capture("443_catch_up_waiting")

        items = shown + elsewhere
        status = CatchUpStatus.Done(newMessages = 1, changed = true)
        frame()
        compose.onNodeWithText("1 new").assertExists()
        capture("444_catch_up_new")

        items = shown
        status = CatchUpStatus.Done(newMessages = 0, changed = false)
        frame()
        compose.onNodeWithText("Up to date").assertExists()
        capture("445_catch_up_up_to_date")

        status = CatchUpStatus.Failed("Cursor couldn't be reached. Check your connection.")
        frame()
        compose.onNodeWithTag(CATCH_UP_TEST_TAG).assertExists()
        compose.onNodeWithText("Cursor couldn't be reached. Check your connection.").assertExists()
        capture("446_catch_up_failed")
    }
}
