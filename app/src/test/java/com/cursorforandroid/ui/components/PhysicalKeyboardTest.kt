package com.cursorforandroid.ui.components

import android.content.res.Configuration
import androidx.compose.ui.input.key.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import android.view.KeyEvent as NativeKeyEvent

/**
 * Which newlines an IME types are taken for a physical Enter, and where a physical Enter goes before the IME is handed
 * it: see [PhysicalKeyboard] and [HardwareEnter.pressBeforeIme].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhysicalKeyboardTest {

    private val attached = Configuration().withKeyboard(attached = true)
    private val none = Configuration().withKeyboard(attached = false)

    @Before
    fun setUp() = PhysicalKeyboard.forget()

    @After
    fun tearDown() = PhysicalKeyboard.forget()

    private fun down(keyCode: Int = NativeKeyEvent.KEYCODE_ENTER, shift: Boolean = false, repeat: Int = 0): KeyEvent =
        keyEvent(keyCode, NativeKeyEvent.ACTION_DOWN, shift = shift, repeat = repeat)

    private fun up(keyCode: Int = NativeKeyEvent.KEYCODE_ENTER): KeyEvent = keyEvent(keyCode, NativeKeyEvent.ACTION_UP)

    private fun beforeIme(event: KeyEvent, composing: Boolean = false, canSend: Boolean = true): HardwareEnter.Press {
        PhysicalKeyboard.heard(event, beforeIme = true)
        return HardwareEnter.pressBeforeIme(event, canSend, composing)
    }

    private fun advance(millis: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(millis))

    @Test
    fun `with no key seen, a lone newline is an Enter only while the configuration has a keyboard attached and exposed`() {
        assertThat(PhysicalKeyboard.typedEnter(attached, alone = true)).isTrue()
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isFalse()
        val hidden = Configuration(attached).apply { hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES }
        assertThat(PhysicalKeyboard.typedEnter(hidden, alone = true)).isFalse()
        val keyless = Configuration(attached).apply { keyboard = Configuration.KEYBOARD_NOKEYS }
        assertThat(PhysicalKeyboard.typedEnter(keyless, alone = true)).isFalse()
        // Text before the newline makes it a paste, not a key.
        assertThat(PhysicalKeyboard.typedEnter(attached, alone = false)).isFalse()
    }

    @Test
    fun `a physical key heard after the IME stands in for the configuration, for a while`() {
        PhysicalKeyboard.heard(down(NativeKeyEvent.KEYCODE_A), beforeIme = false)
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isTrue()
        advance(PhysicalKeyboard.RecentKeyMillis)
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isTrue()
        advance(1)
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isFalse()
    }

    @Test
    fun `the on-screen keyboard's keys are no evidence of a physical one`() {
        PhysicalKeyboard.heard(keyEvent(NativeKeyEvent.KEYCODE_A, NativeKeyEvent.ACTION_DOWN, Keyboard.OnScreen), beforeIme = true)
        assertThat(PhysicalKeyboard.reachesFieldsFirst).isFalse()
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isFalse()
    }

    @Test
    fun `Shift held keeps the newline`() {
        PhysicalKeyboard.heard(down(NativeKeyEvent.KEYCODE_SHIFT_LEFT, shift = true), beforeIme = false)
        assertThat(PhysicalKeyboard.typedEnter(attached, alone = true)).isFalse()
        PhysicalKeyboard.heard(up(NativeKeyEvent.KEYCODE_SHIFT_LEFT), beforeIme = false)
        assertThat(PhysicalKeyboard.typedEnter(attached, alone = true)).isTrue()
    }

    @Test
    fun `once keys reach the fields before the IME, no newline is an Enter but the one for an Enter sent on to the IME`() {
        PhysicalKeyboard.heard(down(NativeKeyEvent.KEYCODE_A), beforeIme = true)
        assertThat(PhysicalKeyboard.reachesFieldsFirst).isTrue()
        assertThat(PhysicalKeyboard.typedEnter(attached, alone = true)).isFalse()

        assertThat(beforeIme(down(), composing = true)).isEqualTo(HardwareEnter.Press.NotOurs)
        // Taken once, with text before it or without, whatever the configuration says.
        assertThat(PhysicalKeyboard.typedEnter(none, alone = false)).isTrue()
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isFalse()
    }

    @Test
    fun `the IME's answer to an Enter is only taken for a while`() {
        beforeIme(down(), composing = true)
        advance(PhysicalKeyboard.ImeAnswerMillis)
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isTrue()

        beforeIme(up())
        beforeIme(down(), composing = true)
        advance(PhysicalKeyboard.ImeAnswerMillis + 1)
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isFalse()
    }

    @Test
    fun `a Shift+Enter or an Enter the IME hands back is not answered for`() {
        beforeIme(down(shift = true), composing = true)
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isFalse()

        beforeIme(up())
        beforeIme(down(), composing = true)
        PhysicalKeyboard.heard(down(), beforeIme = false)
        assertThat(PhysicalKeyboard.typedEnter(none, alone = true)).isFalse()
    }

    @Test
    fun `an Enter sent on to the IME takes its release and repeats with it, and the next press is read afresh`() {
        assertThat(beforeIme(down(), composing = true)).isEqualTo(HardwareEnter.Press.NotOurs)
        assertThat(beforeIme(down(repeat = 1))).isEqualTo(HardwareEnter.Press.Nothing)
        assertThat(beforeIme(up())).isEqualTo(HardwareEnter.Press.NotOurs)
        assertThat(beforeIme(down())).isEqualTo(HardwareEnter.Press.Send)
        assertThat(beforeIme(up())).isEqualTo(HardwareEnter.Press.Nothing)

        // A release lost on the way does not hold the next press back.
        assertThat(beforeIme(down(), composing = true)).isEqualTo(HardwareEnter.Press.NotOurs)
        assertThat(beforeIme(down())).isEqualTo(HardwareEnter.Press.Send)
        assertThat(beforeIme(up())).isEqualTo(HardwareEnter.Press.Nothing)
    }

    @Test
    fun `before the IME, the rest of Enter reads as it does after`() {
        assertThat(beforeIme(down())).isEqualTo(HardwareEnter.Press.Send)
        assertThat(beforeIme(down(), canSend = false)).isEqualTo(HardwareEnter.Press.Nothing)
        assertThat(beforeIme(down(shift = true))).isEqualTo(HardwareEnter.Press.Newline)
        assertThat(beforeIme(down(NativeKeyEvent.KEYCODE_A))).isEqualTo(HardwareEnter.Press.NotOurs)
        assertThat(beforeIme(keyEvent(NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.ACTION_DOWN, Keyboard.OnScreen))).isEqualTo(HardwareEnter.Press.NotOurs)
    }
}
