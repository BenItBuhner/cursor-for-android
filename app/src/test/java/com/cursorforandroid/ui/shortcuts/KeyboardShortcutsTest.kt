package com.cursorforandroid.ui.shortcuts

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The reader: which keys it takes (the listed chords from a hardware keyboard, with their key-ups, and Esc), which it
 * leaves to the focused view (the on-screen keyboard's, a text field's own Ctrl chords, a chord the shell declines,
 * and Esc, Ctrl+N and Ctrl+K while an open popover claims them), and the Ctrl hold that numbers the sidebar's rows
 * after 500 ms. Read at the activity, and with a field focused before the IME: a key read there is read once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KeyboardShortcutsTest {

    private val scope = TestScope()
    private val handler = RecordingHandler()
    private val keys = KeyboardShortcuts(scope).also { it.handler = handler }

    private class RecordingHandler : ShortcutHandler {
        val actions = mutableListOf<ShortcutAction>()
        val releases = mutableListOf<Boolean>()
        var escapes = 0
        var takes = true

        override fun onShortcut(action: ShortcutAction): Boolean {
            actions += action
            return takes
        }

        override fun onEscape(): Boolean {
            escapes++
            return true
        }

        override fun onCtrlReleased(commit: Boolean) {
            releases += commit
        }
    }

    /** Each key its own moment, as the platform stamps them. */
    private var eventTime = 0L

    private fun key(action: Int, code: Int, ctrl: Boolean = false, shift: Boolean = false, repeat: Int = 0, soft: Boolean = false, right: Boolean = false): KeyEvent {
        var meta = 0
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or (if (right) KeyEvent.META_CTRL_RIGHT_ON else KeyEvent.META_CTRL_LEFT_ON)
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val deviceId = if (soft) KeyCharacterMap.VIRTUAL_KEYBOARD else 7
        val flags = if (soft) KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE else 0
        val at = ++eventTime
        return KeyEvent(at, at, action, code, repeat, meta, deviceId, 0, flags, InputDevice.SOURCE_KEYBOARD)
    }

    /**
     * [event] as the platform hands it on with a field focused: the pass before the IME, then — when that did not take
     * it and the IME does not answer it itself ([imeAnswers]) — the activity. Whether the app took it, before the IME
     * or after.
     */
    private fun throughIme(event: KeyEvent, imeAnswers: Boolean = false): Boolean =
        keys.onKeyEventPreIme(event) || (!imeAnswers && keys.onKeyEvent(event))

    private fun dispatch(event: KeyEvent): Boolean = keys.onKeyEvent(event)

    /** Presses [code] with Ctrl (and [shift]) held, down and up: whether each was taken. */
    private fun chord(code: Int, shift: Boolean = false, soft: Boolean = false): Pair<Boolean, Boolean> {
        val down = dispatch(key(KeyEvent.ACTION_DOWN, code, ctrl = true, shift = shift, soft = soft))
        val up = dispatch(key(KeyEvent.ACTION_UP, code, ctrl = true, shift = shift, soft = soft))
        return down to up
    }

    private fun escape(): Pair<Boolean, Boolean> =
        dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)) to dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))

    private fun ctrlDown(right: Boolean = false) =
        keys.onKeyEvent(key(KeyEvent.ACTION_DOWN, if (right) KeyEvent.KEYCODE_CTRL_RIGHT else KeyEvent.KEYCODE_CTRL_LEFT, ctrl = true, right = right))

    private fun ctrlUp(stillHeld: Boolean = false, right: Boolean = false) =
        keys.onKeyEvent(key(KeyEvent.ACTION_UP, if (right) KeyEvent.KEYCODE_CTRL_RIGHT else KeyEvent.KEYCODE_CTRL_LEFT, ctrl = stillHeld, right = !right))

    private fun elapse(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    @Test
    fun `a listed chord from a hardware keyboard is the app's, its key-up too`() {
        ctrlDown()
        assertThat(chord(KeyEvent.KEYCODE_F)).isEqualTo(true to true)
        assertThat(chord(KeyEvent.KEYCODE_B, shift = true)).isEqualTo(true to true)
        assertThat(chord(KeyEvent.KEYCODE_4)).isEqualTo(true to true)
        assertThat(handler.actions).containsExactly(ShortcutAction.Search, ShortcutAction.TogglePanel, ShortcutAction.OpenRailItem(3)).inOrder()
    }

    @Test
    fun `the on-screen keyboard's keys are never read`() {
        assertThat(chord(KeyEvent.KEYCODE_F, soft = true)).isEqualTo(false to false)
        assertThat(chord(KeyEvent.KEYCODE_N, soft = true)).isEqualTo(false to false)
        assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, soft = true))).isFalse()
        assertThat(handler.actions).isEmpty()
        assertThat(handler.escapes).isEqualTo(0)
    }

    @Test
    fun `an open popover keeps Esc, Ctrl+N and Ctrl+K, down and up, until it shuts`() {
        ctrlDown()
        val release = keys.popoverOpened()
        assertThat(escape()).isEqualTo(false to false)
        assertThat(chord(KeyEvent.KEYCODE_N)).isEqualTo(false to false)
        assertThat(chord(KeyEvent.KEYCODE_N, shift = true)).isEqualTo(false to false)
        assertThat(chord(KeyEvent.KEYCODE_K)).isEqualTo(false to false)
        assertThat(handler.actions).isEmpty()
        assertThat(handler.escapes).isEqualTo(0)

        // The chords it does not answer stay the app's, Ctrl+Tab among them.
        assertThat(chord(KeyEvent.KEYCODE_TAB)).isEqualTo(true to true)
        assertThat(chord(KeyEvent.KEYCODE_F)).isEqualTo(true to true)
        assertThat(handler.actions).containsExactly(ShortcutAction.SwitchNext, ShortcutAction.Search).inOrder()

        handler.actions.clear()
        release()
        assertThat(chord(KeyEvent.KEYCODE_N)).isEqualTo(true to true)
        assertThat(chord(KeyEvent.KEYCODE_K)).isEqualTo(true to true)
        assertThat(escape()).isEqualTo(true to true)
        assertThat(handler.actions).containsExactly(ShortcutAction.NewChat, ShortcutAction.Search).inOrder()
        assertThat(handler.escapes).isEqualTo(1)
    }

    @Test
    fun `a popover shut twice is let go of once, and another still open keeps its keys`() {
        ctrlDown()
        val first = keys.popoverOpened()
        val second = keys.popoverOpened()
        first()
        first()
        assertThat(chord(KeyEvent.KEYCODE_N)).isEqualTo(false to false)
        second()
        assertThat(chord(KeyEvent.KEYCODE_N)).isEqualTo(true to true)
        assertThat(handler.actions).containsExactly(ShortcutAction.NewChat)
    }

    @Test
    fun `a chord taken on its way down is taken on its way up, though a popover opened in between`() {
        ctrlDown()
        assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N, ctrl = true))).isTrue()
        keys.popoverOpened()
        assertThat(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_N, ctrl = true))).isTrue()
    }

    @Test
    fun `Tab and Shift+Tab without Ctrl are never read, for a popover or the composer's modes to have`() {
        listOf(false, true).forEach { shift ->
            assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, shift = shift))).isFalse()
            assertThat(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, shift = shift))).isFalse()
        }
        assertThat(handler.actions).isEmpty()
    }

    @Test
    fun `a text field's own Ctrl chords go on to the field, down and up`() {
        ctrlDown()
        listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_Z).forEach { code ->
            assertThat(chord(code)).isEqualTo(false to false)
            assertThat(chord(code, shift = true)).isEqualTo(false to false)
        }
        assertThat(handler.actions).isEmpty()
    }

    @Test
    fun `a chord the shell declines goes on to the view, as if never read`() {
        handler.takes = false
        assertThat(chord(KeyEvent.KEYCODE_R)).isEqualTo(false to false)
        assertThat(handler.actions).containsExactly(ShortcutAction.CatchUp)
    }

    @Test
    fun `Esc is the app's, so the platform never turns it into Back, and with Ctrl it is not`() {
        assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))).isTrue()
        assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, repeat = 1))).isTrue()
        assertThat(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))).isTrue()
        assertThat(handler.escapes).isEqualTo(1)

        assertThat(chord(KeyEvent.KEYCODE_ESCAPE)).isEqualTo(false to false)
        assertThat(handler.escapes).isEqualTo(1)
    }

    @Test
    fun `held down, Ctrl+Tab steps on each repeat while Ctrl+B and Ctrl+N act once`() {
        ctrlDown()
        repeat(3) { n -> assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, ctrl = true, repeat = n))).isTrue() }
        dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, ctrl = true))
        repeat(3) { n -> assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, ctrl = true, repeat = n))).isTrue() }
        assertThat(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, ctrl = true))).isTrue()
        repeat(3) { n -> assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N, ctrl = true, repeat = n))).isTrue() }
        assertThat(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_N, ctrl = true))).isTrue()
        assertThat(handler.actions).containsExactly(
            ShortcutAction.SwitchNext, ShortcutAction.SwitchNext, ShortcutAction.SwitchNext, ShortcutAction.ToggleSidebar, ShortcutAction.NewChat,
        ).inOrder()
    }

    @Test
    fun `Ctrl held on its own for 500 ms numbers the rows, and letting go puts the numbers away`() {
        assertThat(ctrlDown()).isFalse()
        elapse(KeyboardShortcuts.NUMBERS_AFTER_MILLIS - 1)
        assertThat(keys.showNumbers).isFalse()
        elapse(1)
        assertThat(keys.showNumbers).isTrue()

        assertThat(ctrlUp()).isFalse()
        assertThat(keys.showNumbers).isFalse()
        assertThat(handler.releases).containsExactly(true)
    }

    @Test
    fun `a chord pressed before the 500 ms was never a hold, and shows no numbers`() {
        ctrlDown()
        elapse(200)
        chord(KeyEvent.KEYCODE_2)
        elapse(2_000)
        assertThat(keys.showNumbers).isFalse()
        assertThat(handler.actions).containsExactly(ShortcutAction.OpenRailItem(1))
    }

    @Test
    fun `with the numbers up, a digit opens its row and the numbers stay until Ctrl is let go`() {
        ctrlDown()
        elapse(KeyboardShortcuts.NUMBERS_AFTER_MILLIS)
        assertThat(chord(KeyEvent.KEYCODE_3)).isEqualTo(true to true)
        assertThat(keys.showNumbers).isTrue()
        assertThat(handler.actions).containsExactly(ShortcutAction.OpenRailItem(2))
        ctrlUp()
        assertThat(keys.showNumbers).isFalse()
    }

    @Test
    fun `with both Ctrl keys down, letting go of one is not letting go`() {
        ctrlDown()
        ctrlDown(right = true)
        elapse(KeyboardShortcuts.NUMBERS_AFTER_MILLIS)
        ctrlUp(stillHeld = true)
        assertThat(keys.showNumbers).isTrue()
        assertThat(handler.releases).isEmpty()
        ctrlUp(right = true)
        assertThat(keys.showNumbers).isFalse()
        assertThat(handler.releases).containsExactly(true)
    }

    @Test
    fun `the window losing focus with Ctrl held puts the numbers away and the switcher with them`() {
        ctrlDown()
        elapse(KeyboardShortcuts.NUMBERS_AFTER_MILLIS)
        keys.onFocusLost()
        assertThat(keys.showNumbers).isFalse()
        assertThat(handler.releases).containsExactly(false)
        // The Ctrl that comes up afterwards was already let go of.
        ctrlUp()
        assertThat(handler.releases).containsExactly(false)
    }

    @Test
    fun `a key held across the focus loss is not taken on its way up`() {
        ctrlDown()
        dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, ctrl = true))
        keys.onFocusLost()
        assertThat(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, ctrl = true))).isFalse()
    }

    @Test
    fun `Ctrl pressed where the activity did not hear it is still let go of`() {
        // Ctrl went down while another window had the focus: the first the activity hears of it is Ctrl+Tab.
        assertThat(chord(KeyEvent.KEYCODE_TAB)).isEqualTo(true to true)
        ctrlUp()
        assertThat(handler.releases).containsExactly(true)
        elapse(2_000)
        assertThat(keys.showNumbers).isFalse()
    }

    @Test
    fun `with a field focused, a listed chord is taken before the IME is given it, down and up`() {
        assertThat(keys.onKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl = true))).isFalse()
        assertThat(keys.onKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, ctrl = true))).isTrue()
        assertThat(keys.onKeyEventPreIme(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_R, ctrl = true))).isTrue()
        assertThat(keys.onKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, ctrl = true, shift = true))).isTrue()
        assertThat(keys.onKeyEventPreIme(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_R, ctrl = true, shift = true))).isTrue()
        assertThat(handler.actions).containsExactly(ShortcutAction.CatchUp, ShortcutAction.ReloadTranscript).inOrder()
    }

    @Test
    fun `a keyboard app that answers every Ctrl chord itself still never hears the app's`() {
        throughIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl = true), imeAnswers = true)
        listOf(KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_1).forEach { code ->
            assertThat(throughIme(key(KeyEvent.ACTION_DOWN, code, ctrl = true), imeAnswers = true)).isTrue()
            assertThat(throughIme(key(KeyEvent.ACTION_UP, code, ctrl = true), imeAnswers = true)).isTrue()
        }
        assertThat(handler.actions).containsExactly(ShortcutAction.CatchUp, ShortcutAction.SwitchNext, ShortcutAction.ToggleSidebar, ShortcutAction.OpenRailItem(0)).inOrder()
    }

    @Test
    fun `a key read before the IME and not taken is not read a second time at the activity`() {
        handler.takes = false
        val declined = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, ctrl = true)
        assertThat(keys.onKeyEventPreIme(declined)).isFalse()
        assertThat(keys.onKeyEvent(declined)).isFalse()
        assertThat(handler.actions).containsExactly(ShortcutAction.CatchUp)

        // A field's own chord goes on to the IME and the field, never the app's before the IME or after.
        val selectAll = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, ctrl = true)
        assertThat(throughIme(selectAll)).isFalse()
        assertThat(handler.actions).containsExactly(ShortcutAction.CatchUp)
    }

    @Test
    fun `after a key the IME answered itself, the next the activity hears is read afresh`() {
        assertThat(throughIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, ctrl = true), imeAnswers = true)).isFalse()
        // The focus left the fields: the next chord comes by the activity alone.
        assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, ctrl = true))).isTrue()
        assertThat(handler.actions).containsExactly(ShortcutAction.Search)
    }

    @Test
    fun `Esc goes on to the IME, which cancels a composition with it, and is read at the activity`() {
        val down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)
        assertThat(keys.onKeyEventPreIme(down)).isFalse()
        assertThat(handler.escapes).isEqualTo(0)
        assertThat(keys.onKeyEvent(down)).isTrue()
        val up = key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)
        assertThat(keys.onKeyEventPreIme(up)).isFalse()
        assertThat(keys.onKeyEvent(up)).isTrue()
        assertThat(handler.escapes).isEqualTo(1)
    }

    @Test
    fun `the on-screen keyboard's keys are not read before the IME either`() {
        assertThat(keys.onKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, ctrl = true, soft = true))).isFalse()
        assertThat(keys.onKeyEventPreIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, ctrl = true, soft = true))).isFalse()
        assertThat(handler.actions).isEmpty()
    }

    @Test
    fun `Ctrl held and let go with a field focused numbers the rows and is let go of once`() {
        throughIme(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl = true))
        elapse(KeyboardShortcuts.NUMBERS_AFTER_MILLIS)
        assertThat(keys.showNumbers).isTrue()
        throughIme(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT))
        assertThat(keys.showNumbers).isFalse()
        assertThat(handler.releases).containsExactly(true)
    }

    @Test
    fun `without a shell to answer, nothing is taken`() {
        keys.handler = null
        assertThat(chord(KeyEvent.KEYCODE_F)).isEqualTo(false to false)
        assertThat(chord(KeyEvent.KEYCODE_N)).isEqualTo(false to false)
        assertThat(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))).isFalse()
    }
}
