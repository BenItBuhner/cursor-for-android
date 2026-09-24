package com.cursorforandroid.ui.components

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyCharacterMap
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
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
 * The keys are read before the IME is handed them, not only after: an IME that handles the physical keyboard itself
 * (for its suggestions, say) takes an Enter it is handed and types a newline of its own, which no key handler hears.
 * An Enter pressed on a composition still goes to the IME ([HardwareEnter.pressBeforeIme]); put the field inside an
 * [ImeEnterFallback] as well, which takes the newline the IME may type for it as that Enter.
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

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.hardwareEnter(onSend: (() -> Unit)?, composing: () -> Boolean, settleComposition: () -> Unit, newline: () -> Unit): Modifier {
    fun act(press: HardwareEnter.Press): Boolean = when (press) {
        HardwareEnter.Press.NotOurs -> false
        HardwareEnter.Press.Nothing -> true
        HardwareEnter.Press.Settle -> { settleComposition(); true }
        HardwareEnter.Press.Newline -> { newline(); true }
        HardwareEnter.Press.Send -> { onSend?.invoke(); true }
    }
    return onPreInterceptKeyBeforeSoftKeyboard { event ->
        PhysicalKeyboard.heard(event, beforeIme = true)
        act(HardwareEnter.pressBeforeIme(event, canSend = onSend != null, composing = composing()))
    }.onPreviewKeyEvent { event ->
        PhysicalKeyboard.heard(event, beforeIme = false)
        act(HardwareEnter.press(event, canSend = onSend != null, composing = composing()))
    }
}

/** Calls [heard] for every key a physical keyboard presses in the field, whether or not the IME then takes it. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.onPhysicalKey(heard: () -> Unit): Modifier =
    onPreInterceptKeyBeforeSoftKeyboard { if (it.isFromHardwareKeyboard) heard(); false }
        .onPreviewKeyEvent { if (it.isFromHardwareKeyboard) heard(); false }

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
        if (!event.isEnter) return Press.NotOurs
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

    /**
     * [press] for a key the IME has not been handed yet. An Enter pressed on a composition goes on to the IME, the one
     * that knows whether it only accepts the composition (a conversion confirmed) or ends the line as well, as an IME
     * that holds each word it suggests for does; a newline it types for it is then taken for this Enter
     * ([ImeEnterFallback]). The rest of that Enter follows it: its release goes to the IME too, its repeats nowhere.
     */
    fun pressBeforeIme(event: KeyEvent, canSend: Boolean, composing: Boolean): Press {
        val press = press(event, canSend, composing)
        return if (press == Press.NotOurs) press else PhysicalKeyboard.enterBeforeIme(event, press)
    }
}

private val KeyEvent.isEnter: Boolean get() = key == Key.Enter || key == Key.NumPadEnter

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

/**
 * What the physical keyboard's keys have shown the app, for telling whether a newline an IME types was a physical
 * Enter's ([ImeEnterFallback]). It is the process's, not a field's: the keyboard and the IME are the device's.
 */
internal object PhysicalKeyboard {
    /** How long after a physical Enter goes on to the IME a newline from the IME is still taken as its answer. */
    const val ImeAnswerMillis = 1_000L

    /** How long after the last physical key the keyboard still counts as attached whatever the configuration says. */
    const val RecentKeyMillis = 30_000L

    private const val Never = Long.MIN_VALUE

    /**
     * A physical key has reached a field before the IME was handed it. From then on a physical Enter only ever reaches
     * the IME when [enterBeforeIme] sends it there.
     */
    var reachesFieldsFirst = false
        private set

    private var lastKeyAt = Never
    private var shiftHeld = false
    private var enterToImeAt = Never

    /** The IME has been handed the press of a physical Enter, and is handed its release. */
    private var imeHasEnter = false

    /** Notes [event], a key reaching a field before the IME ([beforeIme]) or after it. */
    fun heard(event: KeyEvent, beforeIme: Boolean) {
        if (!event.isFromHardwareKeyboard) return
        if (beforeIme) reachesFieldsFirst = true
        lastKeyAt = SystemClock.uptimeMillis()
        shiftHeld = event.isShiftPressed
        // An Enter the IME hands on is one it typed nothing for.
        if (!beforeIme && event.isEnter && event.type == KeyEventType.KeyDown) enterToImeAt = Never
    }

    /** Where [press], [HardwareEnter.press]'s reading of a physical Enter the IME has not been handed, sends it. */
    fun enterBeforeIme(event: KeyEvent, press: HardwareEnter.Press): HardwareEnter.Press = when {
        press == HardwareEnter.Press.Settle -> {
            imeHasEnter = true
            // A newline the IME types for Shift+Enter is the newline Shift+Enter asks for.
            enterToImeAt = if (event.isShiftPressed) Never else SystemClock.uptimeMillis()
            HardwareEnter.Press.NotOurs
        }
        !imeHasEnter -> press
        event.type == KeyEventType.KeyUp -> { imeHasEnter = false; HardwareEnter.Press.NotOurs }
        event.nativeKeyEvent.repeatCount > 0 -> HardwareEnter.Press.Nothing
        // A fresh press: the release of the one the IME had was lost on the way.
        else -> { imeHasEnter = false; press }
    }

    /**
     * Whether a newline the IME types now is a physical Enter's: one typed just after a physical Enter went on to the
     * IME is; failing that, none is once physical keys are known to reach the fields first ([reachesFieldsFirst]),
     * since such an Enter would have been taken there; failing that, one is while a hardware keyboard is attached (by
     * [configuration], or a physical key lately) and Shift was not held. [alone] is false for a newline committed on
     * the end of other text, which counts only in the first case. A newline taken for the Enter sent on is spent.
     */
    fun typedEnter(configuration: Configuration, alone: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val sentOn = enterToImeAt != Never && now - enterToImeAt <= ImeAnswerMillis
        enterToImeAt = Never
        if (sentOn) return true
        if (!alone || reachesFieldsFirst || shiftHeld) return false
        return configuration.hardwareKeyboardAttached || lastKeyAt != Never && now - lastKeyAt <= RecentKeyMillis
    }

    /** Back to having seen no keys, as at the start of the process. */
    fun forget() {
        reachesFieldsFirst = false
        lastKeyAt = Never
        shiftHeld = false
        enterToImeAt = Never
        imeHasEnter = false
    }
}

/** A hardware keyboard present and not hidden, as the configuration reports it. */
internal val Configuration.hardwareKeyboardAttached: Boolean
    get() = keyboard != Configuration.KEYBOARD_NOKEYS && hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
