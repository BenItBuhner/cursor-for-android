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
            is TranscriptRow.Media -> "media"
            is TranscriptRow.Question -> "question:${row.call.callId}"
            is TranscriptRow.Stretch -> if (row.single != null) "single:${row.single!!.key}" else "stretch:${row.summary.text}"
            is TranscriptRow.Event -> "event:${row.line.text}" + if (row.count > 1) " ×${row.count}" else ""
            is TranscriptRow.Events -> "events:${row.summary.text}"
            is TranscriptRow.Failure -> "failure:${row.footer.id}" + (row.footer.reason?.let { ":$it" } ?: "")
        }
    }

    @Test
    fun `a coordinator's turn is its messages and one stretch between each pair of them, its workers' subagent rows inside`() {
        val rows = TranscriptRows.of(coordinatorItems(), coordinatorMode = true)
        assertThat(kinds(rows)).containsExactly(
            // The injected turn (its remark folded under its line), its run's footer, the coordinator's first note
            // and thought, and the follow-up it queued for the release worker: everything before its first update.
            "stretch:Worked 41s · 1 event · 1 agent · 1 thought",
            "message:c2",
            // Its edit and its second note, before the next updates.
            "stretch:1 edit · 1 note",
            "message:c4",
            "message:c5",
            "message:c6",
            // The failed send is a row of its own, then the older tool's message.
            "single:activity-run-coord-001-3:c7",
            "message:c8",
            // The worker it created, the status check and the run's footer close the turn.
            "stretch:Worked 1m 21s · 2 agents",
        ).inOrder()
        val closing = rows.last() as TranscriptRow.Stretch
        assertThat(closing.listed.map { it.key }).containsExactly("activity-run-coord-001-3:c9", "activity-run-coord-001-3:c10").inOrder()
        assertThat(closing.subagents.map { it.subagent?.source }).containsExactly(SubagentCall.Source.Created)
        assertThat(closing.summary.busy).isFalse()
        assertThat(closing.summary.lineStats).isNull()
        val edits = rows[2] as TranscriptRow.Stretch
        assertThat(edits.summary.lineStats).isEqualTo("+2 -1")
        assertThat(edits.entries.map { it::class.simpleName }).containsExactly("Call", "Note").inOrder()
        // The opening stretch: the event's line first, then the footer (carried by the summary, not listed), the note,
        // the thought, and the worker's row in its place after them.
        val opening = rows[0] as TranscriptRow.Stretch
        assertThat(opening.entries.map { it::class.simpleName }).containsExactly("Event", "Footer", "Note", "Thought", "Call").inOrder()
        assertThat(opening.listed.map { it::class.simpleName }).containsExactly("Event", "Note", "Thought", "Call").inOrder()
        assertThat(opening.subagents.map { it.subagent?.source }).containsExactly(SubagentCall.Source.Queued)
        assertThat((opening.entries.first() as TranscriptRow.Entry.Event).row.notification.narration).startsWith("Noted;")
        // Keys are stable and unique.
        assertThat(rows.map { it.key }).containsNoDuplicates()
        assertThat(rows.map { it.key }).isEqualTo(TranscriptRows.of(coordinatorItems(), coordinatorMode = true).map { it.key })
    }

    @Test
    fun `the message rows carry the coordinator's words, the subagent entries its calls to its workers`() {
        val rows = TranscriptRows.of(coordinatorItems(), coordinatorMode = true)
        val first = rows.filterIsInstance<TranscriptRow.Message>().first()
        assertThat((first.call.payload as ToolPayload.CoordinatorMessage).message).isEqualTo(update)
        val subagents = rows.filterIsInstance<TranscriptRow.Stretch>().flatMap { it.subagents }
        assertThat(subagents.map { it.subagent?.source }).containsExactly(SubagentCall.Source.Queued, SubagentCall.Source.Created).inOrder()
        assertThat(subagents.map { it.subagent?.title }).containsExactly("Land merge train and prep release", "Build Projects under Extended mode").inOrder()
        assertThat((subagents.last().call.payload as ToolPayload.WorkerAction).kind).isEqualTo(ToolPayload.WorkerAction.Kind.Created)
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

    /** An agent's turn that delegated twice among its reads: a local explore task, then a cloud task still at work. */
    private val delegating: List<TimelineItem> = listOf(
        UserMessage("u1", "Make the chart follow the hovered row."),
        ActivityGroup(
            "g1",
            listOf(
                ToolCall("r1", "read_file", ToolKind.Read, "completed", "Chart.tsx"),
                ToolCall("s1", "grep", ToolKind.Grep, "completed", "hoveredId"),
                ToolCall("e1", "task", ToolKind.Task, "completed", "Explore the evals page", payload = ToolPayload.Subagent("Explore the evals page", subagentType = "explore")),
                ToolCall("r2", "read_file", ToolKind.Read, "completed", "Legend.tsx"),
            ),
        ),
        ActivityGroup(
            "g2",
            listOf(
                ToolCall("t1", "task", ToolKind.Task, "running", "CursorBench chart hover highlight", payload = ToolPayload.Subagent("CursorBench chart hover highlight", agentId = "bc-cb1", isBackground = true)),
                ToolCall("x1", "run_terminal_cmd", ToolKind.Shell, "completed", "git status"),
            ),
        ),
        AssistantMessage("a1", "A cloud agent is on it."),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 38_000, emptyList()),
    )

    @Test
    fun `a subagent's row is a step of its stretch, in the order it ran, and splits nothing`() {
        val rows = TranscriptRows.of(delegating, coordinatorMode = false)
        assertThat(kinds(rows)).containsExactly("item:UserMessage", "stretch:2 files · 1 search · 1 command · 2 agents", "item:AssistantMessage", "single:f1").inOrder()
        val stretch = rows[1] as TranscriptRow.Stretch
        assertThat(stretch.entries.map { (it as TranscriptRow.Entry.Call).call.callId }).containsExactly("r1", "s1", "e1", "r2", "t1", "x1").inOrder()
        assertThat(stretch.listed.map { it.key }).containsExactly("g1:r1", "g1:s1", "g1:e1", "g1:r2", "g2:t1", "g2:x1").inOrder()
        assertThat(stretch.subagents.map { it.call.callId }).containsExactly("e1", "t1").inOrder()
        assertThat(stretch.subagents.map { it.subagent?.source }).containsExactly(SubagentCall.Source.Task, SubagentCall.Source.Task)
        // The index finds them where they sit.
        assertThat(SubagentRows.index(rows).isLatest(stretch.subagents.last().call, stretch.subagents.last().subagent!!)).isTrue()
        // A stretch of the subagent alone is drawn as its row.
        val alone = TranscriptRows.of(listOf(UserMessage("u1", "Go."), ActivityGroup("g", listOf(delegating.filterIsInstance<ActivityGroup>()[1].calls.first()))), coordinatorMode = false)
        assertThat(kinds(alone)).containsExactly("item:UserMessage", "single:g:t1").inOrder()
    }

    @Test
    fun `a stretch does not settle while a subagent of it works, as the desktop's work group reads`() {
        val stretch = TranscriptRows.of(delegating, coordinatorMode = false)[1] as TranscriptRow.Stretch
        val hover = SubagentLook(SubagentLook.Indicator.Running, "Wiring the hover state", active = true)
        val ci = SubagentLook(SubagentLook.Indicator.Running, "Waiting on CI for #113", active = true)
        // Nothing at work: the stretch's own line.
        assertThat(StretchSummary.of(stretch, emptyList())).isEqualTo(stretch.summary)
        assertThat(stretch.summary.busy).isFalse()
        // The run has stopped and one works: "1 working", every count after it, shimmering.
        val settled = StretchSummary.of(stretch, listOf(hover))
        assertThat(settled.text).isEqualTo("1 working · 2 files · 1 search · 1 command")
        assertThat(settled.busy).isTrue()
        // The run still writes: "Working", counted.
        assertThat(StretchSummary.of(stretch.copy(live = true), listOf(hover, ci)).text).isEqualTo("2 Working · 2 files · 1 search · 1 command")
        // A Project's chat: "Working" either way, then where the newest of them stands instead of the counts.
        val project = StretchSummary.of(stretch, listOf(hover, ci), coordinator = true)
        assertThat(project.text).isEqualTo("2 Working · Waiting on CI for #113")
        assertThat(project.busy).isTrue()
        // A footer in the stretch does not settle it either.
        val footed = TranscriptRows.of(delegating.filterNot { it is AssistantMessage }, coordinatorMode = false)[1] as TranscriptRow.Stretch
        assertThat(footed.summary.text).isEqualTo("Worked 38s · 2 files · 1 search · 1 command")
        assertThat(StretchSummary.of(footed, listOf(hover)).text).isEqualTo("1 working · 2 files · 1 search · 1 command")
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

    /**
     * A run the server says failed, with nothing after it: its failure is one compact row of its own after the
     * stretch — the reason and the time on it — never a banner, and the stretch's verb stays what the run did. The
     * banner a build before 0.3.41 wrote for it, still in the trace on disk, is not drawn; its reason goes to the footer.
     */
    @Test
    fun `in an agent's chat a coordinator's note is a message, and the newest run's failure is a row of its own with the server's reason`() {
        val items = listOf(
            UserMessage("u1", "Go."),
            ActivityGroup("g1", listOf(ToolCall("r1", "read_file", ToolKind.Read, "completed", "A.kt"))),
            AssistantMessage("a1", "Looked, nothing to do."),
            // As every build wrote them: the banner, then the footer.
            NoticeCard("n1", NoticeCard.RUN_FAILED, "Out of credits", NoticeTone.Error),
            RunFooter("f1", "run-1", RunStatus.ERROR, 12_000, emptyList(), endedAtMillis = 1_789_600_000_000L),
        )
        val agent = TranscriptRows.of(items, coordinatorMode = false)
        // The footer alone would have been a "Worked 12s" line over a "Run failed" line: the failure row stands for both.
        assertThat(kinds(agent)).containsExactly("item:UserMessage", "single:g1:r1", "item:AssistantMessage", "failure:f1:Out of credits").inOrder()
        assertThat(agent.filterIsInstance<TranscriptRow.Item>().none { it.item is NoticeCard }).isTrue()
        // The same items in a coordinator's chat: the reply is a note, folded with the read and the footer; the failure follows the stretch.
        val coordinator = TranscriptRows.of(items, coordinatorMode = true)
        assertThat(kinds(coordinator)).containsExactly("item:UserMessage", "stretch:Worked 12s · 1 file · 1 note", "failure:f1:Out of credits").inOrder()
        val failure = coordinator.last() as TranscriptRow.Failure
        assertThat(failure.footer.endedAtMillis).isEqualTo(1_789_600_000_000L)
        // A footer that carries its own reason keeps it over a banner's.
        val own = TranscriptRows.of(listOf(NoticeCard("n1", NoticeCard.RUN_FAILED, "Older words", NoticeTone.Error), RunFooter("f1", "run-1", RunStatus.ERROR, null, emptyList(), reason = "Out of credits")), coordinatorMode = true)
        assertThat(kinds(own)).containsExactly("failure:f1:Out of credits")
        // A banner filed after its footer, should a copy ever hold one so, is read too.
        val after = TranscriptRows.of(listOf(RunFooter("f1", "run-1", RunStatus.ERROR, null, emptyList()), NoticeCard("n1", NoticeCard.RUN_FAILED, "Out of credits", NoticeTone.Error)), coordinatorMode = true)
        assertThat(kinds(after)).containsExactly("failure:f1:Out of credits")
    }

    /**
     * Bennett's v0.3.39 coordinator: its previous turn was cut off by an infrastructure error mid-tool-call ("Tool
     * result not found") and the server recorded that run as failed; the conversation went on. The failure is then
     * a line inside its stretch — listed when the stretch opens, the summary ending "· failed" — not a banner and
     * not a row; the chat's newest run is the one that says how the chat stands. While the run is the newest and the
     * chat idle, the same failure is the row of its own; the moment the chat runs again, or a newer turn lands, it demotes.
     */
    @Test
    fun `a failure the conversation moved past is a line inside its stretch, and the newest run's is a row until the chat moves on`() {
        val t = 1_789_600_000_000L
        val failed = listOf(
            UserMessage("u1", "Resume the six paused workers.", t),
            AssistantMessage("a1", "Resuming them where they stopped."),
            ActivityGroup("g1", listOf(ToolCall("s1", "sendToAgent", ToolKind.Coordinator, ToolCall.STATUS_INTERRUPTED, "bc-w1"))),
            RunFooter("f1", "run-1", RunStatus.ERROR, 41_000, emptyList(), endedAtMillis = t + 41_000, reason = "Tool result not found"),
        )
        // Newest, the chat idle: the row of its own; the stretch is the work, the interrupted send's worker row in it.
        val current = TranscriptRows.of(failed, coordinatorMode = true)
        assertThat(kinds(current)).containsExactly("item:UserMessage", "stretch:Worked 41s · 1 agent · 1 note", "failure:f1:Tool result not found").inOrder()
        assertThat((current[1] as TranscriptRow.Stretch).entries.none { it is TranscriptRow.Entry.Failure }).isTrue()
        assertThat((current[1] as TranscriptRow.Stretch).subagents.single().subagent?.source).isEqualTo(SubagentCall.Source.Messaged)
        // The chat running again (the next turn under way, nothing of it on screen yet): no longer the chat's state.
        // The failure demotes to a line inside the stretch, which says so at the end of its summary.
        val running = TranscriptRows.of(failed, coordinatorMode = true, runActive = true)
        assertThat(kinds(running)).containsExactly("item:UserMessage", "stretch:Worked 41s · 1 agent · 1 note · failed").inOrder()
        val inside = (running[1] as TranscriptRow.Stretch)
        assertThat(inside.failures.single().footer.reason).isEqualTo("Tool result not found")
        assertThat(inside.live).isFalse()
        // A newer turn after it: the same, and the newer turn's stretch is the live one.
        val movedOn = failed + UserMessage("u2", "Keep going.", t + 90_000) + ActivityGroup("g2", listOf(ToolCall("s2", "getAgentStatus", ToolKind.Coordinator, "running", "6 agents")))
        val rows = TranscriptRows.of(movedOn, coordinatorMode = true, runActive = true)
        assertThat(kinds(rows)).containsExactly("item:UserMessage", "stretch:Worked 41s · 1 agent · 1 note · failed", "item:UserMessage", "single:g2:s2").inOrder()
        assertThat(rows.none { it is TranscriptRow.Failure }).isTrue()
        // A failed run with nothing else in its turn, moved past: its line alone stands for the turn.
        val bare = TranscriptRows.of(listOf(UserMessage("u1", "Go.", t), RunFooter("f1", "run-1", RunStatus.ERROR, 3_000, emptyList()), UserMessage("u2", "Again.", t + 5_000)), coordinatorMode = true)
        assertThat(kinds(bare)).containsExactly("item:UserMessage", "single:failure:f1", "item:UserMessage").inOrder()
        assertThat(rows.map { it.key }).containsNoDuplicates()
        assertThat(current.map { it.key }).containsNoDuplicates()
    }

    /**
     * Bennett's v0.3.25 frame (internal/reference/coordinator-0.3.25-no-replies-cancelled.jpg): a run cancelled
     * because he wrote again is not an error. The stretch says "interrupted" at the end of its line, the footer's
     * verb stays "Worked", and the warning row earlier builds drew for it is not drawn — not from a fresh replay,
     * and not from the notice a build before this one left in the trace on disk.
     */
    @Test
    fun `a run the user's next message cut short reads as interrupted, quietly, and never as a warning`() {
        val t = 1_789_600_000_000L
        val items = listOf(
            UserMessage("u1", "So where we at rn", t),
            AssistantMessage("a1", "Checking the workers."),
            ActivityGroup("g1", listOf(ToolCall("s1", "getAgentStatus", ToolKind.Coordinator, "interrupted", "2 agents"))),
            RunFooter("f1", "run-1", RunStatus.CANCELLED, 694_000, emptyList(), endedAtMillis = t + 700_000),
            // The next message came seconds after the cancel: it is what cancelled the run.
            UserMessage("u2", "I'm in it for all products and renders, by the way.", t + 702_000),
            // A trace a build before this one wrote: the notice ahead of the footer, in the order finish() added them.
            NoticeCard("n-legacy", NoticeCard.RUN_CANCELLED, null, NoticeTone.Warning),
            RunFooter("f2", "run-2", RunStatus.CANCELLED, 167_000, emptyList(), endedAtMillis = t + 900_000),
            // And the other way about, should one ever land after the footer: it stands between nothing.
            NoticeCard("n-legacy-2", NoticeCard.RUN_CANCELLED, null, NoticeTone.Warning),
            UserMessage("u3", "Also make the Fold8 shots.", t + 901_000),
            RunFooter("f3", "run-3", RunStatus.FINISHED, 440_000, emptyList()),
        )
        val rows = TranscriptRows.of(items, coordinatorMode = true)
        assertThat(kinds(rows)).containsExactly(
            "item:UserMessage",
            "stretch:Worked 11m 34s · 1 note · 1 step · interrupted",
            "item:UserMessage",
            // The notice a previous build wrote is gone; the footer alone is the stretch, and reads as interrupted.
            "single:f2",
            "item:UserMessage",
            "single:f3",
        ).inOrder()
        val lone = (rows[3] as TranscriptRow.Stretch).single as TranscriptRow.Entry.Footer
        assertThat(lone.interrupted).isTrue()
        assertThat(StretchSummary.footerLabel(lone.footer, interrupted = true)).isEqualTo("Worked 2m 47s")
        assertThat(StretchSummary.footerLabel(lone.footer)).isEqualTo("Cancelled after 2m 47s")
        assertThat(TranscriptRows.interruptedFooters(items)).containsExactly("f1", "f2")
    }

    @Test
    fun `a cancel the user came back from later, or one Cursor's own turn followed, is a cancel and not an interruption`() {
        val t = 1_789_600_000_000L
        val stopped = RunFooter("f1", "run-1", RunStatus.CANCELLED, 30_000, emptyList(), endedAtMillis = t)
        // Written to again an hour later: the run was stopped, not interrupted.
        val later = listOf(UserMessage("u1", "Go.", t - 30_000), stopped, UserMessage("u2", "Now do this instead.", t + 3_600_000))
        assertThat(TranscriptRows.interruptedFooters(later)).isEmpty()
        assertThat(kinds(TranscriptRows.of(later, coordinatorMode = true))[1]).isEqualTo("single:f1")
        val row = (TranscriptRows.of(later, coordinatorMode = true)[1] as TranscriptRow.Stretch).single as TranscriptRow.Entry.Footer
        assertThat(row.interrupted).isFalse()
        // Followed by a turn Cursor injected, not the user's: nothing says the user cut it short.
        val injected = SystemNotifications.parse("m-inject", CoordinatorFixtures.injectedTurn("subagent_completion_necessary_follow_up"), t + 5_000)!!
        assertThat(TranscriptRows.interruptedFooters(listOf(UserMessage("u1", "Go.", t - 30_000), stopped) + injected.items)).isEmpty()
        // Without times on either side the next message is taken as the cause: the two records are written together.
        val untimed = listOf(UserMessage("u1", "Go."), RunFooter("f1", "run-1", RunStatus.CANCELLED, 30_000, emptyList()), UserMessage("u2", "And this."))
        assertThat(TranscriptRows.interruptedFooters(untimed)).containsExactly("f1")
        // A run that ended any other way is never one.
        assertThat(TranscriptRows.interruptedFooters(listOf(RunFooter("f1", "run-1", RunStatus.ERROR, 30_000, emptyList()), UserMessage("u2", "And this.")))).isEmpty()
    }
}
