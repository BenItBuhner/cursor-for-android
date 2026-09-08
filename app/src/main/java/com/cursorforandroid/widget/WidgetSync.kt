package com.cursorforandroid.widget

import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.cursorforandroid.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    private val following = AtomicBoolean(false)

    /** From the application: follow the app if widgets are placed, and hand the widget picker a live preview. */
    fun start(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        scope.launch { if (hasWidgets(app)) follow(app, graph) }
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 lets the app render the picker's preview itself; the system rate-limits repeats.
            scope.launch { runCatching { GlanceAppWidgetManager(app).setWidgetPreviews(ChatsWidgetReceiver::class) } }
        }
    }

    /** From a render: a widget exists, so what changes from here on has to reach it. Idempotent. */
    fun follow(context: Context, graph: AppGraph) {
        if (!following.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            // The first value is the state as it stands, which the widgets show already (from this render, or their
            // last one in an earlier process); only what changes from here on is news to them.
            WidgetData.snapshots(graph).drop(1).collectLatest {
                delay(SETTLE_MS)
                // A render in progress is finished even if a newer change arrives; the next one follows it.
                withContext(NonCancellable) { renderAll(app) }
            }
        }
    }

    private suspend fun hasWidgets(app: Context): Boolean =
        runCatching { GlanceAppWidgetManager(app).getGlanceIds(ChatsWidget::class.java).isNotEmpty() }.getOrDefault(false)

    private suspend fun renderAll(app: Context) {
        runCatching { if (hasWidgets(app)) ChatsWidget().updateAll(app) }
    }
}
