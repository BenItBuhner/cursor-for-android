package com.cursorforandroid.ui.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The account footer as the official app has it: the row opens Settings, its one trailing icon opens the filter and
 * group sheet, and the header above the list no longer carries that icon.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarAccountFooterTest {

    @get:Rule
    val compose = createComposeRule()

    private var prefs by mutableStateOf(ListPreferences())
    private var settingsOpened = 0
    private var filterOpened = 0

    private fun showSidebar() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = AgentListUiState(hasLoaded = true, prefs = prefs),
                    user = CursorUser("key", "bennett@example.com", "Bennett", "Buhner", 1),
                    isDemo = false,
                    selectedAgentId = null,
                    selectedDestination = null,
                    onQueryChange = {},
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = { settingsOpened++ },
                        onCustomize = { filterOpened++ },
                        onToggleSidebar = {},
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, null, { _, _ -> }, {}),
                    ),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun count(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    @Test
    fun `the filter icon sits in the account row, and the overflow button is gone`() {
        showSidebar()

        assertThat(count(FILTER)).isEqualTo(1)
        assertThat(count("Account")).isEqualTo(0)
        assertThat(count("More")).isEqualTo(0)

        val footer = compose.onNodeWithTag(SidebarTags.ACCOUNT).assertIsDisplayed().getBoundsInRoot()
        val filter = compose.onNodeWithContentDescription(FILTER).assertIsDisplayed().getBoundsInRoot()
        val newChat = compose.onNodeWithContentDescription("New chat").getBoundsInRoot()
        assertThat(filter.top).isAtLeast(footer.top)
        assertThat(filter.bottom).isAtMost(footer.bottom)
        assertThat(filter.right).isAtMost(footer.right)
        assertThat(filter.top).isGreaterThan(newChat.bottom)
    }

    @Test
    fun `the filter icon opens the filter sheet and the row opens Settings`() {
        showSidebar()

        compose.onNodeWithContentDescription(FILTER).assertHasClickAction().performClick()
        assertThat(filterOpened).isEqualTo(1)
        assertThat(settingsOpened).isEqualTo(0)

        compose.onNodeWithTag(SidebarTags.ACCOUNT).assertHasClickAction().performClick()
        assertThat(settingsOpened).isEqualTo(1)
        assertThat(filterOpened).isEqualTo(1)
    }

    @Test
    fun `the header keeps new chat, search and the sidebar toggle`() {
        showSidebar()

        val newChat = compose.onNodeWithContentDescription("New chat").assertIsDisplayed().getBoundsInRoot()
        val search = compose.onNodeWithContentDescription("Search chats").assertIsDisplayed().getBoundsInRoot()
        val toggle = compose.onNodeWithContentDescription("Toggle sidebar").assertIsDisplayed().getBoundsInRoot()
        // Evenly spaced, with nothing between them: the filter's old slot closed up. The labelled nodes are the
        // buttons' enlarged hit layers, which overlap, so it is their centres that are compared.
        val first = (search.left + search.right - newChat.left - newChat.right).value / 2
        val second = (toggle.left + toggle.right - search.left - search.right).value / 2
        assertThat(first).isWithin(0.01f).of(second)
    }

    private companion object {
        const val FILTER = "Filter and group chats"
    }
}
