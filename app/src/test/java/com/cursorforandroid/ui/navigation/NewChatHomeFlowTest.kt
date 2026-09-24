package com.cursorforandroid.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.SidebarTags
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.settings.NewChatHomePickerTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The New chat page setting through the shell, sidebar beside the pane: Projects chosen in Settings pins the demo's
 * Project in the New Chat pane the next time it is on screen, and its shortcut opens the Project; Recent agents
 * chosen again brings the recent chats back.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h720dp-night-420dpi")
class NewChatHomeFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: AppGraph

    @After
    fun tearDown() = runBlocking { graph.drafts.clear(); Unit }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun shortcuts() = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes().size

    private fun choose(home: NewChatHome) {
        compose.onNodeWithTag(SidebarTags.ACCOUNT).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.of(home))).fetchSemanticsNodes().isNotEmpty() }
        // A touch made while the pane is still sliding Settings in is not the screen's to take.
        compose.waitForIdle()
        compose.onNodeWithTag(NewChatHomePickerTags.of(home)).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.newChatHome.first() } == home }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(10_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
    }

    @Test
    fun `Projects chosen in Settings pins the Project in the New Chat pane, and its shortcut opens it`() {
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
        compose.waitUntil(30_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        assertThat(shortcuts()).isEqualTo(0)

        choose(NewChatHome.PROJECTS)
        compose.waitUntil(10_000) { shortcuts() == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag(NewChatHomeTags.PROJECT_SHORTCUT).performClick()
        // The pane has left New Chat for the Project's own chat.
        compose.waitUntil(30_000) { shortcuts() == 0 && !onScreen(NewChatHomeCopy.PLACEHOLDER) }

        choose(NewChatHome.RECENT)
        compose.waitUntil(10_000) { shortcuts() == 0 && onScreen("Revenue Scaling Pipeline Research") }
    }
}
