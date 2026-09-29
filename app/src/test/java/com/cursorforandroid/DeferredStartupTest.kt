package com.cursorforandroid

import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.update.UpdateJobService
import com.cursorforandroid.util.UiDispatcherRearm
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/** The wiring that reaches out to the system waits for the app to be on screen instead of joining the launch. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeferredStartupTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settle = DeferredStartup.settleMs

    private val channels get() = app.getSystemService(NotificationManager::class.java).notificationChannels
    private val jobs get() = app.getSystemService(JobScheduler::class.java).allPendingJobs

    @Before
    fun noWait() {
        DeferredStartup.settleMs = 0
    }

    private var activity: ActivityController<MainActivity>? = null

    /** Restores the wait, and tears the activity down: left resumed, its composition would keep collecting the graph's settings on the shared main looper for every test after this one. */
    @After
    fun restoreWait() {
        DeferredStartup.settleMs = settle
        activity?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `creating the activity reaches neither the notification manager nor the job scheduler`() {
        runBlocking { app.appGraph.prefs.setAutoUpdate(true) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().also { this.activity = it }
        idle()

        assertThat(channels).isEmpty()
        assertThat(jobs).isEmpty()

        activity.start().resume()
        idle()

        assertThat(channels).isNotEmpty()
        assertThat(UpdateJobService.isScheduled(app)).isTrue()
    }

    @Test
    fun `the shell's first frames build neither the updater, the release notes nor the image loader's client`() {
        DeferredStartup.settleMs = 10 * 60_000
        val graph = app.appGraph
        runBlocking { graph.prefs.setDemoMode(true) }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        idleUntil { graph.session.state.value is SessionState.SignedIn && "media" in graph.builtParts() }

        // The shell is up and its image loader provided; the updater and the release notes wait for the deferred startup.
        assertThat(graph.builtParts()).containsNoneOf("releases", "updates", "whatsNew", "storeFiles")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DeferredStartup.settleMs))
        idleUntil { graph.builtParts().containsAll(listOf("releases", "updates")) }
    }

    /**
     * Frame by frame on the main looper's clock until [condition], for at most [BUDGET] of that clock - far short of the
     * settle, so nothing deferred is reached by waiting. Mended each frame: this test drives the looper itself, where
     * Espresso's idling resources are never asked (see [UiDispatcherRearm]).
     */
    private fun idleUntil(condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat((BUDGET.toMillis() / FRAME.toMillis()).toInt()) {
            if (condition()) return
            UiDispatcherRearm.mend()
            looper.idleFor(FRAME)
            // A frame's worth of the disk's threads for each frame of the clock's.
            Thread.sleep(1)
        }
        check(condition()) { "not settled after $BUDGET of the main looper's clock; built: ${app.appGraph.builtParts()}" }
    }

    private fun idle() {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(20) {
            UiDispatcherRearm.mend()
            looper.idle()
            Thread.sleep(5)
        }
    }

    private companion object {
        val FRAME: Duration = Duration.ofMillis(16)
        val BUDGET: Duration = Duration.ofMinutes(1)
    }
}
