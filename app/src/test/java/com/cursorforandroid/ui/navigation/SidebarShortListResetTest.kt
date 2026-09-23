package com.cursorforandroid.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.CursorUser
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
 * "Show N more" lasts one visit to the sidebar: every way of leaving it — the drawer shut, a chat opened, the
 * Project opened, another destination, the rail put away — cuts the group back to five rows, and the chat opened
 * from past the cut stays listed as the sixth. Driven through the shell on the demo, whose Pinned group is made
 * long by pinning seven of its chats (the demo has one Project; the cut and its reset are the same for both groups).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarShortListResetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: AppGraph

    private val pinned = mapOf(
        "bc-demo-0001" to "Codex-Poly-Bot Scaling",
        "bc-demo-0002" to "Revenue Scaling Pipeline Research",
        "bc-demo-0003" to "Cesium Revenue Strategy",
        "bc-demo-0004" to "Cli exploration",
        "bc-demo-0005" to "House environment overhaul",
        "bc-demo-0006" to "Market replay engine",
        "bc-demo-0007" to "Latest release process",
    )

    @After
    fun tearDown() = runBlocking {
        val now = graph.prefs.localAgentState.first().pinnedIds
        pinned.keys.filter { it in now }.forEach { graph.pins.toggle(it) }
    }

    private fun showShell(wide: Boolean) {
        graph = AppGraph(context)
        runBlocking {
            graph.session.enterDemo()
            val now = graph.prefs.localAgentState.first().pinnedIds
            pinned.keys.filter { it !in now }.forEach { graph.pins.toggle(it) }
        }
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
        compose.waitUntil(30_000) { exists(hasText(HOME_PLACEHOLDER, substring = true)) }
        compose.waitUntil(30_000) { listedPinned().size == 5 }
        assertThat(moreText()).isEqualTo("Show 2 more")
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The sidebar's list — composed off screen too while the drawer is shut — told apart by its Pinned header. */
    private val sidebarList = hasScrollToNodeAction() and hasAnyDescendant(hasTestTag("section-${AgentListOrganizer.PINNED_KEY}"))

    /** The pinned chats the sidebar lists. */
    private fun listedPinned(): Set<String> = pinned.values.filterTo(HashSet()) { exists(hasText(it) and hasAnyAncestor(sidebarList)) }

    private val moreRow get() = compose.onNodeWithTag("section-more-${AgentListOrganizer.PINNED_KEY}")

    private fun moreText(): String? = compose.onAllNodes(hasTestTag("section-more-${AgentListOrganizer.PINNED_KEY}")).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitForIdle()
    }

    /** "Show 2 more", tapped: all seven pinned chats listed. */
    private fun listInFull() {
        moreRow.performClick()
        compose.waitUntil(10_000) { listedPinned().size == 7 }
        assertThat(moreText()).isEqualTo("Show less")
    }

    private fun assertCutBack(listed: Int = 5, more: String = "Show 2 more") {
        compose.waitUntil(10_000) { listedPinned().size == listed }
        assertThat(moreText()).isEqualTo(more)
    }

    private fun tapInSidebar(name: String) {
        compose.onAllNodes(hasText(name) and hasAnyAncestor(sidebarList)).onFirst().performClick()
    }

    @Test
    fun `shutting the drawer cuts the group back, and it opens again on five rows`() {
        showShell(wide = false)
        openDrawer()
        listInFull()

        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitForIdle()
        assertCutBack()

        openDrawer()
        assertCutBack()
    }

    @Test
    fun `opening a chat from the drawer cuts the group back`() {
        showShell(wide = false)
        openDrawer()
        val firstFive = listedPinned()
        listInFull()

        tapInSidebar(firstFive.first())
        compose.waitUntil(20_000) { exists(hasText("Follow up", substring = true)) }
        compose.waitForIdle()
        assertCutBack()
    }

    @Test
    fun `opening the Project from the drawer cuts the group back`() {
        showShell(wide = false)
        openDrawer()
        listInFull()

        tapInSidebar("Cesium billing launch")
        compose.waitUntil(20_000) { exists(hasTestTag("chat-header") and hasContentDescription("Cesium billing launch")) }
        compose.waitForIdle()
        assertCutBack()
    }

    @Test
    fun `going to Settings from the drawer cuts the group back`() {
        showShell(wide = false)
        openDrawer()
        listInFull()

        compose.onNodeWithContentDescription("Account").performClick()
        compose.waitUntil(20_000) { exists(hasText("Appearance")) }
        compose.waitForIdle()
        assertCutBack()
    }

    @Test
    fun `beside the pane, opening a chat from past the cut cuts the group back with that chat kept as the sixth row`() {
        showShell(wide = true)
        val firstFive = listedPinned()
        listInFull()
        val pastTheCut = (pinned.values.toSet() - firstFive).first()

        tapInSidebar(pastTheCut)
        compose.waitUntil(20_000) { exists(hasTestTag("chat-header") and hasContentDescription(pastTheCut)) }
        assertCutBack(listed = 6, more = "Show 1 more")
        assertThat(listedPinned()).isEqualTo(firstFive + pastTheCut)
    }

    @Test
    fun `beside the pane, putting the sidebar away cuts the group back`() {
        showShell(wide = true)
        listInFull()

        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitForIdle()
        assertCutBack()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
    }
}
