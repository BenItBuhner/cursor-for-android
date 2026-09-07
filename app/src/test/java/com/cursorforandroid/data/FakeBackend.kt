package com.cursorforandroid.data

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.AgentUsageResponseDto
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateAgentResponseDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.CreateRunResponseDto
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.api.dto.IdResponseDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.ListArtifactsResponseDto
import com.cursorforandroid.data.api.dto.ListModelsResponseDto
import com.cursorforandroid.data.api.dto.ListRepositoriesResponseDto
import com.cursorforandroid.data.api.dto.ListRunsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.dto.V0ConversationResponseDto
import com.cursorforandroid.data.api.dto.V0ListAgentsResponseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-memory API for repository tests: agents, runs and transcripts are plain maps the test mutates directly. */
class FakeCursorApi : CursorApi {
    val agents: MutableMap<String, AgentDto> = ConcurrentHashMap()
    val v0: MutableMap<String, V0AgentDto> = ConcurrentHashMap()
    val runs: MutableMap<String, RunDto> = ConcurrentHashMap()
    /** `user_message` / `assistant_message` rows per agent, text only, exactly like the real endpoint. */
    val transcripts: MutableMap<String, MutableList<V0ConversationMessageDto>> = ConcurrentHashMap()
    val cancelled = CopyOnWriteArrayList<String>()
    var failCancel = false
    var failCreateRun = false
    @Volatile var getRunCalls = 0
    private val created = AtomicInteger()

    fun addRunningAgent(id: String, name: String, runId: String, createdAt: String = "2026-04-13T18:30:00.000Z") {
        agents[id] = AgentDto(id = id, name = name, status = "ACTIVE", createdAt = createdAt, updatedAt = createdAt, latestRunId = runId)
        v0[id] = V0AgentDto(id = id, name = name, status = "RUNNING")
        runs[runId] = RunDto(id = runId, agentId = id, status = "RUNNING", createdAt = createdAt, updatedAt = createdAt)
    }

    /** An idle agent whose first run finished, with the matching two-line transcript. */
    fun addFinishedAgent(id: String, name: String, runId: String, prompt: String = "Add a README", createdAt: String = "2026-04-13T18:30:00.000Z") {
        agents[id] = AgentDto(id = id, name = name, status = "IDLE", createdAt = createdAt, updatedAt = createdAt, latestRunId = runId)
        v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED")
        runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = createdAt, updatedAt = createdAt, durationMs = 60_000, result = "Done.")
        transcripts[id] = CopyOnWriteArrayList(listOf(V0ConversationMessageDto("$runId-user", "user_message", prompt), V0ConversationMessageDto("$runId-asst", "assistant_message", "Done.")))
    }

    private fun notFound() = CursorApiException(404, "not_found", "Not found.")

    override suspend fun me() = ApiKeyInfoDto(apiKeyName = "test")
    override suspend fun models() = ListModelsResponseDto()
    override suspend fun repositories() = ListRepositoriesResponseDto()
    override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean) = ListAgentsResponseDto(
        items = agents.values.map { AgentSummaryDto(it.id, it.name, it.status, it.env, it.url, it.createdAt, it.updatedAt, it.latestRunId) },
    )
    override suspend fun getAgent(id: String): AgentDto = agents[id] ?: throw notFound()
    override suspend fun createAgent(body: CreateAgentRequestDto): CreateAgentResponseDto = throw UnsupportedOperationException()
    override suspend fun archive(id: String) = IdResponseDto(id)
    override suspend fun unarchive(id: String) = IdResponseDto(id)
    override suspend fun delete(id: String) = IdResponseDto(id)
    override suspend fun usage(id: String) = AgentUsageResponseDto()
    override suspend fun artifacts(id: String) = ListArtifactsResponseDto()
    override suspend fun artifactUrl(id: String, path: String) = DownloadArtifactResponseDto(url = "")
    override suspend fun listRuns(id: String, limit: Int, cursor: String?) = ListRunsResponseDto(items = runs.values.filter { it.agentId == id })
    override suspend fun getRun(id: String, runId: String): RunDto {
        getRunCalls++
        return runs[runId] ?: throw notFound()
    }
    /** Starts a run a minute after the newest one and appends the prompt's text (never its images) to the transcript. */
    override suspend fun createRun(id: String, body: CreateRunRequestDto): CreateRunResponseDto {
        if (failCreateRun) throw CursorApiException(503, "unavailable", "Try again later.")
        val agent = agents[id] ?: throw notFound()
        val n = created.incrementAndGet()
        val latest = runs.values.filter { it.agentId == id }.maxOfOrNull { Instant.parse(it.createdAt).toEpochMilli() } ?: 0L
        val createdAt = Instant.ofEpochMilli(latest + 60_000L * n).toString()
        val runId = "run-new-$n"
        val run = RunDto(id = runId, agentId = id, status = "RUNNING", createdAt = createdAt, updatedAt = createdAt)
        runs[runId] = run
        agents[id] = agent.copy(status = "ACTIVE", latestRunId = runId, updatedAt = createdAt)
        transcripts.getOrPut(id) { CopyOnWriteArrayList() } += V0ConversationMessageDto("msg-new-$n", "user_message", body.prompt.text)
        return CreateRunResponseDto(run)
    }
    override suspend fun cancelRun(id: String, runId: String): IdResponseDto {
        if (failCancel) throw CursorApiException(409, "run_not_cancellable", "Run already finished.")
        cancelled += runId
        return IdResponseDto(runId)
    }
    override suspend fun listAgentsV0(limit: Int, cursor: String?) = V0ListAgentsResponseDto(agents = v0.values.toList())
    override suspend fun conversationV0(id: String) = V0ConversationResponseDto(id, transcripts[id]?.toList() ?: emptyList())
}

/** Scriptable streamer: tests push events per run; [RunStreamEvent.Done] closes the stream like the server does. */
class FakeRunStreamer : RunStreamer {
    private val channels = ConcurrentHashMap<String, MutableSharedFlow<RunStreamEvent>>()
    val connections = CopyOnWriteArrayList<String>()

    private fun channel(runId: String) = channels.getOrPut(runId) { MutableSharedFlow(replay = 256) }

    suspend fun emit(runId: String, event: RunStreamEvent) = channel(runId).emit(event)

    override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> = flow {
        connections += runId
        channel(runId).takeWhile { it != RunStreamEvent.Done }.collect { emit(it) }
    }
}
