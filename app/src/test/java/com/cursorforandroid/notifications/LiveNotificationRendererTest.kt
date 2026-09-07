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
        assertThat(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)).isTrue()
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
    fun `several running agents condense to one line each`() {
        val runs = listOf(
            running("bc-1", "Update quick action pills interaction and styling"),
            running("bc-2", "Cesium Revenue Strategy", activity = RunDigest.Activity.Thinking),
            running("bc-3", "Codex-Poly-Bot Scaling", activity = RunDigest.Activity("Running", "redis-cli LLEN catchup:queue")),
        )
        val n = LiveNotificationRenderer.live(context, LiveActivityState(runs, hasReconciled = true))

        assertThat(n.title()).isEqualTo("3 agents running")
        val lines = n.bigText()!!.lines()
        assertThat(lines).containsExactly(
            "\u2022 Update quick action pills interac\u2026 \u00B7 Editing",
            "\u2022 Cesium Revenue Strategy \u00B7 Thinking",
            "\u2022 Codex-Poly-Bot Scaling \u00B7 Running",
        ).inOrder()
        assertThat(n.text()).isEqualTo(lines.first())
        assertThat(n.number).isEqualTo(3)
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(NotificationCompat.isRequestPromotedOngoing(n)).isTrue()
        assertThat(n.actionTitles()).isEmpty()
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
    fun `finished ids are stable per agent and distinct from the live id`() {
        assertThat(LiveNotificationRenderer.finishedId("bc-1")).isEqualTo(LiveNotificationRenderer.finishedId("bc-1"))
        assertThat(LiveNotificationRenderer.finishedId("bc-1")).isNotEqualTo(LiveNotificationRenderer.finishedId("bc-2"))
        assertThat(LiveNotificationRenderer.finishedId("bc-1")).isNotEqualTo(LiveNotificationRenderer.LIVE_ID)
    }
}
