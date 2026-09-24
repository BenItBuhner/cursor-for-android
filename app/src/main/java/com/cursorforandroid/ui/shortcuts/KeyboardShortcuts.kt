package com.cursorforandroid.ui.shortcuts

import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the shell does with the chords [KeyboardShortcuts] reads; registered by the shell while it is composed. */
interface ShortcutHandler {
    /** True when the chord was the app's here; false lets the key go on to the focused view, as if never read. */
    fun onShortcut(action: ShortcutAction): Boolean

    /** Esc on its own: true when something was closed or the text field left. */
    fun onEscape(): Boolean

    /**
     * Ctrl is up: the quick switcher opens what it has selected. [commit] is false when the key was never seen coming
     * up — the window lost focus while it was held — and the switcher is put away instead.
     */
    fun onCtrlReleased(commit: Boolean)
}

/**
 * The app's hardware-keyboard shortcuts, read at the activity before any view sees the key (see
 * `MainActivity.dispatchKeyEvent`), so a chord works with the composer focused as well as anywhere else, and only the
 * chords [ShortcutKeymap] lists are taken — a text field's own Ctrl+A, C, V, X and Z never reach here as the app's.
 * The on-screen keyboard's keys are not read at all.
 *
 * Also keeps whether Ctrl is being held on its own: after [holdMillis] of that, [showNumbers] turns on and the sidebar
 * numbers its first ten rows for Ctrl+1 … Ctrl+0; letting go puts them away. A chord pressed before then was never a
 * hold, and shows nothing.
 *
 * A key taken on its way down is taken on its way up too, so the view under it never sees half a keystroke; Esc taken
 * so is never turned into Back by the platform's fallback, which would have popped the screen or sent the app away.
 */
@Stable
class KeyboardShortcuts(private val scope: CoroutineScope, private val holdMillis: Long = NUMBERS_AFTER_MILLIS) {
    var handler: ShortcutHandler? = null

    /** Ctrl has been held on its own long enough for the sidebar's rows to show their numbers. */
    var showNumbers by mutableStateOf(false)
        private set

    private var ctrlHeld = false
    private var holdJob: Job? = null
    private val taken = HashSet<Int>()

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromHardwareKeyboard) return false
        val code = event.keyCode
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> down(event, code)
            KeyEvent.ACTION_UP -> up(event, code)
            else -> false
        }
    }

    private fun down(event: KeyEvent, code: Int): Boolean {
        if (ShortcutKeymap.isCtrl(code)) {
            if (!ctrlHeld) {
                ctrlHeld = true
                holdJob?.cancel()
                holdJob = scope.launch {
                    delay(holdMillis)
                    if (ctrlHeld) showNumbers = true
                }
            }
            return false
        }
        // Ctrl came up somewhere this activity did not hear it (a dialog's window had the focus)…
        if (ctrlHeld && !event.isCtrlPressed) release(commit = true)
        // …or went down there: held all the same, so that letting go of it is heard, though it was never a hold.
        if (!ctrlHeld && event.isCtrlPressed) ctrlHeld = true
        if (event.isCtrlPressed && !isModifier(code) && !showNumbers) holdJob?.cancel()

        if (code == KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            if (event.repeatCount > 0) return code in taken
            return take(code, handler?.onEscape() == true)
        }
        val action = ShortcutKeymap.action(code, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed) ?: return false
        if (event.repeatCount > 0 && !ShortcutKeymap.repeats(action)) return code in taken
        return take(code, handler?.onShortcut(action) == true)
    }

    private fun up(event: KeyEvent, code: Int): Boolean {
        if (ShortcutKeymap.isCtrl(code)) {
            // With both Ctrl keys down, letting go of one is not letting go.
            if (ctrlHeld && !event.isCtrlPressed) release(commit = true)
            return false
        }
        return taken.remove(code)
    }

    private fun take(code: Int, handled: Boolean): Boolean {
        if (handled) taken += code else taken -= code
        return handled
    }

    private fun release(commit: Boolean) {
        ctrlHeld = false
        holdJob?.cancel()
        holdJob = null
        showNumbers = false
        handler?.onCtrlReleased(commit)
    }

    /** The window lost focus with keys held: whatever comes up next is not heard, so nothing is left held. */
    fun onFocusLost() {
        taken.clear()
        if (ctrlHeld || showNumbers) release(commit = false) else handler?.onCtrlReleased(commit = false)
    }

    private fun isModifier(code: Int): Boolean = KeyEvent.isModifierKey(code)

    companion object {
        /** How long Ctrl is held on its own before the sidebar numbers its rows. */
        const val NUMBERS_AFTER_MILLIS = 500L
    }
}

/** The activity's [KeyboardShortcuts]; null where the shell is composed without one (previews, most tests). */
val LocalKeyboardShortcuts = staticCompositionLocalOf<KeyboardShortcuts?> { null }
