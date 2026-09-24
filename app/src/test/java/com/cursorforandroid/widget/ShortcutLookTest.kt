package com.cursorforandroid.widget

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.glance.unit.ColorProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ShortcutStyle
import com.cursorforandroid.domain.WidgetAppearance
import com.cursorforandroid.domain.WidgetTheme
import com.cursorforandroid.ui.theme.CursorOledColors
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shortcut widget's colours follow its theme as the Chats widget's do: the app's theme under "App", the
 * system's day / night under "System", a pinned side otherwise — for every look, the default solid disc included,
 * which was white in every theme.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShortcutLookTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val day = night(false)
    private val night = night(true)

    @Test
    fun `the default look follows the app's theme, dark in a dark app and light in a light one`() {
        val appearance = WidgetAppearance()
        val dark = ShortcutLook.of(ShortcutStyle.Default, appearance, ThemeMode.Dark, appOledBlack = false)
        val light = ShortcutLook.of(ShortcutStyle.Default, appearance, ThemeMode.Light, appOledBlack = false)
        // Whatever the launcher's own mode: the app's theme is pinned.
        listOf(day, night).forEach { home ->
            assertThat(dark.fill!!.on(home).luminance()).isLessThan(0.2f)
            assertThat(dark.glyph.on(home).luminance()).isGreaterThan(0.6f)
            assertThat(light.fill!!.on(home)).isEqualTo(Color.White)
            assertThat(light.glyph.on(home).luminance()).isLessThan(0.2f)
        }
    }

    @Test
    fun `an app that follows the system gives the launcher a day and a night side to pick from`() {
        val look = ShortcutLook.of(ShortcutStyle.Default, WidgetAppearance(), ThemeMode.System, appOledBlack = false)
        assertThat(look.fill!!.on(day)).isEqualTo(Color.White)
        assertThat(look.fill!!.on(night).luminance()).isLessThan(0.2f)
        val system = ShortcutLook.of(ShortcutStyle.Default, WidgetAppearance(theme = WidgetTheme.System), ThemeMode.Light, appOledBlack = true)
        assertThat(system.fill!!.on(night)).isNotEqualTo(Color.Black)
        assertThat(system.fill!!.on(night).luminance()).isLessThan(0.2f)
    }

    @Test
    fun `every look takes a pinned theme's side, whatever the launcher's mode`() {
        ShortcutStyle.entries.forEach { style ->
            val dark = ShortcutLook.of(style, WidgetAppearance(theme = WidgetTheme.Dark), ThemeMode.Light, appOledBlack = false)
            val light = ShortcutLook.of(style, WidgetAppearance(theme = WidgetTheme.Light), ThemeMode.Dark, appOledBlack = false)
            listOf(day, night).forEach { home ->
                assertWithMessage("$style glyph, dark theme").that(dark.glyph.on(home).luminance()).isGreaterThan(0.5f)
                assertWithMessage("$style text, dark theme").that(dark.text.on(home).copy(alpha = 1f).luminance()).isGreaterThan(0.5f)
                assertWithMessage("$style glyph, light theme").that(light.glyph.on(home).luminance()).isLessThan(0.5f)
                assertWithMessage("$style text, light theme").that(light.text.on(home).copy(alpha = 1f).luminance()).isLessThan(0.5f)
            }
        }
    }

    @Test
    fun `OLED black is black under the solid disc, the composer's OLED surface under the tint, and a hairline keeps the disc's edge`() {
        val oled = WidgetAppearance(theme = WidgetTheme.Oled)
        val solid = ShortcutLook.of(ShortcutStyle.Solid, oled, ThemeMode.Light, appOledBlack = false)
        assertThat(solid.fill!!.on(day)).isEqualTo(Color.Black)
        assertThat(solid.ring!!.on(day).alpha).isGreaterThan(0f)
        assertThat(ShortcutLook.of(ShortcutStyle.Tinted, oled, ThemeMode.Light, appOledBlack = false).fill!!.on(day)).isEqualTo(CursorOledColors.elevated)
    }

    @Test
    fun `the opacity is how much of the wallpaper shows through the disc, and icon only paints nothing`() {
        val look = ShortcutLook.of(ShortcutStyle.Solid, WidgetAppearance(theme = WidgetTheme.Light, opacity = 60), ThemeMode.Light, appOledBlack = false)
        assertThat(look.fill!!.on(day).alpha).isWithin(0.01f).of(0.6f)
        val bare = ShortcutLook.of(ShortcutStyle.IconOnly, WidgetAppearance(), ThemeMode.Dark, appOledBlack = false)
        assertThat(bare.fill).isNull()
        assertThat(bare.ring).isNull()
    }

    @Test
    fun `a swatch paints with the colours the widget takes`() {
        ShortcutStyle.entries.forEach { style ->
            val look = ShortcutLook.of(style, WidgetAppearance(theme = WidgetTheme.Dark), ThemeMode.Light, appOledBlack = false)
            val tones = ShortcutTones.of(style, dark = true, oledBlack = false, opacity = 1f)
            assertWithMessage("$style").that(look.glyph.on(day)).isEqualTo(tones.glyph)
            assertWithMessage("$style").that(look.fill?.on(day)).isEqualTo(tones.fill)
        }
    }

    private fun ColorProvider.on(context: Context): Color = getColor(context)

    private fun night(on: Boolean): Context {
        val configuration = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (on) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return app.createConfigurationContext(configuration)
    }
}
