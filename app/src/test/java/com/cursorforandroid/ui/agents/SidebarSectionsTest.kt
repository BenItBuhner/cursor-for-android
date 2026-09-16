package com.cursorforandroid.ui.agents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
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

        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("Morning standup").assertIsDisplayed()
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

    private fun showSidebar(
        isDemo: Boolean = true,
        sections: List<AgentSection> = listOf(
            AgentSection("pinned", "Pinned", listOf(row("pin", "Pinned chat", pinned = true))),
            AgentSection("date:Today", "Today", listOf(row("today", "Morning standup"))),
            AgentSection("date:Yesterday", "Yesterday", listOf(row("yday", "Old chat"))),
        ),
    ) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = AgentListUiState(sections = sections, hasLoaded = true),
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
                    ),
                )
            }
        }
    }

    private fun row(id: String, name: String, pinned: Boolean = false, isProject: Boolean = false, children: List<AgentRow> = emptyList()) = AgentRow(
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
        children = children,
    )
}
