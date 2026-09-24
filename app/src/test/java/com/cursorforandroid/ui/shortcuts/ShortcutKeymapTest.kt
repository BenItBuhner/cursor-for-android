package com.cursorforandroid.ui.shortcuts

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShortcutKeymapTest {

    private fun ctrl(keyCode: Int, shift: Boolean = false) = ShortcutKeymap.action(keyCode, ctrl = true, shift = shift)

    @Test
    fun `the listed chords are the app's`() {
        assertThat(ctrl(KeyEvent.KEYCODE_F)).isEqualTo(ShortcutAction.Search)
        assertThat(ctrl(KeyEvent.KEYCODE_K)).isEqualTo(ShortcutAction.Search)
        assertThat(ctrl(KeyEvent.KEYCODE_TAB)).isEqualTo(ShortcutAction.SwitchNext)
        assertThat(ctrl(KeyEvent.KEYCODE_TAB, shift = true)).isEqualTo(ShortcutAction.SwitchPrevious)
        assertThat(ctrl(KeyEvent.KEYCODE_B)).isEqualTo(ShortcutAction.ToggleSidebar)
        assertThat(ctrl(KeyEvent.KEYCODE_B, shift = true)).isEqualTo(ShortcutAction.TogglePanel)
        assertThat(ctrl(KeyEvent.KEYCODE_N)).isEqualTo(ShortcutAction.NewChat)
        assertThat(ctrl(KeyEvent.KEYCODE_N, shift = true)).isEqualTo(ShortcutAction.NewProject)
        assertThat(ctrl(KeyEvent.KEYCODE_COMMA)).isEqualTo(ShortcutAction.OpenSettings)
        assertThat(ctrl(KeyEvent.KEYCODE_SLASH)).isEqualTo(ShortcutAction.ShowShortcuts)
        assertThat(ctrl(KeyEvent.KEYCODE_SLASH, shift = true)).isEqualTo(ShortcutAction.ShowShortcuts)
        assertThat(ctrl(KeyEvent.KEYCODE_R)).isEqualTo(ShortcutAction.CatchUp)
        assertThat(ctrl(KeyEvent.KEYCODE_R, shift = true)).isEqualTo(ShortcutAction.ReloadTranscript)
    }

    @Test
    fun `Ctrl+1 to Ctrl+9 are the first nine rows and Ctrl+0 the tenth, from either row of digits`() {
        (1..9).forEach { n -> assertThat(ctrl(KeyEvent.KEYCODE_0 + n)).isEqualTo(ShortcutAction.OpenRailItem(n - 1)) }
        assertThat(ctrl(KeyEvent.KEYCODE_0)).isEqualTo(ShortcutAction.OpenRailItem(9))
        assertThat(ctrl(KeyEvent.KEYCODE_NUMPAD_3)).isEqualTo(ShortcutAction.OpenRailItem(2))
        assertThat(ctrl(KeyEvent.KEYCODE_NUMPAD_0)).isEqualTo(ShortcutAction.OpenRailItem(9))
        assertThat(ctrl(KeyEvent.KEYCODE_1, shift = true)).isNull()
    }

    @Test
    fun `a text field's own Ctrl chords are never the app's`() {
        listOf(
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_ENTER,
        ).forEach { code ->
            assertThat(ctrl(code)).isNull()
            assertThat(ctrl(code, shift = true)).isNull()
        }
    }

    @Test
    fun `without Ctrl, or with Alt or Meta held too, nothing is the app's`() {
        assertThat(ShortcutKeymap.action(KeyEvent.KEYCODE_F, ctrl = false, shift = false)).isNull()
        assertThat(ShortcutKeymap.action(KeyEvent.KEYCODE_B, ctrl = true, shift = false, alt = true)).isNull()
        assertThat(ShortcutKeymap.action(KeyEvent.KEYCODE_N, ctrl = true, shift = false, meta = true)).isNull()
        assertThat(ShortcutKeymap.action(KeyEvent.KEYCODE_F, ctrl = true, shift = true)).isNull()
    }

    @Test
    fun `only the switcher steps as its key repeats`() {
        assertThat(ShortcutKeymap.repeats(ShortcutAction.SwitchNext)).isTrue()
        assertThat(ShortcutKeymap.repeats(ShortcutAction.SwitchPrevious)).isTrue()
        assertThat(ShortcutKeymap.repeats(ShortcutAction.NewChat)).isFalse()
        assertThat(ShortcutKeymap.repeats(ShortcutAction.OpenRailItem(0))).isFalse()
    }

    @Test
    fun `Tab and Shift+Tab without Ctrl are never the app's`() {
        assertThat(ShortcutKeymap.action(KeyEvent.KEYCODE_TAB, ctrl = false, shift = false)).isNull()
        assertThat(ShortcutKeymap.action(KeyEvent.KEYCODE_TAB, ctrl = false, shift = true)).isNull()
    }

    @Test
    fun `only the chords an open popover answers are left to it`() {
        assertThat(ShortcutKeymap.yieldsToPopover(KeyEvent.KEYCODE_N)).isTrue()
        assertThat(ShortcutKeymap.yieldsToPopover(KeyEvent.KEYCODE_K)).isTrue()
        listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_COMMA).forEach {
            assertThat(ShortcutKeymap.yieldsToPopover(it)).isFalse()
        }
    }

    @Test
    fun `the cheat sheet lists every chord the keymap answers`() {
        val listed = ShortcutsCopy.groups.flatMap { it.lines }.flatMap { it.chords }.map { it.joinToString("+") }
        assertThat(listed).containsAtLeast(
            "Ctrl+F", "Ctrl+K", "Ctrl+Tab", "Ctrl+B", "Ctrl+Shift+B", "Ctrl+1 … 9", "Ctrl+0", "Ctrl+N", "Ctrl+Shift+N",
            "Ctrl+,", "Ctrl+/", "Ctrl+Shift+?", "Esc", "Ctrl+R", "Ctrl+Shift+R",
        )
    }
}
