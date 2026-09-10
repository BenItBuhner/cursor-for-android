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
import com.cursorforandroid.data.repo.AgentListState
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Keeps the [LiveNotificationService] up while the activity is visible and at least one agent is running. Android
 * only allows a foreground service to start from the foreground, which is exactly when a run begins from this
 * device or when the list refresh reveals agents started elsewhere. The service stops itself when nothing runs.
 *
 * Whether the service is actually up is part of the decision (see [LiveNotificationService.active]), not assumed
 * from having asked for it: a start the platform refused, or a service the platform took down while agents kept
 * running, used to leave the notification gone for good — nothing asked again until the set of running agents
 * happened to change. Now the [LiveNotificationSupervisor] asks again, with backoff, for as long as the app is in
 * front and there is something to show.
 */
object LiveNotificationCoordinator {

    fun bind(activity: ComponentActivity, graph: AppGraph) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A new supervisor each time the activity comes forward: a fresh retry budget for a fresh chance.
                LiveNotificationSupervisor(
                    start = { LiveNotificationService.start(activity) },
                    stop = {
                        LiveNotificationService.stop(activity)
                        FinishWatchdogJobService.disarm(activity)
                        runCatching { NotificationManagerCompat.from(activity).cancelAll() }
                    },
                ).run(liveDecisions(graph.session.state, graph.agents.state, graph.prefs.liveNotifications, LiveNotificationService.active, graph.prefs.localAgentState))
            }
        }
    }
}

/** What the app wants of the live notification service right now. */
internal sealed interface LiveDecision {
    /** Agents are running and the feature is on: the service should be up. [serviceActive] says whether it is. */
    data class Track(val runningIds: Set<String>, val serviceActive: Boolean) : LiveDecision

    /** Nothing to show (or nothing confirmed yet): the service is left to stop itself. */
    data object Idle : LiveDecision

    /** No account: the service is stopped and every notification taken down. */
    data object SignedOut : LiveDecision
}

internal fun liveDecision(
    session: SessionState,
    list: AgentListState,
    enabled: Boolean,
    serviceActive: Boolean,
    quietIds: Set<String> = emptySet(),
): LiveDecision {
    val running = list.agents.filter { it.isRunning && it.id !in quietIds }.map { it.id }.toSet()
    return when {
        session is SessionState.SignedOut -> LiveDecision.SignedOut
        // A list restored from disk may still say "running" about runs that finished hours ago; the service only
        // starts once a fetch has confirmed what is actually running.
        list.isFromCache -> LiveDecision.Idle
        enabled && session is SessionState.SignedIn && running.isNotEmpty() -> LiveDecision.Track(running, serviceActive)
        else -> LiveDecision.Idle
    }
}

internal fun liveDecisions(
    session: Flow<SessionState>,
    list: Flow<AgentListState>,
    enabled: Flow<Boolean>,
    serviceActive: Flow<Boolean>,
    local: Flow<LocalAgentState> = flowOf(LocalAgentState()),
): Flow<LiveDecision> = combine(session, list, enabled, serviceActive, local) { s, l, e, a, state ->
    liveDecision(s, l, e, a, state.quietIds(AppClock.now()))
}.distinctUntilChanged()

/**
 * Turns [LiveDecision]s into start and stop commands, and does not take a refused or lost service for an answer.
 *
 *  - A [LiveDecision.Track] with the service down asks for it, then asks again with growing pauses until it reports
 *    itself up or [maxStartAttempts] have been made: a start can be refused for a moment (the process not yet
 *    counted as being in front) as well as for good (a device policy), and only the second is worth giving up on.
 *  - A service that was up and went down while agents still run (Android 15's `dataSync` budget, a kill) is brought
 *    back after a pause that doubles with each loss in a row, up to [restartMaxMs], and shrinks back once the
 *    service has stayed up for [healthyAfterMs]: a service that cannot stay up must not be restarted in a hot loop.
 *  - A [LiveDecision.Track] with the service up just re-issues the start, which makes the service re-post its
 *    notification for a running set that changed.
 *
 * Every decision cancels whatever the previous one was still waiting on, so a service that comes up ends its own
 * retries, and a running set that empties ends any restart.
 */
internal class LiveNotificationSupervisor(
    private val start: () -> Boolean,
    private val stop: () -> Unit,
    private val startGraceMs: Long = START_GRACE_MS,
    private val maxStartAttempts: Int = MAX_START_ATTEMPTS,
    private val restartBaseMs: Long = RESTART_BASE_MS,
    private val restartMaxMs: Long = RESTART_MAX_MS,
    private val healthyAfterMs: Long = HEALTHY_AFTER_MS,
) {
    /** Times in a row the service went down with agents still running, since it last stayed up for [healthyAfterMs]. */
    private var losses = 0

    /** Whether the service has been seen up for the current run of agents (so a `serviceActive = false` is a loss, not a first start). */
    private var seenUp = false

    suspend fun run(decisions: Flow<LiveDecision>) {
        decisions.collectLatest { decision ->
            when (decision) {
                is LiveDecision.Track -> if (decision.serviceActive) keepUp() else bringUp()
                LiveDecision.Idle -> settle()
                LiveDecision.SignedOut -> {
                    settle()
                    stop()
                }
            }
        }
    }

    private fun settle() {
        losses = 0
        seenUp = false
    }

    private suspend fun keepUp() {
        seenUp = true
        start()
        delay(healthyAfterMs)
        losses = 0
    }

    private suspend fun bringUp() {
        if (seenUp) {
            seenUp = false
            losses++
            delay((restartBaseMs shl (losses - 1).coerceIn(0, 20)).coerceAtMost(restartMaxMs))
        }
        var attempt = 0
        while (attempt < maxStartAttempts) {
            start()
            delay((startGraceMs shl attempt.coerceAtMost(20)).coerceAtMost(restartMaxMs))
            attempt++
        }
    }

    companion object {
        /** How long a start is given to report the service up before it is asked for again. */
        const val START_GRACE_MS = 3_000L
        const val MAX_START_ATTEMPTS = 5
        /** First pause before bringing back a service that went down while agents still run. */
        const val RESTART_BASE_MS = 5_000L
        const val RESTART_MAX_MS = 5 * 60_000L
        /** A service up for this long counts as healthy again, and the next loss starts the pauses over. */
        const val HEALTHY_AFTER_MS = 60_000L
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
