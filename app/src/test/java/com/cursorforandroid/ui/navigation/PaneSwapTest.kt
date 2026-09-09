package com.cursorforandroid.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rotating a tablet-sized phone switches the shell between the drawer layout and the two-pane one. The detail pane
 * is composed in a different place in each, so without moving it the whole navigation stack — its view models, its
 * saved state and whatever a screen has open — is thrown away and built again. The full-screen image viewer is the
 * visible half of that: it used to close on every rotation.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PaneSwapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var wide by mutableStateOf(false)

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
                    wide = wide,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun nodes(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    private fun scrollListTo(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
        compose.waitUntil(30_000) { onScreen(text) }
    }

    @Test
    fun `the open image viewer stays open when the layout switches to two panes`() {
        scrollListTo(MEDIA_CHAT)
        compose.onAllNodesWithText(MEDIA_CHAT).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(30_000) { onScreen(CHAT_PLACEHOLDER) }
        compose.waitUntil(30_000) { nodes(FIGURE) > 0 }

        // Through the semantics action rather than a tap: the transcript follows the newest reply, so where the
        // figure sits on screen is not settled, and this test is about the layout swap rather than about hit testing.
        compose.onAllNodes(hasContentDescription(FIGURE)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(30_000) { nodes("Close") == 1 }
        // The viewer draws the same figure over the one in the transcript.
        assertThat(nodes(FIGURE)).isEqualTo(2)

        compose.runOnIdle { wide = true }
        compose.waitForIdle()

        assertThat(nodes("Close")).isEqualTo(1)
        assertThat(nodes(FIGURE)).isEqualTo(2)
        assertThat(onScreen(CHAT_PLACEHOLDER)).isTrue()

        // And back the other way, which is the rotation home again.
        compose.runOnIdle { wide = false }
        compose.waitForIdle()
        assertThat(nodes("Close")).isEqualTo(1)
        assertThat(onScreen(CHAT_PLACEHOLDER)).isTrue()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val MEDIA_CHAT = "Android mobile experience"
        const val FIGURE = "Sidebar drawer mid-gesture"
    }
}
