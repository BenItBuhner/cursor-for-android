package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.ModePills
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
 * The home composer alone, in each of the states its footer and text can take: nothing on, the Plan pill (amber,
 * beside a `/command` in the brand orange so the two hues sit together), the Multitask pill (violet), a `/command`
 * painted in the Cursor orange on its own, and — in the light theme, with a long model name — the Plan pill with the
 * model chip giving way to it. Plan and Multitask are one slot, so no state wears both. Written to `screenshots/`
 * beside the walkthrough; CI compares them pixel for pixel (`verifyRoborazziDebug`), and `recordRoborazziDebug`
 * re-records them on purpose. The composer's padding and placeholder frames are [ComposerScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerPillsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private data class Scene(
        val value: String = "",
        val planMode: Boolean = false,
        /** The Extended-mode pills: Ask or Debug worn instead of Plan. */
        val modePill: ModePills.Pill? = null,
        val modelLabel: String = "Claude Fable 5.1",
        val mode: ThemeMode = ThemeMode.Dark,
    )

    private var scene by mutableStateOf(Scene())

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Composer(scene: Scene) {
        CursorTheme(mode = scene.mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(16.dp).testTag("scene")) {
                    ComposerBox(
                        value = scene.value,
                        onValueChange = {},
                        placeholder = "Ask Cursor to build, fix bugs, explore",
                        onSend = {},
                        minLines = 3,
                        plusMenu = ComposerMenuActions(onPickFiles = {}),
                        modelLabel = scene.modelLabel,
                        onModel = {},
                        modePill = scene.modePill ?: if (scene.planMode) ModePills.Pill.Plan else null,
                        onModePill = {},
                        extendedModes = scene.modePill != null,
                    )
                }
            }
        }
    }

    private fun capture(name: String, settledText: String) {
        // The field adopts a new value a frame after the owner changes it; wait for the text before the frame is taken.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(settledText, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun composerStates() {
        compose.setContent { Composer(scene) }

        // Nothing on: "+", the model chip beside send, the placeholder.
        capture("33_composer_no_pill", settledText = "Ask Cursor to build")

        // Plan mode, as the model picker's toggle or `/plan` leaves it: an amber pill right of "+", the chip the model's
        // name alone, and a `/command` in the brand orange beside it in the text.
        scene = Scene(planMode = true, value = "/review Work out how the sidebar should group projects")
        capture("34_composer_plan_pill", settledText = "Work out how")

        // A `/multitask` prompt: the owner still holds the token in front; the field shows the request beside the pill.
        scene = Scene(value = "/multitask Fan the flaky suites out to subagents and land the fixes")
        capture("35_composer_multitask_pill", settledText = "Fan the flaky")

        // Every other command stays in the text, painted in the Cursor orange.
        scene = Scene(value = "/review Ship the release notes, then /subscribe to the checks")
        capture("36_composer_slash_highlight", settledText = "Ship the release")

        // Light theme: the Plan pill, a long model name and a command at once: the chip ellipsises, send stays put.
        scene = Scene(
            value = "/goal Ship the 0.2.0 release notes",
            planMode = true,
            modelLabel = "Claude Fable 5.1 Thinking (Max) with the extended context window",
            mode = ThemeMode.Light,
        )
        capture("37_composer_pills_light", settledText = "Ship the 0.2.0")
    }

    /** Extended mode's two modes, each worn like Plan: Ask in blue, Debug in teal; one pill at a time. */
    @Test
    fun extendedModePills() {
        compose.setContent { Composer(scene) }

        scene = Scene(modePill = ModePills.Pill.Ask, value = "Why does the widget repaint on every list refresh?")
        capture("47_composer_ask_pill", settledText = "Why does the widget")

        scene = Scene(modePill = ModePills.Pill.Debug, value = "The sidebar loses its scroll position after a rotation")
        capture("48_composer_debug_pill", settledText = "The sidebar loses")
    }
}
