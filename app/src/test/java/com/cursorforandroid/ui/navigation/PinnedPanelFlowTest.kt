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
import androidx.compose.ui.test.TouchInjectionScope
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
import androidx.compose.ui.test.onRoot
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
import com.cursorforandroid.ui.components.BackEdgeMinWidth
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.HapticLog
import com.cursorforandroid.ui.components.ShadowHapticLog
import com.cursorforandroid.ui.components.constant
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

    /** How wide the rail's chat list is laid out, in pixels (a dp here), past the clip its slide draws it through. */
    private fun railListWidth(): Int = compose.onAllNodes(hasScrollToNodeAction()).onFirst().fetchSemanticsNode().size.width

    /** How wide a resize edge says its pane is, as TalkBack reads it. */
    private fun edgeSays(edge: String): String? =
        compose.onNodeWithContentDescription(edge).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /** Where a resize edge's boundary is across the window: the middle of the strip that straddles it. */
    private fun edgeX(edge: String): Float = bounds(hasContentDescription(edge)).center.x

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

    /** A finger down on the boundary, across by [dx] and up: the edge follows it the whole way. */
    private fun drag(edge: String, dx: Float) {
        compose.onNodeWithContentDescription(edge).performTouchInput {
            down(center)
            moveBy(Offset(dx, 0f))
            up()
        }
        compose.waitForIdle()
    }

    /** A finger down at [from] in the window's coordinates, moved by ([dx], [dy]) a frame at a time, then [finish]ed. */
    private fun gesture(from: Offset, dx: Float, dy: Float = 0f, finish: TouchInjectionScope.() -> Unit = { up() }) {
        compose.onRoot().performTouchInput {
            down(from)
            repeat(10) { moveBy(Offset(dx / 10, dy / 10), delayMillis = 16) }
            finish()
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
    fun `the pinned panel slides open and shut rather than appearing, the chat reflowing into the room as it goes`() {
        showShell()
        openChat(CLI)
        compose.mainClock.autoAdvance = false

        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.mainClock.advanceTimeBy(SLIDE_MS / 2)
        val opening = chatBounds().right
        assertThat(opening).isGreaterThan(880.5f)
        assertThat(opening).isLessThan(1279.5f)
        assertThat(panelBounds().left).isWithin(1f).of(opening)
        compose.mainClock.advanceTimeBy(SLIDE_MS)
        assertThat(chatBounds().right).isWithin(0.5f).of(880f)
        assertThat(panelBounds().left).isWithin(0.5f).of(880f)

        compose.onNodeWithContentDescription(HIDE_PANEL).performClick()
        compose.mainClock.advanceTimeBy(SLIDE_MS / 2)
        val shutting = chatBounds().right
        assertThat(shutting).isGreaterThan(880.5f)
        assertThat(shutting).isLessThan(1279.5f)
        assertThat(panelBounds().left).isWithin(1f).of(shutting)
        compose.mainClock.advanceTimeBy(SLIDE_MS)
        assertThat(chatBounds().right).isWithin(0.5f).of(1280f)

        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { !panelShown() && described(OPEN_PANEL) }
        assertKept(false) { prefs.panelOpen(PaneWidthClass.Expanded).first() }
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
        assertThat(edgeSays(RESIZE_PANEL)).isEqualTo("400 dp wide")
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("278 dp wide")

        // Dragged past its widest it stops there, half the window; past its narrowest, likewise.
        drag(RESIZE_PANEL, -400f)
        assertThat(panelBounds().width).isWithin(0.5f).of(640f)
        assertThat(edgeSays(RESIZE_PANEL)).isEqualTo("640 dp wide")
        assertThat(chatBounds().right).isWithin(0.5f).of(640f)
        assertKept(640) { prefs.panelWidthDp.first() }
        drag(RESIZE_PANEL, 600f)
        assertThat(panelBounds().width).isWithin(0.5f).of(280f)
        assertKept(280) { prefs.panelWidthDp.first() }

        // The rail as wide as it goes, which the narrow panel leaves it room for.
        drag(RESIZE_SIDEBAR, 200f)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("400 dp wide")
        assertThat(chatBounds().left).isWithin(0.5f).of(400f)
        assertThat(chatBounds().right).isWithin(0.5f).of(1000f)
        assertKept(400) { prefs.railWidthDp.first() }

        // The panel widened again takes the rail's room before the chat's: the rail narrows, the chat keeps its least.
        drag(RESIZE_PANEL, -400f)
        assertThat(panelBounds().width).isWithin(0.5f).of(640f)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("320 dp wide")
        assertThat(chatBounds().width).isWithin(0.5f).of(320f)
        // Only for as long as the panel needs it: what the reader dragged the rail to is what is kept.
        assertKept(400) { prefs.railWidthDp.first() }
    }

    @Test
    fun `either edge drags from anywhere along it, and a scroll that starts on it is the pane's under it`() {
        showShell()
        openChat(CLI)
        openPanel()
        val low = composer.fetchSemanticsNode().boundsInRoot.center.y
        // At the top, level with the panel's tabs, just inside the panel.
        gesture(Offset(edgeX(RESIZE_PANEL) + 5f, bounds(hasTestTag(PANEL_TABS)).center.y), dx = -100f)
        assertThat(panelBounds().width).isWithin(0.5f).of(500f)
        // Low down, level with the composer, just inside the chat.
        gesture(Offset(edgeX(RESIZE_PANEL) - 5f, low), dx = 100f)
        assertThat(panelBounds().width).isWithin(0.5f).of(400f)
        assertKept(400) { prefs.panelWidthDp.first() }
        // The rail's the same: level with its header just inside it, then level with the composer just inside the chat.
        gesture(Offset(edgeX(RESIZE_SIDEBAR) - 5f, 24f), dx = 40f)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("318 dp wide")
        gesture(Offset(edgeX(RESIZE_SIDEBAR) + 5f, low), dx = -40f)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("278 dp wide")
        assertKept(278) { prefs.railWidthDp.first() }

        // Up the rail's list from the strip over its edge, a little sideways drift and all: the list scrolls, and
        // neither pane moves.
        val rail = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
        val scrolledBy = { rail.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() }
        val before = scrolledBy()
        gesture(Offset(edgeX(RESIZE_SIDEBAR) - 4f, 600f), dx = 12f, dy = -300f)
        assertThat(scrolledBy()).isGreaterThan(before)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("278 dp wide")
        assertThat(panelBounds().width).isWithin(0.5f).of(400f)
    }

    @Test
    fun `a back swipe from the window's edge while the panel slides open is back's, not the panel's edge's`() {
        showShell()
        openChat(CLI)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        repeat(4) { if (!described(RESIZE_PANEL)) compose.mainClock.advanceTimeByFrame() }
        // Just set out, the panel's edge, and the strip that drags it, are still inside the window's right back strip.
        val edge = edgeX(RESIZE_PANEL)
        val window = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        assertThat(edge).isGreaterThan(window - BackEdgeMinWidth.value)
        // On 3-button navigation the finger lifts; with gestures the system takes it, and the app hears a cancel.
        gesture(Offset(edge - 2f, 400f), dx = -150f)
        gesture(Offset(edge - 2f, 400f), dx = -150f) { cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { panelShown() }
        compose.waitForIdle()
        assertThat(panelBounds().width).isWithin(0.5f).of(400f)
        assertThat(edgeSays(RESIZE_PANEL)).isEqualTo("400 dp wide")
        assertThat(kept { prefs.panelWidthDp.first() }).isNull()
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
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("240 dp wide")
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
    @Config(qualifiers = "w840dp-h700dp-night-mdpi")
    fun `on a foldable the rail the pinned panel puts away slides off as wide as it stood, not narrowed first`() {
        showShell()
        openChat(CLI)
        val stood = railListWidth()

        // The panel takes the rail's room in the same write that sends the rail away: laid out again at its narrowest
        // for the frames it is still seen, it would jump in by the difference, and the chat out with it.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertThat(railListWidth()).isEqualTo(stood)
        assertThat(chatBounds().left).isGreaterThan(200.5f)
        compose.mainClock.advanceTimeBy(SidebarRailMillis / 2L)
        assertThat(railListWidth()).isEqualTo(stood)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { !railShown() && described(OPEN_SIDEBAR) }
        compose.waitForIdle()
        assertThat(chatBounds().left).isWithin(0.5f).of(0f)
    }

    @Test
    @Config(qualifiers = "w840dp-h700dp-night-mdpi")
    fun `on a foldable a key puts the pinned panel, and the rail it moves, in place in the frame it lands, and a tap after it slides`() {
        showShell()
        openChat(CLI)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        // Ctrl+Shift+B: the panel stands open, and the rail it leaves no room is gone, in the one frame.
        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.mainClock.advanceTimeByFrame()
        assertThat(panelBounds().width).isWithin(0.5f).of(400f)
        assertThat(chatBounds().left).isWithin(0.5f).of(0f)
        assertThat(chatBounds().right).isWithin(0.5f).of(440f)
        assertThat(described(SEARCH_CHATS)).isFalse()

        // Ctrl+B brings the rail over the chat at once, and puts it away the same.
        chord(KeyEvent.KEYCODE_B)
        compose.mainClock.advanceTimeByFrame()
        assertThat(described(CLOSE_DRAWER)).isTrue()
        assertThat(bounds(hasContentDescription(SEARCH_CHATS)).left).isAtLeast(0f)
        chord(KeyEvent.KEYCODE_B)
        compose.mainClock.advanceTimeByFrame()
        assertThat(described(SEARCH_CHATS)).isFalse()

        // Shut by the key, the rail is back beside the chat as the panel goes.
        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.mainClock.advanceTimeByFrame()
        assertThat(chatBounds().left).isWithin(0.5f).of(278f)
        assertThat(chatBounds().right).isWithin(0.5f).of(840f)

        // The panel's button after the keys slides it open, and the rail off with it.
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.mainClock.advanceTimeBy(SidebarRailMillis / 2L)
        assertThat(chatBounds().left).isGreaterThan(0.5f)
        assertThat(chatBounds().left).isLessThan(277.5f)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { !railShown() && described(OPEN_SIDEBAR) }
        assertKept(true) { prefs.panelOpen(PaneWidthClass.Expanded).first() }
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
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("240 dp wide")
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

    private val landed: Int get() = Haptic.GestureEnd.constant()

    private val stop: Int get() = Haptic.SlotTick.constant()

    @Test
    @Config(shadows = [ShadowHapticLog::class])
    fun `the pinned panel is felt once each way a button slides it, as it lands, and not as a key puts it in place or the next chat opens on it`() {
        showShell()
        openChat(CLI)
        HapticLog.clear()

        openPanel()
        assertThat(HapticLog.played).containsExactly(landed)
        compose.onNode(hasTestTag(PANEL_CLOSE)).performClick()
        compose.waitUntil(10_000) { !panelShown() && described(OPEN_PANEL) }
        compose.waitForIdle()
        assertThat(HapticLog.played).containsExactly(landed, landed)
        openPanel()
        compose.onNodeWithContentDescription(HIDE_PANEL).performClick()
        compose.waitUntil(10_000) { !panelShown() && described(OPEN_PANEL) }
        compose.waitForIdle()
        assertThat(HapticLog.played).containsExactly(landed, landed, landed, landed)

        HapticLog.clear()
        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { panelShown() && described(HIDE_PANEL) }
        openChat(HOUSE)
        assertThat(panelShown()).isTrue()
        chord(KeyEvent.KEYCODE_B, shift = true)
        compose.waitUntil(10_000) { !panelShown() && described(OPEN_PANEL) }
        compose.waitForIdle()
        assertThat(HapticLog.played).isEmpty()
    }

    @Test
    @Config(shadows = [ShadowHapticLog::class])
    fun `a pinned panel's slide turned back is felt once, as it lands where it was last sent, and never on its way`() {
        showShell()
        openChat(CLI)
        HapticLog.clear()
        compose.mainClock.autoAdvance = false

        // Turned back a sixth of the way out: the open it never finished is not felt, the shut it lands is.
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.mainClock.advanceTimeBy(SLIDE_MS / 10)
        compose.onNodeWithContentDescription(HIDE_PANEL).performClick()
        compose.mainClock.advanceTimeBy(SLIDE_MS * 2)
        assertThat(chatBounds().right).isWithin(0.5f).of(1280f)
        assertThat(HapticLog.played).containsExactly(landed)

        // Most of the way out it is still on its way, and turned back it is felt only as it lands shut.
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.mainClock.advanceTimeBy(SLIDE_MS * 2 / 5)
        assertThat(chatBounds().right).isLessThan(1280f - 200f)
        assertThat(HapticLog.played).containsExactly(landed)
        compose.onNodeWithContentDescription(HIDE_PANEL).performClick()
        compose.mainClock.advanceTimeBy(SLIDE_MS * 2)
        assertThat(chatBounds().right).isWithin(0.5f).of(1280f)
        assertThat(HapticLog.played).containsExactly(landed, landed)

        // Let run, it is felt as it lands open.
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.mainClock.advanceTimeBy(SLIDE_MS * 2)
        assertThat(panelBounds().width).isWithin(0.5f).of(400f)
        assertThat(HapticLog.played).containsExactly(landed, landed, landed)
        compose.mainClock.autoAdvance = true
    }

    @Test
    @Config(shadows = [ShadowHapticLog::class])
    fun `each edge is felt once where its pane stops, and not while it follows the finger or narrows the rail on the way`() {
        showShell()
        openChat(CLI)
        openPanel()
        HapticLog.clear()

        drag(RESIZE_PANEL, -100f)
        drag(RESIZE_SIDEBAR, 100f)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("378 dp wide")
        assertThat(HapticLog.played).isEmpty()

        // Out past its widest, the rail narrowed to make room for it; then in past its narrowest.
        drag(RESIZE_PANEL, -400f)
        assertThat(panelBounds().width).isWithin(0.5f).of(640f)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("320 dp wide")
        assertThat(HapticLog.played).containsExactly(stop)
        drag(RESIZE_PANEL, 600f)
        assertThat(panelBounds().width).isWithin(0.5f).of(280f)
        // The rail out past its widest, and in past its narrowest.
        drag(RESIZE_SIDEBAR, 300f)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("400 dp wide")
        drag(RESIZE_SIDEBAR, -300f)
        assertThat(edgeSays(RESIZE_SIDEBAR)).isEqualTo("200 dp wide")
        assertThat(HapticLog.played).containsExactly(stop, stop, stop, stop)
    }

    @Test
    @Config(qualifiers = "w840dp-h700dp-night-mdpi", shadows = [ShadowHapticLog::class])
    fun `on a foldable the pinned panel is felt once as it lands, whatever the rail does beside it, and the rail making way for its edge is not felt`() {
        showShell()
        openChat(CLI)
        HapticLog.clear()

        // Opened, it sends the rail off with it: the one landing is felt, not the rail's slide too.
        openPanel()
        compose.waitUntil(10_000) { !railShown() && described(OPEN_SIDEBAR) }
        compose.waitForIdle()
        assertThat(HapticLog.played).containsExactly(landed)

        // Narrowed to its least, which brings the rail back, and widened again, which sends it off: only the stop.
        HapticLog.clear()
        drag(RESIZE_PANEL, 200f)
        compose.waitUntil(10_000) { railShown() && !described(OPEN_SIDEBAR) }
        drag(RESIZE_PANEL, -200f)
        compose.waitUntil(10_000) { !railShown() && described(OPEN_SIDEBAR) }
        compose.waitForIdle()
        assertThat(HapticLog.played).containsExactly(stop)

        // Shut with the rail over the chat, which then settles in beside it: the one landing again.
        compose.onNodeWithContentDescription(OPEN_SIDEBAR).performClick()
        compose.waitUntil(10_000) { described(CLOSE_DRAWER) && railShown() }
        compose.waitForIdle()
        HapticLog.clear()
        compose.onNodeWithContentDescription(HIDE_PANEL).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { !described(CLOSE_DRAWER) && !panelShown() && !described(OPEN_SIDEBAR) }
        compose.waitForIdle()
        assertThat(HapticLog.played).containsExactly(landed)
    }

    private fun fieldText(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val CLI = "Cli exploration"
        const val HOUSE = "House environment overhaul"
        const val PANEL = "conversation-panel"
        const val PANEL_CLOSE = "panel-close"
        const val OPEN_PANEL = "Open panel"
        const val HIDE_PANEL = "Hide panel"
        const val DISMISS_PANEL = "Dismiss panel"
        const val RESIZE_PANEL = "Resize panel"
        const val RESIZE_SIDEBAR = "Resize sidebar"
        const val PANEL_TABS = "panel-tabs"
        const val SEARCH_CHATS = "Search chats"
        const val OPEN_SIDEBAR = "Open sidebar"
        const val CLOSE_DRAWER = "Close navigation menu"
        /** The panel's slide end to end (SidePanel's SlideMillis). */
        const val SLIDE_MS = 300L
        const val Draft = "Half a thought about the"
    }
}
