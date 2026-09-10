package com.cursorforandroid.notifications

import android.app.Notification
import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunDigest
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
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

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

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
    private fun Notification.rows() = bigText()?.lines().orEmpty()
    private fun Notification.actionTitles() = actions.orEmpty().map { it.title.toString() }
    private fun Notification.chip() = NotificationCompat.getShortCriticalText(this)

    /** A roster row without its bar cells and gap, e.g. `Cesium Revenue… · Thinking · 4m`. */
    private fun String.afterBar() = trimStart('\u25AE', '\u25AF', '\u2002')

    @Test
    fun `single running agent is one promoted card with the step, a chronometer and Stop`() {
        val run = running("bc-1", "Update quick action pills interaction and styling")
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(run), hasReconciled = true))

        assertThat(n.title()).isEqualTo("Update quick action pills interaction and styling")
        assertThat(n.text()).isEqualTo("Editing Composer.kt")
        assertThat(n.subText()).isEqualTo("Editing")
        assertThat(n.chip()).isEqualTo("Editing")
        assertThat(n.getLargeIcon()).isNotNull()
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
    }

    @Test
    fun `bare verbs get an ellipsis and stopping hides the Stop action`() {
        val thinking = running("bc-1", "Agent", activity = RunDigest.Activity.Thinking)
        val thinkingCard = LiveNotificationRenderer.live(context, LiveActivityState(listOf(thinking), true))
        assertThat(thinkingCard.text()).isEqualTo("Thinking\u2026")
        assertThat(thinkingCard.subText()).isEqualTo("Thinking")
        assertThat(thinkingCard.chip()).isEqualTo("Thinking")

        val starting = running("bc-1", "Agent", phase = LivePhase.Starting)
        val startingCard = LiveNotificationRenderer.live(context, LiveActivityState(listOf(starting), true))
        assertThat(startingCard.text()).isEqualTo("Starting\u2026")
        assertThat(startingCard.chip()).isEqualTo("Starting")

        val stopping = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "Agent", phase = LivePhase.Stopping)), true))
        assertThat(stopping.text()).isEqualTo("Stopping\u2026")
        assertThat(stopping.chip()).isEqualTo("Stopping")
        assertThat(stopping.actionTitles()).isEmpty()
    }

    @Test
    fun `several running agents are one promoted BigTextStyle roster with a row each`() {
        AppClock.nowMillis = { startedAt + 34 * 60_000 }
        val runs = listOf(
            running("bc-1", "Update quick action pills interaction and styling"),
            running("bc-2", "Cesium Revenue Strategy", activity = RunDigest.Activity.Thinking),
            running("bc-3", "Codex-Poly-Bot Scaling", activity = RunDigest.Activity("Running", "redis-cli LLEN catchup:queue")),
        )
        val n = LiveNotificationRenderer.live(context, LiveActivityState(runs, hasReconciled = true))

        assertThat(n.title()).isEqualTo("3 agents running")
        assertThat(n.extras.getString(Notification.EXTRA_TEMPLATE)).endsWith("BigTextStyle")
        assertThat(n.rows().map { it.afterBar() }).containsExactly(
            "Update quick ac\u2026 \u00B7 Editing \u00B7 34m",
            "Cesium Revenue\u2026 \u00B7 Thinking \u00B7 34m",
            "Codex-Poly-Bot\u2026 \u00B7 Running \u00B7 34m",
        ).inOrder()
        // Every row opens with a five-cell bar lit to the step: editing 3, thinking 1, running 3.
        assertThat(n.rows().map { it.substringBefore('\u2002') }).containsExactly(
            "\u25AE\u25AE\u25AE\u25AF\u25AF",
            "\u25AE\u25AF\u25AF\u25AF\u25AF",
            "\u25AE\u25AE\u25AE\u25AF\u25AF",
        ).inOrder()
        assertThat(n.text()).isEqualTo(n.rows().first())
        assertThat(n.chip()).isEqualTo("3")
        assertThat(n.number).isEqualTo(3)
        assertThat(n.getLargeIcon()).isNull()
        assertThat(n.extras.getBoolean(Notification.EXTRA_COLORIZED)).isFalse()
        assertThat(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)).isFalse()
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isTrue()
        assertThat(n.actionTitles()).isEmpty()
    }

    @Test
    fun `roster rows light the bar in the agent hue, amber while stopping, with the name in bold`() {
        val run = running("bc-1", "Agent")
        val row = LiveNotificationRenderer.rosterRow(context, run, hue = 0xFFB48EAD.toInt(), nowMillis = startedAt + 90_000) as Spanned
        val barColors = row.getSpans(0, 5, ForegroundColorSpan::class.java).sortedBy { row.getSpanStart(it) }
        // Editing sits at 62 of 100: three lit cells, two hollow.
        assertThat(barColors.map { row.getSpanStart(it) to row.getSpanEnd(it) }).containsExactly(0 to 3, 3 to 5).inOrder()
        assertThat(barColors.first().foregroundColor).isEqualTo(0xFFB48EAD.toInt())
        assertThat(barColors.last().foregroundColor).isEqualTo(0x66FFFFFF)
        val bold = row.getSpans(0, row.length, StyleSpan::class.java).single()
        assertThat(bold.style).isEqualTo(Typeface.BOLD)
        assertThat(row.subSequence(row.getSpanStart(bold), row.getSpanEnd(bold)).toString()).isEqualTo("Agent")
        assertThat(row.toString().afterBar()).isEqualTo("Agent \u00B7 Editing \u00B7 1m")

        val stopping = LiveNotificationRenderer.rosterRow(context, run.copy(phase = LivePhase.Stopping), hue = 0xFFB48EAD.toInt(), nowMillis = startedAt) as Spanned
        assertThat(stopping.toString().substringBefore('\u2002')).isEqualTo("\u25AE".repeat(5))
        val lit = stopping.getSpans(0, 5, ForegroundColorSpan::class.java).single()
        assertThat(lit.foregroundColor).isEqualTo(0xFFF1B467.toInt())
        assertThat(stopping.toString().afterBar()).isEqualTo("Agent \u00B7 Stopping \u00B7 now")

        // A two-word verb keeps only its first word: the row has no room for the subject.
        val delegating = LiveNotificationRenderer.rosterRow(context, running("bc-2", "B", activity = RunDigest.Activity("Delegating to", "3 subagents")), hue = 0, nowMillis = startedAt)
        assertThat(delegating.toString().afterBar()).isEqualTo("B \u00B7 Delegating \u00B7 now")
    }

    @Test
    fun `roster counts every running agent even when only eight are tracked`() {
        val tracked = (1..8).map { running("bc-$it", "Agent $it", activity = RunDigest.Activity.Thinking) }
        val n = LiveNotificationRenderer.live(context, LiveActivityState(tracked, hasReconciled = true, runningCount = 12))

        assertThat(n.title()).isEqualTo("12 agents running")
        assertThat(n.number).isEqualTo(12)
        assertThat(n.chip()).isEqualTo("12")
        assertThat(n.rows()).hasSize(9)
        assertThat(n.rows().take(8).map { it.afterBar().substringBefore(" \u00B7 ") }).containsExactlyElementsIn((1..8).map { "Agent $it" }).inOrder()
        assertThat(n.rows().last()).isEqualTo("+4 more")
    }

    @Test
    fun `a second running agent without a tracked stream still makes it a roster`() {
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "Agent")), hasReconciled = true, runningCount = 2))

        assertThat(n.title()).isEqualTo("2 agents running")
        assertThat(n.number).isEqualTo(2)
        assertThat(n.rows()).hasSize(2)
        assertThat(n.rows().last()).isEqualTo("+1 more")
        assertThat(n.actionTitles()).isEmpty()

        // A count that lags behind the tracked list never under-reports, and no "+N more" row appears.
        val stale = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "A"), running("bc-2", "B")), hasReconciled = true, runningCount = 1))
        assertThat(stale.title()).isEqualTo("2 agents running")
        assertThat(stale.rows()).hasSize(2)
        assertThat(stale.rows().none { it.contains("more") }).isTrue()
    }

    @Test
    fun `roster pins a stopping agent above longer-running ones`() {
        val older = running("bc-1", "Older", activity = RunDigest.Activity.Thinking).copy(startedAtMillis = startedAt)
        val stopping = running("bc-2", "Stopping now", phase = LivePhase.Stopping).copy(startedAtMillis = startedAt + 60_000)
        val newer = running("bc-3", "Newer").copy(startedAtMillis = startedAt + 120_000)
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(newer, older, stopping), hasReconciled = true))

        assertThat(n.rows().map { it.afterBar().substringBefore(" \u00B7 ") }).containsExactly("Stopping now", "Older", "Newer").inOrder()
        assertThat(LiveNotificationRenderer.sortedForRoster(listOf(newer, older, stopping)).map { it.agentId })
            .containsExactly("bc-2", "bc-1", "bc-3")
            .inOrder()
    }

    @Test
    fun `an agent keeps its hue from one update to the next`() {
        assertThat(LiveNotificationRenderer.tileHue("bc-1")).isEqualTo(LiveNotificationRenderer.tileHue("bc-1"))
        assertThat(LiveNotificationRenderer.tile(context, LiveNotificationRenderer.tileHue("bc-1")).width).isAtLeast(128)
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
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(run), hasReconciled = true))

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
