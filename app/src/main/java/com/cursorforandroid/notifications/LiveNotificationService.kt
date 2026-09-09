package com.cursorforandroid.notifications

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (`dataSync`) that keeps the run streams alive while the app is in the background and owns
 * the live notification. It starts whenever at least one agent is running and stops itself once the last one
 * finishes, when live notifications are switched off, or when the platform's data-sync time budget runs out.
 */
class LiveNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph: AppGraph by lazy { appGraph }
    private var inForeground = false
    private var idleJob: Job? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // The only reason to follow runs from the background is the notification. Without one the service would hold
        // a dataSync budget open and keep up to eight streams alive to produce nothing the user can see.
        if (!LiveNotifications.canShowLive(this)) {
            shutdown(force = true)
            return START_NOT_STICKY
        }
        val firstStart = !inForeground
        if (!enterForeground()) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP_RUN) {
            val agentId = intent.getStringExtra(EXTRA_AGENT_ID)
            val runId = intent.getStringExtra(EXTRA_RUN_ID)
            if (agentId != null && runId != null) stopRun(agentId, runId)
        } else if (!firstStart) {
            // Re-issued start (running set changed, or notification permission was just granted): show the current
            // state, or start the wait over if this arrived just as a shutdown was declining to stop the service.
            val state = graph.runMonitor.state.value
            if (state.running.isNotEmpty()) post(LiveNotificationRenderer.LIVE_ID, LiveNotificationRenderer.live(this, state)) else scheduleIdleShutdown(state)
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        if (inForeground) return true
        val monitor = graph.runMonitor
        val initial = monitor.state.value.takeIf { it.running.isNotEmpty() }
            ?.let { LiveNotificationRenderer.live(this, it) }
            ?: LiveNotificationRenderer.connecting(this)
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, LiveNotificationRenderer.LIVE_ID, initial, type)
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException (background start) or a missing permission: give up quietly.
            stopSelf()
            return false
        }
        inForeground = true
        // Subscribe before the monitor starts so no finish can slip past the (replay-less) shared flow.
        scope.launch { monitor.finished.collect { onRunFinished(it) } }
        monitor.start()
        scope.launch {
            combine(monitor.state, graph.prefs.liveNotifications) { state, enabled -> state to enabled }
                .collect { (state, enabled) ->
                    when {
                        !enabled -> shutdown()
                        state.running.isEmpty() -> scheduleIdleShutdown(state)
                        else -> {
                            idleJob?.cancel()
                            idleJob = null
                            post(LiveNotificationRenderer.LIVE_ID, LiveNotificationRenderer.live(this@LiveNotificationService, state))
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
            shutdown()
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

    private fun post(id: Int, notification: Notification) {
        // Checked inline (not via LiveNotifications.hasPermission) so lint's MissingPermission analysis can see it.
        // POST_NOTIFICATIONS only exists from API 33; earlier releases report it as denied, hence the version guard.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; the next state change tries again.
        }
    }

    /**
     * [force] stops the service whatever else is in flight, for the cases where staying is not an option.
     * Otherwise `stopSelfResult` is asked: a start command that arrived while this shutdown was being decided means
     * the service is wanted again, and then nothing is torn down. The start that is already on its way re-posts the
     * notification or starts the idle wait over.
     */
    private fun shutdown(force: Boolean = false) {
        idleJob?.cancel()
        idleJob = null
        if (!force && !stopSelfResult(latestStartId)) return
        graph.runMonitor.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (force) stopSelf()
    }

    /** Android 15+ ends `dataSync` services after their daily budget; the run keeps going in the cloud. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Not optional: the platform kills the process instead if the service is still up when this returns.
        shutdown(force = true)
    }

    override fun onDestroy() {
        scope.cancel()
        graph.runMonitor.stop()
        inForeground = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_RUN = "com.cursorforandroid.action.STOP_RUN"
        private const val EXTRA_AGENT_ID = "agent_id"
        private const val EXTRA_RUN_ID = "run_id"
        private const val IDLE_GRACE_MS = 2_500L
        private const val FIRST_RECONCILE_TIMEOUT_MS = 20_000L

        /**
         * Idempotent: a running service just receives another start command. Must be called from the foreground;
         * `ForegroundServiceStartNotAllowedException` (Android 12+) is swallowed because the next running-set change
         * or the next time the app comes forward tries again, and there is nothing to tell the user in the meantime.
         */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, serviceIntent(context)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(serviceIntent(context)) }
        }

        private fun serviceIntent(context: Context) = Intent(context, LiveNotificationService::class.java)

        fun stopRunIntent(context: Context, agentId: String, runId: String): Intent =
            serviceIntent(context)
                .setAction(ACTION_STOP_RUN)
                .putExtra(EXTRA_AGENT_ID, agentId)
                .putExtra(EXTRA_RUN_ID, runId)
    }
}
