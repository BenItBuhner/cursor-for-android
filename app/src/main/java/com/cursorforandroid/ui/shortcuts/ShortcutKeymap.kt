package com.cursorforandroid.ui.shortcuts

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** What a chord on a hardware keyboard asks the app for (see [ShortcutKeymap]). */
sealed interface ShortcutAction {
    /** Ctrl+F or Ctrl+K: the search palette over chats, Projects and the transcripts kept on this device. */
    data object Search : ShortcutAction

    /** Ctrl+Tab: the quick switcher, one step further back through the recent chats each press. */
    data object SwitchNext : ShortcutAction

    /** Ctrl+Shift+Tab: the quick switcher, one step toward the newest. */
    data object SwitchPrevious : ShortcutAction

    /** Ctrl+B: the sidebar — the rail collapsed or expanded on a wide window, the drawer on a narrow one. */
    data object ToggleSidebar : ShortcutAction

    /** Ctrl+Shift+B: the open chat's right-side panel. */
    data object TogglePanel : ShortcutAction

    /** Ctrl+1 … Ctrl+9, Ctrl+0: the sidebar's row at [position] (0-based; Ctrl+0 is the tenth) in the order it is drawn. */
    data class OpenRailItem(val position: Int) : ShortcutAction

    /** Ctrl+N: a fresh New Chat composer, focused. */
    data object NewChat : ShortcutAction

    /** Ctrl+Shift+N: the Project editor, creating one. */
    data object NewProject : ShortcutAction

    /** Ctrl+, : Settings. */
    data object OpenSettings : ShortcutAction

    /** Ctrl+/ or Ctrl+Shift+? : the list of these shortcuts. */
    data object ShowShortcuts : ShortcutAction

    /** Ctrl+R in an open chat: what is new since the last event the chat has. */
    data object CatchUp : ShortcutAction

    /** Ctrl+Shift+R in an open chat: the menu's "Reload transcript" — the chat's copies thrown away and read again. */
    data object ReloadTranscript : ShortcutAction
}

/**
 * The app's chords. Ctrl alone is the modifier, as on desktop Cursor under Windows and Linux; with Alt or Meta held too
 * the chord is not the app's, and neither is any Ctrl chord a text field answers (A, C, V, X, Z, Y, the arrows,
 * Backspace, Home, End), none of which is listed here.
 */
object ShortcutKeymap {
    fun action(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean = false, meta: Boolean = false): ShortcutAction? {
        if (!ctrl || alt || meta) return null
        digit(keyCode)?.let { return if (shift) null else ShortcutAction.OpenRailItem(if (it == 0) 9 else it - 1) }
        return when (keyCode) {
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_K -> ShortcutAction.Search.takeUnless { shift }
            KeyEvent.KEYCODE_TAB -> if (shift) ShortcutAction.SwitchPrevious else ShortcutAction.SwitchNext
            KeyEvent.KEYCODE_B -> if (shift) ShortcutAction.TogglePanel else ShortcutAction.ToggleSidebar
            KeyEvent.KEYCODE_N -> if (shift) ShortcutAction.NewProject else ShortcutAction.NewChat
            KeyEvent.KEYCODE_COMMA -> ShortcutAction.OpenSettings.takeUnless { shift }
            // Ctrl+Shift+? is Ctrl+Shift+/ on the layouts that put ? over /.
            KeyEvent.KEYCODE_SLASH -> ShortcutAction.ShowShortcuts
            KeyEvent.KEYCODE_R -> if (shift) ShortcutAction.ReloadTranscript else ShortcutAction.CatchUp
            else -> null
        }
    }

    /** Held down, the switcher keeps stepping as the key repeats; every other chord acts once however long it is held. */
    fun repeats(action: ShortcutAction): Boolean = action == ShortcutAction.SwitchNext || action == ShortcutAction.SwitchPrevious

    private fun digit(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    fun isCtrl(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
}

/**
 * Whether a physical keyboard sent this key. The on-screen keyboard's keys reach the app as events from the virtual
 * keyboard device, flagged [KeyEvent.FLAG_SOFT_KEYBOARD] when the IME sends them the standard way; an event from any
 * virtual input device counts as the on-screen keyboard's too. The composer's hardware Enter reads keys the same way.
 */
internal val KeyEvent.isFromHardwareKeyboard: Boolean
    get() {
        if (flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return false
        if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        return device?.isVirtual != true
    }
