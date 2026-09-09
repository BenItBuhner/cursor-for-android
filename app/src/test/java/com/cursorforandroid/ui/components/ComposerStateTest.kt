package com.cursorforandroid.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
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
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val field get() = compose.onNode(hasSetTextAction())

    /** The placeholder is part of the node's `Text`, so the draft has to be read off `EditableText`. */
    private fun draft(): String =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

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
    fun `a half-written draft survives the process being killed`() {
        val restorer = StateRestorationTester(compose)
        // Deliberately not snapshot state: the draft's owner is a view model that does not persist it, so it comes
        // back empty on the other side of the kill without anything having recomposed in between.
        var hoisted = ""
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = hoisted, onValueChange = { hoisted = it }, placeholder = "Ask anything", onSend = {})
            }
        }

        field.performTextInput("half a thought")
        compose.runOnIdle { assertThat(hoisted).isEqualTo("half a thought") }
        hoisted = ""

        restorer.emulateSavedInstanceStateRestore()

        assertThat(draft()).isEqualTo("half a thought")
        compose.runOnIdle { assertThat(hoisted).isEqualTo("half a thought") }
    }
}
