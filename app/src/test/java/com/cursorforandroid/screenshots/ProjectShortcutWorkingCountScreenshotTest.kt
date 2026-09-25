package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.home.ProjectShortcutGrid
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The New Chat page's Project shortcuts in each state their top corner shows — one agent working (the glyph alone),
 * two and twelve (the count before the glyph, in the Project's colour), unread, failed, and at rest — each as tall as
 * its icon row and its name: on a phone in both themes, three to a row at the tablet's column width, and on a phone
 * with the text scaled up, where the count still sits on the glyph's line.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectShortcutWorkingCountScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun capture(name: String, mode: ThemeMode, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CursorTheme(mode = mode) {
                    Box(Modifier.testTag(FRAME).fillMaxWidth().background(CursorTheme.colors.canvas).padding(16.dp)) {
                        ProjectShortcutGrid(
                            NewChatHomeFixtures.shortcutStates(),
                            Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth().padding(horizontal = CursorDimens.recentRowInset),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(FRAME).captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun phoneDark() = capture("510_project_shortcuts_working_count_phone_dark", ThemeMode.Dark)

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-notnight-420dpi")
    fun phoneLight() = capture("511_project_shortcuts_working_count_phone_light", ThemeMode.Light)

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun tabletDark() = capture("512_project_shortcuts_working_count_tablet_dark", ThemeMode.Dark)

    @Test
    fun phoneLargeText() = capture("513_project_shortcuts_working_count_phone_large_text", ThemeMode.Dark, fontScale = 1.3f)

    private companion object {
        const val FRAME = "project_shortcuts_frame"
    }
}
