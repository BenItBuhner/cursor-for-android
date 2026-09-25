package com.cursorforandroid.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Cursor Project has no screen of its own: its row in the sidebar and a deep link naming it both open the
 * coordinator's chat, and the Project — its primaries, their menus, the coordinator's hands, the shared context —
 * is that chat's panel: its Project tab, and the Project section under Details, where a primary opens as a tab and
 * from the tab over the coordinator's chat, and its own panel's link to the coordinator goes back to it. Driven
 * through the demo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** What a notification or a link handed the activity: the id of a chat — or of a Project, which is its coordinator's. */
    private var deepLink by mutableStateOf<String?>(null)

    @Composable
    private fun App(graph: AppGraph) {
        CursorTheme(mode = ThemeMode.Dark) {
            CursorRoot(graph = graph, deepLinkAgentId = deepLink, onDeepLinkConsumed = { deepLink = null })
        }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, timeoutMillis: Long = 30_000) = compose.waitUntil(timeoutMillis) { onScreen(text) }

    /** The open chat's name: its header's accessibility label, the header holding no title of its own. */
    private fun waitForChat(name: String, timeoutMillis: Long = 30_000) =
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasTestTag("chat-header") and hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty() }

    /** The sidebar's list, told from the recent list by the section headers only it has. */
    private val sidebarList = hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    private fun enterDemo(): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        compose.setContent { App(graph) }
        waitForText("Try the demo")
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntil(20_000) { graph.session.state.value is SessionState.SignedIn }
        waitForText("Ask Cursor to build, fix bugs, explore")
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        return graph
    }

    /** The coordinator's chat is open: its name on the header, its follow-up composer, no Project view's coordinator row. */
    private fun assertCoordinatorChatOpen() {
        waitForText("Follow up")
        waitForChat("Cesium billing launch")
        assertThat(onScreen("Plans the work and delegates it")).isFalse()
        assertThat(onScreen("Open coordinator chat")).isFalse()
    }

    /** The panel's strip is on the tab tagged [tag]. */
    private fun waitForTab(tag: String) =
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty() }

    /** The panel shows the chat named [name] as a tab of its own. */
    private fun waitForAgentTab(name: String) =
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("agent-tab-title") and hasText(name)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the Project's row opens the coordinator's chat, whose panel carries the Project and opens a primary as a tab`() {
        enterDemo()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(sidebarList).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        waitForText("Projects")
        compose.onNode(hasText("Cesium billing launch") and hasAnyAncestor(sidebarList)).performClick()
        assertCoordinatorChatOpen()

        // The panel opens on the Project tab. Under Details, right under the header: the primaries with their status,
        // the coordinator's hands (the demo stands in for the account) and the shared context's named state.
        compose.onNodeWithContentDescription("Open panel").performClick()
        waitForTab("panel-tab-project")
        compose.onNodeWithTag("project-notes-tab").assertExists()
        compose.onNodeWithTag("panel-tab-details").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("section-Project")).fetchSemanticsNodes().isNotEmpty() }
        waitForText("Stripe webhook handler")
        assertThat(compose.onAllNodesWithTag("project-primary").fetchSemanticsNodes()).hasSize(2)
        val sections = compose.onAllNodes(hasTestTag("section-Header") or hasTestTag("section-Project") or hasTestTag("section-Changes")).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        assertThat(sections.map { it.config[SemanticsProperties.TestTag] }).containsExactly("section-Header", "section-Project", "section-Changes").inOrder()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("project-new-primary"))
        assertThat(onScreen("New primary")).isTrue()
        assertThat(onScreen("Adopt a chat")).isTrue()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText("No shared context for this Project yet."))
        compose.onNodeWithTag("project-appearance").assertExists()
        // New primary opens its sheet.
        compose.onNodeWithTag("project-new-primary").performClick()
        waitForText("What should this agent do?")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(20_000) { !onScreen("What should this agent do?") }

        // A primary opens as a tab of the coordinator's panel (the transcript's worker cards name it too; the panel's
        // row is the one tapped), the coordinator still the chat; the tab opens it as the chat, over the coordinator's.
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("project-primary"))
        compose.onNode(hasTestTag("project-primary") and (hasText("Stripe webhook handler", substring = true) or hasAnyDescendant(hasText("Stripe webhook handler", substring = true)))).performClick()
        waitForAgentTab("Stripe webhook handler")
        waitForChat("Cesium billing launch")
        compose.onNodeWithTag("agent-tab-open").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("conversation-panel")).fetchSemanticsNodes().isEmpty() }
        waitForChat("Stripe webhook handler")
        // Its own panel carries the same Project, and under Details points back to the coordinator.
        compose.onNodeWithContentDescription("Open panel").performClick()
        waitForTab("panel-tab-project")
        compose.onNodeWithTag("panel-tab-details").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("project-coordinator-link")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(onScreen("A primary of this chat")).isTrue()
        // That link opens the coordinator as a tab too, and from there the way back: the coordinator's chat as it was
        // left, its panel still open on the primary's tab, not a second copy of it stacked over the primary.
        compose.onNodeWithTag("project-coordinator-link").performClick()
        waitForAgentTab("Cesium billing launch")
        compose.onNodeWithTag("agent-tab-open").performClick()
        assertCoordinatorChatOpen()
        waitForAgentTab("Stripe webhook handler")
        // Back walks the panel's tabs the way they were opened, Details then the Project, then shuts the panel.
        val dispatcher = compose.activity.onBackPressedDispatcher
        dispatcher.onBackPressed()
        waitForTab("panel-tab-details")
        dispatcher.onBackPressed()
        waitForTab("panel-tab-project")
        dispatcher.onBackPressed()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("conversation-panel")).fetchSemanticsNodes().isEmpty() }
        waitForChat("Cesium billing launch")
        dispatcher.onBackPressed()
        waitForText("Ask Cursor to build, fix bugs, explore")
        compose.waitForIdle()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test
    fun `a deep link naming the Project opens the coordinator's chat`() {
        val graph = enterDemo()
        assertThat(graph.agents.agent(DemoData.PROJECT_ID)?.isProjectRoot).isTrue()
        deepLink = DemoData.PROJECT_ID
        assertCoordinatorChatOpen()
        waitForText("PR #215 (usage aggregation) is")
    }
}
