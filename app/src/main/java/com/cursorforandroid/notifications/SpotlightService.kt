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
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.data.repo.SpotlightFeed
import com.cursorforandroid.data.repo.trackedRun
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SpotlightTarget
import com.cursorforandroid.domain.SpotlightView
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.throttleLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (`dataSync`) behind the Spotlight notification: up while a chat or Project is in the
 * [SpotlightController], down as soon as there is none. It follows the target through [SpotlightFeed] — the run
 * streams the app already shares, never a connection of its own — and posts at most one update a second.
 *
 * Kept apart from [LiveNotificationService] on purpose: the Spotlight is the user's explicit ask for one chat, so it
 * runs whether or not the live roster is switched on, and ends on its own terms (the run finishing, Stop Spotlight,
 * a swipe) without touching the roster's.
 */
class SpotlightService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph: AppGraph by lazy { appGraph }
    private var inForeground = false
    private var following: Job? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // Every start was promised a startForeground, including the ones that only come to stop (see
        // LiveNotificationService.keepForegroundPromise): answered first, whatever happens next.
        if (!enterForeground()) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) stopSpotlight()
        if (!LiveNotifications.canShowSpotlight(this)) graph.spotlight.stop()
        if (following == null) following = scope.launch { follow() }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        if (inForeground) return true
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, SpotlightRenderer.SPOTLIGHT_ID, placeholder(), type)
        } catch (e: Exception) {
            Log.w(TAG, "Foreground start refused", e)
            graph.spotlight.stop()
            stopSelf()
            return false
        }
        inForeground = true
        return true
    }

    /** Stands in for the first frame, which is a stream read away. */
    private fun placeholder(): Notification {
        val target = graph.spotlight.target.value
        val agent = target?.let { graph.agents.agent(it.agentId) }
        val view = SpotlightView(
            agentId = target?.agentId.orEmpty(),
            title = agent?.name ?: getString(R.string.channel_spotlight_name),
            step = getString(R.string.spotlight_connecting),
            startedAtMillis = target?.startedAtMillis ?: AppClock.now(),
            isProject = target?.isProject == true,
        )
        return SpotlightRenderer.spotlight(this, view)
    }

    private suspend fun follow() {
        graph.spotlight.target.collectLatest { target ->
            if (target == null) {
                finish()
                return@collectLatest
            }
            coroutineScope {
                launch { refreshWhileAlone() }
                graph.spotlightFeed.of(target)
                    .throttleLatest(UPDATE_INTERVAL_MS)
                    .collect { frame -> show(target, frame) }
            }
        }
    }

    private fun show(target: SpotlightTarget, frame: SpotlightFeed.Frame) {
        when (frame) {
            is SpotlightFeed.Frame.Live -> {
                graph.spotlight.cover(target, frame.covered)
                LiveNotifications.post(this, SpotlightRenderer.SPOTLIGHT_ID, SpotlightRenderer.spotlight(this, frame.view))
            }
            is SpotlightFeed.Frame.Finished -> {
                announce(frame)
                graph.spotlight.end(target)
            }
            SpotlightFeed.Frame.Ended -> graph.spotlight.end(target)
        }
    }

    /**
     * The finished card, as the live roster would post it and under the same id, so a finish both report is one
     * card. The roster leaves a spotlighted chat out and may have stopped for want of anything else to show, so
     * without this the finish would go unannounced.
     */
    private fun announce(frame: SpotlightFeed.Frame.Finished) {
        val run = trackedRun(frame.agent, frame.snapshot, stopping = false, now = AppClock.now())
        if (run.status == RunStatus.CANCELLED) return
        scope.launch {
            if (graph.prefs.localAgentState.first().isSnoozed(run.agentId, AppClock.now())) return@launch
            if (!graph.prefs.projectNotifications.first().announces(frame.agent)) return@launch
            val foreground = runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
            if (foreground && graph.conversations.isAttached(run.agentId)) return@launch
            LiveNotifications.post(this@SpotlightService, LiveNotificationRenderer.finishedId(run.agentId), LiveNotificationRenderer.finished(this@SpotlightService, run))
        }
    }

    /**
     * A Project's chats start and stop in the agent list, which the live roster's monitor refreshes every minute
     * while it is up. With the roster down (switched off, or idle because the Spotlight covers all that runs) the
     * Spotlight does the same quick refresh itself.
     */
    private suspend fun refreshWhileAlone() {
        while (scope.isActive) {
            delay(REFRESH_INTERVAL_MS)
            if (!LiveNotificationService.active.value) graph.agents.refresh(silent = true, depth = RefreshDepth.Quick)
        }
    }

    /**
     * Stop Spotlight, from the notification. What the Spotlight covered is back on the roster's books, and the roster
     * is asked to show it now rather than the next time the app is opened: the tap that sent this lets the app start
     * a foreground service from the background.
     */
    private fun stopSpotlight() {
        val covered = graph.spotlight.covered.value
        graph.spotlight.stop()
        scope.launch {
            val stillRunning = graph.agents.state.value.agents.any { it.id in covered && it.isRunning }
            if (stillRunning && graph.prefs.liveNotifications.first()) LiveNotificationService.start(this@SpotlightService)
        }
    }

    private fun finish() {
        // A start that arrived meanwhile wants the service again; it will find the next target on its own.
        if (!stopSelfResult(latestStartId)) return
        inForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** Android 15+ caps `dataSync` services at six hours in the background; the Spotlight ends with it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync time budget spent; ending the Spotlight")
        graph.spotlight.stop()
        inForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        following = null
        inForeground = false
        // Destroyed with a Spotlight still set (the system reclaimed the service): nothing is showing it any more.
        graph.spotlight.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Spotlight"
        const val ACTION_STOP = "com.cursorforandroid.action.STOP_SPOTLIGHT"
        /** Android rate-limits an app's notification updates; one a second is well inside that and reads as live. */
        const val UPDATE_INTERVAL_MS = 1_000L
        private const val REFRESH_INTERVAL_MS = 60_000L

        /** Must be called from the foreground, like any foreground service start. False when the platform refused it. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, SpotlightService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Start refused", e)
            false
        }

        fun stopIntent(context: Context): Intent = Intent(context, SpotlightService::class.java).setAction(ACTION_STOP)

        /**
         * The long-press menu's item: takes [agent] out of the Spotlight when it is there, else puts it there in place
         * of whatever was. With notifications off there is nothing to show it in, so the settings page opens instead.
         */
        fun toggle(context: Context, spotlight: SpotlightController, agent: Agent) {
            if (spotlight.isSpotlit(agent.id)) {
                spotlight.stop()
                return
            }
            if (!LiveNotifications.canShowSpotlight(context)) {
                Toast.makeText(context, R.string.spotlight_notifications_off, Toast.LENGTH_SHORT).show()
                runCatching { context.startActivity(LiveNotifications.appNotificationSettingsIntent(context)) }
                return
            }
            spotlight.spotlight(agent)
            if (!start(context)) spotlight.stop()
        }
    }
}
