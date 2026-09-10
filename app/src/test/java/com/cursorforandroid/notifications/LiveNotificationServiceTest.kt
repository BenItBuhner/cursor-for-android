package com.cursorforandroid.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.appGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Runs the real service against the demo backend: two agents start out running, so the live notification must
 * condense them, then each finish must produce its own card and the ongoing notification must go away.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveNotificationServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /** The per-agent cards up right now: everything on the live channel that is not the summary. */
    private fun liveCards(): List<Notification> = manager.allNotifications.filter {
        it.channelId == LiveNotifications.CHANNEL_LIVE && it.flags and Notification.FLAG_GROUP_SUMMARY == 0
    }

    /** Pumps the (paused) main looper so the service's Main-dispatched collectors run, until [condition] holds. */
    private fun awaitOnMain(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `demo agents produce a condensed live notification, then finished cards, then stop the service`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val graph = app.appGraph
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        assertThat(graph.agents.state.value.agents.count { it.isRunning }).isEqualTo(3)

        assertThat(LiveNotificationService.active.value).isFalse()
        assertThat(FinishWatchdogJobService.isArmed(app)).isFalse()
        val controller = Robolectric.buildService(LiveNotificationService::class.java).create().startCommand(0, 1)
        val service = controller.get()
        val foregroundNotification = shadowOf(service).lastForegroundNotification
        assertThat(foregroundNotification).isNotNull()
        assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(LiveNotificationRenderer.LIVE_ID)
        // Up, and says so; the watchdog is armed in case this process does not live to post the finished cards.
        assertThat(LiveNotificationService.active.value).isTrue()
        assertThat(FinishWatchdogJobService.isArmed(app)).isTrue()
        val armed = checkNotNull(app.getSystemService(JobScheduler::class.java).getPendingJob(FinishWatchdogJobService.JOB_ID))
        assertThat(armed.isPersisted).isTrue()
        assertThat(armed.minLatencyMillis).isEqualTo(FinishWatchdogJobService.WHILE_SERVICE_ALIVE_MS)

        // The summary counts all three as soon as the list is reconciled; the third card follows once every tracker
        // has reported, so wait for the steady state rather than the summary alone.
        awaitOnMain(10_000) {
            manager.getNotification(LiveNotificationRenderer.LIVE_ID)?.title() == "3 agents running" && liveCards().size == 3
        }
        val summary = manager.getNotification(LiveNotificationRenderer.LIVE_ID)
        assertThat(summary.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(summary.flags and Notification.FLAG_GROUP_SUMMARY).isNotEqualTo(0)
        assertThat(summary.group).isEqualTo(LiveNotificationRenderer.GROUP_LIVE)
        assertThat(summary.number).isEqualTo(3)
        val cards = liveCards()
        assertThat(cards.map { it.title() }).containsExactly("Codex-Poly-Bot Scaling", "Cesium Revenue Strategy", "Hyper-realistic human limbs")
        cards.forEach { card ->
            assertThat(card.group).isEqualTo(LiveNotificationRenderer.GROUP_LIVE)
            assertThat(card.flags and Notification.FLAG_GROUP_SUMMARY).isEqualTo(0)
            assertThat(card.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
            assertThat(card.actions.map { it.title.toString() }).contains("Stop")
        }

        // The scripted runs finish on their own; each gets a card while the live notification shrinks, then goes.
        awaitOnMain(90_000) { manager.allNotifications.count { it.channelId == LiveNotifications.CHANNEL_FINISHED } == 3 }
        val finished = manager.allNotifications.filter { it.channelId == LiveNotifications.CHANNEL_FINISHED }
        assertThat(finished.map { it.title() }).containsExactly("Codex-Poly-Bot Scaling", "Cesium Revenue Strategy", "Hyper-realistic human limbs")
        finished.forEach { card ->
            assertThat(card.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
            assertThat(card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()).isEqualTo("Finished")
            assertThat(card.actions.map { it.title.toString() }).contains("Review")
            assertThat(card.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).contains("Worked")
        }
        // The limbs script edits one file and pushes a branch; the digest and the row both see it.
        val limbs = finished.first { it.title() == "Hyper-realistic human limbs" }
        assertThat(limbs.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).startsWith("1 File \u00B7 Worked")
        assertThat(graph.agents.state.value.agents.first { it.name == "Hyper-realistic human limbs" }.branchName).isEqualTo("cursor/limb-rigging-3e4f")
        assertThat(graph.agents.state.value.agents.count { it.isRunning }).isEqualTo(0)

        // Idle grace elapses on the main looper clock; the service stops itself and removes the live notification. With
        // nothing left running there is nothing for the watchdog to watch either. The per-agent cards went one by one
        // as their runs finished, and none is left behind.
        awaitOnMain(10_000) { shadowOf(service).isForegroundStopped && shadowOf(service).isStoppedBySelf }
        assertThat(shadowOf(service).notificationShouldRemoved).isTrue()
        assertThat(liveCards()).isEmpty()
        assertThat(graph.runMonitor.isRunning).isFalse()
        assertThat(FinishWatchdogJobService.isArmed(app)).isFalse()
        controller.destroy()
        assertThat(LiveNotificationService.active.value).isFalse()
    }

    @Test
    fun `a data-sync timeout hands the running agents to the watchdog`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val graph = app.appGraph
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        val controller = Robolectric.buildService(LiveNotificationService::class.java).create().startCommand(0, 1)
        val service = controller.get()
        awaitOnMain(10_000) { manager.getNotification(LiveNotificationRenderer.LIVE_ID)?.title() == "3 agents running" && liveCards().size == 3 }

        // Android 15 ends a dataSync service six hours after the app was last in front; the runs keep going in the cloud.
        service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        awaitOnMain(5_000) { shadowOf(service).isForegroundStopped && shadowOf(service).isStoppedBySelf }
        assertThat(graph.runMonitor.isRunning).isFalse()
        // The agent cards leave with the summary; the shade is not left holding three frozen chronometers.
        assertThat(liveCards()).isEmpty()
        // The live notification is gone, but the watchdog is armed to announce the finishes, soon.
        val armed = checkNotNull(app.getSystemService(JobScheduler::class.java).getPendingJob(FinishWatchdogJobService.JOB_ID))
        assertThat(armed.minLatencyMillis).isEqualTo(FinishWatchdogJobService.AFTER_SERVICE_LOSS_MS)
        controller.destroy()
        assertThat(LiveNotificationService.active.value).isFalse()
        FinishWatchdogJobService.disarm(app)
    }
}
