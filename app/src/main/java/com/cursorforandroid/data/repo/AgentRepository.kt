package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelRefDto
import com.cursorforandroid.data.api.dto.RepoConfigDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.AgentListCache
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

data class AgentListState(
    val agents: List<Agent> = emptyList(),
    val isRefreshing: Boolean = false,
    /** True once there is something to show: the list restored from disk, or the first page of a fetch. */
    val hasLoaded: Boolean = false,
    /** True while the list is the one restored from disk and no fetch has completed yet this session. */
    val isFromCache: Boolean = false,
    val error: String? = null,
)

/**
 * How much of the list a refresh fetches. [Quick] reads the newest page of each endpoint, which is where agents
 * started elsewhere appear (the API lists newest first); [Full] pages through everything so deletions and
 * follow-ups on old agents are picked up too.
 */
enum class RefreshDepth { Quick, Full }

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

/**
 * The agent list. Restored from disk before the first fetch so the app opens on the last known state, then kept
 * fresh with stale-while-revalidate refreshes: every v1 page is published the moment it arrives, the legacy v0
 * enrichment (repo / branch / PR / summary) lands independently, and a failure never wipes what is already shown.
 */
class AgentRepository(
    private val session: SessionManager,
    private val prefs: PreferencesStore,
    private val cache: AgentListCache? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val persistDelayMs: Long = PERSIST_DELAY_MS,
) {
    private val restoreMutex = Mutex()

    private val _state = MutableStateFlow(AgentListState())
    val state: StateFlow<AgentListState> = _state.asStateFlow()

    private class InFlight(val depth: RefreshDepth, val job: Job)

    private var inFlight: InFlight? = null
    /** The backend whose cache has been consulted; a backend switch starts over. */
    @Volatile private var restoredFor: CursorBackend? = null
    /** Bumped by [reset]; a fetch that started before a reset must not publish into the list that replaced it. */
    private val generation = AtomicInteger()
    /** The backend the published list belongs to; a switch only clears a list that still belongs to the old one. */
    @Volatile private var owner: CursorBackend? = null

    init {
        scope.launch {
            // Only actual backend switches clear the list; reacting to the initial value could race a refresh that
            // completed before this collector got scheduled and wipe its result. Fetches for the old backend cannot
            // publish any more (see publish), so a fetch for the new one that already landed is left alone.
            session.backend.drop(1).collect { current -> if (owner !== current) clear() }
        }
        if (cache != null) {
            scope.launch {
                // Every fetched list and every local change after it (a run finishing, an archive, a launch) reaches
                // the disk, so the next start shows them; conflated so a burst of patches costs one write.
                _state.filter { it.hasLoaded && !it.isFromCache }.map { it.agents }.distinctUntilChanged().conflate().collect {
                    delay(persistDelayMs)
                    persist()
                }
            }
        }
    }

    fun agent(id: String): Agent? = _state.value.agents.firstOrNull { it.id == id }

    /** Forgets the list on sign-out, so the next account never sees the previous one's agents, not even from a fetch still in flight. */
    fun reset() {
        generation.incrementAndGet()
        clear()
        restoredFor = null
    }

    private fun clear() {
        owner = null
        _state.value = AgentListState()
    }

    /**
     * Shows the list saved by the previous session, if any. Idempotent per backend and a no-op for the demo, which
     * regenerates its data. [refresh] calls this first, so any entry point is cache-first.
     */
    suspend fun restoreFromCache() {
        val backend = session.current
        if (cache == null || backend.isDemo || restoredFor === backend) return
        restoreMutex.withLock {
            if (restoredFor === backend) return
            restoredFor = backend
            val entry = cache.read() ?: return
            if (session.current !== backend) return
            owner = backend
            _state.update { s ->
                // A fetch that finished in the meantime wins over the disk.
                if (s.agents.isNotEmpty() || s.hasLoaded) s else s.copy(agents = entry.value, hasLoaded = true, isFromCache = true)
            }
        }
    }

    /**
     * Fetches the list and publishes it progressively: each v1 page as it arrives, then the v0 enrichment. Returns
     * once everything has landed. A refresh already in flight that is at least as deep is joined instead of queued.
     * [silent] refreshes (background polling) leave the pull-to-refresh indicator alone and, while something is
     * shown, keep their failures to themselves.
     */
    suspend fun refresh(silent: Boolean = false, depth: RefreshDepth = RefreshDepth.Full) {
        restoreFromCache()
        val (job, joined) = startOrJoin(silent, depth)
        if (joined && !silent) _state.update { it.copy(isRefreshing = true) }
        job.join()
    }

    /**
     * Fetches run in the repository's own scope so a caller that goes away (a ViewModel being cleared) never
     * abandons a half-published refresh. A request that is not covered by the running fetch is chained after it.
     */
    @Synchronized
    private fun startOrJoin(silent: Boolean, depth: RefreshDepth): Pair<Job, Boolean> {
        val running = inFlight?.takeIf { it.job.isActive }
        if (running != null && running.depth >= depth) return running.job to true
        val job = scope.launch {
            running?.job?.join()
            fetch(silent, depth)
        }
        inFlight = InFlight(depth, job)
        return job to false
    }

    private suspend fun fetch(silent: Boolean, depth: RefreshDepth) {
        val backend = session.current
        val api = backend.api
        val startedAt = AppClock.now()
        val startedIn = generation.get()
        // Everything published by this fetch belongs to the backend and session it started against; a demo / real
        // switch or a sign-out half-way through must not leak the old list into the new one.
        fun publish(transform: (AgentListState) -> AgentListState) {
            if (session.current !== backend || generation.get() != startedIn) return
            owner = backend
            _state.update(transform)
        }
        publish { it.copy(isRefreshing = it.isRefreshing || !silent, error = null) }
        try {
            var truncated = false
            val seen = HashSet<String>()
            coroutineScope {
                val legacy = async { fetchLegacy(api, depth) }
                var cursor: String? = null
                var pages = 0
                do {
                    val page = api.listAgents(limit = PAGE_SIZE, cursor = cursor, includeArchived = true)
                    pages++
                    seen += page.items.map { it.id }
                    if (page.items.isNotEmpty()) publish { it.withPage(page.items) }
                    cursor = page.nextCursor?.takeIf { it.isNotBlank() }
                } while (cursor != null && pages < maxPages(depth))
                truncated = cursor != null
                legacy.await().takeIf { it.isNotEmpty() }?.let { v0 -> publish { it.withLegacy(v0) } }
            }
            publish { s ->
                val complete = depth == RefreshDepth.Full && !truncated
                (if (complete) s.withoutUnseen(seen, startedAt) else s).copy(isRefreshing = false, hasLoaded = true, isFromCache = false, error = null)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            publish { s ->
                val keepQuiet = silent && s.agents.isNotEmpty()
                s.copy(isRefreshing = false, hasLoaded = true, error = if (keepQuiet) s.error else t.userMessage())
            }
        }
    }

    /** The v0 list is best effort: it enriches rows but never gates them, and its failure is not the list's failure. */
    private suspend fun fetchLegacy(api: CursorApi, depth: RefreshDepth): Map<String, V0AgentDto> = runCatching {
        buildMap {
            var cursor: String? = null
            var pages = 0
            do {
                val page = api.listAgentsV0(limit = PAGE_SIZE, cursor = cursor)
                pages++
                page.agents.forEach { put(it.id, it) }
                cursor = page.nextCursor?.takeIf { it.isNotBlank() }
            } while (cursor != null && pages < maxPages(depth))
        }
    }.getOrDefault(emptyMap())

    private fun maxPages(depth: RefreshDepth) = if (depth == RefreshDepth.Quick) 1 else MAX_PAGES

    /** Merges one v1 page: known rows are refreshed in place (keeping what richer sources knew), new ones appended. */
    private fun AgentListState.withPage(items: List<AgentSummaryDto>): AgentListState {
        val current = agents.associateBy { it.id }
        val fresh = items.associate { it.id to it.toAgent(current[it.id]) }
        val kept = agents.map { fresh[it.id] ?: it }
        val added = items.mapNotNull { if (it.id in current) null else fresh.getValue(it.id) }
        return copy(agents = kept + added, hasLoaded = true)
    }

    private fun AgentListState.withLegacy(legacy: Map<String, V0AgentDto>): AgentListState =
        copy(agents = agents.map { a -> legacy[a.id]?.let(a::withLegacy) ?: a })

    /**
     * After a complete listing, rows the server no longer returns were deleted elsewhere. Agents created around or
     * after the refresh started are kept: they were launched from here while the pages were in flight.
     */
    private fun AgentListState.withoutUnseen(seen: Set<String>, startedAt: Long): AgentListState =
        copy(agents = agents.filter { it.id in seen || it.createdAtMillis > startedAt - RECENT_WINDOW_MS })

    private suspend fun persist() {
        val backend = session.current
        val s = _state.value
        if (cache == null || backend.isDemo || !s.hasLoaded || s.isFromCache) return
        cache.write(s.agents)
    }

    /**
     * Loads the full agent record plus its latest run and folds them into the cached row. A [knownRun] the caller
     * already holds (from the runs list) is used instead of fetching it again when it is still the latest.
     */
    suspend fun loadDetail(id: String, knownRun: RunDto? = null): Result<Agent> = runCatching {
        val api = session.current.api
        val dto = api.getAgent(id)
        val run: RunDto? = if (knownRun != null && (dto.latestRunId == null || dto.latestRunId == knownRun.id)) {
            knownRun
        } else {
            dto.latestRunId?.let { runId -> runCatching { api.getRun(id, runId) }.getOrNull() }
        }
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
        const val PAGE_SIZE = 100
        /** 500 agents per full refresh; the newest come first, and older ones stay in the cache beyond that. */
        const val MAX_PAGES = 5
        const val PERSIST_DELAY_MS = 1_500L
        const val RECENT_WINDOW_MS = 5 * 60 * 1000L
    }
}
