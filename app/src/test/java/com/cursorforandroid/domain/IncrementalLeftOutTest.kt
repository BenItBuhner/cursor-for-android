package com.cursorforandroid.domain

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.fixtures.SevenRunCoordinator
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.random.Random

/**
 * What the transcript leaves out as said again ([CoordinatorTranscript.leftOut]), read incrementally — a scan resumed
 * after a footer ([CoordinatorTranscript.LeftOutScan]) and the presenter that keeps the scan of the settled turns
 * ([TranscriptPresenter]) — against the whole-transcript scan and the presenter as they read before the scan was made
 * resumable ([LegacyLeftOut], [FullScanTranscriptPresenter], kept verbatim): the same keys at every checkpoint, and the
 * same rows, items and reuse on every presentation of Bennett's exports (the seven-run frame with and without its
 * leaked message, the 2026-09-20 whole-turn replay, the coordinator run and the event wall) streamed in item by item,
 * and of random transcripts full of replays, copies, colliding ids, missing footers and interrupted runs, grown,
 * streamed, re-read and paged.
 */
class IncrementalLeftOutTest {

    /** Every key a call or an item of [items] could be left out under. */
    private fun candidateKeys(items: List<TimelineItem>): Set<String> = items.flatMapTo(HashSet()) { item ->
        listOf(CoordinatorTranscript.itemKey(item)) + ((item as? ActivityGroup)?.calls?.map { CoordinatorTranscript.messageKey(item, it) } ?: emptyList())
    }

    /** The whole scan says what it said, value and order included, and a scan resumed at any footer leaves out the same keys. */
    private fun assertScanParity(items: List<TimelineItem>, label: String) {
        val legacy = LegacyLeftOut.leftOut(items)
        assertWithMessage("$label repeatedMessages").that(CoordinatorTranscript.repeatedMessages(items).toList()).containsExactlyElementsIn(LegacyLeftOut.repeatedMessages(items).toList()).inOrder()
        assertWithMessage("$label replayedActivity").that(CoordinatorTranscript.replayedActivity(items).toList()).containsExactlyElementsIn(LegacyLeftOut.replayedActivity(items).toList()).inOrder()
        assertWithMessage("$label leftOut").that(CoordinatorTranscript.leftOut(items).toList()).containsExactlyElementsIn(legacy.toList()).inOrder()
        val checkpoints = listOf(0) + items.indices.filter { items[it] is RunFooter }.map { it + 1 }
        val keys = candidateKeys(items) + legacy
        for (c in checkpoints) {
            val base = CoordinatorTranscript.LeftOutScan().apply { scan(items, 0, c) }
            val rest = CoordinatorTranscript.LeftOutScan(base).apply { scan(items, c, items.size) }
            assertWithMessage("$label resumed at $c").that(base.keys() + rest.keys()).isEqualTo(legacy)
            assertWithMessage("$label resumed at $c: empty").that(rest.isEmpty).isEqualTo(legacy.isEmpty())
            for (key in keys) assertWithMessage("$label resumed at $c: $key").that(rest.leavesOut(key)).isEqualTo(key in legacy)
        }
        // A base read in two pieces reads as one.
        if (checkpoints.size >= 3) {
            val (a, b) = checkpoints[checkpoints.size / 3] to checkpoints[2 * checkpoints.size / 3]
            val base = CoordinatorTranscript.LeftOutScan().apply { scan(items, 0, a); scan(items, a, b) }
            val rest = CoordinatorTranscript.LeftOutScan(base).apply { scan(items, b, items.size) }
            assertWithMessage("$label resumed at $a then $b").that(base.keys() + rest.keys()).isEqualTo(legacy)
        }
    }

