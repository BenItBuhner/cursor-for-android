package com.cursorforandroid.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Cursor design tokens, taken from the desktop build (Cursor 3.19.13, `workbench.glass.main.*` and
 * `theme-cursor/themes/cursor-dark-color-theme.json` "Cursor Dark Anysphere v0.0.3" / `cursor-light-color-theme.json`).
 *
 * The Agents window paints three opaque surfaces — `--cursor-chrome` (#141414, the chat canvas), `--cursor-sidebar`
 * (#181818) and `--cursor-editor` (#181818, inputs / elevated / cards) — and derives everything else from the
 * theme foreground (#F0F0F0 dark, #141414 light) at fixed alphas:
 *
 *  text      100 / 74 / 60 / 36 %      (`--cursor-text-primary…quaternary`)
 *  icons     100 / 66 / 52 / 28 %      (`--cursor-icon-primary…quaternary`)
 *  fills      20 / 14 /  8 /  6 / 4 %  (`--cursor-bg-primary…quinary`), active selection 12 %
 *  strokes    20 / 12 /  8 /  4 %      (`--cursor-stroke-primary…quaternary`), focus 15 %
 *
 * Accent hues are the theme's `button.background`, `textLink`, `badge`, `gitDecoration.*`, `charts.*` values. The
 * unread dot (#6D9BF0) was sampled from the official web app's sidebar.
 */
@Immutable
data class CursorColors(
    val isDark: Boolean,
    /** `--cursor-chrome`: the chat / detail canvas. */
    val canvas: Color,
    /** `--cursor-sidebar`. */
    val sidebar: Color,
    /** `--cursor-editor`: inputs, cards, sheets, menus and other elevated surfaces. */
    val elevated: Color,
    /** The theme foreground every translucent token is derived from. */
    val base: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconTertiary: Color,
    val iconQuaternary: Color,
    /** `--cursor-bg-primary` 20 %. */
    val fillStrong: Color,
    /** `--cursor-bg-secondary` 14 %. */
    val fillMedium: Color,
    /** `--cursor-bg-tertiary` 8 %: neutral chips, "+" button, hover. */
    val fill: Color,
    /** `--cursor-bg-quaternary` 6 %: selected sidebar row. */
    val fillSoft: Color,
    /** `--cursor-bg-quinary` 4 %: input wash, human message. */
    val fillFaint: Color,
    /** `list.activeSelectionBackground` 12 %. */
    val fillActive: Color,
    /** `--cursor-stroke-primary` 20 %. */
    val strokeStrong: Color,
    /** `--cursor-stroke-secondary` 12 %: human message, focused composer. */
    val stroke: Color,
    /** `--cursor-stroke-tertiary` 8 %: cards, composer, dividers. */
    val strokeSubtle: Color,
    /** `--cursor-stroke-quaternary` 4 %. */
    val strokeFaint: Color,
    val focus: Color,
    val accent: Color,
    val onAccent: Color,
    val link: Color,
    val badge: Color,
    val onBadge: Color,
    val green: Color,
    val gitAdded: Color,
    val gitRemoved: Color,
    val gitModified: Color,
    val red: Color,
    val orange: Color,
    val blue: Color,
    val purple: Color,
    val cyan: Color,
    /** `charts.yellow`: the Projects palette's yellow. */
    val yellow: Color,
    /** `terminal.ansiMagenta`: the Projects palette's magenta. */
    val magenta: Color,
    /** `--cursor-brand`, the Cursor orange; the Projects palette's "Brand" tone, the same in every theme. */
    val brand: Color,
    /** Sidebar unread marker. */
    val unreadDot: Color,
    val codeString: Color,
    val codeFunction: Color,
    val codeNumber: Color,
    val codeType: Color,
) {
    /**
     * The colour a Cursor Project's `colorId` stands for, as the Agents Window resolves it (`agent-appearance`):
     * `default` is the secondary icon tone, `brand` the Cursor orange, and every other id one of the theme's chart /
     * terminal hues (`--cursor-icon-<id>-primary`). An id this build has not heard of reads as `default`.
     */
    fun projectTone(colorId: String?): Color = when (colorId?.trim()?.lowercase()) {
        "green" -> green
        "cyan" -> cyan
        "blue" -> blue
        "purple" -> purple
        "magenta" -> magenta
        "orange" -> orange
        "yellow" -> yellow
        "red" -> red
        "brand" -> brand
        else -> iconSecondary
    }
}

/** `--cursor-brand` (#F54E00) in the desktop build, theme-independent. */
private val Brand = Color(0xFFF54E00)

private val DarkBase = Color(0xFFF0F0F0)
private val LightBase = Color(0xFF141414)

val CursorDarkColors = CursorColors(
    isDark = true,
    canvas = Color(0xFF141414),
    sidebar = Color(0xFF181818),
    elevated = Color(0xFF181818),
    base = DarkBase,
    textPrimary = DarkBase,
    textSecondary = DarkBase.copy(alpha = 0.74f),
    textTertiary = DarkBase.copy(alpha = 0.60f),
    textQuaternary = DarkBase.copy(alpha = 0.36f),
    iconPrimary = DarkBase,
    iconSecondary = DarkBase.copy(alpha = 0.66f),
    iconTertiary = DarkBase.copy(alpha = 0.52f),
    iconQuaternary = DarkBase.copy(alpha = 0.28f),
    fillStrong = DarkBase.copy(alpha = 0.20f),
    fillMedium = DarkBase.copy(alpha = 0.14f),
    fill = DarkBase.copy(alpha = 0.08f),
    fillSoft = DarkBase.copy(alpha = 0.06f),
    fillFaint = DarkBase.copy(alpha = 0.04f),
    fillActive = DarkBase.copy(alpha = 0.12f),
    strokeStrong = DarkBase.copy(alpha = 0.20f),
    stroke = DarkBase.copy(alpha = 0.12f),
    strokeSubtle = DarkBase.copy(alpha = 0.08f),
    strokeFaint = DarkBase.copy(alpha = 0.04f),
    focus = DarkBase.copy(alpha = 0.15f),
    accent = Color(0xFF81A1C1),
    onAccent = Color(0xFF191C22),
    link = Color(0xFF81A1C1),
    badge = Color(0xFF88C0D0),
    onBadge = Color(0xFF141414),
    green = Color(0xFF3FA266),
    gitAdded = Color(0xFF70B489),
    gitRemoved = Color(0xFFFC6B83),
    gitModified = Color(0xFFF1B467),
    red = Color(0xFFE34671),
    orange = Color(0xFFF1B467),
    blue = Color(0xFF81A1C1),
    purple = Color(0xFFB48EAD),
    cyan = Color(0xFF88C0D0),
    yellow = Color(0xFFF1B467),
    magenta = Color(0xFFB48EAD),
    brand = Brand,
    unreadDot = Color(0xFF6D9BF0),
    codeString = Color(0xFFA8CC7C),
    codeFunction = Color(0xFFEBC88D),
    codeNumber = Color(0xFFF8C762),
    codeType = Color(0xFF82D2CE),
)

/**
 * Cursor Dark with true-black chrome. OLED pixels turn off at #000000; cards and inputs sit one step up so they
 * still read as surfaces against the canvas. Accents and type stay the dark theme's.
 */
val CursorOledColors = CursorDarkColors.copy(
    canvas = Color.Black,
    sidebar = Color.Black,
    elevated = Color(0xFF0A0A0A),
)

val CursorLightColors = CursorColors(
    isDark = false,
    canvas = Color(0xFFF3F3F3),
    sidebar = Color(0xFFFCFCFC),
    elevated = Color(0xFFFCFCFC),
    base = LightBase,
    textPrimary = LightBase,
    textSecondary = LightBase.copy(alpha = 0.74f),
    textTertiary = LightBase.copy(alpha = 0.60f),
    textQuaternary = LightBase.copy(alpha = 0.36f),
    iconPrimary = LightBase,
    iconSecondary = LightBase.copy(alpha = 0.66f),
    iconTertiary = LightBase.copy(alpha = 0.52f),
    iconQuaternary = LightBase.copy(alpha = 0.28f),
    fillStrong = LightBase.copy(alpha = 0.20f),
    fillMedium = LightBase.copy(alpha = 0.14f),
    fill = LightBase.copy(alpha = 0.08f),
    fillSoft = LightBase.copy(alpha = 0.06f),
    fillFaint = LightBase.copy(alpha = 0.04f),
    fillActive = LightBase.copy(alpha = 0.08f),
    strokeStrong = LightBase.copy(alpha = 0.20f),
    stroke = LightBase.copy(alpha = 0.12f),
    strokeSubtle = LightBase.copy(alpha = 0.08f),
    strokeFaint = LightBase.copy(alpha = 0.04f),
    focus = LightBase.copy(alpha = 0.20f),
    accent = Color(0xFF2778C1),
    onAccent = Color(0xFFFCFCFC),
    link = Color(0xFF0064B0),
    badge = Color(0xFFF3F3F3),
    onBadge = LightBase.copy(alpha = 0.66f),
    green = Color(0xFF00854C),
    gitAdded = Color(0xFF007041),
    gitRemoved = Color(0xFFBE1744),
    gitModified = Color(0xFFA46700),
    red = Color(0xFFCE405B),
    orange = Color(0xFFCD4500),
    blue = Color(0xFF2778C1),
    purple = Color(0xFF7565CC),
    cyan = Color(0xFF176C74),
    yellow = Color(0xFFA46700),
    magenta = Color(0xFF92156A),
    brand = Brand,
    unreadDot = Color(0xFF2778C1),
    codeString = Color(0xFF007041),
    codeFunction = Color(0xFFA46700),
    codeNumber = Color(0xFFCD4500),
    codeType = Color(0xFF176C74),
)

val LocalCursorColors = staticCompositionLocalOf { CursorDarkColors }
