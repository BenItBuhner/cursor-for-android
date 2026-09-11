package com.cursorforandroid.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.ArtifactPaths
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The full-screen viewer used to be composed by the transcript row the figure was tapped in, so it went away with
 * that row: a lazy list disposes a row as soon as it scrolls off, which a running agent's replies do on their own.
 * It is opened through the row but composed by the screen, and outlives it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class FigureLightboxTest {

    @get:Rule
    val compose = createComposeRule()

    private var rowOnScreen by mutableStateOf(true)

    private fun nodes(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    @Test
    fun `an open viewer outlives the row it was opened from`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking { graph.session.enterDemo() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val lightbox = rememberLightboxState(AGENT)
                CompositionLocalProvider(LocalMarkdownMedia provides MarkdownMediaContext(AGENT, graph.media, lightbox)) {
                    if (rowOnScreen) ImageBlock(ArtifactPaths.VM_ROOT + FILE, FIGURE)
                }
                FigureLightbox(lightbox, graph.media, AGENT)
            }
        }

        compose.waitUntil(30_000) { nodes(FIGURE) == 1 }
        compose.onAllNodes(hasContentDescription(FIGURE))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(30_000) { nodes("Close") == 1 }
        assertThat(nodes(FIGURE)).isEqualTo(2)

        rowOnScreen = false
        compose.waitForIdle()

        assertThat(nodes("Close")).isEqualTo(1)
        // Only the viewer's copy is left, and it still has its own bitmap to draw.
        assertThat(nodes(FIGURE)).isEqualTo(1)
    }

    @Test
    fun `an open viewer survives a configuration change`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking { graph.session.enterDemo() }
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val lightbox = rememberLightboxState(AGENT)
                CompositionLocalProvider(LocalMarkdownMedia provides MarkdownMediaContext(AGENT, graph.media, lightbox)) {
                    ImageBlock(ArtifactPaths.VM_ROOT + FILE, FIGURE)
                }
                FigureLightbox(lightbox, graph.media, AGENT)
            }
        }

        compose.waitUntil(30_000) { nodes(FIGURE) == 1 }
        compose.onAllNodes(hasContentDescription(FIGURE))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(30_000) { nodes("Close") == 1 }
        assertThat(nodes(FIGURE)).isEqualTo(2)

        // The rotation the activity handles itself: the composition is rebuilt from saved state, and a viewer that
        // did not come back would drop the reader back on the transcript with their place in the figure lost.
        restorer.emulateSavedInstanceStateRestore()

        // The figure it holds is decoded again for the new window rather than saved, so the viewer comes back on
        // its spinner; what has to survive is the viewer itself, and its own way out of it.
        compose.waitUntil(30_000) { nodes("Close") == 1 }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertThat(nodes("Close")).isEqualTo(0)
    }

    private companion object {
        const val AGENT = "bc-demo-0012"
        const val FILE = "predictive_back_drawer.png"
        const val FIGURE = "Sidebar drawer mid-gesture"
    }
}
