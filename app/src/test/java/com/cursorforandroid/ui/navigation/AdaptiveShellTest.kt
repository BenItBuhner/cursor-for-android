package com.cursorforandroid.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * The shell's adaptive layout in the running app: the rail steps through its three states from its own toggles and
 * remembers the choice per width class across a fold, and the panel is a pane where the window has room for three
 * columns and a sheet where it has not.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
class AdaptiveShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var windowWidth by mutableIntStateOf(1000)

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
                    windowHeightDp = 720,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun described(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `the sidebar hides from its own toggle, comes back from the header's, and the choice survives a fold`() {
        // A wide window opens with the full sidebar.
        compose.waitUntil(30_000) { described("Toggle sidebar") }
        assertThat(described("New chat")).isTrue()

        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        settle()
        // Hidden: the detail pane's header offers the way back.
        compose.waitUntil(10_000) { described("Open sidebar") }
        assertThat(runBlocking { graph.prefs.railStates.first() }["Expanded"]).isEqualTo(RailState.Hidden.name)

        // Folded to the cover display: the drawer, whatever the wide window's sidebar was.
        windowWidth = 411
        settle()
        compose.waitUntil(10_000) { described("Open sidebar") }

        // Unfolded again: the inner display's sidebar is as it was left.
        windowWidth = 1000
        settle()
        compose.waitUntil(10_000) { described("Open sidebar") }
        assertThat(described("Toggle sidebar")).isFalse()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        settle()
        compose.waitUntil(10_000) { described("Toggle sidebar") }
        assertThat(runBlocking { graph.prefs.railStates.first() }["Expanded"]).isEqualTo(RailState.Expanded.name)
    }

    @Test
    fun `the panel is a pane beside the chat where three columns fit and a sheet where they do not`() {
        compose.waitUntil(30_000) { described("Toggle sidebar") }
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(CHAT, substring = true)) }.isSuccess }
        compose.onAllNodesWithText(CHAT).onFirst().performClick()
        compose.waitUntil(30_000) { onScreen(CHAT_PLACEHOLDER) }

        // 1000 dp with the sidebar: 1000 − 278 − 360 = 362 for the chat, under the floor — a sheet.
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(10_000) { tagged("conversation-panel") }
        assertThat(tagged("panel-pane")).isFalse()
        compose.onNodeWithContentDescription("Close panel").performClick()
        settle()
        compose.waitUntil(10_000) { !tagged("conversation-panel") }

        // The sidebar hidden gives the chat 640 dp: the same panel comes back as a pane.
        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        settle()
        compose.waitUntil(10_000) { described("Open sidebar") }
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(10_000) { tagged("panel-pane") }
        assertThat(tagged("panel-grip")).isTrue()
        assertThat(onScreen(CHAT_PLACEHOLDER)).isTrue()

        // Folded: the open panel carries over as the sheet; unfolded: back to the pane.
        windowWidth = 411
        settle()
        compose.waitUntil(10_000) { tagged("conversation-panel") && !tagged("panel-pane") }
        windowWidth = 1000
        settle()
        compose.waitUntil(10_000) { tagged("panel-pane") }
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val CHAT = "Android mobile experience"
    }
}
