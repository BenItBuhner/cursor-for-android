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
 * Cursor's desktop UI font stack is literally the platform system font (SF Pro on macOS), so on Android the
 * system sans (Roboto / OEM equivalent) is the faithful analogue. The only bundled programming font in the
 * desktop app is JetBrains Mono, which we bundle too for paths, branches, SHAs and inline code.
 */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

@Immutable
data class CursorTypography(
    /** Large screen header, e.g. sign-in headline. */
    val display: TextStyle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    /** Top bar title. */
    val title: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    /** Sheet / dialog title. */
    val sheetTitle: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    /** Sidebar rows, list rows, primary body copy. */
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    /** Row titles that need slight emphasis. */
    val bodyMedium: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    /** Descriptions, secondary values, metadata. */
    val secondary: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    /** Section labels ("Pinned", "Today", "Grouping"). Sentence case, never uppercase. */
    val sectionLabel: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    /** Timestamps, kbd hints, counts. */
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    /** Chat message text — slightly larger than desktop's 13px because of viewing distance. */
    val message: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    /** Inline code, paths, branches. */
    val code: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 18.sp),
    /** Fenced code blocks. */
    val codeBlock: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.5.sp, lineHeight = 18.sp),
)

val LocalCursorTypography = staticCompositionLocalOf { CursorTypography() }
