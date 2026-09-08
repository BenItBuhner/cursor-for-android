package com.cursorforandroid.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CursorThemeTest {

    @Test
    fun `oled black replaces charcoal while Cursor Dark is on`() {
        val colors = cursorColorsFor(ThemeMode.Dark, oledBlack = true, systemInDarkTheme = false)
        assertThat(colors).isEqualTo(CursorOledColors)
        assertThat(colors.canvas).isEqualTo(Color.Black)
        assertThat(colors.sidebar).isEqualTo(Color.Black)
        assertThat(colors.isDark).isTrue()
    }

    @Test
    fun `oled black applies when matching a dark system`() {
        assertThat(cursorColorsFor(ThemeMode.System, oledBlack = true, systemInDarkTheme = true))
            .isEqualTo(CursorOledColors)
    }

    @Test
    fun `oled black stays off when matching a light system`() {
        assertThat(cursorColorsFor(ThemeMode.System, oledBlack = true, systemInDarkTheme = false))
            .isEqualTo(CursorLightColors)
    }

    @Test
    fun `oled black is ignored while Cursor Light is on`() {
        assertThat(cursorColorsFor(ThemeMode.Light, oledBlack = true, systemInDarkTheme = true))
            .isEqualTo(CursorLightColors)
    }

    @Test
    fun `dark without oled stays Cursor Dark`() {
        assertThat(cursorColorsFor(ThemeMode.Dark, oledBlack = false, systemInDarkTheme = true))
            .isEqualTo(CursorDarkColors)
        assertThat(CursorDarkColors.canvas).isEqualTo(Color(0xFF141414))
    }
}
