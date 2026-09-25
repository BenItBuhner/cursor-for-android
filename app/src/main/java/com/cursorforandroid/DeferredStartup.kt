package com.cursorforandroid

import androidx.activity.ComponentActivity
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.cursorforandroid.notifications.LiveNotificationCoordinator
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.shortcuts.LauncherShortcuts
import com.cursorforandroid.update.UpdateCoordinator
import com.cursorforandroid.widget.WidgetSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wiring between the app and the rest of the system that the first frame does not need: the notification
 * channels and the live-run service's coordinator, the periodic update check, the composer catalogs' background
 * refresh, the widget picker's preview, and the sweep of what earlier processes left in the cache directory.
 *
 * All of it is held back until the activity has been on screen for [SETTLE_MS]. Each one talks to a system
 * service — `NotificationManager`, `JobScheduler`, the launcher's widget host — and the periodic update check is
 * the pointed case: scheduled during a launch, its `onStartJob` can be dispatched into a main thread that is
 * still building the first screen, and a callback the app cannot answer in time is an ANR rather than a slow start.
 */
object DeferredStartup {

    /** How long after the first frame the process is taken to be idle enough to pay for the above. */
    private const val SETTLE_MS = 2_000L

    /** Test seam: what this object is for is *when* it runs, which cannot be asserted through a real wait. */
    internal var settleMs = SETTLE_MS

    /** Called from every activity creation; the work either belongs to that activity or is idempotent. */
    fun arm(activity: ComponentActivity, graph: AppGraph) {
        // Not held back with the rest: a crash in the first seconds is the kind worth hearing about, and reading the
        // one setting costs nothing the first frame notices. The collector is the process's, started once.
        graph.crashReporting.follow(graph.prefs.crashReports, ProcessLifecycleOwner.get().lifecycleScope)
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed {}
            delay(settleMs)
            LiveNotifications.ensureChannels(activity)
            LiveNotificationCoordinator.bind(activity, graph)
            UpdateCoordinator.bind(activity, graph)
            CatalogFreshness.bind(activity, graph)
            LiveSyncBinding.bind(graph)
            // The widget picker's live preview is composed here, once the screen is up; the widgets themselves are
            // followed from the application (see CursorApp), whichever way the process was started.
            WidgetSync.publishPreviews(activity)
            // The launcher's long-press menu names the pinned chats and Projects; kept in step for as long as the process lives.
            LauncherShortcuts.start(activity, graph)
            // The process's, not the activity's: a rotation mid-sweep would otherwise cancel it for the whole process.
            ProcessLifecycleOwner.get().lifecycleScope.launch { graph.sweepLeftovers() }
        }
    }
}
