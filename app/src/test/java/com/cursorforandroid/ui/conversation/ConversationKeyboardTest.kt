package com.cursorforandroid.ui.conversation

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The keyboard and the chat, on the real screen with the demo's transcript: the follow-up composer rides the
 * keyboard's inset, the transcript keeps its newest turn on the composer while the viewport changes, and leaving the
 * chat takes the keyboard along rather than leaving it standing over the pane underneath.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ConversationKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Composable
    private fun App(graph: AppGraph) {
        CursorTheme(mode = ThemeMode.Dark) {
            CursorRoot(graph = graph, deepLinkAgentId = null, onDeepLinkConsumed = {})
        }
    }

    @Before
    fun openChat() {
        graph = AppGraph(ApplicationProvider.getApplicationContext())
        compose.setContent { App(graph) }
        waitForText("Try the demo")
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntil(20_000) { graph.session.state.value is SessionState.SignedIn }
        waitForText(HOME_PLACEHOLDER, 30_000)
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(IDLE_CHAT, substring = true)) }.isSuccess
        }
        waitForText(IDLE_CHAT, 30_000)
        compose.onAllNodesWithText(IDLE_CHAT).onFirst().performClick()
        waitForText(CHAT_PLACEHOLDER)
        // The transcript itself, not just the frame around it: the demo replays the chat's runs on open.
        waitForText(CHAT_PROMPT, 30_000)
        compose.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithText(text: String) = onAllNodesWithText(text).onFirst()

    /** The chat's follow-up field; the New Chat pane's composer is on screen too while a pop reveals it. */
    private val field get() = compose.onNode(hasSetTextAction() and hasText(CHAT_PLACEHOLDER, substring = true))

    /** Whether this node is a row of a lazy list: the transcript, or the drawer's chat list composed off screen. */
    private fun SemanticsNode.inLazyList(): Boolean {
        var node: SemanticsNode? = parent
        while (node != null) {
            if (node.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)) return true
            node = node.parent
        }
        return false
    }

    private fun fieldBounds(): Rect = field.fetchSemanticsNode().boundsInRoot

    private fun rootBottom(): Float = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom

    /**
     * The bottom edge of the lowest transcript text on screen: the newest turn, sitting on the composer. The drawer's
     * rows are composed just off the start edge, so only nodes within the window count.
     */
    private fun newestTurnBottom(): Float {
        val fieldTop = fieldBounds().top
        return compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
            .filter { it.config.contains(SemanticsProperties.Text) && !it.config.contains(SemanticsProperties.EditableText) && it.inLazyList() }
            .map(SemanticsNode::boundsInRoot)
            .filter { it.left >= 0f && it.bottom <= fieldTop && it.height > 0f }
            .maxOf { it.bottom }
    }

    private fun dispatchInsets(navigationBar: Int, ime: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
            .build()
        val target = composeView()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(target, insets) }
        compose.waitForIdle()
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun softInputVisible(): Boolean =
        shadowOf(compose.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    /**
     * The demo chat's transcript is a prompt, a reply and a footer, shorter than the viewport with the keyboard up, so
     * it reads from the top and the keyboard must not drag it about; a transcript taller than the viewport is what
     * KeyboardInsetsTest anchors to the composer, on the same list configuration.
     */
    @Test
    fun `the follow-up composer rides the keyboard while a short transcript keeps its place`() {
        dispatchInsets(NavigationBar, 0)
        val restingField = fieldBounds()
        val restingTurn = newestTurnBottom()
        // The composer's field clears the navigation bar at rest; the transcript sits above the composer.
        assertThat(restingField.bottom).isLessThan(rootBottom() - NavigationBar)
        assertThat(restingTurn).isLessThan(restingField.top)

        // The keyboard climbs: on every frame the composer is exactly as far up as the keyboard is taller than the
        // bar — nothing while the keyboard is still below the bar's height — and keeps its own height.
        for (ime in listOf(60, 126, 240, 480, 720, 900)) {
            dispatchInsets(NavigationBar, ime)
            val lift = (ime - NavigationBar).coerceAtLeast(0).toFloat()
            assertThat(fieldBounds().bottom).isWithin(0.5f).of(restingField.bottom - lift)
            assertThat(fieldBounds().height).isWithin(0.5f).of(restingField.height)
            assertThat(newestTurnBottom()).isWithin(0.5f).of(restingTurn)
            assertThat(newestTurnBottom()).isLessThan(fieldBounds().top)
        }

        // And back down, landing where it started: no gap left under the composer, nothing overlapping the bar.
        for (ime in listOf(720, 480, 240, 126, 60, 0)) {
            dispatchInsets(NavigationBar, ime)
            val lift = (ime - NavigationBar).coerceAtLeast(0).toFloat()
            assertThat(fieldBounds().bottom).isWithin(0.5f).of(restingField.bottom - lift)
            assertThat(newestTurnBottom()).isWithin(0.5f).of(restingTurn)
        }
        assertThat(fieldBounds()).isEqualTo(restingField)
    }

    @Test
    fun `leaving the chat releases the composer's focus and asks the keyboard away as the pane starts to go`() {
        field.performClick()
        field.assertIsFocused()
        compose.waitUntil(5_000) { softInputVisible() }

        // Freeze the clock so the pop is caught on its first frame, with the chat still on screen.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        field.assertIsNotFocused()
        assertThat(softInputVisible()).isFalse()
        // The chat is still there, mid-transition: the keyboard left first, not after the pane was gone.
        assertThat(compose.onAllNodes(hasText(CHAT_PLACEHOLDER, substring = true)).fetchSemanticsNodes()).isNotEmpty()

        compose.mainClock.autoAdvance = true
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(CHAT_PLACEHOLDER, substring = true)).fetchSemanticsNodes().isEmpty() }
        assertThat(softInputVisible()).isFalse()
    }

    private companion object {
        const val IDLE_CHAT = "Cli exploration"
        const val CHAT_PROMPT = "Explore how the Cursor CLI resumes cloud agents"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        /** 48dp at 420dpi, the gesture navigation bar of the reference device. */
        const val NavigationBar = 126
    }
}
