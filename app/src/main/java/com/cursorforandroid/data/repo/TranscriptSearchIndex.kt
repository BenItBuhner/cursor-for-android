package com.cursorforandroid.data.repo

import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.CachedTrace
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPassage
import com.cursorforandroid.domain.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the search palette searches a chat's transcript in: the text this device keeps of it — the `/v0` transcript's
 * prompts and replies, and the traces of its finished runs (thoughts, tool calls' lines, injected turns), the Beta
 * engine's record turns among them. The server has no search over transcripts, so the device's copies are the corpus.
 *
 * Read on demand, newest chats first, and published as it goes ([passages]), so matches in the chats read so far show
 * while the rest are read. A chat is read again only when its list row says it moved on since. Reading here never
 * counts as opening the chat: the caches' own ranking of what to keep is left as it was (see [ConversationCache.peek]).
 */
class TranscriptSearchIndex(
    private val conversations: ConversationCache,
    private val traces: TraceCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxChats: Int = MAX_CHATS,
    private val runsPerChat: Int = RUNS_PER_CHAT,
    private val totalChars: Int = TOTAL_CHARS,
) {
    private val state = MutableStateFlow<Map<String, List<TranscriptPassage>>>(emptyMap())
    val passages: StateFlow<Map<String, List<TranscriptPassage>>> = state.asStateFlow()

    private val reading = MutableStateFlow(false)

    /** A [refresh] is under way: the palette says it is still searching transcripts. */
    val isReading: StateFlow<Boolean> = reading.asStateFlow()

    private val stamps = HashMap<String, Long>()
    private val lock = Mutex()

    /**
     * Reads the chats given, newest first, as `id to updatedAt`: the ones not read since they last moved on, until
     * the chats read hold [totalChars] between them; the older ones past that are left out of the search.
     */
    suspend fun refresh(chats: List<Pair<String, Long>>) = lock.withLock {
        reading.value = true
        try {
            val wanted = chats.take(maxChats)
            val kept = wanted.mapTo(HashSet()) { it.first }
            state.update { held -> held.filterKeys { it in kept } }
            var total = 0
            for ((agentId, updatedAt) in wanted) {
                if (total >= totalChars) {
                    state.update { it - agentId }
                    stamps.remove(agentId)
                    continue
                }
                val held = state.value[agentId]
                if (stamps[agentId] == updatedAt && held != null) {
                    total += charsOf(held)
                    continue
                }
                val read = try {
                    withContext(dispatcher) { read(agentId) }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
                stamps[agentId] = updatedAt
                if (read.isNullOrEmpty()) {
                    state.update { it - agentId }
                } else {
                    total += charsOf(read)
                    state.update { it + (agentId to read) }
                }
            }
        } finally {
            reading.value = false
        }
    }

    private suspend fun read(agentId: String): List<TranscriptPassage> {
        val conversation = conversations.peek(agentId)
        val runIds = traces.newestRunIds(agentId, runsPerChat)
        val runs = if (runIds.isEmpty()) emptyList() else traces.read(agentId, runIds, markOpened = false).values.toList()
        return passagesOf(conversation, runs)
    }

    fun clear() {
        state.value = emptyMap()
        stamps.clear()
    }

    companion object {
        /** As many chats as the transcript cache keeps (`ConversationCache.MAX_ENTRIES`). */
        const val MAX_CHATS = 200

        /** The newest runs' traces read per chat: the rest of a long chat's activity is left out of the search. */
        const val RUNS_PER_CHAT = 60

        private const val PASSAGE_CHARS = 20_000
        private const val DETAIL_CHARS = 400
        private const val CHAT_CHARS = 300_000

        /** All chats together, newest first: past it the older chats are not read, keeping the index to tens of MB. */
        const val TOTAL_CHARS = 8_000_000

        /**
         * The passages of a chat in transcript order: the `/v0` transcript's messages (their ids are the timeline's
         * message ids), then what only the traces hold, oldest run first. A reply both carry is kept once.
         */
        fun passagesOf(conversation: CachedConversation?, runs: Collection<CachedTrace>): List<TranscriptPassage> {
            val out = ArrayList<TranscriptPassage>()
            val seen = HashSet<String>()
            var chars = 0
            fun add(itemId: String?, text: String?, cap: Int = PASSAGE_CHARS) {
                val t = text?.trim()?.take(cap)
                if (t.isNullOrEmpty() || chars >= CHAT_CHARS || !seen.add(t)) return
                chars += t.length
                out += TranscriptPassage(itemId, t)
            }
            conversation?.messages?.forEach { add(it.id, it.text) }
            conversation?.local?.forEach { prompt ->
                add(prompt.message.id, prompt.message.text)
                prompt.reply?.let { add(it.id, it.text) }
            }
            // Newest run first, so what the chat's cap leaves out is its oldest activity; put back in order after.
            val traced = out.size
            for (run in runs.sortedByDescending { it.createdAtMillis }) {
                val start = out.size
                run.items.forEach { item -> itemText(item, ::add) }
                out.subList(start, out.size).reverse()
            }
            out.subList(traced, out.size).reverse()
            return out
        }

        /** Every character [passages] hold: what [TOTAL_CHARS] is counted in. */
        fun charsOf(passages: List<TranscriptPassage>): Int = passages.sumOf { it.text.length }

        private fun itemText(item: TimelineItem, add: (String?, String?, Int) -> Unit) {
            when (item) {
                is UserMessage -> add(item.id, item.text, PASSAGE_CHARS)
                is AssistantMessage -> add(item.id, item.markdown, PASSAGE_CHARS)
                is ActivityGroup -> item.steps.forEach { step ->
                    when (step) {
                        is ThinkingBlock -> add(item.id, step.text, PASSAGE_CHARS)
                        is ToolCall -> {
                            // A Project's coordinator speaks to the reader through a tool: its message is its reply.
                            (step.payload as? ToolPayload.CoordinatorMessage)?.let { add(item.id, it.message, PASSAGE_CHARS) }
                            add(item.id, step.summary, DETAIL_CHARS)
                            add(item.id, step.detail, DETAIL_CHARS)
                        }
                    }
                }
                is SystemNotification -> {
                    add(item.id, item.title, DETAIL_CHARS)
                    add(item.id, item.body ?: item.summary, PASSAGE_CHARS)
                }
                else -> Unit
            }
        }
    }
}
