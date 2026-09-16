package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

/**
 * The wall of injected turns Bennett's coordinator chat showed (see [CoordinatorFixtures], `event_wall.json`:
 * eighteen consecutive silent turns, in Cursor's own markup) cut into rows: one stretch between two messages with
 * the eighteen behind one line inside it, the same notice twice in a row counted once, a message ending the stretch,
 * the newest group open only when small, and each event's row reading as its subject, its verb and its actor.
 */
class EventGroupsTest {

    private val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject }

    /** The wall as the screen has it: each turn's notice, nothing said after it. */
    private fun wallItems(): List<TimelineItem> = wall.flatMapIndexed { index, turn ->
        SystemNotifications.parse("inject-$index", turn.getValue("text").jsonPrimitive.content, turn.getValue("timestampMillis").jsonPrimitive.long)!!.items
    }

    private fun notice(id: String, title: String, summary: String?, kind: SystemNotification.Kind = SystemNotification.Kind.Other, at: Long? = null, body: String? = null) =
        SystemNotification(id, kind, title, summary, body, raw = "<system_notification>…</system_notification>", timestampMillis = at)

    /** The runs of events behind one line, wherever the stretches hold them, in order. */
    private fun groups(rows: List<TranscriptRow>): List<TranscriptRow.Events> =
        rows.filterIsInstance<TranscriptRow.Stretch>().flatMap { stretch -> stretch.entries.filterIsInstance<TranscriptRow.Entry.Events>().map { it.group } }

    /** The lone events drawn as their own line: a stretch of one event, or an event entry standing among other steps. */
    private fun loneEvents(rows: List<TranscriptRow>): List<TranscriptRow.Event> =
        rows.filterIsInstance<TranscriptRow.Stretch>().flatMap { stretch -> stretch.entries.filterIsInstance<TranscriptRow.Entry.Event>().map { it.row } }

    private fun message(id: String, text: String) = ActivityGroup(
        "$id-group",
        listOf(ToolCall("$id-call", ToolNames.USER_MESSAGE_TOOL, ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage(text))),
    )

    @Test
    fun `the wall is one group behind one line, counted by kind and spanned in time`() {
        val items = wallItems()
        assertThat(items).hasSize(18)
        assertThat(items.all { it is SystemNotification }).isTrue()
        // Cursor's instruction to the model is not a prompt: nothing of the wall reads as the user's words.
        assertThat(items.filterIsInstance<UserMessage>()).isEmpty()

        val rows = TranscriptRows.of(CoordinatorTranscript.present(items, coordinatorMode = true), coordinatorMode = true)
        // One stretch, whose one line says what it holds; inside it, the eighteen behind one line of their own.
        val stretch = rows.single() as TranscriptRow.Stretch
        assertThat(stretch.single).isNull()
        assertThat(stretch.summary.text).isEqualTo("18 events")
        assertThat(stretch.eventCount).isEqualTo(18)
        val group = groups(rows).single()
        assertThat(group.count).isEqualTo(18)
        assertThat(group.summary.text).isEqualTo("18 events · 11 GitHub · 7 subagents · 1h 59m span")
        // Silent, and eighteen strong: closed.
        assertThat(group.startsOpen).isFalse()
        // The same completion twice in a row is one row counted twice; the rest stand once each, in order.
        assertThat(group.events).hasSize(17)
        val repeated = group.events.single { it.count > 1 }
        assertThat(repeated.line.text).isEqualTo("Match product sets to their references B · completed")
        assertThat(repeated.count).isEqualTo(2)
        // "#12 · synchronize" came twice too, but not in a row: two rows.
        assertThat(group.events.count { it.line.subject == "#12" }).isEqualTo(2)
        assertThat(group.events.map { it.line.text }).containsExactly(
            "Hand & Arm Renders · completed",
            "#64 · opened · BenItBuhner",
            "Render kitchen v2 shard R6-1of2 · completed",
            "#57 · merged · cursor[bot]",
            "#62 · merged · cursor[bot]",
            "#63 · merged · cursor[bot]",
            "#65 · opened · BenItBuhner",
            "Match product sets to their references B · completed",
            "iPhone & Galaxy Renders · completed",
            "#12 · synchronize · cursor[bot]",
            "#60 · merged · cursor[bot]",
            "#58 · merged · cursor[bot]",
            "#59 · merged · cursor[bot]",
            "#12 · synchronize · cursor[bot]",
            "Match product sets to their references A · completed",
            "Render backyard v2 shard Y1-1of4 · completed",
            "#66 · opened · BenItBuhner",
        ).inOrder()
        // Each row keeps what it opened onto: the subagent's report, the pull request's URL.
        assertThat(group.events.first().notification.body).contains("Rendered the hand & arm stills")
        assertThat(group.events[1].notification.body).contains("https://github.com/BenItBuhner/revenue-scaling-pipeline/pull/64")
        assertThat(group.events.first().notification.agentId).isEqualTo("bc-00000000-1a2d-5218-8e2e-7464ea74f671")
        assertThat(rows.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `a turn that spoke, a worker's card, a question or a picture breaks the group, and its own event stays a row`() {
        val items = wallItems()
        // The sixth turn produced a message: everything up to the message is one stretch, the six events behind one
        // line inside it; the message stands; the footer and the twelve events after are the next stretch.
        val spoke = items.take(6) + message("m1", "PR #65 is open; the renders are in.") + RunFooter("f1", "run-1", RunStatus.FINISHED, 20_000, emptyList()) + items.drop(6)
        val rows = TranscriptRows.of(CoordinatorTranscript.present(spoke, coordinatorMode = true), coordinatorMode = true)
        assertThat(rows.map { it::class.simpleName }).containsExactly("Stretch", "Message", "Stretch").inOrder()
        assertThat(groups(rows).map { it.count }).containsExactly(6, 12).inOrder()
        assertThat((rows[0] as TranscriptRow.Stretch).summary.text).isEqualTo("6 events")
        // The footer of the turn that spoke opens the next stretch and is carried by its line, not listed.
        val after = rows[2] as TranscriptRow.Stretch
        assertThat(after.summary.text).isEqualTo("Worked 20s · 12 events")
        assertThat(after.listed.map { it::class.simpleName }).containsExactly("Events")
        assertThat(loneEvents(rows)).isEmpty()

        // A picture produced between two events: it stands outside the stretch, after it; the call that made it
        // keeps the events on either side of it apart inside — two groups in the one stretch.
        val image = ToolCall("i1", "generate_image", ToolKind.Image, "completed", "board", payload = ToolPayload.GeneratedImage("board.png", "The board", src = "file:///tmp/board.png"))
        val drew = items.take(3) + ActivityGroup("g-img", listOf(image)) + items.drop(3)
        val drawn = TranscriptRows.of(drew, coordinatorMode = true)
        assertThat(drawn.map { it::class.simpleName }).containsExactly("Stretch", "Media").inOrder()
        assertThat((drawn[0] as TranscriptRow.Stretch).summary.text).isEqualTo("18 events · 1 image")
        assertThat((drawn[0] as TranscriptRow.Stretch).listed.map { it::class.simpleName }).containsExactly("Events", "Call", "Events").inOrder()
        assertThat(groups(drawn).map { it.count }).containsExactly(3, 15).inOrder()

        // A silent turn's own work — a remark, a footer — stays in the same stretch: the remark folded under the
        // turn's line, the footer between two events, which does not keep them apart.
        val worked = items.take(2) + AssistantMessage("a1", "Noted, nothing to do.") + RunFooter("f2", "run-2", RunStatus.FINISHED, 9_000, emptyList()) + items.drop(2)
        val groupRows = TranscriptRows.of(CoordinatorTranscript.present(worked, coordinatorMode = true), coordinatorMode = true)
        val stretch = groupRows.single() as TranscriptRow.Stretch
        assertThat(stretch.summary.text).isEqualTo("Worked 9s · 18 events")
        val group = groups(groupRows).single()
        assertThat(group.count).isEqualTo(18)
        assertThat(group.events[1].notification.narration).isEqualTo("Noted, nothing to do.")
        assertThat(stretch.entries.map { it::class.simpleName }).containsExactly("Events", "Footer").inOrder()
    }

    @Test
    fun `two events group, one does not, and the newest small group opens on its own`() {
        val a = notice("a", "GitHub notification", "#12 · synchronize · cursor[bot]")
        val b = notice("b", "GitHub notification", "#13 · opened · BenItBuhner")
        val c = notice("c", "Subagent completed", "Render kitchen", SystemNotification.Kind.Subagent)
        val prompt = UserMessage("u1", "Carry on.")
        // One event alone is its line, not a summary of itself.
        val single = TranscriptRows.of(listOf(prompt, a, prompt.copy(id = "u2")), coordinatorMode = true)
        assertThat(single.map { it::class.simpleName }).containsExactly("Item", "Stretch", "Item").inOrder()
        assertThat((single[1] as TranscriptRow.Stretch).single).isInstanceOf(TranscriptRow.Entry.Event::class.java)
        assertThat(groups(single)).isEmpty()

        val two = TranscriptRows.of(listOf(prompt, a, b), coordinatorMode = true)
        val newest = groups(two).single()
        assertThat(newest.count).isEqualTo(2)
        assertThat(newest.startsOpen).isTrue()
        assertThat(newest.summary.text).isEqualTo("2 events · 2 GitHub")

        // Two groups: the older one is closed whatever its size, the newest opens only under three events.
        val both = TranscriptRows.of(listOf(a, b, prompt, b.copy(id = "b2"), c), coordinatorMode = true)
        assertThat(both.map { it::class.simpleName }).containsExactly("Stretch", "Item", "Stretch").inOrder()
        assertThat(groups(both).map { it.startsOpen }).containsExactly(false, true).inOrder()
        val three = groups(TranscriptRows.of(listOf(a, b, c), coordinatorMode = true)).single()
        assertThat(three.startsOpen).isFalse()
        assertThat(three.summary.text).isEqualTo("3 events · 2 GitHub · 1 subagent")

        // A run still writing after the events: one live stretch, the events behind their line inside it.
        val live = TranscriptRows.of(listOf(a, b, c, ActivityGroup("g1", listOf(ToolCall("r1", "read_file", ToolKind.Read, "running", "A.kt")))), coordinatorMode = true, runActive = true)
        val working = live.single() as TranscriptRow.Stretch
        assertThat(working.live).isTrue()
        assertThat(working.summary.text).isEqualTo("Working · 3 events · 1 file")
        assertThat(working.listed.map { it::class.simpleName }).containsExactly("Events", "Call").inOrder()
        // An earlier turn's footer inside the stretch does not close it: the injected turn after it is still running.
        val afterFooter = TranscriptRows.of(listOf(a, RunFooter("f1", "run-1", RunStatus.FINISHED, 9_000, emptyList()), b, ActivityGroup("g2", listOf(ToolCall("r2", "read_file", ToolKind.Read, "running", "B.kt")))), coordinatorMode = true, runActive = true)
        val still = afterFooter.single() as TranscriptRow.Stretch
        assertThat(still.live).isTrue()
        assertThat(still.summary.text).isEqualTo("Working · 2 events · 1 file")
        // Closed with its footer: nothing is being written, however active the row says the chat is.
        val closed = TranscriptRows.of(listOf(a, RunFooter("f1", "run-1", RunStatus.FINISHED, 9_000, emptyList()), b, RunFooter("f2", "run-2", RunStatus.FINISHED, 3_000, emptyList())), coordinatorMode = true, runActive = true)
        assertThat((closed.single() as TranscriptRow.Stretch).live).isFalse()
        assertThat((closed.single() as TranscriptRow.Stretch).summary.text).isEqualTo("Worked 12s · 2 events")
    }

    @Test
    fun `the same notice in a row is one row counted, a different one is not`() {
        val sync = notice("s1", "GitHub notification", "#12 · synchronize · cursor[bot]", at = 1_000L)
        val rows = TranscriptRows.of(listOf(sync, sync.copy(id = "s2", timestampMillis = 2_000L), sync.copy(id = "s3", summary = "#12 · synchronize · BenItBuhner")), coordinatorMode = false)
        val group = groups(rows).single()
        assertThat(group.events.map { "${it.line.text}${if (it.count > 1) " ×${it.count}" else ""}" }).containsExactly(
            "#12 · synchronize · cursor[bot] ×2",
            "#12 · synchronize · BenItBuhner",
        ).inOrder()
        assertThat(group.count).isEqualTo(3)
        assertThat((rows.single() as TranscriptRow.Stretch).summary.text).isEqualTo("3 events")
        // Alone, a repeated notice is one line counted twice, not a group.
        val pair = (TranscriptRows.of(listOf(sync, sync.copy(id = "s2")), coordinatorMode = false).single() as TranscriptRow.Stretch).single as TranscriptRow.Entry.Event
        assertThat(pair.row.count).isEqualTo(2)
        assertThat(pair.key).isEqualTo("s1")
        // A footer between two of the same notice does not keep them apart either.
        val split = TranscriptRows.of(listOf(sync, RunFooter("f1", "run-1", RunStatus.FINISHED, 5_000, emptyList()), sync.copy(id = "s2")), coordinatorMode = false).single() as TranscriptRow.Stretch
        assertThat(split.eventCount).isEqualTo(2)
        assertThat(split.listed.map { it::class.simpleName }).containsExactly("Event")
        assertThat(split.summary.text).isEqualTo("Worked 5s · 2 events")
    }

    @Test
    fun `an event's line is its subject, verb and actor, whatever kind it is`() {
        assertThat(EventLine.of(notice("1", "GitHub notification", "#64 · opened · BenItBuhner"))).isEqualTo(EventLine(EventLine.Source.GitHub, "#64", "opened", "BenItBuhner"))
        assertThat(EventLine.of(notice("2", "GitHub notification", "A subscribed pull request changed."))).isEqualTo(EventLine(EventLine.Source.GitHub, "A subscribed pull request changed.", null, null))
        assertThat(EventLine.of(notice("3", "Subagent completed", "Hand & Arm Renders", SystemNotification.Kind.Subagent))).isEqualTo(EventLine(EventLine.Source.Subagent, "Hand & Arm Renders", "completed", null))
        assertThat(EventLine.of(notice("4", "Worker failed", "Usage events aggregation", SystemNotification.Kind.Worker))).isEqualTo(EventLine(EventLine.Source.Worker, "Usage events aggregation", "failed", null))
        assertThat(EventLine.of(notice("5", "Shell timed out", null, SystemNotification.Kind.Task))).isEqualTo(EventLine(EventLine.Source.Task, "Shell", "timed out", null))
        // A goal's row keeps the event first and the objective, a sentence, dimmed beside it.
        assertThat(EventLine.of(notice("6", "Goal continued", "Build the limbs", SystemNotification.Kind.Goal))).isEqualTo(EventLine(EventLine.Source.Goal, "Goal continued", null, "Build the limbs"))
        assertThat(EventLine.of(notice("6b", "Goal not set", "The account refused it.", SystemNotification.Kind.Goal)).text).isEqualTo("Goal not set · The account refused it.")
        assertThat(EventLine.of(notice("6c", "Goal completed", null, SystemNotification.Kind.Goal)).text).isEqualTo("Goal completed")
        assertThat(EventLine.of(notice("7", "Timer notification", "Check the release every hour"))).isEqualTo(EventLine(EventLine.Source.Timer, "Timer", "Check the release every hour", null))
        assertThat(EventLine.of(notice("8", "Slack notification", "Bennett: ship it"))).isEqualTo(EventLine(EventLine.Source.Other, "Bennett: ship it", "Slack", null))
        assertThat(EventLine.of(notice("9", "System notification", "The user paused the goal."))).isEqualTo(EventLine(EventLine.Source.Other, "The user paused the goal.", null, null))
        assertThat(EventLine.of(notice("9", "System notification", "The user paused the goal.")).text).isEqualTo("The user paused the goal.")
    }

    @Test
    fun `a group's line names the kinds most first, three at most, and no span without times`() {
        val events = listOf(
            notice("t", "Timer notification", "Hourly"),
            notice("g1", "GitHub notification", "#1 · opened · a"),
            notice("g2", "GitHub notification", "#2 · opened · a"),
            notice("s", "Subagent completed", "Render", SystemNotification.Kind.Subagent),
            notice("w", "Worker completed", "Aggregate", SystemNotification.Kind.Worker),
            notice("o", "Goal continued", "Build", SystemNotification.Kind.Goal),
        )
        val group = groups(TranscriptRows.of(events, coordinatorMode = true)).single()
        assertThat(group.summary.text).isEqualTo("6 events · 2 GitHub · 1 subagent · 1 worker")
        assertThat(group.summary.span).isNull()
        // Under a minute apart, the span is not worth saying.
        val close = groups(TranscriptRows.of(listOf(events[1].copy(timestampMillis = 10_000L), events[2].copy(timestampMillis = 40_000L)), coordinatorMode = true)).single()
        assertThat(close.summary.span).isNull()
    }
}
