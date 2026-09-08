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
 * Wires the update manager to the world outside its own state: the periodic job follows the "automatic updates"
 * preference, and the process lifecycle tells it when the app comes forward (time for a cheap check) and when it
 * leaves the screen (time to apply a downloaded update). Bound from [com.cursorforandroid.MainActivity] only, so
 * nothing reaches the network in tests that compose the UI directly.
 */
object UpdateCoordinator {

    private var processObserverInstalled = false

    fun bind(activity: ComponentActivity, graph: AppGraph) {
        activity.lifecycleScope.launch {
            graph.updates.autoUpdate.distinctUntilChanged().collect { enabled ->
                if (enabled) UpdateJobService.schedule(activity) else UpdateJobService.cancel(activity)
            }
        }
        if (processObserverInstalled) return
        processObserverInstalled = true
        val process = ProcessLifecycleOwner.get()
        // Process-level rather than the activity's own events: a rotation restarts the activity but never means the
        // user left, and an install at that moment would tear the app down under them.
        process.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> process.lifecycleScope.launch { graph.updates.onAppStarted() }
                    Lifecycle.Event.ON_STOP -> process.lifecycleScope.launch { graph.updates.onAppStopped() }
                    else -> Unit
                }
            },
        )
    }
}
