package com.cursorforandroid.ui.shortcuts

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptHit
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage

/**
 * A search palette hit on its way to its chat: the chat, once its rows are in, scrolls to the row the hit is in
 * rather than sitting on its newest turn (see `ConversationScreen`). One at a time — a newer pick replaces it.
 */
@Stable
class TranscriptFocusRequests {
    var pending by mutableStateOf<Request?>(null)
        private set

    data class Request(val agentId: String, val hit: TranscriptHit)

    fun request(agentId: String, hit: TranscriptHit) {
        pending = Request(agentId, hit)
    }

    /** The chat has shown it, or given up on it. */
    fun done(request: Request) {
        if (pending == request) pending = null
    }
}

val LocalTranscriptFocus = staticCompositionLocalOf<TranscriptFocusRequests?> { null }

/** What an open chat answers from the keyboard; the chat registers it with [ChatShortcuts] while it is composed. */
interface ChatShortcutTarget {
    /** Ctrl+Shift+B: the right-side panel opened or shut. False when the chat has no panel to show. */
    fun togglePanel(): Boolean

    /** Ctrl+R. */
    fun catchUp()

    /** Ctrl+Shift+R. */
    fun reloadTranscript()

    /** Esc: true when the chat had something of its own open to shut (its panel). */
    fun escape(): Boolean
}

/**
 * The chats composed right now and what each answers, by agent id. Keyed rather than single: while one chat slides
 * over another both are composed, and a chord goes to the one on top of the stack, which the shell knows.
 */
@Stable
class ChatShortcuts {
    private val targets = HashMap<String, ChatShortcutTarget>()

    fun register(agentId: String, target: ChatShortcutTarget) {
        targets[agentId] = target
    }

    fun unregister(agentId: String, target: ChatShortcutTarget) {
        if (targets[agentId] === target) targets.remove(agentId)
    }

    fun target(agentId: String): ChatShortcutTarget? = targets[agentId]
}

val LocalChatShortcuts = staticCompositionLocalOf<ChatShortcuts?> { null }

/**
 * Which row of a presented transcript a [TranscriptHit] is in: the row that draws the item the hit names — a message
 * by its id, a thought or tool call by its activity group's, wherever the rows fold them — or, where no row carries
 * that id (the chat was presented otherwise since it was kept), the newest row whose text holds the words searched.
 */
object TranscriptHitLocator {
    fun rowIndex(rows: List<TranscriptRow>, hit: TranscriptHit): Int? {
        hit.itemId?.let { id -> rows.indexOfFirst { draws(it, id) }.takeIf { it >= 0 }?.let { return it } }
        val needle = hit.needle.takeIf { it.isNotBlank() } ?: return null
        return rows.indexOfLast { row -> texts(row).any { it.contains(needle, ignoreCase = true) } }.takeIf { it >= 0 }
    }

    private fun draws(row: TranscriptRow, id: String): Boolean = when (row) {
        is TranscriptRow.Item -> row.item.id == id
        is TranscriptRow.Event -> row.notification.id == id
        is TranscriptRow.Events -> row.rows.any { draws(it, id) }
        is TranscriptRow.Message -> row.group.id == id
        is TranscriptRow.Media -> row.group.id == id
        is TranscriptRow.Question -> row.group.id == id
        is TranscriptRow.Stretch -> row.entries.any { entry ->
            when (entry) {
                is TranscriptRow.Entry.Event -> draws(entry.row, id)
                is TranscriptRow.Entry.Events -> draws(entry.group, id)
                else -> entry.key == id || entry.key.startsWith("$id:")
            }
        }
        else -> row.key == id
    }

    private fun texts(row: TranscriptRow): Sequence<String> = when (row) {
        is TranscriptRow.Item -> sequenceOf(
            when (val item = row.item) {
                is UserMessage -> item.text
                is AssistantMessage -> item.markdown
                else -> ""
            },
        )
        is TranscriptRow.Event -> sequenceOf(row.notification.title, row.notification.body.orEmpty())
        is TranscriptRow.Events -> row.rows.asSequence().flatMap(::texts)
        is TranscriptRow.Message -> sequenceOf((row.call.payload as? ToolPayload.CoordinatorMessage)?.message.orEmpty())
        is TranscriptRow.Stretch -> row.entries.asSequence().map { entry ->
            when (entry) {
                is TranscriptRow.Entry.Thought -> entry.block.text
                is TranscriptRow.Entry.Call -> entry.call.summary + " " + entry.call.detail.orEmpty()
                is TranscriptRow.Entry.Note -> entry.message.markdown
                else -> ""
            }
        }
        else -> emptySequence()
    }
}
