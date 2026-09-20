package com.cursorforandroid.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cursorforandroid.appGraph
import java.util.concurrent.TimeUnit

/**
 * The widget's refreshes that do not ride on the app being open: the one its header button asks for, and the
 * periodic one that stands in for the app while its process is not around.
 *
 * The platform's own tick (`updatePeriodMillis`) cannot come more often than every half hour, and between two of
 * them a widget in a dead process shows whatever the last render knew — a run as still working an hour after it
 * finished. The periodic work here halves that: every quarter hour (WorkManager's floor), on a connection, with a
 * battery that is not low, the newest page is read and every widget re-rendered — the same one-page,
 * two-conditions cost a widget tick pays (see [WidgetData.prepare]). Scheduled while widgets are placed, cancelled
 * with the last one; `KEEP`, so a second request while one stands changes nothing.
 */
internal object WidgetRefreshWork {

    private const val NOW = "widget-refresh-now"
    private const val PERIODIC = "widget-refresh-periodic"
    private const val FORCED = "forced"

    /** WorkManager's minimum period; anything shorter is rounded up to it anyway. */
    private const val PERIOD_MINUTES = 15L

    /**
     * The refresh the button asked for: the newest page, whatever the list's age, then every widget rendered with
     * the button back in place. One at a time — a second tap while one runs joins it rather than starting over,
     * which would cancel the first mid-fetch.
     */
    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInputData(workDataOf(FORCED to true)).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED, requiresBatteryNotLow = true))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC)
    }

    internal fun isForced(worker: WidgetRefreshWorker): Boolean = worker.inputData.getBoolean(FORCED, false)
}

/**
 * One refresh pass: the list made ready as a widget tick would make it, the newest page read (forced, or when the
 * list is stale), the refresh flag taken off every widget, and every widget rendered. The flag comes off whatever
 * the fetch came to — offline, a server error — because the spinner promises an attempt, not a result; the rows
 * say what the attempt found.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val forced = WidgetRefreshWork.isForced(this)
        try {
            WidgetData.refresh(app.appGraph, deviceRefreshBudget(app), forced)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "Widget refresh failed", t)
        } finally {
            clearRefreshingFlags(app)
            runCatching { ChatsWidget().updateAll(app) }.onFailure { Log.w(TAG, "Widget render after refresh failed", it) }
        }
        return Result.success()
    }

    private suspend fun clearRefreshingFlags(app: Context) {
        val manager = GlanceAppWidgetManager(app)
        runCatching { manager.getGlanceIds(ChatsWidget::class.java) }.getOrDefault(emptyList()).forEach { id ->
            runCatching { updateAppWidgetState(app, id) { it.remove(ChatsWidget.REFRESHING_SINCE_KEY) } }
        }
    }

    private companion object {
        const val TAG = "WidgetRefresh"
    }
}
