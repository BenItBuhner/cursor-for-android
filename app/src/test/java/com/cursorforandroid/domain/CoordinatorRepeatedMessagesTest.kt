package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.SevenRunCoordinator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the rows keep of a coordinator's messages whatever the runs' logs carry (see [SevenRunCoordinator] and
 * [CoordinatorTranscript.repeatedMessages]): each message once, a run that sent nothing contributing to the stretch
 * alone, one stretch between two messages with the covered runs' footers summed — through the presenter's paging,
 * a window that opens on the copy before the message's own turn is loaded, and every identity a copy can carry.
 */
class CoordinatorRepeatedMessagesTest {

    private val firstAt = 1_789_340_000_000L
    private val runs = SevenRunCoordinator.runs(firstAt)

    private fun send(group: String, callId: String, text: String, messageId: String? = null) =
        ActivityGroup(group, listOf(ToolCall(callId, "sendMessage", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage(text, messageId = messageId))))

    private fun footer(id: String, durationMs: Long) = RunFooter("footer-$id", id, RunStatus.FINISHED, durationMs, emptyList())

    private fun messages(rows: List<TranscriptRow>): List<String> = rows.filterIsInstance<TranscriptRow.Message>().map { (it.call.payload as ToolPayload.CoordinatorMessage).message }

    @Test
    fun `the same call with the same text is a copy, whichever run's log carries it`() {
        val items = listOf(send("g1", "turn-1:step:1:tool", "Done."), footer("r1", 1_000), send("g2", "turn-1:step:1:tool", "Done."), footer("r2", 2_000), send("g3", "turn-1:step:1:tool", " Done. "), footer("r3", 3_000))
        assertThat(CoordinatorTranscript.repeatedMessages(items)).containsExactly("g2:turn-1:step:1:tool", "turn-1:step:1:tool", "g3:turn-1:step:1:tool", "turn-1:step:1:tool")
        val rows = TranscriptRows.of(CoordinatorTranscript.present(items, coordinatorMode = true), coordinatorMode = true)
        assertThat(messages(rows)).containsExactly("Done.")
        // The copies' runs read as one stretch under the message, worked for all three runs' time.
        assertThat(rows.filterIsInstance<TranscriptRow.Stretch>().single().summary.action).isEqualTo("Worked 6s")
    }

    @Test
    fun `the same delivered message under another call id is a copy too, when it reads the same`() {
        val items = listOf(send("g1", "turn-1:step:1:tool", "Shipped.", messageId = "msg_1"), send("g2", "turn-2:step:0:tool", "Shipped.", messageId = "msg_1"))
        assertThat(CoordinatorTranscript.repeatedMessages(items)).containsExactly("g2:turn-2:step:0:tool", "turn-1:step:1:tool")
    }

    @Test
    fun `a positional id or a message id that comes round on other words names another message`() {
        // The stream's ids are positional: two runs' first steps share an id and say different things.
        val positional = listOf(send("g1", "turn-0:step:1:tool", "First."), send("g2", "turn-0:step:1:tool", "Second."))
        assertThat(CoordinatorTranscript.repeatedMessages(positional)).isEmpty()
        // A message id handed out twice for different words (the generated coordinator_run.sse does this) is two messages.
        val reused = listOf(send("g1", "c2", "One.", messageId = "msg_01R9x"), send("g2", "c4", "Two.", messageId = "msg_01R9x"))
        assertThat(CoordinatorTranscript.repeatedMessages(reused)).isEmpty()
        // The same words twice in two calls of the coordinator's own are two messages: the text alone is never the identity.
        val twice = listOf(send("g1", "turn-0:step:1:tool", "Noted."), send("g2", "turn-1:step:1:tool", "Noted."))
        assertThat(CoordinatorTranscript.repeatedMessages(twice)).isEmpty()
        assertThat(messages(TranscriptRows.of(CoordinatorTranscript.present(twice, coordinatorMode = true), coordinatorMode = true))).containsExactly("Noted.", "Noted.")
    }

