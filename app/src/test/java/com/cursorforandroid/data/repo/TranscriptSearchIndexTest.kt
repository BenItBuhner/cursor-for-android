package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.CachedTrace
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.TranscriptPassage
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the search palette searches a chat's transcript in: the kept transcript's messages, then what only the kept
 * traces hold, in order and once each; read again only when the chat moved on, and within the index's budget.
 */
class TranscriptSearchIndexTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun conversation(agentId: String, vararg messages: Pair<String, String>) =
        CachedConversation(agentId, messages.map { (id, text) -> V0ConversationMessageDto(id, if (id.startsWith("u")) "user_message" else "assistant_message", text) }, runs = emptyList())

    private fun call(summary: String, detail: String? = null) = ToolCall("c-$summary", "grep", ToolKind.Grep, "completed", summary, detail = detail)

    private fun texts(passages: List<TranscriptPassage>) = passages.map { it.itemId to it.text }

    @Test
    fun `the transcript's messages first, then the traces oldest run first, each reply once`() {
        val chat = conversation("a", "u1" to "Pack the props into an atlas", "a1" to "  Packed: the shared material atlas.  ")
        val older = CachedTrace(
            "run-1", createdAtMillis = 100,
            items = listOf(
                ActivityGroup("g1", listOf(ThinkingBlock("Looking for the props"), call("props/", detail = "rg -n props"))),
                AssistantMessage("a1", "Packed: the shared material atlas."),
            ),
        )
        val newer = CachedTrace(
            "run-2", createdAtMillis = 200,
            items = listOf(
                ActivityGroup("g2", listOf(ThinkingBlock("Mipmaps next"))),
                SystemNotification("n1", SystemNotification.Kind.Subagent, title = "Subagent completed", body = "Atlas verified", raw = ""),
            ),
        )
        val passages = TranscriptSearchIndex.passagesOf(chat, listOf(newer, older))
        assertThat(texts(passages)).containsExactly(
            "u1" to "Pack the props into an atlas",
            "a1" to "Packed: the shared material atlas.",
            "g1" to "Looking for the props",
            "g1" to "props/",
            "g1" to "rg -n props",
            "g2" to "Mipmaps next",
            "n1" to "Subagent completed",
            "n1" to "Atlas verified",
        ).inOrder()
    }

    @Test
    fun `a chat kept without its transcript is searched in its traces alone, and blank text is left out`() {
        val run = CachedTrace("r", 1, listOf(UserMessage("u9", "  "), AssistantMessage("a9", "Only in the trace")))
        assertThat(texts(TranscriptSearchIndex.passagesOf(null, listOf(run)))).containsExactly("a9" to "Only in the trace")
        assertThat(TranscriptSearchIndex.passagesOf(null, emptyList())).isEmpty()
    }

    @Test
    fun `a very long chat keeps its transcript and its newest runs, and lets its oldest activity go`() {
        val runs = (1..40).map { n -> CachedTrace("r$n", n.toLong(), listOf(AssistantMessage("a$n", "reply $n " + "x".repeat(19_000)))) }
        val passages = TranscriptSearchIndex.passagesOf(conversation("a", "u1" to "the prompt"), runs)
        assertThat(passages.first().text).isEqualTo("the prompt")
        assertThat(passages.last().itemId).isEqualTo("a40")
        assertThat(passages.map { it.itemId }).doesNotContain("a1")
        assertThat(TranscriptSearchIndex.charsOf(passages)).isAtMost(300_000 + 20_000)
    }

    private fun index(conversations: ConversationCache, traces: TraceCache, totalChars: Int = TranscriptSearchIndex.TOTAL_CHARS) =
        TranscriptSearchIndex(conversations, traces, dispatcher = Dispatchers.Unconfined, totalChars = totalChars)

    private fun caches(): Pair<ConversationCache, TraceCache> {
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        return ConversationCache(disk.child("conversations")) to TraceCache(disk.child("traces"))
    }

    @Test
    fun `refresh reads the kept chats, and a chat again only once it has moved on`() = runBlocking<Unit> {
        val (conversations, traces) = caches()
        conversations.write(conversation("a", "u1" to "shared material atlas"))
        traces.put("b", listOf(CachedTrace("rb", 5, listOf(AssistantMessage("ab", "Only the trace knows")))))
        val index = index(conversations, traces)

        index.refresh(listOf("a" to 10L, "b" to 10L, "gone" to 10L))
        assertThat(index.passages.value.keys).containsExactly("a", "b")
        assertThat(texts(index.passages.value.getValue("b"))).containsExactly("ab" to "Only the trace knows")
        assertThat(index.isReading.value).isFalse()

        conversations.write(conversation("a", "u1" to "shared material atlas", "a1" to "Done"))
        index.refresh(listOf("a" to 10L, "b" to 10L))
        assertThat(index.passages.value.getValue("a")).hasSize(1)

        index.refresh(listOf("a" to 11L, "b" to 10L))
        assertThat(index.passages.value.getValue("a")).hasSize(2)

        index.refresh(listOf("a" to 11L))
        assertThat(index.passages.value.keys).containsExactly("a")
    }

    @Test
    fun `past the budget the older chats are left out of the search`() = runBlocking<Unit> {
        val (conversations, traces) = caches()
        conversations.write(conversation("new", "u1" to "n".repeat(600)))
        conversations.write(conversation("mid", "u1" to "m".repeat(600)))
        conversations.write(conversation("old", "u1" to "o".repeat(600)))
        val index = index(conversations, traces, totalChars = 1_000)

        index.refresh(listOf("new" to 3L, "mid" to 2L, "old" to 1L))
        assertThat(index.passages.value.keys).containsExactly("new", "mid")
    }
}
