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
import com.cursorforandroid.data.api.dto.ModelListItemDto
import com.cursorforandroid.data.api.dto.RepositoryDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.dto.V0ConversationResponseDto
import com.cursorforandroid.data.api.dto.V0ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.V0SourceDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory API for repository tests: agents and runs are plain maps the test mutates directly. The list endpoints
 * page like the real ones (newest first, `nextCursor` = the id that starts the next page) and can be made to fail
 * or to wait on a gate so tests can observe what is published before each answer arrives.
 */
open class FakeCursorApi : CursorApi {
    val agents: MutableMap<String, AgentDto> = ConcurrentHashMap()
    val v0: MutableMap<String, V0AgentDto> = ConcurrentHashMap()
    val runs: MutableMap<String, RunDto> = ConcurrentHashMap()
    /** `user_message` / `assistant_message` rows per agent, text only, exactly like the real endpoint. */
    val transcripts: MutableMap<String, List<V0ConversationMessageDto>> = ConcurrentHashMap()
    val cancelled = CopyOnWriteArrayList<String>()
    val createRequests = CopyOnWriteArrayList<CreateAgentRequestDto>()
    val runRequests = CopyOnWriteArrayList<CreateRunRequestDto>()
    var failCancel = false
    var failCreateRun = false
    /** When set, [createAgent] throws it once (after recording the request) instead of creating anything. */
    @Volatile var failNextCreate: Throwable? = null
    /** When set, [createAgent] waits for it (after recording the request) before creating anything, like a slow server. */
    @Volatile var createGate: CompletableDeferred<Unit>? = null
    /** When set, [createAgent] answers without a name, as the server does before it has generated a title. */
    @Volatile var blankCreatedNames = false
    /** When set, [listAgents] suspends until the deferred completes, so a test can interleave work with a refresh. */
    @Volatile var listGate: CompletableDeferred<Unit>? = null
    @Volatile var getRunCalls = 0
    @Volatile var getAgentCalls = 0
    @Volatile var listAgentsCalls = 0
    @Volatile var listAgentsV0Calls = 0
    @Volatile var listRunsCalls = 0
    @Volatile var conversationCalls = 0
    @Volatile var modelsCalls = 0
    @Volatile var repositoriesCalls = 0

    /** Largest page the fake serves regardless of the requested `limit`. */
    @Volatile var pageSize = Int.MAX_VALUE
    @Volatile var failListAgents: Throwable? = null
    @Volatile var failListAgentsV0: Throwable? = null
    @Volatile var failConversation: Throwable? = null
    @Volatile var failListRuns: Throwable? = null
    @Volatile var failModels: Throwable? = null
    @Volatile var failRepositories: Throwable? = null
    /** When set, the v0 list waits for it before answering. */
    @Volatile var v0Gate: CompletableDeferred<Unit>? = null
    /** When set, every v1 page after the first waits for it before answering. */
    @Volatile var laterPagesGate: CompletableDeferred<Unit>? = null
    /** When set, the transcript endpoint waits for it before answering. */
    @Volatile var conversationGate: CompletableDeferred<Unit>? = null
    /** When set, the agent detail endpoint waits for it before answering. */
    @Volatile var getAgentGate: CompletableDeferred<Unit>? = null
    var modelItems: List<ModelListItemDto> = emptyList()
    var repositoryUrls: List<String> = emptyList()
    private val ids = AtomicInteger()

    fun addRunningAgent(id: String, name: String, runId: String, createdAt: String = "2026-04-13T18:30:00.000Z") {
        agents[id] = AgentDto(id = id, name = name, status = "ACTIVE", createdAt = createdAt, updatedAt = createdAt, latestRunId = runId)
        v0[id] = V0AgentDto(id = id, name = name, status = "RUNNING")
        runs[runId] = RunDto(id = runId, agentId = id, status = "RUNNING", createdAt = createdAt, updatedAt = createdAt)
    }

