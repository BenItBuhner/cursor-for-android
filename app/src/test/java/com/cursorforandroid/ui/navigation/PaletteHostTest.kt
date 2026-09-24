package com.cursorforandroid.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.data.repo.TranscriptSearchIndex
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.shortcuts.PaletteTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The search palette reads the transcripts this device keeps for the chats the list has, and again as the list
 * changes while search is up: the list still loading when Ctrl+F came, then a chat moving on with a new reply.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-night-240dpi")
class PaletteHostTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun agent(updatedAt: Long) = Agent(
        id = CHAT_ID, name = TITLE, lifecycle = AgentLifecycle.IDLE, runStatus = RunStatus.FINISHED, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/$CHAT_ID", createdAtMillis = 1L, updatedAtMillis = updatedAt, latestRunId = "run-1",
        repoUrl = "https://github.com/bennett/visual-engine", startingRef = "main",
    )

    private fun kept(vararg replies: String) = CachedConversation(
        CHAT_ID,
        listOf(V0ConversationMessageDto("u1", "user_message", "Pack the props")) +
            replies.mapIndexed { n, text -> V0ConversationMessageDto("a$n", "assistant_message", text) },
        runs = emptyList(),
    )

    private fun hitShown(snippet: String) =
        compose.onAllNodes(hasTestTag(PaletteTags.result(0)) and hasText(TITLE, substring = true)).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasAnyAncestor(hasTestTag(PaletteTags.result(0))) and hasText(snippet, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `search opened before the list has loaded finds the transcript once the chats come, and a chat's new reply once it moves on`() {
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        val conversations = ConversationCache(disk.child("conversations"))
        runBlocking { conversations.write(kept("Packed: the shared material atlas.")) }
        val index = TranscriptSearchIndex(conversations, TraceCache(disk.child("traces")), dispatcher = Dispatchers.Unconfined)
        val shortcuts = ShellShortcuts()
        var list by mutableStateOf(AgentListUiState())
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                PaletteHost(shortcuts, list, index, onOpen = { _, _ -> })
            }
        }

        compose.runOnIdle { shortcuts.palette.openSearch() }
        compose.onNodeWithTag(PaletteTags.FIELD).performTextInput("material atlas")
        compose.waitForIdle()
        assertThat(index.passages.value).isEmpty()

        list = AgentListUiState(allAgents = listOf(agent(updatedAt = 10L)), hasLoaded = true)
        compose.waitUntil(5_000) { hitShown("shared material atlas") }

        runBlocking { conversations.write(kept("Packed: the shared material atlas.", "Mipmaps baked for every prop.")) }
        list = AgentListUiState(allAgents = listOf(agent(updatedAt = 20L)), hasLoaded = true)
        compose.onNodeWithTag(PaletteTags.FIELD).performTextClearance()
        compose.onNodeWithTag(PaletteTags.FIELD).performTextInput("mipmaps baked")
        compose.waitUntil(5_000) { hitShown("Mipmaps baked") }
    }

    private companion object {
        const val CHAT_ID = "bc-palette-1"
        const val TITLE = "Atlas packing"
    }
}
