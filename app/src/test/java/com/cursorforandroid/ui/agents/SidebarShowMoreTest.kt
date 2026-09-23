package com.cursorforandroid.ui.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A long Projects group lists its first five rows and a quiet "Show N more" under them; tapped, every Project is
 * listed in place with "Show less" to cut it back. The open Project stays listed past the cut, the header's chevron
 * still folds the whole group, the Settings switch turns the cut off, and Pinned is cut the same way while the date
 * groups keep every row.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarShowMoreTest {

    @get:Rule
    val compose = createComposeRule()

    private var listState by mutableStateOf(AgentListUiState())
    private var selected by mutableStateOf<String?>(null)
    private val shortLists = SidebarShortLists()

    private val projectNames = listOf(
        "Zenium", "Polymarket Bot Scaling", "Revenue Scaling Pipeline", "Shopify Competitor", "Noetic", "SSHit",
        "Cursor for Android", "Murmur", "Shipyard", "Cesium", "Codex Meter", "Job & Bounty Research",
    )
    private val projects = projectNames.mapIndexed { i, name -> row("p${i + 1}", name, isProject = true) }

    /** The names of [names] the sidebar lists, top to bottom. */
    private fun listed(names: List<String>): List<String> = names
        .mapNotNull { name -> compose.onAllNodes(hasText(name)).fetchSemanticsNodes().firstOrNull()?.let { name to it.positionInRoot.y } }
        .sortedBy { it.second }
        .map { it.first }

    private val showMore get() = compose.onNodeWithTag("section-more-${AgentListOrganizer.PROJECTS_KEY}")

    @Test
    fun `a long Projects group lists its first five rows, then says how many more there are`() {
        showSidebar()
        assertThat(listed(projectNames)).containsExactlyElementsIn(projectNames.take(5)).inOrder()
        showMore.assertIsDisplayed()
        compose.onNodeWithText("Show 7 more").assertIsDisplayed()
        // Under the fifth Project, above the next group.
        val more = compose.onNodeWithText("Show 7 more").fetchSemanticsNode().positionInRoot.y
        assertThat(more).isGreaterThan(compose.onNodeWithText("Noetic").fetchSemanticsNode().positionInRoot.y)
        assertThat(more).isLessThan(compose.onNodeWithText("Pinned").fetchSemanticsNode().positionInRoot.y)
    }

    @Test
    fun `Show more lists every Project in place, in order, and Show less cuts the group back`() {
        showSidebar()
        compose.onNodeWithText("Show 7 more").performClick()
        compose.waitForIdle()
        assertThat(listed(projectNames)).containsExactlyElementsIn(projectNames).inOrder()
        compose.onNodeWithText("Show less").assertIsDisplayed()
        compose.onAllNodes(hasText("Show 7 more")).fetchSemanticsNodes().let { assertThat(it).isEmpty() }
        val less = compose.onNodeWithText("Show less").fetchSemanticsNode().positionInRoot.y
        assertThat(less).isGreaterThan(compose.onNodeWithText("Job & Bounty Research").fetchSemanticsNode().positionInRoot.y)

        compose.onNodeWithText("Show less").performClick()
        compose.waitForIdle()
        assertThat(listed(projectNames)).containsExactlyElementsIn(projectNames.take(5)).inOrder()
        compose.onNodeWithText("Show 7 more").assertIsDisplayed()
    }

    @Test
    fun `the open Project past the fifth row stays listed as the sixth, and in its own place when listed in full`() {
        selected = "p9"
        showSidebar()
        assertThat(listed(projectNames)).containsExactly("Zenium", "Polymarket Bot Scaling", "Revenue Scaling Pipeline", "Shopify Competitor", "Noetic", "Shipyard").inOrder()
        compose.onNodeWithText("Show 6 more").assertIsDisplayed()

        compose.onNodeWithText("Show 6 more").performClick()
        compose.waitForIdle()
        assertThat(listed(projectNames)).containsExactlyElementsIn(projectNames).inOrder()

        compose.onNodeWithText("Show less").performClick()
        compose.waitForIdle()
        assertThat(listed(projectNames).last()).isEqualTo("Shipyard")

        // Another Project opened within the five: the sixth row goes back behind the cut.
        selected = "p2"
        compose.waitForIdle()
        assertThat(listed(projectNames)).containsExactlyElementsIn(projectNames.take(5)).inOrder()
        compose.onNodeWithText("Show 7 more").assertIsDisplayed()
    }

    @Test
    fun `leaving cuts a group listed in full back to five rows`() {
        showSidebar()
        compose.onNodeWithText("Show 7 more").performClick()
        compose.waitForIdle()
        assertThat(listed(projectNames)).hasSize(12)

        compose.runOnIdle { shortLists.reset() }
        compose.waitForIdle()
        assertThat(listed(projectNames)).containsExactlyElementsIn(projectNames.take(5)).inOrder()
        compose.onNodeWithText("Show 7 more").assertIsDisplayed()
    }

    @Test
    fun `with the setting off every Project is listed and there is no Show more row`() {
        showSidebar(shorten = false)
        assertThat(listed(projectNames)).containsExactlyElementsIn(projectNames).inOrder()
        showMore.assertDoesNotExist()

        // Turned back on, the cut returns.
        listState = listState.copy(shortenLongGroups = true)
        compose.waitForIdle()
        assertThat(listed(projectNames)).hasSize(5)
        compose.onNodeWithText("Show 7 more").assertIsDisplayed()
    }

    @Test
    fun `the header's chevron still folds the whole group, whether it is cut or listed in full`() {
        showSidebar()
        compose.onNodeWithContentDescription("Collapse Projects").performClick()
        compose.waitForIdle()
        assertThat(listed(projectNames)).isEmpty()
        showMore.assertDoesNotExist()
        // The folded header counts every Project, not only the five it would list.
        compose.onNodeWithTag("section-count-${AgentListOrganizer.PROJECTS_KEY}", useUnmergedTree = true).assertTextEquals(" · 12")

        compose.onNodeWithContentDescription("Expand Projects").performClick()
        compose.waitForIdle()
        assertThat(listed(projectNames)).hasSize(5)

        compose.onNodeWithText("Show 7 more").performClick()
        compose.onNodeWithContentDescription("Collapse Projects").performClick()
        compose.waitForIdle()
        assertThat(listed(projectNames)).isEmpty()
        compose.onNodeWithContentDescription("Expand Projects").performClick()
        compose.waitForIdle()
        // Folding is not leaving: the list is still in full.
        assertThat(listed(projectNames)).hasSize(12)
    }

    @Test
    fun `a long Pinned group is cut the same way, and the date groups keep every row`() {
        val pinnedNames = (1..7).map { "Pinned chat $it" }
        val todayNames = (1..8).map { "Today chat $it" }
        showSidebar(
            sections = listOf(
                AgentSection(AgentListOrganizer.PINNED_KEY, "Pinned", pinnedNames.mapIndexed { i, n -> row("pin$i", n, pinned = true) }),
                AgentSection("date:Today", "Today", todayNames.mapIndexed { i, n -> row("t$i", n) }),
            ),
        )
        assertThat(listed(pinnedNames)).containsExactlyElementsIn(pinnedNames.take(5)).inOrder()
        compose.onNodeWithText("Show 2 more").assertIsDisplayed()
        assertThat(listed(todayNames)).containsExactlyElementsIn(todayNames).inOrder()
        compose.onNodeWithTag("section-more-date:Today").assertDoesNotExist()

        compose.onNodeWithText("Show 2 more").performClick()
        compose.waitForIdle()
        assertThat(listed(pinnedNames)).containsExactlyElementsIn(pinnedNames).inOrder()

        // Folded, Pinned shows nothing, its Show row included.
        compose.onNodeWithContentDescription("Collapse Pinned").performClick()
        compose.waitForIdle()
        assertThat(listed(pinnedNames)).isEmpty()
        compose.onNodeWithTag("section-more-${AgentListOrganizer.PINNED_KEY}").assertDoesNotExist()
    }

    @Test
    fun `a search lists every matching Project, past the fifth too`() {
        showSidebar()
        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.onNode(hasAnyAncestor(hasTestTag("sidebar-search")) and hasSetTextAction()).performTextInput("S")
        // The organizer narrows the rows; this state stands in for its answer to "S".
        val matching = projects.filter { it.agent.name.contains("S", ignoreCase = true) }
        listState = listState.copy(sections = listOf(AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", matching)))
        compose.waitForIdle()
        assertThat(matching.size).isGreaterThan(5)
        assertThat(listed(projectNames)).containsExactlyElementsIn(matching.map { it.agent.name }).inOrder()
        showMore.assertDoesNotExist()
    }

    @Test
    fun `the Show more row carries a dot while a Project it holds back is unread`() {
        showSidebar(sections = listOf(AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", projects.mapIndexed { i, r -> if (i == 10) r.copy(isUnread = true, indicator = AgentIndicator.Unread) else r })))
        compose.onNodeWithTag("section-more-unread-${AgentListOrganizer.PROJECTS_KEY}", useUnmergedTree = true).assertIsDisplayed()

        // Listed, the unread row speaks for itself.
        compose.onNodeWithText("Show 7 more").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("section-more-unread-${AgentListOrganizer.PROJECTS_KEY}", useUnmergedTree = true).assertDoesNotExist()

        // An unread Project within the five puts no dot on the row.
        compose.onNodeWithText("Show less").performClick()
        listState = listState.copy(sections = listOf(AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", projects.mapIndexed { i, r -> if (i == 1) r.copy(isUnread = true, indicator = AgentIndicator.Unread) else r })))
        compose.waitForIdle()
        compose.onNodeWithTag("section-more-unread-${AgentListOrganizer.PROJECTS_KEY}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `five Projects are listed without a Show more row`() {
        showSidebar(sections = listOf(AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", projects.take(5))))
        assertThat(listed(projectNames)).hasSize(5)
        showMore.assertDoesNotExist()
    }

    private fun showSidebar(
        sections: List<AgentSection> = listOf(
            AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", projects),
            AgentSection(AgentListOrganizer.PINNED_KEY, "Pinned", listOf(row("pin", "Pinned chat", pinned = true))),
            AgentSection("date:Today", "Today", listOf(row("today", "Morning standup"))),
        ),
        shorten: Boolean = true,
    ) {
        listState = AgentListUiState(sections = sections, hasLoaded = true, shortenLongGroups = shorten)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = listState,
                    user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                    isDemo = true,
                    selectedAgentId = selected,
                    selectedDestination = null,
                    onQueryChange = {},
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = {},
                        onCustomize = {},
                        onToggleSidebar = null,
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                        onSectionCollapsed = { key, folded ->
                            listState = listState.copy(collapsedSections = if (folded) listState.collapsedSections + key else listState.collapsedSections - key)
                        },
                    ),
                    shortLists = shortLists,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun row(id: String, name: String, pinned: Boolean = false, isProject: Boolean = false) = AgentRow(
        agent = Agent(
            id = id,
            name = name,
            lifecycle = AgentLifecycle.IDLE,
            runStatus = RunStatus.FINISHED,
            envType = EnvType.CLOUD,
            envName = null,
            url = "https://cursor.com/agents/$id",
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
            latestRunId = null,
            repoUrl = null,
            startingRef = null,
            isProject = isProject,
        ),
        indicator = AgentIndicator.Read,
        isPinned = pinned,
        isUnread = false,
        launchedFromThisDevice = false,
    )
}
