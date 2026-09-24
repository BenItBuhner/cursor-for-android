package com.cursorforandroid.ui.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.Keyboard
import com.cursorforandroid.ui.components.pressEnter
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Project panel's composing sheets take a physical Enter as their action, as the chat's composer takes it as send. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectSheetsEnterTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun `a physical Enter steers, Shift+Enter and the on-screen Enter break the line, and an empty steer does nothing`() {
        val steered = mutableListOf<String>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { SteerSheet(workerName = "docs", onSteer = { steered += it }, onDismiss = {}) }
        }
        val field = compose.onNode(hasSetTextAction())

        field.pressEnter()
        assertThat(field.text()).isEmpty()

        field.performTextInput("use the v2 API")
        field.pressEnter(shift = true)
        field.pressEnter(Keyboard.OnScreen)
        field.performTextInput("and keep v1 working")
        assertThat(steered).isEmpty()

        field.pressEnter()
        assertThat(steered).containsExactly("use the v2 API\n\nand keep v1 working")
    }

    @Test
    fun `a physical Enter in either field starts the new primary, once it has a prompt`() {
        val launched = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { NewWorkerSheet(root = null, onLaunch = { p, n -> launched += p to n }, onDismiss = {}) }
        }
        val prompt = compose.onAllNodes(hasSetTextAction())[0]
        val name = compose.onAllNodes(hasSetTextAction())[1]

        name.performTextInput("docs")
        name.pressEnter()
        assertThat(launched).isEmpty()
        assertThat(name.text()).isEqualTo("docs")

        prompt.performTextInput("Write the docs")
        name.pressEnter()
        assertThat(launched).containsExactly("Write the docs" to "docs")
    }
}
