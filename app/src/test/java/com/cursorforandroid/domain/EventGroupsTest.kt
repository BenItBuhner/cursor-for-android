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
 * eighteen consecutive silent turns, in Cursor's own markup) cut into rows: one group behind one line, the same
 * notice twice in a row counted once, a turn that spoke breaking the group, the newest group open only when small,
 * and each event's row reading as its subject, its verb and its actor.
 */
class EventGroupsTest {

    private val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject }

    /** The wall as the screen has it: each turn's notice, nothing said after it. */
    private fun wallItems(): List<TimelineItem> = wall.flatMapIndexed { index, turn ->
        SystemNotifications.parse("inject-$index", turn.getValue("text").jsonPrimitive.content, turn.getValue("timestampMillis").jsonPrimitive.long)!!.items
    }

    private fun notice(id: String, title: String, summary: String?, kind: SystemNotification.Kind = SystemNotification.Kind.Other, at: Long? = null, body: String? = null) =
        SystemNotification(id, kind, title, summary, body, raw = "<system_notification>…</system_notification>", timestampMillis = at)

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
        val group = rows.single() as TranscriptRow.Events
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
        // The sixth turn produced a message: the five before it are one group, it stands as event and message, the twelve after are another.
        val spoke = items.take(6) + message("m1", "PR #65 is open; the renders are in.") + RunFooter("f1", "run-1", RunStatus.FINISHED, 20_000, emptyList()) + items.drop(6)
        val rows = TranscriptRows.of(CoordinatorTranscript.present(spoke, coordinatorMode = true), coordinatorMode = true)
        val kinds = rows.map { it::class.simpleName }
        assertThat(kinds).containsExactly("Events", "Event", "Message", "Stretch", "Events").inOrder()
        assertThat((rows[0] as TranscriptRow.Events).count).isEqualTo(5)
        assertThat((rows[1] as TranscriptRow.Event).line.text).isEqualTo("#63 · merged · cursor[bot]")
        // The footer alone after the message is that turn's, not the next group's.
        assertThat((rows[3] as TranscriptRow.Stretch).single).isInstanceOf(TranscriptRow.Entry.Footer::class.java)
        assertThat((rows[4] as TranscriptRow.Events).count).isEqualTo(12)

        // A turn whose work produced a picture is not silent either.
        val image = ToolCall("i1", "generate_image", ToolKind.Image, "completed", "board", payload = ToolPayload.GeneratedImage("board.png", "The board", src = "file:///tmp/board.png"))
        val drew = items.take(3) + ActivityGroup("g-img", listOf(image)) + items.drop(3)
        val drawn = TranscriptRows.of(drew, coordinatorMode = true).map { it::class.simpleName }
        assertThat(drawn).containsExactly("Events", "Event", "Stretch", "Media", "Events").inOrder()

        // A silent turn's own work — a note, a footer — stays with it inside the group, as a stretch.
        val worked = items.take(2) + AssistantMessage("a1", "Noted, nothing to do.") + RunFooter("f2", "run-2", RunStatus.FINISHED, 9_000, emptyList()) + items.drop(2)
        val groupRows = TranscriptRows.of(CoordinatorTranscript.present(worked, coordinatorMode = true), coordinatorMode = true)
        val group = groupRows.single() as TranscriptRow.Events
        assertThat(group.count).isEqualTo(18)
        // The remark was folded under the turn's last notice; the footer follows it inside the group.
        assertThat(group.rows.map { it::class.simpleName }.take(4)).containsExactly("Event", "Event", "Stretch", "Event").inOrder()
        assertThat((group.rows[2] as TranscriptRow.Stretch).single).isInstanceOf(TranscriptRow.Entry.Footer::class.java)
        assertThat(group.events[1].notification.narration).isEqualTo("Noted, nothing to do.")
    }

    @Test
    fun `two events group, one does not, and the newest small group opens on its own`() {
        val a = notice("a", "GitHub notification", "#12 · synchronize · cursor[bot]")
        val b = notice("b", "GitHub notification", "#13 · opened · BenItBuhner")
        val c = notice("c", "Subagent completed", "Render kitchen", SystemNotification.Kind.Subagent)
        val prompt = UserMessage("u1", "Carry on.")
        val single = TranscriptRows.of(listOf(prompt, a, prompt.copy(id = "u2")), coordinatorMode = true).map { it::class.simpleName }
        assertThat(single).containsExactly("Item", "Event", "Item").inOrder()

        val two = TranscriptRows.of(listOf(prompt, a, b), coordinatorMode = true)
        val newest = two[1] as TranscriptRow.Events
        assertThat(newest.count).isEqualTo(2)
        assertThat(newest.startsOpen).isTrue()
        assertThat(newest.summary.text).isEqualTo("2 events · 2 GitHub")

        // Two groups: the older one is closed whatever its size, the newest opens only under three events.
        val both = TranscriptRows.of(listOf(a, b, prompt, b.copy(id = "b2"), c), coordinatorMode = true)
        assertThat(both.map { it::class.simpleName }).containsExactly("Events", "Item", "Events").inOrder()
        assertThat((both[0] as TranscriptRow.Events).startsOpen).isFalse()
        assertThat((both[2] as TranscriptRow.Events).startsOpen).isTrue()
        val three = TranscriptRows.of(listOf(a, b, c), coordinatorMode = true).single() as TranscriptRow.Events
        assertThat(three.startsOpen).isFalse()
        assertThat(three.summary.text).isEqualTo("3 events · 2 GitHub · 1 subagent")

        // A run still writing after the last event keeps that turn out of the group: it is not silent yet.
        val live = TranscriptRows.of(listOf(a, b, c, ActivityGroup("g1", listOf(ToolCall("r1", "read_file", ToolKind.Read, "running", "A.kt")))), coordinatorMode = true, runActive = true)
        assertThat(live.map { it::class.simpleName }).containsExactly("Events", "Event", "Stretch").inOrder()
        assertThat((live[2] as TranscriptRow.Stretch).live).isTrue()
    }

    @Test
    fun `the same notice in a row is one row counted, a different one is not`() {
        val sync = notice("s1", "GitHub notification", "#12 · synchronize · cursor[bot]", at = 1_000L)
        val rows = TranscriptRows.of(listOf(sync, sync.copy(id = "s2", timestampMillis = 2_000L), sync.copy(id = "s3", summary = "#12 · synchronize · BenItBuhner")), coordinatorMode = false)
        val group = rows.single() as TranscriptRow.Events
        assertThat(group.events.map { "${it.line.text}${if (it.count > 1) " ×${it.count}" else ""}" }).containsExactly(
            "#12 · synchronize · cursor[bot] ×2",
            "#12 · synchronize · BenItBuhner",
        ).inOrder()
        assertThat(group.count).isEqualTo(3)
        // Alone, a repeated notice is one row counted twice, not a group.
        val pair = TranscriptRows.of(listOf(sync, sync.copy(id = "s2")), coordinatorMode = false).single() as TranscriptRow.Event
        assertThat(pair.count).isEqualTo(2)
        assertThat(pair.key).isEqualTo("s1")
    }

    @Test
    fun `an event's line is its subject, verb and actor, whatever kind it is`() {
        assertThat(EventLine.of(notice("1", "GitHub notification", "#64 · opened · BenItBuhner"))).isEqualTo(EventLine(EventLine.Source.GitHub, "#64", "opened", "BenItBuhner"))
        assertThat(EventLine.of(notice("2", "GitHub notification", "A subscribed pull request changed."))).isEqualTo(EventLine(EventLine.Source.GitHub, "A subscribed pull request changed.", null, null))
        assertThat(EventLine.of(notice("3", "Subagent completed", "Hand & Arm Renders", SystemNotification.Kind.Subagent))).isEqualTo(EventLine(EventLine.Source.Subagent, "Hand & Arm Renders", "completed", null))
        assertThat(EventLine.of(notice("4", "Worker failed", "Usage events aggregation", SystemNotification.Kind.Worker))).isEqualTo(EventLine(EventLine.Source.Worker, "Usage events aggregation", "failed", null))
        assertThat(EventLine.of(notice("5", "Shell timed out", null, SystemNotification.Kind.Task))).isEqualTo(EventLine(EventLine.Source.Task, "Shell", "timed out", null))
        assertThat(EventLine.of(notice("6", "Goal continued", "Build the limbs", SystemNotification.Kind.Goal))).isEqualTo(EventLine(EventLine.Source.Goal, "Build the limbs", "continued", null))
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
        val group = TranscriptRows.of(events, coordinatorMode = true).single() as TranscriptRow.Events
        assertThat(group.summary.text).isEqualTo("6 events · 2 GitHub · 1 subagent · 1 worker")
        assertThat(group.summary.span).isNull()
        // Under a minute apart, the span is not worth saying.
        val close = TranscriptRows.of(listOf(events[1].copy(timestampMillis = 10_000L), events[2].copy(timestampMillis = 40_000L)), coordinatorMode = true).single() as TranscriptRow.Events
        assertThat(close.summary.span).isNull()
    }
}
