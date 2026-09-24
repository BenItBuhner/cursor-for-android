package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.components.ModePills.Pill
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import android.view.KeyEvent as NativeKeyEvent

/**
 * The composer's modes and models from a keyboard and the `/` popover, as on the desktop: Shift+Tab steps through the
 * modes in the desktop's order; the popover lists the modes under "Modes" and the catalog's models under "Models",
 * narrowed by a model's name as it is typed, and a pick puts the mode on or sets the model with its parameters.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerModesAndModelsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opus: ModelOption = LiveModelCatalog.model("claude-opus-5.5")
    private var value by mutableStateOf("")
    private var modePill by mutableStateOf<Pill?>(null)
    private var model by mutableStateOf<ModelChoice?>(ModelChoice(opus, opus.defaultVariant))
    private val sent = mutableListOf<String>()

    private fun show(extended: Boolean = true, outside: Boolean = false) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    if (outside) Box(Modifier.size(40.dp).testTag("before").focusable())
                    ComposerBox(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = "Ask",
                        onSend = { sent += value },
                        plusMenu = ComposerMenuActions(onPickMedia = {}),
                        modelLabel = model?.label,
                        onModel = {},
                        modePill = modePill,
                        onModePill = { modePill = it },
                        extendedModes = extended,
                        models = LiveModelCatalog.models,
                        currentModel = model,
                        onPickModel = { model = it },
                    )
                    if (outside) Box(Modifier.size(40.dp).testTag("after").focusable())
                }
            }
        }
    }

    private val field get() = compose.onNode(hasSetTextAction())

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) = compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().size

    /** The pill worn beside "+", by its cross; "none" without one. */
    private fun worn(): String = Pill.entries.firstOrNull { described("Remove ${it.label}") > 0 }?.label ?: "none"

    private fun highlighted(): List<String> = compose.onAllNodes(isSelected()).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    private fun shiftTab() = field.pressKey(NativeKeyEvent.KEYCODE_TAB, shift = true).also { compose.waitForIdle() }

    @Test
    fun `Shift+Tab steps from no mode through Plan, Debug, Multitask and Ask and back, the pill following`() {
        show()
        field.performTextInput("fix the login")

        val steps = List(5) {
            assertThat(shiftTab()).isTrue()
            Triple(worn(), modePill, value)
        }
        assertThat(steps).containsExactly(
            Triple("Plan", Pill.Plan, "fix the login"),
            Triple("Debug", Pill.Debug, "fix the login"),
            // Multitask is the prompt's own token, in front, and puts the mode off.
            Triple("Multitask", null, "/multitask fix the login"),
            Triple("Ask", Pill.Ask, "fix the login"),
            Triple("none", null, "fix the login"),
        ).inOrder()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `without Extended mode the cycle is Plan and Multitask`() {
        show(extended = false)
        val steps = List(3) {
            shiftTab()
            worn()
        }
        assertThat(steps).containsExactly("Plan", "Multitask", "none").inOrder()
    }

    @Test
    fun `Shift+Tab while the popover is up is its pick, and plain Tab and the on-screen keyboard leave the cycle alone`() {
        show()
        // With the popover up, Shift+Tab picks, as #337 made it: the first row, /goal, is completed.
        field.performTextInput("/")
        compose.waitUntil(10_000) { shown("Modes") }
        assertThat(shiftTab()).isTrue()
        compose.waitUntil(10_000) { !shown("Modes") }
        assertThat(value).isEqualTo("/goal ")
        assertThat(worn()).isEqualTo("none")

        field.pressKey(NativeKeyEvent.KEYCODE_TAB)
        field.pressKey(NativeKeyEvent.KEYCODE_TAB, keyboard = Keyboard.OnScreen, shift = true)
        compose.waitForIdle()
        assertThat(worn()).isEqualTo("none")
        assertThat(modePill).isNull()
    }

    @Test
    fun `outside the composer Shift+Tab still moves focus, and Tab still brings it into the field`() {
        show(outside = true)
        compose.onNodeWithTag("before").pressKey(NativeKeyEvent.KEYCODE_TAB)
        field.assertIsFocused()

        compose.onNodeWithTag("after").pressKey(NativeKeyEvent.KEYCODE_TAB, shift = true)
        compose.onNodeWithTag("after").assertIsNotFocused()
        assertThat(worn()).isEqualTo("none")
        assertThat(modePill).isNull()
        assertThat(value).isEmpty()
    }

    @Test
    fun `the popover lists the modes under Modes, a pick puts one on, and picking it again takes it off`() {
        show()
        field.performTextInput("/")
        compose.waitUntil(10_000) { shown("Modes") && shown("Pinpoint the root cause of an issue") }
        assertThat(shown("Commands")).isTrue()
        assertThat(shown("Models")).isTrue()

        compose.onNodeWithText("Pinpoint the root cause of an issue").performClick()
        compose.waitUntil(10_000) { modePill == Pill.Debug }
        assertThat(value).isEmpty()
        assertThat(worn()).isEqualTo("Debug")

        // Worn, its row is checked; Enter on it (the best match for "/deb") takes it off again.
        field.performTextInput("/deb")
        compose.waitUntil(10_000) { described("On") == 1 }
        field.pressEnter()
        compose.waitUntil(10_000) { modePill == null }
        assertThat(value).isEmpty()
        assertThat(worn()).isEqualTo("none")
    }

    @Test
    fun `a model's name after the slash lists the matching models, the current one checked, and Enter sets the one highlighted`() {
        show()
        field.performTextInput("/Opus 5")
        compose.waitUntil(10_000) { shown("Models") && shown("Claude Opus 5") }
        // Nothing else answers to it: no commands, no modes.
        assertThat(shown("Commands")).isFalse()
        assertThat(shown("Modes")).isFalse()
        assertThat(described("Current model")).isEqualTo(1)

        field.pressKey(NativeKeyEvent.KEYCODE_SHIFT_LEFT)
        assertThat(highlighted()).containsExactly("Claude Opus 5")
        assertThat(field.pressEnter()).isTrue()
        compose.waitUntil(10_000) { model?.model?.id == "claude-opus-5-thinking" }
        val picked = LiveModelCatalog.model("claude-opus-5-thinking")
        assertThat(model!!.variant).isEqualTo(picked.defaultVariant)
        assertThat(value).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `parameter words pick the variant through the normaliser, shown beside the name, and a tap sets it`() {
        show()
        field.performTextInput("fix it /opus 5.5 max fast")
        compose.waitUntil(10_000) { shown("Max effort · Fast") }
        compose.onNodeWithText("Max effort · Fast").performClick()
        compose.waitUntil(10_000) { model?.params?.any { it.id == "effort" && it.value == "max" } == true }
        assertThat(model!!.model.id).isEqualTo("claude-opus-5.5")
        assertThat(model!!.params.associate { it.id to it.value }).containsExactly("effort", "max", "fast", "true")
        assertThat(value).isEqualTo("fix it")
    }

    @Test
    fun `a message typed after a command names no model and keeps the popover shut`() {
        show()
        field.performTextInput("/goal fix the login flow")
        compose.waitForIdle()
        assertThat(shown("Models")).isFalse()
        assertThat(described("Slash commands")).isEqualTo(0)
        field.pressEnter()
        assertThat(sent).containsExactly("/goal fix the login flow")
    }

    @Test
    fun `a section cut short opens in full from its Show more row`() {
        show()
        field.performTextInput("/")
        val remaining = LiveModelCatalog.models.size - 4
        compose.waitUntil(10_000) { shown("Show $remaining more") }
        assertThat(shown("Muse Spark 1.3")).isFalse()
        // Below the popover's fold under the commands and the modes.
        compose.onNodeWithText("Show $remaining more").performScrollTo().performClick()
        compose.waitUntil(10_000) { shown("Muse Spark 1.3") }
        assertThat(value).isEqualTo("/")
    }
}
