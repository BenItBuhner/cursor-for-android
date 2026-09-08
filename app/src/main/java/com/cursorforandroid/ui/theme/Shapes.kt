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
 * Geometry. Proportions follow the official web app (cursor.com/agents, measured at 2x; 1 CSS px == 1 dp since the
 * web body text is 14px and ours is 14sp), but anything a finger has to hit is sized for touch rather than for a
 * pointer: the desktop's 36px header becomes 44dp and every icon button accepts touches over at least 44dp. The
 * touch layer is separate from the visual box, so a control's painted size follows the web rather than the finger —
 * the composer's round buttons stay at the web's 24px next to their 14sp text instead of growing with the 40dp hit
 * area, which is what keeps text and buttons in proportion.
 */
object CursorDimens {
    val hairline = 1.dp
    /** Sidebar column: 278px on the web. */
    val sidebarWidth = 278.dp
    /** Header row. */
    val headerHeight = 44.dp
    /**
     * Icon boxes are sized so the VISIBLE glyph matches the web: the 24-unit icons fill ~18–20 of the viewport, so a
     * 22dp box shows a ~17px cube and an 18dp box a ~14px toggle / search glyph.
     */
    val logo = 22.dp
    val headerIcon = 18.dp
    /** Visual box of a flat icon button; the touch target is [touchTarget]. */
    val iconButton = 32.dp
    val touchTarget = 44.dp
    val chevron = 16.dp
    /** Sidebar rows. */
    val sidebarRow = 36.dp
    val sidebarRowGap = 2.dp
    /** Selected row: inset on both sides, radius 6. */
    val selectionInset = 8.dp
    /** State glyph slot in a chat row. */
    val glyph = 16.dp
    val unreadDot = 6.dp
    /**
     * Composer footer row, and the min height of the selector chips ("Model ⌄", repository, branch): a 28dp tap
     * height for the chips, with the round buttons centred in it. The chips paint nothing, so the row's height is
     * only ever seen as air.
     */
    val composerFooter = 28.dp
    /**
     * Composer "+", send and stop: the web's 24px disc beside its 14px text, kept at 24dp so the text stays the
     * largest thing in the box (the disc is ~2.4x the text's cap height, as on cursor.com). Touches are accepted
     * over 40dp regardless, so the disc never has to grow for the finger — growing it is what made the buttons
     * dwarf the 13sp text before.
     */
    val roundButton = 24.dp
    /**
     * Icon box inside the disc. The 24-unit "+" and arrow span 14 units plus their round caps, the stop square 12,
     * so a 17dp box shows a ~11dp "+": a little under half the disc, like the web's.
     */
    val roundButtonGlyph = 17.dp
    /** Composer box: 640px max, 12px padding. */
    val composerMaxWidth = 640.dp
    val composerPadding = 12.dp
    /** Recent-chat preview card, radius 8. */
    val previewCardWidth = 124.dp
    val previewCardHeight = 80.dp
    val recentRowGap = 18.dp
    /** Card → title gap. */
    val previewToTitle = 16.dp
    /** Recent-row dot. */
    val recentDot = 6.dp
    /** Pills ("Branch", "Open", branch names): 12px text. */
    val pillHeight = 22.dp
    /** Account footer avatar. */
    val avatar = 30.dp
    /** Desktop-idiom buttons ("Continue", "Sign out") grown to a comfortable tap height. */
    val buttonHeight = 34.dp
    /** Settings / sheet rows. */
    val listRow = 44.dp
    /** Depth of the fade a scrolling list dissolves into at an edge that still has content behind it. */
    val scrollFade = 28.dp
}

val LocalCursorShapes = staticCompositionLocalOf { CursorShapes() }
