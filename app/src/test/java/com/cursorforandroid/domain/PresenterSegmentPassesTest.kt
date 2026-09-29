package com.cursorforandroid.domain

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.fixtures.GoalFixtures
import com.cursorforandroid.fixtures.SevenRunCoordinator
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.Buffer
import org.junit.Test
import kotlin.random.Random

/**
 * The chat's mode by its content, its goal, its subagent index and its newest group of events, read per segment by
 * [TranscriptPresenter] — each segment's part kept with it, only the segments cut afresh read — against the
 * presenter that read them over the whole transcript on every presentation ([FullScanTranscriptPresenter], kept
 * verbatim): the same answer on every presentation of the goal, coordinator and event-wall fixtures streamed in item
 * by item, and of random transcripts full of goal calls, continuations, subagents, workers, their notices, failures
 * and coordinator calls in a plain chat's mode, grown, streamed, re-read, re-minted, paged, cut short and emptied.
 */
class PresenterSegmentPassesTest {

    /** The incremental presenter's answer is the full-scan presenter's, fed the same history, and the whole reading's. */
    private fun assertParity(presenter: TranscriptPresenter, full: FullScanTranscriptPresenter, items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean, label: String) {
        val expected = runCatching { full.present(items, coordinatorMode, runActive) }
        val actual = runCatching { presenter.present(items, coordinatorMode, runActive) }
        assertWithMessage("$label throws as before").that(actual.exceptionOrNull()?.javaClass).isEqualTo(expected.exceptionOrNull()?.javaClass)
        if (expected.isFailure) return
        val before = expected.getOrThrow()
        val presented = actual.getOrThrow()
        assertWithMessage("$label mode").that(presented.coordinatorMode).isEqualTo(before.coordinatorMode)
        assertWithMessage("$label items").that(presented.items).isEqualTo(before.items)
        assertWithMessage("$label rows").that(presented.rows).isEqualTo(before.rows)
        assertWithMessage("$label built").that(presented.segmentsBuilt).isEqualTo(before.segmentsBuilt)
        assertWithMessage("$label reused").that(presented.segmentsReused).isEqualTo(before.segmentsReused)
        assertWithMessage("$label goal").that(presented.goal).isEqualTo(before.goal)
        assertWithMessage("$label goal, whole").that(presented.goal).isEqualTo(GoalTranscript.derive(items))
        assertWithMessage("$label subagents").that(presented.subagents).isEqualTo(before.subagents)
        assertWithMessage("$label subagents, whole").that(presented.subagents).isEqualTo(SubagentRows.index(presented.rows))
        assertWithMessage("$label newest group").that(TranscriptRows.newestGroupToOpen(presented.rows)).isEqualTo(TranscriptRows.newestGroupToOpen(before.rows))
    }

    /** [items] presented as they stream in: every prefix, the replies among them written out word by word. */
    private fun assertStreamed(items: List<TimelineItem>, coordinatorMode: Boolean, label: String) {
        val presenter = TranscriptPresenter()
        val full = FullScanTranscriptPresenter()
        val shown = ArrayList<TimelineItem>()
        items.forEachIndexed { index, item ->
            if (item is AssistantMessage && item.markdown.length > 8) {
                val words = item.markdown.split(' ')
                for (w in 1 until words.size step maxOf(1, words.size / 4)) {
                    val partial = item.copy(markdown = words.take(w).joinToString(" "), isStreaming = true)
                    assertParity(presenter, full, shown + partial, coordinatorMode, runActive = true, label = "$label @$index/$w")
                }
            }
            shown += item
            assertParity(presenter, full, shown.toList(), coordinatorMode, runActive = index < items.lastIndex, label = "$label @$index")
        }
    }

    private fun goalRun(name: String, runId: String): List<TimelineItem> {
        val source = Buffer().writeUtf8(GoalFixtures.text(name))
        val live = TimelineBuilder.LiveRun(runId, timed = false)
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            val event = (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.event ?: continue
            live.apply(event)
        }
        live.apply(RunStreamEvent.Result(runId, RunStatus.FINISHED, null, 30_000L, null))
        return live.snapshot()
    }

    // -- the fixtures --------------------------------------------------------------------------------------------------

