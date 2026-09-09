package com.cursorforandroid.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The composer hoists a `String` but a text field needs a selection too, so the two are kept in step by hand. These
 * drive the two ways that can go wrong: the owner mirroring the draft back late, and the process being killed while
 * a draft is half-written.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val field get() = compose.onNode(hasSetTextAction())

    /** The placeholder is part of the node's `Text`, so the draft has to be read off `EditableText`. */
    private fun draft(): String =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun selection(): TextRange? =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    @Test
    fun `typing is not undone by an owner that mirrors the draft back late`() {
        var hoisted by mutableStateOf("")
        val sent = mutableListOf<String>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = hoisted, onValueChange = { sent += it }, placeholder = "Ask anything", onSend = {})
            }
        }

        field.performTextInput("half a thought")

        // The owner has not answered yet. Mirroring during composition would rewrite the field back to "" here.
        compose.runOnIdle { assertThat(sent.last()).isEqualTo("half a thought") }
        assertThat(draft()).isEqualTo("half a thought")

        compose.runOnIdle { hoisted = sent.last() }
        assertThat(draft()).isEqualTo("half a thought")
    }

    @Test
    fun `text pushed in from outside lands with the caret after it`() {
        var hoisted by mutableStateOf("")
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = hoisted, onValueChange = { hoisted = it }, placeholder = "Ask anything", onSend = {})
            }
        }

        // What the "+" menu does when a slash command is picked.
        compose.runOnIdle { hoisted = "/model " }
        assertThat(draft()).isEqualTo("/model ")
        field.performTextInput("sonnet")
        assertThat(draft()).isEqualTo("/model sonnet")
    }

    @Test
    fun `clearing the draft after a send still empties the field`() {
        var hoisted by mutableStateOf("")
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = hoisted, onValueChange = { hoisted = it }, placeholder = "Ask anything", onSend = {})
            }
        }

        field.performTextInput("send me")
        compose.runOnIdle { hoisted = "" }
        assertThat(draft()).isEmpty()
    }

    @Test
    fun `a draft and its caret come back after a configuration change`() {
        val restorer = StateRestorationTester(compose)
        var hoisted by mutableStateOf("")
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = hoisted, onValueChange = { hoisted = it }, placeholder = "Ask anything", onSend = {})
            }
        }

        field.performTextInput("half a thought")
        field.performTextInputSelection(TextRange(4))

        // The view model outlives a rotation, so it still holds the draft on the other side.
        restorer.emulateSavedInstanceStateRestore()

        assertThat(draft()).isEqualTo("half a thought")
        assertThat(selection()).isEqualTo(TextRange(4))
    }

    @Test
    fun `a draft the owner has already thrown away is not put back on top of it`() {
        val restorer = StateRestorationTester(compose)
        val hoisted = mutableStateOf("")
        // The pane's saved state is written when it leaves composition, and the owner can drop the draft after that
        // - sending from the home composer does exactly this on its way into the new chat. Reading the flag rather
        // than the state keeps that second step from reaching the composer while it is still on screen.
        var dropped = false
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = if (dropped) "" else hoisted.value,
                    onValueChange = { hoisted.value = it },
                    placeholder = "Ask anything",
                    onSend = {},
                )
            }
        }

        field.performTextInput("Do the thing")
        compose.runOnIdle { assertThat(hoisted.value).isEqualTo("Do the thing") }
        dropped = true

        restorer.emulateSavedInstanceStateRestore()

        assertThat(draft()).isEmpty()
    }
}
