package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Who holds the composer's field. The rules are that a reader arriving on a screen is not handed a keyboard, that a
 * command picked from the "+" menu gives the field back so the prompt can be finished, and that a composer rebuilt
 * from instance state is left the way it was rather than the way the field happens to start.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val field get() = compose.onNode(hasSetTextAction())

    private fun draft(): String =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun selection(): TextRange? =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    @Test
    fun `arriving on a screen does not take the field`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = "", onValueChange = {}, placeholder = "Ask anything", onSend = {})
            }
        }
        field.assertIsNotFocused()
    }

    @Test
    fun `a command picked from the plus menu hands the field back with the caret after it`() {
        var hoisted by mutableStateOf("")
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = hoisted,
                    onValueChange = { hoisted = it },
                    placeholder = "Ask anything",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickFiles = {}),
                )
            }
        }

        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.onNodeWithText("Multitask").performClick()
        compose.waitForIdle()

        // The command is the owner's: the field shows it as the Multitask pill and stays empty, caret at the start.
        compose.runOnIdle { assertThat(hoisted).isEqualTo("/multitask ") }
        assertThat(draft()).isEmpty()
        assertThat(selection()).isEqualTo(TextRange(0))
        compose.onNodeWithContentDescription("Remove Multitask").assertExists()
        field.assertIsFocused()

        // And carrying on writes the request, which goes out behind the command.
        field.performTextInput("ship it")
        assertThat(draft()).isEqualTo("ship it")
        compose.runOnIdle { assertThat(hoisted).isEqualTo("/multitask ship it") }
    }

    @Test
    fun `a field that had focus gets it back after a configuration change`() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = "", onValueChange = {}, placeholder = "Ask anything", onSend = {})
            }
        }

        field.performClick()
        field.assertIsFocused()

        restorer.emulateSavedInstanceStateRestore()

        field.assertIsFocused()
    }

    @Test
    fun `a field that did not have focus is not given it by a configuration change`() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = "", onValueChange = {}, placeholder = "Ask anything", onSend = {})
            }
        }

        field.assertIsNotFocused()

        restorer.emulateSavedInstanceStateRestore()

        field.assertIsNotFocused()
    }
}
