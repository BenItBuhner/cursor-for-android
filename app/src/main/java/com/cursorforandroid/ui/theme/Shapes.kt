package com.cursorforandroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Cursor's four-step radius scale (calibrated against the desktop Agents window):
 * 6 chips / badges / inline code, 8 buttons / inputs / sidebar selection, 10 cards, 12 composer + sheets,
 * capsule for pills, toggles and the circular icon buttons.
 */
@Immutable
data class CursorShapes(
    val sm: RoundedCornerShape = RoundedCornerShape(6.dp),
    val md: RoundedCornerShape = RoundedCornerShape(8.dp),
    val lg: RoundedCornerShape = RoundedCornerShape(10.dp),
    val xl: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** Chat bubbles and the mobile composer card get one step more than desktop for thumb-scale surfaces. */
    val bubble: RoundedCornerShape = RoundedCornerShape(16.dp),
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    val full: RoundedCornerShape = RoundedCornerShape(50),
)

object CursorDimens {
    val borderWidth = 1.dp
    val iconButton = 40.dp
    val iconSize = 20.dp
    val sidebarWidth = 300.dp
    val rowHeight = 40.dp
    val statusDot = 8.dp
    val pageGutter = 16.dp
}

val LocalCursorShapes = staticCompositionLocalOf { CursorShapes() }
