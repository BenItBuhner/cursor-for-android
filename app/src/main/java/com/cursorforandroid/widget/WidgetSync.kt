package com.cursorforandroid.widget

import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.cursorforandroid.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the home-screen widgets in step with the app for as long as its process lives: a list page landing, a run
 * finishing, a pin, a filter, a theme change or a sign-out re-renders every placed widget. Nothing is followed
 * until a widget exists — found at start-up, or by the first render — so a phone without one pays nothing; the
 * widgets' own periodic update covers the time the process is not around.
 */
object WidgetSync {

    /** A burst of changes (pages of a refresh, a run's status then its summary) settles into one render. */
    private const val SETTLE_MS = 750L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The collection of [WidgetData.snapshots], held so that it can be dropped when the last widget goes. */
    private var followJob: Job? = null

    /**
     * Bumped by [stop]. The start-up check asks the launcher, which suspends, so its answer can describe a widget
     * set that no longer exists by the time it arrives; following on a stale yes would leave a collector running
     * for the rest of the process with nothing to take it down again.
     */
    private var generation = 0

    /**
     * How the launcher is asked whether any widget is placed. Asking suspends, which is the whole difficulty here,
     * so this is also the seam a test uses to hold an answer open across a removal.
     */
    internal var placedWidgets: suspend (Context) -> Boolean = ::glanceIdsPresent

    /** From the application: follow the app if widgets are placed, and hand the widget picker a live preview. */
    fun start(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        scope.launch {
            val seen = generationNow()
            if (!placedWidgets(app)) return@launch
            // The generation rejects a removal the receiver has already reported, and a second look covers one
            // whose broadcast has not arrived yet; the install re-reads the generation under its own lock.
            if (generationNow() == seen && placedWidgets(app)) followIfCurrent(app, graph, seen)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 lets the app render the picker's preview itself; the system rate-limits repeats.
            scope.launch { runCatching { GlanceAppWidgetManager(app).setWidgetPreviews(ChatsWidgetReceiver::class) } }
        }
    }

    /** From a render: a widget exists, so what changes from here on has to reach it. Idempotent. */
    fun follow(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        synchronized(this) { followLocked(app, graph) }
    }

    /** [follow], unless the widget set has changed since [seen] — the start-up check's answer is that old. */
    private fun followIfCurrent(app: Context, graph: AppGraph, seen: Int) {
        synchronized(this) { if (generation == seen) followLocked(app, graph) }
    }

    private fun followLocked(app: Context, graph: AppGraph) {
        if (followJob?.isActive == true) return
        followJob = scope.launch {
            // The first value is the state as it stands, which the widgets show already (from this render, or
            // their last one in an earlier process); only what changes from here on is news to them.
            WidgetData.snapshots(graph).drop(1).collectLatest {
                delay(SETTLE_MS)
                // A render in progress is finished even if a newer change arrives; the next one follows it.
                withContext(NonCancellable) { renderAll(app) }
            }
        }
    }

    /**
     * From the receiver when the last widget is removed. Without this, every session, list, pin, filter and theme
     * change for the rest of the process's life would still wake the collector, wait out [SETTLE_MS] and ask the
     * launcher for widget ids that are no longer there. Adding a widget again starts a render, which calls [follow].
     */
    fun stop() {
        synchronized(this) {
            generation++
            followJob?.cancel()
            followJob = null
        }
    }

    internal fun isFollowing(): Boolean = synchronized(this) { followJob?.isActive == true }

    private fun generationNow(): Int = synchronized(this) { generation }

    private suspend fun glanceIdsPresent(app: Context): Boolean =
        runCatching { GlanceAppWidgetManager(app).getGlanceIds(ChatsWidget::class.java).isNotEmpty() }.getOrDefault(false)

    private suspend fun renderAll(app: Context) {
        runCatching { if (placedWidgets(app)) ChatsWidget().updateAll(app) }
    }
}
