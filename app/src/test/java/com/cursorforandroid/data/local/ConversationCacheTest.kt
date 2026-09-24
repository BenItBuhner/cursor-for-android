package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the transcripts' store keeps once long chats outweigh its byte budget. */
class ConversationCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun conversation(agentId: String) = CachedConversation(
        agentId = agentId,
        messages = listOf(V0ConversationMessageDto("m-1", "assistant_message", "x".repeat(4_000))),
        runs = emptyList(),
    )

    @Test
    fun `past the byte budget the chat least recently opened or written goes, and opening one counts`() = runBlocking<Unit> {
        val dir = folder.newFolder("conversations")
        val cache = ConversationCache(JsonDiskCache(dir, dispatcher = Dispatchers.Unconfined), maxBytes = 9_000)
        cache.write(conversation("bc-a"))
        File(dir, "bc-a.json").setLastModified(System.currentTimeMillis() - 60_000)
        cache.write(conversation("bc-b"))
        File(dir, "bc-b.json").setLastModified(System.currentTimeMillis() - 30_000)

        // Opened after bc-b was written: bc-b is now the one used longest ago.
        assertThat(cache.read("bc-a")).isNotNull()
        cache.write(conversation("bc-c"))

        assertThat(cache.read("bc-b")).isNull()
        assertThat(cache.read("bc-a")).isNotNull()
        assertThat(cache.read("bc-c")).isNotNull()
    }

    @Test
    fun `record windows are held to their own byte budget`() = runBlocking<Unit> {
        val dir = folder.newFolder("conversations-records")
        val cache = ConversationCache(JsonDiskCache(dir, dispatcher = Dispatchers.Unconfined), maxRecordBytes = 1)
        val window = CachedRecordWindow(total = 1, firstStep = 0, turnIndexed = true, turns = listOf(CachedRecordTurn(stepIndex = 0, stepCount = 1)))
        cache.writeRecord("bc-a", window)
        File(dir, "record-windows/bc-a.json").setLastModified(System.currentTimeMillis() - 60_000)
        cache.writeRecord("bc-b", window)

        assertThat(cache.readRecord("bc-a")).isNull()
        assertThat(cache.readRecord("bc-b")).isEqualTo(window)
    }
}