    /**
     * The incremental presenter's answer is the full-scan presenter's, fed the same history: the same rows and items,
     * the same turns rebuilt and reused; and, where [whole], the whole presentation's with the legacy keys.
     */
    private fun assertPresenterParity(presenter: TranscriptPresenter, full: FullScanTranscriptPresenter, items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean, label: String, whole: Boolean) {
        val before = full.present(items, coordinatorMode, runActive)
        val legacy = LegacyLeftOut.leftOut(items)
        val presented = presenter.present(items, coordinatorMode, runActive)
        assertWithMessage("$label mode").that(presented.coordinatorMode).isEqualTo(before.coordinatorMode)
        assertWithMessage("$label items").that(presented.items).isEqualTo(before.items)
        assertWithMessage("$label rows").that(presented.rows).isEqualTo(before.rows)
        assertWithMessage("$label keys").that(presented.rows.map { it.key }).isEqualTo(before.rows.map { it.key })
        assertWithMessage("$label built").that(presented.segmentsBuilt).isEqualTo(before.segmentsBuilt)
        assertWithMessage("$label reused").that(presented.segmentsReused).isEqualTo(before.segmentsReused)
        assertWithMessage("$label goal").that(presented.goal).isEqualTo(before.goal)
        if (!whole) return
        val mode = presented.coordinatorMode
        val expectedItems = GoalTranscript.lift(CoordinatorTranscript.present(items, mode, legacy))
        assertWithMessage("$label items, whole").that(presented.items).isEqualTo(expectedItems)
        assertWithMessage("$label rows, whole").that(presented.rows).isEqualTo(TranscriptRows.of(expectedItems, mode, runActive))
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
                    assertPresenterParity(presenter, full, shown + partial, coordinatorMode, runActive = true, label = "$label @$index/$w", whole = true)
                }
            }
            shown += item
            assertPresenterParity(presenter, full, shown.toList(), coordinatorMode, runActive = index < items.lastIndex, label = "$label @$index", whole = true)
        }
        assertScanParity(items, label)
    }

    // -- Bennett's exports ---------------------------------------------------------------------------------------------

    @Test
    fun `the seven-run frame reads the same incrementally, with its leaked message and without, prompts or bare`() {
        val firstAt = 1_789_340_000_000L
        for (leak in listOf(true, false)) for (prompts in listOf(true, false)) {
            val items = SevenRunCoordinator.transcript(SevenRunCoordinator.runs(firstAt, leak), prompts, firstAt)
            if (leak) assertThat(LegacyLeftOut.leftOut(items)).isNotEmpty()
            for (mode in listOf(true, false)) assertStreamed(items, mode, "seven-run leak=$leak prompts=$prompts mode=$mode")
        }
    }

    @Test
    fun `the 2026-09-20 whole-turn replay reads the same incrementally`() {
        fun call(id: String, name: String, args: String) = RunStreamEvent.ToolCall(SseToolCallDto(callId = id, name = name, status = "completed", args = Json.parseToJsonElement(args) as JsonObject, result = Json.parseToJsonElement("""{"success":{}}""") as JsonObject))
        fun turn(runId: String, calls: List<Pair<String, String>>, text: String?, durationMs: Long): List<TimelineItem> {
            val live = TimelineBuilder.LiveRun(runId, timed = false)
            live.apply(RunStreamEvent.Thinking("Reading the report."))
            calls.forEach { (id, name) ->
                val args = when (name) { "send_message" -> """{"text":{"content":"Done: the pricing table is filed."}}"""; "send_to_agent" -> """{"agentId":"bc-w","message":"Next."}"""; else -> """{"path":"notes.md"}""" }
                live.apply(call(id, name, args))
            }
            text?.let { live.apply(RunStreamEvent.Assistant(it)) }
            live.apply(RunStreamEvent.Result(runId, RunStatus.FINISHED, null, durationMs, null))
            return live.snapshot()
        }
        val original = listOf("7gwWXN" to "run_terminal_cmd", "xVzzwn" to "get_mcp_tools", "AGGrGj" to "read_file", "9dBRYJ" to "send_to_agent", "W5TUR6" to "send_message", "RCHLg4" to "edit_file", "w2dwQQ" to "edit_file")
        val note = "The research worker's table is filed; the primary is told what comes next."
        val runs = listOf(
            turn("run-A", original, note, 98_586), turn("run-B", original, note, 115_315),
            turn("run-C", emptyList(), note, 4_245), turn("run-D", listOf("mzA4RA" to "edit_file"), note, 54_429),
        )
        val bare = runs.flatten()
        assertThat(LegacyLeftOut.leftOut(bare)).isNotEmpty()
        assertStreamed(bare, coordinatorMode = true, label = "replay bare")
        // The same runs each under a prompt of its own: the replays cross the turns the presenter cuts.
        val prompted = runs.flatMapIndexed { i, run -> listOf(UserMessage("u$i", "Prompt $i", 1_000_000L + i * 60_000L)) + run }
        assertStreamed(prompted, coordinatorMode = true, label = "replay prompted")
        assertStreamed(prompted, coordinatorMode = false, label = "replay prompted, plain")
    }

    @Test
    fun `the coordinator run and the event wall read the same incrementally`() {
        val injected = SystemNotifications.parse("m-inject", CoordinatorFixtures.injectedTurn("subagent_completion_necessary_follow_up"), 1_789_340_700_000L)!!
        val run = injected.items + AssistantMessage("asst-inject", "Noted; the release worker is done.") + RunFooter("run-inject", "run-inject", RunStatus.FINISHED, 41_000, emptyList()) +
            CoordinatorFixtures.replay("coordinator_run.sse", "run-coord-001")
        // The run's log twice: the second copy a silent turn replaying the first.
        val twice = run + UserMessage("u-again", "Again", 1_789_340_900_000L) + CoordinatorFixtures.replay("coordinator_run.sse", "run-coord-002")
        assertStreamed(twice, coordinatorMode = true, label = "coordinator run")
        val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject }
        val wallItems = wall.flatMapIndexed { index, turn ->
            SystemNotifications.parse("inject-$index", turn.getValue("text").jsonPrimitive.content, turn.getValue("timestampMillis").jsonPrimitive.long)!!.items +
                listOf(AssistantMessage("a$index", "Logged."), RunFooter("f$index", "run$index", RunStatus.FINISHED, 12_000L, emptyList()))
        }
        assertStreamed(wallItems, coordinatorMode = true, label = "event wall")
    }

    // -- random transcripts ------------------------------------------------------------------------------------------------

    @Test
    fun `random transcripts full of replays leave out the same keys at every checkpoint`() {
        val random = Random(20260928)
        repeat(300) { round ->
            val items = Generator(random, "r$round").turns(1 + random.nextInt(14))
            assertScanParity(items, "round $round")
        }
    }

    @Test
    fun `random transcripts present the same rows incrementally as they grow, stream, re-read and page`() {
        val random = Random(9281)
        repeat(200) { round ->
            val generator = Generator(random, "p$round")
            val coordinatorMode = random.nextInt(4) > 0
            val presenter = TranscriptPresenter()
            var items: List<TimelineItem> = generator.turns(1 + random.nextInt(6))
            val full = FullScanTranscriptPresenter()
            repeat(20) { step ->
                items = when (random.nextInt(10)) {
                    0, 1 -> items + generator.turns(1 + random.nextInt(2))
                    2, 3 -> generator.stream(items)
                    4 -> items + generator.tailStep()
                    5 -> items + generator.footer()
                    6 -> generator.older(1 + random.nextInt(3)) + items
                    7 -> items.map { if ((it is UserMessage || it is RunFooter) && random.nextBoolean()) remint(it) else it }
                    8 -> generator.reread(items)
                    else -> if (random.nextInt(6) == 0) emptyList() else items.take(maxOf(0, items.size - 1 - random.nextInt(3)))
                }
                // Not the whole presentation: on shapes this odd (a footer straight after a prompt, a reply after a
                // cancel's notice) the presenter's turn-by-turn reading and the whole one already differed.
                assertPresenterParity(presenter, full, items, coordinatorMode, runActive = random.nextBoolean(), label = "round $round step $step", whole = false)
            }
            assertScanParity(items, "round $round")
        }
    }

    private fun remint(item: TimelineItem): TimelineItem = when (item) {
        is UserMessage -> item.copy()
        is RunFooter -> item.copy()
        else -> item
    }

    /**
     * Coordinator-shaped turns that say things again: calls replayed from earlier runs (the same instance, or a copy),
     * messages sent again under the same call or message id, replies repeated word for word or with other spacing,
     * group and item ids that come round, runs without a footer, runs the next prompt cut short, legacy cancel notices.
     */
    private class Generator(private val random: Random, private val tag: String) {
        private var turn = 0
        private var older = 0
        private var clock = 1_800_000_000_000L
        private val drawnCalls = ArrayList<ToolCall>()
        private val drawnTexts = ArrayList<String>()
        private val groupIds = ArrayList<String>()
        private val itemIds = ArrayList<String>()

        fun turns(count: Int): List<TimelineItem> = (0 until count).flatMap { turn(turn++) }

        /** Older turns, for a page inserted above: ids of their own, times before everything so far. */
        fun older(count: Int): List<TimelineItem> = (0 until count).flatMap { turn(-1_000 - older++) }

        private fun turn(t: Int): List<TimelineItem> {
            val out = ArrayList<TimelineItem>()
            clock += 60_000L
            when (random.nextInt(4)) {
                0 -> out += UserMessage("$tag-u$t", "Prompt $t", clock)
                1 -> out += SystemNotification("$tag-n$t", SystemNotification.Kind.Worker, title = "Report ${t % 3}", summary = "Worker ${t % 2} reported", raw = "<system_notification>$t</system_notification>", timestampMillis = clock, agentId = "bc-w${t % 2}")
                2 -> out += UserMessage("$tag-u$t", "Prompt $t", clock)
                else -> Unit
            }
            repeat(random.nextInt(3)) { k -> out += group(t, k) }
            if (random.nextInt(3) > 0) out += reply(t)
            if (random.nextInt(4) > 0) out += footer()
            return out
        }

        private fun group(t: Int, k: Int): ActivityGroup {
            val steps = ArrayList<ActivityStep>()
            repeat(random.nextInt(5)) { s -> steps += step(t, k, s) }
            if (steps.isEmpty()) steps += ThinkingBlock("thinking $t-$k", 2)
            val id = if (groupIds.isNotEmpty() && random.nextInt(8) == 0) groupIds.random(random) else "$tag-g$t-$k"
            groupIds += id
            return ActivityGroup(id, steps)
        }

        private fun step(t: Int, k: Int, s: Int): ActivityStep {
            if (drawnCalls.isNotEmpty() && random.nextInt(3) == 0) {
                val earlier = drawnCalls.random(random)
                return when (random.nextInt(3)) {
                    0 -> earlier
                    1 -> earlier.copy()
                    else -> earlier.copy(summary = earlier.summary + " again")
                }
            }
            val id = when (random.nextInt(10)) {
                0 -> ""
                1 -> "turn-0:step:$s:tool"
                else -> "$tag-c$t-$k-$s"
            }
            val call = when (random.nextInt(7)) {
                0 -> ToolCall(id, "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "File$s.kt", detail = "src/File$s.kt")
                1 -> ToolCall(id, "edit_file", ToolKind.Edit, ToolCall.STATUS_COMPLETED, "notes.md", linesAdded = 1, linesRemoved = 1)
                2 -> ToolCall(id, "SendMessage", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage(messageText(), messageId = if (random.nextBoolean()) "m-${random.nextInt(4)}" else null))
                3 -> ToolCall(id, "send_to_user", ToolKind.Other, ToolCall.STATUS_COMPLETED, "", detail = if (random.nextBoolean()) messageText() else null)
                4 -> ToolCall(id, "SendMessage", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage("", missing = true))
                5 -> ToolCall(id, "sendToAgent", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "Worker", linkedAgentIds = listOf("bc-w${s % 2}"))
                else -> ThinkingBlock("thought $t-$k-$s", 1).let { return it }
            }
            drawnCalls += call
            return call
        }

        private fun messageText(): String = listOf("Done: the table is filed.", "Done:  the table\nis filed.", "The workers are moving.", "Nothing new.").random(random)

        private fun reply(t: Int): AssistantMessage {
            val text = if (drawnTexts.isNotEmpty() && random.nextInt(3) == 0) {
                drawnTexts.random(random).let { if (random.nextBoolean()) " $it\n" else it }
            } else {
                listOf("Logged.", "Reply $t with `code`", "", "The notes are updated and worker ${t % 2} is told.").random(random)
            }
            drawnTexts += text
            val id = if (itemIds.isNotEmpty() && random.nextInt(8) == 0) itemIds.random(random) else "$tag-a$t"
            itemIds += id
            return AssistantMessage(id, text, isStreaming = random.nextInt(6) == 0)
        }

        fun footer(): List<TimelineItem> {
            val n = random.nextInt(1_000_000)
            val status = if (random.nextInt(3) == 0) RunStatus.CANCELLED else RunStatus.FINISHED
            val footer = RunFooter("$tag-f$n", "$tag-run$n", status, 12_000L, emptyList(), endedAtMillis = if (random.nextBoolean()) clock + 5_000L else null)
            return if (status == RunStatus.CANCELLED && random.nextInt(3) == 0) listOf(footer, NoticeCard("$tag-cancel$n", NoticeCard.RUN_CANCELLED)) else listOf(footer)
        }

        /** Something added to the newest turn: a step, a reply, a group that replays one drawn before. */
        fun tailStep(): List<TimelineItem> = when (random.nextInt(3)) {
            0 -> listOf(group(turn, random.nextInt(9)))
            1 -> listOf(reply(turn))
            else -> listOf(ActivityGroup("$tag-live${random.nextInt(1000)}", listOf(drawnCalls.randomOrNull(random) ?: ToolCall("$tag-live", "grep", ToolKind.Grep, "running", "needle"))))
        }

        /** The newest reply written on by a word, or a reply started. */
        fun stream(items: List<TimelineItem>): List<TimelineItem> {
            val last = items.lastOrNull()
            if (last is AssistantMessage) return items.dropLast(1) + last.copy(markdown = last.markdown + " word", isStreaming = true)
            return items + AssistantMessage("$tag-s${random.nextInt(1000)}", "So far", isStreaming = true)
        }

        /** A turn in the middle read again: one of its items minted anew, a step more, or its reply said otherwise. */
        fun reread(items: List<TimelineItem>): List<TimelineItem> {
            if (items.isEmpty()) return items
            val at = random.nextInt(items.size)
            val out = items.toMutableList()
            out[at] = when (val item = items[at]) {
                is ActivityGroup -> item.copy(steps = item.steps + step(turn, 0, 9))
                is AssistantMessage -> item.copy(markdown = item.markdown + " (edited)")
                is RunFooter -> item.copy(status = if (item.status == RunStatus.CANCELLED) RunStatus.FINISHED else RunStatus.CANCELLED)
                else -> return items
            }
            return out
        }
    }
}
