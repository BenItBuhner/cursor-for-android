package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * The composer's expand button once a long prompt scrolls inside it, and the composer stretched up over the
 * transcript it docks under. Same device qualifiers as [AppScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerExpandScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(mode: ThemeMode) {
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    val colors = CursorTheme.colors
                    Column(Modifier.fillMaxSize().background(colors.canvas).padding(16.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                            Text("Transcript", color = colors.textSecondary)
                        }
                        ComposerBox(
                            value = PROMPT,
                            onValueChange = {},
                            placeholder = "Follow up…",
                            onSend = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun expand() {
        compose.onNode(hasTestTag("composer-expand"), useUnmergedTree = true).performClick()
    }

    @Test
    fun offered() {
        show(ThemeMode.Dark)
        capture("650_composer_expand_offered")
    }

    @Test
    fun expanded() {
        show(ThemeMode.Dark)
        expand()
        capture("651_composer_expand_expanded")
    }

    @Test
    fun expandedLight() {
        show(ThemeMode.Light)
        expand()
        capture("652_composer_expand_expanded_light")
    }

    private companion object {
        val PROMPT = (1..18).joinToString("\n") { "$it. Step $it of making resume survive a dropped connection." }
    }
}
