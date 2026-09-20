package com.cursorforandroid.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.cursorforandroid.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the home-screen widgets in step with the app for as long as its process lives: a list page landing, a run
 * finishing, a pin, a filter, a theme change or a sign-out re-renders every placed widget. Nothing is followed
 * until a widget exists — found at start-up, or by the first render — so a phone without one pays nothing; the
 * widgets' own periodic update and [WidgetRefreshWork] cover the time the process is not around.
 *
 * Started from the application, not from a screen: the moments a widget most needs to change are the ones the app
 * is not on screen for — a run finishing under the live notification's service, the finish watchdog's job reading
 * a run record in a process it woke for the purpose — and a follower installed only by an activity was never there
 * for them, so the widget kept saying "working" until the next half-hour tick.
 */
object WidgetSync {

    private const val TAG = "WidgetSync"

    /**
     * How long a change waits for the rest of its burst (the pages of a refresh, a run's status then its summary)
     * before the widgets are rendered once for all of it. A fixed wait from the first change, not from the last:
     * a list that keeps changing — a running row touched by every stream event — is rendered every so often rather
     * than never.
     */
    internal const val SETTLE_MS = 300L

    /** A render that failed (a launcher that would not answer, WorkManager mid-initialisation) is tried once more after this. */
    internal const val RETRY_MS = 1_000L

    /**
     * How long after the process starts the launcher is asked about placed widgets. Asking is cheap but not free —
     * Glance's registry is a DataStore, read and back-filled on first use — and the first seconds of a process
     * belong to whatever started it: the first frame of the app, the foreground service's `startForeground`
     * promise, a job's deadline. Nothing a widget shows changes in those seconds that the follower, once up, does
     * not see: the list restored from disk and every fetch after it are changes it renders. Same wait as
     * `DeferredStartup`.
     */
    internal var startSettleMs = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The collection of [WidgetData.snapshots], held so that it can be dropped when the last widget goes. */
    private var followJob: Job? = null

    /** The start-up check while it waits and asks, so that the last widget's removal can call it off too. */
    private var startJob: Job? = null

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

    /** What a render is: every placed widget composed again. A seam, so the pipeline's timing can be pinned without a launcher. */
    internal var renderer: suspend (Context) -> Unit = { app -> ChatsWidget().updateAll(app) }

    /**
     * From the application: follow the app if widgets are placed. One question to the launcher, off the main
     * thread, and nothing more when the answer is no.
     */
    fun start(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(startSettleMs)
            val seen = generationNow()
            if (!placedWidgets(app)) return@launch
            // The generation rejects a removal the receiver has already reported, and a second look covers one
            // whose broadcast has not arrived yet; the install re-reads the generation under its own lock.
            if (generationNow() == seen && placedWidgets(app)) {
                val installed = followIfCurrent(app, graph, seen)
                // The follower renders what changes from here on. The widgets, though, show what an earlier process
                // last drew, and this one may already hold something newer — a list restored and patched in the
                // seconds before the launcher answered — so a list that is loaded by now is drawn once. (A follower
                // a render installed before this had that render for it.)
                if (installed && graph.agents.state.value.hasLoaded) renderAll(app)
                // An app updated in place gets no onEnabled for the widgets it already had; the timer is KEEP, so
                // this is free when it stands already.
                runCatching { WidgetRefreshWork.schedulePeriodic(app) }
            }
        }
        synchronized(this) {
            startJob?.cancel()
            startJob = job
        }
        job.start()
    }

    /** From a screen, once it has settled: hand the widget picker a live preview. Android 15 lets the app render it; the system rate-limits repeats. */
    fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val app = context.applicationContext
        scope.launch { runCatching { GlanceAppWidgetManager(app).setWidgetPreviews(ChatsWidgetReceiver::class) } }
    }

    /**
     * One widget rendered again, off whatever lifetime asked for it: the configuration screen finishes the moment a
     * choice is written, and a render on its own scope would be cancelled with it.
     */
    fun render(context: Context, id: GlanceId) {
        val app = context.applicationContext
        scope.launch { runCatching { ChatsWidget().update(app, id) }.onFailure { Log.w(TAG, "Widget render failed", it) } }
    }

    /** From a render: a widget exists, so what changes from here on has to reach it. Idempotent. */
    fun follow(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        synchronized(this) { followLocked(app, graph) }
    }

    /**
     * [follow], unless the widget set has changed since [seen] — the start-up check's answer is that old. True when
     * this call installed the follower (false when one was there already, or the answer was stale).
     */
    private fun followIfCurrent(app: Context, graph: AppGraph, seen: Int): Boolean =
        synchronized(this) { generation == seen && followLocked(app, graph) }

    /** Installs the follower; false when one is running already. */
    private fun followLocked(app: Context, graph: AppGraph): Boolean {
        if (followJob?.isActive == true) return false
        followJob = scope.launch {
            val snapshots = WidgetData.snapshots(graph)
            // The first value is the state as it stands, which the widgets show already (from this render, or
            // their last one in an earlier process); only what changes from here on is news to them.
            var rendered: WidgetSnapshot? = snapshots.first()
            // Conflated: a change that lands during the wait or the render is folded into the next pass, never
            // queued behind it. A render reads the state as it stands when the wait is over, so the pass a burst's
            // last change would start finds nothing new and draws nothing.
            snapshots.drop(1).conflate().collect {
                delay(SETTLE_MS)
                val current = snapshots.first()
                if (current == rendered) return@collect
                rendered = current
                // A render in progress is finished even if the follower is stopped meanwhile; the next change starts another.
                withContext(NonCancellable) { renderAll(app) }
            }
        }
        return true
    }

    /**
     * From the receiver when the last widget is removed. Without this, every session, list, pin, filter and theme
     * change for the rest of the process's life would still wake the collector, wait out [SETTLE_MS] and ask the
     * launcher for widget ids that are no longer there. Adding a widget again starts a render, which calls [follow].
     */
    fun stop() {
        synchronized(this) {
            generation++
            startJob?.cancel()
            startJob = null
            followJob?.cancel()
            followJob = null
        }
    }

    internal fun isFollowing(): Boolean = synchronized(this) { followJob?.isActive == true }

    private fun generationNow(): Int = synchronized(this) { generation }

    private suspend fun glanceIdsPresent(app: Context): Boolean =
        runCatching { GlanceAppWidgetManager(app).getGlanceIds(ChatsWidget::class.java).isNotEmpty() }.getOrDefault(false)

    /**
     * One render of every widget, tried twice: a failure is logged and the widgets are left as they were, never
     * silently. Runs under [NonCancellable], so nothing here is the follower's own cancellation; whatever a render
     * throws — Glance's own timeouts included — is the render's failure and is caught as one.
     */
    private suspend fun renderAll(app: Context) {
        if (!placedWidgets(app)) return
        val failure = runCatching { renderer(app) }.exceptionOrNull() ?: return
        Log.w(TAG, "Widget render failed; trying once more", failure)
        delay(RETRY_MS)
        runCatching { renderer(app) }.onFailure { Log.w(TAG, "Widget render failed again; the widgets keep their last frame", it) }
    }
}
