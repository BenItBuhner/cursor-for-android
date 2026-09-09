package com.cursorforandroid.ui.share

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
