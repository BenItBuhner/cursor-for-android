package com.cursorforandroid

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.UsageReset
import com.cursorforandroid.notifications.UsageNotifications
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The Android-side plumbing around usage: the infrequent job and the always-on reset notification. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UsageGlueTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the periodic job is scheduled once, with the constraints the poll needs, and cancelled`() {
        val scheduler = app.getSystemService(JobScheduler::class.java)
        assertThat(UsageJobService.isScheduled(app)).isFalse()

        UsageJobService.schedule(app)
        UsageJobService.schedule(app)
        val job = checkNotNull(scheduler.getPendingJob(UsageJobService.JOB_ID))
        assertThat(scheduler.allPendingJobs.filter { it.id == UsageJobService.JOB_ID }).hasSize(1)
        assertThat(job.isPeriodic).isTrue()
        assertThat(job.intervalMillis).isEqualTo(UsageJobService.PERIOD_MS)
        assertThat(job.flexMillis).isEqualTo(UsageJobService.FLEX_MS)
        assertThat(job.flexMillis).isLessThan(job.intervalMillis)
        assertThat(job.networkType).isEqualTo(JobInfo.NETWORK_TYPE_ANY)
        assertThat(job.isPersisted).isTrue()
        assertThat(job.isRequireBatteryNotLow).isTrue()
        assertThat(job.service.className).isEqualTo(UsageJobService::class.java.name)
        assertThat(UsageJobService.PERIOD_MS).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(UsageJobService.FOREGROUND_MS).isEqualTo(TimeUnit.MINUTES.toMillis(5))

        UsageJobService.cancel(app)
        assertThat(UsageJobService.isScheduled(app)).isFalse()
    }

    @Test
    fun `the scheduled run happens off the main thread the scheduler starts the job on`() {
        val service = Robolectric.buildService(UsageJobService::class.java).create().get()
        val finishedOn = AtomicReference<Thread>()
        val finished = CountDownLatch(1)

        service.startRun {
            finishedOn.set(Thread.currentThread())
            finished.countDown()
        }

        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(finishedOn.get()).isNotSameInstanceAs(Looper.getMainLooper().thread)
    }

    @Test
    fun `a remaining-percent reset always posts, and the card opens Settings`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UsageNotifications.ensureChannel(app)
        assertThat(UsageNotifications.postReset(app, UsageReset(included = true, api = false))).isTrue()

        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        val notification = manager.getNotification(UsageNotifications.RESET_ID)
        assertThat(notification).isNotNull()
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            .isEqualTo(app.getString(R.string.notif_usage_reset_title))
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo(app.getString(R.string.notif_usage_reset_included))
        val open = shadowOf(notification.contentIntent).savedIntent
        assertThat(open.action).isEqualTo(UsageNotifications.ACTION_OPEN_SETTINGS)
        assertThat(open.component?.className).isEqualTo(MainActivity::class.java.name)

        assertThat(UsageNotifications.postReset(app, UsageReset(included = false, api = false))).isFalse()
        UsageNotifications.postReset(app, UsageReset(included = true, api = true))
        assertThat(manager.getNotification(UsageNotifications.RESET_ID).extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo(app.getString(R.string.notif_usage_reset_both))
    }
}
