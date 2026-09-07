package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunDigest
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Follows every running agent (up to [maxTracked], like the iOS Live Activity's eight) through the [LiveRunHub]
 * and turns their snapshots into [TrackedRun]s for the live notification. Runs that finish are published once on
 * [finished]. While active it refreshes the agent list periodically so agents started from another surface, or
 * runs whose stream expired, are picked up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunMonitor(
    private val agents: AgentRepository,
    private val hub: LiveRunHub,
    /** Resolves a run's real start time (its `createdAt`); falls back to the agent's `updatedAt` when null. */
    private val runStartedAt: suspend (agentId: String, runId: String) -> Long? = { _, _ -> null },
    private val refreshIntervalMs: Long = 60_000L,
    private val maxTracked: Int = MAX_TRACKED,
    private val nowProvider: () -> Long = System::currentTimeMillis,
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
    private val finishedEmitted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private class Tracker(val runId: String, val job: Job)

    val isRunning: Boolean get() = scope != null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + serial)
        scope = s
        s.launch {
            agents.state
                .map { st -> st.agents.filter { it.isRunning }.sortedByDescending { it.updatedAtMillis } }
                .distinctUntilChanged()
                .collect { reconcile(s, it) }
        }
        s.launch {
            while (isActive) {
                delay(refreshIntervalMs)
                agents.refresh()
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        trackers.clear()
        detailRequested.clear()
        stopping.clear()
        finishedEmitted.clear()
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
        val wanted = running.filter { it.latestRunId != null }.take(maxTracked).associateBy { it.id }
        // Agents without a known run id come from a summary-only list: load the detail record once to learn it.
        running.filter { it.latestRunId == null && detailRequested.add(it.id) }.forEach { agent ->
            scope.launch { agents.loadDetail(agent.id) }
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
            st.copy(
                running = st.running.map { run -> wanted[run.agentId]?.let { run.withAgent(it) } ?: run },
                hasReconciled = true,
            )
        }
    }

    private suspend fun track(agent: Agent, runId: String) {
        val startedAt = hub.current(agent.id, runId)?.startedAtMillis
            ?: runCatching { runStartedAt(agent.id, runId) }.getOrNull()
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

    private fun publishFinished(run: TrackedRun) {
        if (finishedEmitted.add(run.runId)) _finished.tryEmit(run)
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
            st.copy(running = list.sortedBy { it.startedAtMillis })
        }
    }

    companion object {
        /** Matches the iOS app, which tracks up to eight agents in its Live Activity. */
        const val MAX_TRACKED = 8
    }
}
