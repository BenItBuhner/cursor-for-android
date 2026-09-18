package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * The incremental presenter against the presentation the screen ran whole until 0.3.31: the same rows for the same
 * items — on the real coordinator fixture, on the event wall, and on thousands of random transcripts in both modes —
 * and only the turns that changed rebuilt when a delta lands, a page inserts above, or a turn is minted anew for
 * the same facts. Every behaviour of #162–#177 the rows carry (the coordinator's message as a row, one stretch
 * between two messages, the newest stretch live, the newest small group open, a run the next message interrupted)
 * is held by the parity checks.
 */
class TranscriptPresenterTest {

    private fun legacy(items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean): List<TranscriptRow> {
        val mode = coordinatorMode || CoordinatorTranscript.hasCoordinatorContent(items)
        return TranscriptRows.of(GoalTranscript.lift(CoordinatorTranscript.present(items, mode)), mode, runActive)
    }

    private fun assertParity(items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean, presenter: TranscriptPresenter = TranscriptPresenter()): TranscriptPresenter.Presented {
        val presented = presenter.present(items, coordinatorMode, runActive)
        val expected = legacy(items, coordinatorMode, runActive)
        assertThat(presented.rows).isEqualTo(expected)
        assertThat(presented.rows.map { it.key }).isEqualTo(expected.map { it.key })
        // The summaries the rows carry, computed either way, say the same.
        presented.rows.zip(expected).forEach { (a, b) ->
            if (a is TranscriptRow.Stretch && b is TranscriptRow.Stretch) assertThat(a.summary.text).isEqualTo(b.summary.text)
            if (a is TranscriptRow.Events && b is TranscriptRow.Events) assertThat(a.summary.text).isEqualTo(b.summary.text)
        }
        assertThat(presented.items).isEqualTo(GoalTranscript.lift(CoordinatorTranscript.present(items, presented.coordinatorMode)))
        assertThat(presented.goal).isEqualTo(GoalTranscript.derive(items))
        return presented
    }

    private fun coordinatorItems(): List<TimelineItem> {
        val injected = SystemNotifications.parse("m-inject", CoordinatorFixtures.injectedTurn("subagent_completion_necessary_follow_up"), 1_789_340_700_000L)!!
        return injected.items + AssistantMessage("asst-inject", "Noted; the release worker is done.") + RunFooter("run-inject", "run-inject", RunStatus.FINISHED, 41_000, emptyList()) +
            CoordinatorFixtures.replay("coordinator_run.sse", "run-coord-001")
    }

    @Test
    fun `the coordinator fixture presents to the rows the whole presentation gives, live or not, in either mode`() {
        val items = coordinatorItems()
        for (mode in listOf(true, false)) for (active in listOf(true, false)) assertParity(items, mode, active)
        // The content alone reads the chat as a coordinator's, as the screen did.
        assertThat(TranscriptPresenter().present(items, coordinatorMode = false, runActive = false).coordinatorMode).isTrue()
    }

