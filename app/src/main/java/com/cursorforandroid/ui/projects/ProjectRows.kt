package com.cursorforandroid.ui.projects

import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectWorker
import com.cursorforandroid.ui.agents.AgentsViewModel

/** One primary as the Project section draws it: the worker and its row. */
internal data class WorkerLine(val worker: ProjectWorker, val row: AgentRow)

/**
 * What the Project section draws of its primaries, built ahead of the frame (see [ProjectViewModel.panel]): a line per
 * primary, the first of an id listed twice, and the summary's counts over the primaries as listed.
 */
internal data class ProjectRows(val workers: List<WorkerLine> = emptyList(), val running: Int = 0, val needsInput: Int = 0) {
    /** Each primary's line by its id, built with the rows rather than on the frame that asks. */
    val byId: Map<String, WorkerLine> = workers.associateBy { it.worker.agent.id }

    companion object {
        fun of(state: ProjectViewState, local: LocalAgentState, nowMillis: Long): ProjectRows = ProjectRowsBuilder().build(state, local, nowMillis)
    }
}

/**
 * Builds [ProjectRows], keeping each line from the build before while its worker, the device's own state and the clock's
 * minute are what they were: a Project of a hundred primaries, one of which moved, rebuilds that one's row. One build at
 * a time.
 */
internal class ProjectRowsBuilder {
    private var lastLocal: LocalAgentState? = null
    private var lastTick = Long.MIN_VALUE
    private var lines: Map<String, WorkerLine> = emptyMap()

    /** Rows built rather than kept, for the scale benchmark. */
    @Volatile
    var rowsBuilt = 0L
        private set

    fun build(state: ProjectViewState, local: LocalAgentState, nowMillis: Long): ProjectRows {
        val tick = nowMillis / AgentsViewModel.CLOCK_TICK_MS
        val kept = if (local == lastLocal && tick == lastTick) lines else emptyMap()
        val built = state.workers.distinctBy { it.agent.id }.map { worker ->
            kept[worker.agent.id]?.takeIf { it.worker == worker } ?: WorkerLine(worker, AgentListOrganizer.toRow(worker.agent, local, nowMillis)).also { rowsBuilt++ }
        }
        lastLocal = local
        lastTick = tick
        return ProjectRows(
            workers = built,
            running = state.workers.count { it.agent.isRunning } + (if (state.root?.isRunning == true) 1 else 0),
            needsInput = state.workers.count { it.agent.hasPendingInteraction },
        ).also { lines = it.byId }
    }
}
