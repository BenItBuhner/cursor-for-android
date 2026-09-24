package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.repo.CatchUp
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.newMessageCount

/**
 * The brief word a refresh the reader asked for leaves on the chat: "Up to date" when the server had nothing the chat
 * did not, "3 new" for the messages it brought, or the reason the read did not go through.
 */
object RefreshWord {
    const val UP_TO_DATE = "Up to date"

    fun new(count: Int): String = "$count new"

    /** Told from the chat as it was before the refresh and as the refresh left it (see [newMessageCount]). */
    fun of(before: ConversationState, after: ConversationState): String {
        (after.error ?: after.transcriptError)?.let { return it }
        return count(newMessageCount(before.items, after.items))
    }

    /** Told from what the repository's catch-up found. */
    fun of(catchUp: CatchUp): String = catchUp.error ?: count(catchUp.newMessages)

    private fun count(newMessages: Int): String = if (newMessages == 0) UP_TO_DATE else new(newMessages)
}
