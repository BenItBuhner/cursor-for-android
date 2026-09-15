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
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
 * is the Project section of that chat's panel, from where a primary opens in place. Driven through the demo.
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

    /** The coordinator's chat is open: its name in the header, its follow-up composer, no Project view's coordinator row. */
    private fun assertCoordinatorChatOpen() {
        waitForText("Follow up")
        assertThat(onScreen("Cesium billing launch")).isTrue()
        assertThat(onScreen("Plans the work and delegates it")).isFalse()
        assertThat(onScreen("Open coordinator chat")).isFalse()
    }

    @Test
    fun `the Project's row opens the coordinator's chat, whose panel carries the Project and opens a primary in place`() {
        enterDemo()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(sidebarList).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        waitForText("Projects")
        compose.onNode(hasText("Cesium billing launch") and hasAnyAncestor(sidebarList)).performClick()
        assertCoordinatorChatOpen()

        // A coordinator's panel opens on the Project panel — the Project's notes under its name, as on cursor.com.
        // The chat's own sections are the panel's other surface, reached from the Agents pill: the Project section
        // right under the Overview, with its primaries and their status, the coordinator's hands (the demo stands in
        // for the account) and the shared context.
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("project-notes-tab")).fetchSemanticsNodes().isNotEmpty() }
        waitForText("Shipping")
        // The chat's own sections are the panel's other surface: the Agents pill above the composer opens them on the
        // Project section, as the web's pill opens the Project's agents.
        compose.onNodeWithContentDescription("Close panel").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("conversation-panel")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("pill-agents").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("section-Project")).fetchSemanticsNodes().isNotEmpty() }
        waitForText("Stripe webhook handler")
        assertThat(compose.onAllNodesWithTag("project-primary").fetchSemanticsNodes()).hasSize(2)
        val sections = compose.onAllNodes(hasTestTag("section-Header") or hasTestTag("section-Project") or hasTestTag("section-Changes")).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        assertThat(sections.map { it.config[SemanticsProperties.TestTag] }).containsExactly("section-Header", "section-Project", "section-Changes").inOrder()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("project-new-primary"))
        assertThat(onScreen("New primary")).isTrue()
        assertThat(onScreen("Adopt a chat")).isTrue()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText("Show shared context"))
        compose.onNodeWithTag("project-appearance").assertExists()
        // New primary opens its sheet.
        compose.onNodeWithTag("project-new-primary").performClick()
        waitForText("What should this agent do?")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(20_000) { !onScreen("What should this agent do?") }

        // A primary opens in place of the coordinator's chat (the transcript's worker cards name it too; the panel's
        // row is the one tapped); its own panel points back to the coordinator.
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("project-primary"))
        compose.onNode(hasTestTag("project-primary") and (hasText("Stripe webhook handler", substring = true) or hasAnyDescendant(hasText("Stripe webhook handler", substring = true)))).performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("conversation-panel")).fetchSemanticsNodes().isEmpty() }
        waitForText("Stripe webhook handler")
        // A worker's panel opens on the Project panel too; its own sections — with the way back to the coordinator —
        // are the panel's other surface, from the header menu.
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Chat details")
        compose.onNodeWithText("Chat details").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("project-coordinator-link")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(onScreen("A primary of this chat")).isTrue()
        compose.onNodeWithTag("project-coordinator-link").performClick()
        assertCoordinatorChatOpen()
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
