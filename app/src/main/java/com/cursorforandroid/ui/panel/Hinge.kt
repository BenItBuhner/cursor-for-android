package com.cursorforandroid.ui.panel

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo

/**
 * A fold that splits the window into two panes side by side: [start] to [end] is what it covers, nothing on a Fold's
 * crease and the hinge itself where it hides part of the screen. Measured from the window's left edge, as the platform
 * reports it, until [fromStart] measures it from a layout's start edge.
 */
@Immutable
data class Hinge(val start: Dp, val end: Dp) {
    /** The same hinge measured from the start edge of a [window]-wide layout: from its right edge under RTL. */
    fun fromStart(window: Dp, rtl: Boolean): Hinge = if (rtl) Hinge(window - end, window - start) else this

    companion object {
        /**
         * The fold in [info] that the shell keeps content off, in dp at [density]: a vertical one that separates the two
         * panes, which a Fold's does held half open like a book, or a hinge that hides part of the screen at any angle.
         * A Fold lying flat reads as one screen and has none, as Material's adaptive panes treat it
         * (`HingePolicy.AvoidSeparating`); a horizontal fold is the tabletop posture, not a split between panes.
         */
        fun of(info: WindowLayoutInfo, density: Float): Hinge? = info.displayFeatures
            .filterIsInstance<FoldingFeature>()
            .firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL && (it.isSeparating || it.state == FoldingFeature.State.HALF_OPENED) }
            ?.bounds
            ?.let { Hinge(start = (it.left / density).dp, end = (it.right / density).dp) }
    }
}

/**
 * The window's [Hinge], as [WindowLayoutInfo] last had it: state, so what lays the panes out reads it where it is used
 * (in derived state and at layout) and a fold changing under a composition recomposes nothing that does not depend on it.
 */
@Stable
class WindowHinge(initial: Hinge? = null) {
    var value: Hinge? by mutableStateOf(initial)
}

/** The activity's [WindowHinge]; null where the shell is composed without one, which lays it out as if there were no fold. */
val LocalHinge = staticCompositionLocalOf<WindowHinge?> { null }
