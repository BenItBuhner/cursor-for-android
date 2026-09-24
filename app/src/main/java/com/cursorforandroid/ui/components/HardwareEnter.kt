package com.cursorforandroid.ui.components

import android.view.KeyCharacterMap
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Enter on a physical keyboard sends, as on the desktop and cursor.com; Shift+Enter is the newline. The on-screen
 * keyboard's Enter is left alone and stays a newline: phones have no Shift+Enter, and their send is the button.
 * Which keyboard pressed it is read from the event itself ([isFromHardwareKeyboard]), never from the configuration,
 * since a Bluetooth keyboard and the on-screen one are often both there at once.
 *
 * [onSend] is what the field's send button does right now, or null while that button would not send (nothing to send,
 * files still going up, a launch in flight, the button a Stop): Enter then does nothing at all, not even a newline.
 * An Enter that lands while the IME holds a composition (a handwritten word, pinyin awaiting its characters) is the
 * IME's, as the desktop leaves an Enter to a composing IME: it only accepts the composition as it stands, and the
 * next Enter sends. Shift+Enter's newline is put in here rather than left to the field, whose own reading of that
 * chord turns on the keyboard's key map. A programmatic edit skips the field's input transformation, so [onEdited]
 * hears the text it leaves.
 *
 * An IME can still take the physical Enter before the app sees it and type a newline itself; that Enter is text by
 * the time it arrives and is not told apart here.
 */
fun Modifier.sendOnHardwareEnter(state: TextFieldState, onSend: (() -> Unit)?, onEdited: (String) -> Unit): Modifier =
    hardwareEnter(
        onSend = onSend,
        composing = { state.composition != null },
        // An edit that changes nothing still ends the composition and tells the IME so.
        settleComposition = { state.edit { } },
        newline = {
            state.edit {
                val at = selection.min
                replace(at, selection.max, "\n")
                selection = TextRange(at + 1)
            }
            onEdited(state.text.toString())
        },
    )

/** [sendOnHardwareEnter] for a field that holds a [TextFieldValue]. */
fun Modifier.sendOnHardwareEnter(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, onSend: (() -> Unit)?): Modifier =
    hardwareEnter(
        onSend = onSend,
        composing = { value.composition != null },
        settleComposition = { onValueChange(value.copy(composition = null)) },
        newline = {
            val at = value.selection.min
            onValueChange(TextFieldValue(value.text.replaceRange(at, value.selection.max, "\n"), TextRange(at + 1)))
        },
    )

private fun Modifier.hardwareEnter(onSend: (() -> Unit)?, composing: () -> Boolean, settleComposition: () -> Unit, newline: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        when (HardwareEnter.press(event, canSend = onSend != null, composing = composing())) {
            HardwareEnter.Press.NotOurs -> false
            HardwareEnter.Press.Nothing -> true
            HardwareEnter.Press.Settle -> { settleComposition(); true }
            HardwareEnter.Press.Newline -> { newline(); true }
            HardwareEnter.Press.Send -> { onSend?.invoke(); true }
        }
    }

internal object HardwareEnter {

    enum class Press {
        /** Not Enter from a physical keyboard, or Enter with Alt, Ctrl or Meta: the field handles it as it always has. */
        NotOurs,
        Send,
        Newline,
        /** The IME's composition is accepted as typed; nothing is sent and no newline goes in. */
        Settle,
        /** Taken and dropped: send is not available, or it is the key's release or a held Enter repeating. */
        Nothing,
    }

    fun press(event: KeyEvent, canSend: Boolean, composing: Boolean): Press {
        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return Press.NotOurs
        if (!event.isFromHardwareKeyboard) return Press.NotOurs
        if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return Press.NotOurs
        if (event.type != KeyEventType.KeyDown) return Press.Nothing
        val repeat = event.nativeKeyEvent.repeatCount > 0
        return when {
            composing -> if (repeat) Press.Nothing else Press.Settle
            event.isShiftPressed -> Press.Newline
            // Holding Enter down sends once, however long the key repeats.
            repeat -> Press.Nothing
            canSend -> Press.Send
            else -> Press.Nothing
        }
    }
}

/**
 * Whether a physical keyboard sent this key. The on-screen keyboard's keys reach the app as events from the virtual
 * keyboard device, flagged [android.view.KeyEvent.FLAG_SOFT_KEYBOARD] when the IME sends them the standard way; an
 * event from any virtual input device counts as the on-screen keyboard's too.
 */
internal val KeyEvent.isFromHardwareKeyboard: Boolean
    get() {
        val native = nativeKeyEvent
        if (native.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0) return false
        if (native.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        return native.device?.isVirtual != true
    }
