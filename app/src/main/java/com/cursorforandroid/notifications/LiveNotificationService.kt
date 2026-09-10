package com.cursorforandroid.notifications

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.cursorforandroid.AppGraph
import com.cursorforandroid.R
import com.cursorforandroid.appGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (`dataSync`) that keeps the run streams alive while the app is in the background and owns
 * the live notification. It starts whenever at least one agent is running and stops itself once the last one
 * finishes, when live notifications are switched off, or when the platform's data-sync time budget runs out.
 *
 * Whether it is up is published on [active], because the platform can take it down at any moment — a refused
 * start, a process killed under memory pressure, Android 15 ending a `dataSync` service six hours after the app was
 * last in front — and none of that ends the runs. [LiveNotificationCoordinator] watches [active] to bring the
 * service back whenever the app is in front, and [FinishWatchdogJobService] is armed for as long as it is up so the
 * finished cards still arrive when it is gone and the app is not around to restart it.
 */
class LiveNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph: AppGraph by lazy { appGraph }
    private var inForeground = false
    private var idleJob: Job? = null
    /** Ids of the per-agent cards currently up, so the ones whose agent stopped running can be taken down. */
    private var agentCardIds: Set<Int> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val firstStart = !inForeground
        if (!enterForeground()) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP_RUN) {
            val agentId = intent.getStringExtra(EXTRA_AGENT_ID)
            val runId = intent.getStringExtra(EXTRA_RUN_ID)
            if (agentId != null && runId != null) stopRun(agentId, runId)
        } else if (!firstStart) {
            // Re-issued start (running set changed, or notification permission was just granted): show the current state.
            graph.runMonitor.state.value.takeIf { it.running.isNotEmpty() }?.let { postLive(LiveNotificationRenderer.live(this, it)) }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        if (inForeground) return true
        val monitor = graph.runMonitor
        val initial = monitor.state.value.takeIf { it.running.isNotEmpty() }?.let { LiveNotificationRenderer.live(this, it) }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, LiveNotificationRenderer.LIVE_ID, initial?.summary ?: LiveNotificationRenderer.connecting(this), type)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (a background start, or Android 15's dataSync budget spent
            // before the app was in front again) or a missing permission. Not fatal: [active] stays false, and the
            // coordinator tries again from the foreground. Logged, because a refused start is exactly what "the
            // notification never showed up" looks like from the outside.
            Log.w(TAG, "Foreground start refused", e)
            stopSelf()
            return false
        }
        inForeground = true
        _active.value = true
        // The summary is up as the foreground notification; the per-agent cards go with it.
        initial?.let { postAgentCards(it) }
        // The safety net for the finished cards, in case this process does not live to post them.
        FinishWatchdogJobService.arm(this, FinishWatchdogJobService.WHILE_SERVICE_ALIVE_MS)
        // Subscribe before the monitor starts so no finish can slip past the (replay-less) shared flow.
        scope.launch { monitor.finished.collect { onRunFinished(it) } }
        monitor.start()
        scope.launch {
            combine(monitor.state, graph.prefs.liveNotifications) { state, enabled -> state to enabled }
                .collect { (state, enabled) ->
                    when {
                        !enabled -> shutdown(keepWatching = false)
                        state.running.isEmpty() -> scheduleIdleShutdown(state)
                        else -> {
                            idleJob?.cancel()
                            idleJob = null
                            postLive(LiveNotificationRenderer.live(this@LiveNotificationService, state))
                        }
                    }
                }
        }
        return true
    }

    /** Waits briefly so a follow-up that starts right after a run finishes keeps the same notification. */
    private fun scheduleIdleShutdown(state: LiveActivityState) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(if (state.hasReconciled) IDLE_GRACE_MS else FIRST_RECONCILE_TIMEOUT_MS)
            // Nothing is being followed. When the list agrees nothing is running the watchdog is stood down; when it
            // still says otherwise (or was never reconciled) the watchdog keeps reading the records instead.
            val current = graph.runMonitor.state.value
            shutdown(keepWatching = !current.hasReconciled || current.totalRunning > 0)
        }
    }

    private fun onRunFinished(run: TrackedRun) {
        // A cancel was requested by the user somewhere; nothing to announce.
        if (run.status == RunStatus.CANCELLED) return
        // The user is looking at this very conversation: the transcript already shows the result.
        val foreground = runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
        if (foreground && graph.conversations.isAttached(run.agentId)) return
        post(LiveNotificationRenderer.finishedId(run.agentId), LiveNotificationRenderer.finished(this, run))
    }

    private fun stopRun(agentId: String, runId: String) {
        val monitor = graph.runMonitor
        monitor.markStopping(agentId)
        scope.launch {
            graph.agents.cancelRun(agentId, runId).onFailure { t ->
                monitor.clearStopping(agentId)
                Toast.makeText(this@LiveNotificationService, getString(R.string.notif_stop_failed, t.userMessage()), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun post(id: Int, notification: Notification) = LiveNotifications.post(this, id, notification)

    /** Summary first so the group exists before its children, then the cards; cards for agents no longer running go. */
    private fun postLive(cards: LiveCards) {
        post(LiveNotificationRenderer.LIVE_ID, cards.summary)
        postAgentCards(cards)
    }

    private fun postAgentCards(cards: LiveCards) {
        cards.agents.forEach { (id, notification) -> post(id, notification) }
        (agentCardIds - cards.agents.keys).forEach { LiveNotifications.cancel(this, it) }
        agentCardIds = cards.agents.keys
    }

    /** `STOP_FOREGROUND_REMOVE` only takes the summary down; the cards are ordinary notifications and go by hand. */
    private fun cancelAgentCards() {
        agentCardIds.forEach { LiveNotifications.cancel(this, it) }
        agentCardIds = emptySet()
    }

    /**
     * Stops following the runs and takes the live notification down. With [keepWatching] the runs are believed to be
     * still going, so the watchdog is armed to announce their finishes; without it there is nothing left to watch.
     */
    private fun shutdown(keepWatching: Boolean) {
        idleJob?.cancel()
        idleJob = null
        graph.runMonitor.stop()
        if (keepWatching) {
            FinishWatchdogJobService.arm(this, FinishWatchdogJobService.AFTER_SERVICE_LOSS_MS)
        } else {
            FinishWatchdogJobService.disarm(this)
        }
        cancelAgentCards()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Android 15+ ends `dataSync` services six hours after the app was last in front; the runs keep going in the
     * cloud. An app in the background may not start another foreground service, so from here the watchdog announces
     * the finishes, and the coordinator brings the live notification back the next time the app is in front.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync time budget spent; handing the running agents to the watchdog")
        shutdown(keepWatching = true)
    }

    override fun onDestroy() {
        scope.cancel()
        graph.runMonitor.stop()
        cancelAgentCards()
        inForeground = false
        _active.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveNotifications"
        const val ACTION_STOP_RUN = "com.cursorforandroid.action.STOP_RUN"
        private const val EXTRA_AGENT_ID = "agent_id"
        private const val EXTRA_RUN_ID = "run_id"
        private const val IDLE_GRACE_MS = 2_500L
        private const val FIRST_RECONCILE_TIMEOUT_MS = 20_000L

        private val _active = MutableStateFlow(false)

        /** True from a successful `startForeground` until the service is destroyed: whether the live notification is being kept. */
        val active: StateFlow<Boolean> = _active.asStateFlow()

        /**
         * Idempotent: a running service just receives another start command. Must be called from the foreground.
         * Returns false when the platform refused the start outright (an app in the background, on Android 12+); a
         * start that is accepted can still fail in `startForeground`, which [active] reports.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, LiveNotificationService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Start refused", e)
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveNotificationService::class.java)) }
        }

        fun stopRunIntent(context: Context, agentId: String, runId: String): Intent =
            Intent(context, LiveNotificationService::class.java)
                .setAction(ACTION_STOP_RUN)
                .putExtra(EXTRA_AGENT_ID, agentId)
                .putExtra(EXTRA_RUN_ID, runId)
    }
}
