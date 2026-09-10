package com.cursorforandroid.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunDigest
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveNotificationRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val startedAt = 1_700_000_000_000L

    @Before
    fun setUp() = LiveNotifications.ensureChannels(context)

    private fun running(id: String, title: String, activity: RunDigest.Activity = RunDigest.Activity("Editing", "Composer.kt"), phase: LivePhase = LivePhase.Running) = TrackedRun(
        agentId = id, runId = "run-$id", title = title, status = RunStatus.RUNNING, phase = phase, startedAtMillis = startedAt,
        digest = RunDigest(filesEdited = 1, activity = activity),
    )

    private fun finished(status: RunStatus = RunStatus.FINISHED, digest: RunDigest = RunDigest(filesEdited = 3, additions = 80, deletions = 230), prUrl: String? = "https://github.com/acme/app/pull/7") = TrackedRun(
        agentId = "bc-1", runId = "run-1", title = "Update quick action pills interaction and styling", status = status, phase = LivePhase.Finished,
        startedAtMillis = startedAt, digest = digest, durationMs = 185_000, branch = "cursor/pills-1a2b", prUrl = prUrl,
        summary = "Restyled the quick action pills and tightened their press states.\n\nMore detail below.", finishedAtMillis = startedAt + 185_000,
    )

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.subText() = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    private fun Notification.bigText() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    private fun Notification.actionTitles() = actions.orEmpty().map { it.title.toString() }
    private fun Notification.chip() = NotificationCompat.getShortCriticalText(this)
    private fun Notification.isSummary() = flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** The one agent card in a single-agent set. */
    private fun LiveCards.only(): Notification = agents.values.single()

    @Test
    fun `single running agent is one promoted card in the live group with the step, a chronometer and Stop`() {
        val run = running("bc-1", "Update quick action pills interaction and styling")
        val cards = LiveNotificationRenderer.live(context, LiveActivityState(listOf(run), hasReconciled = true))

        assertThat(cards.agents.keys).containsExactly(LiveNotificationRenderer.liveId("bc-1"))
        val n = cards.only()
        assertThat(n.title()).isEqualTo("Update quick action pills interaction and styling")
        assertThat(n.text()).isEqualTo("Editing Composer.kt")
        assertThat(n.subText()).isEqualTo("Editing")
        assertThat(n.chip()).isEqualTo("Editing")
        assertThat(n.getLargeIcon()).isNotNull()
        assertThat(n.group).isEqualTo(LiveNotificationRenderer.GROUP_LIVE)
        assertThat(n.isSummary()).isFalse()
        assertThat(n.extras.getBoolean(Notification.EXTRA_COLORIZED)).isFalse()
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(n.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(n.`when`).isEqualTo(startedAt)
        assertThat(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)).isTrue()
        assertThat(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)).isFalse()
        assertThat(n.extras.getInt(Notification.EXTRA_PROGRESS)).isEqualTo(62)
        assertThat(n.extras.getInt(Notification.EXTRA_PROGRESS_MAX)).isEqualTo(100)
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isTrue()
        assertThat(NotificationCompat.getChannelId(n)).isEqualTo(LiveNotifications.CHANNEL_LIVE)
        assertThat(n.actionTitles()).containsExactly("Stop")
        assertThat(n.contentIntent).isNotNull()

        // The summary is the foreground notification: a group header, never promoted, never colorized.
        val summary = cards.summary
        assertThat(summary.title()).isEqualTo("1 agent running")
        assertThat(summary.text()).isEqualTo("Update quick action pills interaction and styling")
        assertThat(summary.group).isEqualTo(LiveNotificationRenderer.GROUP_LIVE)
        assertThat(summary.isSummary()).isTrue()
        assertThat(summary.number).isEqualTo(1)
        assertThat(NotificationCompat.isRequestPromotedOngoing(summary)).isFalse()
        assertThat(summary.extras.getBoolean(Notification.EXTRA_COLORIZED)).isFalse()
        assertThat(summary.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
    }

    @Test
    fun `bare verbs get an ellipsis and stopping hides the Stop action`() {
        val thinking = running("bc-1", "Agent", activity = RunDigest.Activity.Thinking)
        val thinkingCard = LiveNotificationRenderer.live(context, LiveActivityState(listOf(thinking), true)).only()
        assertThat(thinkingCard.text()).isEqualTo("Thinking\u2026")
        assertThat(thinkingCard.subText()).isEqualTo("Thinking")
        assertThat(thinkingCard.chip()).isEqualTo("Thinking")

        val starting = running("bc-1", "Agent", phase = LivePhase.Starting)
        val startingCard = LiveNotificationRenderer.live(context, LiveActivityState(listOf(starting), true)).only()
        assertThat(startingCard.text()).isEqualTo("Starting\u2026")
        assertThat(startingCard.chip()).isEqualTo("Starting")

        val stopping = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "Agent", phase = LivePhase.Stopping)), true)).only()
        assertThat(stopping.text()).isEqualTo("Stopping\u2026")
        assertThat(stopping.chip()).isEqualTo("Stopping")
        assertThat(stopping.actionTitles()).isEmpty()
    }

    @Test
    fun `several running agents each get their own card under one summary`() {
        val runs = listOf(
            running("bc-1", "Update quick action pills interaction and styling"),
            running("bc-2", "Cesium Revenue Strategy", activity = RunDigest.Activity.Thinking),
            running("bc-3", "Codex-Poly-Bot Scaling", activity = RunDigest.Activity("Running", "redis-cli LLEN catchup:queue")),
        )
        val cards = LiveNotificationRenderer.live(context, LiveActivityState(runs, hasReconciled = true))

        assertThat(cards.agents.keys).containsExactly(
            LiveNotificationRenderer.liveId("bc-1"),
            LiveNotificationRenderer.liveId("bc-2"),
            LiveNotificationRenderer.liveId("bc-3"),
        )
        val byTitle = cards.agents.values.associateBy { it.title() }
        assertThat(byTitle.keys).containsExactly("Update quick action pills interaction and styling", "Cesium Revenue Strategy", "Codex-Poly-Bot Scaling")
        assertThat(byTitle.getValue("Cesium Revenue Strategy").text()).isEqualTo("Thinking\u2026")
        assertThat(byTitle.getValue("Codex-Poly-Bot Scaling").text()).isEqualTo("Running redis-cli LLEN catchup:queue")
        assertThat(byTitle.getValue("Codex-Poly-Bot Scaling").chip()).isEqualTo("Running")
        cards.agents.values.forEach { card ->
            assertThat(card.group).isEqualTo(LiveNotificationRenderer.GROUP_LIVE)
            assertThat(card.isSummary()).isFalse()
            assertThat(NotificationCompat.isRequestPromotedOngoing(card)).isTrue()
            assertThat(card.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
            assertThat(card.actionTitles()).containsExactly("Stop")
            assertThat(card.getLargeIcon()).isNotNull()
        }
        // Equal start times keep the incoming order; sort keys follow the roster so the shade does too.
        assertThat(cards.agents.values.map { it.sortKey }).containsExactly("000", "001", "002").inOrder()

        val summary = cards.summary
        assertThat(summary.title()).isEqualTo("3 agents running")
        assertThat(summary.text()).isEqualTo("Update quick action pills interaction and styling \u00B7 Cesium Revenue Strategy \u00B7 Codex-Poly-Bot Scaling")
        assertThat(summary.number).isEqualTo(3)
        assertThat(summary.isSummary()).isTrue()
        assertThat(summary.actionTitles()).isEmpty()
    }

    @Test
    fun `summary counts every running agent even when only eight are tracked`() {
        val tracked = (1..8).map { running("bc-$it", "Agent $it", activity = RunDigest.Activity.Thinking) }
        val cards = LiveNotificationRenderer.live(context, LiveActivityState(tracked, hasReconciled = true, runningCount = 12))

        assertThat(cards.agents).hasSize(8)
        assertThat(cards.summary.title()).isEqualTo("12 agents running")
        assertThat(cards.summary.text()).isEqualTo("Agent 1 \u00B7 Agent 2 \u00B7 Agent 3 \u00B7 +9 more")
        assertThat(cards.summary.number).isEqualTo(12)
    }

    @Test
    fun `a second running agent without a tracked stream still counts in the summary`() {
        val cards = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "Agent")), hasReconciled = true, runningCount = 2))

        assertThat(cards.agents).hasSize(1)
        assertThat(cards.summary.title()).isEqualTo("2 agents running")
        assertThat(cards.summary.text()).isEqualTo("Agent \u00B7 +1 more")
        assertThat(cards.summary.number).isEqualTo(2)

        // A count that lags behind the tracked list never under-reports, and no "+N more" appears.
        val stale = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "A"), running("bc-2", "B")), hasReconciled = true, runningCount = 1))
        assertThat(stale.summary.title()).isEqualTo("2 agents running")
        assertThat(stale.summary.text()).isEqualTo("A \u00B7 B")
        assertThat(stale.agents).hasSize(2)
    }

    @Test
    fun `a stopping agent sorts to the top of the group`() {
        val older = running("bc-1", "Older", activity = RunDigest.Activity.Thinking).copy(startedAtMillis = startedAt)
        val stopping = running("bc-2", "Stopping now", phase = LivePhase.Stopping).copy(startedAtMillis = startedAt + 60_000)
        val newer = running("bc-3", "Newer").copy(startedAtMillis = startedAt + 120_000)
        val cards = LiveNotificationRenderer.live(context, LiveActivityState(listOf(newer, older, stopping), hasReconciled = true))

        val bySortKey = cards.agents.values.sortedBy { it.sortKey }.map { it.title() }
        assertThat(bySortKey).containsExactly("Stopping now", "Older", "Newer").inOrder()
        assertThat(cards.summary.text()).isEqualTo("Stopping now \u00B7 Older \u00B7 Newer")
        assertThat(LiveNotificationRenderer.sortedForRoster(listOf(newer, older, stopping)).map { it.agentId })
            .containsExactly("bc-2", "bc-1", "bc-3")
            .inOrder()
    }

    @Test
    fun `live ids are stable per agent and never collide with the summary or a finished card`() {
        assertThat(LiveNotificationRenderer.liveId("bc-1")).isEqualTo(LiveNotificationRenderer.liveId("bc-1"))
        assertThat(LiveNotificationRenderer.liveId("bc-1")).isNotEqualTo(LiveNotificationRenderer.liveId("bc-2"))
        assertThat(LiveNotificationRenderer.liveId("bc-1")).isNotEqualTo(LiveNotificationRenderer.LIVE_ID)
        assertThat(LiveNotificationRenderer.liveId("bc-1")).isNotEqualTo(LiveNotificationRenderer.finishedId("bc-1"))
        // Tiles are dealt by id too, so a card keeps its hue from one update to the next.
        assertThat(LiveNotificationRenderer.tileHue("bc-1")).isEqualTo(LiveNotificationRenderer.tileHue("bc-1"))
    }

    @Test
    fun `a roster of up to six agents never shares a tile hue`() {
        // "bc-demo-0001" and "bc-demo-0010" hash to the same hue on their own; in one roster the second moves on.
        val roster = listOf(running("bc-demo-0001", "A"), running("bc-demo-0010", "B"), running("bc-demo-0003", "C"))
        val hues = LiveNotificationRenderer.tileHues(roster)
        assertThat(hues.values.toSet()).hasSize(3)
        assertThat(hues.getValue("bc-demo-0001")).isEqualTo(LiveNotificationRenderer.tileHue("bc-demo-0001"))

        val six = (1..6).map { running("agent-$it", "Agent $it") }
        assertThat(LiveNotificationRenderer.tileHues(six).values.toSet()).hasSize(6)
        // Alone, an agent wears its own hue; the roster only reassigns on a clash.
        assertThat(LiveNotificationRenderer.tileHues(listOf(running("bc-demo-0010", "B"))).getValue("bc-demo-0010"))
            .isEqualTo(LiveNotificationRenderer.tileHue("bc-demo-0010"))
    }

    @Test
    fun `journey progress walks one determinate bar`() {
        val editing = running("bc-1", "Agent")
        assertThat(LiveNotificationRenderer.journeyProgress(editing)).isEqualTo(62)
        assertThat(LiveNotificationRenderer.activityProgress(RunDigest.Activity.Thinking)).isEqualTo(28)
        assertThat(LiveNotificationRenderer.activityProgress(RunDigest.Activity.Writing)).isEqualTo(74)
        assertThat(LiveNotificationRenderer.journeyProgress(editing.copy(phase = LivePhase.Starting))).isEqualTo(10)
        assertThat(LiveNotificationRenderer.journeyProgress(editing.copy(phase = LivePhase.Stopping))).isEqualTo(92)

        val style = LiveNotificationRenderer.journeyStyle(context, editing)
        assertThat(style.progress).isEqualTo(62)
        assertThat(style.isProgressIndeterminate).isFalse()
        assertThat(style.isStyledByProgress).isTrue()
        assertThat(style.progressSegments).hasSize(1)
        assertThat(style.progressSegments.single().length).isEqualTo(100)
        assertThat(style.progressPoints).isEmpty()
        assertThat(style.progressTrackerIcon).isNotNull()
        assertThat(style.progressStartIcon).isNull()
        assertThat(style.progressEndIcon).isNotNull()
        assertThat(LiveNotificationRenderer.tile(context, LiveNotificationRenderer.tileHue("bc-1")).width).isAtLeast(128)
    }

    @Test
    fun `finished card carries the diff stats line, the reply, Review and View PR`() {
        val n = LiveNotificationRenderer.finished(context, finished())

        assertThat(n.title()).isEqualTo("Update quick action pills interaction and styling")
        assertThat(n.subText()).isEqualTo("Finished")
        assertThat(n.text()).isEqualTo("+80 \u2212230 \u00B7 3 Files")
        assertThat(n.bigText()).isEqualTo("+80 \u2212230 \u00B7 3 Files\nRestyled the quick action pills and tightened their press states.")
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
        assertThat(n.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isFalse()
        assertThat(NotificationCompat.getChannelId(n)).isEqualTo(LiveNotifications.CHANNEL_FINISHED)
        assertThat(n.actionTitles()).containsExactly("Review", "View PR").inOrder()
        assertThat(n.`when`).isEqualTo(startedAt + 185_000)
    }

    @Test
    fun `finished card falls back to duration and shows failures`() {
        val plain = LiveNotificationRenderer.finished(context, finished(digest = RunDigest(), prUrl = null))
        assertThat(plain.text()).isEqualTo("Worked 3m 5s")
        assertThat(plain.actionTitles()).containsExactly("Review")

        val failed = LiveNotificationRenderer.finished(context, finished(status = RunStatus.ERROR, digest = RunDigest()))
        assertThat(failed.subText()).isEqualTo("Failed")
    }

    @Test
    fun `finished card skips leading screenshots and recordings and never shows raw tags`() {
        val run = finished(digest = RunDigest(), prUrl = null).copy(
            summary = "<img alt=\"Proof\" src=\"/opt/cursor/artifacts/proof.png\" />\n\n" +
                "<video src=\"/opt/cursor/artifacts/demo.mp4\"></video>\n\n" +
                "Re-recorded the 7 affected screenshots; see ![before/after](/opt/cursor/artifacts/diff.png) for the diff.",
        )
        val n = LiveNotificationRenderer.finished(context, run)
        assertThat(n.bigText()).isEqualTo("Worked 3m 5s\nRe-recorded the 7 affected screenshots; see before/after for the diff.")

        val onlyMedia = LiveNotificationRenderer.finished(context, run.copy(summary = "<img src=\"/opt/cursor/artifacts/proof.png\" />"))
        assertThat(onlyMedia.bigText()).isEqualTo("Worked 3m 5s")

        // Nothing but a captioned image: the caption is the best line there is.
        val captioned = LiveNotificationRenderer.finished(context, run.copy(summary = "<img alt=\"Proof\" src=\"/opt/cursor/artifacts/proof.png\" />"))
        assertThat(captioned.bigText()).isEqualTo("Worked 3m 5s\nProof")
    }

    @Test
    fun `finished ids are stable per agent and distinct from the live id`() {
        assertThat(LiveNotificationRenderer.finishedId("bc-1")).isEqualTo(LiveNotificationRenderer.finishedId("bc-1"))
        assertThat(LiveNotificationRenderer.finishedId("bc-1")).isNotEqualTo(LiveNotificationRenderer.finishedId("bc-2"))
        assertThat(LiveNotificationRenderer.finishedId("bc-1")).isNotEqualTo(LiveNotificationRenderer.LIVE_ID)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LiveNotificationRendererApi36Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = LiveNotifications.ensureChannels(context)

    @Test
    fun `single running agent is a determinate ProgressStyle Live Update`() {
        val run = TrackedRun(
            agentId = "bc-1",
            runId = "run-bc-1",
            title = "Update quick action pills",
            status = RunStatus.RUNNING,
            phase = LivePhase.Running,
            startedAtMillis = 1_700_000_000_000L,
            digest = RunDigest(filesEdited = 1, activity = RunDigest.Activity("Editing", "Composer.kt")),
        )
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(run), hasReconciled = true)).agents.values.single()

        assertThat(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()).isEqualTo("Update quick action pills")
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).isEqualTo("Editing Composer.kt")
        assertThat(n.extras.getBoolean(Notification.EXTRA_COLORIZED)).isFalse()
        // ProgressStyle keeps the step in the collapsed row, so no verb rides in the header on Android 16.
        assertThat(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)).isNull()
        assertThat(NotificationCompat.getShortCriticalText(n)).isEqualTo("Editing")
        assertThat(n.getLargeIcon()).isNotNull()
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isTrue()
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        val style = LiveNotificationRenderer.journeyStyle(context, run)
        assertThat(style.progress).isEqualTo(62)
        assertThat(style.isProgressIndeterminate).isFalse()
        assertThat(style.progressSegments.map { it.length }).containsExactly(100)
        assertThat(style.progressPoints).isEmpty()
    }
}
