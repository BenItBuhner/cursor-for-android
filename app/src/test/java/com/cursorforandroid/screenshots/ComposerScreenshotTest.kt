package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Isolated composer frames so a padding change can be judged against the footer discs without walking the
 * whole demo app. Same device qualifiers as [AppScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(
        mode: ThemeMode,
        value: String,
        placeholder: String,
        minLines: Int,
    ) {
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ComposerPreview(value = value, placeholder = placeholder, minLines = minLines)
                }
            }
        }
    }

    @Test
    fun homePlaceholder() {
        show(
            mode = ThemeMode.Dark,
            value = "",
            placeholder = "Ask Cursor to build, fix bugs, explore",
            minLines = 3,
        )
        capture("29_composer_placeholder")
    }

    @Test
    fun homeTyped() {
        show(
            mode = ThemeMode.Dark,
            value = "Fix the composer padding so the placeholder lines up with the plus button.",
            placeholder = "Ask Cursor to build, fix bugs, explore",
            minLines = 3,
        )
        capture("30_composer_typed")
    }

    @Test
    fun followUp() {
        show(
            mode = ThemeMode.Dark,
            value = "",
            placeholder = "Follow up…",
            minLines = 1,
        )
        capture("31_composer_follow_up")
    }

    @Test
    fun homePlaceholderLight() {
        show(
            mode = ThemeMode.Light,
            value = "",
            placeholder = "Ask Cursor to build, fix bugs, explore",
            minLines = 3,
        )
        capture("32_composer_placeholder_light")
    }
}

@Composable
private fun ComposerPreview(value: String, placeholder: String, minLines: Int) {
    val colors = CursorTheme.colors
    Column(Modifier.fillMaxSize().background(colors.canvas).padding(horizontal = 16.dp, vertical = 24.dp)) {
        ComposerBox(
            value = value,
            onValueChange = {},
            placeholder = placeholder,
            onSend = {},
            plusMenu = ComposerMenuActions(onPickFiles = {}),
            modelLabel = "Claude Fable 5.1",
            onModel = {},
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
