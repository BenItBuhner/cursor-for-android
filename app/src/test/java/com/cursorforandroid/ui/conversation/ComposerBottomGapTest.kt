package com.cursorforandroid.ui.conversation

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
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
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where the follow-up composer's box ends up against the window's edges, in the real chat with the demo's transcript,
 * for each way a phone can draw its navigation and with the keyboard closed and open. The window's insets are
 * dispatched with the platform's own values: 24dp for the gesture bar (the home handle's region), 48dp for three
 * buttons, none when a device hides its bar, and a 300dp keyboard over each.
 *
 * What is held: the gap under the box — to the bar, to the keyboard, or to the window's edge when there is neither —
 * is never smaller than the gap at its sides and only slightly larger, and it is the same in every one of the six
 * configurations, so no configuration pads for the keyboard and the bar both. Where the composer is not full width
 * (a fold's inner screen, a tablet) the sides are the centring, not a gutter, and the bottom is held against the
 * gutter instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = PHONE)
class ComposerBottomGapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private val density: Float get() = compose.activity.resources.displayMetrics.density

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
        compose.onAllNodesWithText("Try the demo").onFirst().performClick()
        compose.waitUntil(20_000) { graph.session.state.value is SessionState.SignedIn }
        waitForText(HOME_PLACEHOLDER, 30_000)
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(IDLE_CHAT, substring = true)) }.isSuccess
        }
        waitForText(IDLE_CHAT, 30_000)
        compose.onAllNodesWithText(IDLE_CHAT).onFirst().performClick()
        waitForText(CHAT_PLACEHOLDER)
        waitForText(CHAT_PROMPT, 30_000)
        compose.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun dispatchInsets(navigationBarPx: Int, imePx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBarPx))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), navigationBarPx > 0)
            .setVisible(WindowInsetsCompat.Type.ime(), imePx > 0)
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

    private enum class Navigation(val label: String, val insetDp: Int) {
        Hidden("hidden", 0),
        Gesture("gesture", 24),
        ThreeButton("3-button", 48),
    }

    /** The composer box's air, in dp: at each side of the window, and under it above the bar, keyboard or edge. */
    private class Gaps(val left: Float, val right: Float, val bottom: Float, val bottomMargin: Float) {
        val side get() = minOf(left, right)
        override fun toString() = "side L%.1f/R%.1f bottom %.1f (margin to window %.1f)".format(left, right, bottom, bottomMargin)
    }

    private fun measure(navigation: Navigation, imeDp: Int): Gaps {
        dispatchInsets((navigation.insetDp * density).toInt(), (imeDp * density).toInt())
        val root: Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val box: Rect = compose.onNodeWithTag("follow-up-composer").fetchSemanticsNode().boundsInRoot
        val inset = maxOf(navigation.insetDp, imeDp) * density
        return Gaps(
            left = (box.left - root.left) / density,
            right = (root.right - box.right) / density,
            bottom = (root.bottom - inset - box.bottom) / density,
            bottomMargin = (root.bottom - box.bottom) / density,
        )
    }

    private fun assertBottomClearsSides(device: String) {
        val gutter = CursorDimens.composerGutter.value
        val measured = Navigation.entries.flatMap { navigation -> listOf(0, KEYBOARD_DP).map { ime -> Triple(navigation, ime, measure(navigation, ime)) } }
        measured.forEach { (navigation, ime, gaps) -> println("MEASURE $device ${navigation.label} keyboard=${ime}dp: $gaps") }
        for ((navigation, ime, gaps) in measured) {
            val label = "$device, ${navigation.label} navigation, keyboard ${ime}dp"
            // Full width: the sides are the gutter and the bottom must clear it; centred: the bottom holds the gutter.
            val floor = minOf(gaps.side, gutter)
            assertWithMessage("bottom gap ($label)").that(gaps.bottom).isAtLeast(floor - TOLERANCE_DP)
            assertWithMessage("bottom gap ($label)").that(gaps.bottom).isAtMost(floor + SLIGHTLY_MORE_DP + TOLERANCE_DP)
        }
        // One gap in every configuration: nothing pads for the bar and the keyboard both, nothing loses the bar.
        val bottoms = measured.map { it.third.bottom }
        assertWithMessage("spread of bottom gaps across configurations").that(bottoms.max() - bottoms.min()).isAtMost(TOLERANCE_DP)
    }

    @Test
    fun `phone - bottom gap clears the side gutter in every navigation mode, keyboard closed and open`() {
        assertBottomClearsSides("phone w411dp")
    }

    @Test
    @Config(sdk = [35], qualifiers = FOLD)
    fun `fold inner screen - bottom gap clears the gutter in every navigation mode`() {
        assertBottomClearsSides("fold w841dp")
    }

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `tablet - bottom gap clears the gutter in every navigation mode`() {
        assertBottomClearsSides("tablet w1000dp")
    }

    private companion object {
        const val IDLE_CHAT = "Cli exploration"
        const val CHAT_PROMPT = "Explore how the Cursor CLI resumes cloud agents"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val KEYBOARD_DP = 300
        /** How much more than the sides the bottom may have: "equal to or slightly greater". */
        const val SLIGHTLY_MORE_DP = 2f
        const val TOLERANCE_DP = 0.5f
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val FOLD = "w841dp-h701dp-night-420dpi"
private const val TABLET = "w1000dp-h720dp-night-320dpi"
