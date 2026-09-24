package com.cursorforandroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * The focus on either side of a sheet — the sidebar drawer, the side panel, the wide window's rail — as it opens and
 * closes, so the keyboard never stands over a field the reader can no longer see or reach.
 *
 * Opening, a field in the content the sheet covers ([coveredFocus]) that holds focus — the composer, as a rule — lets
 * it go and the keyboard is put away, since the sheet and its scrim now stand between the reader and that field. A
 * field in the sheet itself that holds focus as it opens (the sidebar's search) keeps it and the keyboard.
 *
 * Closing, it is the other way about: a field in the sheet ([sheetFocus]) that holds focus lets it go and the keyboard
 * is put away, where it would otherwise leave with the sheet still taking every keystroke off screen. Focus anywhere
 * else stays where it is: a composer beside the rail keeps it as the rail collapses.
 *
 * Either way it happens as the move is committed — the sheet's target turning: a release that settles, a fling, a
 * button, the scrim, back carried through, a request from elsewhere — and never while a finger is still dragging the
 * sheet. A swipe that settles back where it started, a back gesture that is cancelled or one the system takes for
 * itself commits nothing, so the keyboard does not drop and come back up for it. A field's text is its own and stays
 * as it was.
 */
@Stable
class SheetFocus internal constructor() {
    /** Whether something inside the covered content holds focus, as the content last reported it. */
    internal var contentHasFocus = false

    /** Whether something inside the sheet holds focus, as the sheet last reported it. */
    internal var sheetHasFocus = false
}

/** A [SheetFocus] for a sheet that is committed to being open whenever [open] reads true. */
@Composable
fun rememberSheetFocus(open: () -> Boolean): SheetFocus {
    val focus = remember { SheetFocus() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val isOpen by rememberUpdatedState(open)
    LaunchedEffect(focus, focusManager, keyboard) {
        snapshotFlow { isOpen() }.collect { opened ->
            val leftBehind = if (opened) focus.contentHasFocus else focus.sheetHasFocus
            if (leftBehind) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
        }
    }
    return focus
}

/** Marks the content a sheet opens over, so [focus] knows whether the focus it would release on opening is in there. */
fun Modifier.coveredFocus(focus: SheetFocus): Modifier = onFocusChanged { focus.contentHasFocus = it.hasFocus }

/**
 * Marks the sheet itself, so [focus] knows whether the focus it would release on closing is in there. It belongs on a
 * node that stays composed while the sheet is shut, so it hears a focused field inside leave the composition.
 */
fun Modifier.sheetFocus(focus: SheetFocus): Modifier = onFocusChanged { focus.sheetHasFocus = it.hasFocus }
