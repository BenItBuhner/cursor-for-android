package com.cursorforandroid

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.cursorforandroid.notifications.LiveNotificationCoordinator
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.update.UpdateCoordinator
import com.cursorforandroid.widget.WidgetSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wiring between the app and the rest of the system that the first frame does not need: the notification
 * channels and the live-run service's coordinator, the periodic update check, and the widget follower.
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
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed {}
            delay(settleMs)
            LiveNotifications.ensureChannels(activity)
            LiveNotificationCoordinator.bind(activity, graph)
            UpdateCoordinator.bind(activity, graph)
            // Placed home-screen widgets follow the list, pins, filters, theme and session for as long as this
            // process lives. A process woken by a widget render rather than by the app installs it from there.
            WidgetSync.start(activity, graph)
        }
    }
}
