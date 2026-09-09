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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
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

    private fun showSidebar(isDemo: Boolean = true) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = AgentListUiState(
                        sections = listOf(
                            AgentSection("pinned", "Pinned", listOf(row("pin", "Pinned chat", pinned = true))),
                            AgentSection("date:Today", "Today", listOf(row("today", "Morning standup"))),
                            AgentSection("date:Yesterday", "Yesterday", listOf(row("yday", "Old chat"))),
                        ),
                        hasLoaded = true,
                    ),
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
                        rowActions = AgentRowActions({}, {}, {}, {}, {}),
                    ),
                )
            }
        }
    }

    private fun row(id: String, name: String, pinned: Boolean = false) = AgentRow(
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
        ),
        indicator = AgentIndicator.Read,
        isPinned = pinned,
        isUnread = false,
        launchedFromThisDevice = false,
    )
}
