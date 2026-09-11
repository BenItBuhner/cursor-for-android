package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The working row is the only place a dropped connection is shown, and README.md promises it says "Reconnecting…"
 * while the stream is being re-established — including in the window where the reply itself is the newest item.
 */
class WorkingRowTest {

    private fun state(
        runStatus: RunStatus? = RunStatus.RUNNING,
        isStreaming: Boolean = true,
        isReconnecting: Boolean = false,
        streamingReply: Boolean = true,
    ) = ConversationState(
        agentId = "bc-1",
        items = listOf(
            if (streamingReply) AssistantMessage("a-1", "Half a repl", isStreaming = true)
            else RunFooter("run-1", "run-1", RunStatus.FINISHED, 1_000L, emptyList()),
        ),
        runStatus = runStatus,
        isStreaming = isStreaming,
        isReconnecting = isReconnecting,
    )

    @Test
    fun `a streaming reply is its own progress, so the row stays away`() {
        assertThat(state().showsWorkingRow()).isFalse()
    }

    @Test
    fun `a dropped connection brings the row back even while the reply is the newest item`() {
        assertThat(state(isReconnecting = true).showsWorkingRow()).isTrue()
    }

    @Test
    fun `a run with nothing streaming yet still shows the row`() {
        assertThat(state(streamingReply = false).showsWorkingRow()).isTrue()
    }

    @Test
    fun `a finished run never shows the row, reconnecting or not`() {
        val over = state(runStatus = RunStatus.FINISHED, isStreaming = false, isReconnecting = true, streamingReply = false)
        assertThat(over.showsWorkingRow()).isFalse()
    }
}
