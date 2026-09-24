package com.cursorforandroid.ui.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
 * Ctrl held: the sidebar's first ten rows as drawn — Projects, then Pinned, then the date groups, a folded group's rows
 * and the rows behind a "Show more" skipped — show 1 … 9, 0 in place of their glyphs, and the shell is told which rows
 * those are, for Ctrl+1 … Ctrl+0. Let go, the glyphs come back.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarShortcutNumbersTest {

    @get:Rule
    val compose = createComposeRule()

    private var listState by mutableStateOf(AgentListUiState())
    private var numbers by mutableStateOf(true)
    private var reported = emptyList<AgentRow>()

    private val projects = (1..7).map { row("p$it", "Project $it", isProject = true) }
    private val pinned = (1..2).map { row("pin$it", "Pinned chat $it", pinned = true) }
    private val today = (1..5).map { row("t$it", "Today chat $it") }

    /** The digits drawn on rows, top to bottom. */
    private fun drawnDigits(): List<Int> = (0..9)
        .mapNotNull { n -> compose.onAllNodes(hasTestTag(AgentRowTags.shortcutNumber(n)), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.let { n to it.positionInRoot.y } }
        .sortedBy { it.second }
        .map { it.first }

    @Test
    fun `Ctrl held numbers the first ten rows as drawn, one to nine then zero`() {
        showSidebar()
        assertThat(drawnDigits()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 0).inOrder()
        // Five Projects (the rest are behind "Show 2 more"), the two pinned chats, then today's first three.
        assertThat(reported.map { it.agent.id }).containsExactly("p1", "p2", "p3", "p4", "p5", "pin1", "pin2", "t1", "t2", "t3").inOrder()
    }

    @Test
    fun `a folded group's rows are not numbered, and the numbers move on to the next group`() {
        showSidebar()
        compose.onNodeWithContentDescription("Collapse Projects").performClick()
        compose.waitForIdle()
        assertThat(reported.map { it.agent.id }).containsExactly("pin1", "pin2", "t1", "t2", "t3", "t4", "t5").inOrder()
        assertThat(drawnDigits()).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
    }

    @Test
    fun `let go, the glyphs are back and no numbers are drawn, though the rows stay known`() {
        showSidebar()
        compose.runOnIdle { numbers = false }
        compose.waitForIdle()
        assertThat(drawnDigits()).isEmpty()
        assertThat(reported).hasSize(10)
    }

    private fun showSidebar() {
        listState = AgentListUiState(
            sections = listOf(
                AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", projects),
                AgentSection(AgentListOrganizer.PINNED_KEY, "Pinned", pinned),
                AgentSection("date:Today", "Today", today),
            ),
            hasLoaded = true,
            shortenLongGroups = true,
        )
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
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
                        onSectionCollapsed = { key, folded ->
                            listState = listState.copy(collapsedSections = if (folded) listState.collapsedSections + key else listState.collapsedSections - key)
                        },
                        onShortcutRows = { reported = it },
                    ),
                    shortLists = SidebarShortLists(),
                    showShortcutNumbers = numbers,
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