    @Test
    fun `a message without its text is never a copy and never an original`() {
        val cut = ActivityGroup("g0", listOf(ToolCall("turn-0:step:1:tool", "sendMessage", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage("", missing = true))))
        val items = listOf(cut, send("g1", "turn-0:step:1:tool", "Now with its text."), cut.copy(id = "g2"))
        assertThat(CoordinatorTranscript.repeatedMessages(items)).isEmpty()
        val rows = TranscriptRows.of(CoordinatorTranscript.present(items, coordinatorMode = true), coordinatorMode = true)
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).hasSize(3)
    }

    @Test
    fun `the seven runs' logs present to three messages, a silent run contributing to the stretch alone`() {
        for (prompts in listOf(false, true)) {
            val items = SevenRunCoordinator.transcript(runs, prompts, firstAt)
            val presented = TranscriptPresenter().present(items, coordinatorMode = true, runActive = true)
            val rows = presented.rows
            assertThat(messages(rows)).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
            assertThat(rows.map { it.key }).containsNoDuplicates()
            // The message is drawn from run 2's own log: its group's id names run 2.
            val m2 = rows.filterIsInstance<TranscriptRow.Message>()[1]
            assertThat(m2.group.id).startsWith("activity-run-2-")
            // Nothing of the silent runs stands as a row of its own: their calls, notes and footers are entries of the one stretch after the message.
            val between = rows.subList(rows.indexOf(m2) + 1, rows.indexOfFirst { it is TranscriptRow.Message && (it.call.payload as ToolPayload.CoordinatorMessage).message == SevenRunCoordinator.M6 })
            assertThat(between).hasSize(1)
            val stretch = between.single() as TranscriptRow.Stretch
            assertThat(stretch.entries.filterIsInstance<TranscriptRow.Entry.Footer>().map { it.footer.runId }).containsExactly("run-2", "run-3", "run-4", "run-5").inOrder()
            assertThat(stretch.summary.action).isEqualTo("Worked 3m 10s")
            assertThat(stretch.entries.none { it is TranscriptRow.Entry.Call && it.call.payload is ToolPayload.CoordinatorMessage }).isTrue()
            // The presented items carry the copies' groups without the copied call — and no run's group empties.
            val presentedCalls = presented.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }
            assertThat(presentedCalls.count { it.callId == SevenRunCoordinator.M2_CALL_ID }).isEqualTo(1)
            assertThat(presentedCalls.count { it.name == "search_replace" }).isEqualTo(5)
        }
    }

    @Test
    fun `a window opened on the copy draws it once, and hands it to the message's own turn when that turn pages in`() {
        val all = SevenRunCoordinator.transcript(runs, prompts = true, firstAt)
        val startOfRun3 = all.indexOfFirst { it is SystemNotification && it.id == "prompt-run-3" }
        val newest = all.subList(startOfRun3, all.size)
        val presenter = TranscriptPresenter()
        // The newest turns alone: run 3's log has the message, no earlier turn shows it, so it is drawn there — once.
        val first = presenter.present(newest, coordinatorMode = true, runActive = true)
        assertThat(messages(first.rows)).containsExactly(SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
        assertThat(first.rows.filterIsInstance<TranscriptRow.Message>().first().group.id).startsWith("activity-run-3-")
        // The page above lands: the message's own turn is in, the copy under run 3 goes, and the message is drawn once, there.
        val second = presenter.present(all, coordinatorMode = true, runActive = true)
        assertThat(messages(second.rows)).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
        assertThat(second.rows.filterIsInstance<TranscriptRow.Message>()[1].group.id).startsWith("activity-run-2-")
        assertThat(second.rows.map { it.key }).containsNoDuplicates()
        // Only the segments that changed were cut again: the one with the copy, and the pages that landed.
        assertThat(second.segmentsReused).isAtLeast(1)
        // Presented again as it is, every segment is the last presentation's.
        val third = presenter.present(all, coordinatorMode = true, runActive = true)
        assertThat(third.segmentsBuilt).isEqualTo(0)
        assertThat(third.rows).isEqualTo(second.rows)
    }
}
