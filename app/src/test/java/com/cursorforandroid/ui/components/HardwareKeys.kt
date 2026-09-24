package com.cursorforandroid.ui.components

import android.view.InputDevice
import android.view.KeyCharacterMap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import android.view.KeyEvent as NativeKeyEvent

/** Where a key event comes from, as the platform stamps it: its device, and the flags the input method adds. */
internal enum class Keyboard(val deviceId: Int, val flags: Int) {
    /** A Bluetooth or USB keyboard: a real input device, nothing flagging it as the on-screen one. */
    Physical(deviceId = 7, flags = 0),

    /** The on-screen keyboard's Enter, as an IME sends it down the input connection. */
    OnScreen(KeyCharacterMap.VIRTUAL_KEYBOARD, NativeKeyEvent.FLAG_SOFT_KEYBOARD or NativeKeyEvent.FLAG_KEEP_TOUCH_MODE),
}

internal fun enterKey(
    action: Int,
    keyboard: Keyboard = Keyboard.Physical,
    shift: Boolean = false,
    ctrl: Boolean = false,
    repeat: Int = 0,
    keyCode: Int = NativeKeyEvent.KEYCODE_ENTER,
): KeyEvent {
    var meta = 0
    if (shift) meta = meta or NativeKeyEvent.META_SHIFT_ON or NativeKeyEvent.META_SHIFT_LEFT_ON
    if (ctrl) meta = meta or NativeKeyEvent.META_CTRL_ON or NativeKeyEvent.META_CTRL_LEFT_ON
    return KeyEvent(NativeKeyEvent(0L, 0L, action, keyCode, repeat, meta, keyboard.deviceId, 0, keyboard.flags, InputDevice.SOURCE_KEYBOARD))
}

/**
 * Focuses the field and presses Enter on it, down and up; [held] extra key-downs are the key repeating while it is
 * held. Returns whether anything took the key-down.
 */
internal fun SemanticsNodeInteraction.pressEnter(
    keyboard: Keyboard = Keyboard.Physical,
    shift: Boolean = false,
    ctrl: Boolean = false,
    held: Int = 0,
    keyCode: Int = NativeKeyEvent.KEYCODE_ENTER,
): Boolean {
    performSemanticsAction(SemanticsActions.RequestFocus)
    val window = checkNotNull(fetchSemanticsNode().root)
    val taken = performKeyPress(enterKey(NativeKeyEvent.ACTION_DOWN, keyboard, shift, ctrl, keyCode = keyCode))
    for (repeat in 1..held) performKeyPress(enterKey(NativeKeyEvent.ACTION_DOWN, keyboard, shift, ctrl, repeat, keyCode))
    // The press can take the field away (a saved edit closes); the key-up then goes wherever focus went, as on a device.
    window.sendKeyEvent(enterKey(NativeKeyEvent.ACTION_UP, keyboard, shift, ctrl, keyCode = keyCode))
    return taken
}
