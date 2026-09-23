package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The published transcript's rule for a coordinator's word to the user: a message shown with its body stays shown,
 * whichever source answers next, for as long as its turn is shown — and only under its own turn.
 */
class ShownMessagesTest {

    private fun prompt(id: String, text: String) = UserMessage(id, text)
    private fun message(group: String, callId: String, text: String, status: String = "completed", recovered: Boolean = false, messageId: String? = null) =
        ActivityGroup(group, listOf(ToolCall(callId, "sendMessage", ToolKind.Coordinator, status, "", payload = ToolPayload.CoordinatorMessage(text, recovered = recovered, messageId = messageId))))
    private fun work(group: String) = ActivityGroup(group, listOf(ToolCall("$group-read", "read_file", ToolKind.Read, "completed", "A.kt")))
    private fun footer(id: String) = RunFooter(id, "run-$id", RunStatus.FINISHED, 3_000L, emptyList())

    /** Each prompt's text with the messages drawn under it before the next. */
    private fun replies(items: List<TimelineItem>): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, MutableList<String>>>()
        for (item in items) when (item) {
            is UserMessage -> out += item.text to ArrayList()
            is ActivityGroup -> CoordinatorTranscript.messageTexts(listOf(item)).forEach { text -> out.last().second += text }
            else -> Unit
        }
        return out
    }

    private val asked = prompt("rec-prompt-0", "Where are we on the scanner?")
    private val answer = "The scanner is on day two: forty markets read, nothing flagged for you."
    private val nextAsked = prompt("rec-prompt-1", "Another regression.")
    private val nextAnswer = "Yep, I see it: the reply under your last message is gone."

    private val both = listOf(asked, work("w0"), message("m0", "c0", answer), footer("f0"), nextAsked, message("m1", "c1", nextAnswer), footer("f1"))

    @Test
    fun `a message a later build lacks is put back where it stood`() {
        val shown = ShownMessages()
        shown.keep(both, "record")
        val without = listOf(asked, work("w0"), footer("f0"), nextAsked, message("m1", "c1", nextAnswer), footer("f1"))
        val kept = shown.keep(without, "record")
        assertThat(replies(kept)).containsExactly(asked.text to listOf(answer), nextAsked.text to listOf(nextAnswer)).inOrder()
        // After the row it followed, ahead of the footer.
        assertThat(kept.indexOfFirst { it is ActivityGroup && it.id.contains(ShownMessages.SHOWN_SUFFIX) }).isEqualTo(2)
    }

    @Test
    fun `a message the next build shows under another turn is not drawn twice`() {
        val shown = ShownMessages()
        // Drawn under the first prompt before its own turn was read in; the next build has it under its own turn.
        shown.keep(listOf(asked, message("m0", "c0", answer), message("loose", "c1", nextAnswer)), "record")
        val kept = shown.keep(both, "record")
        assertThat(replies(kept)).containsExactly(asked.text to listOf(answer), nextAsked.text to listOf(nextAnswer)).inOrder()
    }

    @Test
    fun `a turn no longer shown shows none of its messages`() {
        val shown = ShownMessages()
        shown.keep(both, "record")
        val pagedOut = listOf(nextAsked, message("m1", "c1", nextAnswer), footer("f1"))
        assertThat(shown.keep(pagedOut, "record")).isEqualTo(pagedOut)
    }

    @Test
    fun `a turn the chat was rewound past forgets its messages`() {
        val shown = ShownMessages()
        shown.keep(both, "record")
        val rewound = listOf(asked, message("m0", "c0", answer), footer("f0"), prompt("rec-prompt-1", "Something else instead."))
        assertThat(replies(shown.keep(rewound, "record")).last().second).isEmpty()
        // Forgotten, not waiting: the old prompt's words back at another place do not bring it back.
        assertThat(replies(shown.keep(rewound + prompt("rec-prompt-2", nextAsked.text), "record")).last().second).isEmpty()
    }

    @Test
    fun `a build from another source finds the turn by its words`() {
        val shown = ShownMessages()
        shown.keep(both, "record")
        val fromLogs = listOf(prompt("run-a-u", asked.text), message("x0", "s0", answer), prompt("run-b-u", nextAsked.text), footer("fb"))
        assertThat(replies(shown.keep(fromLogs, "runs"))).containsExactly(asked.text to listOf(answer), nextAsked.text to listOf(nextAnswer)).inOrder()
    }

    @Test
    fun `within one source a turn with the same words elsewhere does not take another's message`() {
        val shown = ShownMessages()
        val cont = "continue"
        shown.keep(listOf(prompt("rec-prompt-4", cont), message("m4", "c4", "Picked up where we left off."), footer("f4")), "record")
        // The old "continue" paged out; a newer one with the same words has not said anything yet.
        val newer = listOf(prompt("rec-prompt-9", cont), work("w9"))
        assertThat(replies(shown.keep(newer, "record"))).containsExactly(cont to emptyList<String>())
    }

    @Test
    fun `an echo replaced by the server's copy of its prompt keeps its message`() {
        val shown = ShownMessages(standsIn = { it.startsWith("local-") })
        shown.keep(listOf(prompt("local-1", nextAsked.text), message("m1", "c1", nextAnswer)), "record")
        val served = listOf(prompt("rec-prompt-1", nextAsked.text), work("w1"))
        assertThat(replies(shown.keep(served, "record"))).containsExactly(nextAsked.text to listOf(nextAnswer))
    }

    @Test
    fun `a copy that grew, or one still being written, is the same message`() {
        val shown = ShownMessages()
        val cut = "Understood: it has come and gone for many"
        shown.keep(listOf(asked, message("m0", "c0", cut)), "record")
        val grown = listOf(asked, message("m0", "c0", "$cut versions, and 0.3.85 made it steady."))
        assertThat(shown.keep(grown, "record")).isEqualTo(grown)
        // A call not yet completed is no message shown: nothing is kept from it.
        val fresh = ShownMessages()
        fresh.keep(listOf(asked, message("m0", "c0", answer, status = "running")), "record")
        assertThat(fresh.size).isEqualTo(0)
    }

    @Test
    fun `a turn read again without its message keeps it, after its group or ahead of the footer`() {
        val before = listOf(asked, work("w0"), message("m0", "c0", answer), footer("f0"))
        val short = listOf(asked, work("w0"), footer("f0"))
        val kept = CoordinatorTranscript.keepMessages(before, short)
        assertThat(replies(kept)).containsExactly(asked.text to listOf(answer))
        assertThat(kept[2].id).isEqualTo("m0${CoordinatorTranscript.KEPT_SUFFIX}c0")
        assertThat(kept.last()).isInstanceOf(RunFooter::class.java)
        // Nothing missing: the same list.
        assertThat(CoordinatorTranscript.keepMessages(before, before)).isSameInstanceAs(before)
    }

    @Test
    fun `a message read leniently in either copy is not drawn twice`() {
        val before = listOf(asked, message("m0", "c0", "The scanner is on day two", recovered = true, messageId = "msg-1"))
        val exact = listOf(asked, message("m0", "c0", answer, messageId = "msg-1"))
        assertThat(CoordinatorTranscript.keepMessages(before, exact)).isSameInstanceAs(exact)
    }
}
