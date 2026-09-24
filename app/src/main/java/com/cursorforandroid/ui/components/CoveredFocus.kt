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
 * The focus held under a modal sheet — the sidebar drawer, the side panel — as it opens over the content: a field in
 * the content that has it (the composer, as a rule) lets it go and the keyboard is put away, since the sheet and its
 * scrim now stand between the reader and that field. The field's text is its own and stays as it was.
 *
 * It happens as the open is committed — the sheet's target turning open: a release that settles open, a fling, the
 * button, a request from elsewhere — and never while a finger is still dragging the sheet in. A swipe that settles
 * back shut, or is taken away by the system's back gesture, commits nothing, so the keyboard does not drop and come
 * back up for it. A field in the sheet itself that holds focus as it opens (the sidebar's search) keeps it and the
 * keyboard: only focus inside the content marked with [coveredFocus] is released.
 */
@Stable
class CoveredFocus internal constructor() {
    /** Whether something inside the covered content holds focus, as the content last reported it. */
    internal var contentHasFocus = false
}

/** A [CoveredFocus] for a sheet that is committed to being open whenever [open] reads true. */
@Composable
fun rememberCoveredFocus(open: () -> Boolean): CoveredFocus {
    val covered = remember { CoveredFocus() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val isOpen by rememberUpdatedState(open)
    LaunchedEffect(covered, focusManager, keyboard) {
        snapshotFlow { isOpen() }.collect { opened ->
            if (opened && covered.contentHasFocus) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
        }
    }
    return covered
}

/** Marks the content a sheet opens over, so [covered] knows whether the focus it would release is in there. */
fun Modifier.coveredFocus(covered: CoveredFocus): Modifier = onFocusChanged { covered.contentHasFocus = it.hasFocus }
