package com.cursorforandroid.ui.navigation

import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.panel.PaneWidthClass
import com.cursorforandroid.ui.shortcuts.KeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.LocalKeyboardShortcuts
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The conversation panel pinned beside the chat on wide windows, in the running shell on the demo, at mdpi so that a
 * pixel is a dp: the chat narrows for it and keeps its composer's keyboard, back and Esc stay the chat's, it stands
 * open for every chat and across restarts for its window size class, its edge and the rail's drag to resize them
 * within their ranges and the widths are kept, and where it leaves the rail no room the rail makes way and comes
 * over the chat on asking.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-night-mdpi")
class PinnedPanelFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private lateinit var keys: KeyboardShortcuts

    /** The shell on a wide window, on a device that kept whatever [before] puts there. */
    private fun showShell(before: suspend AppGraph.() -> Unit = {}) {
        graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking {
            graph.session.enterDemo()
            graph.before()
        }
        keys = KeyboardShortcuts(compose.activity.lifecycleScope)
        compose.setContent {
            CompositionLocalProvider(LocalKeyboardShortcuts provides keys) {
                CursorTheme(mode = ThemeMode.Dark) {
                    AppShell(
                        graph = graph,
                        user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                        isDemo = true,
                        wide = true,
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

    private fun described(description: String) = exists(hasContentDescription(description))

    private fun displayed(matcher: SemanticsMatcher) = runCatching { compose.onAllNodes(matcher).onFirst().assertIsDisplayed() }.isSuccess

    private fun chatOpen(name: String) = exists(hasTestTag("chat-header") and hasContentDescription(name))

    private fun panelShown() = displayed(hasTestTag(PANEL))

    private fun railShown() = displayed(hasContentDescription(SEARCH_CHATS))

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun panelBounds() = bounds(hasTestTag(PANEL))

    /** The chat's column, as its transcript spans it. */
    private fun chatBounds() = bounds(hasTestTag("transcript"))

    /** How wide a resize handle says its pane is, as TalkBack reads it. */
    private fun handleSays(handle: String): String? =
        compose.onNodeWithContentDescription(handle).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun <T> kept(read: suspend AppGraph.() -> T): T = runBlocking { graph.read() }

    /** What the device keeps, once the write the shell launched for it has landed. */
    private fun <T> assertKept(expected: T, read: suspend AppGraph.() -> T) {
        compose.waitUntil(5_000) { kept(read) == expected }
    }

    private fun softInputVisible(): Boolean =
        shadowOf(compose.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    private val composer: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true))

    private val written: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasText(Draft))

    private fun startWriting() {
        composer.performClick()
        composer.performTextInput(Draft)
        written.assertIsFocused()
        compose.waitUntil(5_000) { softInputVisible() }
    }

    /** A key from a hardware keyboard: the app's reader first, then the window, as the activity dispatches it. */
    private fun send(action: Int, code: Int, ctrl: Boolean = false, shift: Boolean = false) {
        var meta = 0
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val event = KeyEvent(0L, 0L, action, code, 0, meta, 7, 0, 0, InputDevice.SOURCE_KEYBOARD)
        compose.runOnUiThread { if (!keys.onKeyEvent(event)) compose.activity.dispatchKeyEvent(event) }
        compose.waitForIdle()
    }

    private fun press(code: Int, ctrl: Boolean = false, shift: Boolean = false) {
        send(KeyEvent.ACTION_DOWN, code, ctrl, shift)
        send(KeyEvent.ACTION_UP, code, ctrl, shift)
    }

    /** Ctrl held, [code] pressed, Ctrl let go. */
    private fun chord(code: Int, shift: Boolean = false) {
        send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl = true)
        press(code, ctrl = true, shift = shift)
        send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT)
    }

    /** The chat called [name], tapped in the rail, whose list is the first to scroll in the wide shell. */
    private fun openChat(name: String) {
        compose.waitUntil(30_000) { exists(hasScrollToNodeAction()) }
        compose.waitUntil(30_000) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(name, substring = true)) }.isSuccess
        }
        compose.onAllNodesWithText(name).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(20_000) { chatOpen(name) && onScreen(CHAT_PLACEHOLDER) }
        compose.waitForIdle()
    }

    private fun openPanel() {
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.waitUntil(10_000) { described(HIDE_PANEL) }
        compose.waitForIdle()
    }

    /** A finger down on the handle, across by [dx] and up: past touch slop, so the edge moves [dx] less the slop. */
    private fun drag(handle: String, dx: Float) {
        compose.onNodeWithContentDescription(handle).performTouchInput {
            down(center)
            moveBy(Offset(dx, 0f))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun `the panel opens pinned beside the chat, which narrows for it and keeps its composer's keyboard`() {
        showShell()
        openChat(CLI)
        startWriting()

        openPanel()
        // A pane of the layout rather than a sheet: nothing to dismiss it by, and the composer is left be.
        assertThat(described(DISMISS_PANEL)).isFalse()
        written.assertIsFocused()
        assertThat(softInputVisible()).isTrue()
        val panel = panelBounds()
        assertThat(panel.width).isWithin(0.5f).of(400f)
        assertThat(panel.right).isWithin(0.5f).of(1280f)
        // The chat, composer and all, ends where the panel begins, beside the rail at its own width.
        val chat = chatBounds()
        assertThat(chatOpen(CLI)).isTrue()
        assertThat(chat.left).isWithin(0.5f).of(278f)
        assertThat(chat.right).isWithin(0.5f).of(panel.left)
        assertThat(written.fetchSemanticsNode().boundsInRoot.right).isAtMost(panel.left + 0.5f)
        assertThat(railShown()).isTrue()
        assertKept(true) { prefs.panelOpen(PaneWidthClass.Expanded).first() }
    }

    @Test
    fun `back is the chat's beside a pinned panel, which stands open for the next chat until it is hidden`() {
        showShell()
        openChat(CLI)
        openPanel()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { !chatOpen(CLI) }
        compose.waitForIdle()
        assertThat(onScreen(HOME_PLACEHOLDER)).isTrue()
        assertThat(panelShown()).isFalse()
        assertKept(true) { prefs.panelOpen(PaneWidthClass.Expanded).first() }

        // Open from its first frame, with nothing pressed.
        openChat(HOUSE)
        assertThat(described(HIDE_PANEL)).isTrue()
        assertThat(panelShown()).isTrue()
        assertThat(panelBounds().width).isWithin(0.5f).of(400f)

        compose.onNodeWithContentDescription(HIDE_PANEL).performClick()
        compose.waitUntil(10_000) { !panelShown() && described(OPEN_PANEL) }
        assertThat(chatBounds().right).isWithin(0.5f).of(1280f)
        assertKept(false) { prefs.panelOpen(PaneWidthClass.Expanded).first() }

        openChat(CLI)
        assertThat(described(OPEN_PANEL)).isTrue()
        assertThat(panelShown()).isFalse()
    }

    @Test
    fun `Ctrl+Shift+B pins the panel and puts it away, Ctrl+B still moves the rail beside it, and Esc leaves the composer rather than the panel`() {
        showShell()
        openChat(HOUSE)
        assertThat(panelShown()).isFalse()

        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { panelShown() && described(HIDE_PANEL) }
        assertKept(true) { prefs.panelOpen(PaneWidthClass.Expanded).first() }

        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { !railShown() }
        assertThat(panelShown()).isTrue()
        assertThat(chatBounds().left).isWithin(0.5f).of(0f)
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { railShown() }
        assertThat(panelShown()).isTrue()

        startWriting()
        press(KeyEvent.KEYCODE_ESCAPE)
        written.assertIsNotFocused()
        assertThat(fieldText(written)).isEqualTo(Draft)
        assertThat(panelShown()).isTrue()

        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { !panelShown() && described(OPEN_PANEL) }
        assertKept(false) { prefs.panelOpen(PaneWidthClass.Expanded).first() }
    }

    @Test
    fun `the panel's edge and the rail's drag to resize them within their ranges, the rail giving way first, and the widths are kept`() {
        showShell()
        openChat(CLI)
        openPanel()
        assertThat(handleSays(RESIZE_PANEL)).isEqualTo("400 dp wide")
        assertThat(handleSays(RESIZE_SIDEBAR)).isEqualTo("278 dp wide")

        // Dragged past its widest it stops there, half the window; past its narrowest, likewise.
        drag(RESIZE_PANEL, -400f)
        assertThat(panelBounds().width).isWithin(0.5f).of(640f)
        assertThat(handleSays(RESIZE_PANEL)).isEqualTo("640 dp wide")
        assertThat(chatBounds().right).isWithin(0.5f).of(640f)
        assertKept(640) { prefs.panelWidthDp.first() }
        drag(RESIZE_PANEL, 600f)
        assertThat(panelBounds().width).isWithin(0.5f).of(280f)
        assertKept(280) { prefs.panelWidthDp.first() }

        // The rail as wide as it goes, which the narrow panel leaves it room for.
        drag(RESIZE_SIDEBAR, 200f)
        assertThat(handleSays(RESIZE_SIDEBAR)).isEqualTo("400 dp wide")
        assertThat(chatBounds().left).isWithin(0.5f).of(400f)
        assertThat(chatBounds().right).isWithin(0.5f).of(1000f)
        assertKept(400) { prefs.railWidthDp.first() }

        // The panel widened again takes the rail's room before the chat's: the rail narrows, the chat keeps its least.
        drag(RESIZE_PANEL, -400f)
        assertThat(panelBounds().width).isWithin(0.5f).of(640f)
        assertThat(handleSays(RESIZE_SIDEBAR)).isEqualTo("320 dp wide")
        assertThat(chatBounds().width).isWithin(0.5f).of(320f)
        // Only for as long as the panel needs it: what the reader dragged the rail to is what is kept.
        assertKept(400) { prefs.railWidthDp.first() }
    }

    @Test
    @Config(qualifiers = "w840dp-h700dp-night-mdpi")
    fun `on a foldable the pinned panel puts the rail away, which comes over the chat on asking and back beside it with room`() {
        showShell()
        openChat(CLI)
        assertThat(railShown()).isTrue()

        openPanel()
        compose.waitUntil(10_000) { !railShown() && described(OPEN_SIDEBAR) }
        compose.waitForIdle()
        assertThat(panelBounds().width).isWithin(0.5f).of(400f)
        assertThat(chatBounds().left).isWithin(0.5f).of(0f)
        assertThat(chatBounds().right).isWithin(0.5f).of(440f)

        // Asked for, by its button or Ctrl+B, the rail comes over the chat and the panel stays where it is.
        compose.onNodeWithContentDescription(OPEN_SIDEBAR).performClick()
        compose.waitUntil(10_000) { described(CLOSE_DRAWER) && railShown() }
        assertThat(panelShown()).isTrue()
        compose.onNodeWithContentDescription(CLOSE_DRAWER).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { !described(CLOSE_DRAWER) && !railShown() }
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { described(CLOSE_DRAWER) && railShown() }
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { !described(CLOSE_DRAWER) && !railShown() }

        // The panel at its narrowest leaves the rail room beside the chat again, narrower than it was.
        drag(RESIZE_PANEL, 200f)
        assertThat(panelBounds().width).isWithin(0.5f).of(280f)
        compose.waitUntil(10_000) { railShown() && !described(OPEN_SIDEBAR) }
        compose.waitForIdle()
        assertThat(handleSays(RESIZE_SIDEBAR)).isEqualTo("240 dp wide")
        assertThat(chatBounds().width).isWithin(0.5f).of(320f)
        drag(RESIZE_PANEL, -200f)
        compose.waitUntil(10_000) { !railShown() && described(OPEN_SIDEBAR) }

        // Over the chat when the panel goes, the rail settles in beside it.
        chord(KeyEvent.KEYCODE_B)
        compose.waitUntil(10_000) { described(CLOSE_DRAWER) }
        compose.onNodeWithContentDescription(HIDE_PANEL).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { !described(CLOSE_DRAWER) && !panelShown() && !described(OPEN_SIDEBAR) }
        compose.waitForIdle()
        assertThat(railShown()).isTrue()
        assertThat(chatBounds().left).isWithin(0.5f).of(278f)
    }

    @Test
    fun `a panel left pinned open comes back open at the widths the rail and it were left at, after a restart`() {
        showShell {
            prefs.setPanelOpen(PaneWidthClass.Expanded, true)
            prefs.setPanelWidthDp(480)
            prefs.setRailWidthDp(240)
        }
        openChat(HOUSE)
        assertThat(described(HIDE_PANEL)).isTrue()
        assertThat(panelShown()).isTrue()
        assertThat(panelBounds().width).isWithin(0.5f).of(480f)
        assertThat(handleSays(RESIZE_SIDEBAR)).isEqualTo("240 dp wide")
        assertThat(chatBounds().left).isWithin(0.5f).of(240f)
        assertThat(chatBounds().right).isWithin(0.5f).of(800f)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-night-mdpi")
    fun `each window size class keeps its own panel, so one left open on its side waits shut upright`() {
        showShell { prefs.setPanelOpen(PaneWidthClass.Expanded, true) }
        openChat(CLI)
        assertThat(described(OPEN_PANEL)).isTrue()
        assertThat(panelShown()).isFalse()

        openPanel()
        assertThat(panelShown()).isTrue()
        assertKept(true) { prefs.panelOpen(PaneWidthClass.Medium).first() }
        // Upright, the panel at the width it opens at leaves the rail no room beside the chat.
        compose.waitUntil(10_000) { !railShown() && described(OPEN_SIDEBAR) }
        assertThat(chatBounds().width).isWithin(0.5f).of(400f)
    }

    private fun fieldText(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val CLI = "Cli exploration"
        const val HOUSE = "House environment overhaul"
        const val PANEL = "conversation-panel"
        const val OPEN_PANEL = "Open panel"
        const val HIDE_PANEL = "Hide panel"
        const val DISMISS_PANEL = "Dismiss panel"
        const val RESIZE_PANEL = "Resize panel"
        const val RESIZE_SIDEBAR = "Resize sidebar"
        const val SEARCH_CHATS = "Search chats"
        const val OPEN_SIDEBAR = "Open sidebar"
        const val CLOSE_DRAWER = "Close navigation menu"
        const val Draft = "Half a thought about the"
    }
}
