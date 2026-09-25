package com.cursorforandroid

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Starts background live sync ([com.cursorforandroid.data.repo.LiveSync]) once, following the process's place on
 * screen: a rotation restarts the activity, which is not the app leaving. Bound from [DeferredStartup], once the
 * first screen has settled, so nothing is held before the chat the reader opened first has loaded.
 */
object LiveSyncBinding {

    private var installed = false

    fun bind(graph: AppGraph) {
        if (installed) return
        installed = true
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val foreground = MutableStateFlow(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> foreground.value = true
                    Lifecycle.Event.ON_STOP -> foreground.value = false
                    else -> Unit
                }
            },
        )
        graph.liveSync.start(graph.agents.state.map { it.agents }.distinctUntilChanged(), graph.liveSyncEnabled, foreground)
    }
}
