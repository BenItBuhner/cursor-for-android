package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage

/**
 * The brief word a refresh the reader asked for leaves on the chat: "Up to date" when the server had nothing the chat
 * did not, "3 new" for the messages it brought, or the reason the read did not go through.
 */
object RefreshWord {
    const val UP_TO_DATE = "Up to date"

    fun new(count: Int): String = "$count new"

    /** Told from the chat as it was before the refresh and as the refresh left it. */
    fun of(before: ConversationState, after: ConversationState): String {
        (after.error ?: after.transcriptError)?.let { return it }
        val count = newMessages(before.items, after.items)
        return if (count == 0) UP_TO_DATE else new(count)
    }

    /**
     * The prompts, replies and notices in [after] that [before] did not have, told by what they say rather than their
     * ids: a reload rebuilds the chat from its runs' traces, and a reply read off the conversation comes back from the
     * trace under a new id; a prompt sent from here hands over to the server's copy. The same words twice are two.
     */
    fun newMessages(before: List<TimelineItem>, after: List<TimelineItem>): Int {
        val seen = HashMap<String, Int>()
        before.forEach { item -> item.messageKey()?.let { seen.merge(it, 1, Int::plus) } }
        return after.count { item ->
            val key = item.messageKey() ?: return@count false
            val left = seen[key] ?: 0
            if (left > 0) seen[key] = left - 1
            left == 0
        }
    }

    /** A reply still streaming is not a message yet: its words are only part of what it will say. */
    private fun TimelineItem.messageKey(): String? = when (this) {
        is UserMessage -> "u:" + QueuePlacement.textKey(text)
        is AssistantMessage -> if (isStreaming) null else "a:" + markdown.trim()
        is SystemNotification -> "n:$id"
        else -> null
    }
}
