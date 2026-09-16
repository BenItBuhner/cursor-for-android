package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The transcript cut into rows: the messages as themselves and everything between two of them behind one summary,
 * on a real coordinator's turn (the stream's frames, plus the turn Cursor injected before it) and a real worker's
 * turn (see [CoordinatorFixtures]).
 */
class TranscriptRowsTest {

    private val update = "The keyboard worker merged #113 with four root causes fixed against the Gboard reference; **v0.3.4** is being cut from it, Bennett has the explanation plus the note that his recording was the reference app, and the board is updated."

    /** The coordinator's chat as the screen has it: the injected turn with its remark, then the streamed turn. */
    private fun coordinatorItems(): List<TimelineItem> {
        val injected = SystemNotifications.parse("m-inject", CoordinatorFixtures.injectedTurn("subagent_completion_necessary_follow_up"), 1_789_340_700_000L)!!
        val items = injected.items + AssistantMessage("asst-inject", "Noted; the release worker is done.") + RunFooter("run-inject", "run-inject", RunStatus.FINISHED, 41_000, emptyList()) +
            CoordinatorFixtures.replay("coordinator_run.sse", "run-coord-001")
        return CoordinatorTranscript.present(items, coordinatorMode = true)
    }

    private fun kinds(rows: List<TranscriptRow>) = rows.map { row ->
        when (row) {
            is TranscriptRow.Item -> "item:${row.item::class.simpleName}"
            is TranscriptRow.Message -> "message:${row.call.callId}"
            is TranscriptRow.Worker -> "worker:${row.call.callId}"
            is TranscriptRow.Media -> "media"
            is TranscriptRow.Question -> "question:${row.call.callId}"
            is TranscriptRow.Stretch -> if (row.single != null) "single:${row.single!!.key}" else "stretch:${row.summary.text}"
            is TranscriptRow.Event -> "event:${row.line.text}" + if (row.count > 1) " ×${row.count}" else ""
            is TranscriptRow.Events -> "events:${row.summary.text}"
        }
    }

    @Test
    fun `a coordinator's turn is its messages, its worker's card, and one stretch between each pair of them`() {
        val rows = TranscriptRows.of(coordinatorItems(), coordinatorMode = true)
        assertThat(kinds(rows)).containsExactly(
            // The injected turn (its remark folded under its line), its run's footer, and the coordinator's first
            // note, thought and message to the worker: everything before the first update, one stretch.
            "stretch:Worked 41s · 1 event · 1 agent · 1 thought",
            "message:c2",
            // Its edit and its second note, before the next updates.
            "stretch:1 edit · 1 note",
            "message:c4",
            "message:c5",
            "message:c6",
            // The failed send is a row of its own, then the older tool's message and the worker it created.
            "single:activity-run-coord-001-3:c7",
            "message:c8",
            "worker:c9",
            // The status check and the run's footer close the turn.
            "stretch:Worked 1m 21s · 1 agent",
        ).inOrder()
        val closing = rows.last() as TranscriptRow.Stretch
        assertThat(closing.listed.map { it.key }).containsExactly("activity-run-coord-001-3:c10").inOrder()
        assertThat(closing.summary.busy).isFalse()
        assertThat(closing.summary.lineStats).isNull()
        val edits = rows[2] as TranscriptRow.Stretch
        assertThat(edits.summary.lineStats).isEqualTo("+2 -1")
        assertThat(edits.entries.map { it::class.simpleName }).containsExactly("Call", "Note").inOrder()
        // The opening stretch: the event's line first, then the footer (carried by the summary, not listed), the note, the thought, the call.
        val opening = rows[0] as TranscriptRow.Stretch
        assertThat(opening.entries.map { it::class.simpleName }).containsExactly("Event", "Footer", "Note", "Thought", "Call").inOrder()
        assertThat(opening.listed.map { it::class.simpleName }).containsExactly("Event", "Note", "Thought", "Call").inOrder()
        assertThat((opening.entries.first() as TranscriptRow.Entry.Event).row.notification.narration).startsWith("Noted;")
        // Keys are stable and unique.
        assertThat(rows.map { it.key }).containsNoDuplicates()
        assertThat(rows.map { it.key }).isEqualTo(TranscriptRows.of(coordinatorItems(), coordinatorMode = true).map { it.key })
    }

