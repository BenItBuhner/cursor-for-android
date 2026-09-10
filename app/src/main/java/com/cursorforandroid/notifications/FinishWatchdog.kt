package com.cursorforandroid.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.cursorforandroid.appGraph
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.data.repo.parseIsoMillis
import com.cursorforandroid.data.repo.statusEnum
import com.cursorforandroid.data.repo.withLatestRun
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The safety net under the live notification. Only the foreground service can follow a run's stream while the app
 * is in the background, and the platform can take the service away at any moment: a start refused, the process
 * killed under memory pressure, the phone rebooted, Android 15 ending a `dataSync` service six hours after the app
 * was last in front. None of that ends the runs — the agents keep working in the cloud — but it used to end every
 * notification about them: the ongoing one vanished, and the "Finished" cards never came.
 *
 * A check reads the records of the runs the app believes are going and announces the ones that are over, which is
 * the one thing that needs no foreground service. It cannot bring the live notification back (an app in the
 * background may not start a foreground service; the coordinator does that the next time the app is in front), so
 * the caller keeps checking, on the schedule [Outcome] asks for, until nothing is running any more.
 */
class FinishWatchdog(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val prefs: PreferencesStore,
    /** `GET /v1/agents/{id}/runs/{runId}`, the authority on whether a run is over; null when it cannot be read. */
    private val runRecord: suspend (agentId: String, runId: String) -> RunDto?,
    /** Whether the service is up in this process, in which case it — not the watchdog — announces the finishes. */
    private val isServiceActive: () -> Boolean,
    private val announce: (TrackedRun) -> Unit,
    private val refreshTimeoutMs: Long = REFRESH_TIMEOUT_MS,
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val nowProvider: () -> Long = AppClock::now,
    /** `agentId/runId` of every finish announced so far; shared by every watchdog in the process unless a test says otherwise. */
    private val announced: MutableSet<String> = announcedInProcess,
) {
    sealed interface Outcome {
        /** The service is up and owns the notifications; check again later in case it goes away. */
        data object ServiceUp : Outcome

        /** Runs are still going with no service to follow them; check again soon. */
        data class Watching(val running: Int) : Outcome

        /** Nothing is running any more. */
        data object Done : Outcome

        /** Signed out, or live notifications switched off: nothing to announce, now or later. */
        data object Off : Outcome
    }

    suspend fun check(): Outcome {
        if (isServiceActive()) return Outcome.ServiceUp
        if (!prefs.liveNotifications.first()) return Outcome.Off
        session.restoreIfNeeded()
        if (session.state.value !is SessionState.SignedIn) return Outcome.Off
        // A fresh process knows the list only from disk, which is where the rows that were running last time are.
        agents.restoreFromCache()
        val before = agents.state.value.agents.filter { it.isRunning && it.latestRunId != null }
        if (before.isEmpty()) return Outcome.Done

        // A list from disk, or one the app last fetched a while ago, is brought up to date first. The fetch reads the
        // records of the rows that say a run is active (see AgentRepository.verifyRunStatuses), and it is what makes the
        // settled rows reach the disk: the cache is never written while the list is the one restored from it.
        val fetches = agents.refreshCompleted.value
        val stale = agents.state.value.isFromCache || agents.lastRefreshedAt == 0L || nowProvider() - agents.lastRefreshedAt > staleAfterMs
        if (stale) withTimeoutOrNull(refreshTimeoutMs) { agents.refresh(silent = true, depth = RefreshDepth.Quick) }
        val refreshed = agents.refreshCompleted.value > fetches

        for (was in before.take(MAX_CHECKED)) {
            val runId = was.latestRunId ?: continue
            val now = agents.agent(was.id) ?: continue
            // The row moved on to a newer turn: the user followed up, so the outcome of the old one is old news.
            if (now.latestRunId != runId) continue
            val record: RunDto?
            if (now.runStatus?.isTerminal == true) {
                record = null
            } else {
                // A fetch that just landed has read this record (the row is among the newest active ones); its word stands.
                if (refreshed) continue
                val read = runCatching { runRecord(was.id, runId) }.getOrNull() ?: continue
                if (!read.statusEnum().isTerminal) continue
                agents.patch(was.id) { it.withLatestRun(read) }
                record = read
            }
            val settled = agents.agent(was.id) ?: continue
            if (settled.runStatus == RunStatus.CANCELLED) continue
            val local = prefs.localAgentState.first()
            if (local.isSnoozed(was.id, nowProvider())) continue
            if (announced.add("${was.id}/$runId")) announce(trackedRun(settled, runId, record))
        }
        val running = agents.state.value.agents.count { it.isRunning }
        return if (running > 0) Outcome.Watching(running) else Outcome.Done
    }

    /**
     * The finished card's content from the row (and the record, when one was read): the row carries the final reply,
     * duration, branch and pull request the record gave it. No tool digest — nothing streamed — so the card's second
     * line is the duration, as it is for any run whose tools reported no line counts.
     */
    private fun trackedRun(agent: Agent, runId: String, record: RunDto?): TrackedRun {
        val finishedAt = record?.let { parseIsoMillis(it.updatedAt).takeIf { ms -> ms > 0 } }
            ?: agent.updatedAtMillis.takeIf { it > 0 }
            ?: nowProvider()
        val startedAt = record?.let { parseIsoMillis(it.createdAt).takeIf { ms -> ms > 0 } }
            ?: (finishedAt - (agent.durationMs ?: 0L))
        return TrackedRun(
            agentId = agent.id,
            runId = runId,
            title = agent.name,
            status = agent.runStatus ?: RunStatus.UNKNOWN,
            phase = LivePhase.Finished,
            startedAtMillis = startedAt,
            durationMs = agent.durationMs,
            branch = agent.branchName,
            prUrl = agent.prUrl,
            summary = agent.summary,
            finishedAtMillis = finishedAt,
        )
    }

    companion object {
        /** Rows read per check; the same cap as the refresh's own record reads, newest activity first. */
        const val MAX_CHECKED = 12
        const val REFRESH_TIMEOUT_MS = 30_000L
        /** A list the app fetched longer ago than this is fetched again before the rows are trusted. */
        const val STALE_AFTER_MS = 10 * 60_000L

        /**
         * `agentId/runId` of every finish announced by a watchdog in this process. Across processes the settled row on
         * disk does the same job: a run that reads finished there is never a candidate again.
         */
        private val announcedInProcess: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

/**
 * Runs a [FinishWatchdog] check from `JobScheduler`. Armed by the service for as long as it is up, and re-armed by
 * every check that finds it up, so a check only ever does real work once the service has been gone for a while —
 * or the whole process with it: the job is persisted, so it also comes back after a reboot. A check that finds runs
 * still going re-arms itself until they are over.
 */
class FinishWatchdogJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            val outcome = try {
                check()
            } catch (e: CancellationException) {
                throw e // onStopJob already told the scheduler; jobFinished must not follow.
            } catch (t: Throwable) {
                Log.w(TAG, "Check failed; trying again later", t)
                FinishWatchdog.Outcome.Watching(running = 0)
            }
            when (outcome) {
                FinishWatchdog.Outcome.ServiceUp -> arm(this@FinishWatchdogJobService, WHILE_SERVICE_ALIVE_MS)
                is FinishWatchdog.Outcome.Watching -> arm(this@FinishWatchdogJobService, POLL_MS)
                FinishWatchdog.Outcome.Done, FinishWatchdog.Outcome.Off -> Unit
            }
            jobFinished(params, false)
        }
        return true
    }

    private suspend fun check(): FinishWatchdog.Outcome {
        val graph = appGraph
        LiveNotifications.ensureChannels(this)
        return FinishWatchdog(
            session = graph.session,
            agents = graph.agents,
            prefs = graph.prefs,
            runRecord = { agentId, runId -> graph.session.current.api.getRun(agentId, runId) },
            isServiceActive = { LiveNotificationService.active.value },
            announce = { run -> LiveNotifications.post(this, LiveNotificationRenderer.finishedId(run.agentId), LiveNotificationRenderer.finished(this, run)) },
        ).check()
    }

    /** The system wants the slot back: stop and let it reschedule. */
    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        job = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FinishWatchdog"
        const val JOB_ID = 0x4C4E5744

        /** How long the service may stay silent before a check runs; every check that finds it up pushes this out again. */
        val WHILE_SERVICE_ALIVE_MS: Long = TimeUnit.MINUTES.toMillis(15)

        /** The first check after the service stopped with runs still going. */
        val AFTER_SERVICE_LOSS_MS: Long = TimeUnit.MINUTES.toMillis(2)

        /** Between checks while runs are going and no service follows them. */
        val POLL_MS: Long = TimeUnit.MINUTES.toMillis(5)

        /** Schedules a check in [delayMs] (once the device is online), replacing any check already scheduled. */
        fun arm(context: Context, delayMs: Long) {
            val scheduler = scheduler(context) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, FinishWatchdogJobService::class.java))
                .setMinimumLatency(delayMs)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }.onFailure { Log.w(TAG, "Could not arm", it) }
        }

        fun disarm(context: Context) {
            runCatching { scheduler(context)?.cancel(JOB_ID) }
        }

        fun isArmed(context: Context): Boolean = scheduler(context)?.getPendingJob(JOB_ID) != null

        private fun scheduler(context: Context): JobScheduler? = context.getSystemService(JobScheduler::class.java)
    }
}
