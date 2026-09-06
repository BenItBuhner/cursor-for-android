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
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val colors = if (dark) CursorDarkColors else CursorLightColors
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
                bodyLarge = typography.body,
                bodyMedium = typography.secondary,
                bodySmall = typography.caption,
                titleLarge = typography.title,
                titleMedium = typography.bodyMedium,
                titleSmall = typography.sectionLabel,
                labelLarge = typography.bodyMedium,
                labelMedium = typography.secondary,
                labelSmall = typography.caption,
            ),
            shapes = Shapes(
                extraSmall = shapes.sm,
                small = shapes.md,
                medium = shapes.lg,
                large = shapes.xl,
                extraLarge = shapes.sheet,
            ),
            content = content,
        )
    }
}

/**
 * Material components (drawer sheet, bottom sheet, switch, ripple) read from the M3 scheme, so map the
 * Cursor tokens onto it. Anything visible is still drawn with [CursorColors] directly.
 */
private fun CursorColors.toMaterialScheme(): ColorScheme {
    val onSurfaceOpaque = textPrimary.compositeOver(canvas)
    return if (isDark) {
        darkColorScheme(
            primary = accentBlue,
            onPrimary = onAccent,
            secondary = cyan,
            onSecondary = onAccent,
            background = canvas,
            onBackground = onSurfaceOpaque,
            surface = canvas,
            onSurface = onSurfaceOpaque,
            surfaceVariant = surface,
            onSurfaceVariant = textSecondary.compositeOver(canvas),
            surfaceContainer = surface,
            surfaceContainerLow = surface,
            surfaceContainerLowest = canvas,
            surfaceContainerHigh = surfaceRaised,
            surfaceContainerHighest = surfaceRaised,
            outline = borderSubtle.compositeOver(canvas),
            outlineVariant = borderSubtle.compositeOver(canvas),
            error = danger,
            onError = onAccent,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = accentBlue,
            onPrimary = onAccent,
            secondary = cyan,
            onSecondary = Color.White,
            background = canvas,
            onBackground = onSurfaceOpaque,
            surface = canvas,
            onSurface = onSurfaceOpaque,
            surfaceVariant = surface,
            onSurfaceVariant = textSecondary.compositeOver(canvas),
            surfaceContainer = surface,
            surfaceContainerLow = surface,
            surfaceContainerLowest = canvas,
            surfaceContainerHigh = surfaceRaised,
            surfaceContainerHighest = surfaceRaised,
            outline = borderSubtle.compositeOver(canvas),
            outlineVariant = borderSubtle.compositeOver(canvas),
            error = danger,
            onError = Color.White,
            scrim = Color.Black,
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

@Composable
@ReadOnlyComposable
fun isCursorDark(): Boolean = LocalCursorColors.current.isDark
