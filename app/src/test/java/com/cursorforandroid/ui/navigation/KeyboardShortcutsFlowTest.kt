package com.cursorforandroid.ui.navigation

import android.app.Application
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import com.cursorforandroid.ui.theme.CursorDimens
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
 * The hardware keyboard in the running shell, on the demo, each key handed on as the platform hands it (see [send]) —
 * with a field focused, a keyboard app that answers Ctrl chords itself among the ways: the search palette finding a chat
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

    /** Whether the keyboard app answers a key itself, while a text field has the focus (see [send]); none, by default. */
    private var imeAnswers: (KeyEvent) -> Boolean = { false }

    /** The keys the keyboard app was handed. */
    private val imeHeard = mutableListOf<Int>()

    /**
     * Samsung Keyboard and Gboard as far as Ctrl goes, at their most possessive: Ctrl itself and every chord on it
     * pressed in a field are theirs, and the window hears nothing of them after.
     */
    private val answersCtrlChords: (KeyEvent) -> Boolean = { it.isCtrlPressed || KeyEvent.isModifierKey(it.keyCode) }

    private var eventTime = 0L

    /**
     * A key from a hardware keyboard, handed on as the platform hands it: the views' pass before the IME (the shell's
     * reader among them), then — a text field focused — the keyboard app, then the activity, which reads it as
     * `MainActivity.dispatchKeyEvent` does before the window has it.
     */
    private fun send(action: Int, code: Int, ctrl: Boolean = false, shift: Boolean = false, repeat: Int = 0) {
        var meta = 0
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val at = ++eventTime
        val event = KeyEvent(at, at, action, code, repeat, meta, 7, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val fieldFocused = exists(isFocused() and hasSetTextAction())
        compose.runOnUiThread {
            if (compose.activity.window.decorView.dispatchKeyEventPreIme(event)) return@runOnUiThread
            if (fieldFocused) {
                imeHeard += code
                if (imeAnswers(event)) return@runOnUiThread
            }
            if (!keys.onKeyEvent(event)) compose.activity.dispatchKeyEvent(event)
        }
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

    @Test
    fun `typing in the composer, with a keyboard app that answers Ctrl chords itself, Ctrl+R and Ctrl+Shift+R still reach the chat`() {
        showShell(wide = true)
        searchAndOpen("House environment", HOUSE)
        imeAnswers = answersCtrlChords
        val composer = compose.onNode(hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true))
        composer.performClick()
        composer.assertIsFocused()
        // The field's own chords are still the keyboard app's.
        chord(KeyEvent.KEYCODE_A)
        assertTrue(KeyEvent.KEYCODE_A in imeHeard)

        assertTrue(showsWhileClockHeld(UP_TO_DATE) { chord(KeyEvent.KEYCODE_R) })
        assertTrue(showsWhileClockHeld(UP_TO_DATE) { chord(KeyEvent.KEYCODE_R, shift = true) })
        assertFalse(KeyEvent.KEYCODE_R in imeHeard)
    }

    @Test
    fun `typing in the composer, with that keyboard app, the shell's other chords are the shell's too, and Esc still reaches it`() {
        showShell(wide = true)
        searchAndOpen("House environment", HOUSE)
        imeAnswers = answersCtrlChords
        val composer = compose.onNode(hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true))
        composer.performClick()
        composer.assertIsFocused()

        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { displayed(hasTestTag("conversation-panel")) }
        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { !displayed(hasTestTag("conversation-panel")) }
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { !displayed(hasContentDescription("Search chats")) }
        chord(KeyEvent.KEYCODE_K)
        compose.waitUntil(10_000) { exists(hasTestTag(PaletteTags.CARD)) }
        paletteField.assertIsFocused()
        press(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(5_000) { !exists(hasTestTag(PaletteTags.CARD)) }
        assertTrue(listOf(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_K).none { it in imeHeard })
    }

    @Test
    fun `typing in the composer, with that keyboard app, Ctrl+Tab, Ctrl+1 to 0, Ctrl+N and Ctrl+F are the shell's, letting go of Ctrl too`() {
        showShell(wide = true)
        searchAndOpen("Cli exploration", CLI)
        searchAndOpen("House environment", HOUSE)
        imeAnswers = answersCtrlChords
        val composer = compose.onNode(hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true))
        composer.performClick()
        composer.assertIsFocused()
        val composerFocused = { exists(isFocused() and hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true)) }

        // The switcher steps both ways, and letting go of Ctrl, which the keyboard app keeps as well, opens the pick.
        ctrlDown()
        press(KeyEvent.KEYCODE_TAB, ctrl = true)
        press(KeyEvent.KEYCODE_TAB, ctrl = true)
        press(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true)
        compose.onNode(resultRow(1, CLI)).assertIsSelected()
        ctrlUp()
        compose.waitUntil(20_000) { chatOpen(CLI) }
        compose.waitUntil(10_000) { composerFocused() }

        chord(KeyEvent.KEYCODE_1)
        compose.waitUntil(20_000) { chatOpen(PROJECT) }
        compose.waitUntil(10_000) { composerFocused() }
        chord(KeyEvent.KEYCODE_0)
        compose.waitUntil(20_000) { exists(CHAT_HEADER) && !chatOpen(PROJECT) }
        compose.waitUntil(10_000) { composerFocused() }

        chord(KeyEvent.KEYCODE_N)
        compose.waitUntil(20_000) { exists(isFocused() and hasSetTextAction() and hasText(HOME_PLACEHOLDER, substring = true)) }

        chord(KeyEvent.KEYCODE_F)
        compose.waitUntil(10_000) { exists(hasTestTag(PaletteTags.CARD)) }
        paletteField.assertIsFocused()

        assertTrue(KeyEvent.KEYCODE_CTRL_LEFT in imeHeard)
        val shells = listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_F)
        assertTrue(shells.none { it in imeHeard })
    }

    @Test
    fun `beside the pane, Ctrl+B slides the rail as its own toggle does, already on its way in the first frame after the key`() {
        showShell(wide = true)
        compose.waitUntil(20_000) { displayed(SEARCH_CHATS) }
        val rest = left(SEARCH_CHATS)
        val railWidth = (CursorDimens.sidebarWidth + CursorDimens.hairline).value
        clockHeld {
            // The toggle's first frame has the rail where it starts; half-way on it is on its way.
            tap(hasContentDescription("Toggle sidebar"))
            frame()
            assertEquals(rest, left(SEARCH_CHATS), 0.5f)
            compose.mainClock.advanceTimeBy(SidebarRailMillis / 2L)
            assertTrue(left(SEARCH_CHATS) in (rest - railWidth + 1f)..(rest - 1f))
            settle()
            assertFalse(exists(SEARCH_CHATS))

            // Ctrl+B's first frame already has it coming in, and going out again, rather than where it was.
            chord(KeyEvent.KEYCODE_B)
            frame()
            assertTrue(left(SEARCH_CHATS) in (rest - railWidth + 0.5f)..(rest - 1f))
            settle()
            assertEquals(rest, left(SEARCH_CHATS), 0.5f)
            chord(KeyEvent.KEYCODE_B)
            frame()
            assertTrue(left(SEARCH_CHATS) in (rest - railWidth + 1f)..(rest - 0.5f))
            compose.mainClock.advanceTimeBy(SidebarRailMillis / 2L)
            assertTrue(left(SEARCH_CHATS) in (rest - railWidth + 1f)..(rest - 1f))
            settle()
            assertFalse(exists(SEARCH_CHATS))
        }
    }

    @Test
    fun `on a phone, Ctrl+B slides the drawer as the header's button does, already on its way in the first frame after the key`() {
        showShell(wide = false)
        clockHeld {
            // The button's first frame has the drawer where it starts, off the window.
            tap(hasContentDescription("Open sidebar"))
            frame()
            val shut = left(SEARCH_CHATS)
            settle()
            val open = left(SEARCH_CHATS)
            assertTrue(shut < open - 100f)

            chord(KeyEvent.KEYCODE_B)
            frame()
            assertTrue(left(SEARCH_CHATS) in (shut + 1f)..(open - 0.5f))
            settle()
            assertFalse(displayed(SEARCH_CHATS))

            chord(KeyEvent.KEYCODE_B)
            frame()
            assertTrue(left(SEARCH_CHATS) in (shut + 0.5f)..(open - 1f))
            settle()
            assertEquals(open, left(SEARCH_CHATS), 0.5f)
        }
    }

    @Test
    fun `Ctrl+Shift+B and Esc slide the chat's panel as its button does, already on its way in the first frame after the key`() {
        showShell(wide = true)
        searchAndOpen("House environment", HOUSE)
        clockHeld {
            // The button's first frame has the panel where it starts, off the window's edge.
            tap(hasContentDescription("Open panel"))
            frame()
            val shut = left(PANEL)
            settle()
            val open = left(PANEL)
            assertTrue(shut > open + 1f)

            press(KeyEvent.KEYCODE_ESCAPE)
            frame()
            assertTrue(left(PANEL) in (open + 0.5f)..(shut - 1f))
            settle()
            assertFalse(exists(PANEL))

            chord(KeyEvent.KEYCODE_B, shift = true)
            frame()
            assertTrue(left(PANEL) in (open + 1f)..(shut - 0.5f))
            settle()
            assertEquals(open, left(PANEL), 0.5f)
        }
    }

    @Test
    fun `the palette and the shortcuts sheet come and go within the frame their key lands`() {
        showShell(wide = true)
        clockHeld {
            chord(KeyEvent.KEYCODE_K)
            frame()
            assertTrue(exists(hasTestTag(PaletteTags.CARD)))
            press(KeyEvent.KEYCODE_ESCAPE)
            frame()
            assertFalse(exists(hasTestTag(PaletteTags.CARD)))
            chord(KeyEvent.KEYCODE_SLASH)
            frame()
            assertTrue(exists(hasTestTag(PaletteTags.SHORTCUTS)))
            chord(KeyEvent.KEYCODE_SLASH)
            frame()
            assertFalse(exists(hasTestTag(PaletteTags.CARD)))
        }
    }

    @Test
    fun `a screen the keyboard opens is on screen alone within the frame the key lands, where a tapped row and back slide`() {
        showShell(wide = true)
        compose.waitUntil(20_000) { onScreen(PROJECT) }
        clockHeld {
            // Ctrl+1 from the New Chat pane: the pane is gone in the same frame, not sliding out under the chat.
            chord(KeyEvent.KEYCODE_1)
            firstFrameWith { exists(CHAT_HEADER) }
            assertFalse(onScreen(HOME_PLACEHOLDER))
            settle()
            assertTrue(chatOpen(PROJECT))

            // Ctrl+Tab, let go: the chat it picks replaces this one outright.
            ctrlDown()
            press(KeyEvent.KEYCODE_TAB, ctrl = true)
            ctrlUp()
            firstFrameWith { !chatOpen(PROJECT) }
            assertEquals(1, compose.onAllNodes(CHAT_HEADER).fetchSemanticsNodes().size)
            settle()

            // Ctrl+N: the New Chat pane, alone.
            chord(KeyEvent.KEYCODE_N)
            firstFrameWith { onScreen(HOME_PLACEHOLDER) }
            assertFalse(exists(CHAT_HEADER))
            settle()

            // Ctrl+comma pushes Settings the same way.
            chord(KeyEvent.KEYCODE_COMMA)
            firstFrameWith { onScreen("Appearance") }
            assertFalse(onScreen(HOME_PLACEHOLDER))
            settle()

            // Back still slides: both screens are drawn while Settings goes.
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            firstFrameWith { onScreen(HOME_PLACEHOLDER) }
            assertTrue(onScreen("Appearance"))
            settle()
            assertFalse(onScreen("Appearance"))

            // And a row tapped slides its chat in over the pane.
            tap(hasText(CLI) and hasClickAction())
            firstFrameWith { exists(CHAT_HEADER) }
            assertTrue(onScreen(HOME_PLACEHOLDER))
            settle()
            assertFalse(onScreen(HOME_PLACEHOLDER))
        }
    }

    @Test
    fun `a chat switched to with Ctrl+Tab or Ctrl+1 opens with the caret in its composer, and one tapped in the sidebar does not`() {
        showShell(wide = true)
        searchAndOpen("Cli exploration", CLI)
        searchAndOpen("House environment", HOUSE)
        assertFalse(exists(isFocused() and hasSetTextAction()))

        ctrlDown()
        press(KeyEvent.KEYCODE_TAB, ctrl = true)
        ctrlUp()
        compose.waitUntil(20_000) { chatOpen(CLI) }
        compose.waitUntil(10_000) { exists(isFocused() and hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true)) }

        chord(KeyEvent.KEYCODE_1)
        compose.waitUntil(20_000) { chatOpen(PROJECT) }
        compose.waitUntil(10_000) { exists(isFocused() and hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true)) }

        compose.onAllNodes(hasText(HOUSE) and hasClickAction()).onFirst().performClick()
        compose.waitUntil(20_000) { chatOpen(HOUSE) }
        compose.waitForIdle()
        assertFalse(exists(isFocused() and hasSetTextAction()))
    }

    private fun left(matcher: SemanticsMatcher): Float = compose.onAllNodes(matcher).onFirst().getBoundsInRoot().left.value

    /**
     * Holds the test clock for [block], so what a key or a tap changes can be looked at a frame at a time ([frame]):
     * a key's change has to be in place at the end of the frame it lands in, where a tap's is still on its way.
     */
    private fun clockHeld(block: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            block()
        } finally {
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    private fun frame() = compose.mainClock.advanceTimeByFrame()

    /**
     * Frame by frame, a few at most, to the first that draws a change: the test clock can take a frame or two to get a
     * state change to the screen, and it is that first frame a key's change must already be complete in.
     */
    private fun firstFrameWith(drawn: () -> Boolean) {
        repeat(4) {
            frame()
            if (drawn()) return
        }
        throw AssertionError("not drawn within 4 frames")
    }

    /** Past the longest slide there is, and the frame after it. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        frame()
    }

    /** The tap's own action, run without a pointer: an injected tap would move the held clock along with it. */
    private fun tap(matcher: SemanticsMatcher) {
        compose.onAllNodes(matcher).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /**
     * Whether [text] comes up after [action], looked for frame by frame: left to run on, the test clock would carry a
     * snackbar through its whole showing inside one wait for idle. Ctrl+R's answer is the pull's, put away on the
     * main looper's clock, which the test clock does not move: it is moved here while the word is waited out.
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
            compose.waitUntil(20_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                !onScreen(text)
            }
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
        val SEARCH_CHATS = hasContentDescription("Search chats")
        val PANEL = hasTestTag("conversation-panel")
        val CHAT_HEADER = hasTestTag("chat-header")
    }
}
