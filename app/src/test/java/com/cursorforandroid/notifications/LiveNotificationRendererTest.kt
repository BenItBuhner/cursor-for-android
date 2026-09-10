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
    private fun Notification.summaryText() = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
    private fun Notification.actionTitles() = actions.orEmpty().map { it.title.toString() }

    @Test
    fun `single running agent is an ongoing promoted card with the step, a chronometer and Stop`() {
        val run = running("bc-1", "Update quick action pills interaction and styling")
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(run), hasReconciled = true))

        assertThat(n.title()).isEqualTo("Update quick action pills interaction and styling")
        assertThat(n.text()).isEqualTo("Editing Composer.kt")
        assertThat(n.subText()).isEqualTo("Running")
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
        assertThat(LiveNotificationRenderer.live(context, LiveActivityState(listOf(thinking), true)).text()).isEqualTo("Thinking\u2026")

        val starting = running("bc-1", "Agent", phase = LivePhase.Starting)
        assertThat(LiveNotificationRenderer.live(context, LiveActivityState(listOf(starting), true)).text()).isEqualTo("Starting\u2026")

        val stopping = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "Agent", phase = LivePhase.Stopping)), true))
        assertThat(stopping.subText()).isEqualTo("Stopping")
        assertThat(stopping.actionTitles()).isEmpty()
    }

    @Test
    fun `several running agents condense to one InboxStyle row each`() {
        val runs = listOf(
            running("bc-1", "Update quick action pills interaction and styling"),
            running("bc-2", "Cesium Revenue Strategy", activity = RunDigest.Activity.Thinking),
            running("bc-3", "Codex-Poly-Bot Scaling", activity = RunDigest.Activity("Running", "redis-cli LLEN catchup:queue")),
        )
        val n = LiveNotificationRenderer.live(context, LiveActivityState(runs, hasReconciled = true))

        assertThat(n.title()).isEqualTo("3 agents running")
        assertThat(n.rosterLines()).containsExactly(
            "Update quick action pills interacti\u2026 \u2014 Editing Composer.kt",
            "Cesium Revenue Strategy \u2014 Thinking",
            "Codex-Poly-Bot Scaling \u2014 Running redis-cli LLEN catc\u2026",
        ).inOrder()
        assertThat(n.summaryText()).isNull()
        assertThat(n.text()).isEqualTo(n.rosterLines().first())
        assertThat(n.number).isEqualTo(3)
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isTrue()
        assertThat(n.actionTitles()).isEmpty()
    }

    @Test
    fun `headline counts every running agent even when only eight are tracked`() {
        val tracked = (1..8).map { running("bc-$it", "Agent $it", activity = RunDigest.Activity.Thinking) }
        val n = LiveNotificationRenderer.live(context, LiveActivityState(tracked, hasReconciled = true, runningCount = 12))

        assertThat(n.title()).isEqualTo("12 agents running")
        assertThat(n.number).isEqualTo(12)
        assertThat(n.rosterLines()).containsExactlyElementsIn((1..8).map { "Agent $it \u2014 Thinking" }).inOrder()
        assertThat(n.summaryText()).isEqualTo("+4 more")
        assertThat(n.text()).isEqualTo(n.rosterLines().first())
    }

    @Test
    fun `a second running agent without a tracked stream still makes it a condensed card`() {
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "Agent")), hasReconciled = true, runningCount = 2))

        assertThat(n.title()).isEqualTo("2 agents running")
        assertThat(n.number).isEqualTo(2)
        assertThat(n.rosterLines()).containsExactly("Agent \u2014 Editing Composer.kt")
        assertThat(n.summaryText()).isEqualTo("+1 more")
        assertThat(n.actionTitles()).isEmpty()

        // A count that lags behind the tracked list never under-reports, and no "+N more" summary appears.
        val stale = LiveNotificationRenderer.live(context, LiveActivityState(listOf(running("bc-1", "A"), running("bc-2", "B")), hasReconciled = true, runningCount = 1))
        assertThat(stale.title()).isEqualTo("2 agents running")
        assertThat(stale.rosterLines()).hasSize(2)
        assertThat(stale.summaryText()).isNull()
    }

    @Test
    fun `roster pins a stopping agent above longer-running ones`() {
        val older = running("bc-1", "Older", activity = RunDigest.Activity.Thinking).copy(startedAtMillis = startedAt)
        val stopping = running("bc-2", "Stopping now", phase = LivePhase.Stopping).copy(startedAtMillis = startedAt + 60_000)
        val newer = running("bc-3", "Newer").copy(startedAtMillis = startedAt + 120_000)
        val n = LiveNotificationRenderer.live(context, LiveActivityState(listOf(newer, older, stopping), hasReconciled = true))

        assertThat(n.rosterLines()).containsExactly(
            "Stopping now \u2014 Stopping",
            "Older \u2014 Thinking",
            "Newer \u2014 Editing Composer.kt",
        ).inOrder()
    }

    @Test
    fun `journey progress walks the start work wrap-up bar`() {
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
        assertThat(style.progressSegments).hasSize(3)
        assertThat(style.progressPoints).hasSize(2)
        assertThat(style.progressTrackerIcon).isNotNull()
        assertThat(style.progressStartIcon).isNotNull()
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
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isTrue()
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        val style = LiveNotificationRenderer.journeyStyle(context, run)
        assertThat(style.progress).isEqualTo(62)
        assertThat(style.isProgressIndeterminate).isFalse()
        assertThat(style.progressSegments.map { it.length }).containsExactly(20, 60, 20).inOrder()
    }
}
