package com.cursorforandroid.update

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.appGraph
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.update.PendingInstall
import com.cursorforandroid.data.update.UpdateCache
import com.cursorforandroid.domain.AppRelease
import com.cursorforandroid.domain.AppVersion
import com.cursorforandroid.domain.ReleaseAsset
import com.cursorforandroid.domain.UpdateState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The Android-side plumbing around the update manager, on Robolectric's shadows of the platform services. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpdateGlueTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val release = AppRelease(
        tagName = "v9.0.0",
        version = AppVersion(9, 0, 0),
        isPreRelease = false,
        publishedAtMs = 0L,
        htmlUrl = "https://github.com/x/y/releases/tag/v9.0.0",
        apk = ReleaseAsset("cursor-for-android-9.0.0.apk", "https://example.invalid/app.apk", 3L),
    )

    @Test
    fun `the periodic job is scheduled once, with the constraints the check needs, and cancelled with the preference`() {
        val scheduler = app.getSystemService(JobScheduler::class.java)
        assertThat(UpdateJobService.isScheduled(app)).isFalse()

        UpdateJobService.schedule(app)
        UpdateJobService.schedule(app)
        val job = checkNotNull(scheduler.getPendingJob(UpdateJobService.JOB_ID))
        assertThat(scheduler.allPendingJobs).hasSize(1)
        assertThat(job.isPeriodic).isTrue()
        assertThat(job.intervalMillis).isEqualTo(UpdateJobService.PERIOD_MS)
        assertThat(job.networkType).isEqualTo(JobInfo.NETWORK_TYPE_ANY)
        assertThat(job.isPersisted).isTrue()
        assertThat(job.isRequireBatteryNotLow).isTrue()
        assertThat(job.service.className).isEqualTo(UpdateJobService::class.java.name)

        UpdateJobService.cancel(app)
        assertThat(UpdateJobService.isScheduled(app)).isFalse()
    }

    @Test
    fun `an install writes the APK into a session for our own package and commits it to the status receiver`() = runBlocking {
        val apk = File(app.cacheDir, "9000099.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val platform = AndroidUpdatePlatform(app)
        val installer = app.packageManager.packageInstaller
        val recorded = mutableListOf<Int>()

        platform.install(apk, release) { recorded += it }

        val session = installer.allSessions.single()
        // Recorded before the commit: the callback can start a process that has nothing else to go on.
        assertThat(recorded).containsExactly(session.sessionId)
        assertThat(session.appPackageName).isEqualTo(BuildConfig.APPLICATION_ID)
        assertThat(installer.mySessions).hasSize(1)

        // The system finishing the session fires the committed status receiver: our broadcast, for our release. (The
        // shadow sends it without the EXTRA_STATUS the real installer fills in; the receiver's parsing is covered below.)
        shadowOf(installer).setSessionSucceeds(session.sessionId)
        shadowOf(Looper.getMainLooper()).idle()
        val status = shadowOf(app).broadcastIntents.last { it.action == UpdateInstallReceiver.ACTION_INSTALL_STATUS }
        assertThat(status.component?.className).isEqualTo(UpdateInstallReceiver::class.java.name)
        assertThat(status.getIntExtra(UpdateInstallReceiver.EXTRA_VERSION_CODE, -1)).isEqualTo(release.versionCode)
        assertThat(status.getIntExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, -1)).isEqualTo(session.sessionId)

        platform.abandonSessions()
        assertThat(installer.mySessions).isEmpty()
    }

    @Test
    fun `a file that is not a package does not parse`() {
        val junk = File(app.cacheDir, "junk.apk").apply { writeText("not an apk") }
        assertThat(AndroidUpdatePlatform(app).inspect(junk)).isNull()
    }

    @Test
    fun `the receiver serves a verdict that arrives in a process holding nothing about the install`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // What the process that committed the session left behind, and nothing else: no manager has run here yet.
        val cache = UpdateCache(JsonDiskCache(File(app.cacheDir, "update-check")))
        cache.writePending(PendingInstall(SESSION_ID, release, committedAtMs = System.currentTimeMillis()))
        val updates = app.appGraph.updates
        assertThat(updates.state.value).isEqualTo(UpdateState.Idle)

        val confirmation = Intent("android.content.pm.action.CONFIRM_INSTALL").putExtra(PackageInstaller.EXTRA_SESSION_ID, SESSION_ID)
        deliver(PackageInstaller.STATUS_PENDING_USER_ACTION) { it.putExtra(Intent.EXTRA_INTENT, confirmation) }
        settle { updates.state.value is UpdateState.Installing }
        assertThat(updates.state.value).isEqualTo(UpdateState.Installing(release, awaitingConfirmation = true))

        // No activity is started in the background; a notification carries the user back to the app instead.
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        val notification = manager.getNotification(UpdateNotifications.READY_ID)
        assertThat(notification).isNotNull()
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()).isEqualTo("Cursor 9.0.0 is ready to install")
        assertThat(notification.actions.single().title.toString()).isEqualTo("Install")
        val open = shadowOf(notification.contentIntent).savedIntent
        assertThat(open.action).isEqualTo(UpdateNotifications.ACTION_INSTALL_UPDATE)
        assertThat(open.component?.className).isEqualTo("com.cursorforandroid.MainActivity")
        assertThat(shadowOf(app).nextStartedActivity).isNull()

        // Declining clears the notification and leaves the release to be offered again.
        deliver(PackageInstaller.STATUS_FAILURE_ABORTED)
        settle { updates.state.value !is UpdateState.Installing }
        assertThat(manager.getNotification(UpdateNotifications.READY_ID)).isNull()
        assertThat(updates.state.value.release).isEqualTo(release)
    }

    private fun deliver(status: Int, extras: (Intent) -> Unit = {}) {
        UpdateInstallReceiver().onReceive(
            app,
            Intent(app, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                .putExtra(UpdateInstallReceiver.EXTRA_VERSION_CODE, release.versionCode)
                .putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, SESSION_ID)
                .putExtra(PackageInstaller.EXTRA_STATUS, status)
                .also(extras),
        )
    }

    /** The receiver finishes its work off the main thread, so the looper is pumped until it lands. */
    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("The receiver's work never landed")
    }

    private companion object {
        const val SESSION_ID = 7
    }
}
