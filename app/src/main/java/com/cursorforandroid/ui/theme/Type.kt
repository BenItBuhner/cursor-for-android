package com.cursorforandroid.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cursorforandroid.R

/**
 * Cursor's type scale from the desktop build: `--cursor-font-size-xs/sm/base/lg` = 11 / 12 / 13 / 14 px with
 * line heights 14 / 16 / 18 / 22. The UI font is the platform system font (the desktop stack is
 * `SF Pro, -apple-system, BlinkMacSystemFont, sans-serif`), so Android's system sans is the correct analogue.
 * JetBrains Mono is the only font Cursor bundles; it is bundled here for code and paths only — branch names, PR
 * refs and every other label stay in the UI font.
 */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

@Immutable
data class CursorTypography(
    /** `--cursor-font-size-lg` 14 / 22 — conversation text (`--conversation-text-font-size`). */
    val message: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    /** 13 / 18 — sidebar rows and list titles (measured: ascender-to-descender 12px at 13px SF Pro). */
    val row: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    val rowMedium: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    /**
     * 14 / 22 — composer input and placeholder, the same size as [message]: the web composer is 14/22 too, and a
     * draft that becomes a sent message must not shrink. Set against the 13sp selector chips and the 24dp round
     * buttons, this keeps the composer's text-to-button proportion at the web's (14px text, 24px buttons).
     */
    val input: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    /** `--cursor-font-size-base` 13 / 18 — controls, chips, selector labels, secondary lines. */
    val base: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    val baseMedium: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    /** `--cursor-font-size-sm` 12 / 16 — group labels, metadata, captions. */
    val small: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    /** `--cursor-font-size-xs` 11 / 14 — badges, kbd hints. */
    val tiny: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
    /** Header and sheet titles: 14 medium, as in the Agents window header. */
    val title: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    /** Standalone section titles inside a screen ("Sign in" stays [pageTitle]; sheet pages use this). */
    val sectionTitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    val pageTitle: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    val code: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 16.sp),
    val codeBlock: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 18.sp),
)

val LocalCursorTypography = staticCompositionLocalOf { CursorTypography() }