    fun addIdleAgent(
        id: String,
        name: String,
        runId: String,
        createdAt: String = "2026-04-13T18:30:00.000Z",
        repo: String? = "https://github.com/acme/app",
        summary: String? = null,
        result: String? = "Done.",
    ) {
        agents[id] = AgentDto(id = id, name = name, status = "IDLE", createdAt = createdAt, updatedAt = createdAt, latestRunId = runId)
        v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED", source = repo?.let { V0SourceDto(it, "main") }, summary = summary)
        runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = createdAt, updatedAt = createdAt, durationMs = 65_000, result = result)
    }

    /** An idle agent whose runs all finished; [prompts] pair each run (oldest first) with its transcript prompt and reply. */
    fun addFinishedAgent(id: String, name: String, vararg prompts: Triple<String, String, String>, firstRunAt: String = "2026-04-13T18:30:00.000Z") {
        var at = java.time.Instant.parse(firstRunAt)
        val messages = mutableListOf<V0ConversationMessageDto>()
        prompts.forEachIndexed { index, (runId, prompt, reply) ->
            val iso = at.toString()
            runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = iso, updatedAt = iso, durationMs = 60_000L * (index + 1), result = reply)
            messages += V0ConversationMessageDto("$runId-u", "user_message", prompt)
            messages += V0ConversationMessageDto("$runId-a", "assistant_message", reply)
            at = at.plusSeconds(3600)
        }
        val last = prompts.last().first
        agents[id] = AgentDto(id = id, name = name, status = "IDLE", createdAt = firstRunAt, updatedAt = runs.getValue(last).updatedAt, latestRunId = last)
        v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED")
        transcripts[id] = messages
    }

    private fun notFound() = CursorApiException(404, "not_found", "Not found.")

    private fun <T : Any> page(all: List<T>, id: (T) -> String, limit: Int, cursor: String?): Pair<List<T>, String?> {
        val start = cursor?.let { c -> all.indexOfFirst { id(it) == c }.takeIf { it >= 0 } } ?: 0
        val size = minOf(limit, pageSize)
        val items = all.drop(start).take(size)
        val next = all.getOrNull(start + size)?.let(id)
        return items to next
    }

    private fun newestFirst() = agents.values.sortedWith(compareByDescending<AgentDto> { it.createdAt }.thenBy { it.id })

    override suspend fun me() = ApiKeyInfoDto(apiKeyName = "test")
    override suspend fun models(): ListModelsResponseDto {
        modelsCalls++
        failModels?.let { throw it }
        return ListModelsResponseDto(items = modelItems)
    }
    override suspend fun repositories(): ListRepositoriesResponseDto {
        repositoriesCalls++
        failRepositories?.let { throw it }
        return ListRepositoriesResponseDto(items = repositoryUrls.map(::RepositoryDto))
    }
    override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean): ListAgentsResponseDto {
        listAgentsCalls++
        failListAgents?.let { throw it }
        // Snapshot first, then wait: the answer reflects the server as it was when the request went out.
        val (items, next) = page(newestFirst().filter { includeArchived || it.status != "ARCHIVED" }, { it.id }, limit, cursor)
        if (cursor != null) laterPagesGate?.await()
        listGate?.await()
        return ListAgentsResponseDto(
            items = items.map { AgentSummaryDto(it.id, it.name, it.status, it.env, it.url, it.createdAt, it.updatedAt, it.latestRunId) },
            nextCursor = next,
        )
    }
    override suspend fun getAgent(id: String): AgentDto {
        getAgentCalls++
        getAgentGate?.await()
        return agents[id] ?: throw notFound()
    }
    /**
     * Creates the agent and its `CREATING` run. Like the real API, the transcript endpoint knows nothing of the prompt
     * yet: [transcripts] is left alone, so `conversationV0` answers with an empty list until a test fills it in.
     */
    override suspend fun createAgent(body: CreateAgentRequestDto): CreateAgentResponseDto {
        createRequests += body
        failNextCreate?.let { failNextCreate = null; throw it }
        createGate?.await()
        val id = body.agentId ?: "bc-fake-${ids.incrementAndGet()}"
        if (agents.containsKey(id)) throw CursorApiException(409, "agent_id_conflict", "An agent with this id already exists.")
        val runId = "run-fake-${ids.incrementAndGet()}"
        val now = "2026-04-13T18:30:00.000Z"
        val name = if (blankCreatedNames) null else body.name ?: body.prompt.text.take(60)
        val agent = AgentDto(id = id, name = name, status = "ACTIVE", createdAt = now, updatedAt = now, latestRunId = runId, repos = body.repos.orEmpty())
        val run = RunDto(id = runId, agentId = id, status = "CREATING", createdAt = now, updatedAt = now)
        agents[id] = agent
        runs[runId] = run
        return CreateAgentResponseDto(agent, run)
    }
    override suspend fun archive(id: String) = IdResponseDto(id)
    override suspend fun unarchive(id: String) = IdResponseDto(id)
    override suspend fun delete(id: String) = IdResponseDto(id)
    override suspend fun usage(id: String) = AgentUsageResponseDto()
    override suspend fun artifacts(id: String) = ListArtifactsResponseDto()
    open override suspend fun artifactUrl(id: String, path: String) = DownloadArtifactResponseDto(url = "")
    override suspend fun listRuns(id: String, limit: Int, cursor: String?): ListRunsResponseDto {
        listRunsCalls++
        failListRuns?.let { throw it }
        return ListRunsResponseDto(items = runs.values.filter { it.agentId == id })
    }
    override suspend fun getRun(id: String, runId: String): RunDto {
        getRunCalls++
        return runs[runId] ?: throw notFound()
    }
    /**
     * Starts `run-followup-<n>` on the agent and appends the prompt's text (never its images) to the transcript; the
     * test streams the run through the fake streamer like any other.
     */
    override suspend fun createRun(id: String, body: CreateRunRequestDto): CreateRunResponseDto {
        runRequests += body
        if (failCreateRun) throw CursorApiException(503, "unavailable", "Try again later.")
        val agent = agents[id] ?: throw notFound()
        val sequence = ids.incrementAndGet()
        val runId = "run-followup-$sequence"
        val now = java.time.Instant.parse("2026-04-14T09:00:00.000Z").plusSeconds(sequence.toLong()).toString()
        val run = RunDto(id = runId, agentId = id, status = "RUNNING", createdAt = now, updatedAt = now)
        runs[runId] = run
        agents[id] = agent.copy(status = "ACTIVE", latestRunId = runId, updatedAt = now)
        transcripts[id] = transcripts[id].orEmpty() + V0ConversationMessageDto("$runId-u", "user_message", body.prompt.text)
        return CreateRunResponseDto(run)
    }
    override suspend fun cancelRun(id: String, runId: String): IdResponseDto {
        if (failCancel) throw CursorApiException(409, "run_not_cancellable", "Run already finished.")
        cancelled += runId
        return IdResponseDto(runId)
    }
    override suspend fun listAgentsV0(limit: Int, cursor: String?): V0ListAgentsResponseDto {
        listAgentsV0Calls++
        failListAgentsV0?.let { throw it }
        v0Gate?.await()
        val ordered = newestFirst().mapNotNull { v0[it.id] }
        val (items, next) = page(ordered, { it.id }, limit, cursor)
        return V0ListAgentsResponseDto(agents = items, nextCursor = next)
    }
    override suspend fun conversationV0(id: String): V0ConversationResponseDto {
        conversationCalls++
        failConversation?.let { throw it }
        conversationGate?.await()
        return V0ConversationResponseDto(id, transcripts[id] ?: if (id in agents) emptyList() else throw notFound())
    }
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
