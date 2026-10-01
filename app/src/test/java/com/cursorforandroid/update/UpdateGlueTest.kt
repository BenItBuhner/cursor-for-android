package com.cursorforandroid.update

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.DeferredStartup
import com.cursorforandroid.MainActivity
import com.cursorforandroid.appGraph
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.update.CachedUpdateCheck
import com.cursorforandroid.data.update.PendingInstall
import com.cursorforandroid.data.update.UpdateCache
import com.cursorforandroid.data.update.VerifiedDownload
import com.cursorforandroid.domain.AppRelease
import com.cursorforandroid.domain.AppVersion
import com.cursorforandroid.domain.ReleaseAsset
import com.cursorforandroid.domain.UpdateState
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.util.UiDispatcherRearm
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        // The run belongs in a slice at the end of the period, never in the one that starts the moment the job is
        // scheduled: that window opens during the launch doing the scheduling, and onStartJob is dispatched into a
        // main thread that is still building the first screen. A flex equal to the period is what makes it immediate.
        assertThat(job.flexMillis).isEqualTo(UpdateJobService.FLEX_MS)
        assertThat(job.flexMillis).isLessThan(job.intervalMillis)
        assertThat(job.networkType).isEqualTo(JobInfo.NETWORK_TYPE_ANY)
        assertThat(job.isPersisted).isTrue()
        assertThat(job.isRequireBatteryNotLow).isTrue()
        assertThat(job.service.className).isEqualTo(UpdateJobService::class.java.name)

        UpdateJobService.cancel(app)
        assertThat(UpdateJobService.isScheduled(app)).isFalse()
    }

    @Test
    fun `the scheduled run happens off the main thread the scheduler starts the job on`() {
        runBlocking { app.appGraph.prefs.setAutoUpdate(false) }
        val service = Robolectric.buildService(UpdateJobService::class.java).create().get()
        val finishedOn = AtomicReference<Thread>()
        val finished = CountDownLatch(1)

        service.startRun {
            finishedOn.set(Thread.currentThread())
            finished.countDown()
        }

        // The main looper is never pumped: a run that needed it would be a run the scheduler is still waiting for.
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(finishedOn.get()).isNotSameInstanceAs(Looper.getMainLooper().thread)
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
        assertThat(platform.isSessionActive(session.sessionId)).isFalse()
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

        // The app is not on screen, so the system's dialog is not thrown in front of whatever the user is doing — and
        // nothing is posted to the shade about it either. The state alone carries it: Settings offers "Confirm".
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertThat(manager.allNotifications).isEmpty()
        assertThat(shadowOf(app).nextStartedActivity).isNull()

        // Declining leaves the release to be offered again, still with nothing in the shade.
        deliver(PackageInstaller.STATUS_FAILURE_ABORTED)
        settle { updates.state.value !is UpdateState.Installing }
        assertThat(manager.allNotifications).isEmpty()
        assertThat(updates.state.value.release).isEqualTo(release)
    }

    @Test
    fun `the first start of this build takes the retired update channel and its card out of the shade`() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(LiveNotifications.RETIRED_CHANNEL_UPDATES, "Updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.notify(
            LiveNotifications.RETIRED_UPDATE_READY_ID,
            Notification.Builder(app, LiveNotifications.RETIRED_CHANNEL_UPDATES).setContentTitle("Cursor 0.4.0 is ready to install").build(),
        )
        assertThat(shadowOf(manager).getNotification(LiveNotifications.RETIRED_UPDATE_READY_ID)).isNotNull()

        LiveNotifications.ensureChannels(app)

        assertThat(shadowOf(manager).getNotification(LiveNotifications.RETIRED_UPDATE_READY_ID)).isNull()
        val channels = manager.notificationChannels.map { it.id }
        assertThat(channels).doesNotContain(LiveNotifications.RETIRED_CHANNEL_UPDATES)
        assertThat(channels).containsAtLeast(LiveNotifications.CHANNEL_LIVE, LiveNotifications.CHANNEL_FINISHED)
    }

    /**
     * The whole app, on the lifecycle the coordinator listens to, with a downloaded update waiting: coming forward
     * and leaving again commits no session, starts no activity and posts nothing. The update is the sidebar's hint
     * and Settings' Install, and stays exactly that until the user gets there.
     */
    @Test
    fun `an update that is ready waits through the app being opened and put away without a session, a dialog or a notification`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val graph = app.appGraph
        runBlocking {
            graph.prefs.setAutoUpdate(true)
            // The last check was a moment ago, so the foreground pass has no list to read: what is on disk is the
            // whole of the updater's input here.
            graph.prefs.setUpdateLastCheckedAt(System.currentTimeMillis())
            val apk = File(app.cacheDir, "updates/${release.versionCode}.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            val cache = UpdateCache(JsonDiskCache(File(app.cacheDir, "update-check")))
            cache.write(CachedUpdateCheck(etag = "\"etag\"", checkedAtMs = System.currentTimeMillis(), candidate = release))
            cache.writeVerified(VerifiedDownload(release.versionCode, apk.length(), "00"))
        }
        DeferredStartup.settleMs = 0
        val installer = app.packageManager.packageInstaller
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { this.activity = it }
        // The deferred startup has bound the coordinator once the job it schedules is there.
        settle { UpdateJobService.isScheduled(app) }

        // Robolectric never moves the process-wide lifecycle on its own, so the app coming forward is reported here.
        process.handleLifecycleEvent(Lifecycle.Event.ON_START)
        process.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        settle { graph.updates.state.value is UpdateState.Downloaded }
        val downloaded = graph.updates.state.value as UpdateState.Downloaded
        assertThat(downloaded.release).isEqualTo(release)

        // Away.
        activity.pause().stop()
        process.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        process.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        idle()

        assertThat(graph.updates.state.value).isEqualTo(downloaded)
        assertThat(installer.allSessions).isEmpty()
        assertThat(notifications.allNotifications).isEmpty()
        assertThat(shadowOf(app).nextStartedActivity).isNull()

        // And back again, which is the one moment the updater does act on its own: a check, throttled away here.
        activity.start().resume()
        process.handleLifecycleEvent(Lifecycle.Event.ON_START)
        idle()
        assertThat(graph.updates.state.value).isEqualTo(downloaded)
        assertThat(installer.allSessions).isEmpty()
        assertThat(notifications.allNotifications).isEmpty()
        process.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    private var activity: ActivityController<MainActivity>? = null
    private val settleMs = DeferredStartup.settleMs

    /** Tears down the activity a test built; left resumed, its composition would keep collecting on the shared main looper. */
    @After
    fun tearDown() {
        DeferredStartup.settleMs = settleMs
        activity?.let { runCatching { it.pause().stop().destroy() } }
        shadowOf(Looper.getMainLooper()).idle()
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

    /** A few frames of the main looper, with a moment of the disk's threads for each, so anything in flight lands. */
    private fun idle() {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(20) {
            UiDispatcherRearm.mend()
            looper.idle()
            Thread.sleep(5)
        }
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
