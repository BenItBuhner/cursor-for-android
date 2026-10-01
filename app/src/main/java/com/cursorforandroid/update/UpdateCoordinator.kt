package com.cursorforandroid.update

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.cursorforandroid.AppGraph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Wires the update manager to the world outside its own state: the periodic job follows the "check for updates"
 * preference, and the process lifecycle tells it when the app comes forward (time for a cheap check). That is the
 * whole of it — the app leaving the screen is not an event the updater hears about, because nothing happens on
 * leaving: no install, no notification. Bound from [com.cursorforandroid.MainActivity] only, so nothing reaches the
 * network in tests that compose the UI directly.
 */
object UpdateCoordinator {

    /** The graph the process observer serves; one per process in the app, one per test under Robolectric. */
    private var observed: Pair<AppGraph, LifecycleEventObserver>? = null

    fun bind(activity: ComponentActivity, graph: AppGraph) {
        activity.lifecycleScope.launch {
            graph.updates.autoUpdate.distinctUntilChanged().collect { enabled ->
                if (enabled) UpdateJobService.schedule(activity) else UpdateJobService.cancel(activity)
            }
        }
        if (observed?.first === graph) return
        val process = ProcessLifecycleOwner.get()
        observed?.let { process.lifecycle.removeObserver(it.second) }
        // Process-level rather than the activity's own events: a rotation restarts the activity but never means the
        // app was opened again.
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
            process.lifecycleScope.launch { graph.updates.onAppStarted() }
            // The installed version's notes, for the What's new page: from the disk after the first time, and
            // not tied to the check-for-updates switch — nothing here installs anything.
            graph.whatsNew.refresh()
        }
        observed = graph to observer
        process.lifecycle.addObserver(observer)
    }
}
