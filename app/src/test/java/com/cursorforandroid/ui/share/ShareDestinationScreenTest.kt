package com.cursorforandroid.ui.share

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ShareDestinationScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `new chat and an existing chat are both offered and the rows look like the sidebar`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { graph.session.enterDemo(); graph.agents.refresh() }
        var newChat = false
        var picked: String? = null
        compose.setContent {
            val vm: AgentsViewModel = viewModel(factory = AgentsViewModel.Factory(graph))
            val listState by vm.uiState.collectAsStateWithLifecycle()
            CursorTheme(mode = ThemeMode.Dark) {
                ShareDestinationScreen(
                    listState = listState,
                    draft = ShareDraft(generation = 1, text = "Shared from Chrome", attachments = emptyList()),
                    onNewChat = { newChat = true },
                    onPickChat = { picked = it.agent.id },
                    onRefresh = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Cli exploration", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Add to").assertIsDisplayed()
        compose.onNodeWithText("Shared from Chrome").assertIsDisplayed()
        compose.onNodeWithText("New chat").assertIsDisplayed().performClick()
        assertThat(newChat).isTrue()

        compose.onNodeWithText("Cli exploration").assertIsDisplayed().performClick()
        assertThat(picked).isEqualTo("bc-demo-0004")
    }

    @Test
    fun `close dismisses the picker`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { graph.session.enterDemo() }
        var dismissed = false
        compose.setContent {
            val vm: AgentsViewModel = viewModel(factory = AgentsViewModel.Factory(graph))
            val listState by vm.uiState.collectAsStateWithLifecycle()
            CursorTheme(mode = ThemeMode.Dark) {
                ShareDestinationScreen(
                    listState = listState,
                    draft = ShareDraft(generation = 1, text = "Hi", attachments = emptyList()),
                    onNewChat = {},
                    onPickChat = {},
                    onRefresh = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithContentDescription("Close").performClick()
        assertThat(dismissed).isTrue()
    }

    /**
     * The picker is stacked over the shell rather than composed in its place, so anywhere it has no control of its
     * own — the header band beside the title — a tap used to land on the pane below and a horizontal drag used to
     * pull the sidebar drawer open behind it.
     */
    @Test
    fun `taps and drags on the picker never reach what is stacked underneath`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { graph.session.enterDemo() }
        var taps = 0
        var dragged = 0f
        compose.setContent {
            val vm: AgentsViewModel = viewModel(factory = AgentsViewModel.Factory(graph))
            val listState by vm.uiState.collectAsStateWithLifecycle()
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    // The shell's own gestures: the drawer's horizontal drag over everything, and a control below.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .draggable(rememberDraggableState { dragged += it }, Orientation.Horizontal)
                            .clickable { taps++ },
                    )
                    ShareDestinationScreen(
                        listState = listState,
                        draft = ShareDraft(generation = 1, text = "Shared from Chrome", attachments = emptyList()),
                        onNewChat = {},
                        onPickChat = {},
                        onRefresh = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Add to").assertIsDisplayed()

        compose.onRoot().performTouchInput { click(Offset(right - 8f, top + 8f)) }
        compose.onRoot().performTouchInput { swipe(Offset(0f, top + 8f), Offset(width * 0.9f, top + 8f)) }
        compose.waitForIdle()

        assertThat(taps).isEqualTo(0)
        assertThat(dragged).isEqualTo(0f)
    }
}
