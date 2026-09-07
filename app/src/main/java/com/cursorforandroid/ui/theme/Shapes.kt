package com.cursorforandroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Cursor's radius scale (`--cursor-radius-*` in the desktop build): xs 2, sm 4, base 6, lg 8, xl 12, 2xl 14, full.
 * Composer and human messages use xl (`--conversation-surface-border-radius`), cards and selected sidebar rows
 * use lg, chips and buttons use base, badges use sm.
 */
@Immutable
data class CursorShapes(
    val xs: RoundedCornerShape = RoundedCornerShape(2.dp),
    val sm: RoundedCornerShape = RoundedCornerShape(4.dp),
    val base: RoundedCornerShape = RoundedCornerShape(6.dp),
    val lg: RoundedCornerShape = RoundedCornerShape(8.dp),
    val xl: RoundedCornerShape = RoundedCornerShape(12.dp),
    val xxl: RoundedCornerShape = RoundedCornerShape(14.dp),
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    val full: RoundedCornerShape = RoundedCornerShape(50),
)

object CursorDimens {
    val hairline = 1.dp
    /** Web sidebar is 280px wide. */
    val sidebarWidth = 280.dp
    /** Sidebar rows: 32dp with a 6dp gap between them reproduces the web's ~33px pitch. */
    val sidebarRow = 32.dp
    val headerHeight = 44.dp
    val iconSmall = 14.dp
    val icon = 16.dp
    val iconLarge = 18.dp
    val iconButton = 32.dp
    val composerMaxWidth = 840.dp
    val unreadDot = 6.dp
}

val LocalCursorShapes = staticCompositionLocalOf { CursorShapes() }
