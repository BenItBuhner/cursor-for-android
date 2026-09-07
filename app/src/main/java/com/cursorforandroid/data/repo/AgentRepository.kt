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
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val attachments: AttachmentStore,
) {
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow(AgentListState())
    val state: StateFlow<AgentListState> = _state.asStateFlow()

    /** The backend the cached list was fetched from; a different current backend means the list is not ours. */
    @Volatile private var loadedFrom: CursorBackend? = null

    /** Bumped by [reset]; a refresh that started before a reset must not publish the old session's rows after it. */
    @Volatile private var generation = 0

    /** Epoch millis of the last successful [refresh]; zero before the first one and after a [reset]. */
    @Volatile var lastRefreshedAt: Long = 0L
        private set

    /** Forgets the cached list, e.g. on sign-out, so the next session starts from "Loading" rather than stale rows. */
    fun reset() {
        generation++
        loadedFrom = null
        lastRefreshedAt = 0L
        _state.value = AgentListState()
    }

    fun agent(id: String): Agent? = _state.value.agents.firstOrNull { it.id == id }

    val isRefreshing: Boolean get() = refreshMutex.isLocked

    /** Refreshes silently unless one is in flight or the current backend's list was fetched less than [maxAgeMs] ago. */
    suspend fun refreshIfStale(maxAgeMs: Long) {
        if (isRefreshing) return
        if (loadedFrom === session.current && AppClock.now() - lastRefreshedAt < maxAgeMs) return
        refresh(silent = true)
    }

    /**
     * Fetches v1 (identity + lifecycle) and v0 (repo / branch / PR / summary) in parallel and merges them.
     * [silent] refreshes (background polling) leave the pull-to-refresh indicator alone. A list cached from another
     * backend (demo vs. real) is dropped before the fetch, and a result is discarded if the backend changed or
     * [reset] ran while it was in flight.
     */
    suspend fun refresh(silent: Boolean = false) = refreshMutex.withLock {
        val backend = session.current
        val api = backend.api
        if (loadedFrom != null && loadedFrom !== backend) _state.value = AgentListState()
        val startedIn = generation
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
                // Pages are cursor-based over a list that changes underneath; an agent can straddle two of them,
                // and the sidebar keys its rows on the id.
                val summaries = v1.await().distinctBy { it.id }
                val legacy: Map<String, V0AgentDto> = v0.await().associateBy { it.id }
                if (generation != startedIn || session.current !== backend) return@coroutineScope discard()
                val previous = _state.value.agents.associateBy { it.id }
                val merged = summaries.map { it.toAgent(legacy[it.id], previous[it.id]) }
                _state.update { it.copy(agents = merged, isRefreshing = false, hasLoaded = true, error = null) }
                loadedFrom = backend
                lastRefreshedAt = AppClock.now()
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (generation != startedIn || session.current !== backend) return@withLock discard()
            _state.update { it.copy(isRefreshing = false, hasLoaded = true, error = t.userMessage()) }
        }
    }

    /** The session moved on while this fetch ran: leave the (already reset) list alone, but never a stuck spinner. */
    private fun discard() = _state.update { it.copy(isRefreshing = false) }

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
        // The agent exists now; a full disk must not turn that into a launch error.
        runCatching { attachments.save(agent.id, response.run.id, request.images) }
        prefs.markLaunchedHere(agent.id)
        // Read as of now; the finished run will bump updatedAt past this and surface the unread dot.
        prefs.markRead(agent.id, AppClock.now())
        agent
    }

    suspend fun followUp(agentId: String, text: String, images: List<PromptImage> = emptyList(), planMode: Boolean? = null): Result<RunDto> = runCatching {
        val response = session.current.api.createRun(
            agentId,
            CreateRunRequestDto(PromptEncoding.toPromptDto(text, images), mode = planMode?.let { if (it) "plan" else "agent" }),
        )
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
        attachments.delete(agentId)
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
