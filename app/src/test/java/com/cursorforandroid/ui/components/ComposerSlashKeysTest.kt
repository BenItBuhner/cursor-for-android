package com.cursorforandroid.ui.components

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import android.view.KeyEvent as NativeKeyEvent

/**
 * With the composer's `/` popover up, a physical keyboard drives it as on the desktop: the arrows move the highlight,
 * Enter or Tab completes the highlighted command instead of sending, Esc closes the popover and leaves the text.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerSlashKeysTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // "/go" lists these three, in this order: two names that start with it, then a description that holds it.
    private val catalog = SlashCatalog(
        listOf(
            SlashCommand("goal", "Set a goal that Cursor will pursue", kind = SlashCommand.Kind.Command, origin = SlashCommand.Origin.BuiltIn),
            SlashCommand("go-live", "Ship the branch to production", origin = SlashCommand.Origin.Project),
            SlashCommand("chat-sdk", "Bots for Slack, Telegram and Google Chat", origin = SlashCommand.Origin.Plugin),
        ),
    )
    private val field get() = compose.onNode(hasSetTextAction())
    private var prompt = ""
    private val sent = mutableListOf<String>()
    private lateinit var view: View

    private fun show(commands: SlashCatalog = catalog) {
        compose.setContent {
            view = LocalView.current
            var value by remember { mutableStateOf("") }
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = value,
                    onValueChange = { value = it; prompt = it },
                    placeholder = "Ask",
                    onSend = { sent += prompt },
                    plusMenu = ComposerMenuActions(onPickMedia = {}),
                    commands = commands,
                )
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** A row of the popover is on screen: the field itself never carries a description. */
    private fun popoverOpen() = compose.onAllNodes(hasClickAction() and hasText("Set a goal that Cursor will pursue", substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun highlighted(): List<String> = compose.onAllNodes(isSelected()).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    private fun typeGo() {
        field.performTextInput("/go")
        compose.waitUntil(10_000) { popoverOpen() && shown("/chat-sdk") }
    }

    @Test
    fun `a physical Enter completes the highlighted command instead of sending, and the next Enter sends`() {
        show()
        typeGo()

        assertThat(field.pressEnter()).isTrue()
        compose.waitUntil(10_000) { !popoverOpen() }
        assertThat(prompt).isEqualTo("/goal ")
        assertThat(sent).isEmpty()

        field.performTextInput("make CI green")
        field.pressEnter()
        assertThat(sent).containsExactly("/goal make CI green")
    }

    @Test
    fun `the highlight shows once a physical key reaches the composer, and the arrows move it round the ends`() {
        show()
        // Typed through the on-screen keyboard: the rows are for tapping, and nothing wears a highlight yet.
        typeGo()
        assertThat(highlighted()).isEmpty()

        field.pressKey(NativeKeyEvent.KEYCODE_SHIFT_LEFT)
        assertThat(highlighted()).containsExactly("/goal")
        field.pressKey(NativeKeyEvent.KEYCODE_DPAD_UP)
        assertThat(highlighted()).containsExactly("/chat-sdk")
        field.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(highlighted()).containsExactly("/goal")
        field.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(highlighted()).containsExactly("/go-live")

        field.pressEnter()
        compose.waitUntil(10_000) { !popoverOpen() }
        assertThat(prompt).isEqualTo("/go-live ")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `the highlight goes back to the top as the query starts, and stays on its row as the query narrows`() {
        show()
        field.performTextInput("/")
        compose.waitUntil(10_000) { popoverOpen() && shown("/chat-sdk") }
        field.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        field.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(highlighted()).containsExactly("/chat-sdk")

        field.performTextInput("g")
        compose.waitForIdle()
        assertThat(highlighted()).containsExactly("/goal")

        field.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        field.performTextInput("o")
        compose.waitForIdle()
        assertThat(highlighted()).containsExactly("/go-live")
    }

    @Test
    fun `Tab completes the highlighted command too, with Shift or without`() {
        show()
        typeGo()
        field.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        field.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(field.pressKey(NativeKeyEvent.KEYCODE_TAB)).isTrue()
        compose.waitUntil(10_000) { !popoverOpen() }
        assertThat(prompt).isEqualTo("/chat-sdk ")

        typeGo()
        assertThat(field.pressKey(NativeKeyEvent.KEYCODE_TAB, shift = true)).isTrue()
        compose.waitUntil(10_000) { !popoverOpen() }
        assertThat(prompt).isEqualTo("/chat-sdk /goal ")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `Esc closes the popover and leaves the text, and Enter then sends it`() {
        show()
        typeGo()

        assertThat(field.pressKey(NativeKeyEvent.KEYCODE_ESCAPE)).isTrue()
        compose.waitUntil(10_000) { !popoverOpen() }
        assertThat(prompt).isEqualTo("/go")
        assertThat(sent).isEmpty()

        field.pressEnter()
        assertThat(sent).containsExactly("/go")
    }

    @Test
    fun `the on-screen keyboard's Enter is still a newline with the popover up`() {
        show()
        typeGo()

        field.pressEnter(Keyboard.OnScreen)
        compose.waitForIdle()
        assertThat(prompt).isEqualTo("/go\n")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `an Enter while the IME composes only accepts the composition, and the next Enter completes`() {
        show()
        field.performTextInput("/")
        compose.waitUntil(10_000) { popoverOpen() }
        val connection = compose.runOnIdle { view.onCreateInputConnection(EditorInfo()) }
        assertThat(connection).isNotNull()
        compose.runOnIdle { connection!!.setComposingText("go", 1) }
        compose.waitUntil(10_000) { shown("/chat-sdk") }

        field.pressEnter()
        compose.waitForIdle()
        assertThat(prompt).isEqualTo("/go")
        assertThat(popoverOpen()).isTrue()

        field.pressEnter()
        compose.waitUntil(10_000) { !popoverOpen() }
        assertThat(prompt).isEqualTo("/goal ")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `with only the waiting notice up, Enter is the popover's and sends nothing`() {
        show(catalog.copy(pending = true))
        // A body no name and no description can hold, so nothing is listed and only the notice is left.
        field.performTextInput("/-zz")
        compose.waitUntil(10_000) { shown("Looking for the agent's own skills") }

        assertThat(field.pressEnter()).isTrue()
        assertThat(sent).isEmpty()
        assertThat(prompt).isEqualTo("/-zz")
    }
}
