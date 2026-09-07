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

/**
 * Geometry measured from the official web app at 2x (cursor.com/agents); 1 CSS px == 1 dp because the web
 * body text is 14px and ours is 14sp. Values are the measured CSS pixels.
 */
object CursorDimens {
    val hairline = 1.dp
    /** Sidebar column: 278px. */
    val sidebarWidth = 278.dp
    /** Header row: icons centred at y=18 → 36px. */
    val headerHeight = 36.dp
    /**
     * Icon boxes are sized so the VISIBLE glyph matches the web: our paths fill ~16–18 of the 24-unit viewport,
     * so a 24dp box shows a 16px cube, an 18dp box shows a ~13px search / toggle glyph, a 16dp box a 12px glyph.
     */
    val logo = 24.dp
    val headerIcon = 18.dp
    val iconButton = 28.dp
    val chevron = 16.dp
    /** Sidebar rows: 32px tall, 33px pitch. */
    val sidebarRow = 32.dp
    val sidebarRowGap = 1.dp
    /** Selected row: 6px inset on both sides, radius 6. */
    val selectionInset = 6.dp
    /** Row leading icon ("New Chat"): ~13px visible, at x=14.5; text starts at x=37. */
    val rowIcon = 18.dp
    /** State glyph slot: glyph centred at x=20, ~12px visible; title at x=36. */
    val glyph = 16.dp
    val unreadDot = 6.dp
    /** Composer "+", mic and send: 24px circles with 12px glyphs. */
    val roundButton = 24.dp
    /** 20dp box → ~11.5px visible "+" like the web. */
    val roundButtonGlyph = 20.dp
    /** Composer box: 640px max, 12px padding. */
    val composerMaxWidth = 640.dp
    val composerPadding = 12.dp
    /** Recent-chat preview card: 134 x 84, radius 8; rows on a 106px pitch (22px gap). */
    val previewCardWidth = 134.dp
    val previewCardHeight = 84.dp
    val recentRowGap = 22.dp
    /** Card → title gap: 17px. */
    val previewToTitle = 17.dp
    /** Recent-row dot: 5px. */
    val recentDot = 5.dp
    /** Pills ("Branch", "Open", "Early Beta"): 20px tall, 12px text. */
    val pillHeight = 20.dp
    /** Account footer avatar: 28px. */
    val avatar = 28.dp
}

val LocalCursorShapes = staticCompositionLocalOf { CursorShapes() }
