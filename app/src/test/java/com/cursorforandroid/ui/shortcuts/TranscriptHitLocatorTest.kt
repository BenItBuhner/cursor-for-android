package com.cursorforandroid.ui.shortcuts

import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.TranscriptHit
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which row of the presented transcript a palette hit scrolls to: the row drawing its item, else the newest row saying it. */
class TranscriptHitLocatorTest {

    private val notice = SystemNotification("n1", SystemNotification.Kind.Subagent, title = "Subagent completed", body = "Atlas verified", raw = "")

    private val rows = listOf(
        TranscriptRow.Item(UserMessage("u1", "Pack the props into an atlas")),
        TranscriptRow.Stretch(
            listOf(
                TranscriptRow.Entry.Thought(ThinkingBlock("Mipmaps for the atlas"), key = "g1:0"),
                TranscriptRow.Entry.Call(ToolCall("c1", "grep", ToolKind.Grep, "completed", "props/"), key = "g1:c1"),
                TranscriptRow.Entry.Note(AssistantMessage("a0", "A working note")),
            ),
        ),
        TranscriptRow.Item(AssistantMessage("a1", "The shared material atlas is packed.")),
        TranscriptRow.Events(listOf(TranscriptRow.Event(notice))),
        TranscriptRow.Item(UserMessage("u2", "Thanks")),
    )

    private fun hit(itemId: String?, needle: String = "") = TranscriptHitLocator.rowIndex(rows, TranscriptHit(itemId, needle, 1))

    @Test
    fun `a message is found by its id`() {
        assertThat(hit("u1")).isEqualTo(0)
        assertThat(hit("a1")).isEqualTo(2)
        assertThat(hit("u2")).isEqualTo(4)
    }

    @Test
    fun `a thought or tool call is found by its activity group, wherever the rows folded it`() {
        assertThat(hit("g1")).isEqualTo(1)
        assertThat(hit("a0")).isEqualTo(1)
        assertThat(hit("n1")).isEqualTo(3)
    }

    @Test
    fun `an id no row draws falls back to the newest row that says the words`() {
        assertThat(hit("gone", needle = "ATLAS")).isEqualTo(3)
        assertThat(hit(null, needle = "mipmaps")).isEqualTo(1)
        assertThat(hit(null, needle = "props/")).isEqualTo(1)
    }

    @Test
    fun `nothing to go on is no row`() {
        assertThat(hit("gone")).isNull()
        assertThat(hit(null, needle = "not said anywhere")).isNull()
        assertThat(TranscriptHitLocator.rowIndex(emptyList(), TranscriptHit("u1", "atlas", 1))).isNull()
    }
}
