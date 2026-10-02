package com.cursorforandroid.ui.shortcuts

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** What a chord on a hardware keyboard asks the app for (see [ShortcutKeymap]). */
sealed interface ShortcutAction {
    /** Ctrl+P or Ctrl+K (out of the box; see [ShortcutBindings]): the search palette over chats, Projects and the transcripts kept on this device. */
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
 * The app's chords. Ctrl+Tab, Ctrl+Shift+Tab and Ctrl+1 … Ctrl+0 are fixed; every other shortcut is on the keys
 * [bindings][ShortcutBindings] puts it on — Ctrl chords out of the box, as on desktop Cursor under Windows and Linux,
 * and Ctrl or Alt chords once moved. A Meta chord is never the app's, and neither is any Ctrl chord a text field
 * answers (A, C, V, X, Z, Y, the arrows, Backspace, Home, End), which [ShortcutRules] keeps any shortcut off.
 */
object ShortcutKeymap {
    fun action(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean = false,
        meta: Boolean = false,
        bindings: ShortcutBindings = ShortcutBindings.Defaults,
    ): ShortcutAction? {
        if (meta || (!ctrl && !alt)) return null
        if (ctrl && !alt) {
            digit(keyCode)?.let { if (!shift) return ShortcutAction.OpenRailItem(if (it == 0) 9 else it - 1) }
            if (keyCode == KeyEvent.KEYCODE_TAB) return if (shift) ShortcutAction.SwitchPrevious else ShortcutAction.SwitchNext
        }
        return bindings.owner(KeyChord(keyCode, ctrl, shift, alt))?.action
    }

    /** Held down, the switcher keeps stepping as the key repeats; every other chord acts once however long it is held. */
    fun repeats(action: ShortcutAction): Boolean = action == ShortcutAction.SwitchNext || action == ShortcutAction.SwitchPrevious

    /**
     * The chords an open popover answers itself (`Modifier.popoverKeys`: Ctrl+N or Ctrl+J down and Ctrl+P or Ctrl+K up,
     * as in the desktop's menus), left to it while it is open along with Esc, whichever shortcut they are on. Ctrl+Tab
     * stays the app's: the popover picks on Tab, but a held Ctrl is the switcher's.
     */
    fun yieldsToPopover(keyCode: Int, ctrl: Boolean = true, meta: Boolean = false): Boolean =
        ctrl && !meta && keyCode in POPOVER_KEYS

    private val POPOVER_KEYS = setOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_K)

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
