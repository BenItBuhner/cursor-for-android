package com.cursorforandroid.ui.navigation

import android.app.Application
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.domain.BuiltInSlashCommands
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.AgentRowTags
import com.cursorforandroid.ui.settings.KeyboardShortcutsTags
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.shortcuts.KeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.LocalKeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.PaletteTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The hardware keyboard in the running shell, on the demo, each key read first by [KeyboardShortcuts] and handed on
 * to the window when it is not the app's, as `MainActivity.dispatchKeyEvent` does: the search palette finding a chat
 * by its transcript and opening it on the hit, the quick switcher, the rail and the drawer, the chat's panel, the
 * sidebar's numbers, New Chat, Settings' page of shortcuts, and a chat's Ctrl+R and Ctrl+Shift+R.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class KeyboardShortcutsFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: AppGraph
    private lateinit var keys: KeyboardShortcuts

    private fun showShell(wide: Boolean) {
        graph = AppGraph(context)
        runBlocking {
            graph.session.enterDemo()
            // What this device keeps of the chat's transcript, as an earlier open would have left it.
            val house = DemoData.seeds.first { it.id == HOUSE_ID }
            graph.caches.conversations.write(CachedConversation(HOUSE_ID, DemoData.transcript(house), runs = emptyList()))
        }
        keys = KeyboardShortcuts(compose.activity.lifecycleScope)
        compose.setContent {
            CompositionLocalProvider(LocalKeyboardShortcuts provides keys) {
                CursorTheme(mode = ThemeMode.Dark) {
                    AppShell(
                        graph = graph,
                        user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                        isDemo = true,
                        wide = wide,
                        deepLinkAgentId = null,
                        onDeepLinkConsumed = {},
                    )
                }
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun onScreen(text: String) = exists(hasText(text, substring = true))

    private fun displayed(matcher: SemanticsMatcher) = runCatching { compose.onAllNodes(matcher).onFirst().assertIsDisplayed() }.isSuccess

    /** A sidebar row's Ctrl+digit key cap: drawn inside the row, whose semantics merge it away. */
    private fun badge(n: Int) = compose.onAllNodes(hasTestTag(AgentRowTags.shortcutNumber(n)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private val slashNames = (BuiltInSlashCommands.commands + BuiltInSlashCommands.skills).map { "/${it.name}" }

    /** The `/` popover's highlighted row, by its name; null while the popover is shut (or no physical key has reached it). */
    private fun popoverHighlight(): String? = compose.onAllNodes(isSelected()).fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        .firstOrNull { it in slashNames }

    private fun chatOpen(name: String) = exists(hasTestTag("chat-header") and hasContentDescription(name))

    /** A key from a hardware keyboard: the app's reader first, then the window, as the activity dispatches it. */
    private fun send(action: Int, code: Int, ctrl: Boolean = false, shift: Boolean = false, repeat: Int = 0) {
        var meta = 0
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val event = KeyEvent(0L, 0L, action, code, repeat, meta, 7, 0, 0, InputDevice.SOURCE_KEYBOARD)
        compose.runOnUiThread { if (!keys.onKeyEvent(event)) compose.activity.dispatchKeyEvent(event) }
        compose.waitForIdle()
    }

    private fun press(code: Int, ctrl: Boolean = false, shift: Boolean = false) {
        send(KeyEvent.ACTION_DOWN, code, ctrl, shift)
        send(KeyEvent.ACTION_UP, code, ctrl, shift)
    }

    private fun ctrlDown() = send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl = true)

    private fun ctrlUp() = send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT)

    /** Ctrl held, [code] pressed, Ctrl let go. */
    private fun chord(code: Int, shift: Boolean = false) {
        ctrlDown()
        press(code, ctrl = true, shift = shift)
        ctrlUp()
    }

    private val paletteField get() = compose.onNodeWithTag(PaletteTags.FIELD)

    private fun resultRow(index: Int, text: String) = hasTestTag(PaletteTags.result(index)) and hasText(text, substring = true)

    /** Ctrl+F, [query] typed, Enter on the first row once it is [expected]. */
    private fun searchAndOpen(query: String, expected: String) {
        chord(KeyEvent.KEYCODE_F)
        paletteField.assertIsFocused()
        paletteField.performTextInput(query)
        compose.waitUntil(20_000) { exists(resultRow(0, expected)) }
        press(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(20_000) { chatOpen(expected) }
        compose.waitUntil(20_000) { onScreen(CHAT_PLACEHOLDER) }
        compose.waitForIdle()
    }

    @Test
    fun `Ctrl+F finds a chat by what its transcript says, with the snippet, and Enter opens it on the reply`() {
        showShell(wide = true)
        chord(KeyEvent.KEYCODE_F)
        compose.onNodeWithTag(PaletteTags.CARD).assertIsDisplayed()
        paletteField.assertIsFocused()

        paletteField.performTextInput("material atlas")
        compose.waitUntil(20_000) { exists(resultRow(0, HOUSE)) }
        compose.onNode(hasAnyAncestor(hasTestTag(PaletteTags.result(0))) and hasText("shared material atlas", substring = true), useUnmergedTree = true).assertExists()
        compose.onNode(hasAnyAncestor(hasTestTag(PaletteTags.result(0))) and hasText("1 match"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(PaletteTags.result(0)).assertIsSelected()

        press(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(20_000) { chatOpen(HOUSE) }
        compose.waitUntil(20_000) { displayed(hasText("shared material atlas", substring = true) and !hasTestTag(PaletteTags.FIELD)) }
        assertFalse(exists(hasTestTag(PaletteTags.CARD)))
    }

    @Test
    fun `Esc puts the palette away, Ctrl+K opens it too, and Ctrl+slash shows the shortcuts, Ctrl+R among them`() {
        showShell(wide = true)
        chord(KeyEvent.KEYCODE_K)
        compose.onNodeWithTag(PaletteTags.FIELD).assertIsFocused()
        press(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(5_000) { !exists(hasTestTag(PaletteTags.CARD)) }

        chord(KeyEvent.KEYCODE_SLASH)
        compose.onNodeWithTag(PaletteTags.SHORTCUTS).assertExists()
        assertTrue(exists(hasAnyAncestor(hasTestTag(PaletteTags.SHORTCUTS)) and hasText("Check for new messages")))
        assertTrue(exists(hasAnyAncestor(hasTestTag(PaletteTags.SHORTCUTS)) and hasText("Reload transcript")))
        // Pressed again, the cheat sheet goes as it came.
        chord(KeyEvent.KEYCODE_SLASH)
        compose.waitUntil(5_000) { !exists(hasTestTag(PaletteTags.CARD)) }
    }

    @Test
    fun `Ctrl+Tab steps through the recent chats while Ctrl is held, and letting go opens the one picked`() {
        showShell(wide = true)
        searchAndOpen("Cli exploration", CLI)
        searchAndOpen("House environment", HOUSE)

        ctrlDown()
        press(KeyEvent.KEYCODE_TAB, ctrl = true)
        compose.onNodeWithTag(PaletteTags.SWITCHER).assertIsDisplayed()
        compose.onNode(resultRow(0, HOUSE)).assertExists()
        compose.onNode(resultRow(1, CLI)).assertIsSelected()

        press(KeyEvent.KEYCODE_TAB, ctrl = true)
        compose.onNodeWithTag(PaletteTags.result(2)).assertIsSelected()
        press(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true)
        compose.onNodeWithTag(PaletteTags.result(1)).assertIsSelected()

        ctrlUp()
        compose.waitUntil(20_000) { chatOpen(CLI) }
        assertFalse(exists(hasTestTag(PaletteTags.CARD)))
    }

    @Test
    fun `beside the pane, Ctrl+B puts the rail away and back, and Ctrl+Shift+B opens the chat's panel, Esc shutting it`() {
        showShell(wide = true)
        compose.waitUntil(20_000) { displayed(hasContentDescription("Search chats")) }
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { !displayed(hasContentDescription("Search chats")) }
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { displayed(hasContentDescription("Search chats")) }

        searchAndOpen("House environment", HOUSE)
        assertFalse(displayed(hasTestTag("conversation-panel")))
        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { displayed(hasTestTag("conversation-panel")) }
        press(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(10_000) { !displayed(hasTestTag("conversation-panel")) }
        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { displayed(hasTestTag("conversation-panel")) }
        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { !displayed(hasTestTag("conversation-panel")) }
    }

    @Test
    fun `on a phone, Ctrl+B opens the drawer and shuts it`() {
        showShell(wide = false)
        assertFalse(displayed(hasContentDescription("Search chats")))
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { displayed(hasContentDescription("Search chats")) }
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { !displayed(hasContentDescription("Search chats")) }
    }

    @Test
    fun `Ctrl held numbers the rail's rows, Ctrl+1 opens the first, and letting go puts the numbers away`() {
        showShell(wide = true)
        compose.waitUntil(20_000) { displayed(hasContentDescription("Search chats")) }
        ctrlDown()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KeyboardShortcuts.NUMBERS_AFTER_MILLIS + 100))
        compose.waitUntil(5_000) { badge(1) }
        assertTrue(badge(0))

        press(KeyEvent.KEYCODE_1, ctrl = true)
        compose.waitUntil(20_000) { chatOpen(PROJECT) }
        ctrlUp()
        compose.waitUntil(5_000) { !badge(1) }
    }

    @Test
    fun `Ctrl+N from a chat starts a new one with the caret in the composer`() {
        showShell(wide = true)
        searchAndOpen("Cli exploration", CLI)
        chord(KeyEvent.KEYCODE_N)
        compose.waitUntil(20_000) { exists(hasSetTextAction() and hasText(HOME_PLACEHOLDER, substring = true)) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText(HOME_PLACEHOLDER, substring = true)).assertIsFocused()
    }

    @Test
    fun `Ctrl+comma opens Settings, whose Keyboard shortcuts page lists them all`() {
        showShell(wide = true)
        chord(KeyEvent.KEYCODE_COMMA)
        compose.waitUntil(20_000) { onScreen("Appearance") }
        compose.onNodeWithTag(SettingsTags.KEYBOARD_SHORTCUTS_ROW).performScrollTo().performClick()
        compose.waitUntil(20_000) { exists(hasTestTag(KeyboardShortcutsTags.PAGE)) }
        listOf("Search chats and Projects", "Switch between recent chats", "Check for new messages", "Reload transcript").forEach { label ->
            assertTrue(label, exists(hasAnyAncestor(hasTestTag(KeyboardShortcutsTags.PAGE)) and hasText(label, substring = true)))
        }
    }

    @Test
    fun `with the composer's slash popover up, Ctrl+N and Esc are the popover's, and Ctrl+N is New Chat once it is shut`() {
        showShell(wide = true)
        searchAndOpen("House environment", HOUSE)
        val composer = compose.onNode(hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true))
        composer.performClick()
        composer.performTextInput("/")
        // The highlight shows once a physical key has reached the composer.
        press(KeyEvent.KEYCODE_SHIFT_LEFT)
        compose.waitUntil(10_000) { popoverHighlight() != null }
        val first = checkNotNull(popoverHighlight())

        chord(KeyEvent.KEYCODE_N)
        val second = popoverHighlight()
        assertTrue(second != null && second != first)
        assertTrue(chatOpen(HOUSE))
        chord(KeyEvent.KEYCODE_K)
        assertEquals(first, popoverHighlight())
        assertFalse(exists(hasTestTag(PaletteTags.CARD)))

        press(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(5_000) { popoverHighlight() == null }
        assertTrue(chatOpen(HOUSE))

        chord(KeyEvent.KEYCODE_N)
        compose.waitUntil(20_000) { exists(hasSetTextAction() and hasText(HOME_PLACEHOLDER, substring = true)) }
    }

    @Test
    fun `in a chat, Ctrl+R and Ctrl+Shift+R each say it is up to date`() {
        showShell(wide = true)
        // Pressed as soon as the chat shows, while its first read may still be under way: that read is not counted.
        searchAndOpen("House environment", HOUSE)
        assertTrue(showsWhileClockHeld(UP_TO_DATE) { chord(KeyEvent.KEYCODE_R) })
        assertTrue(showsWhileClockHeld(UP_TO_DATE) { chord(KeyEvent.KEYCODE_R, shift = true) })
    }

    /**
     * Whether [text] comes up after [action], looked for frame by frame: left to run on, the test clock would carry a
     * snackbar through its whole showing inside one wait for idle.
     */
    private fun showsWhileClockHeld(text: String, action: () -> Unit): Boolean {
        compose.mainClock.autoAdvance = false
        try {
            action()
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                compose.mainClock.advanceTimeByFrame()
                if (onScreen(text)) return true
                Thread.sleep(10)
            }
            return false
        } finally {
            compose.mainClock.autoAdvance = true
            compose.waitUntil(20_000) { !onScreen(text) }
        }
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val HOUSE_ID = "bc-demo-0005"
        const val HOUSE = "House environment overhaul"
        const val CLI = "Cli exploration"
        const val PROJECT = "Cesium billing launch"
        const val UP_TO_DATE = "Up to date"
    }
}
