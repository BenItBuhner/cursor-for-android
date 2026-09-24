package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.ModePills.Pill
import com.cursorforandroid.ui.components.pressKey
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.view.KeyEvent as NativeKeyEvent

/**
 * The composer's modes and models as the keyboard and the `/` popover reach them, docked where a chat docks it:
 * Shift+Tab stepping from no mode through Plan, Debug, Multitask and Ask and back to none, a frame after each press;
 * and the popover listing the commands, the modes and the catalog's models under their headers in both themes, the
 * models narrowed by a name ("/Opus 5") with the current one checked and a keyboard's highlight on the best match,
 * a worn mode checked, and parameter words spelling a variant beside the model's name; and the "+" menu opening on
 * Plan, the one mode it offers, off and (light) on. Written to `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentModesScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private val opus: ModelOption = LiveModelCatalog.model("claude-opus-5.5")
    private var value by mutableStateOf("")
    private var modePill by mutableStateOf<Pill?>(null)
    private var model by mutableStateOf<ModelChoice?>(ModelChoice(opus, opus.defaultVariant))

    private val field get() = compose.onNode(hasSetTextAction())

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(mode: ThemeMode) {
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = CursorDimens.composerGutter)
                                .padding(bottom = CursorDimens.composerBottomGap)
                                .testTag("composer"),
                        ) {
                            ComposerBox(
                                value = value,
                                onValueChange = { value = it },
                                placeholder = "Follow up…",
                                onSend = {},
                                plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                                modelLabel = model?.label,
                                onModel = {},
                                modePill = modePill,
                                onModePill = { modePill = it },
                                extendedModes = true,
                                models = LiveModelCatalog.models,
                                currentModel = model,
                                onPickModel = { model = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun captureComposer(name: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("composer").captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScreen(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun shiftTabCycle() {
        show(ThemeMode.Dark)
        field.performTextInput("Fix the cent the checkout total loses after the coupon step")
        listOf(
            "484_mode_cycle_1_plan" to { modePill == Pill.Plan },
            "485_mode_cycle_2_debug" to { modePill == Pill.Debug },
            "486_mode_cycle_3_multitask" to { value.startsWith("/multitask ") },
            "487_mode_cycle_4_ask" to { modePill == Pill.Ask },
            "488_mode_cycle_5_none" to { modePill == null && !value.startsWith("/") },
        ).forEach { (name, reached) ->
            field.pressKey(NativeKeyEvent.KEYCODE_TAB, shift = true)
            compose.waitUntil(10_000) { reached() }
            captureComposer(name)
        }
    }

    private fun open(mode: ThemeMode, typed: String, settled: String, keyboard: Boolean = false) {
        show(mode)
        field.performTextInput(typed)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("Slash commands")).fetchSemanticsNodes().isNotEmpty() && shown(settled)
        }
        // A key from a physical keyboard (Shift alone) shows the row its Enter would pick.
        if (keyboard) field.pressKey(NativeKeyEvent.KEYCODE_SHIFT_LEFT)
    }

    private fun popover(mode: ThemeMode, typed: String, name: String, settled: String, keyboard: Boolean = false) {
        open(mode, typed, settled, keyboard)
        captureScreen(name)
    }

    @Test
    fun slashModesAndModelsDark() = popover(ThemeMode.Dark, "/", "490_slash_modes_models_dark", settled = "Claude Opus 5.5")

    @Test
    @Config(qualifiers = LIGHT)
    fun slashModesAndModelsLight() = popover(ThemeMode.Light, "/", "491_slash_modes_models_light", settled = "Claude Opus 5.5")

    /** Scrolled down past the commands: every mode, and the models with the one in use first and checked. */
    @Test
    fun slashModesAndModelsScrolled() {
        open(ThemeMode.Dark, "/", settled = "Claude Opus 5.5")
        compose.onNodeWithText("Show ${LiveModelCatalog.models.size - 4} more").performScrollTo()
        captureScreen("496_slash_modes_models_scrolled_dark")
    }

    @Test
    fun slashModelSearchDark() = popover(ThemeMode.Dark, "/Opus 5", "492_slash_model_search_dark", settled = "Claude Opus 5", keyboard = true)

    @Test
    @Config(qualifiers = LIGHT)
    fun slashModelSearchLight() = popover(ThemeMode.Light, "/Opus 5", "493_slash_model_search_light", settled = "Claude Opus 5", keyboard = true)

    @Test
    fun slashModeWorn() {
        modePill = Pill.Debug
        popover(ThemeMode.Dark, "/de", "494_slash_mode_worn_dark", settled = "Pinpoint the root cause of an issue")
    }

    @Test
    fun slashModelVariant() =
        popover(ThemeMode.Dark, "Fix the rounding /opus 5.5 max fast", "495_slash_model_variant_dark", settled = "Max effort · Fast")

    private fun plusMenu(mode: ThemeMode, name: String) {
        show(mode)
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitUntil(10_000) { shown(Pill.Plan.description) }
        captureScreen(name)
    }

    @Test
    fun plusMenuPlanDark() = plusMenu(ThemeMode.Dark, "497_plus_menu_plan_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun plusMenuPlanOnLight() {
        modePill = Pill.Plan
        plusMenu(ThemeMode.Light, "498_plus_menu_plan_on_light")
    }

    private companion object {
        const val LIGHT = "w411dp-h914dp-notnight-420dpi"
    }
}
