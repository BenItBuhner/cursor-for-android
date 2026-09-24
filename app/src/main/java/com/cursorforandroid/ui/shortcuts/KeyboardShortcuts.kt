package com.cursorforandroid.ui.shortcuts

import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
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
 * The app's hardware-keyboard shortcuts, read before the IME is handed the key ([onKeyEventPreIme]) and, where nothing
 * in the shell holds the focus, at the activity ([onKeyEvent], from `MainActivity.dispatchKeyEvent`), so a chord
 * works with the composer focused as well as anywhere else, and only the chords [ShortcutKeymap] lists are taken — a
 * text field's own Ctrl+A, C, V, X and Z never reach here as the app's. The on-screen keyboard's keys are not read at all.
 *
 * With a text field focused the window gives every hardware key to the IME ahead of the activity, and an IME that
 * answers a Ctrl chord itself, or sends it back as its own key, leaves the activity nothing of it: Samsung Keyboard and
 * Gboard both stand in front of the activity there. The views' pass before the IME is the one every key goes through.
 *
 * While a popover in the focused field is open ([popoverOpened]) the keys it answers are left to it: Esc closes it,
 * and Ctrl+N and Ctrl+K move its highlight ([ShortcutKeymap.yieldsToPopover]).
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
    private var popovers = 0

    /** The key [onKeyEventPreIme] read and let go on to the IME, for the activity not to read it a second time. */
    private var declined: KeyStamp? = null

    /**
     * A popover in the focused field opened that answers Esc, Ctrl+N and Ctrl+K itself (the composer's `/` popover,
     * `Modifier.popoverKeys`): they are left to it until the returned release is called, when it shuts.
     */
    fun popoverOpened(): () -> Unit {
        popovers++
        var open = true
        return {
            if (open) {
                open = false
                popovers--
            }
        }
    }

    /**
     * The key on its way to the IME (see `Modifier.shortcutsBeforeIme`). Esc is left to go on: an IME composing a word
     * cancels it on Esc, and the activity still hears one it lets through. A key read here and not taken is not read
     * again when the activity is handed it after the IME.
     */
    fun onKeyEventPreIme(event: KeyEvent): Boolean {
        declined = null
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) return false
        return read(event).also { taken -> if (!taken) declined = KeyStamp.of(event) }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val seen = declined == KeyStamp.of(event)
        declined = null
        return if (seen) false else read(event)
    }

    private fun read(event: KeyEvent): Boolean {
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

        val escape = code == KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
        if (popovers > 0 && (escape || ShortcutKeymap.yieldsToPopover(code))) return false
        if (escape) {
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

/**
 * Hands [keys] every key while something under this node holds the focus, in the views' pass before the IME is given
 * it (see [KeyboardShortcuts.onKeyEventPreIme]); a key it takes goes no further. Around the whole shell, so a chord
 * reaches it from the composer, the palette's field, any field of the window.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.shortcutsBeforeIme(keys: KeyboardShortcuts?): Modifier =
    if (keys == null) this else onPreInterceptKeyBeforeSoftKeyboard { keys.onKeyEventPreIme(it.nativeKeyEvent) }

/** A key event told by its values: the platform recycles key events through a pool, so the object is no witness. */
private data class KeyStamp(val eventTime: Long, val action: Int, val keyCode: Int, val repeatCount: Int) {
    companion object {
        fun of(event: KeyEvent) = KeyStamp(event.eventTime, event.action, event.keyCode, event.repeatCount)
    }
}

/** The activity's [KeyboardShortcuts]; null where the shell is composed without one (previews, most tests). */
val LocalKeyboardShortcuts = staticCompositionLocalOf<KeyboardShortcuts?> { null }
