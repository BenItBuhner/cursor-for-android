package com.cursorforandroid

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The infrequent usage poll while the app is not on screen: [PERIOD_MS] (JobScheduler's floor, and the long
 * end of the 5–15 minute range). Foreground polling is [UsageCoordinator]'s 5-minute loop. The job persists
 * across reboots and needs a connection; a low battery skips it.
 */
class UsageJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = startRun { jobFinished(params, false) }
        return true
    }

    @VisibleForTesting
    internal fun startRun(onFinished: () -> Unit): Job = scope.launch {
        try {
            appGraph.usage.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The next period tries again; Settings keeps the last snapshot.
        }
        onFinished()
    }

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
        const val JOB_ID = 0x55534147
        val PERIOD_MS: Long = TimeUnit.MINUTES.toMillis(15)
        /**
         * Slice at the end of each period the run may fall in, so a freshly scheduled job does not dispatch
         * [onStartJob] into the launch that just scheduled it (see `UpdateJobService.FLEX_MS`).
         */
        val FLEX_MS: Long = TimeUnit.MINUTES.toMillis(5)
        /** Foreground / active poll: the short end of the 5–15 minute range. */
        val FOREGROUND_MS: Long = TimeUnit.MINUTES.toMillis(5)

        fun isScheduled(context: Context): Boolean = scheduler(context)?.getPendingJob(JOB_ID) != null

        fun schedule(context: Context) {
            val scheduler = scheduler(context) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UsageJobService::class.java))
                .setPeriodic(PERIOD_MS, FLEX_MS)
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
