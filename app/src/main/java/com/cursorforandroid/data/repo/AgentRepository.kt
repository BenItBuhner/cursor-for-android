package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelRefDto
import com.cursorforandroid.data.api.dto.RepoConfigDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AgentListState(
    val agents: List<Agent> = emptyList(),
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
)

data class LaunchRequest(
    val prompt: String,
    val images: List<PromptImage> = emptyList(),
    val repoUrl: String?,
    val ref: String?,
    val modelId: String?,
    val modelParams: List<ModelParam>,
    val autoCreatePr: Boolean,
    val planMode: Boolean,
    val name: String? = null,
    /** Client-minted id (see [LaunchIdempotency]); null lets the server mint one and disables retry recovery. */
    val agentId: String? = null,
)

class AgentRepository(
    private val session: SessionManager,
    private val prefs: PreferencesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow(AgentListState())
    val state: StateFlow<AgentListState> = _state.asStateFlow()

    init {
        scope.launch {
            // Only actual backend switches reset the list; reacting to the initial value could race a refresh that
            // completed before this collector got scheduled and wipe its result.
            session.backend.drop(1).collect { _state.value = AgentListState() }
        }
    }

    fun agent(id: String): Agent? = _state.value.agents.firstOrNull { it.id == id }

    /**
     * Fetches v1 (identity + lifecycle) and v0 (repo / branch / PR / summary) in parallel and merges them.
     * [silent] refreshes (background polling) leave the pull-to-refresh indicator alone.
     */
    suspend fun refresh(silent: Boolean = false) = refreshMutex.withLock {
        val api = session.current.api
        val knownBefore = _state.value.agents.mapTo(HashSet()) { it.id }
        _state.update { it.copy(isRefreshing = !silent, error = null) }
        try {
            coroutineScope {
                val v1 = async {
                    buildList {
                        var cursor: String? = null
                        repeat(MAX_PAGES) {
                            val page = api.listAgents(limit = 100, cursor = cursor, includeArchived = true)
                            addAll(page.items)
                            cursor = page.nextCursor ?: return@buildList
                        }
                    }
                }
                val v0 = async {
                    runCatching {
                        buildList {
                            var cursor: String? = null
                            repeat(MAX_PAGES) {
                                val page = api.listAgentsV0(limit = 100, cursor = cursor)
                                addAll(page.agents)
                                cursor = page.nextCursor ?: return@buildList
                            }
                        }
                    }.getOrDefault(emptyList())
                }
                val summaries = v1.await()
                val legacy: Map<String, V0AgentDto> = v0.await().associateBy { it.id }
                val local = _state.value.agents
                val previous = local.associateBy { it.id }
                val merged = summaries.map { it.toAgent(legacy[it.id], previous[it.id]) }
                // An agent launched while the list was being fetched is not in the server's answer yet; dropping it
                // would make a chat the user just started vanish until the next refresh.
                val listed = summaries.mapTo(HashSet()) { it.id }
                val launchedMeanwhile = local.filter { it.id !in listed && it.id !in knownBefore }
                _state.update { it.copy(agents = launchedMeanwhile + merged, isRefreshing = false, hasLoaded = true, error = null) }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.update { it.copy(isRefreshing = false, hasLoaded = true, error = t.userMessage()) }
        }
    }

    /** Loads the full agent record plus its latest run and folds them into the cached row. */
    suspend fun loadDetail(id: String): Result<Agent> = runCatching {
        val api = session.current.api
        val dto = api.getAgent(id)
        val run: RunDto? = dto.latestRunId?.let { runId -> runCatching { api.getRun(id, runId) }.getOrNull() }
        val merged = dto.mergeInto(agent(id), run)
        upsert(merged)
        merged
    }

    /**
     * Creates the agent and its first run. When [LaunchRequest.agentId] is set and the server answers
     * `409 agent_id_conflict`, an earlier attempt already went through (its reply was lost to a timeout, a dropped
     * connection or a cancel), so that agent is adopted instead of failing or creating a duplicate.
     */
    suspend fun launch(request: LaunchRequest, modelDisplayName: String?): Result<Agent> = runCatching {
        val api = session.current.api
        val (dto, run) = try {
            // Off the main thread: base64-encoding the images and serializing the body happen before the call is
            // enqueued, on whichever thread makes it.
            withContext(Dispatchers.IO) {
                val body = CreateAgentRequestDto(
                    prompt = PromptEncoding.toPromptDto(request.prompt, request.images),
                    agentId = request.agentId,
                    model = request.modelId?.let { id ->
                        ModelRefDto(id = id, params = request.modelParams.takeIf { it.isNotEmpty() }?.map { ModelParamDto(it.id, it.value) })
                    },
                    name = request.name,
                    env = if (request.repoUrl == null) AgentEnvDto(type = "cloud") else null,
                    repos = request.repoUrl?.let { listOf(RepoConfigDto(url = it, startingRef = request.ref?.ifBlank { null })) },
                    autoCreatePR = request.autoCreatePr.takeIf { it },
                    mode = if (request.planMode) "plan" else null,
                )
                api.createAgent(body)
            }.let { it.agent to it.run }
        } catch (t: Throwable) {
            if (request.agentId == null || t.toCursorError()?.code != AGENT_ID_CONFLICT) throw t
            val existing = api.getAgent(request.agentId)
            existing to existing.latestRunId?.let { runId -> runCatching { api.getRun(existing.id, runId) }.getOrNull() }
        }
        val agent = dto.mergeInto(null, run).copy(
            runStatus = run?.let { RunStatus.parse(it.status) } ?: RunStatus.CREATING,
            modelDisplayName = modelDisplayName,
        )
        upsert(agent)
        prefs.markLaunchedHere(agent.id)
        // Read as of now; the finished run will bump updatedAt past this and surface the unread dot.
        prefs.markRead(agent.id, AppClock.now())
        agent
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun followUp(agentId: String, text: String, images: List<PromptImage> = emptyList(), planMode: Boolean? = null): Result<RunDto> = runCatching {
        val api = session.current.api
        val response = withContext(Dispatchers.IO) {
            api.createRun(agentId, CreateRunRequestDto(PromptEncoding.toPromptDto(text, images), mode = planMode?.let { if (it) "plan" else "agent" }))
        }
        agent(agentId)?.let { upsert(it.copy(runStatus = RunStatus.parse(response.run.status), latestRunId = response.run.id, lifecycle = AgentLifecycle.ACTIVE, updatedAtMillis = AppClock.now())) }
        response.run
    }

    suspend fun cancelRun(agentId: String, runId: String): Result<Unit> = runCatching {
        session.current.api.cancelRun(agentId, runId)
        agent(agentId)?.let { upsert(it.copy(runStatus = RunStatus.CANCELLED, lifecycle = AgentLifecycle.IDLE)) }
    }

    suspend fun archive(agentId: String): Result<Unit> = runCatching {
        session.current.api.archive(agentId)
        agent(agentId)?.let { upsert(it.copy(lifecycle = AgentLifecycle.ARCHIVED)) }
    }

    suspend fun unarchive(agentId: String): Result<Unit> = runCatching {
        session.current.api.unarchive(agentId)
        agent(agentId)?.let { upsert(it.copy(lifecycle = AgentLifecycle.IDLE)) }
    }

    suspend fun delete(agentId: String): Result<Unit> = runCatching {
        session.current.api.delete(agentId)
        _state.update { s -> s.copy(agents = s.agents.filterNot { it.id == agentId }) }
    }

    fun upsert(agent: Agent) {
        _state.update { s ->
            val exists = s.agents.any { it.id == agent.id }
            val list = if (exists) s.agents.map { if (it.id == agent.id) agent else it } else listOf(agent) + s.agents
            s.copy(agents = list)
        }
    }

    fun patch(agentId: String, transform: (Agent) -> Agent) {
        agent(agentId)?.let { upsert(transform(it)) }
    }

    private companion object {
        const val MAX_PAGES = 3
        const val AGENT_ID_CONFLICT = "agent_id_conflict"
    }
}
