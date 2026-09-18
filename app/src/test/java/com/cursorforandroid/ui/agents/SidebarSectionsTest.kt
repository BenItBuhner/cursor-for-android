package com.cursorforandroid.ui.agents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.GroupLabel
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarSectionsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `clicking a date header hides that section's rows and leaves the others`() {
        showSidebar()
        compose.onNodeWithText("Morning standup").assertIsDisplayed()
        compose.onNodeWithText("Old chat").assertIsDisplayed()

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("Morning standup").assertDoesNotExist()
        compose.onNodeWithText("Old chat").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        // The fold is reported by key, for the device to remember; the sidebar reads it back from the state.
        assertThat(folds).containsExactly("date:Today" to true)

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("Morning standup").assertIsDisplayed()
        assertThat(folds.last()).isEqualTo("date:Today" to false)
    }

    @Test
    fun `every group header carries a chevron that folds the group like the row does, and a folded header keeps its count`() {
        showSidebar()
        // A chevron on each header, saying which way it will fold; down while open (nothing is hovered here).
        listOf("Pinned", "Today", "Yesterday").forEach { title ->
            compose.onNodeWithContentDescription("Collapse $title").assertIsDisplayed()
            compose.onNodeWithTag("section-count-${keyOf(title)}", useUnmergedTree = true).assertDoesNotExist()
        }

        // The chevron alone folds the group: its rows go, the header stays with the count of what it hides.
        compose.onNodeWithContentDescription("Collapse Yesterday").performClick()
        compose.onNodeWithText("Old chat").assertDoesNotExist()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithTag("section-count-date:Yesterday", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(" · 1")
        compose.onNodeWithContentDescription("Expand Yesterday").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse Yesterday").assertDoesNotExist()
        compose.onNodeWithTag("section-date:Yesterday").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        // The other groups are untouched.
        compose.onNodeWithText("Morning standup").assertIsDisplayed()
        compose.onNodeWithText("Pinned chat").assertIsDisplayed()

        // And opens it again, the count going with the fold.
        compose.onNodeWithContentDescription("Expand Yesterday").performClick()
        compose.onNodeWithText("Old chat").assertIsDisplayed()
        compose.onNodeWithTag("section-count-date:Yesterday", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("section-date:Yesterday").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        assertThat(folds).containsExactly("date:Yesterday" to true, "date:Yesterday" to false).inOrder()
    }

    @Test
    fun `a folded header shows a dot while one of its rows is unread, and none once they are read`() {
        showSidebar(
            sections = listOf(
                AgentSection("date:Today", "Today", listOf(row("a", "Read one"), row("b", "Unread one", unread = true), row("c", "Another read"))),
                AgentSection("date:Yesterday", "Yesterday", listOf(row("d", "Old chat"))),
            ),
        )
        // Open, the rows speak for themselves: no dot on any header.
        compose.onNodeWithTag("section-unread-date:Today", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithTag("section-count-date:Today", useUnmergedTree = true).assertTextEquals(" · 3")
        compose.onNodeWithContentDescription("Unread chats in Today").assertIsDisplayed()
        // A folded group with nothing unread carries no dot.
        compose.onNodeWithText("Yesterday").performClick()
        compose.onNodeWithTag("section-unread-date:Yesterday", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("section-count-date:Yesterday", useUnmergedTree = true).assertIsDisplayed()

        // The unread row is read (a new list lands): the dot goes, the fold stays.
        listState = listState.copy(sections = listState.sections.map { s -> s.copy(rows = s.rows.map { it.copy(isUnread = false, indicator = AgentIndicator.Read) }) })
        compose.waitForIdle()
        compose.onNodeWithTag("section-unread-date:Today", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Unread one").assertDoesNotExist()
    }

    @Test
    fun `the Projects header pairs the plus with the chevron, same size and side by side, and only the chevron folds`() {
        var created = 0
        val project = row("proj", "Billing launch", isProject = true, children = listOf(row("w1", "Webhook worker")))
        showSidebar(
            sections = listOf(AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", listOf(project)), AgentSection("date:Today", "Today", listOf(row("today", "Morning standup")))),
            onNewProject = { created++ },
        )
        val plus = compose.onNodeWithTag("new-project").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val chevron = compose.onNodeWithTag("section-chevron-projects").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // The title's own text node: merged, "Projects" is the whole header row.
        val title = compose.onNode(hasText("Projects"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // The same size, centred on one line, the plus immediately to the left of the chevron.
        assertThat(plus.width).isWithin(0.5f).of(chevron.width)
        assertThat(plus.height).isWithin(0.5f).of(chevron.height)
        assertThat(plus.center.y).isWithin(0.5f).of(chevron.center.y)
        assertThat(plus.right).isAtMost(chevron.left)
        with(compose.density) { assertThat((chevron.left - plus.right)).isAtMost(8.dp.toPx()) }
        // Right-aligned: the chevron is the header's last thing, with room after it, and the title stands well clear.
        assertThat(chevron.left).isGreaterThan(title.right)
        with(compose.density) { assertThat(chevron.width).isWithin(0.5f).of(HeaderGlyphButtonSize.toPx()) }
        // Every other header's chevron is the same button.
        val today = compose.onNodeWithTag("section-chevron-date:Today").fetchSemanticsNode().boundsInRoot
        assertThat(today.width).isWithin(0.5f).of(chevron.width)
        assertThat(today.right).isWithin(0.5f).of(chevron.right)

        // The plus creates; it never folds the group. The chevron folds; it never creates.
        compose.onNodeWithTag("new-project").performClick()
        assertThat(created).isEqualTo(1)
        assertThat(folds).isEmpty()
        compose.onNodeWithText("Billing launch").assertIsDisplayed()
        compose.onNodeWithTag("section-chevron-projects").performClick()
        assertThat(created).isEqualTo(1)
        assertThat(folds).containsExactly("projects" to true)
        compose.onNodeWithText("Billing launch").assertDoesNotExist()
        // Folded, the Projects header still offers the plus.
        compose.onNodeWithTag("new-project").assertIsDisplayed()
        compose.onNodeWithTag("section-count-projects", useUnmergedTree = true).assertTextEquals(" · 1")
    }

    @Test
    fun `a search opens folded groups for as long as it is typed`() {
        showSidebar(collapsed = setOf("date:Yesterday"))
        compose.onNodeWithText("Old chat").assertDoesNotExist()
        compose.onNodeWithTag("section-count-date:Yesterday", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("old")
        compose.waitForIdle()
        // The fold is still the device's; only the view opens while the search is up.
        compose.onNodeWithText("Old chat").assertIsDisplayed()
        compose.onNodeWithTag("section-count-date:Yesterday", useUnmergedTree = true).assertDoesNotExist()
        assertThat(listState.collapsedSections).containsExactly("date:Yesterday")

        compose.onNodeWithContentDescription("Close search").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Old chat").assertDoesNotExist()
        compose.onNodeWithTag("section-count-date:Yesterday", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the account footer shows the name and never the email`() {
        showSidebar(isDemo = false)
        compose.onNodeWithText("Demo User").assertIsDisplayed()
        compose.onNodeWithText("a@b.com").assertDoesNotExist()
    }

    @Test
    fun `a Project's workers stay behind their count until the row's chevron is tapped`() {
        val project = row("proj", "Billing launch", isProject = true, children = listOf(row("w1", "Webhook worker"), row("w2", "Usage aggregation", children = listOf(row("w3", "Backfill subagent")))))
        showSidebar(sections = listOf(AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", listOf(project)), AgentSection("date:Today", "Today", listOf(row("today", "Morning standup")))))
        compose.onNodeWithText("Projects").assertIsDisplayed()
        compose.onNodeWithText("Billing launch").assertIsDisplayed()
        compose.onNodeWithText("Webhook worker").assertDoesNotExist()
        // Three chats hang off the Project: the two workers and the subagent one of them spawned.
        compose.onNodeWithText("3").assertIsDisplayed()

        compose.onNodeWithContentDescription("Show chats under Billing launch").performClick()
        compose.onNodeWithText("Webhook worker").assertIsDisplayed()
        compose.onNodeWithText("Usage aggregation").assertIsDisplayed()
        compose.onNodeWithText("Backfill subagent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show chats under Usage aggregation").performClick()
        compose.onNodeWithText("Backfill subagent").assertIsDisplayed()

        compose.onNodeWithContentDescription("Hide chats under Billing launch").performClick()
        compose.onNodeWithText("Webhook worker").assertDoesNotExist()
        compose.onNodeWithText("Backfill subagent").assertDoesNotExist()
        compose.onNodeWithText("Morning standup").assertIsDisplayed()
    }

    @Test
    fun `clicking Pinned collapses only the pinned group`() {
        showSidebar()
        compose.onNodeWithText("Pinned chat").assertIsDisplayed()
        compose.onNodeWithText("Pinned").performClick()
        compose.onNodeWithText("Pinned chat").assertDoesNotExist()
        compose.onNodeWithText("Morning standup").assertIsDisplayed()
        assertThat(folds).containsExactly("pinned" to true)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the chevron sits next to the title only while the header is hovered, and flips when collapsed`() {
        compose.setContent {
            var expanded by remember { mutableStateOf(true) }
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.size(280.dp, 48.dp)) {
                    GroupLabel("Yesterday", expanded = expanded, onToggle = { expanded = !expanded })
                }
            }
        }

        compose.onNodeWithContentDescription("Collapse Yesterday").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand Yesterday").assertDoesNotExist()

        compose.onNodeWithText("Yesterday").performMouseInput { enter() }
        compose.onNodeWithContentDescription("Collapse Yesterday").assertIsDisplayed()

        compose.onNodeWithText("Yesterday").performMouseInput { exit(Offset(-1f, -1f)) }
        compose.onNodeWithContentDescription("Collapse Yesterday").assertDoesNotExist()

        compose.onNodeWithText("Yesterday").performClick()
        compose.onNodeWithText("Yesterday").performMouseInput { enter(center) }
        compose.onNodeWithContentDescription("Expand Yesterday").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse Yesterday").assertDoesNotExist()

        compose.onNodeWithText("Yesterday").performMouseInput { exit(Offset(-1f, -1f)) }
        compose.onNodeWithContentDescription("Expand Yesterday").assertDoesNotExist()
    }

    @Test
    fun `search keeps typed characters at the end while the organized list state lags`() {
        var listState by mutableStateOf(searchListState())
        val reported = mutableListOf<String>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = listState,
                    user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                    isDemo = true,
                    selectedAgentId = null,
                    selectedDestination = null,
                    onQueryChange = { reported += it },
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = {},
                        onCustomize = {},
                        onToggleSidebar = null,
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Search chats").performClick()
        val field = compose.onAllNodes(hasSetTextAction()).onFirst()
        // One character, then a list recomposition that still carries the old (empty) query — the combine that
        // organises the sidebar runs off the main thread, so this is what a keystroke actually looks like.
        field.performTextInput("c")
        listState = listState.copy(nowMillis = listState.nowMillis + 1)
        compose.waitForIdle()
        field.performTextInput("e")
        listState = listState.copy(nowMillis = listState.nowMillis + 1)
        compose.waitForIdle()
        field.performTextInput("s")
        compose.waitForIdle()

        assertThat(reported.last()).isEqualTo("ces")
        compose.onNode(hasSetTextAction() and hasText("ces")).assertExists()
        // A cursor that jumped to the start would have built "sec" (or "esc") instead.
        assertThat(reported).doesNotContain("sec")
        assertThat(reported).doesNotContain("esc")
    }

    @Test
    fun `a list at its top stays there when the Projects group lands above the first row`() {
        // Enough rows that the list scrolls, with nothing above "Pinned" yet: the sidebar as it is while the list loads.
        val today = (1..30).map { row("t$it", "Chat number $it") }
        var listState by mutableStateOf(
            AgentListUiState(sections = listOf(AgentSection("pinned", "Pinned", listOf(row("pin", "Pinned chat", pinned = true))), AgentSection("date:Today", "Today", today)), hasLoaded = true),
        )
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.size(320.dp, 480.dp)) {
                    Sidebar(
                        state = listState,
                        user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                        isDemo = true,
                        selectedAgentId = null,
                        selectedDestination = null,
                        onQueryChange = {},
                        callbacks = SidebarCallbacks(
                            onNewChat = {},
                            onSettings = {},
                            onCustomize = {},
                            onToggleSidebar = null,
                            onRefresh = {},
                            rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                        ),
                    )
                }
            }
        }
        compose.onNodeWithText("Pinned").assertIsDisplayed()
        compose.onNodeWithText("Chat number 30").assertDoesNotExist()

        // The publish that completes the load puts the Projects group above everything: the list shows it.
        val project = row("proj", "Billing launch", isProject = true, children = listOf(row("w1", "Webhook worker")))
        listState = listState.copy(sections = listOf(AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", listOf(project))) + listState.sections)
        compose.waitForIdle()
        compose.onNodeWithText("Projects").assertIsDisplayed()
        compose.onNodeWithText("Billing launch").assertIsDisplayed()
        compose.onNodeWithText("Pinned").assertIsDisplayed()

        // A list the reader has scrolled keeps its place when rows land above it.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Chat number 30"))
        compose.waitForIdle()
        compose.onNodeWithText("Chat number 30").assertIsDisplayed()
        listState = listState.copy(sections = listOf(AgentSection("newer", "Newer", listOf(row("n1", "Brand new")))) + listState.sections)
        compose.waitForIdle()
        compose.onNodeWithText("Chat number 30").assertIsDisplayed()
        compose.onNodeWithText("Brand new").assertDoesNotExist()
    }

    private fun searchListState() = AgentListUiState(
        sections = listOf(
            AgentSection("date:Today", "Today", listOf(row("today", "Morning standup"))),
        ),
        hasLoaded = true,
        query = "",
    )

    /** The sidebar's state as the view model would hold it; the folds reported through the callback are written back here. */
    private var listState by mutableStateOf(AgentListUiState())

    /** Every fold reported, in order: the section key and whether it was folded closed. */
    private val folds = mutableListOf<Pair<String, Boolean>>()

    private fun keyOf(title: String): String = if (title == "Pinned") "pinned" else "date:$title"
    /**
     * The pull's indicator is let go once the first page is on screen; while the rest of the refresh settles the list
     * says so in a quiet footer, and says nothing once it has settled. The rows on screen are reported to the view
     * model, which reads their pull request badges — not every row's.
     */
    @Test
    fun `while older items still sync the list shows a quiet footer, and reports the rows on screen`() {
        var visible: List<String> = emptyList()
        showSidebar(isSyncingOlder = true, onVisibleRows = { visible = it })
        compose.onNodeWithText("Still syncing older items\u2026").assertIsDisplayed()
        compose.waitForIdle()
        assertThat(visible).containsExactly("pin", "today", "yday").inOrder()
    }

    @Test
    fun `once the refresh has settled the footer is gone`() {
        showSidebar(isSyncingOlder = false)
        compose.onNodeWithText("Still syncing older items\u2026").assertDoesNotExist()
    }

    private fun showSidebar(
        isDemo: Boolean = true,
        sections: List<AgentSection> = listOf(
            AgentSection("pinned", "Pinned", listOf(row("pin", "Pinned chat", pinned = true))),
            AgentSection("date:Today", "Today", listOf(row("today", "Morning standup"))),
            AgentSection("date:Yesterday", "Yesterday", listOf(row("yday", "Old chat"))),
        ),
        collapsed: Set<String> = emptySet(),
        onNewProject: (() -> Unit)? = null,
        isSyncingOlder: Boolean = false,
        onVisibleRows: (List<String>) -> Unit = {},
    ) {
        listState = AgentListUiState(sections = sections, hasLoaded = true, collapsedSections = collapsed, isSyncingOlder = isSyncingOlder)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = listState,
                    user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                    isDemo = isDemo,
                    selectedAgentId = null,
                    selectedDestination = null,
                    onQueryChange = {},
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = {},
                        onCustomize = {},
                        onToggleSidebar = null,
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                        onNewProject = onNewProject,
                        // What the view model does: the fold is remembered and comes back through the state.
                        onSectionCollapsed = { key, folded ->
                            folds += key to folded
                            listState = listState.copy(collapsedSections = if (folded) listState.collapsedSections + key else listState.collapsedSections - key)
                        },
                        onVisibleRows = onVisibleRows,
                    ),
                )
            }
        }
    }

    private fun row(id: String, name: String, pinned: Boolean = false, isProject: Boolean = false, children: List<AgentRow> = emptyList(), unread: Boolean = false) = AgentRow(
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
        indicator = if (unread) AgentIndicator.Unread else AgentIndicator.Read,
        isPinned = pinned,
        isUnread = unread,
        launchedFromThisDevice = false,
        children = children,
    )
}
