package com.cursorforandroid.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.cursorforandroid.R
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.ui.theme.CursorColors
import com.cursorforandroid.ui.theme.CursorDarkColors
import com.cursorforandroid.ui.theme.CursorLightColors
import com.cursorforandroid.ui.theme.CursorOledColors
import com.cursorforandroid.ui.theme.ThemeMode

/**
 * [CursorColors] as Glance colour providers, following the app's theme setting: Cursor Dark and Cursor Light are
 * fixed colours; "Match system" is a day / night pair the launcher resolves itself, so the widget flips with the
 * system theme even while the app is not running. OLED black replaces the dark side of either pair. Only the tokens
 * the widget draws are exposed — the sidebar surface and the text / icon / state colours of its rows.
 */
class WidgetPalette private constructor(private val mode: ThemeMode, private val oledBlack: Boolean) {

    private val darkColors: CursorColors = if (oledBlack) CursorOledColors else CursorDarkColors

    private fun token(pick: (CursorColors) -> Color): ColorProvider = when (mode) {
        ThemeMode.Dark -> ColorProvider(pick(darkColors))
        ThemeMode.Light -> ColorProvider(pick(CursorLightColors))
        ThemeMode.System -> ColorProvider(day = pick(CursorLightColors), night = pick(darkColors))
    }

    /** `--cursor-sidebar`. */
    val surface: ColorProvider = token { it.sidebar }
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

    /** The colour a pull request is shown in — the sidebar's `pullRequestTint` (Primitives.kt), token for token. */
    fun pullRequestTint(state: PullRequestState?): ColorProvider = when (state) {
        PullRequestState.Open -> gitAdded
        PullRequestState.Draft -> textTertiary
        PullRequestState.Merged -> purple
        PullRequestState.Closed -> red
        null -> textSecondary
    }

    companion object {
        fun forMode(mode: ThemeMode, oledBlack: Boolean = false): WidgetPalette = WidgetPalette(mode, oledBlack)
    }
}
