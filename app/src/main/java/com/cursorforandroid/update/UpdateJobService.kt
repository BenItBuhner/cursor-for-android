package com.cursorforandroid.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.cursorforandroid.appGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The periodic update check, run by `JobScheduler` roughly every [PERIOD_MS] while the device is online and its
 * battery is not low, whether or not the app has been opened. The job persists across reboots. Everything it does
 * is [com.cursorforandroid.data.update.UpdateManager.runScheduled]: check, download on Wi-Fi, install or notify.
 */
class UpdateJobService : JobService() {

    /**
     * Never a main-thread dispatcher: the scheduler calls [onStartJob] on the main thread and holds the process to a
     * deadline for returning from it, so no part of the run may happen on the way out of it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = startRun { jobFinished(params, false) }
        return true
    }

    @VisibleForTesting
    internal fun startRun(onFinished: () -> Unit): Job = scope.launch {
        try {
            appGraph.updates.runScheduled()
        } catch (e: CancellationException) {
            throw e // onStopJob already told the scheduler; jobFinished must not follow.
        } catch (_: Throwable) {
            // A failed run is recorded in the update state; the next period tries again.
        }
        onFinished()
    }

    /** The system wants the slot back (constraints lost, or the run took too long): stop and let it reschedule. */
    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        job = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = 0x55504431
        val PERIOD_MS: Long = TimeUnit.HOURS.toMillis(12)

        fun isScheduled(context: Context): Boolean = scheduler(context)?.getPendingJob(JOB_ID) != null

        /** Idempotent: an already scheduled job keeps its timing rather than being reset. */
        fun schedule(context: Context) {
            val scheduler = scheduler(context) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }
        }

        fun cancel(context: Context) {
            runCatching { scheduler(context)?.cancel(JOB_ID) }
        }

        private fun scheduler(context: Context): JobScheduler? = context.getSystemService(JobScheduler::class.java)
    }
}
