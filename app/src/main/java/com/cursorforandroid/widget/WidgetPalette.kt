package com.cursorforandroid.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.cursorforandroid.R
import com.cursorforandroid.domain.CornerStyle
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.WidgetAppearance
import com.cursorforandroid.domain.WidgetTheme
import com.cursorforandroid.ui.theme.CursorColors
import com.cursorforandroid.ui.theme.CursorDarkColors
import com.cursorforandroid.ui.theme.CursorLightColors
import com.cursorforandroid.ui.theme.CursorOledColors
import com.cursorforandroid.ui.theme.ThemeMode

/**
 * [CursorColors] as Glance colour providers for one widget's [WidgetAppearance]: Cursor Dark and Cursor Light are
 * fixed colours; "Match system" is a day / night pair the launcher resolves itself, so the widget flips with the
 * system theme even while the app is not running. OLED black replaces the dark side of either pair. Only the tokens
 * the widget draws are exposed — the sidebar surface and the text / icon / state colours of its rows.
 */
class WidgetPalette private constructor(private val mode: ThemeMode, private val oledBlack: Boolean, private val opacity: Float) {

    private val darkColors: CursorColors = if (oledBlack) CursorOledColors else CursorDarkColors

    private fun token(pick: (CursorColors) -> Color): ColorProvider = when (mode) {
        ThemeMode.Dark -> ColorProvider(pick(darkColors))
        ThemeMode.Light -> ColorProvider(pick(CursorLightColors))
        ThemeMode.System -> ColorProvider(day = pick(CursorLightColors), night = pick(darkColors))
    }

    /** `--cursor-sidebar`, at the widget's opacity: the wallpaper shows through what is left. */
    val surface: ColorProvider = token { it.sidebar.copy(alpha = opacity) }
    val textPrimary: ColorProvider = token { it.textPrimary }
    val textTertiary: ColorProvider = token { it.textTertiary }
    val textQuaternary: ColorProvider = token { it.textQuaternary }
    val iconPrimary: ColorProvider = token { it.iconPrimary }
    val iconSecondary: ColorProvider = token { it.iconSecondary }
    val iconTertiary: ColorProvider = token { it.iconTertiary }
    val iconQuaternary: ColorProvider = token { it.iconQuaternary }
    val unreadDot: ColorProvider = token { it.unreadDot }
    val red: ColorProvider = token { it.red }
    private val textSecondary: ColorProvider = token { it.textSecondary }
    private val gitAdded: ColorProvider = token { it.gitAdded }
    private val purple: ColorProvider = token { it.purple }

    /**
     * The layout that plays the "working" glyph's frame animation in this palette's [iconSecondary]: a ProgressBar's
     * tint is a resource, so the three ways the token can resolve are three layouts (see
     * `res/layout/widget_working_indicator*.xml`).
     */
    val workingIndicatorLayout: Int = when (mode) {
        ThemeMode.Dark -> R.layout.widget_working_indicator_dark
        ThemeMode.Light -> R.layout.widget_working_indicator_light
        ThemeMode.System -> R.layout.widget_working_indicator
    }

    /** `--cursor-sidebar` at full strength, whatever the widget's opacity: the ring that sets a badge off its icon. */
    val halo: ColorProvider = token { it.sidebar }

    /** A Project's tone, as the sidebar paints its icon ([CursorColors.projectTone]): the secondary icon tone for `default`. */
    fun projectTone(colorId: String?): ColorProvider = token { it.projectTone(colorId) }

    /** [projectTone] as a day and a night colour, for the working glyph's tint (see [tints]). */
    fun projectToneTints(colorId: String?): Pair<Color, Color> = tints { it.projectTone(colorId) }

    /** [iconTertiary] as a day and a night colour, for the working glyph's tint (see [tints]). */
    val iconTertiaryTints: Pair<Color, Color> get() = tints { it.iconTertiary }

    /**
     * A token as the day and night colours a view's tint list is set to from Android 12 (`RemoteViews`'
     * night-aware setters): the same colour twice for a fixed theme, the pair for "Match system".
     */
    private fun tints(pick: (CursorColors) -> Color): Pair<Color, Color> = when (mode) {
        ThemeMode.Dark -> pick(darkColors).let { it to it }
        ThemeMode.Light -> pick(CursorLightColors).let { it to it }
        ThemeMode.System -> pick(CursorLightColors) to pick(darkColors)
    }

    /** The corner button's disc for [style]. */
    fun cornerFill(style: CornerStyle): ColorProvider = when (style) {
        CornerStyle.White -> ColorProvider(Color.White)
        CornerStyle.Tinted -> token { it.accent }
        CornerStyle.Glass -> token { it.fillMedium }
    }

    /** The corner button's glyph on [cornerFill]. */
    fun cornerGlyph(style: CornerStyle): ColorProvider = when (style) {
        CornerStyle.White -> ColorProvider(CursorLightColors.iconPrimary)
        CornerStyle.Tinted -> token { it.onAccent }
        CornerStyle.Glass -> iconSecondary
    }

    /** The colour a pull request is shown in — the sidebar's `pullRequestTint` (Primitives.kt), token for token. */
    fun pullRequestTint(state: PullRequestState?): ColorProvider = when (state) {
        PullRequestState.Open -> gitAdded
        PullRequestState.Draft -> textTertiary
        PullRequestState.Merged -> purple
        PullRequestState.Closed -> red
        null -> textSecondary
    }

    companion object {
        /** The app's own theme, as every widget followed before the choice existed. */
        fun forMode(mode: ThemeMode, oledBlack: Boolean = false, opacity: Float = 1f): WidgetPalette = WidgetPalette(mode, oledBlack, opacity)

        /**
         * The palette for one widget's [appearance]: its own theme when it has one, else the app's ([appMode] and
         * [appOledBlack], the settings every widget followed before this was a choice).
         */
        fun forAppearance(appearance: WidgetAppearance, appMode: ThemeMode, appOledBlack: Boolean): WidgetPalette {
            val (mode, oledBlack) = appearance.theme.resolve(appMode, appOledBlack)
            return WidgetPalette(mode, oledBlack, appearance.opacityFraction)
        }
    }
}

/**
 * The colours a widget's [WidgetTheme] stands for, as every widget kind reads it: a [ThemeMode] — one side, or
 * [ThemeMode.System] for a day / night pair the launcher picks from — and whether the dark side is OLED black.
 * [WidgetTheme.App] is the app's own setting ([appMode], [appOledBlack]).
 */
internal fun WidgetTheme.resolve(appMode: ThemeMode, appOledBlack: Boolean): Pair<ThemeMode, Boolean> = when (this) {
    WidgetTheme.App -> appMode to appOledBlack
    WidgetTheme.System -> ThemeMode.System to false
    WidgetTheme.Light -> ThemeMode.Light to false
    WidgetTheme.Dark -> ThemeMode.Dark to false
    WidgetTheme.Oled -> ThemeMode.Dark to true
}
