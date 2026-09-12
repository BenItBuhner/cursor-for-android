package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunDigest
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Follows running agents (up to [maxTracked] at a time, like the iOS Live Activity's eight) through the [LiveRunHub]
 * and turns their snapshots into [TrackedRun]s for the live notification. The cap bounds the open streams, not what
 * is reported: [LiveActivityState.runningCount] always carries the full number of running agents. Runs that finish
 * are published once on [finished]. While active it refreshes the agent list periodically so agents started from
 * another surface, or runs whose stream expired, are picked up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunMonitor(
    private val agents: AgentRepository,
    private val hub: LiveRunHub,
    /**
     * Reads a run's record (`GET /v1/agents/{id}/runs/{runId}`) before it is followed: the authority on whether the
     * run is still going, and the source of its real start time (`createdAt`). Null when it cannot be read.
     */
    private val runRecord: suspend (agentId: String, runId: String) -> RunDto? = { _, _ -> null },
    private val refreshIntervalMs: Long = 60_000L,
    private val maxTracked: Int = MAX_TRACKED,
    private val nowProvider: () -> Long = AppClock::now,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(LiveActivityState())
    val state: StateFlow<LiveActivityState> = _state.asStateFlow()

    private val _finished = MutableSharedFlow<TrackedRun>(extraBufferCapacity = 64)
    val finished: SharedFlow<TrackedRun> = _finished.asSharedFlow()

    // Tracking runs on one thread; the collections are still concurrent because start/stop/markStopping are
    // called from the service's main thread.
    private val serial = dispatcher.limitedParallelism(1)
    @Volatile private var scope: CoroutineScope? = null
    private val trackers = ConcurrentHashMap<String, Tracker>()
    private val detailRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val stopping: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /**
     * Runs already reported on [finished]. Kept across [stop] — the service comes and goes as agents do, and a run
     * that finishes over a restart would otherwise be announced twice — and bounded, since nothing else would ever
     * take an id out of it.
     */
    private val finishedEmitted = RecentIds(MAX_REMEMBERED_FINISHES)
    /** Per Project, the names of its workers that finished while the monitor has been up, for the rolled-up card. */
    private val finishedWorkers = ConcurrentHashMap<String, List<String>>()

    private class Tracker(val runId: String, val job: Job)

    /** Remembers the last [max] ids it was shown, so "have I seen this?" cannot grow without end. */
    private class RecentIds(private val max: Int) {
        private val ids = LinkedHashSet<String>()

        /** True the first time an id is offered, false every time after. */
        @Synchronized
        fun add(id: String): Boolean {
            if (!ids.add(id)) return false
            if (ids.size > max) ids.iterator().run { next(); remove() }
            return true
        }
    }

    val isRunning: Boolean get() = scope != null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + serial)
        scope = s
        s.launch {
            agents.state
                // Rows restored from disk are not tracked until a fetch has confirmed they are still running.
                .filter { !it.isFromCache }
                .map { st -> st.agents.filter { it.isRunning }.sortedByDescending { it.updatedAtMillis } }
                .distinctUntilChanged()
                .collect { reconcile(s, it) }
        }
        s.launch {
            var tick = 0
            while (isActive) {
                delay(refreshIntervalMs)
                tick++
                // The newest page is where agents started elsewhere show up; a full pass every few ticks still
                // catches follow-ups on old agents without paging through everything each minute.
                agents.refresh(silent = true, depth = if (tick % FULL_REFRESH_EVERY == 0) RefreshDepth.Full else RefreshDepth.Quick)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        trackers.clear()
        detailRequested.clear()
        stopping.clear()
        _state.value = LiveActivityState()
    }

    /** Reflects a cancel request from the notification immediately; the run's terminal event confirms it. */
    fun markStopping(agentId: String) {
        stopping += agentId
        _state.update { st -> st.copy(running = st.running.map { if (it.agentId == agentId) it.copy(phase = LivePhase.Stopping) else it }) }
    }

    /** Reverts [markStopping] when the cancel request failed. */
    fun clearStopping(agentId: String) {
        stopping -= agentId
        _state.update { st ->
            st.copy(running = st.running.map { if (it.agentId == agentId && it.phase == LivePhase.Stopping) it.copy(phase = LivePhase.Running) else it })
        }
    }

    private fun reconcile(scope: CoroutineScope, running: List<Agent>) {
        // The count covers every running agent, not just the [maxTracked] followed below, and is published before any
        // tracker is dropped so an intermediate state never reports fewer agents than it lists.
        _state.update { st -> st.copy(runningCount = running.size) }
        val wanted = running.filter { it.latestRunId != null }.take(maxTracked).associateBy { it.id }
        // Agents without a known run id come from a summary-only list: load the detail record to learn it. One request
        // per agent at a time; a failed one (offline, a launch the server has not finished creating) is asked for again
        // at the next reconcile rather than never, or the agent would go unfollowed for as long as the monitor runs.
        // The note is dropped once an agent stops running, so the set tracks the list rather than everything ever seen.
        detailRequested.retainAll(running.mapTo(HashSet()) { it.id })
        running.filter { it.latestRunId == null && detailRequested.add(it.id) }.forEach { agent ->
            scope.launch { agents.loadDetail(agent.id).onFailure { detailRequested.remove(agent.id) } }
        }
        trackers.entries.toList().forEach { (agentId, tracker) ->
            val stillWanted = wanted[agentId]?.latestRunId == tracker.runId
            if (!stillWanted) {
                tracker.job.cancel()
                trackers.remove(agentId)
                stopping.remove(agentId)
                _state.update { st -> st.copy(running = st.running.filterNot { it.agentId == agentId }) }
                // The row may flip to a terminal status a beat before the tracker sees the terminal snapshot.
                val snapshot = hub.current(agentId, tracker.runId)
                val agent = agents.agent(agentId)
                if (snapshot?.finished == true && agent != null) publishFinished(toTracked(agent, snapshot))
            }
        }
        wanted.values.filter { !trackers.containsKey(it.id) }.forEach { agent ->
            val runId = agent.latestRunId ?: return@forEach
            trackers[agent.id] = Tracker(runId, scope.launch { track(agent, runId) })
        }
        _state.update { st ->
            val published = st.running.map { run -> wanted[run.agentId]?.let { run.withAgent(it) } ?: run }
            st.copy(
                running = published,
                // This pass only *starts* the trackers it wants; each publishes its run once it has read the run
                // record, which is a network round trip away. So a list with a tracker still to report has not been
                // caught up with — it is settling, not idle — and saying otherwise here is what let the live
                // notification's short idle grace expire before the first run arrived, taking the foreground service
                // down with it and leaving no notification at all. An agent nothing is being waited for — no run id,
                // and the detail read that would learn it failed — is not a tracker in flight: the pass is as caught
                // up as it can be, and that agent is asked about again at the next one.
                hasReconciled = st.hasReconciled || published.isNotEmpty() || trackers.isEmpty(),
            )
        }
    }

    private suspend fun track(agent: Agent, runId: String) {
        // Unless the run is already being followed (a conversation screen has it open), its record is read first: the
        // row only says the list called the run active, and the list can be behind the finish — or the row can be a
        // cache from days ago. A run the record reports over is folded into the row and never streamed: replaying a
        // finished run's log as though it were live is what stamped chats from weeks ago "updated just now".
        val followed = hub.current(agent.id, runId)
        val record = if (followed == null) runCatching { runRecord(agent.id, runId) }.getOrNull() else null
        if (record != null && record.statusEnum().isTerminal) {
            agents.patch(agent.id) { it.withLatestRun(record) }
            trackers.remove(agent.id)
            stopping.remove(agent.id)
            return
        }
        val startedAt = followed?.startedAtMillis
            ?: record?.let { parseIsoMillis(it.createdAt).takeIf { ms -> ms > 0 } }
            ?: agent.updatedAtMillis.takeIf { it > 0 }
            ?: nowProvider()
        upsert(TrackedRun(agent.id, runId, agent.name, agent.runStatus ?: RunStatus.CREATING, LivePhase.Starting, startedAt, branch = agent.branchName, prUrl = agent.prUrl))
        hub.snapshots(agent.id, runId, startedAt)
            .transformWhile { emit(it); !it.finished }
            .collect { snapshot ->
                val current = agents.agent(agent.id) ?: agent
                val tracked = toTracked(current, snapshot)
                if (snapshot.finished) {
                    trackers.remove(agent.id)
                    stopping.remove(agent.id)
                    _state.update { st -> st.copy(running = st.running.filterNot { it.agentId == agent.id }) }
                    publishFinished(tracked)
                } else {
                    upsert(tracked)
                }
            }
    }

    /**
     * Reports a finished run once. A Project's worker never gets a card of its own: its finish is the Project's news,
     * rolled up with the other workers of that Project that finished while the monitor has been up (see
     * [TrackedRun.rolledUpInto]), so however many workers a coordinator runs there is one line per Project.
     */
    private fun publishFinished(run: TrackedRun) {
        if (!finishedEmitted.add(run.runId)) return
        _finished.tryEmit(rolledUp(run))
    }

    private fun rolledUp(run: TrackedRun): TrackedRun {
        val agent = agents.agent(run.agentId) ?: return run
        val project = agent.parent?.takeIf { agent.isProjectChild } ?: return run
        val names = finishedWorkers.compute(project.id) { _, names -> ((names ?: emptyList()) + run.title).takeLast(MAX_ROLLED_UP_WORKERS) }.orEmpty()
        return run.rolledUpInto(project.id, agents.agent(project.id)?.name, names)
    }

    private fun toTracked(agent: Agent, snapshot: LiveRunHub.Snapshot): TrackedRun {
        val result = snapshot.result
        val phase = when {
            snapshot.finished -> LivePhase.Finished
            agent.id in stopping -> LivePhase.Stopping
            snapshot.eventCount == 0 -> LivePhase.Starting
            else -> LivePhase.Running
        }
        val branches = result?.git.toBranches().ifEmpty { agent.branches }
        return TrackedRun(
            agentId = agent.id,
            runId = snapshot.runId,
            title = agent.name,
            status = snapshot.status,
            phase = phase,
            startedAtMillis = snapshot.startedAtMillis,
            digest = RunDigest.from(snapshot.items),
            durationMs = result?.durationMs ?: if (snapshot.finished) (snapshot.finishedAtMillis ?: nowProvider()) - snapshot.startedAtMillis else null,
            branch = branches.firstOrNull { it.branch != null }?.branch,
            prUrl = branches.firstOrNull { it.prUrl != null }?.prUrl,
            summary = result?.text?.takeIf { it.isNotBlank() }
                ?: snapshot.items.lastOrNull { it is AssistantMessage && it.markdown.isNotBlank() }?.let { (it as AssistantMessage).markdown },
            finishedAtMillis = snapshot.finishedAtMillis,
        )
    }

    private fun TrackedRun.withAgent(agent: Agent) = copy(
        title = agent.name,
        branch = branch ?: agent.branchName,
        prUrl = prUrl ?: agent.prUrl,
    )

    private fun upsert(run: TrackedRun) {
        _state.update { st ->
            val list = if (st.running.any { it.agentId == run.agentId }) st.running.map { if (it.agentId == run.agentId) run else it } else st.running + run
            // A published run is the other way the monitor is caught up with the list: the tracker the reconcile
            // was waiting for has arrived.
            st.copy(running = list.sortedBy { it.startedAtMillis }, hasReconciled = true)
        }
    }

    companion object {
        /**
         * Streams followed at once; matches the iOS app, which tracks up to eight agents in its Live Activity. Each
         * tracked run is a live connection kept open by the foreground service, so this bounds the detail lines in
         * the notification, never the count it reports.
         */
        const val MAX_TRACKED = 8
        private const val FULL_REFRESH_EVERY = 5
        /** Far more finishes than a session sees, and small enough that the ids cost nothing to hold. */
        private const val MAX_REMEMBERED_FINISHES = 256
        /** Worker names a Project's rolled-up card lists at most; older finishes drop off the front. */
        private const val MAX_ROLLED_UP_WORKERS = 6
    }
}
