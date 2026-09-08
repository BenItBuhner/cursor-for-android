package com.cursorforandroid.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode { System, Dark, Light }

/** Colours for [mode], applying OLED black only while the resolved theme is dark. */
fun cursorColorsFor(mode: ThemeMode, oledBlack: Boolean, systemInDarkTheme: Boolean): CursorColors {
    val dark = when (mode) {
        ThemeMode.System -> systemInDarkTheme
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    return when {
        !dark -> CursorLightColors
        oledBlack -> CursorOledColors
        else -> CursorDarkColors
    }
}

object CursorTheme {
    val colors: CursorColors
        @Composable @ReadOnlyComposable get() = LocalCursorColors.current
    val typography: CursorTypography
        @Composable @ReadOnlyComposable get() = LocalCursorTypography.current
    val shapes: CursorShapes
        @Composable @ReadOnlyComposable get() = LocalCursorShapes.current
}

@Composable
fun CursorTheme(
    mode: ThemeMode = ThemeMode.System,
    oledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = cursorColorsFor(mode, oledBlack, isSystemInDarkTheme())
    val dark = colors.isDark
    val typography = CursorTypography()
    val shapes = CursorShapes()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(
        LocalCursorColors provides colors,
        LocalCursorTypography provides typography,
        LocalCursorShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = Typography(
                bodyLarge = typography.row,
                bodyMedium = typography.base,
                bodySmall = typography.small,
                titleLarge = typography.pageTitle,
                titleMedium = typography.title,
                titleSmall = typography.baseMedium,
                labelLarge = typography.baseMedium,
                labelMedium = typography.small,
                labelSmall = typography.tiny,
            ),
            shapes = Shapes(
                extraSmall = shapes.sm,
                small = shapes.base,
                medium = shapes.lg,
                large = shapes.xl,
                extraLarge = shapes.sheet,
            ),
            content = content,
        )
    }
}

/**
 * Material components (drawer sheet, bottom sheet, menus, ripple) read the M3 scheme, so map the Cursor tokens
 * onto it. Everything visible is still drawn with [CursorColors] directly.
 */
private fun CursorColors.toMaterialScheme(): ColorScheme {
    val onSurface = textPrimary.compositeOver(canvas)
    val onSurfaceVariant = textSecondary.compositeOver(canvas)
    val outline = stroke.compositeOver(canvas)
    return if (isDark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent, secondary = cyan, onSecondary = onAccent,
            background = canvas, onBackground = onSurface, surface = canvas, onSurface = onSurface,
            surfaceVariant = elevated, onSurfaceVariant = onSurfaceVariant,
            surfaceContainer = elevated, surfaceContainerLow = elevated, surfaceContainerLowest = canvas,
            surfaceContainerHigh = elevated, surfaceContainerHighest = elevated,
            outline = outline, outlineVariant = strokeSubtle.compositeOver(canvas),
            error = red, onError = onAccent, scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent, secondary = cyan, onSecondary = Color.White,
            background = canvas, onBackground = onSurface, surface = canvas, onSurface = onSurface,
            surfaceVariant = elevated, onSurfaceVariant = onSurfaceVariant,
            surfaceContainer = elevated, surfaceContainerLow = elevated, surfaceContainerLowest = canvas,
            surfaceContainerHigh = elevated, surfaceContainerHighest = elevated,
            outline = outline, outlineVariant = strokeSubtle.compositeOver(canvas),
            error = red, onError = Color.White, scrim = Color.Black,
        )
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}
