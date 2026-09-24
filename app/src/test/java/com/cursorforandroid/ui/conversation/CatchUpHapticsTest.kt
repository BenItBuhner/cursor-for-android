package com.cursorforandroid.ui.conversation

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the pull to catch up says through the finger ([CatchUpHaptic]): a tick as the pull crosses the threshold and
 * again if it drops back under, a confirm as an armed pull is let go (and never an un-arming tick for the release),
 * and one light cue per answer as it arrives — none for an answer already up when the screen comes back. Played
 * through the view's own feedback with no flags, so the system's touch-feedback setting has the last word.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CatchUpHapticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val played = mutableListOf<CatchUpHaptic>()
    private var status by mutableStateOf<CatchUpStatus>(CatchUpStatus.Idle)
    private lateinit var pull: CatchUpPull

    private fun chat() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val density = LocalDensity.current
                pull = remember { with(density) { CatchUpPull(CatchUpPullThreshold.toPx(), CatchUpPullReveal.toPx()) } }
                val reveal = rememberCatchUpReveal(pull, status)
                val list = rememberLazyListState()
                val scroll = rememberTranscriptScroll(list, key = "chat")
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    LazyColumn(
                        state = list,
                        reverseLayout = true,
                        userScrollEnabled = false,
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("transcript")
                            .readerScrolling(scroll, pull = pull, canCatchUp = { true }, onCatchUp = { status = CatchUpStatus.Checking }),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(60, key = { it }) { Text("Row $it", Modifier.fillMaxWidth().height(56.dp)) }
                    }
                    CatchUpIndicator(pull, status, reveal, onDismiss = {}, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), haptics = { played += it })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a real pull ticks at the threshold and confirms as it is let go, with no un-arming tick for the release`() {
        chat()
        compose.onNodeWithTag("transcript").performTouchInput { swipeUp(startY = bottom - 4f, endY = top + 4f, durationMillis = 900) }
        compose.waitForIdle()
        assertThat(status).isEqualTo(CatchUpStatus.Checking)
        assertThat(played).containsExactly(CatchUpHaptic.Armed, CatchUpHaptic.Released).inOrder()
    }

    @Test
    fun `dropping back under the threshold ticks again, and a release short of it is silent`() {
        chat()
        pull.stretch(pull.thresholdPx * 1.2f)
        compose.waitForIdle()
        pull.relax(pull.thresholdPx * 0.5f)
        compose.waitForIdle()
        assertThat(pull.release()).isFalse()
        compose.waitForIdle()
        assertThat(played).containsExactly(CatchUpHaptic.Armed, CatchUpHaptic.Disarmed).inOrder()

        pull.stretch(pull.thresholdPx * 2f)
        compose.waitForIdle()
        assertThat(pull.release()).isTrue()
        compose.waitForIdle()
        assertThat(played).containsExactly(CatchUpHaptic.Armed, CatchUpHaptic.Disarmed, CatchUpHaptic.Armed, CatchUpHaptic.Released).inOrder()
    }

    @Test
    fun `each answer plays once as it arrives, a failure as a failure, and the wait before it plays nothing`() {
        chat()
        status = CatchUpStatus.Waiting(untilMillis = 0L)
        compose.waitForIdle()
        status = CatchUpStatus.Checking
        compose.waitForIdle()
        assertThat(played).isEmpty()
        status = CatchUpStatus.Done(newMessages = 2, changed = true)
        compose.waitForIdle()
        status = CatchUpStatus.Idle
        compose.waitForIdle()
        status = CatchUpStatus.Checking
        compose.waitForIdle()
        status = CatchUpStatus.Failed("Cursor couldn't be reached. Check your connection.")
        compose.waitForIdle()
        assertThat(played).containsExactly(CatchUpHaptic.Answered, CatchUpHaptic.Failed).inOrder()
    }

    @Test
    fun `an answer already up when the screen comes back plays nothing`() {
        status = CatchUpStatus.Done(newMessages = 1, changed = true)
        chat()
        assertThat(played).isEmpty()
    }

    @Test
    fun `the cues are the gesture-threshold pair from 34 with a clock tick before it, and CONFIRM and REJECT from 30`() {
        assertThat(CatchUpHaptic.Armed.feedback(sdk = 35)).isEqualTo(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        assertThat(CatchUpHaptic.Disarmed.feedback(sdk = 34)).isEqualTo(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
        assertThat(CatchUpHaptic.Armed.feedback(sdk = 33)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(CatchUpHaptic.Disarmed.feedback(sdk = 26)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(CatchUpHaptic.Released.feedback(sdk = 30)).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(CatchUpHaptic.Released.feedback(sdk = 29)).isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY)
        assertThat(CatchUpHaptic.Failed.feedback(sdk = 30)).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(CatchUpHaptic.Failed.feedback(sdk = 29)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(CatchUpHaptic.Answered.feedback(sdk = 35)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(CatchUpHaptic.Answered.feedback(sdk = 26)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
    }

    @Test
    fun `the cues go through the view's own feedback, without the flags that would override the system setting`() {
        lateinit var view: View
        lateinit var haptics: (CatchUpHaptic) -> Unit
        compose.setContent {
            view = LocalView.current
            haptics = rememberCatchUpHaptics()
        }
        compose.waitForIdle()
        // Robolectric records the flagless overload only: a call passing FLAG_IGNORE_GLOBAL_SETTING would not show here.
        compose.runOnIdle { haptics(CatchUpHaptic.Armed) }
        assertThat(shadowOf(view).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        compose.runOnIdle { haptics(CatchUpHaptic.Released) }
        assertThat(shadowOf(view).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.CONFIRM)
    }
}
