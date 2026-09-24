package com.cursorforandroid.ui.components

import android.content.ClipboardManager as PlatformClipboard
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.compose.launchRefused
import com.cursorforandroid.ui.conversation.ChatHapticCues
import com.cursorforandroid.ui.conversation.OutgoingStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app's haptics: which platform constant each moment plays on each Android version, that the system's switch
 * silences it, and the small decisions of when a drag, a pull, a back
 * gesture, a run or a launch is felt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class HapticsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `on Android 14 and later each moment plays its own named constant`() {
        val expected = mapOf(
            Haptic.LongPress to HapticFeedbackConstants.LONG_PRESS,
            Haptic.Confirm to HapticFeedbackConstants.CONFIRM,
            Haptic.Reject to HapticFeedbackConstants.REJECT,
            Haptic.DragStart to HapticFeedbackConstants.DRAG_START,
            Haptic.SlotTick to HapticFeedbackConstants.SEGMENT_TICK,
            Haptic.GestureEnd to HapticFeedbackConstants.GESTURE_END,
            Haptic.ThresholdActivate to HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
            Haptic.ThresholdDeactivate to HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE,
            Haptic.ToggleOn to HapticFeedbackConstants.TOGGLE_ON,
            Haptic.ToggleOff to HapticFeedbackConstants.TOGGLE_OFF,
            Haptic.Select to HapticFeedbackConstants.SEGMENT_TICK,
            Haptic.TextHandleMove to HapticFeedbackConstants.TEXT_HANDLE_MOVE,
            Haptic.Subtle to HapticFeedbackConstants.SEGMENT_TICK,
        )
        assertThat(expected.keys).containsExactlyElementsIn(Haptic.entries)
        for (sdk in listOf(34, 35, 36)) Haptic.entries.forEach { assertThat(it.constant(sdk)).isEqualTo(expected.getValue(it)) }
    }

    @Test
    fun `on Android 11 to 13 the gesture constants stand in for the ones 14 added`() {
        for (sdk in 30..33) {
            assertThat(Haptic.Confirm.constant(sdk)).isEqualTo(HapticFeedbackConstants.CONFIRM)
            assertThat(Haptic.Reject.constant(sdk)).isEqualTo(HapticFeedbackConstants.REJECT)
            assertThat(Haptic.DragStart.constant(sdk)).isEqualTo(HapticFeedbackConstants.GESTURE_START)
            assertThat(Haptic.GestureEnd.constant(sdk)).isEqualTo(HapticFeedbackConstants.GESTURE_END)
            assertThat(Haptic.SlotTick.constant(sdk)).isEqualTo(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            assertThat(Haptic.ThresholdActivate.constant(sdk)).isEqualTo(HapticFeedbackConstants.CONTEXT_CLICK)
            assertThat(Haptic.ThresholdDeactivate.constant(sdk)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
            assertThat(Haptic.ToggleOn.constant(sdk)).isEqualTo(HapticFeedbackConstants.CONTEXT_CLICK)
            assertThat(Haptic.ToggleOff.constant(sdk)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    @Test
    fun `on Android 8 each moment falls back to the nearest of the oldest constants`() {
        assertThat(Haptic.Confirm.constant(26)).isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY)
        assertThat(Haptic.Reject.constant(26)).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
        assertThat(Haptic.DragStart.constant(26)).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
        assertThat(Haptic.SlotTick.constant(26)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(Haptic.GestureEnd.constant(26)).isEqualTo(HapticFeedbackConstants.KEYBOARD_TAP)
        assertThat(Haptic.TextHandleMove.constant(26)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(Haptic.GestureEnd.constant(27)).isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        assertThat(Haptic.SlotTick.constant(27)).isEqualTo(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
    }

    @Test
    fun `no version is ever handed a constant it does not know`() {
        for (sdk in 26..36) Haptic.entries.forEach { haptic ->
            val constant = haptic.constant(sdk)
            assertThat(IntroducedIn.getValue(constant)).isAtMost(sdk)
        }
    }

    @Test
    fun `the system's switch has the last word, and is never asked to be ignored`() {
        val view = RecordingView(ApplicationProvider.getApplicationContext())
        val haptics = Haptics(view)
        Haptic.entries.forEach { haptics.perform(it) }
        assertThat(view.played.map { it.first }).isEqualTo(Haptic.entries.map { it.constant() })
        assertThat(view.played.all { it.second and HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING == 0 }).isTrue()

        view.played.clear()
        view.isHapticFeedbackEnabled = false
        Haptic.entries.forEach { assertThat(haptics.perform(it)).isFalse() }
        assertThat(view.played).isEmpty()
    }

    @Test
    fun `Compose's own haptics and every copy are played through the app's constants`() {
        lateinit var view: View
        lateinit var feedback: HapticFeedback
        lateinit var clipboard: ClipboardManager
        compose.setContent {
            ProvideHaptics {
                view = LocalView.current
                feedback = LocalHapticFeedback.current
                clipboard = LocalClipboardManager.current
            }
        }
        compose.waitForIdle()

        feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        assertThat(shadowOf(view).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
        clipboard.setText(AnnotatedString("cd ~/work"))
        assertThat(shadowOf(view).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(platformClip()).isEqualTo("cd ~/work")
        assertThat(clipboard.getText()?.text).isEqualTo("cd ~/work")
    }

    @Test
    fun `a nested provider does not wrap the clipboard twice`() {
        lateinit var outer: ClipboardManager
        lateinit var inner: ClipboardManager
        compose.setContent {
            ProvideHaptics {
                outer = LocalClipboardManager.current
                ProvideHaptics { inner = LocalClipboardManager.current }
            }
        }
        compose.waitForIdle()
        assertThat(inner).isSameInstanceAs(outer)
    }

    @Test
    fun `a sheet is felt once as a swipe lands it on the other side, and never springing back`() {
        val landing = SheetLanding()
        fun SheetLanding.settle(vararg frames: Float): List<Haptic> = frames.toList().mapNotNull(::at)

        // A short flick: the finger takes the sheet a tenth of the way, the fling the rest.
        landing.dragged(0f)
        landing.dragged(0.05f)
        landing.released(wasOpen = false, open = true)
        assertThat(landing.settle(0.1f, 0.5f, 0.9f, 0.995f, 1f)).containsExactly(Haptic.GestureEnd)

        // Closing again, the same: felt as it lands shut, not before.
        landing.dragged(1f)
        landing.released(wasOpen = true, open = false)
        assertThat(landing.settle(0.9f, 0.4f, 0.02f, 0f)).containsExactly(Haptic.GestureEnd)

        // Let go short of committing, or taken past half-way and back: it springs back without a sound.
        landing.dragged(0f)
        landing.dragged(0.3f)
        landing.released(wasOpen = false, open = false)
        assertThat(landing.settle(0.2f, 0f)).isEmpty()
        landing.dragged(0f)
        landing.dragged(0.7f)
        landing.dragged(0.4f)
        landing.released(wasOpen = false, open = false)
        assertThat(landing.settle(0.2f, 0f)).isEmpty()

        // A slow drag held fully open lands as the finger lets go.
        landing.dragged(0f)
        landing.dragged(1f)
        landing.released(wasOpen = false, open = true)
        assertThat(landing.settle(1f, 1f)).containsExactly(Haptic.GestureEnd)
    }

    @Test
    fun `a sheet caught on its way lands once, and a slide or a jump in between leaves nothing behind`() {
        val landing = SheetLanding()
        // Flicked open, caught half-way and sent on open: one landing, not none and not two.
        landing.dragged(0f)
        landing.released(wasOpen = false, open = true)
        assertThat(listOf(0.3f, 0.5f).mapNotNull(landing::at)).isEmpty()
        landing.dragged(0.5f)
        landing.released(wasOpen = true, open = true)
        assertThat(listOf(0.8f, 1f).mapNotNull(landing::at)).containsExactly(Haptic.GestureEnd)

        // Flicked shut, and a shortcut jumps it open before it lands: the next drag starts from rest, and a drag that
        // springs back to where the shortcut left it is silent.
        landing.dragged(1f)
        landing.released(wasOpen = true, open = false)
        assertThat(landing.at(0.6f)).isNull()
        landing.dragged(1f)
        landing.released(wasOpen = true, open = true)
        assertThat(listOf(0.9f, 1f).mapNotNull(landing::at)).isEmpty()
    }

    @Test
    fun `a pull is felt arming and disarming, and never while it springs back or refreshes`() {
        val pull = PullThreshold()
        assertThat(listOf(0.2f, 0.6f, 0.95f).map(pull::pulled)).containsExactly(null, null, null)
        assertThat(pull.pulled(1f)).isEqualTo(Haptic.ThresholdActivate)
        assertThat(pull.pulled(1.3f)).isNull()
        assertThat(pull.pulled(0.9f)).isEqualTo(Haptic.ThresholdDeactivate)
        assertThat(pull.pulled(1.1f)).isEqualTo(Haptic.ThresholdActivate)
        // Let go past the threshold: the refresh holds the indicator, then it hides; the next pull starts afresh.
        pull.reset()
        assertThat(pull.armed).isFalse()
        // Sprung to the threshold, the indicator is read resting there before the refresh holds it, and again once the
        // refresh is over before it hides: where it stands, not a crossing.
        assertThat(pull.pulled(1f)).isNull()
        assertThat(pull.armed).isTrue()
        pull.reset()
        assertThat(pull.pulled(1f)).isNull()
        pull.reset()
        assertThat(pull.pulled(0f)).isNull()
        assertThat(pull.pulled(0.1f)).isNull()
        assertThat(pull.pulled(1.05f)).isEqualTo(Haptic.ThresholdActivate)
        // A finger read past it the moment it takes the pull has still crossed it.
        pull.reset()
        assertThat(pull.pulled(1.2f)).isEqualTo(Haptic.ThresholdActivate)
    }

    @Test
    fun `a predictive back is felt as it commits, not when it is cancelled or never scrubbed`() = runBlocking<Unit> {
        val view = RecordingView(ApplicationProvider.getApplicationContext())
        val haptics = Haptics(view)
        val gestureEnd = HapticFeedbackConstants.GESTURE_END

        flowOf(backEvent(0.2f), backEvent(0.8f)).feltOnCommit(haptics).toList()
        assertThat(view.played.map { it.first }).containsExactly(gestureEnd)

        view.played.clear()
        emptyFlow<BackEventCompat>().feltOnCommit(haptics).toList()
        assertThat(view.played).isEmpty()

        // Cancelled mid-scrub: the collector stops before the flow completes.
        val endless = flow { while (true) emit(backEvent(0.5f)) }
        endless.feltOnCommit(haptics).take(2).toList()
        assertThat(view.played).isEmpty()
    }

    @Test
    fun `the stop confirmation is felt when the interruption goes through, either way`() {
        val view = RecordingView(ApplicationProvider.getApplicationContext())
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        val setting = mutableStateOf<Boolean?>(true)
        val confirmation = RunStopConfirmation(setting, prefs, CoroutineScope(Dispatchers.Unconfined), Haptics(view))
        var stopped = 0

        confirmation.ask(RunInterruption.Stop, "agent-1") { stopped++ }
        assertThat(view.played).isEmpty()
        confirmation.keepRunning()
        assertThat(view.played).isEmpty()

        confirmation.ask(RunInterruption.Stop, "agent-1") { stopped++ }
        confirmation.accept()
        assertThat(stopped).isEqualTo(1)
        assertThat(view.played.map { it.first }).containsExactly(HapticFeedbackConstants.CONFIRM)

        setting.value = false
        confirmation.ask(RunInterruption.SendNow, "agent-1") { stopped++ }
        assertThat(stopped).isEqualTo(2)
        assertThat(view.played.map { it.first }).containsExactly(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.CONFIRM)
    }

    @Test
    fun `a run ending in the open chat is felt by how it ended`() {
        assertThat(ChatHapticCues.runEnded(RunStatus.RUNNING, RunStatus.FINISHED)).isEqualTo(Haptic.Subtle)
        assertThat(ChatHapticCues.runEnded(RunStatus.CREATING, RunStatus.ERROR)).isEqualTo(Haptic.Reject)
        assertThat(ChatHapticCues.runEnded(RunStatus.RUNNING, RunStatus.EXPIRED)).isEqualTo(Haptic.Reject)
        // A stop the reader asked for was felt at the tap.
        assertThat(ChatHapticCues.runEnded(RunStatus.RUNNING, RunStatus.CANCELLED)).isNull()
        // A chat opened on a run already over, and a run still going, are nothing new.
        assertThat(ChatHapticCues.runEnded(null, RunStatus.FINISHED)).isNull()
        assertThat(ChatHapticCues.runEnded(RunStatus.FINISHED, RunStatus.FINISHED)).isNull()
        assertThat(ChatHapticCues.runEnded(RunStatus.CREATING, RunStatus.RUNNING)).isNull()
        assertThat(ChatHapticCues.runEnded(RunStatus.RUNNING, null)).isNull()
    }

    @Test
    fun `a message is felt failing once, and a launch once it is refused`() {
        val sending = mapOf("a" to OutgoingStatus.Sending)
        val failed = mapOf("a" to OutgoingStatus.Failed("Network is unreachable"))
        assertThat(ChatHapticCues.sendFailed(sending, failed)).isTrue()
        assertThat(ChatHapticCues.sendFailed(emptyMap(), failed)).isTrue()
        assertThat(ChatHapticCues.sendFailed(failed, failed + ("b" to OutgoingStatus.Sending))).isFalse()
        assertThat(ChatHapticCues.sendFailed(failed, emptyMap())).isFalse()

        assertThat(launchRefused(wasLaunching = true, launching = false, error = "No machine available")).isTrue()
        assertThat(launchRefused(wasLaunching = true, launching = false, error = null)).isFalse()
        assertThat(launchRefused(wasLaunching = false, launching = false, error = "Pick a repository")).isFalse()
        assertThat(launchRefused(wasLaunching = false, launching = true, error = null)).isFalse()
    }

    private fun platformClip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(PlatformClipboard::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun backEvent(progress: Float) = BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

    /** A view that keeps every haptic it is asked for, with its flags. */
    private class RecordingView(context: Context) : View(context) {
        val played = mutableListOf<Pair<Int, Int>>()

        init {
            isHapticFeedbackEnabled = true
        }

        override fun performHapticFeedback(feedbackConstant: Int): Boolean = performHapticFeedback(feedbackConstant, 0)

        override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
            if (!isHapticFeedbackEnabled && flags and HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING == 0) return false
            played += feedbackConstant to flags
            return true
        }
    }

    private companion object {
        /** The API level each constant [Haptic.constant] may return was added in. */
        val IntroducedIn = mapOf(
            HapticFeedbackConstants.LONG_PRESS to 3,
            HapticFeedbackConstants.VIRTUAL_KEY to 5,
            HapticFeedbackConstants.KEYBOARD_TAP to 8,
            HapticFeedbackConstants.CLOCK_TICK to 21,
            HapticFeedbackConstants.CONTEXT_CLICK to 23,
            HapticFeedbackConstants.TEXT_HANDLE_MOVE to 27,
            HapticFeedbackConstants.VIRTUAL_KEY_RELEASE to 27,
            HapticFeedbackConstants.CONFIRM to 30,
            HapticFeedbackConstants.REJECT to 30,
            HapticFeedbackConstants.GESTURE_START to 30,
            HapticFeedbackConstants.GESTURE_END to 30,
            HapticFeedbackConstants.DRAG_START to 34,
            HapticFeedbackConstants.SEGMENT_TICK to 34,
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE to 34,
            HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE to 34,
            HapticFeedbackConstants.TOGGLE_ON to 34,
            HapticFeedbackConstants.TOGGLE_OFF to 34,
        )
    }
}
