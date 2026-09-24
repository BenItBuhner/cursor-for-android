package com.cursorforandroid.ui.components

import android.view.KeyCharacterMap
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import android.view.KeyEvent as NativeKeyEvent

/**
 * A physical keyboard's Enter sends from the composer and Shift+Enter breaks the line; the on-screen keyboard's Enter
 * stays a newline. Enter presses send only when the send button would: never a Stop, never while a send is in flight
 * or there is nothing to send, and never through an IME's composition.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class HardwareEnterTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val field get() = compose.onNode(hasSetTextAction())

    private var hoisted by mutableStateOf("")
    private var sends = 0
    private var stops = 0
    private lateinit var view: View

    private fun show(canSend: (String) -> Boolean = { it.isNotBlank() }, isRunning: Boolean = false, isSending: Boolean = false) {
        compose.setContent {
            view = LocalView.current
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = hoisted,
                    onValueChange = { hoisted = it },
                    placeholder = "Ask anything",
                    onSend = { sends++ },
                    canSend = canSend(hoisted),
                    isRunning = isRunning,
                    onStop = { stops++ },
                    isSending = isSending,
                )
            }
        }
    }

    private fun draft(): String =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun `a physical Enter sends the draft and puts no newline in it`() {
        show()
        field.performTextInput("ship it")
        assertThat(field.pressEnter()).isTrue()
        assertThat(sends).isEqualTo(1)
        assertThat(draft()).isEqualTo("ship it")
    }

    @Test
    fun `Shift+Enter on a physical keyboard breaks the line and sends nothing`() {
        show()
        field.performTextInput("line one")
        field.pressEnter(shift = true)
        field.performTextInput("line two")
        assertThat(sends).isEqualTo(0)
        assertThat(draft()).isEqualTo("line one\nline two")
        compose.runOnIdle { assertThat(hoisted).isEqualTo("line one\nline two") }
    }

    @Test
    fun `holding Shift+Enter keeps breaking lines`() {
        show()
        field.performTextInput("a")
        field.pressEnter(shift = true, held = 2)
        assertThat(draft()).isEqualTo("a\n\n\n")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `the on-screen keyboard's Enter stays a newline`() {
        show()
        field.performTextInput("first")
        field.pressEnter(Keyboard.OnScreen)
        assertThat(sends).isEqualTo(0)
        assertThat(draft()).isEqualTo("first\n")
        compose.runOnIdle { assertThat(hoisted).isEqualTo("first\n") }
    }

    @Test
    fun `Enter on an empty composer does nothing, not even a newline`() {
        show()
        field.pressEnter()
        assertThat(sends).isEqualTo(0)
        assertThat(draft()).isEmpty()
    }

    @Test
    fun `Enter does nothing while the owner holds send back`() {
        // The New Chat composer while its files are still going up: text there, send disabled.
        show(canSend = { false })
        field.performTextInput("wait for the upload")
        field.pressEnter()
        assertThat(sends).isEqualTo(0)
        assertThat(draft()).isEqualTo("wait for the upload")
    }

    @Test
    fun `Enter does nothing while a send is already in flight`() {
        show(isSending = true)
        field.performTextInput("again")
        field.pressEnter()
        assertThat(sends).isEqualTo(0)
        assertThat(draft()).isEqualTo("again")
    }

    @Test
    fun `during a run Enter sends as the button does, and on an empty composer never stops the run`() {
        show(isRunning = true)
        field.pressEnter()
        assertThat(stops).isEqualTo(0)
        assertThat(sends).isEqualTo(0)

        // With text the button is Send again; queueing it behind the run is the owner's, as it is for a tap.
        field.performTextInput("and then this")
        field.pressEnter()
        assertThat(sends).isEqualTo(1)
        assertThat(stops).isEqualTo(0)
    }

    @Test
    fun `holding Enter down sends once`() {
        show()
        field.performTextInput("once")
        field.pressEnter(held = 3)
        assertThat(sends).isEqualTo(1)
        assertThat(draft()).isEqualTo("once")
    }

    @Test
    fun `the number pad's Enter sends too`() {
        show()
        field.performTextInput("from the pad")
        field.pressEnter(keyCode = NativeKeyEvent.KEYCODE_NUMPAD_ENTER)
        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `Ctrl+Enter is not taken as a send`() {
        show()
        field.performTextInput("hold on")
        field.pressEnter(ctrl = true)
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `an Enter during an IME composition only accepts it, and the next Enter sends`() {
        show()
        field.performTextInput("say ")
        compose.waitForIdle()
        val connection = compose.runOnIdle { view.onCreateInputConnection(EditorInfo()) }
        assertThat(connection).isNotNull()
        compose.runOnIdle { connection!!.setComposingText("nihao", 1) }
        assertThat(draft()).isEqualTo("say nihao")

        field.pressEnter()
        assertThat(sends).isEqualTo(0)
        assertThat(draft()).isEqualTo("say nihao")
        compose.runOnIdle { assertThat(hoisted).isEqualTo("say nihao") }

        field.pressEnter()
        assertThat(sends).isEqualTo(1)
        assertThat(draft()).isEqualTo("say nihao")
    }

    @Test
    fun `which keyboard pressed the key is read from the event`() {
        fun down(keyboard: Keyboard) = enterKey(NativeKeyEvent.ACTION_DOWN, keyboard)
        assertThat(down(Keyboard.Physical).isFromHardwareKeyboard).isTrue()
        assertThat(down(Keyboard.OnScreen).isFromHardwareKeyboard).isFalse()
        // Either mark alone is enough: some IMEs send the soft-keyboard flag from a real device id, others neither.
        val virtualOnly = NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0)
        assertThat(KeyEvent(virtualOnly).isFromHardwareKeyboard).isFalse()
        val flaggedOnly = NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ENTER, 0, 0, 7, 0, NativeKeyEvent.FLAG_SOFT_KEYBOARD)
        assertThat(KeyEvent(flaggedOnly).isFromHardwareKeyboard).isFalse()
        // The two-argument constructor an IME's sendKeyEvent often uses stamps the virtual keyboard's id.
        assertThat(KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ENTER)).isFromHardwareKeyboard).isFalse()
    }

    @Test
    fun `what a press means`() {
        fun press(event: KeyEvent, canSend: Boolean = true, composing: Boolean = false) = HardwareEnter.press(event, canSend, composing)
        val down = enterKey(NativeKeyEvent.ACTION_DOWN)
        assertThat(press(down)).isEqualTo(HardwareEnter.Press.Send)
        assertThat(press(down, canSend = false)).isEqualTo(HardwareEnter.Press.Nothing)
        assertThat(press(down, composing = true)).isEqualTo(HardwareEnter.Press.Settle)
        assertThat(press(enterKey(NativeKeyEvent.ACTION_DOWN, shift = true), canSend = false)).isEqualTo(HardwareEnter.Press.Newline)
        assertThat(press(enterKey(NativeKeyEvent.ACTION_DOWN, shift = true), composing = true)).isEqualTo(HardwareEnter.Press.Settle)
        assertThat(press(enterKey(NativeKeyEvent.ACTION_DOWN, repeat = 1))).isEqualTo(HardwareEnter.Press.Nothing)
        assertThat(press(enterKey(NativeKeyEvent.ACTION_UP))).isEqualTo(HardwareEnter.Press.Nothing)
        assertThat(press(enterKey(NativeKeyEvent.ACTION_DOWN, Keyboard.OnScreen))).isEqualTo(HardwareEnter.Press.NotOurs)
        assertThat(press(enterKey(NativeKeyEvent.ACTION_DOWN, ctrl = true))).isEqualTo(HardwareEnter.Press.NotOurs)
        assertThat(press(enterKey(NativeKeyEvent.ACTION_DOWN, keyCode = NativeKeyEvent.KEYCODE_SPACE))).isEqualTo(HardwareEnter.Press.NotOurs)
    }
}
