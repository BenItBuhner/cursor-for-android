package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The composer's `/` popover, driven as a person types: one character at a time, then a tap. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerSlashPopoverTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val catalog = SlashCatalog.BUILT_IN.mergedWith(
        SlashCatalog(listOf(SlashCommand("chat-sdk", "Vercel Chat SDK expert guidance, for Slack, Telegram and Google Chat bots", origin = SlashCommand.Origin.Plugin))),
    )
    private var prompt = ""
    private val remembered = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = value,
                    onValueChange = { value = it; prompt = it },
                    placeholder = "Ask",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickFiles = {}, onSkillUsed = { remembered += it }),
                    commands = catalog,
                )
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    /** A row of the popover is on screen: the field itself never carries a description. */
    private fun popoverOpen() = compose.onAllNodes(hasClickAction() and hasText("Set a goal that Cursor will pursue", substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `typing a slash one character at a time narrows the list, and a tap completes the token typed so far`() {
        show()
        val field = compose.onNode(hasSetTextAction())

        field.performTextInput("/")
        compose.waitUntil { shown("/autopilot") && shown("/goal") }
        field.performTextInput("g")
        compose.waitForIdle()
        assertThat(shown("/goal")).isTrue()
        field.performTextInput("o")
        compose.waitUntil { shown("/chat-sdk") }
        // "/go": the goal command by name, the plugin skill by its description; the rest is gone.
        assertThat(shown("/autopilot")).isFalse()
        assertThat(shown("/multitask")).isFalse()

        compose.onNodeWithText("/goal").performClick()
        compose.waitUntil { !shown("Set a goal that Cursor will pursue") }

        // The whole "/go" is replaced, not just the slash the row was first composed under.
        assertThat(prompt).isEqualTo("/goal ")
        field.performTextInput("make CI green")
        compose.waitForIdle()
        assertThat(prompt).isEqualTo("/goal make CI green")
        assertThat(remembered).isEmpty()
    }

    @Test
    fun `a name the catalog does not list is offered as a project skill and remembered when picked`() {
        show()
        val field = compose.onNode(hasSetTextAction())

        field.performTextInput("/land-it")
        compose.waitUntil { shown("Project or synced skill") }
        compose.onNode(hasClickAction() and hasText("Project or synced skill")).performClick()
        compose.waitUntil { !shown("Project or synced skill") }

        assertThat(prompt).isEqualTo("/land-it ")
        assertThat(remembered).containsExactly("land-it")
    }

    @Test
    fun `a token nothing matches closes the popover, and a space after the command keeps it closed`() {
        show()
        val field = compose.onNode(hasSetTextAction())

        field.performTextInput("/")
        compose.waitUntil { popoverOpen() }
        field.performTextInput("zz")
        compose.waitUntil { !popoverOpen() }

        field.performTextInput("q")
        compose.waitForIdle()
        assertThat(popoverOpen()).isFalse()
        assertThat(prompt).isEqualTo("/zzq")
    }
}
