package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.RecordFallback
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which notices the dock shows for a chat's state, what closing one is remembered by, and which states say a closed
 * notice's condition has cleared (see [LoadNotices], [NoticeDismissals]).
 */
class LoadNoticesTest {

    private val items = listOf(UserMessage("m1", "Ship it", 1_000L))
    private val fallback = RecordFallback("Rate limited by Cursor: Too many requests.", sinceMillis = 1_000L, readMillis = 640L, retryAfterMillis = 2_000L)

    @Test
    fun `the notices are the load's failure over a loaded transcript, then the record's refusal, in the screen's words`() {
        val both = ConversationState("bc-1", items = items, isLoading = false, transcriptError = "Cursor took too long to respond.", recordFallback = fallback)
        assertThat(LoadNotices.of(both).map { it.kind }).containsExactly(LoadNotice.Kind.LoadError, LoadNotice.Kind.RecordFallback).inOrder()
        assertThat(LoadNotices.of(both)[0].title).isEqualTo("Couldn't refresh the transcript: Cursor took too long to respond.")
        assertThat(LoadNotices.of(both)[0].detail).isNull()
        assertThat(LoadNotices.of(both)[0].tone).isEqualTo(NoticeTone.Error)
        assertThat(LoadNotices.of(both)[1].title).isEqualTo("$RECORD_FALLBACK_TITLE: ${fallback.reason}")
        assertThat(LoadNotices.of(both)[1].detail).isEqualTo(RECORD_FALLBACK_DETAIL)
        assertThat(LoadNotices.of(both)[1].tone).isEqualTo(NoticeTone.Neutral)
        // The load's own error outranks the transcript's, and stands the record's refusal down.
        val failed = both.copy(error = "Couldn't reach Cursor.")
        assertThat(LoadNotices.of(failed).map { it.title }).containsExactly("Couldn't reach Cursor.")
        // A failure with nothing loaded is the screen, not a notice over it; the record's refusal is a notice regardless.
        assertThat(LoadNotices.of(both.copy(items = emptyList()))).hasSize(1)
        assertThat(LoadNotices.of(both.copy(items = emptyList()))[0].kind).isEqualTo(LoadNotice.Kind.RecordFallback)
        assertThat(LoadNotices.of(ConversationState("bc-1", items = items, isLoading = false))).isEmpty()
    }

    @Test
    fun `a notice's identity is its words alone`() {
        val a = LoadNotices.recordFallback(fallback)
        // The same refusal read again, later and slower, is the same notice.
        val later = LoadNotices.recordFallback(fallback.copy(sinceMillis = 9_000L, readMillis = 4_000L, retryAfterMillis = null))
        assertThat(later.identity).isEqualTo(a.identity)
        assertThat(later).isEqualTo(a)
        // Different words are a different notice, whichever half of them changed.
        assertThat(LoadNotices.recordFallback(fallback.copy(reason = "FetchBackgroundComposer has been removed")).identity).isNotEqualTo(a.identity)
        assertThat(LoadNotice(LoadNotice.Kind.RecordFallback, a.title, detail = null).identity).isNotEqualTo(a.identity)
        assertThat(LoadNotice(LoadNotice.Kind.LoadError, a.title, a.detail).identity).isEqualTo(a.identity)
        assertThat(a.identity).matches("[0-9a-f]{16}")
    }

    @Test
    fun `what is shown leaves the closed notices out, and everything out while what is closed is still being read`() {
        val state = ConversationState("bc-1", items = items, isLoading = false, transcriptError = "Cursor took too long to respond.", recordFallback = fallback)
        val (error, record) = LoadNotices.of(state)
        assertThat(LoadNotices.shown(state, hidden = null)).isEmpty()
        assertThat(LoadNotices.shown(state, hidden = emptySet())).containsExactly(error, record).inOrder()
        assertThat(LoadNotices.shown(state, hidden = setOf(record.identity))).containsExactly(error)
        assertThat(LoadNotices.shown(state, hidden = setOf(error.identity, record.identity))).isEmpty()
    }

    @Test
    fun `a state is settled once a load has ended with a transcript on screen`() {
        // A chat being opened: the disk's copy, the record still to be asked — its refusal comes a moment later.
        assertThat(LoadNotices.isSettled(ConversationState("bc-1", items = items, isLoading = true))).isFalse()
        // The empty state a transcript is reset to before "Reload transcript" reads it again.
        assertThat(LoadNotices.isSettled(ConversationState("bc-1", isLoading = false))).isFalse()
        // A load that ended: with the refusal standing, or without it.
        assertThat(LoadNotices.isSettled(ConversationState("bc-1", items = items, isLoading = false, recordFallback = fallback))).isTrue()
        assertThat(LoadNotices.isSettled(ConversationState("bc-1", items = items, isLoading = false))).isTrue()
    }
}
