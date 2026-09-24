package com.cursorforandroid.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.ui.projects.SteerSheet
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import android.view.KeyEvent as NativeKeyEvent

/**
 * A physical Enter sends from the composer even with an IME that takes Enter from the physical keyboard and types the
 * newline itself, as Samsung Keyboard and Gboard can. The keys go through the window's real input pipeline, the IME's
 * turn included; the IME answers Enter in each of the ways one can put a newline in.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi", shadows = [ShadowFakeImeInputMethodManager::class])
class ImeHardwareEnterTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // "/go" lists "goal" first.
    private val catalog = SlashCatalog(
        listOf(
            SlashCommand("goal", "Set a goal that Cursor will pursue", kind = SlashCommand.Kind.Command, origin = SlashCommand.Origin.BuiltIn),
            SlashCommand("go-live", "Ship the branch to production", origin = SlashCommand.Origin.Project),
        ),
    )
    private val field get() = compose.onNode(hasSetTextAction())
    private var hoisted by mutableStateOf("")
    private val sent = mutableListOf<String>()
    private val sends get() = sent.size
    private lateinit var view: View

    @Before
    fun setUp() {
        FakeIme.reset()
        PhysicalKeyboard.forget()
    }

    @After
    fun tearDown() {
        FakeIme.reset()
        PhysicalKeyboard.forget()
    }

    /** The composer, on a device whose configuration has a hardware keyboard [attached] or none. */
    private fun show(attached: Boolean = true, commands: SlashCatalog = SlashCatalog.BUILT_IN) {
        compose.setContent {
            val base = LocalConfiguration.current
            val configuration = remember(base, attached) { base.withKeyboard(attached) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                CursorTheme(mode = ThemeMode.Dark) {
                    ComposerBox(value = hoisted, onValueChange = { hoisted = it }, placeholder = "Ask anything", onSend = { sent += hoisted }, commands = commands)
                }
            }
        }
    }

    private fun draft(): String =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Types [text], then binds the IME to the focused field, as the system does once the field takes focus. */
    private fun typedWithIme(text: String, style: FakeIme.Style = FakeIme.Style.PassesKeysOn) {
        if (text.isNotEmpty()) field.performTextInput(text) else field.performSemanticsAction(SemanticsActions.RequestFocus)
        view = field.windowView
        compose.runOnIdle { FakeIme.bind(view, style) }
    }

    private fun ime(action: FakeIme.() -> Unit) = compose.runOnIdle { FakeIme.action() }

    private fun press(keyCode: Int, shift: Boolean = false) = compose.pressThroughWindow(view, keyCode, shift = shift)

    private fun pressEnter(shift: Boolean = false) = press(NativeKeyEvent.KEYCODE_ENTER, shift)

    @Test
    fun `the harness hands keys to the IME before the views`() {
        show()
        typedWithIme("ship it")
        press(NativeKeyEvent.KEYCODE_SHIFT_LEFT)
        assertThat(FakeIme.heard.map { it.keyCode }).contains(NativeKeyEvent.KEYCODE_SHIFT_LEFT)
    }

    @Test
    fun `an IME that commits a newline for Enter is never handed a physical Enter, and the Enter sends`() {
        show()
        typedWithIme("ship it", FakeIme.Style.CommitsNewline)
        pressEnter()
        assertThat(sent).containsExactly("ship it")
        assertThat(draft()).isEqualTo("ship it")
        assertThat(FakeIme.heardEnterDowns()).isEqualTo(0)
    }

    @Test
    fun `an IME that answers Enter with an Enter key of its own is never handed a physical Enter, and the Enter sends`() {
        show()
        typedWithIme("ship it", FakeIme.Style.SendsOwnEnter)
        pressEnter()
        assertThat(sent).containsExactly("ship it")
        assertThat(draft()).isEqualTo("ship it")
        assertThat(FakeIme.heardEnterDowns()).isEqualTo(0)
    }

    @Test
    fun `Gboard-style, with the last word still composing, the word and then a newline committed, one Enter sends it all`() {
        show()
        typedWithIme("ship ", FakeIme.Style.CommitsNewline)
        ime { compose("it") }
        pressEnter()
        assertThat(sent).containsExactly("ship it")
        assertThat(draft()).isEqualTo("ship it")
        // The composing word's Enter was the IME's to answer, release and all.
        assertThat(FakeIme.heardEnterDowns()).isEqualTo(1)
        assertThat(FakeIme.heard.last().let { it.keyCode to it.action }).isEqualTo(NativeKeyEvent.KEYCODE_ENTER to NativeKeyEvent.ACTION_UP)
    }

    @Test
    fun `Samsung-style, the composing word and the newline committed together, one Enter sends it all`() {
        show()
        typedWithIme("ship ", FakeIme.Style.CommitsWordAndNewline)
        ime { compose("it") }
        pressEnter()
        assertThat(sent).containsExactly("ship it")
        assertThat(draft()).isEqualTo("ship it")
    }

    @Test
    fun `the composing word finished and an Enter key of the IME's own sent, one Enter sends it all`() {
        show()
        typedWithIme("ship ", FakeIme.Style.SendsOwnEnter)
        ime { compose("it") }
        pressEnter()
        assertThat(sent).containsExactly("ship it")
        assertThat(draft()).isEqualTo("ship it")
    }

    @Test
    fun `a conversion IME's Enter only confirms the composition, and the next Enter sends`() {
        show()
        typedWithIme("say ", FakeIme.Style.ConfirmsComposition)
        ime { compose("nihao") }
        pressEnter()
        assertThat(sends).isEqualTo(0)
        assertThat(draft()).isEqualTo("say nihao")

        pressEnter()
        assertThat(sent).containsExactly("say nihao")
        assertThat(FakeIme.heardEnterDowns()).isEqualTo(1)
    }

    @Test
    fun `Shift+Enter breaks the line whatever the IME does with Enter`() {
        show()
        typedWithIme("line one", FakeIme.Style.CommitsNewline)
        pressEnter(shift = true)
        assertThat(draft()).isEqualTo("line one\n")
        assertThat(sends).isEqualTo(0)
        assertThat(FakeIme.heardEnterDowns()).isEqualTo(0)
    }

    @Test
    fun `Shift+Enter on a composing word leaves the IME's newline in`() {
        show()
        typedWithIme("line ", FakeIme.Style.CommitsNewline)
        ime { compose("one") }
        pressEnter(shift = true)
        assertThat(draft()).isEqualTo("line one\n")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `the on-screen keyboard's Enter keeps its newline with a keyboard attached, once physical keys reach the composer`() {
        show(attached = true)
        typedWithIme("ship it")
        press(NativeKeyEvent.KEYCODE_SHIFT_LEFT)
        ime { tapEnter() }
        assertThat(draft()).isEqualTo("ship it\n")
        ime { connection!!.sendKeyEvent(ownEnter(NativeKeyEvent.ACTION_DOWN)); connection!!.sendKeyEvent(ownEnter(NativeKeyEvent.ACTION_UP)) }
        assertThat(draft()).isEqualTo("ship it\n\n")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `a newline the IME types long after a physical Enter went to it is not that Enter's`() {
        show()
        typedWithIme("say ", FakeIme.Style.ConfirmsComposition)
        ime { compose("nihao") }
        pressEnter()
        ShadowSystemClock.advanceBy(Duration.ofMillis(PhysicalKeyboard.ImeAnswerMillis + 1))
        ime { tapEnter() }
        assertThat(draft()).isEqualTo("say nihao\n")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `a paste of several lines never sends, through the IME or the menu`() {
        show(attached = true)
        typedWithIme("ship it ")
        ime { connection!!.commitText("one\ntwo", 1) }
        ime { connection!!.commitText(" three\n", 1) }
        assertThat(draft()).isEqualTo("ship it one\ntwo three\n")

        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("lines", "four\nfive"))
        field.performSemanticsAction(SemanticsActions.PasteText)
        assertThat(draft()).isEqualTo("ship it one\ntwo three\nfour\nfive")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `with no key yet seen before the IME, a lone newline is an Enter while a keyboard is attached, however the IME types it`() {
        show(attached = true)
        typedWithIme("ship it")
        ime { tapEnter() }
        assertThat(sends).isEqualTo(1)
        ime { connection!!.commitText("\n", 1, null) }
        assertThat(sends).isEqualTo(2)
        ime { connection!!.sendKeyEvent(ownEnter(NativeKeyEvent.ACTION_DOWN)); connection!!.sendKeyEvent(ownEnter(NativeKeyEvent.ACTION_UP)) }
        assertThat(sends).isEqualTo(3)
        assertThat(draft()).isEqualTo("ship it")
    }

    @Test
    fun `with no keyboard attached and no physical key seen, a newline stays a newline`() {
        show(attached = false)
        typedWithIme("ship it")
        ime { tapEnter() }
        assertThat(draft()).isEqualTo("ship it\n")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `a physical key lately counts as a keyboard attached, for a while`() {
        show(attached = false)
        typedWithIme("ship it")
        // Reaches the composer after the IME only, as with an IME that hands keys on but reads Enter itself.
        field.pressKey(NativeKeyEvent.KEYCODE_SHIFT_LEFT)
        ime { tapEnter() }
        assertThat(sends).isEqualTo(1)
        assertThat(draft()).isEqualTo("ship it")

        ShadowSystemClock.advanceBy(Duration.ofMillis(PhysicalKeyboard.RecentKeyMillis + 1))
        ime { tapEnter() }
        assertThat(sends).isEqualTo(1)
        assertThat(draft()).isEqualTo("ship it\n")
    }

    @Test
    fun `a newline typed while Shift is held stays a newline`() {
        show(attached = true)
        typedWithIme("line one")
        field.performKeyPress(keyEvent(NativeKeyEvent.KEYCODE_SHIFT_LEFT, NativeKeyEvent.ACTION_DOWN, shift = true))
        ime { tapEnter() }
        assertThat(draft()).isEqualTo("line one\n")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `with nothing to send, the IME's newline stays`() {
        show(attached = true)
        typedWithIme("")
        ime { tapEnter() }
        assertThat(draft()).isEqualTo("\n")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `with the popover open, Enter picks the highlighted command before the IME can take it`() {
        show(commands = catalog)
        typedWithIme("/go", FakeIme.Style.CommitsNewline)
        compose.waitUntil(10_000) { popoverOpen() }
        pressEnter()
        compose.waitUntil(10_000) { !popoverOpen() }
        compose.runOnIdle { assertThat(hoisted).isEqualTo("/goal ") }
        assertThat(sends).isEqualTo(0)
        assertThat(FakeIme.heardEnterDowns()).isEqualTo(0)
    }

    @Test
    fun `with the popover open, the IME's newline for an Enter picks the highlighted command`() {
        show(attached = true, commands = catalog)
        typedWithIme("/go")
        compose.waitUntil(10_000) { popoverOpen() }
        ime { tapEnter() }
        compose.waitUntil(10_000) { !popoverOpen() }
        compose.runOnIdle { assertThat(hoisted).isEqualTo("/goal ") }
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `a sheet's field steers on a physical Enter the IME would have made a newline`() {
        val steered = mutableListOf<String>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { SteerSheet(workerName = "docs", onSteer = { steered += it }, onDismiss = {}) }
        }
        typedWithIme("use the v2 API", FakeIme.Style.CommitsNewline)
        pressEnter()
        assertThat(steered).containsExactly("use the v2 API")
        assertThat(FakeIme.heardEnterDowns()).isEqualTo(0)
    }

    @Test
    fun `a sheet's field takes the IME's newline for an Enter as the Enter`() {
        val steered = mutableListOf<String>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { SteerSheet(workerName = "docs", onSteer = { steered += it }, onDismiss = {}) }
        }
        typedWithIme("use the v2 API")
        // The sheet's window has a configuration of its own; a physical key lately is the keyboard's evidence here.
        field.pressKey(NativeKeyEvent.KEYCODE_SHIFT_LEFT)
        ime { tapEnter() }
        assertThat(steered).containsExactly("use the v2 API")
    }

    private fun popoverOpen() =
        compose.onAllNodes(hasClickAction() and hasText("Set a goal that Cursor will pursue", substring = true)).fetchSemanticsNodes().isNotEmpty()
}

/** This configuration with a hardware keyboard attached and exposed, or with none. */
internal fun Configuration.withKeyboard(attached: Boolean): Configuration = Configuration(this).apply {
    keyboard = if (attached) Configuration.KEYBOARD_QWERTY else Configuration.KEYBOARD_NOKEYS
    hardKeyboardHidden = if (attached) Configuration.HARDKEYBOARDHIDDEN_NO else Configuration.HARDKEYBOARDHIDDEN_YES
}
