package com.cursorforandroid.ui.navigation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
 * The share destination picker is emitted as a sibling of the shell, over it rather than in its place, so it has to
 * swallow what reaches it. A horizontal drag anywhere on it used to reach the drawer's `draggable` underneath and
 * open the sidebar invisibly — which turns the detail pane's back handler off and starts list polling, and reveals
 * a drawer nobody asked for once the picker is dismissed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ShareOverlayTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

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
                    wide = false,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun drawerSheet() = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Navigation menu")).fetchSemanticsNode()

    @Test
    fun `a drag across the share picker leaves the drawer where it was`() {
        val closed = drawerSheet()
        val width = closed.size.width.toFloat()
        assertThat(closed.positionInRoot.x).isWithin(1f).of(-width)

        graph.share.receive(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Shared from Chrome"),
        )
        compose.waitUntil(20_000) { onScreen("Add to") }

        // Across the picker's header band, which has no control of its own between the Close button and the edge.
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.5f, 20f), Offset(width * 0.95f, 20f)) }
        compose.waitForIdle()

        assertThat(drawerSheet().positionInRoot.x).isWithin(1f).of(-width)
        assertThat(onScreen("Add to")).isTrue()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
    }
}
