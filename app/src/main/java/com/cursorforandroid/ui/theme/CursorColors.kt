package com.cursorforandroid.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Cursor design tokens.
 *
 * Sources:
 *  - Opaque backgrounds, base overlay colour and accent hues come from the theme JSON bundled with the
 *    desktop app (`theme-cursor/themes/cursor-dark-color-theme.json`, "Cursor Dark Anysphere", and
 *    `cursor-light-color-theme.json`). Cursor paints almost every text/border/hover/selection as the single
 *    base colour (#E4E4E4 dark, #15151D light) at a low alpha over a couple of near-black backgrounds.
 *  - Surface levels were verified by sampling the official web app (cursor.com/agents) and the iOS app
 *    screenshots: the canvas is #141414 and every elevated surface (sidebar, cards, sheets, composer)
 *    is #181818. Status colours (unread blue #59A1E9, running #5A8CBA, error #D54268) were sampled from the
 *    iOS agent list.
 */
@Immutable
data class CursorColors(
    val isDark: Boolean,
    /** Level 0 — the main canvas behind conversations and the empty state. */
    val canvas: Color,
    /** Level 1 — sidebar, cards, sheets, composer, popovers. */
    val surface: Color,
    /** Level 2 — inline code chips, nested cards inside a surface. */
    val surfaceRaised: Color,
    /** The overlay base colour that all translucent tokens derive from. */
    val base: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textPlaceholder: Color,
    /** Input / field fill. base @ 4%. */
    val wash: Color,
    /** Card / input / divider border. base @ 7%. */
    val borderSubtle: Color,
    /** Hovered / pressed row. base @ 7%. */
    val hover: Color,
    /** Selected row, neutral chip fill. base @ 12%. */
    val selected: Color,
    /** Focus ring, circular icon-button fill. base @ 15%. */
    val borderFocus: Color,
    val accentBlue: Color,
    val accentBlueHover: Color,
    val onAccent: Color,
    val cyan: Color,
    val green: Color,
    val orange: Color,
    val orangeDeep: Color,
    val danger: Color,
    val secondaryButton: Color,
    val statusUnread: Color,
    val statusRunning: Color,
    val statusError: Color,
    val statusIdle: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    val link: Color,
    val codeString: Color,
    val codeFunction: Color,
    val codeKeyword: Color,
    val codeNumber: Color,
    val codeType: Color,
    val codeComment: Color,
)

private val DarkBase = Color(0xFFE4E4E4)
private val LightBase = Color(0xFF15151D)

val CursorDarkColors = CursorColors(
    isDark = true,
    canvas = Color(0xFF141414),
    surface = Color(0xFF181818),
    surfaceRaised = Color(0xFF232323),
    base = DarkBase,
    textPrimary = DarkBase.copy(alpha = 0.92f),
    textSecondary = DarkBase.copy(alpha = 0.55f),
    textPlaceholder = DarkBase.copy(alpha = 0.37f),
    wash = DarkBase.copy(alpha = 0.04f),
    borderSubtle = DarkBase.copy(alpha = 0.07f),
    hover = DarkBase.copy(alpha = 0.07f),
    selected = DarkBase.copy(alpha = 0.12f),
    borderFocus = DarkBase.copy(alpha = 0.15f),
    accentBlue = Color(0xFF81A1C1),
    accentBlueHover = Color(0xFF87A6C4),
    onAccent = Color(0xFF191C22),
    cyan = Color(0xFF88C0D0),
    green = Color(0xFF3FA266),
    orange = Color(0xFFF1B467),
    orangeDeep = Color(0xFFD2943E),
    danger = Color(0xFFE34671),
    secondaryButton = Color(0xFF626262),
    statusUnread = Color(0xFF59A1E9),
    statusRunning = Color(0xFF5A8CBA),
    statusError = Color(0xFFD54268),
    statusIdle = DarkBase.copy(alpha = 0.30f),
    diffAdded = Color(0xFF3FA266),
    diffRemoved = Color(0xFFFC6B83),
    link = Color(0xFF81A1C1),
    codeString = Color(0xFFA8CC7C),
    codeFunction = Color(0xFFEBC88D),
    codeKeyword = Color(0xFFD6D6DD),
    codeNumber = Color(0xFFF8C762),
    codeType = Color(0xFF82D2CE),
    codeComment = DarkBase.copy(alpha = 0.45f),
)

val CursorLightColors = CursorColors(
    isDark = false,
    canvas = Color(0xFFFCFCFC),
    surface = Color(0xFFF3F3F4),
    surfaceRaised = Color(0xFFE9E9EB),
    base = LightBase,
    textPrimary = LightBase.copy(alpha = 0.92f),
    textSecondary = Color(0xFF121224).copy(alpha = 0.63f),
    textPlaceholder = Color(0xFF0E0E2A).copy(alpha = 0.28f),
    wash = Color(0xFF0B0B2D).copy(alpha = 0.04f),
    borderSubtle = Color(0xFF0B0B2D).copy(alpha = 0.08f),
    hover = Color(0xFF0B0B2D).copy(alpha = 0.07f),
    selected = Color(0xFF0B0B2D).copy(alpha = 0.10f),
    borderFocus = Color(0xFF161618).copy(alpha = 0.16f),
    accentBlue = Color(0xFF3173A7),
    accentBlueHover = Color(0xFF3C80B8),
    onAccent = Color(0xFFFCFCFC),
    cyan = Color(0xFF4C7F8C),
    green = Color(0xFF2D8B48),
    orange = Color(0xFFE6742D),
    orangeDeep = Color(0xFFA16900),
    danger = Color(0xFFDE3757),
    secondaryButton = Color(0xFF0C0C2C).copy(alpha = 0.12f),
    statusUnread = Color(0xFF3C80B8),
    statusRunning = Color(0xFF3C7CAB),
    statusError = Color(0xFFDE3757),
    statusIdle = LightBase.copy(alpha = 0.25f),
    diffAdded = Color(0xFF2D8B48),
    diffRemoved = Color(0xFFC92D4F),
    link = Color(0xFF3173A7),
    codeString = Color(0xFF1F8A65),
    codeFunction = Color(0xFFA16900),
    codeKeyword = Color(0xFF252525),
    codeNumber = Color(0xFFC08532),
    codeType = Color(0xFF4C7F8C),
    codeComment = LightBase.copy(alpha = 0.45f),
)

val LocalCursorColors = staticCompositionLocalOf { CursorDarkColors }
