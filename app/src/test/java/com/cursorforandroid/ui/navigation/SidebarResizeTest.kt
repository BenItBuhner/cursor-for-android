package com.cursorforandroid.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sidebar's grip on a wide window: a drag resizes the column within [WindowPosture.SIDEBAR_MIN_DP] and
 * [WindowPosture.SIDEBAR_MAX_DP], the width the finger lifts on is kept per width class and is what a fold and
 * unfold come back to, a drag under the minimum snaps the sidebar shut without losing the width, and a sidebar
 * dragged wide turns an open pane into a sheet rather than overlapping it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1200dp-h800dp-land-night-160dpi")
class SidebarResizeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var windowWidth by mutableIntStateOf(WINDOW)

    @Before
    fun enterDemo() {
        graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking { graph.session.enterDemo() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    windowWidthDp = windowWidth,
                    windowHeightDp = 800,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        compose.waitUntil(30_000) { tagged("sidebar-grip") }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun described(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    /** The sidebar's laid-out width, as the grip states it. */
    private fun sidebarWidth(): Int = compose.onNodeWithTag("sidebar-grip").fetchSemanticsNode().config[SemanticsProperties.StateDescription].removeSuffix(" dp").toInt()

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Drags the grip by [dp] (at mdpi a dp is a pixel) in a straight horizontal line, finger down to finger up. */
    private fun dragGrip(dp: Float) {
        compose.onNodeWithTag("sidebar-grip").performTouchInput {
            swipe(start = center, end = Offset(center.x + dp, center.y), durationMillis = 300)
        }
        settle()
    }

    private fun savedWidth(): Int? = runBlocking { graph.prefs.sidebarWidths.first() }["Expanded"]

    /** The width written to disk once the write has landed (DataStore writes off the test's clock). */
    private fun awaitSaved(expected: Int) {
        compose.waitUntil(10_000) { savedWidth() == expected }
    }

    @Test
    fun `the grip resizes the sidebar within its bounds and the width is kept per width class`() {
        assertThat(sidebarWidth()).isEqualTo(WindowPosture.SIDEBAR_DEFAULT_DP)
        assertThat(savedWidth()).isNull()

        // Wider by about 60 (the touch slop comes off the first move), and written down.
        dragGrip(60f)
        val wider = sidebarWidth()
        assertThat(wider).isIn(310..338)
        awaitSaved(wider)

        // Far past the maximum: held at it.
        dragGrip(400f)
        assertThat(sidebarWidth()).isEqualTo(WindowPosture.SIDEBAR_MAX_DP)
        awaitSaved(WindowPosture.SIDEBAR_MAX_DP)

        // Back under the minimum but not by enough to snap shut: held at the minimum.
        dragGrip(-(WindowPosture.SIDEBAR_MAX_DP - WindowPosture.SIDEBAR_MIN_DP + 20f))
        assertThat(sidebarWidth()).isEqualTo(WindowPosture.SIDEBAR_MIN_DP)
        awaitSaved(WindowPosture.SIDEBAR_MIN_DP)
        assertThat(described("Toggle sidebar")).isTrue()

        // Folded to the cover display and back: the inner display's sidebar is the width it was left at.
        windowWidth = 411
        settle()
        compose.waitUntil(10_000) { !tagged("sidebar-grip") }
        windowWidth = WINDOW
        settle()
        compose.waitUntil(10_000) { tagged("sidebar-grip") }
        assertThat(sidebarWidth()).isEqualTo(WindowPosture.SIDEBAR_MIN_DP)
    }

    @Test
    fun `dragged under the minimum the sidebar snaps shut and comes back at its width`() {
        dragGrip(40f)
        val kept = sidebarWidth()
        assertThat(kept).isIn(290..318)
        awaitSaved(kept)

        // Under the hide line (160): shut, and the header offers the way back; the width kept is the last one lifted on.
        dragGrip(-(kept - WindowPosture.SIDEBAR_HIDE_BELOW_DP + 30f))
        compose.waitUntil(10_000) { described("Open sidebar") }
        assertThat(tagged("sidebar-grip")).isFalse()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.railStates.first() }["Expanded"] == RailState.Hidden.name }
        assertThat(savedWidth()).isEqualTo(kept)

        compose.onNodeWithContentDescription("Open sidebar").performClick()
        settle()
        compose.waitUntil(10_000) { tagged("sidebar-grip") }
        assertThat(sidebarWidth()).isEqualTo(kept)
    }

    @Test
    fun `a sidebar dragged wide turns the pane into a sheet instead of overlapping it`() {
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(CHAT, substring = true)) }.isSuccess }
        compose.onAllNodesWithText(CHAT).onFirst().performClick()
        compose.waitUntil(30_000) { onScreen(CHAT_PLACEHOLDER) }

        // 1200 − 278 − 360 = 562 for the chat: a pane.
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(10_000) { tagged("panel-pane") }

        // The sidebar at its widest leaves 1200 − 400 − 360 = 440: still a pane.
        dragGrip(200f)
        assertThat(sidebarWidth()).isEqualTo(WindowPosture.SIDEBAR_MAX_DP)
        compose.waitUntil(10_000) { tagged("panel-pane") }

        // A narrower window with the same sidebar: 1000 − 400 − 360 = 240, under the chat's floor — the panel falls
        // back to the sheet; the sidebar and the pane never share the room.
        windowWidth = 1000
        settle()
        compose.waitUntil(10_000) { tagged("conversation-panel") && !tagged("panel-pane") }
        assertThat(sidebarWidth()).isEqualTo(WindowPosture.SIDEBAR_MAX_DP)
        compose.onNodeWithContentDescription("Close panel").performClick()
        settle()

        // Narrowed to about 220 (1000 − 278 − 360 = 362 would still be under the floor), the same panel is a pane
        // again: 1000 − 220 − 360 = 420.
        dragGrip(-200f)
        val narrow = sidebarWidth()
        assertThat(narrow).isIn(WindowPosture.SIDEBAR_MIN_DP..240)
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(10_000) { tagged("panel-pane") }
        assertThat(compose.onAllNodes(hasStateDescription("$narrow dp")).fetchSemanticsNodes()).hasSize(1)
    }

    private companion object {
        const val WINDOW = 1200
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val CHAT = "Android mobile experience"
    }
}