    @Test
    fun `the event wall presents to the same rows and its newest small group opens`() {
        val wall = CoordinatorFixtures.json("event_wall.json")
        val texts = CoordinatorFixtures.array(wall, "turns").map { it.toString().trim('"').replace("\\n", "\n").replace("\\\"", "\"") }
        val items = ArrayList<TimelineItem>()
        items += UserMessage("u0", "Start", 1_000L)
        items += ActivityGroup("g0", listOf(ToolCall("send0", "SendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("Kicking off."))))
        texts.take(20).forEachIndexed { i, text ->
            items += SystemNotifications.parse("n$i", text, 2_000L + i * 60_000L)?.items ?: listOf(UserMessage("n$i", text, 2_000L + i * 60_000L))
            items += AssistantMessage("a$i", "Logged.")
            items += RunFooter("f$i", "run$i", RunStatus.FINISHED, 12_000L, emptyList())
        }
        assertParity(items, coordinatorMode = true, runActive = false)
        assertParity(items, coordinatorMode = true, runActive = true)
        // Two more events: the newest group is small and opens on its own, whole or incremental.
        val more = items + SystemNotifications.parse("nx", texts[0], 9_000_000L)!!.items + RunFooter("fx", "runx", RunStatus.FINISHED, 1_000L, emptyList())
        val presenter = TranscriptPresenter()
        presenter.present(items, coordinatorMode = true, runActive = false)
        assertParity(more, coordinatorMode = true, runActive = false, presenter = presenter)
    }

    @Test
    fun `a live delta rebuilds the newest turn alone and keeps every other row's instance`() {
        val presenter = TranscriptPresenter()
        // Six finished turns, then the live one: its prompt and the story so far, still being written.
        val base = ordinaryTurns(from = 0, count = 6, random = Random(7)) + UserMessage("u-live", "Go on", 99_000L) +
            ActivityGroup("g-live", listOf(ToolCall("c-live", "read_file", ToolKind.Read, "running", "Live.kt")))
        val first = presenter.present(base, coordinatorMode = false, runActive = true)
        assertThat(first.segmentsBuilt).isEqualTo(7)
        // The live turn writes on: a reply starts under its steps.
        val grown = base + AssistantMessage("a-live", "So far", isStreaming = true)
        val second = assertParity(grown, coordinatorMode = false, runActive = true, presenter = presenter)
        assertThat(second.segmentsBuilt).isEqualTo(1)
        assertThat(second.segmentsReused).isEqualTo(6)
        // The rows before the newest turn's are the very instances of the first presentation.
        val firstRowsByKey = first.rows.associateBy { it.key }
        val untouchedKeys = legacy(ordinaryTurns(from = 0, count = 6, random = Random(7)), coordinatorMode = false, runActive = false).map { it.key }
        assertThat(untouchedKeys).isNotEmpty()
        untouchedKeys.forEach { key -> assertThat(second.rows.first { it.key == key }).isSameInstanceAs(firstRowsByKey.getValue(key)) }
    }

    @Test
    fun `a page inserted above keeps the shown rows' instances and order`() {
        val presenter = TranscriptPresenter()
        val newest = ordinaryTurns(from = 20, count = 10, random = Random(1))
        val first = presenter.present(newest, coordinatorMode = false, runActive = true)
        val widened = ordinaryTurns(from = 10, count = 10, random = Random(2)) + newest
        val second = assertParity(widened, coordinatorMode = false, runActive = true, presenter = presenter)
        assertThat(second.segmentsBuilt).isEqualTo(10)
        assertThat(second.segmentsReused).isEqualTo(10)
        val shownKeys = first.rows.map { it.key }
        assertThat(second.rows.map { it.key }.filter { it in shownKeys.toSet() }).containsExactlyElementsIn(shownKeys).inOrder()
        val firstByKey = first.rows.associateBy { it.key }
        second.rows.filter { it.key in firstByKey }.forEach { row -> assertThat(row).isSameInstanceAs(firstByKey.getValue(row.key)) }
    }

    @Test
    fun `turns minted anew for the same facts are reused, as the default path's builder mints them on every publication`() {
        val presenter = TranscriptPresenter()
        val items = ordinaryTurns(from = 0, count = 12, random = Random(3))
        presenter.present(items, coordinatorMode = false, runActive = false)
        // Every prompt and footer a new instance, equal to the last; the groups the same instances (the traces).
        val reminted = items.map { item ->
            when (item) {
                is UserMessage -> item.copy()
                is RunFooter -> item.copy()
                is AssistantMessage -> item.copy()
                else -> item
            }
        }
        val again = assertParity(reminted, coordinatorMode = false, runActive = false, presenter = presenter)
        assertThat(again.segmentsBuilt).isEqualTo(0)
        assertThat(again.segmentsReused).isEqualTo(12)
    }

    @Test
    fun `a run the next message cuts short reads as interrupted once that message lands, across the turn boundary`() {
        val presenter = TranscriptPresenter()
        val ended = 1_000_000L
        val items = listOf<TimelineItem>(
            UserMessage("u1", "Do it", ended - 60_000L),
            ActivityGroup("g1", listOf(ToolCall("c1", "read_file", ToolKind.Read, "completed", "A.kt"), ToolCall("c2", "read_file", ToolKind.Read, "completed", "B.kt"), ToolCall("c3", "read_file", ToolKind.Read, "completed", "C.kt"))),
            RunFooter("f1", "run1", RunStatus.CANCELLED, 30_000L, emptyList(), endedAtMillis = ended),
        )
        val stopped = assertParity(items, coordinatorMode = false, runActive = false, presenter = presenter)
        assertThat((stopped.rows.last() as TranscriptRow.Stretch).summary.text).startsWith("Cancelled after")
        val interrupted = items + UserMessage("u2", "Actually, do this instead", ended + 5_000L) + ActivityGroup("g2", listOf(ToolCall("c4", "read_file", ToolKind.Read, "completed", "D.kt")))
        val presented = assertParity(interrupted, coordinatorMode = false, runActive = true, presenter = presenter)
        val stretch = presented.rows.first { it is TranscriptRow.Stretch } as TranscriptRow.Stretch
        assertThat(stretch.summary.text).isEqualTo("Worked 30s · 3 files · interrupted")
        // The first turn was re-cut for the fact the second brought; nothing else of it changed.
        assertThat(presented.segmentsBuilt).isEqualTo(2)
    }

    @Test
    fun `the coordinator's tools arriving in a later turn re-read the whole chat in the coordinator's way`() {
        val presenter = TranscriptPresenter()
        val plain = ordinaryTurns(from = 0, count = 3, random = Random(4))
        val before = presenter.present(plain, coordinatorMode = false, runActive = false)
        assertThat(before.coordinatorMode).isFalse()
        val withUpdate = plain + UserMessage("u-c", "Report", 9_000L) + ActivityGroup("g-c", listOf(ToolCall("s1", "SendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("Here is where we are."))))
        val after = assertParity(withUpdate, coordinatorMode = false, runActive = false, presenter = presenter)
        assertThat(after.coordinatorMode).isTrue()
        assertThat(after.rows.count { it is TranscriptRow.Message }).isEqualTo(1)
        // Every earlier turn was cut again: an agent's reply is a note inside the stretch in a coordinator's chat.
        assertThat(after.segmentsBuilt).isEqualTo(4)
        // Emptied in place (Reload transcript) and filled again, the chat is read afresh — and reads the same.
        assertThat(presenter.present(emptyList(), coordinatorMode = false, runActive = false).rows).isEmpty()
        assertParity(plain, coordinatorMode = false, runActive = false, presenter = presenter).also { assertThat(it.coordinatorMode).isFalse() }
    }

    @Test
    fun `random transcripts present to the same rows as the whole presentation, incrementally, in both modes`() {
        val random = Random(20260917)
        repeat(60) { round ->
            val coordinatorMode = random.nextBoolean()
            val presenter = TranscriptPresenter()
            var items: List<TimelineItem> = emptyList()
            repeat(8) { step ->
                items = when (random.nextInt(4)) {
                    0 -> randomTurns(random, from = 1000 * round + 100 * step, count = 1 + random.nextInt(4), coordinator = coordinatorMode) + items
                    1 -> items + randomTurns(random, from = 1000 * round + 100 * step, count = 1 + random.nextInt(3), coordinator = coordinatorMode)
                    2 -> if (items.isEmpty()) randomTurns(random, from = 1000 * round + 100 * step, count = 2, coordinator = coordinatorMode) else items + randomTail(random, "t$round-$step")
                    else -> items.map { if (it is UserMessage && random.nextBoolean()) it.copy() else it }
                }
                assertParity(items, coordinatorMode, runActive = random.nextBoolean(), presenter = presenter)
            }
        }
    }

    // -- generators ----------------------------------------------------------------------------------------------------

    private fun ordinaryTurns(from: Int, count: Int, random: Random): List<TimelineItem> = (from until from + count).flatMap { t ->
        listOf(
            UserMessage("u$t", "Prompt $t", 1_000L * t),
            ActivityGroup("g$t", listOf(ThinkingBlock("thinking $t", 2), ToolCall("c$t-1", "read_file", ToolKind.Read, "completed", "File$t.kt"), ToolCall("c$t-2", "edit_file", ToolKind.Edit, "completed", "File$t.kt", linesAdded = random.nextInt(9), linesRemoved = 1))),
            AssistantMessage("a$t", "Reply $t with `code`"),
            RunFooter("f$t", "run$t", RunStatus.FINISHED, 12_000L + random.nextInt(50_000), emptyList()),
        )
    }

    private fun randomTurns(random: Random, from: Int, count: Int, coordinator: Boolean): List<TimelineItem> = (from until from + count).flatMap { t ->
        val at = 1_000_000L + 60_000L * t
        val out = ArrayList<TimelineItem>()
        when (random.nextInt(if (coordinator) 3 else 1)) {
            0 -> out += UserMessage("u$t", "Prompt $t", at)
            1 -> out += SystemNotifications.parse("n$t", CoordinatorFixtures.injectedTurn(if (random.nextBoolean()) "subagent_completion_already_visible" else "github_pull_request_synchronize"), at)!!.items
            else -> out += SystemNotifications.parse("n$t", CoordinatorFixtures.injectedTurn("subagent_completion_necessary_follow_up"), at)!!.items
        }
        val steps = ArrayList<ActivityStep>()
        repeat(random.nextInt(5)) { k ->
            steps += when (random.nextInt(if (coordinator) 6 else 4)) {
                0 -> ThinkingBlock("thought $t-$k", random.nextInt(9).toLong())
                1 -> ToolCall("c$t-$k", "read_file", ToolKind.Read, "completed", "File$k.kt")
                2 -> ToolCall("c$t-$k", "edit_file", ToolKind.Edit, "completed", "File$k.kt", linesAdded = 3, linesRemoved = 1)
                3 -> ToolCall("c$t-$k", "run_terminal_cmd", ToolKind.Shell, if (random.nextInt(8) == 0) "running" else "completed", "ls", isError = random.nextInt(6) == 0)
                4 -> ToolCall("c$t-$k", "SendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("Update $t-$k **bold**"))
                else -> ToolCall("c$t-$k", "createAgent", ToolKind.Coordinator, "completed", "Worker $k", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOf(WorkerStatus(agentId = "bc-w$t-$k", name = "Worker $k")), reported = true), linkedAgentIds = listOf("bc-w$t-$k"))
            }
        }
        if (steps.isNotEmpty()) out += ActivityGroup("g$t", steps)
        if (random.nextInt(3) > 0) out += AssistantMessage("a$t", if (random.nextBoolean()) "Logged." else "Reply $t\n\n- one\n- two", isStreaming = false)
        if (random.nextInt(4) > 0) out += RunFooter("f$t", "run$t", if (random.nextInt(5) == 0) RunStatus.CANCELLED else RunStatus.FINISHED, 12_000L, emptyList(), endedAtMillis = at + 12_000L)
        out
    }

    /** Something appended to the newest turn: a delta of its reply, a step, its footer. */
    private fun randomTail(random: Random, id: String): List<TimelineItem> = when (random.nextInt(3)) {
        0 -> listOf(AssistantMessage("a-$id", "word ", isStreaming = true))
        1 -> listOf(ActivityGroup("g-$id", listOf(ToolCall("c-$id", "grep", ToolKind.Grep, "running", "needle"))))
        else -> listOf(RunFooter("f-$id", "run-$id", RunStatus.FINISHED, 5_000L, emptyList()))
    }
}
