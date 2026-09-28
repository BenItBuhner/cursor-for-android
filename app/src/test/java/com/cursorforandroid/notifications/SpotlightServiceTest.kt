package com.cursorforandroid.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.appGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Runs the real Spotlight service against the demo backend: the Cesium chat's scripted run reads, greps and sends
 * out three explore subagents, then finishes, so the Spotlight must show the steps and subagents live, hand over
 * to the finished card, and take itself down.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SpotlightServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.bigText() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    private fun awaitOnMain(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private fun demo(): AppGraph {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val graph = app.appGraph
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        return graph
    }

    private fun AppGraph.cesium() = agents.state.value.agents.first { it.name == "Cesium Revenue Strategy" }

    private fun spotlightService(): ServiceController<SpotlightService> =
        Robolectric.buildService(SpotlightService::class.java).create().startCommand(0, 1)

    @After
    fun tearDown() = app.appGraph.spotlight.stop()

    @Test
    fun `a spotlighted chat shows its steps and subagents live, then hands over to the finished card`() {
        val graph = demo()
        val cesium = graph.cesium()
        assertThat(cesium.isRunning).isTrue()
        graph.spotlight.spotlight(cesium)
        val controller = spotlightService()
        val service = controller.get()
        assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(SpotlightRenderer.SPOTLIGHT_ID)

        awaitOnMain(10_000) { manager.getNotification(SpotlightRenderer.SPOTLIGHT_ID)?.title() == "Cesium Revenue Strategy" }
        val first = manager.getNotification(SpotlightRenderer.SPOTLIGHT_ID)
        assertThat(first.channelId).isEqualTo(LiveNotifications.CHANNEL_SPOTLIGHT)
        assertThat(first.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)

        // The scripted run delegates to three explore subagents; the expanded card lists them under the step.
        awaitOnMain(60_000) { manager.getNotification(SpotlightRenderer.SPOTLIGHT_ID)?.bigText()?.contains("\u2022 Survey") == true }
        val delegating = manager.getNotification(SpotlightRenderer.SPOTLIGHT_ID)
        assertThat(delegating.bigText()).contains("tool call")
        assertThat(graph.spotlight.covered.value).containsExactly(cesium.id)

        // The run finishes: the Spotlight is taken down, the finished card takes over, and the service goes.
        awaitOnMain(90_000) { graph.spotlight.target.value == null }
        awaitOnMain(10_000) { shadowOf(service).isForegroundStopped && shadowOf(service).isStoppedBySelf }
        assertThat(shadowOf(service).notificationShouldRemoved).isTrue()
        awaitOnMain(5_000) { manager.getNotification(LiveNotificationRenderer.finishedId(cesium.id)) != null }
        val card = manager.getNotification(LiveNotificationRenderer.finishedId(cesium.id))
        assertThat(card.title()).isEqualTo("Cesium Revenue Strategy")
        assertThat(card.channelId).isEqualTo(LiveNotifications.CHANNEL_FINISHED)
        assertThat(graph.spotlight.covered.value).isEmpty()
        controller.destroy()
    }

    @Test
    fun `Stop Spotlight from the notification ends it at once while the run goes on`() {
        val graph = demo()
        val cesium = graph.cesium()
        graph.spotlight.spotlight(cesium)
        val controller = spotlightService()
        val service = controller.get()
        awaitOnMain(10_000) { manager.getNotification(SpotlightRenderer.SPOTLIGHT_ID)?.title() == "Cesium Revenue Strategy" }

        controller.withIntent(SpotlightService.stopIntent(app)).startCommand(0, 2)
        awaitOnMain(5_000) { shadowOf(service).isForegroundStopped && shadowOf(service).isStoppedBySelf }
        assertThat(graph.spotlight.target.value).isNull()
        assertThat(graph.spotlight.covered.value).isEmpty()
        assertThat(graph.cesium().isRunning).isTrue()
        assertThat(manager.getNotification(LiveNotificationRenderer.finishedId(cesium.id))).isNull()
        controller.destroy()
    }

    @Test
    fun `the live roster leaves the spotlighted chat to its own card`() {
        val graph = demo()
        graph.spotlight.spotlight(graph.cesium())
        val spotlight = spotlightService()
        val live = Robolectric.buildService(LiveNotificationService::class.java).create().startCommand(0, 1)

        awaitOnMain(10_000) {
            manager.getNotification(LiveNotificationRenderer.LIVE_ID)?.let { it.title() == "2 agents running" && it.bigText()?.lines()?.size == 2 } == true
        }
        val roster = manager.getNotification(LiveNotificationRenderer.LIVE_ID)
        assertThat(roster.bigText()).doesNotContain("Cesium")
        assertThat(manager.getNotification(SpotlightRenderer.SPOTLIGHT_ID).title()).isEqualTo("Cesium Revenue Strategy")

        // Out of the Spotlight, it is back on the roster.
        graph.spotlight.stop()
        awaitOnMain(10_000) { manager.getNotification(LiveNotificationRenderer.LIVE_ID)?.title() == "3 agents running" }
        live.destroy()
        spotlight.destroy()
    }
}
