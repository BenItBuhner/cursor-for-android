package com.cursorforandroid

import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.update.UpdateJobService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

    @After
    fun restoreWait() {
        DeferredStartup.settleMs = settle
    }

    @Test
    fun `creating the activity reaches neither the notification manager nor the job scheduler`() {
        runBlocking { app.appGraph.prefs.setAutoUpdate(true) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        idle()

        assertThat(channels).isEmpty()
        assertThat(jobs).isEmpty()

        activity.start().resume()
        idle()

        assertThat(channels).isNotEmpty()
        assertThat(UpdateJobService.isScheduled(app)).isTrue()
    }

    private fun idle() {
        val deadline = System.nanoTime() + 20_000_000_000L
        repeat(20) {
            if (System.nanoTime() > deadline) return
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }
}
