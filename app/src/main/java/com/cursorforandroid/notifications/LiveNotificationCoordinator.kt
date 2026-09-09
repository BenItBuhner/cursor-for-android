package com.cursorforandroid.notifications

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.SessionState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Starts the [LiveNotificationService] while the activity is visible and at least one agent is running.
 * Android only allows a foreground service to start from the foreground, which is exactly when a run begins
 * from this device or when the list refresh reveals agents started elsewhere. The service stops itself.
 */
object LiveNotificationCoordinator {

    private sealed interface Decision {
        data class Track(val runningIds: Set<String>) : Decision
        data object Idle : Decision
        data object Blocked : Decision
        data object SignedOut : Decision
    }

    fun bind(activity: ComponentActivity, graph: AppGraph) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(graph.session.state, graph.agents.state, graph.prefs.liveNotifications) { session, list, enabled ->
                    val running = list.agents.filter { it.isRunning }.map { it.id }.toSet()
                    when {
                        session is SessionState.SignedOut -> Decision.SignedOut
                        // A list restored from disk may still say "running" about runs that finished hours ago; the
                        // service only starts once a fetch has confirmed what is actually running.
                        list.isFromCache -> Decision.Idle
                        enabled && session is SessionState.SignedIn && running.isNotEmpty() ->
                            if (LiveNotifications.canShowLive(activity)) Decision.Track(running) else Decision.Blocked
                        else -> Decision.Idle
                    }
                    // Notification capability is not a flow, so it is read here rather than observed: this whole
                    // collection restarts every time the activity becomes visible, which is when the user can be
                    // coming back from the system settings page that changed the answer.
                }.distinctUntilChanged().collect { decision ->
                    when (decision) {
                        // Re-issued whenever the running set changes; a live service just gets another start command.
                        is Decision.Track -> LiveNotificationService.start(activity)
                        // Following streams to post a card the system would drop is all cost and no benefit.
                        Decision.Blocked -> LiveNotificationService.stop(activity)
                        Decision.SignedOut -> {
                            LiveNotificationService.stop(activity)
                            runCatching { NotificationManagerCompat.from(activity).cancelAll() }
                        }
                        Decision.Idle -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Asks for `POST_NOTIFICATIONS` (Android 13+) the first time an agent is running while the feature is on, the
 * same moment the live notification would appear. A refusal is remembered and never nagged about; Settings
 * offers a way back into the system page.
 */
@Composable
fun NotificationPermissionPrompt(graph: AppGraph, hasRunningAgents: Boolean) {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val enabled by graph.prefs.liveNotifications.collectAsStateWithLifecycle(initialValue = false)
    val asked by graph.prefs.notificationPermissionAsked.collectAsStateWithLifecycle(initialValue = true)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The service may already be running invisibly; a start command re-posts its notification now that it can show.
        if (granted) LiveNotificationService.start(context)
    }
    LaunchedEffect(hasRunningAgents, enabled, asked) {
        if (hasRunningAgents && enabled && !asked && !LiveNotifications.hasPermission(context)) {
            graph.prefs.setNotificationPermissionAsked()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
