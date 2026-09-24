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

/** [keyCode] going down or up on [keyboard] with the modifiers held, [repeat] key-downs into holding it. */
internal fun keyEvent(
    keyCode: Int,
    action: Int,
    keyboard: Keyboard = Keyboard.Physical,
    shift: Boolean = false,
    ctrl: Boolean = false,
    meta: Boolean = false,
    repeat: Int = 0,
): KeyEvent {
    var state = 0
    if (shift) state = state or NativeKeyEvent.META_SHIFT_ON or NativeKeyEvent.META_SHIFT_LEFT_ON
    if (ctrl) state = state or NativeKeyEvent.META_CTRL_ON or NativeKeyEvent.META_CTRL_LEFT_ON
    if (meta) state = state or NativeKeyEvent.META_META_ON or NativeKeyEvent.META_META_LEFT_ON
    return KeyEvent(NativeKeyEvent(0L, 0L, action, keyCode, repeat, state, keyboard.deviceId, 0, keyboard.flags, InputDevice.SOURCE_KEYBOARD))
}

internal fun enterKey(
    action: Int,
    keyboard: Keyboard = Keyboard.Physical,
    shift: Boolean = false,
    ctrl: Boolean = false,
    repeat: Int = 0,
    keyCode: Int = NativeKeyEvent.KEYCODE_ENTER,
): KeyEvent = keyEvent(keyCode, action, keyboard, shift = shift, ctrl = ctrl, repeat = repeat)

/**
 * Focuses the field and presses [keyCode] on it, down and up; [held] extra key-downs are the key repeating while it
 * is held. Returns whether anything took the key-down.
 */
internal fun SemanticsNodeInteraction.pressKey(
    keyCode: Int,
    keyboard: Keyboard = Keyboard.Physical,
    shift: Boolean = false,
    ctrl: Boolean = false,
    meta: Boolean = false,
    held: Int = 0,
): Boolean {
    performSemanticsAction(SemanticsActions.RequestFocus)
    val window = checkNotNull(fetchSemanticsNode().root)
    fun key(action: Int, repeat: Int = 0) = keyEvent(keyCode, action, keyboard, shift, ctrl, meta, repeat)
    val taken = performKeyPress(key(NativeKeyEvent.ACTION_DOWN))
    for (repeat in 1..held) performKeyPress(key(NativeKeyEvent.ACTION_DOWN, repeat))
    // The press can take the field away (a saved edit closes); the key-up then goes wherever focus went, as on a device.
    window.sendKeyEvent(key(NativeKeyEvent.ACTION_UP))
    return taken
}

/** [pressKey] for Enter. */
internal fun SemanticsNodeInteraction.pressEnter(
    keyboard: Keyboard = Keyboard.Physical,
    shift: Boolean = false,
    ctrl: Boolean = false,
    held: Int = 0,
    keyCode: Int = NativeKeyEvent.KEYCODE_ENTER,
): Boolean = pressKey(keyCode, keyboard, shift = shift, ctrl = ctrl, held = held)
