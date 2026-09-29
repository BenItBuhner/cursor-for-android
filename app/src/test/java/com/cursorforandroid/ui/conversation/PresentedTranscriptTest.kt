package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The user's messages a presentation carries for the screen ([PresentedTranscript.userMessages]): the transcript's, in
 * order, and the very list the last presentation had while they are the same messages, so what the screen keys on
 * them (the sent fades, the queue's deliveries) is left alone by a reply streaming under them.
 */
class PresentedTranscriptTest {

    private val presenter = TranscriptPresenter()
    private val now = 1_789_380_000_000L

    private fun present(items: List<com.cursorforandroid.domain.TimelineItem>, previous: PresentedTranscript?): PresentedTranscript {
        val state = ConversationState("bc-1", items = items, isLoading = false, isStreaming = true)
        return PresentedTranscript(state, presenter.present(items, coordinatorMode = false, runActive = true), previous)
    }

    @Test
    fun `a streaming reply keeps the list of messages`() {
        val turns = ScreenPublications.transcript(40, now)
        val first = present(turns + AssistantMessage("a-live", "Working", isStreaming = true), null)
        assertThat(first.userMessages.map { it.id }).isEqualTo((0 until 40).map { "u$it" })
        val next = present(turns + AssistantMessage("a-live", "Working on it", isStreaming = true), first)
        assertThat(next.userMessages).isSameInstanceAs(first.userMessages)
        val again = present(turns + AssistantMessage("a-live", "Working on it", isStreaming = true), next)
        assertThat(again.userMessages).isSameInstanceAs(first.userMessages)
    }

    @Test
    fun `a new message, or a message filed anew, is a new list`() {
        val turns = ScreenPublications.transcript(10, now)
        val first = present(turns, null)
        val sent = present(turns + UserMessage("local-1", "And the tests.", isPending = true), first)
        assertThat(sent.userMessages).isNotSameInstanceAs(first.userMessages)
        assertThat(sent.userMessages.last().id).isEqualTo("local-1")
        val filed = present(turns + UserMessage("u-server", "And the tests."), sent)
        assertThat(filed.userMessages).isNotSameInstanceAs(sent.userMessages)
        assertThat(filed.userMessages.last().id).isEqualTo("u-server")
        val edited = present(turns.map { if (it.id == "u3") UserMessage("u3", "Prompt 3, reworded.") else it }, filed)
        assertThat(edited.userMessages.map { it.id }).isEqualTo((0 until 10).map { "u$it" })
        assertThat(edited.userMessages[3].text).isEqualTo("Prompt 3, reworded.")
    }
}
