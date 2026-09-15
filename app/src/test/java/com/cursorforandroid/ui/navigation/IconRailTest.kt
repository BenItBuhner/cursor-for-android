package com.cursorforandroid.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.panel.PanelFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rail's glyph column: the Projects the organizer handed the sidebar as glyphs that open their coordinators,
 * the actions every glyph stands for, and the three-state rail laying it out at its width.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
class IconRailTest {

    @get:Rule
    val compose = createComposeRule()

    private val cesium = PanelFixtures.agent.copy(id = "bc-cesium", name = "Cesium billing launch", isProject = true, projectAppearance = ProjectAppearance("rocket", "purple"))
    private val noetic = PanelFixtures.agent.copy(id = "bc-noetic", name = "Noetic", isProject = true, projectAppearance = ProjectAppearance("brain", "blue"))
    private val state = AgentListUiState(
        sections = listOf(
            AgentSection(AgentListOrganizer.PROJECTS_KEY, "Projects", listOf(row(cesium, unread = true), row(noetic))),
            AgentSection("today", "Today", listOf(row(PanelFixtures.agent))),
        ),
        hasLoaded = true,
    )
    private val user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
    private val tapped = mutableListOf<String>()
    private var rail by mutableStateOf(RailState.IconOnly)

    private fun row(agent: com.cursorforandroid.domain.Agent, unread: Boolean = false) =
        AgentRow(agent, if (unread) AgentIndicator.Unread else AgentIndicator.Read, isPinned = false, isUnread = unread, launchedFromThisDevice = false)

    private fun show(selected: String? = null) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Row(Modifier.fillMaxSize()) {
                    SidebarRail(
                        state = rail,
                        iconContent = {
                            IconRail(
                                state = state,
                                user = user,
                                selectedAgentId = selected,
                                selectedDestination = SidebarDestination.NewChat,
                                callbacks = IconRailCallbacks(
                                    onNewChat = { tapped += "new" },
                                    onSearch = { tapped += "search" },
                                    onCustomize = { tapped += "customize" },
                                    onOpenRow = { tapped += "open:${it.agent.id}" },
                                    onSettings = { tapped += "settings" },
                                    onToggle = { tapped += "toggle" },
                                ),
                            )
                        },
                    ) { Box(Modifier.fillMaxSize().testTag("expanded-sidebar")) }
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("detail"))
                }
            }
        }
    }

    @Test
    fun `the glyph column shows one glyph per Project and opens its coordinator`() {
        show(selected = "bc-cesium")
        compose.onNodeWithTag("icon-rail").assertIsDisplayed()
        assertThat(compose.onAllNodesWithTag("icon-rail-project").fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithContentDescription("Project Cesium billing launch").assertIsSelected()
        compose.onNodeWithContentDescription("Project Noetic").performClick()
        assertThat(tapped).containsExactly("open:bc-noetic")
        // The rail is its glyph width; the detail pane starts past it and its hairline.
        assertThat(compose.onNodeWithTag("detail").getBoundsInRoot().left.value).isWithin(0.5f).of((WindowPosture.RAIL_ICON_DP + 1).toFloat())
        compose.onAllNodesWithTag("expanded-sidebar").fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }

    @Test
    fun `every glyph stands for its action`() {
        show()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.onNodeWithContentDescription("Filter and group chats").performClick()
        compose.onNodeWithTag("icon-rail-toggle").performClick()
        compose.onNodeWithTag("icon-rail-account").performClick()
        assertThat(tapped).containsExactly("new", "search", "customize", "toggle", "settings").inOrder()
    }

    @Test
    fun `the rail moves between its three widths and leaves the composition when hidden`() {
        show()
        rail = RailState.Expanded
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithTag("expanded-sidebar").assertExists()
        assertThat(compose.onNodeWithTag("detail").getBoundsInRoot().left.value).isWithin(0.5f).of(279f)
        rail = RailState.Hidden
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("expanded-sidebar").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("icon-rail").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onNodeWithTag("detail").getBoundsInRoot().left.value).isWithin(0.5f).of(0f)
        rail = RailState.IconOnly
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithTag("icon-rail").assertExists()
        assertThat(compose.onNodeWithTag("detail").getBoundsInRoot().left.value).isWithin(0.5f).of(57f)
    }
}
