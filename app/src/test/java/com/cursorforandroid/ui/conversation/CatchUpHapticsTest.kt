package com.cursorforandroid.ui.conversation

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.constant
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the pull to catch up says through the finger, in the app's own [Haptic]s: the sidebar's threshold pair as the
 * pull crosses it and again if it drops back under (`PullRefreshHaptics`), a confirm as an armed pull is let go (and
 * never an un-arming for the release, nor a crossing as the indicator springs to the threshold and home), and one cue
 * per answer as it arrives, none for an answer already up when the screen comes back. Played through `Haptics`, so
 * the system's touch feedback switch silences all of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CatchUpHapticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val recorder = RecordingView(ApplicationProvider.getApplicationContext())
    private val status = MutableStateFlow<CatchUpStatus>(CatchUpStatus.Idle)
    private lateinit var pull: CatchUpPull

    /** What was felt, as the [Haptic]s that play those constants. */
    private fun felt(vararg haptics: Haptic) = haptics.map { it.constant() }

    /** The chat with the pull under it, felt on [recorder]. */
    private fun chat() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val density = LocalDensity.current
                pull = remember { with(density) { CatchUpPull(CatchUpPullThreshold.toPx()) } }
                val list = rememberLazyListState()
                val scroll = rememberTranscriptScroll(list, key = "chat")
                val reader = rememberReaderScroll(scroll, pull = pull, canCatchUp = { true }, onCatchUp = { status.value = CatchUpStatus.Checking })
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = list,
                        reverseLayout = true,
                        userScrollEnabled = false,
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("transcript").readerScrolling(reader),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(60, key = { it }) { Text("Row $it", Modifier.fillMaxWidth().height(56.dp)) }
                    }
                    CompositionLocalProvider(LocalView provides recorder) {
                        CatchUpIndicator(pull, status, onSettled = {}, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pullUp() {
        compose.onNodeWithTag("transcript").performTouchInput { swipeUp(startY = bottom - 4f, endY = top + 4f, durationMillis = 900) }
        compose.waitForIdle()
    }

    @Test
    fun `a real pull is felt arming at the threshold and confirms as it is let go, with nothing more as it springs and spins`() {
        chat()
        pullUp()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertThat(status.value).isEqualTo(CatchUpStatus.Checking)
        assertThat(recorder.played).containsExactlyElementsIn(felt(Haptic.ThresholdActivate, Haptic.Confirm)).inOrder()
    }

    @Test
    fun `dropping back under the threshold is felt disarming, and a release short of it is silent`() {
        chat()
        pull.stretch(pull.thresholdPx * 1.2f)
        compose.waitForIdle()
        pull.relax(pull.thresholdPx * 0.5f)
        compose.waitForIdle()
        assertThat(pull.release()).isFalse()
        compose.waitForIdle()
        assertThat(recorder.played).containsExactlyElementsIn(felt(Haptic.ThresholdActivate, Haptic.ThresholdDeactivate)).inOrder()

        pull.stretch(pull.thresholdPx * 2f)
        compose.waitForIdle()
        assertThat(pull.release()).isTrue()
        compose.waitForIdle()
        assertThat(recorder.played).containsExactlyElementsIn(
            felt(Haptic.ThresholdActivate, Haptic.ThresholdDeactivate, Haptic.ThresholdActivate, Haptic.Confirm),
        ).inOrder()
    }

    @Test
    fun `each answer plays once as it arrives, lightest for an answer and a reject for a failure, and the wait before it plays nothing`() {
        chat()
        status.value = CatchUpStatus.Waiting(untilMillis = 0L)
        compose.waitForIdle()
        status.value = CatchUpStatus.Checking
        compose.waitForIdle()
        assertThat(recorder.played).isEmpty()
        status.value = CatchUpStatus.Done(newMessages = 2, changed = true)
        compose.waitForIdle()
        status.value = CatchUpStatus.Idle
        compose.waitForIdle()
        status.value = CatchUpStatus.Checking
        compose.waitForIdle()
        status.value = CatchUpStatus.Failed("Cursor couldn't be reached. Check your connection.")
        compose.waitForIdle()
        assertThat(recorder.played).containsExactlyElementsIn(felt(Haptic.Subtle, Haptic.Reject)).inOrder()
    }

    @Test
    fun `an answer already up when the screen comes back plays nothing`() {
        status.value = CatchUpStatus.Done(newMessages = 1, changed = true)
        chat()
        assertThat(recorder.played).isEmpty()
    }

    @Test
    fun `with the system's touch feedback off a pull still catches up and nothing is felt, and turned back on the next is`() {
        recorder.isHapticFeedbackEnabled = false
        chat()
        pullUp()
        assertThat(status.value).isEqualTo(CatchUpStatus.Checking)
        assertThat(recorder.played).isEmpty()

        status.value = CatchUpStatus.Idle
        recorder.isHapticFeedbackEnabled = true
        compose.waitForIdle()
        pullUp()
        assertThat(status.value).isEqualTo(CatchUpStatus.Checking)
        assertThat(recorder.played.last()).isEqualTo(Haptic.Confirm.constant())
    }

    /** A view that keeps every haptic it is asked for, as the system's switch lets it. */
    private class RecordingView(context: Context) : View(context) {
        val played = mutableListOf<Int>()

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            played += feedbackConstant
            return true
        }
    }
}
