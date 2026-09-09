package com.cursorforandroid.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
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

    private companion object {
        const val AGENT = "bc-demo-0012"
        const val FILE = "predictive_back_drawer.png"
        const val FIGURE = "Sidebar drawer mid-gesture"
    }
}
