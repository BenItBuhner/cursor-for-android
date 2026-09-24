package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * A physical keyboard drives any popover's list as the desktop's menus take it: the arrows move the highlight round
 * the ends, Enter and Tab pick the row it is on, Esc closes. Nothing here knows what the rows are.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PopoverKeysTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val field get() = compose.onNode(hasSetTextAction())
    private val text = TextFieldState()
    private var items by mutableStateOf(listOf("Agent", "Plan", "Ask", "Debug"))
    private var query by mutableStateOf("")
    private var open by mutableStateOf(true)
    private val picked = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val selection = rememberPopoverSelection(items, query)
                Column {
                    BasicTextField(
                        text,
                        Modifier.popoverKeys(open, selection, composing = { text.composition != null }, onPick = { picked += it }, onDismiss = { open = false }),
                    )
                    if (open) {
                        items.forEachIndexed { index, item ->
                            CursorMenuItem(item, icon = null, highlighted = index == selection.highlighted, onClick = {})
                        }
                    }
                }
            }
        }
    }

    private fun highlighted(): List<String> = compose.onAllNodes(isSelected()).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    private fun down() = field.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)

    private fun up() = field.pressKey(NativeKeyEvent.KEYCODE_DPAD_UP)

    @Test
    fun `the arrows move the highlight round the ends, and Enter picks the row it is on`() {
        show()
        assertThat(highlighted()).containsExactly("Agent")

        down()
        assertThat(highlighted()).containsExactly("Plan")
        up()
        up()
        assertThat(highlighted()).containsExactly("Debug")
        down()
        assertThat(highlighted()).containsExactly("Agent")

        down()
        assertThat(field.pressEnter()).isTrue()
        assertThat(picked).containsExactly("Plan")
        // The Enter was the popover's: the field never saw it.
        assertThat(text.text.toString()).isEmpty()
    }

    @Test
    fun `Tab picks as Enter does, with Shift or without`() {
        show()
        down()
        down()
        assertThat(field.pressKey(NativeKeyEvent.KEYCODE_TAB)).isTrue()
        assertThat(field.pressKey(NativeKeyEvent.KEYCODE_TAB, shift = true)).isTrue()
        assertThat(picked).containsExactly("Ask", "Ask").inOrder()
        assertThat(text.text.toString()).isEmpty()
    }

    @Test
    fun `Esc closes it, and the keys are the field's again`() {
        show()
        down()
        assertThat(field.pressKey(NativeKeyEvent.KEYCODE_ESCAPE)).isTrue()
        compose.waitForIdle()
        assertThat(open).isFalse()
        assertThat(highlighted()).isEmpty()

        field.pressEnter(Keyboard.OnScreen)
        field.pressEnter()
        assertThat(picked).isEmpty()
        assertThat(text.text.toString()).isEqualTo("\n\n")
    }

    @Test
    fun `the on-screen keyboard's keys are left to the field while it is open`() {
        show()
        field.pressEnter(Keyboard.OnScreen)
        assertThat(picked).isEmpty()
        assertThat(text.text.toString()).isEqualTo("\n")
        assertThat(highlighted()).containsExactly("Agent")
    }

    @Test
    fun `a new query starts the highlight at the top, and a list that shrinks keeps it on the last row it still has`() {
        show()
        down()
        down()
        down()
        assertThat(highlighted()).containsExactly("Debug")

        items = listOf("Agent", "Plan")
        compose.waitForIdle()
        assertThat(highlighted()).containsExactly("Plan")
        // Grown back, it stays where it was drawn in to rather than jumping to the row it had left.
        items = listOf("Agent", "Plan", "Ask", "Debug")
        compose.waitForIdle()
        assertThat(highlighted()).containsExactly("Plan")

        query = "a"
        compose.waitForIdle()
        assertThat(highlighted()).containsExactly("Agent")
        field.pressEnter()
        assertThat(picked).containsExactly("Agent")
    }

    @Test
    fun `with nothing listed Enter is still taken, picks nothing and types nothing`() {
        show()
        down()
        items = emptyList()
        compose.waitForIdle()
        assertThat(highlighted()).isEmpty()

        assertThat(field.pressEnter()).isTrue()
        assertThat(down()).isTrue()
        assertThat(picked).isEmpty()
        assertThat(text.text.toString()).isEmpty()

        // Whatever is listed next starts at the top.
        items = listOf("Ask", "Debug")
        compose.waitForIdle()
        assertThat(highlighted()).containsExactly("Ask")
    }

    @Test
    fun `what a press means`() {
        fun press(
            keyCode: Int,
            action: Int = NativeKeyEvent.ACTION_DOWN,
            keyboard: Keyboard = Keyboard.Physical,
            shift: Boolean = false,
            ctrl: Boolean = false,
            meta: Boolean = false,
            open: Boolean = true,
            composing: Boolean = false,
        ) = PopoverKeys.press(keyEvent(keyCode, action, keyboard, shift, ctrl, meta), open = open, composing = composing)

        assertThat(press(NativeKeyEvent.KEYCODE_DPAD_DOWN)).isEqualTo(PopoverKeys.Press.Down)
        assertThat(press(NativeKeyEvent.KEYCODE_DPAD_UP)).isEqualTo(PopoverKeys.Press.Up)
        // The desktop's Emacs chords, on Ctrl alone.
        assertThat(press(NativeKeyEvent.KEYCODE_N, ctrl = true)).isEqualTo(PopoverKeys.Press.Down)
        assertThat(press(NativeKeyEvent.KEYCODE_J, ctrl = true)).isEqualTo(PopoverKeys.Press.Down)
        assertThat(press(NativeKeyEvent.KEYCODE_P, ctrl = true)).isEqualTo(PopoverKeys.Press.Up)
        assertThat(press(NativeKeyEvent.KEYCODE_K, ctrl = true)).isEqualTo(PopoverKeys.Press.Up)
        assertThat(press(NativeKeyEvent.KEYCODE_N)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_P, ctrl = true, meta = true)).isEqualTo(PopoverKeys.Press.NotOurs)

        assertThat(press(NativeKeyEvent.KEYCODE_ENTER)).isEqualTo(PopoverKeys.Press.Pick)
        assertThat(press(NativeKeyEvent.KEYCODE_ENTER, shift = true)).isEqualTo(PopoverKeys.Press.Pick)
        assertThat(press(NativeKeyEvent.KEYCODE_NUMPAD_ENTER)).isEqualTo(PopoverKeys.Press.Pick)
        assertThat(press(NativeKeyEvent.KEYCODE_TAB)).isEqualTo(PopoverKeys.Press.Pick)
        assertThat(press(NativeKeyEvent.KEYCODE_TAB, shift = true)).isEqualTo(PopoverKeys.Press.Pick)
        assertThat(press(NativeKeyEvent.KEYCODE_ESCAPE)).isEqualTo(PopoverKeys.Press.Close)

        // A taken key's release is taken with it; nothing happens twice.
        assertThat(press(NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.ACTION_UP)).isEqualTo(PopoverKeys.Press.Nothing)
        assertThat(press(NativeKeyEvent.KEYCODE_DPAD_DOWN, NativeKeyEvent.ACTION_UP)).isEqualTo(PopoverKeys.Press.Nothing)
        assertThat(press(NativeKeyEvent.KEYCODE_N, NativeKeyEvent.ACTION_UP, ctrl = true)).isEqualTo(PopoverKeys.Press.Nothing)

        // Closed, it takes nothing: Enter sends again, and Shift+Tab is free for whatever else wants it.
        assertThat(press(NativeKeyEvent.KEYCODE_ENTER, open = false)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_TAB, shift = true, open = false)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_ESCAPE, open = false)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_DPAD_DOWN, open = false)).isEqualTo(PopoverKeys.Press.NotOurs)
        // The IME's composition and the on-screen keyboard are left alone.
        assertThat(press(NativeKeyEvent.KEYCODE_ENTER, composing = true)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_DPAD_DOWN, composing = true)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_ENTER, keyboard = Keyboard.OnScreen)).isEqualTo(PopoverKeys.Press.NotOurs)

        assertThat(press(NativeKeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_DEL)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_SPACE)).isEqualTo(PopoverKeys.Press.NotOurs)
        assertThat(press(NativeKeyEvent.KEYCODE_A)).isEqualTo(PopoverKeys.Press.NotOurs)
    }
}
