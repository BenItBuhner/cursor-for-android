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
    private fun Notification.rosterLines() = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
    private fun Notification.actionTitles() = actions.orEmpty().map { it.title.toString() }
    private fun Notification.chip() = NotificationCompat.getShortCriticalText(this)

    @Test
    fun `single running agent is a colorized scoreboard with the step as the title`() {
        val run = running("bc-1", "Update quick action pills interaction and styling")
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(run), hasReconciled = true))

        assertThat(n.title()).isEqualTo("Editing Composer.kt")
        assertThat(n.text()).isEqualTo("Update quick action pills interaction and styling")
        assertThat(n.subText()).isNull()
        assertThat(n.chip()).isEqualTo("Editing")
        assertThat(n.getLargeIcon()).isNotNull()
        assertThat(n.extras.getBoolean(Notification.EXTRA_COLORIZED)).isTrue()
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
        assertThat(thinkingCard.title()).isEqualTo("Thinking\u2026")
        assertThat(thinkingCard.text()).isEqualTo("Agent")
        assertThat(thinkingCard.chip()).isEqualTo("Thinking")

        val starting = running("bc-1", "Agent", phase = LivePhase.Starting)
        val startingCard = LiveNotificationRenderer.live(context, LiveActivityState(listOf(starting), true))
        assertThat(startingCard.title()).isEqualTo("Starting\u2026")
        assertThat(startingCard.chip()).isEqualTo("Starting")

        val stopping = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "Agent", phase = LivePhase.Stopping)), true))
        assertThat(stopping.title()).isEqualTo("Stopping\u2026")
        assertThat(stopping.chip()).isEqualTo("Stopping")
        assertThat(stopping.actionTitles()).isEmpty()
    }

    @Test
    fun `several running agents stay one ProgressStyle card headed by the lead step`() {
        val runs = listOf(
            running("bc-1", "Update quick action pills interaction and styling"),
            running("bc-2", "Cesium Revenue Strategy", activity = RunDigest.Activity.Thinking),
            running("bc-3", "Codex-Poly-Bot Scaling", activity = RunDigest.Activity("Running", "redis-cli LLEN catchup:queue")),
        )
        val n = LiveNotificationRenderer.live(context, LiveActivityState(runs, hasReconciled = true))

        assertThat(n.title()).isEqualTo("Editing Composer.kt")
        assertThat(n.text()).isEqualTo("3 agents \u00B7 Thinking \u00B7 Running")
        assertThat(n.rosterLines()).isEmpty()
        assertThat(n.chip()).isEqualTo("3")
        assertThat(n.number).isEqualTo(3)
        assertThat(n.getLargeIcon()).isNotNull()
        assertThat(n.extras.getBoolean(Notification.EXTRA_COLORIZED)).isTrue()
        assertThat(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)).isTrue()
        assertThat(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)).isFalse()
        assertThat(n.extras.getInt(Notification.EXTRA_PROGRESS)).isEqualTo(62)
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isTrue()
        assertThat(n.actionTitles()).isEmpty()
    }

    @Test
    fun `headline counts every running agent even when only eight are tracked`() {
        val tracked = (1..8).map { running("bc-$it", "Agent $it", activity = RunDigest.Activity.Thinking) }
        val n = LiveNotificationRenderer.live(context, LiveActivityState(tracked, hasReconciled = true, runningCount = 12))

        assertThat(n.title()).isEqualTo("Thinking\u2026")
        assertThat(n.text()).isEqualTo("12 agents \u00B7 +4 more")
        assertThat(n.number).isEqualTo(12)
        assertThat(n.chip()).isEqualTo("12")
        assertThat(n.rosterLines()).isEmpty()
    }

    @Test
    fun `a second running agent without a tracked stream still makes it a fleet card`() {
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "Agent")), hasReconciled = true, runningCount = 2))

        assertThat(n.title()).isEqualTo("Editing Composer.kt")
        assertThat(n.text()).isEqualTo("2 agents \u00B7 +1 more")
        assertThat(n.number).isEqualTo(2)
        assertThat(n.chip()).isEqualTo("2")
        assertThat(n.rosterLines()).isEmpty()
        assertThat(n.actionTitles()).isEmpty()

        // A count that lags behind the tracked list never under-reports, and no "+N more" summary appears.
        val stale = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "A"), running("bc-2", "B")), hasReconciled = true, runningCount = 1))
        assertThat(stale.title()).isEqualTo("Editing Composer.kt")
        assertThat(stale.text()).isEqualTo("2 agents")
        assertThat(stale.number).isEqualTo(2)
    }

    @Test
    fun `fleet pins a stopping agent as the lead`() {
        val older = running("bc-1", "Older", activity = RunDigest.Activity.Thinking).copy(startedAtMillis = startedAt)
        val stopping = running("bc-2", "Stopping now", phase = LivePhase.Stopping).copy(startedAtMillis = startedAt + 60_000)
        val newer = running("bc-3", "Newer").copy(startedAtMillis = startedAt + 120_000)
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(newer, older, stopping), hasReconciled = true))

        assertThat(n.title()).isEqualTo("Stopping\u2026")
        assertThat(n.text()).isEqualTo("3 agents \u00B7 Thinking \u00B7 Editing")
        assertThat(LiveNotificationRenderer.sortedForRoster(listOf(newer, older, stopping)).map { it.agentId })
            .containsExactly("bc-2", "bc-1", "bc-3")
            .inOrder()
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
        assertThat(LiveNotificationRenderer.poster(context).width).isAtLeast(128)
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

        assertThat(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()).isEqualTo("Editing Composer.kt")
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).isEqualTo("Update quick action pills")
        assertThat(n.extras.getBoolean(Notification.EXTRA_COLORIZED)).isTrue()
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
