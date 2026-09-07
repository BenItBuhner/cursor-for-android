package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelRefDto
import com.cursorforandroid.data.api.dto.RepoConfigDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
            session.backend.collect { _state.value = AgentListState() }
        }
    }

    fun agent(id: String): Agent? = _state.value.agents.firstOrNull { it.id == id }

    /**
     * Fetches v1 (identity + lifecycle) and v0 (repo / branch / PR / summary) in parallel and merges them.
     * [silent] refreshes (background polling) leave the pull-to-refresh indicator alone.
     */
    suspend fun refresh(silent: Boolean = false) = refreshMutex.withLock {
        val api = session.current.api
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
                val previous = _state.value.agents.associateBy { it.id }
                val merged = summaries.map { it.toAgent(legacy[it.id], previous[it.id]) }
                _state.update { it.copy(agents = merged, isRefreshing = false, hasLoaded = true, error = null) }
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

    suspend fun launch(request: LaunchRequest, modelDisplayName: String?): Result<Agent> = runCatching {
        val api = session.current.api
        val body = CreateAgentRequestDto(
            prompt = PromptEncoding.toPromptDto(request.prompt, request.images),
            model = request.modelId?.let { id ->
                ModelRefDto(id = id, params = request.modelParams.takeIf { it.isNotEmpty() }?.map { ModelParamDto(it.id, it.value) })
            },
            name = request.name,
            env = if (request.repoUrl == null) AgentEnvDto(type = "cloud") else null,
            repos = request.repoUrl?.let { listOf(RepoConfigDto(url = it, startingRef = request.ref?.ifBlank { null })) },
            autoCreatePR = request.autoCreatePr.takeIf { it },
            mode = if (request.planMode) "plan" else null,
        )
        val response = api.createAgent(body)
        val agent = response.agent.mergeInto(null, response.run).copy(
            runStatus = RunStatus.parse(response.run.status),
            modelDisplayName = modelDisplayName,
        )
        upsert(agent)
        prefs.markLaunchedHere(agent.id)
        // Read as of now; the finished run will bump updatedAt past this and surface the unread dot.
        prefs.markRead(agent.id, System.currentTimeMillis())
        agent
    }

    suspend fun followUp(agentId: String, text: String, images: List<PromptImage> = emptyList(), planMode: Boolean? = null): Result<RunDto> = runCatching {
        val response = session.current.api.createRun(
            agentId,
            CreateRunRequestDto(PromptEncoding.toPromptDto(text, images), mode = planMode?.let { if (it) "plan" else "agent" }),
        )
        agent(agentId)?.let { upsert(it.copy(runStatus = RunStatus.parse(response.run.status), latestRunId = response.run.id, lifecycle = AgentLifecycle.ACTIVE, updatedAtMillis = System.currentTimeMillis())) }
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
    }
}