    @Test
    fun `the message rows carry the coordinator's words, the worker row its card`() {
        val rows = TranscriptRows.of(coordinatorItems(), coordinatorMode = true)
        val first = rows.filterIsInstance<TranscriptRow.Message>().first()
        assertThat((first.call.payload as ToolPayload.CoordinatorMessage).message).isEqualTo(update)
        val worker = rows.filterIsInstance<TranscriptRow.Worker>().single()
        assertThat((worker.call.payload as ToolPayload.WorkerAction).kind).isEqualTo(ToolPayload.WorkerAction.Kind.Created)
        assertThat(rows.filterIsInstance<TranscriptRow.Media>()).isEmpty()
        assertThat(rows.filterIsInstance<TranscriptRow.Question>()).isEmpty()
    }

    @Test
    fun `a worker's real turn is its replies with one stretch between each, and the footer alone at the end`() {
        val meta = CoordinatorFixtures.json("agent_run.json")
        val items = listOf(UserMessage("u1", meta.getValue("prompt").toString().trim('"'))) + CoordinatorFixtures.replay("agent_run.sse", "run-agent-001")
        val rows = TranscriptRows.of(items, coordinatorMode = false)

        val replies = items.filterIsInstance<AssistantMessage>()
        assertThat(replies).hasSize(37)
        // Every reply is a row of its own, and no two stretches are adjacent: one per gap.
        assertThat(rows.count { it is TranscriptRow.Item && it.item is AssistantMessage }).isEqualTo(37)
        rows.zipWithNext().forEach { (a, b) -> assertThat(a is TranscriptRow.Stretch && b is TranscriptRow.Stretch).isFalse() }
        assertThat(rows.first()).isEqualTo(TranscriptRow.Item(items.first()))
        // The lone thought before the first reply is drawn as a thought; the footer after the last reply as a footer.
        val opening = rows[1] as TranscriptRow.Stretch
        assertThat(opening.single).isInstanceOf(TranscriptRow.Entry.Thought::class.java)
        val closing = rows.last() as TranscriptRow.Stretch
        assertThat(closing.single).isInstanceOf(TranscriptRow.Entry.Footer::class.java)
        assertThat(StretchSummary.footerLabel((closing.single as TranscriptRow.Entry.Footer).footer)).isEqualTo("Worked 28m 27s")
        // The stretch after the first reply: two reads and the thought before the second reply.
        val second = rows[3] as TranscriptRow.Stretch
        assertThat(second.summary.text).isEqualTo("2 files · 1 thought")
        assertThat(second.entries.map { it::class.simpleName }).containsExactly("Call", "Call", "Thought").inOrder()
        // Every tool call of the turn is inside a stretch; nothing was dropped.
        val calls = rows.filterIsInstance<TranscriptRow.Stretch>().flatMap { it.entries }.count { it is TranscriptRow.Entry.Call }
        assertThat(calls).isEqualTo(126)
        assertThat(rows.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `the newest stretch of a running run reads Working, and a footer or a message after it ends that`() {
        val items = listOf(
            UserMessage("u1", "Fix the flaky test."),
            ActivityGroup("g1", listOf(ThinkingBlock("Reading the test first.", isStreaming = false), ToolCall("r1", "read_file", ToolKind.Read, "completed", "FlakyTest.kt"), ToolCall("r2", "run_terminal_cmd", ToolKind.Shell, "running", "gradle test"))),
        )
        val live = TranscriptRows.of(items, coordinatorMode = false, runActive = true)
        val stretch = live.last() as TranscriptRow.Stretch
        assertThat(stretch.live).isTrue()
        assertThat(stretch.summary.text).isEqualTo("Working · 1 file · 1 command · 1 thought")
        assertThat(stretch.summary.busy).isTrue()
        // The same items with the run over: no shimmer, the first count leads.
        val over = TranscriptRows.of(items, coordinatorMode = false, runActive = false).last() as TranscriptRow.Stretch
        assertThat(over.live).isFalse()
        assertThat(over.summary.text).isEqualTo("1 file · 1 command · 1 thought")
        // A footer closes the stretch: "Worked", whatever the run status says.
        val closed = TranscriptRows.of(items + RunFooter("f1", "run-1", RunStatus.FINISHED, 65_000, emptyList()), coordinatorMode = false, runActive = true).last() as TranscriptRow.Stretch
        assertThat(closed.live).isFalse()
        assertThat(closed.summary.text).isEqualTo("Worked 1m 5s · 1 file · 1 command · 1 thought")
        // A reply after the stretch: the stretch is not the one being written.
        val replied = TranscriptRows.of(items + AssistantMessage("a1", "Fixed.", isStreaming = true), coordinatorMode = false, runActive = true)
        assertThat((replied[1] as TranscriptRow.Stretch).live).isFalse()
    }

    @Test
    fun `pictures and a pending question follow their stretch as rows of their own, a failure is always counted`() {
        val image = ToolCall("i1", "generate_image", ToolKind.Image, "completed", "a board", payload = ToolPayload.GeneratedImage("board.png", "The board", src = "file:///tmp/board.png"))
        val question = ToolCall(
            "q1", "ask_question", ToolKind.Question, "running", "",
            payload = ToolPayload.Question("Which?", listOf(ToolPayload.Question.Item("1", "Prorate or bill next cycle?", listOf(ToolPayload.Question.Option("a", "Prorate"), ToolPayload.Question.Option("b", "Next cycle"))))),
        )
        val failed = ToolCall("e1", "edit_file", ToolKind.Edit, "completed", "notes.md", isError = true)
        val items = listOf(
            UserMessage("u1", "Draw the board and ask me."),
            ActivityGroup("g1", listOf(ToolCall("r1", "read_file", ToolKind.Read, "completed", "A.kt"), image, failed, question)),
        )
        val rows = TranscriptRows.of(items, coordinatorMode = false, runActive = true)
        assertThat(kinds(rows)).containsExactly("item:UserMessage", "stretch:Working · 1 file · 1 image · 1 failed", "media", "question:q1").inOrder()
        val stretch = rows[1] as TranscriptRow.Stretch
        // The calls themselves stay in the sequence; only the picture and the card stand outside it.
        assertThat(stretch.entries.map { (it as TranscriptRow.Entry.Call).call.callId }).containsExactly("r1", "i1", "e1", "q1").inOrder()
        assertThat((rows[2] as TranscriptRow.Media).group.media.map { it.callId }).containsExactly("i1")
        // Four kinds and a failure: the counts are capped, the failure never is.
        val many = ActivityGroup("g2", listOf(
            ToolCall("a", "edit_file", ToolKind.Edit, "completed", "A.kt"), ToolCall("b", "read_file", ToolKind.Read, "completed", "B.kt"),
            ToolCall("c", "grep", ToolKind.Grep, "completed", "foo"), ToolCall("d", "run_terminal_cmd", ToolKind.Shell, "completed", "ls"), failed,
        ))
        val capped = TranscriptRows.of(listOf(many), coordinatorMode = false).single() as TranscriptRow.Stretch
        assertThat(capped.summary.text).isEqualTo("1 edit · 1 file · 1 search · 1 command · 1 failed")
    }

    @Test
    fun `in an agent's chat a coordinator's note is a message, a run that failed says so in its footer`() {
        val items = listOf(
            UserMessage("u1", "Go."),
            ActivityGroup("g1", listOf(ToolCall("r1", "read_file", ToolKind.Read, "completed", "A.kt"))),
            AssistantMessage("a1", "Looked, nothing to do."),
            RunFooter("f1", "run-1", RunStatus.ERROR, 12_000, emptyList()),
            NoticeCard("n1", "Run failed", "Out of credits", NoticeTone.Error),
        )
        val agent = TranscriptRows.of(items, coordinatorMode = false)
        assertThat(kinds(agent)).containsExactly("item:UserMessage", "single:g1:r1", "item:AssistantMessage", "single:f1", "item:NoticeCard").inOrder()
        // The same items in a coordinator's chat: the reply is a note, folded with the read and the footer.
        val coordinator = TranscriptRows.of(items, coordinatorMode = true)
        assertThat(kinds(coordinator)).containsExactly("item:UserMessage", "stretch:Failed after 12s · 1 file · 1 note", "item:NoticeCard").inOrder()
    }
}
