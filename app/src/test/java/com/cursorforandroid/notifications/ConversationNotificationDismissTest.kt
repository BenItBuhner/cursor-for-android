package com.cursorforandroid.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.ui.conversation.ConversationViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Opening a finished chat from the list must take its shade card down. Tapping the card itself already auto-cancels;
 * walking in from inside the app used to leave the announcement up while the transcript showed the same reply.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConversationNotificationDismissTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Test
    fun `cancelFinished removes only that agent's card`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        LiveNotifications.ensureChannels(app)
        LiveNotifications.post(app, LiveNotificationRenderer.finishedId(OPENED), finished(OPENED, "Opened chat"))
        LiveNotifications.post(app, LiveNotificationRenderer.finishedId(OTHER), finished(OTHER, "Other chat"))

        LiveNotifications.cancelFinished(app, OPENED)

        assertThat(manager.getNotification(LiveNotificationRenderer.finishedId(OPENED))).isNull()
        assertThat(manager.getNotification(LiveNotificationRenderer.finishedId(OTHER))).isNotNull()
    }

    @Test
    fun `opening a chat from inside the app dismisses its finished notification and leaves the others`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        LiveNotifications.ensureChannels(app)
        val graph = AppGraph(app)
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        LiveNotifications.post(app, LiveNotificationRenderer.finishedId(IDLE), finished(IDLE, "Revenue Scaling Pipeline Research"))
        LiveNotifications.post(app, LiveNotificationRenderer.finishedId(OTHER_IDLE), finished(OTHER_IDLE, "Latest release process"))
        assertThat(manager.getNotification(LiveNotificationRenderer.finishedId(IDLE))).isNotNull()
        assertThat(manager.getNotification(LiveNotificationRenderer.finishedId(OTHER_IDLE))).isNotNull()

        ConversationViewModel(graph, IDLE)

        assertThat(manager.getNotification(LiveNotificationRenderer.finishedId(IDLE))).isNull()
        assertThat(manager.getNotification(LiveNotificationRenderer.finishedId(OTHER_IDLE))).isNotNull()
    }

    private fun finished(agentId: String, title: String) = LiveNotificationRenderer.finished(
        app,
        TrackedRun(
            agentId = agentId,
            runId = "run-$agentId",
            title = title,
            status = RunStatus.FINISHED,
            phase = LivePhase.Finished,
            startedAtMillis = 1_700_000_000_000L,
            durationMs = 60_000,
            summary = "Done.",
            finishedAtMillis = 1_700_000_060_000L,
        ),
    )

    private companion object {
        const val OPENED = "bc-1"
        const val OTHER = "bc-2"
        /** Demo: finished, so opening it is the unread-row path. */
        const val IDLE = "bc-demo-0002"
        const val OTHER_IDLE = "bc-demo-0007"
    }
}
