package com.cursorforandroid.data.faults

import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.fixtures.BigProject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** The shared S50/S200/S500 account shape used by the scale-performance workstream. */
internal enum class ScaleFleet(
    val label: String,
    val total: Int,
    val smallProjects: Int,
    val smallWorkers: Int,
    val bigWorkers: Int,
    val running: Int,
    val bigRunning: Int,
) {
    S50("S50", 50, 3, 5, 20, 15, 10),
    S200("S200", 200, 6, 12, 120, 60, 40),
    S500("S500", 500, 10, 15, 150, 120, 60),
}

/**
 * Populates [FaultServer] with the workstream fleet and advances its running agents in ten-second logical ticks.
 * A tick emits one 200-character step per logical second, moves every running row, and turns over roughly 5% of the
 * running set. This keeps a 60-second sample to six wall-clock seconds while requests still pay phone RTT.
 */
internal class ScaleFleetServer(
    val server: FaultServer,
    val fleet: ScaleFleet,
    startAt: Long = 1_800_000_000_000L,
) {
    val bigProjectId: String = BigProject.AGENT_ID
    val smallProjectIds = (0 until fleet.smallProjects).map { "bc-scale-project-$it" }
    val bigWorkerIds = (0 until fleet.bigWorkers).map { "bc-scale-big-worker-$it" }
    val smallWorkerIds = smallProjectIds.flatMapIndexed { p, _ ->
        (0 until fleet.smallWorkers).map { w -> "bc-scale-small-$p-worker-$w" }
    }
    val projectIds = smallProjectIds + bigProjectId
    val allIds: List<String>

    private val runningIds = ConcurrentHashMap.newKeySet<String>()
    private var sequence = 0
    var logicalNow: Long = startAt
        private set

    init {
        val fixed = projectIds + bigWorkerIds + smallWorkerIds
        require(fixed.size <= fleet.total) { "${fleet.label} project fixture has ${fixed.size} rows, over ${fleet.total}" }
        val ordinary = (0 until fleet.total - fixed.size).map { "bc-scale-chat-$it" }
        allIds = fixed + ordinary
        allIds.forEachIndexed { i, id ->
            val at = startAt - (30L * 86_400_000L * i / max(1, fleet.total - 1))
            addFinished(id, if (id == bigProjectId) BigProject.AGENT_NAME else id, at)
        }

        smallProjectIds.forEachIndexed { p, project ->
            val children = smallWorkerIds.filter { it.startsWith("bc-scale-small-$p-") }
            server.composers[project] = server.composers.getValue(project).copy(project = true)
            server.workers[project] = children.map { it to "MANAGER_SPAWN_KIND_CREATED" }
            children.forEach { child -> server.composers[child] = server.composers.getValue(child).copy(manager = project) }
        }
        server.composers[bigProjectId] = server.composers.getValue(bigProjectId).copy(project = true)
        server.workers[bigProjectId] = bigWorkerIds.map { it to "MANAGER_SPAWN_KIND_CREATED" }
        bigWorkerIds.forEach { child -> server.composers[child] = server.composers.getValue(child).copy(manager = bigProjectId) }
        server.rootProjects += projectIds
        addBigTranscript(startAt)

        val selected = buildList {
            add(bigProjectId)
            addAll(bigWorkerIds.take(fleet.bigRunning))
            addAll((smallWorkerIds + ordinary + smallProjectIds).filterNot { it == bigProjectId }.take(fleet.running - size))
        }
        check(selected.size == fleet.running)
        selected.forEach { start(it) }
        server.clock = { logicalNow }
    }

    fun runningCount(): Int = runningIds.size

    /** Advances ten logical seconds and keeps the running population stable while agents finish and restart. */
    fun tick() {
        logicalNow += LOGICAL_TICK_MS
        val stepAt = iso(logicalNow)
        runningIds.toList().forEach { id ->
            val runId = server.agents[id]?.latestRunId ?: return@forEach
            val steps = (1..10).map { n ->
                "assistant" to """{"text":"<$runId:${logicalNow / 1_000}:$n> ${"x".repeat(200)}"}"""
            }
            server.appendRunEvents(runId, steps)
            server.runs[runId]?.let { server.runs[runId] = it.copy(updatedAt = stepAt) }
            server.agents[id]?.let { server.agents[id] = it.copy(updatedAt = stepAt) }
            server.composers[id]?.let { server.composers[id] = it.copy(activityMs = logicalNow, running = true) }
        }

        val flipsEachWay = max(1, fleet.running / 40)
        val stopping = runningIds.filterNot { it == bigProjectId }.sorted().take(flipsEachWay)
        stopping.forEach {
            server.endTurn(it, durationMs = LOGICAL_TICK_MS)
            runningIds.remove(it)
        }
        val starting = allIds.filterNot { it in runningIds }.filterNot { it == bigProjectId }.takeLast(flipsEachWay)
        starting.forEach(::start)
        check(runningIds.size == fleet.running)
    }

    private fun start(id: String) {
        val runId = "run-scale-${fleet.label.lowercase()}-${sequence++}-$id"
        server.startTurnElsewhere(id, "Continue the scale task for $id.", runId)
        runningIds += id
    }

    private fun addFinished(id: String, name: String, at: Long) {
        val runId = "run-finished-$id"
        server.runs[runId] = RunDto(
            id = runId,
            agentId = id,
            status = "FINISHED",
            createdAt = iso(at - 60_000L),
            updatedAt = iso(at),
            durationMs = 60_000L,
            result = "Done.",
        )
        server.logs[runId] = listOf(
            "assistant" to """{"text":"Done."}""",
            "result" to """{"runId":"$runId","status":"FINISHED","text":"Done.","durationMs":60000}""",
        )
        server.agents[id] = AgentDto(
            id = id,
            name = name,
            status = "IDLE",
            createdAt = iso(at - 60_000L),
            updatedAt = iso(at),
            latestRunId = runId,
            url = "https://cursor.com/agents/$id",
        )
        server.v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED")
        server.transcripts[id] = listOf(
            V0ConversationMessageDto("$runId-u", "user_message", "Start."),
            V0ConversationMessageDto("$runId-a", "assistant_message", "Done."),
        )
        server.composers[id] = FaultServer.Composer(id, name, activityMs = at)
    }

    private fun addBigTranscript(at: Long) {
        val turns = BigProject.turns(at - BigProject.TURNS * BigProject.TURN_SPACING_MS)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(
                id = turn.runId,
                agentId = bigProjectId,
                status = "FINISHED",
                createdAt = iso(turn.startedAt),
                updatedAt = iso(turn.endedAt),
                durationMs = turn.durationMs,
                result = turn.message,
            )
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[bigProjectId] = server.agents.getValue(bigProjectId).copy(
            createdAt = iso(turns.first().startedAt),
            updatedAt = iso(newest.endedAt),
            latestRunId = newest.runId,
        )
        server.transcripts[bigProjectId] = BigProject.v0Transcript(turns)
        server.records[bigProjectId] = turns.flatMap { it.record }
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private companion object {
        const val LOGICAL_TICK_MS = 10_000L
    }
}
