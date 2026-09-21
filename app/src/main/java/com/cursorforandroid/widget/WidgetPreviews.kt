package com.cursorforandroid.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cursorforandroid.AppGraph
import com.cursorforandroid.appGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The widgets' pictures in the launcher's widget picker on Android 15, where the widget composes its own
 * ([androidx.glance.appwidget.GlanceAppWidget.providePreview]) and hands the system a RemoteViews it keeps.
 *
 * Two things about how the system keeps it decide when to publish. It keeps the picture in memory only, so a reboot
 * loses it and the picker falls back to the provider's `previewLayout` until the app publishes again. And it keeps it
 * across an update of the app — the new build's provider inherits the old build's RemoteViews, categories and all —
 * so the launcher goes on inflating the old build's picture against the new build's resources; when the resource ids
 * have moved between the two, that inflation throws and the picker shows "Can't load widget" in the widget's place.
 * The system also allows two publishes an hour per widget. So a publish is made once per installed build and boot,
 * and never again for the same pair: at the app's start when the pair is new, and through [WidgetPreviewsWorker] the
 * moment an update lands ([WidgetPackageReplacedReceiver]), whether or not the app is opened after it. A publish the system refused (the hour's two
 * spent) is not recorded, so the next start tries again.
 */
object WidgetPreviews {

    private const val TAG = "WidgetPreviews"

    /**
     * How the previews are published: each kind's receiver through Glance, answering with the kinds that went
     * through. A seam, so the bookkeeping can be pinned without a launcher.
     */
    internal var publisher: suspend (Context, List<WidgetKind>) -> Set<WidgetKind> = ::publishThroughGlance

    /** The installed build (its install time, which every reinstall moves) and the boot a publish stands for. */
    internal fun stamp(context: Context): String {
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0)
        return "$installed/$boot"
    }

    /**
     * Publishes the previews of the kinds the system does not hold for this build and boot. True when every kind's is
     * in place afterwards; false when the system refused one, which is left due for the next call. Nothing before
     * Android 15, which has no generated previews.
     */
    suspend fun publishIfNeeded(context: Context, graph: AppGraph, stamp: String = stamp(context)): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        val app = context.applicationContext
        val published = graph.prefs.widgetPreviewsPublished.first()
        val due = WidgetKinds.all.filter { entry(stamp, it) !in published }
        if (due.isEmpty()) return true
        val done = try {
            publisher(app, due)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "Widget previews could not be published", t)
            emptySet()
        }
        // Entries of other builds and boots are stale by definition and go with this write.
        val kept = published.filterTo(LinkedHashSet()) { it.startsWith("$stamp|") } + done.map { entry(stamp, it) }
        if (kept != published) graph.prefs.setWidgetPreviewsPublished(kept)
        if (done.isNotEmpty()) Log.i(TAG, "Widget previews published for ${done.joinToString { it.receiver.simpleName }} ($stamp)")
        if (done.size < due.size) Log.w(TAG, "The system refused ${due.size - done.size} of ${due.size} widget previews (rate limit); they are due at the next start")
        return done.size == due.size
    }

    private fun entry(stamp: String, kind: WidgetKind): String = "$stamp|${kind.receiver.name}"

    private suspend fun publishThroughGlance(context: Context, kinds: List<WidgetKind>): Set<WidgetKind> {
        if (Build.VERSION.SDK_INT < 35) return emptySet()
        val manager = GlanceAppWidgetManager(context)
        return kinds.filterTo(LinkedHashSet()) { kind ->
            try {
                manager.setWidgetPreviews(kind.receiver.kotlin) == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "Preview of ${kind.receiver.simpleName} could not be composed", t)
                false
            }
        }
    }
}

/**
 * An update of the app has landed: the system still holds the previous build's widget previews, so the new build's
 * are published now (see [WidgetPreviews]) rather than at the next opening of the app — which is what would leave the
 * picker inflating the old build's pictures against the new build's resources in the meantime. The publish itself is
 * WorkManager's ([WidgetPreviewsWorker]): a preview composes in well under a second on a phone, but a broadcast
 * receiver's window is the platform's, and a device busy re-optimising every app it just updated is exactly the one
 * that would run out of it.
 */
class WidgetPackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || Build.VERSION.SDK_INT < 35) return
        WidgetPreviewsWorker.enqueue(context)
    }
}

/** One publish of the previews the system does not hold for this build and boot (see [WidgetPreviews.publishIfNeeded]). */
class WidgetPreviewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        // A publish the system refused is due at the next start of the app, not at WorkManager's next attempt: the
        // limit it ran into is hourly.
        WidgetPreviews.publishIfNeeded(app, app.appGraph)
        return Result.success()
    }

    companion object {
        const val NAME = "widget-previews"

        /** One at a time: a second update landing while the first's publish runs joins it. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<WidgetPreviewsWorker>().build())
        }
    }
}
