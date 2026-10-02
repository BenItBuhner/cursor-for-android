package com.cursorforandroid

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.notifications.UsageNotifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Adaptive usage polling: every [UsageJobService.FOREGROUND_MS] while the process is started (app on screen
 * or the user active) *and* a real Extended-mode session can answer, and the 15-minute [UsageJobService] otherwise.
 * Bound from [DeferredStartup] so the first frame never pays for it. Default mode has no session, and the demo has
 * sample numbers with no account to ask — neither schedules a job nor starts the loop.
 */
object UsageCoordinator {

    private var processObserverInstalled = false
    private var loop: Job? = null
    @Volatile private var watching = false

    fun bind(activity: ComponentActivity, graph: AppGraph) {
        UsageNotifications.ensureChannel(activity)
        val process = ProcessLifecycleOwner.get()
        fun stopLoop() {
            loop?.cancel()
            loop = null
        }
        fun startLoop() {
            if (!watching) return
            loop?.cancel()
            loop = process.lifecycleScope.launch {
                while (true) {
                    runCatching { graph.usage.refresh() }
                    delay(UsageJobService.FOREGROUND_MS)
                }
            }
        }
        activity.lifecycleScope.launch {
            combine(graph.session.state, graph.extendedMode.enabled) { state, extended ->
                state is SessionState.SignedIn && !state.isDemo && extended
            }.distinctUntilChanged().collect { watch ->
                watching = watch
                if (watch) {
                    UsageJobService.schedule(activity)
                    if (process.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startLoop()
                } else {
                    UsageJobService.cancel(activity)
                    stopLoop()
                }
            }
        }
        if (processObserverInstalled) return
        processObserverInstalled = true
        process.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> startLoop()
                    Lifecycle.Event.ON_STOP -> stopLoop()
                    else -> Unit
                }
            },
        )
    }
}
