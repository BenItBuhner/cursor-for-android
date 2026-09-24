package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
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

    /** The prompts and replies in [after] that [before] did not have. */
    fun newMessages(before: List<TimelineItem>, after: List<TimelineItem>): Int {
        val seen = before.asSequence().filter { it.isMessage }.mapTo(HashSet()) { it.id }
        return after.count { it.isMessage && it.id !in seen }
    }

    private val TimelineItem.isMessage: Boolean get() = this is UserMessage || this is AssistantMessage
}
