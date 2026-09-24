package com.cursorforandroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * The row a physical keyboard has highlighted in a popover's [items], as in the desktop's menus: moved by the arrows
 * round from the last row to the first and back ([move]), picked by Enter or Tab ([popoverKeys]). With nothing listed
 * there is no highlight. A row shows it by passing `highlighted` to its [CursorMenuItem].
 */
@Stable
class PopoverSelection<T> internal constructor(val items: List<T>, private val index: MutableIntState) {
    /** The highlighted row's index, or -1 while [items] is empty. */
    val highlighted: Int get() = if (items.isEmpty()) -1 else index.intValue.coerceIn(0, items.lastIndex)

    val highlightedItem: T? get() = items.getOrNull(highlighted)

    /** Moves the highlight [step] rows, round from the last row to the first and back. */
    fun move(step: Int) {
        if (items.isNotEmpty()) index.intValue = Math.floorMod(highlighted + step, items.size)
    }
}

/**
 * A [PopoverSelection] over [items], on the first row to begin with and again whenever any of [resetKeys] changes (the
 * popover opening on a new query, say). A list that changes under it otherwise keeps the highlight, drawn in to the
 * last row the list still has, as the desktop's `/` menu keeps it.
 */
@Composable
fun <T> rememberPopoverSelection(items: List<T>, vararg resetKeys: Any?): PopoverSelection<T> {
    val index = remember(*resetKeys) { mutableIntStateOf(0) }
    // Written back rather than only clamped on reading, so a list that shrinks and grows again stays where it was
    // drawn in to instead of jumping back to a row it had left; an empty list starts the next one at the top.
    SideEffect {
        val held = if (items.isEmpty()) 0 else index.intValue.coerceIn(0, items.lastIndex)
        if (held != index.intValue) index.intValue = held
    }
    return PopoverSelection(items, index)
}

/**
 * Hands a physical keyboard to an open popover, as the desktop's menus take it: the arrows, and Ctrl+N or Ctrl+J down
 * and Ctrl+P or Ctrl+K up, move [selection]'s highlight; Enter and Tab, with Shift or without, pick the highlighted row
 * ([onPick]), or do nothing while nothing is listed; Esc closes it ([onDismiss]). While [open] is false it takes
 * nothing, so Enter goes back to sending and Tab and Shift+Tab to whatever else the field does with them. Put it ahead
 * of the field's other key handlers ([sendOnHardwareEnter]) so they only hear what an open popover leaves.
 *
 * The on-screen keyboard is left alone, as [sendOnHardwareEnter] leaves it, and so is a key pressed while the IME
 * holds a composition: that key is the IME's, and an Enter then only accepts the composition. The keys are read
 * before the IME is handed them as well as after, as [sendOnHardwareEnter] reads them, so an IME that handles the
 * physical keyboard itself cannot take them from the popover.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun <T> Modifier.popoverKeys(
    open: Boolean,
    selection: PopoverSelection<T>,
    composing: () -> Boolean,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
): Modifier {
    val handle = { event: KeyEvent ->
        when (PopoverKeys.press(event, open = open, composing = composing())) {
            PopoverKeys.Press.NotOurs -> false
            PopoverKeys.Press.Nothing -> true
            PopoverKeys.Press.Up -> { selection.move(-1); true }
            PopoverKeys.Press.Down -> { selection.move(1); true }
            PopoverKeys.Press.Pick -> { selection.highlightedItem?.let(onPick); true }
            PopoverKeys.Press.Close -> { onDismiss(); true }
        }
    }
    return onPreInterceptKeyBeforeSoftKeyboard(handle).onPreviewKeyEvent(handle)
}

internal object PopoverKeys {
    enum class Press {
        /** Not a key an open popover takes: handed on. */
        NotOurs,

        /** The highlight one row up, round to the last. */
        Up,

        /** The highlight one row down, round to the first. */
        Down,

        /** The highlighted row picked. */
        Pick,

        /** The popover closed. */
        Close,

        /** Taken, and nothing done: the release of a key the popover takes. */
        Nothing,
    }

    fun press(event: KeyEvent, open: Boolean, composing: Boolean): Press {
        if (!open || composing || !event.isFromHardwareKeyboard) return Press.NotOurs
        val ctrlOnly = event.isCtrlPressed && !event.isMetaPressed
        val press = when (event.key) {
            Key.DirectionUp -> Press.Up
            Key.DirectionDown -> Press.Down
            Key.P, Key.K -> if (ctrlOnly) Press.Up else return Press.NotOurs
            Key.N, Key.J -> if (ctrlOnly) Press.Down else return Press.NotOurs
            Key.Enter, Key.NumPadEnter, Key.Tab -> Press.Pick
            Key.Escape -> Press.Close
            else -> return Press.NotOurs
        }
        return if (event.type == KeyEventType.KeyDown) press else Press.Nothing
    }
}
