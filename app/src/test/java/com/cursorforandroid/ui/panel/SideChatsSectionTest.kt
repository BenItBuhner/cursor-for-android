package com.cursorforandroid.ui.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Side chats section, for any chat: the side chats the list hangs off it, each opening in place; the account's
 * read of them in Extended mode; starting one from here, and the plain error a refusal is.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SideChatsSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(PanelFixtures.withSideChats())

    @Before
    fun pinClock() {
        AppClock.nowMillis = { PanelFixtures.NOW }
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private val asked = mutableListOf<String>()
    private val actions = object : PanelActions by PanelActions.None {
        override fun refreshSideChats() { asked += "refresh" }
        override fun startSideChat(name: String?) { asked += "start:$name" }
        override fun openAgent(agentId: String) { asked += "open:$agentId" }
    }

    private fun show() {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ConversationPanel(state, actions, onClose = {}) } }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag(tag))

    private fun open() {
        scrollTo("section-SideChats")
        compose.onNodeWithTag("section-SideChats").performClick()
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the section lists the chat's side chats newest first, opens one in place, and re-reads them on opening`() {
        show()
        // The hint counts them while the section is closed.
        assertThat(PanelRegistry.default()[PanelSectionId.SideChats]!!.hint(state)).isEqualTo("2")
        open()
        assertThat(asked).contains("refresh")
        scrollTo("side-chats-section")
        val rows = compose.onAllNodesWithTag("side-chat").fetchSemanticsNodes()
        assertThat(rows).hasSize(2)
        assertThat(shown("Should the widget follow the toggle?")).isTrue()
        assertThat(shown("Working · 12m")).isTrue()
        assertThat(shown("Light theme contrast check")).isTrue()
        compose.onAllNodesWithTag("side-chat")[1].performClick()
        assertThat(asked).contains("open:bc-side-2")
    }

    @Test
    fun `Extended mode offers a new side chat, named or not, and shows how the start went`() {
        show()
        open()
        scrollTo("new-side-chat")
        compose.onNodeWithTag("new-side-chat").performClick()
        compose.onNodeWithText("Name (optional)").performTextInput("Pricing copy")
        compose.onNodeWithText("Start").performClick()
        assertThat(asked).contains("start:Pricing copy")

        state = state.copy(sideChatCreation = RemoteLoad.Loading)
        compose.waitForIdle()
        assertThat(shown("Starting a side chat…")).isTrue()
        assertThat(compose.onAllNodesWithTag("new-side-chat").fetchSemanticsNodes()).isEmpty()

        // A refusal is an error, whatever it says: the account's words, and a way to try again — never a "coming soon".
        state = state.copy(sideChatCreation = RemoteLoad.Failed("Cursor refused (name too long)."))
        compose.waitForIdle()
        scrollTo("side-chat-failed")
        assertThat(shown("Couldn't start a side chat")).isTrue()
        assertThat(shown("name too long")).isTrue()
        assertThat(shown("coming")).isFalse()
        // The side chats it already has are still listed and openable.
        assertThat(compose.onAllNodesWithTag("side-chat").fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Start").performClick()
        assertThat(asked.last()).isEqualTo("start:null")
    }

    @Test
    fun `the account's read names its states, and a chat with none yet says so`() {
        state = PanelFixtures.loaded().copy(capabilities = Capabilities.EXTENDED, sideChatsLoad = RemoteLoad.Loading)
        show()
        open()
        assertThat(shown("Looking for side chats…")).isTrue()

        state = state.copy(sideChatsLoad = RemoteLoad.Failed("Cursor refused (the chat is archived)."))
        compose.waitForIdle()
        assertThat(shown("the chat is archived")).isTrue()
        compose.onNodeWithText("Retry").performClick()
        assertThat(asked.count { it == "refresh" }).isAtLeast(2)

        state = state.copy(sideChatsLoad = RemoteLoad.Loaded(Unit))
        compose.waitForIdle()
        assertThat(shown("No side chats yet")).isTrue()
        compose.onNodeWithTag("new-side-chat").assertIsDisplayed()
    }

    @Test
    fun `with the mode off the side chats the list knows are listed, without an action or a placeholder`() {
        state = PanelFixtures.loaded().copy(sideChats = PanelFixtures.sideChats, sideChatsLoad = RemoteLoad.Unsupported("Needs Extended mode"))
        show()
        open()
        assertThat(compose.onAllNodesWithTag("side-chat").fetchSemanticsNodes()).hasSize(2)
        assertThat(compose.onAllNodesWithTag("new-side-chat").fetchSemanticsNodes()).isEmpty()
        assertThat(shown("Needs Extended mode")).isFalse()
        assertThat(shown("No side chats yet")).isFalse()
        compose.onAllNodesWithTag("side-chat")[0].performClick()
        assertThat(asked).contains("open:bc-side-1")

        // None known and the mode off: no section at all.
        state = PanelFixtures.loaded()
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("section-SideChats").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the demo lists its side chats but starts none`() {
        state = PanelFixtures.withSideChats().copy(isDemo = true)
        show()
        open()
        assertThat(compose.onAllNodesWithTag("side-chat").fetchSemanticsNodes()).hasSize(2)
        assertThat(compose.onAllNodesWithTag("new-side-chat").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("New side chat")).fetchSemanticsNodes()).isEmpty()
    }
}
