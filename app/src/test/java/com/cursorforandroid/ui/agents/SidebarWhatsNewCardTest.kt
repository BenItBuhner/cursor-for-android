package com.cursorforandroid.ui.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.settings.WhatsNewCopy
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The card slot above the account footer: the "What's new in …" card in the update card's component, leading to the
 * notes; one card at a time, the update's first.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarWhatsNewCardTest {

    @get:Rule
    val compose = createComposeRule()

    private var updateHint by mutableStateOf<String?>(null)
    private var whatsNewHint by mutableStateOf<String?>(null)
    private var settingsOpened = 0
    private var whatsNewOpened = 0

    private fun showSidebar() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = AgentListUiState(hasLoaded = true),
                    user = CursorUser("key", "bennett@example.com", "Bennett", "Buhner", 1),
                    isDemo = false,
                    selectedAgentId = null,
                    selectedDestination = null,
                    onQueryChange = {},
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = { settingsOpened++ },
                        onCustomize = {},
                        onToggleSidebar = null,
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, null, { _, _ -> }, {}),
                        onWhatsNew = { whatsNewOpened++ },
                    ),
                    updateHint = updateHint,
                    whatsNewHint = whatsNewHint,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun cardShown(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the card names the version and opens the notes, not Settings`() {
        whatsNewHint = WhatsNewCopy.title("0.3.37")
        showSidebar()

        compose.onNodeWithTag(SidebarTags.WHATS_NEW_HINT).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("What's new in 0.3.37").assertIsDisplayed()
        assertThat(cardShown(SidebarTags.UPDATE_HINT)).isFalse()

        compose.onNodeWithTag(SidebarTags.WHATS_NEW_HINT).performClick()
        assertThat(whatsNewOpened).isEqualTo(1)
        assertThat(settingsOpened).isEqualTo(0)
    }

    @Test
    fun `one card at a time - the update takes the slot while one is due, and the slot is empty with neither`() {
        showSidebar()
        assertThat(cardShown(SidebarTags.WHATS_NEW_HINT)).isFalse()
        assertThat(cardShown(SidebarTags.UPDATE_HINT)).isFalse()

        compose.runOnIdle { whatsNewHint = WhatsNewCopy.title("0.3.37") }
        compose.waitUntil(5_000) { cardShown(SidebarTags.WHATS_NEW_HINT) }

        compose.runOnIdle { updateHint = "Update available: v0.3.38" }
        compose.waitUntil(5_000) { cardShown(SidebarTags.UPDATE_HINT) }
        assertThat(cardShown(SidebarTags.WHATS_NEW_HINT)).isFalse()
        compose.onNodeWithText("Update available: v0.3.38").assertIsDisplayed()
        compose.onNodeWithTag(SidebarTags.UPDATE_HINT).performClick()
        assertThat(settingsOpened).isEqualTo(1)
        assertThat(whatsNewOpened).isEqualTo(0)

        // The update landed and its notes were read: the slot is empty again.
        compose.runOnIdle { updateHint = null; whatsNewHint = null }
        compose.waitUntil(5_000) { !cardShown(SidebarTags.UPDATE_HINT) }
        assertThat(cardShown(SidebarTags.WHATS_NEW_HINT)).isFalse()
    }
}