    @Test
    fun `the goal runs read the same per segment`() {
        val at = 1_789_340_000_000L
        val continued = SystemNotification("goal-cont", SystemNotification.Kind.Goal, SystemNotifications.GOAL_CONTINUED, body = "Ship the release", raw = "<goal/>", timestampMillis = at + 120_000L)
        val items = listOf(UserMessage("u0", "/goal Ship the release", at)) + goalRun("goal_run.sse", "run-goal") +
            UserMessage("u1", "Keep going", at + 60_000L) + goalRun("goal_refused_run.sse", "run-refused") +
            continued + goalRun("goal_completed_run.sse", "run-done") +
            UserMessage("u2", "Next", at + 180_000L) + AssistantMessage("a2", "Nothing more to do on the goal.")
        assertThat(GoalTranscript.derive(items)).isNull()
        assertThat(GoalTranscript.derive(items.dropLast(2))).isNotNull()
        for (mode in listOf(false, true)) assertStreamed(items, mode, "goal runs mode=$mode")
    }

    @Test
    fun `the seven-run frame, the coordinator run and the event wall read the same per segment`() {
        val firstAt = 1_789_340_000_000L
        for (leak in listOf(true, false)) {
            val items = SevenRunCoordinator.transcript(SevenRunCoordinator.runs(firstAt, leak), true, firstAt)
            for (mode in listOf(true, false)) assertStreamed(items, mode, "seven-run leak=$leak mode=$mode")
        }
        val injected = SystemNotifications.parse("m-inject", CoordinatorFixtures.injectedTurn("subagent_completion_necessary_follow_up"), 1_789_340_700_000L)!!
        val run = injected.items + AssistantMessage("asst-inject", "Noted; the release worker is done.") + RunFooter("run-inject", "run-inject", RunStatus.FINISHED, 41_000, emptyList()) +
            UserMessage("u-again", "Again", 1_789_340_900_000L) + CoordinatorFixtures.replay("coordinator_run.sse", "run-coord-001")
        for (mode in listOf(true, false)) assertStreamed(run, mode, "coordinator run mode=$mode")
        val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject }
        val wallItems = wall.flatMapIndexed { index, turn ->
            listOf(UserMessage("wu$index", "Check $index", turn.getValue("timestampMillis").jsonPrimitive.long - 1)) +
                SystemNotifications.parse("inject-$index", turn.getValue("text").jsonPrimitive.content, turn.getValue("timestampMillis").jsonPrimitive.long)!!.items +
                listOf(AssistantMessage("a$index", "Logged."), RunFooter("f$index", "run$index", RunStatus.FINISHED, 12_000L, emptyList()))
        }
        for (mode in listOf(true, false)) assertStreamed(wallItems, mode, "event wall mode=$mode")
    }

    // -- random transcripts ------------------------------------------------------------------------------------------------

    @Test
    fun `random transcripts present the same mode, goal, subagents and rows as they grow, stream, re-read and page`() {
        val random = Random(20260929)
        repeat(300) { round ->
            val generator = Generator(random, "s$round")
            var coordinatorMode = random.nextInt(3) == 0
            val presenter = TranscriptPresenter()
            val full = FullScanTranscriptPresenter()
            var items: List<TimelineItem> = generator.turns(1 + random.nextInt(6))
            repeat(24) { step ->
                items = when (random.nextInt(12)) {
                    0, 1 -> items + generator.turns(1 + random.nextInt(2))
                    2, 3, 4 -> generator.stream(items)
                    5 -> items + generator.tailStep()
                    6 -> items + generator.footer()
                    7 -> generator.older(1 + random.nextInt(3)) + items
                    8 -> items.map { if (random.nextInt(3) == 0) remint(it) else it }
                    9 -> generator.reread(items)
                    10 -> items
                    else -> if (random.nextInt(6) == 0) emptyList() else items.take(maxOf(0, items.size - 1 - random.nextInt(4)))
                }
                // The caller's word moves now and then: the list or the record says coordinator, or stops saying it.
                if (random.nextInt(8) == 0) coordinatorMode = !coordinatorMode
                assertParity(presenter, full, items, coordinatorMode, runActive = random.nextBoolean(), label = "round $round step $step")
            }
        }
    }

    // -- what a streaming publication reads -----------------------------------------------------------------------------

    /** Whether [group]'s calls have been read since it was made (they are read once, on the first ask). */
    private fun callsRead(group: ActivityGroup): Boolean =
        (ActivityGroup::class.java.getDeclaredField("calls\$delegate").apply { isAccessible = true }.get(group) as Lazy<*>).isInitialized()

    @Test
    fun `a streaming publication reads the calls of the turn it changes and of no other`() {
        val read = listOf(false, true).associateWith { coordinatorMode ->
            val turns = (0 until 200).map { t ->
                listOf(
                    UserMessage("u$t", "Prompt $t", 1_000_000L + t * 60_000L),
                    ActivityGroup("g$t", listOf(ThinkingBlock("thinking $t", 2), ToolCall("c$t", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "File$t.kt"))),
                    AssistantMessage("a$t", "Reply $t"),
                    RunFooter("f$t", "run$t", RunStatus.FINISHED, 12_000L, emptyList()),
                )
            }
            val presenter = TranscriptPresenter()
            presenter.present(turns.flatten().dropLast(2) + AssistantMessage("live", "So", isStreaming = true), coordinatorMode, runActive = true)
            // The repository mints every turn anew for the same facts: the turns are the ones presented, by value.
            val reminted = turns.map { turn -> turn.map { if (it is ActivityGroup) it.copy() else it } }
            val live = reminted.flatten().dropLast(2) + AssistantMessage("live", "So far", isStreaming = true)
            val presented = presenter.present(live, coordinatorMode, runActive = true)
            assertThat(presented.segmentsBuilt).isEqualTo(1)
            assertThat(presented.goal).isNull()
            assertThat(presented.coordinatorMode).isEqualTo(coordinatorMode)
            reminted.dropLast(1).map { turn -> turn.filterIsInstance<ActivityGroup>().single() }.count(::callsRead)
        }
        assertWithMessage("groups of the 199 unchanged turns whose calls were read, by the caller's coordinator word").that(read).isEqualTo(mapOf(false to 0, true to 0))
    }

    private fun remint(item: TimelineItem): TimelineItem = when (item) {
        is UserMessage -> item.copy()
        is RunFooter -> item.copy()
        is ActivityGroup -> item.copy()
        is SystemNotification -> item.copy()
        else -> item
    }

    /**
     * Turns that say things about the goal and the subagents: goals set (by payload or, as an earlier build kept them,
     * by name), paused, resumed, completed, cleared and refused; continuations; tasks, workers created, messaged and
     * stopped, under ids that come round; their notices, alone and in runs that fold into groups; coordinator calls
     * that make a plain chat a coordinator's by its content; failed, cancelled and missing footers.
     */
    private class Generator(private val random: Random, private val tag: String) {
        private var turn = 0
        private var older = 0
        private var clock = 1_800_000_000_000L
        private val titles = listOf("Look into the flake", "Fix the build", "Write the notes")

        fun turns(count: Int): List<TimelineItem> = (0 until count).flatMap { turn(turn++) }

        fun older(count: Int): List<TimelineItem> = (0 until count).flatMap { turn(-1_000 - older++) }

        private fun turn(t: Int): List<TimelineItem> {
            val out = ArrayList<TimelineItem>()
            clock += 60_000L
            when (random.nextInt(5)) {
                0, 1 -> out += UserMessage("$tag-u$t", "Prompt $t", clock.takeIf { random.nextInt(8) > 0 })
                2 -> repeat(1 + random.nextInt(3)) { k -> out += notice(t, k) }
                3 -> out += UserMessage("$tag-u$t", "/goal Objective $t", clock)
                else -> Unit
            }
            repeat(random.nextInt(3)) { k -> out += group(t, k) }
            if (random.nextInt(3) > 0) out += AssistantMessage("$tag-a$t", listOf("Logged.", "Reply $t, with more words than one", "").random(random), isStreaming = random.nextInt(6) == 0)
            if (random.nextInt(4) > 0) out += footer()
            return out
        }

        private fun notice(t: Int, k: Int): SystemNotification = when (random.nextInt(5)) {
            0 -> SystemNotification("$tag-n$t-$k", SystemNotification.Kind.Goal, SystemNotifications.GOAL_CONTINUED, body = if (random.nextInt(4) > 0) "Objective ${random.nextInt(3)}" else null, raw = "<goal/>", timestampMillis = clock.takeIf { random.nextBoolean() })
            1 -> SystemNotification("$tag-n$t-$k", SystemNotification.Kind.Subagent, listOf("Subagent completed", "Subagent failed", "Subagent cancelled").random(random), summary = titles.random(random), raw = "<n/>", timestampMillis = clock, agentId = "sub${random.nextInt(3)}".takeIf { random.nextBoolean() }, callId = "$tag-c${random.nextInt(maxOf(1, turn))}-0-0".takeIf { random.nextBoolean() })
            2 -> SystemNotification("$tag-n$t-$k", SystemNotification.Kind.Worker, listOf("Agent completed", "Agent update", "Agent failed").random(random), summary = "Worker ${random.nextInt(2)} reported", raw = "<n/>", timestampMillis = clock, agentId = "bc-w${random.nextInt(3)}")
            3 -> SystemNotification("$tag-n$t-$k", SystemNotification.Kind.Other, "Pull request synchronized", summary = "#12", raw = "<n/>", timestampMillis = clock)
            else -> SystemNotification("$tag-n$t-$k", SystemNotification.Kind.Task, "Task finished", raw = "<n/>", timestampMillis = null)
        }

        private fun group(t: Int, k: Int): ActivityGroup {
            val steps = ArrayList<ActivityStep>()
            repeat(1 + random.nextInt(4)) { s -> steps += step(t, k, s) }
            return ActivityGroup("$tag-g$t-$k", steps)
        }

        private fun step(t: Int, k: Int, s: Int): ActivityStep {
            val id = "$tag-c$t-$k-$s"
            val status = if (random.nextInt(8) == 0) ToolCall.STATUS_RUNNING else ToolCall.STATUS_COMPLETED
            return when (random.nextInt(12)) {
                0 -> ToolCall(id, "CreateGoal", ToolKind.Other, status, "", isError = random.nextInt(6) == 0, payload = ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, objective = "Objective ${random.nextInt(3)}".takeIf { random.nextInt(5) > 0 }))
                1 -> ToolCall(id, "UpdateGoal", ToolKind.Other, status, "", payload = ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Update, status = GoalStatus.entries.random(random), error = "Refused".takeIf { random.nextInt(6) == 0 }))
                2 -> ToolCall(id, "create_goal", ToolKind.Other, status, "", detail = "Objective ${random.nextInt(3)}".takeIf { random.nextBoolean() })
                3 -> ToolCall(id, "task", ToolKind.Task, status, titles.random(random), payload = ToolPayload.Subagent(titles.random(random).takeIf { random.nextInt(4) > 0 }, agentId = "sub${random.nextInt(3)}".takeIf { random.nextBoolean() }))
                4 -> {
                    val worker = "bc-w${random.nextInt(3)}"
                    ToolCall(id, "create_agent", ToolKind.Coordinator, status, "Worker", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOf(WorkerStatus(worker, "Worker $worker")), text = "Do the thing.", title = titles.random(random).takeIf { random.nextBoolean() }, model = "gpt-5".takeIf { random.nextBoolean() }))
                }
                5 -> ToolCall(id, "send_to_agent", ToolKind.Coordinator, status, "bc-w1", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus("bc-w${random.nextInt(3)}")), text = "Rebase.", title = "Rebase".takeIf { random.nextBoolean() }))
                6 -> ToolCall(id, "stop_agent", ToolKind.Coordinator, status, "bc-w${random.nextInt(3)}", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Stopped, listOf(WorkerStatus("bc-w${random.nextInt(3)}"))))
                7 -> ToolCall(id, "SendMessage", ToolKind.Coordinator, status, "", payload = ToolPayload.CoordinatorMessage("Done: turn $t."))
                8 -> ThinkingBlock("thought $t-$k-$s", 1)
                else -> ToolCall(id, "read_file", ToolKind.Read, status, "File$s.kt")
            }
        }

        fun footer(): List<TimelineItem> {
            val n = random.nextInt(1_000_000)
            val status = listOf(RunStatus.FINISHED, RunStatus.FINISHED, RunStatus.ERROR, RunStatus.CANCELLED).random(random)
            return listOf(RunFooter("$tag-f$n", "$tag-run$n", status, 12_000L, emptyList(), endedAtMillis = if (random.nextBoolean()) clock + 5_000L else null, reason = "Out of credits".takeIf { status == RunStatus.ERROR && random.nextBoolean() }))
        }

        fun tailStep(): List<TimelineItem> = when (random.nextInt(3)) {
            0 -> listOf(group(turn, random.nextInt(9)))
            1 -> listOf(notice(turn, random.nextInt(9)))
            else -> listOf(AssistantMessage("$tag-t${random.nextInt(1000)}", "More"))
        }

        fun stream(items: List<TimelineItem>): List<TimelineItem> {
            val last = items.lastOrNull()
            if (last is AssistantMessage) return items.dropLast(1) + last.copy(markdown = last.markdown + " word", isStreaming = true)
            return items + AssistantMessage("$tag-s${random.nextInt(1000)}", "So far", isStreaming = true)
        }

        /** A turn in the middle read again: a group with a step more, a notice or a reply said otherwise, a footer's status turned. */
        fun reread(items: List<TimelineItem>): List<TimelineItem> {
            if (items.isEmpty()) return items
            val at = random.nextInt(items.size)
            val out = items.toMutableList()
            out[at] = when (val item = items[at]) {
                is ActivityGroup -> item.copy(steps = item.steps + step(turn, 0, 9))
                is AssistantMessage -> item.copy(markdown = item.markdown + " (edited)")
                is SystemNotification -> item.copy(title = if (item.title.endsWith("failed")) "Subagent completed" else "Subagent failed")
                is RunFooter -> item.copy(status = if (item.status == RunStatus.ERROR) RunStatus.FINISHED else RunStatus.ERROR)
                else -> return items
            }
            return out
        }
    }
}
