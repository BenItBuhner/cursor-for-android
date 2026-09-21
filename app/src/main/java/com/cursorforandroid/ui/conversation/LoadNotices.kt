package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.RecordFallback
import com.cursorforandroid.domain.NoticeTone
import java.security.MessageDigest

/**
 * One notice about how the transcript loaded, as the dock shows it over the composer (see [LoadNoticeCard]): a load
 * or a refresh that failed, in the server's words, or the account's record refused with the documented endpoints
 * standing in. Not a queued follow-up and not a held one: those are the reader's own messages, and stay.
 *
 * [identity] is what closing the notice is remembered by (see [NoticeDismissals]): its words — the title and the
 * detail — and nothing else. The same refusal read again a minute later, or after a reopen, is the same notice and
 * stays closed; a refusal in different words is a new one and shows. Hashed, so what is kept on the device is a
 * short key rather than the server's sentence.
 */
data class LoadNotice(val kind: Kind, val title: String, val detail: String? = null) {
    enum class Kind { LoadError, RecordFallback }

    /** A failed load is a failure; the record standing down is a degradation, said quietly. */
    val tone: NoticeTone get() = when (kind) {
        Kind.LoadError -> NoticeTone.Error
        Kind.RecordFallback -> NoticeTone.Neutral
    }

    val identity: String = identityOf(title, detail)

    companion object {
        /** The first sixteen hex digits of the SHA-256 of the title and the detail, the two kept apart by a NUL. */
        fun identityOf(title: String, detail: String?): String {
            val digest = MessageDigest.getInstance("SHA-256").digest((title + '\u0000' + (detail ?: "")).toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/** The notices [ConversationScreen] docks over the composer for a chat's state, and when a closed one comes back. */
object LoadNotices {

    /**
     * The notices the dock shows for [state], in the order they stack: the load's failure — or, with the runs
     * answering and the transcript not, the transcript's — over a transcript that did load (a failure with nothing
     * loaded is the screen itself, not a notice over it), then the record's refusal while nothing else has failed.
     */
    fun of(state: ConversationState): List<LoadNotice> = listOfNotNull(loadError(state), recordFallback(state))

    /** [of], less the notices the reader has closed; nothing at all while what was closed has yet to be read ([hidden] null). */
    fun shown(state: ConversationState, hidden: Set<String>?): List<LoadNotice> =
        if (hidden == null) emptyList() else of(state).filterNot { it.identity in hidden }

    fun loadError(state: ConversationState): LoadNotice? =
        (state.error ?: state.transcriptError?.let { "Couldn't refresh the transcript: $it" })?.takeIf { state.items.isNotEmpty() }?.let { LoadNotice(LoadNotice.Kind.LoadError, it) }

    fun recordFallback(state: ConversationState): LoadNotice? = state.recordFallback?.takeIf { state.error == null }?.let(::recordFallback)

    /** The record's refusal in the words [RecordFallbackRow] has always used: the title names the reason, the detail what is on screen because of it. */
    fun recordFallback(fallback: RecordFallback): LoadNotice =
        LoadNotice(LoadNotice.Kind.RecordFallback, "$RECORD_FALLBACK_TITLE: ${fallback.reason}", RECORD_FALLBACK_DETAIL)

    /**
     * Whether [state] is one a notice's absence can be read from: a load has run to its end and left a transcript
     * on screen. Not while a load is under way — a reopen starts on the disk's copy with the record still to be
     * asked, and its refusal comes back a moment later — and not the empty state a transcript is reset to before
     * "Reload transcript" reads it again: neither says the condition has cleared, only that it has not been looked
     * for yet.
     */
    fun isSettled(state: ConversationState): Boolean = !state.isLoading && state.items.isNotEmpty()
}
