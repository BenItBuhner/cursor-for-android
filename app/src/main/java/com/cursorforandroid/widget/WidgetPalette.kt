package com.cursorforandroid.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.cursorforandroid.ui.theme.CursorColors
import com.cursorforandroid.ui.theme.CursorDarkColors
import com.cursorforandroid.ui.theme.CursorLightColors
import com.cursorforandroid.ui.theme.ThemeMode

/**
 * [CursorColors] as Glance colour providers, following the app's theme setting: Cursor Dark and Cursor Light are
 * fixed colours; "Match system" is a day / night pair the launcher resolves itself, so the widget flips with the
 * system theme even while the app is not running. Only the tokens the widget draws are exposed — the sidebar
 * surface and the text / icon / state colours of its rows.
 */
class WidgetPalette private constructor(private val mode: ThemeMode) {

    private fun token(pick: (CursorColors) -> Color): ColorProvider = when (mode) {
        ThemeMode.Dark -> ColorProvider(pick(CursorDarkColors))
        ThemeMode.Light -> ColorProvider(pick(CursorLightColors))
        ThemeMode.System -> ColorProvider(day = pick(CursorLightColors), night = pick(CursorDarkColors))
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
    val gitAdded: ColorProvider = token { it.gitAdded }

    companion object {
        fun forMode(mode: ThemeMode): WidgetPalette = WidgetPalette(mode)
    }
}
