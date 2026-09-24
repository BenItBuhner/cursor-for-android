package com.cursorforandroid.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * New-chat drafts through the shell, on a window wide enough for the sidebar to stand beside the pane: what is being
 * written in the New Chat pane is no row while the pane is on screen; left, it is the sidebar's first row; tapped, it is
 * the pane's again; "+" leaves it a row and opens a fresh pane; its menu deletes it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h720dp-night-420dpi")
class NewChatDraftsFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: AppGraph

    @After
    fun tearDown() = runBlocking { graph.drafts.clear(); Unit }

    private fun showShell() {
        graph = AppGraph(context)
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    wide = true,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        compose.waitUntil(30_000) { graph.newChatDrafts.state.value.loaded }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun draftRows() = compose.onAllNodes(hasTestTag("draft-row")).fetchSemanticsNodes().size

    private fun type(text: String) {
        // The composer is the only field on screen while the sidebar's search is closed.
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(text)
    }

    private fun composerHolds(text: String) = compose.onAllNodes(hasSetTextAction() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `left, the draft being written is the sidebar's first row, and tapped it is the pane's again`() {
        showShell()
        type("Rewrite the onboarding copy")
        compose.waitForIdle()
        // Being written: no row.
        assertThat(draftRows()).isEqualTo(0)

        compose.onNodeWithContentDescription("Account").performClick()
        compose.waitUntil(10_000) { draftRows() == 1 }
        compose.onNodeWithText("Rewrite the onboarding copy").assertExists()

        compose.onAllNodesWithTag("draft-row").onFirst().performClick()
        compose.waitUntil(10_000) { composerHolds("Rewrite the onboarding copy") }
        compose.waitUntil(10_000) { draftRows() == 0 }
    }

    @Test
    fun `new chat keeps the draft as a row and opens a fresh pane, and the row's menu deletes it`() {
        showShell()
        type("First idea")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(10_000) { draftRows() == 1 && !composerHolds("First idea") }

        type("Second idea")
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(10_000) { draftRows() == 2 }

        compose.onAllNodesWithTag("draft-row").onFirst().performTouchInput { longClick() }
        compose.onNodeWithText("Delete draft").performClick()
        compose.waitUntil(10_000) { draftRows() == 1 }
        // The most recent went; the older stays.
        compose.onNodeWithText("First idea").assertExists()
        assertThat(onScreen("Second idea")).isFalse()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
    }
}
