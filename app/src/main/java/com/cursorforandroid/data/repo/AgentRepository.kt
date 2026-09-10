package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ComposerLifecycleApi
import com.cursorforandroid.data.api.ComposerSnapshot
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
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
 * started elsewhere appear (the API lists newest first); [Full] reads the newest few hundred, which is what a poll
 * every few minutes can afford; [Deep] pages to the end of both endpoints, which is the only pass that can tell
 * that an old agent was deleted elsewhere. A [Full] pass is promoted to a [Deep] one when the last complete
 * listing was over an hour ago.
 */
enum class RefreshDepth { Quick, Full, Deep }

/** What [AgentRepository.refreshIfStale] did, so a poller can tell "nothing to do" from "could not be done". */
enum class RefreshOutcome {
    /** The list was fetched recently enough, or a fetch is already in flight. */
    Skipped,
    Refreshed,
    /** The server could not be reached or would not answer; what was shown stands. */
    Failed,
}

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
    /** Sent inline as `mcpServers[]`; only the enabled ones go out. */
    val mcpServers: List<McpServer> = emptyList(),
    /** Where the agent runs: Cursor cloud (the default), a team pool, or a connected machine. */
    val env: DeviceTarget = DeviceTarget.Cloud,
) {
    /**
     * What the chat is called until the server has named it: the prompt's first line of text, its slash commands
     * stripped, cut at a word to about the length of the titles the server generates.
     */
    val provisionalName: String
        get() {
            val line = SlashCommands.strip(prompt).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "New chat"
            if (line.length <= PROVISIONAL_NAME_LENGTH) return line
            val cut = line.take(PROVISIONAL_NAME_LENGTH)
            val atWord = cut.lastIndexOf(' ').takeIf { it >= PROVISIONAL_NAME_LENGTH / 2 } ?: cut.length
            return cut.substring(0, atWord).trimEnd() + "…"
        }

    private companion object {
        const val PROVISIONAL_NAME_LENGTH = 60
    }
}

/**
 * `env` on Create An Agent. Default cloud with a repository omits the field (the repo is the target); no-repo
 * cloud still sends `{ type: cloud }` so the VM is empty rather than local. Pool and machine always go out.
 */
fun DeviceTarget.toEnvDto(hasRepo: Boolean): AgentEnvDto? = when (type) {
    EnvType.POOL -> AgentEnvDto(type = "pool", name = apiName)
    EnvType.MACHINE -> AgentEnvDto(type = "machine", name = apiName)
    EnvType.CLOUD, EnvType.UNKNOWN -> when {
        apiName != null -> AgentEnvDto(type = "cloud", name = apiName)
        hasRepo -> null
        else -> AgentEnvDto(type = "cloud")
    }
}

/** What a launch created: the list row, and the first run as the server reported it (null when adopting an agent whose run could not be read). */
data class Launched(val agent: Agent, val run: RunDto?)

/**
 * The agent list. Restored from disk before the first fetch so the app opens on the last known state, then kept
 * fresh with stale-while-revalidate refreshes: every v1 page is published the moment it arrives, the legacy v0
 * enrichment (repo / branch / PR / summary / execution status) lands independently, the runs whose state is still in
 * question are then read from their records (see [verifyRunStatuses]), and a failure never wipes what is already
 * shown. The server's `updatedAt` is the row's activity time; a stamp made here survives a refresh only while the
 * server is about to confirm it (see `reconcileUpdatedAt`).
 */
class AgentRepository(
    private val session: SessionManager,
    private val prefs: PreferencesStore,
    private val attachments: AttachmentStore,
    private val cache: AgentListCache? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val persistDelayMs: Long = PERSIST_DELAY_MS,
    /**
     * The account-level archive / rename the first-party apps use. Optional so unit tests that only exercise the
     * public list can omit it; a real session always has one.
     */
    private val account: ComposerLifecycleApi? = null,
    /** Where the demo's chats were started, by id: the demo has no account service to say (see [applySources]). */
    private val demoSources: Map<String, AgentSource> = emptyMap(),
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
    /**
     * Serializes every generation check with the publication it guards, and with [reset] / [clear]. Without it the
     * two are a check and a separate write: a reset could land in between and the old account's page would be
     * applied to the list that replaced it.
     */
    private val publishLock = Any()
    /** The backend the published list belongs to; a switch only clears a list that still belongs to the old one. */
    @Volatile private var owner: CursorBackend? = null
    /**
     * Chats shown in the list before the server has confirmed them (see [beginLaunch]). They exist only in memory:
     * a row that may still fail to be created is never written to disk.
     */
    private val pendingLaunches: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Epoch millis of the last completed fetch for the current backend; zero before the first one and after a [reset]. */
    @Volatile var lastRefreshedAt: Long = 0L
        private set

    /** Epoch millis of the last pass that reached the end of the list — the only one that can reconcile deletions. */
    @Volatile private var lastCompleteListingAt: Long = 0L

    private val _refreshCompleted = MutableStateFlow(0L)
    /** Completed fetches for the current backend, counted: the cue for work that follows each one (the account's pins, for one). */
    val refreshCompleted: StateFlow<Long> = _refreshCompleted.asStateFlow()

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
        synchronized(publishLock) {
            generation.incrementAndGet()
            clear()
            restoredFor = null
            pendingLaunches.clear()
        }
    }

    private fun clear() = synchronized(publishLock) {
        owner = null
        lastRefreshedAt = 0L
        lastCompleteListingAt = 0L
        _refreshCompleted.value = 0L
        _state.value = AgentListState()
    }

    /**
     * The account the list belongs to right now. Captured when an operation starts and handed to every publication
     * it makes, so a sign-out in between is what decides whether the result lands — not the moment the write happens
     * to be scheduled.
     */
    fun token(): Int = generation.get()

    /**
     * Applies [transform] to the list, but only while it still belongs to [startedIn] (and, when given, to
     * [backend]). The check and the write are one critical section shared with [reset], so nothing can be published
     * into a list that has since been replaced.
     */
    private fun publish(backend: CursorBackend?, startedIn: Int, transform: (AgentListState) -> AgentListState): Boolean =
        synchronized(publishLock) {
            if (generation.get() != startedIn) return false
            if (backend != null) {
                if (session.current !== backend) return false
                owner = backend
            }
            _state.value = transform(_state.value)
            true
        }

    /**
     * Drops a list that belongs to another backend before anything is published for the current one. The switch
     * collector above does the same, but asynchronously; a refresh or restore that follows a switch immediately
     * must not merge the new backend's rows into the old backend's list.
     */
    private fun dropForeignList(backend: CursorBackend) {
        if (owner != null && owner !== backend) clear()
    }

    /**
     * For returning to the foreground and for polling while the list is on screen: refreshes silently unless one is
     * already in flight or the current backend's list was fetched less than [maxAgeMs] ago. A list from another
     * backend, or none, is always stale.
     */
    suspend fun refreshIfStale(maxAgeMs: Long, depth: RefreshDepth = RefreshDepth.Full): RefreshOutcome {
        if (synchronized(this) { inFlight?.job?.isActive == true }) return RefreshOutcome.Skipped
        if (owner === session.current && lastRefreshedAt != 0L && AppClock.now() - lastRefreshedAt < maxAgeMs) return RefreshOutcome.Skipped
        val before = _refreshCompleted.value
        refresh(silent = true, depth = depth)
        // A silent refresh keeps its failure to itself while there is something to show, so the completed count —
        // not the error — is what says whether the fetch got through.
        return if (_refreshCompleted.value != before) RefreshOutcome.Refreshed else RefreshOutcome.Failed
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
            val startedIn = token()
            restoredFor = backend
            dropForeignList(backend)
            val entry = cache.read() ?: return
            val landed = publish(backend, startedIn) { s ->
                // A fetch that finished in the meantime wins over the disk.
                if (s.agents.isNotEmpty() || s.hasLoaded) s else s.copy(agents = entry.value, hasLoaded = true, isFromCache = true)
            }
            // A sign-out landed while the file was being read: this disk copy is not the current account's, and the
            // one that is has still to be restored.
            if (!landed) restoredFor = null
        }
    }

    /**
     * Fetches the list and publishes it progressively: each v1 page as it arrives, then the v0 enrichment. Returns
     * once everything has landed. A refresh already in flight that is at least as deep is joined instead of queued.
     * [silent] refreshes (background polling) leave the pull-to-refresh indicator alone and, while something is
     * shown, keep their failures to themselves.
     */
    suspend fun refresh(silent: Boolean = false, depth: RefreshDepth = RefreshDepth.Full) {
        val startedIn = token()
        restoreFromCache()
        val (job, joined) = startOrJoin(silent, deepIfDue(depth))
        if (joined && !silent) publish(null, startedIn) { it.copy(isRefreshing = true) }
        job.join()
    }

    /**
     * Promotes a [RefreshDepth.Full] pass to a complete listing when the last one was over
     * [COMPLETE_LISTING_EVERY_MS] ago. The windowed pass is what a poll every few minutes can afford; reconciling
     * deletions and old activity beyond the window needs the whole list, which is worth an hourly pass.
     */
    private fun deepIfDue(depth: RefreshDepth): RefreshDepth =
        if (depth == RefreshDepth.Full && AppClock.now() - lastCompleteListingAt >= COMPLETE_LISTING_EVERY_MS) RefreshDepth.Deep else depth

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
        dropForeignList(backend)
        val startedIn = generation.get()
        // The rows as they were when the fetch started. Rows that appear while the pages are in flight and are not in
        // the server's answer were launched here meanwhile; the complete-listing cleanup below must not make a chat
        // the user just started vanish. And a row whose latest run is not the one it had is a new turn to verify.
        val before = _state.value.agents.associateBy { it.id }
        val knownBefore = before.keys
        // Everything published by this fetch belongs to the backend and session it started against; a demo / real
        // switch or a sign-out half-way through must not leak the old list into the new one.
        fun publish(transform: (AgentListState) -> AgentListState): Boolean = publish(backend, startedIn, transform)
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
            verifyRunStatuses(api, before, startedAt) { transform -> publish(transform) }
            // Pinned rows outlive the listing window, as they do in the desktop sidebar; the pin sync fetches the
            // ones the window never returned, and this keeps the next complete listing from dropping them again.
            val pinned = prefs.localAgentState.first().pinnedIds
            // Only a pass that reached the end of the list knows that a row the server did not return is gone
            // rather than merely beyond where the pass stopped.
            val complete = depth != RefreshDepth.Quick && !truncated
            val landed = publish { s ->
                (if (complete) s.withoutUnseen(seen, knownBefore, startedAt, pinned) else s)
                    .let { if (backend.isDemo) it.withSources(demoSources) else it }
                    .copy(isRefreshing = false, hasLoaded = true, isFromCache = false, error = null)
            }
            // Under the same lock as the publication: a completed fetch is the cue the account's pins are synced
            // on, and the previous account's must not give it.
            if (landed) synchronized(publishLock) {
                if (generation.get() == startedIn) {
                    lastRefreshedAt = AppClock.now()
                    if (complete) lastCompleteListingAt = lastRefreshedAt
                    _refreshCompleted.update { it + 1 }
                }
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

    private fun maxPages(depth: RefreshDepth) = when (depth) {
        RefreshDepth.Quick -> 1
        RefreshDepth.Full -> MAX_PAGES
        RefreshDepth.Deep -> MAX_DEEP_PAGES
    }

    /**
     * Settles the execution state the lists could not. The v1 list has no run state at all (its `status` is a
     * lifecycle that reads `ACTIVE` for finished agents too), the legacy list is one status per agent and best effort,
     * so once both have landed the runs still in question are read from their records — the one source that is
     * neither stale nor ambiguous about them — and folded into their rows. In question are, at most [MAX_VERIFIED_RUNS]
     * per refresh and in this order of urgency, newest activity first:
     *  - rows whose latest run is not the one they had when the fetch started, with recent activity: a turn started
     *    elsewhere, whose spinner should not wait for the legacy list to catch up with it;
     *  - rows that say a run is active: the list may lag its finish, or the row may be a cache from days ago. This is
     *    the polling the API documents for run state, and it is what stops a stale spinner without opening a stream
     *    (streaming a finished run's log as if it were live is what stamped old chats "updated just now");
     *  - rows that say a run is over but were active within the last few minutes: a turn that just ended, or one that
     *    is going while the legacy list still describes the previous one — the record tells which;
     *  - rows with no run-level status at all, with recent activity: the legacy list failed or does not know them.
     * A quiet row with no status is left at rest: `updatedAt` going quiet is what a finished run looks like, and
     * reading hundreds of old records would gain nothing. A record that cannot be read leaves its row as it is.
     */
    private suspend fun verifyRunStatuses(api: CursorApi, before: Map<String, Agent>, startedAt: Long, publish: ((AgentListState) -> AgentListState) -> Boolean) {
        // A fetch that can no longer publish (backend switch, sign-out) has no rows of its own to settle.
        if (!publish { it }) return
        fun recent(agent: Agent) = agent.updatedAtMillis >= startedAt - VERIFY_RECENT_WINDOW_MS
        fun justActive(agent: Agent) = agent.updatedAtMillis >= startedAt - VERIFY_JUST_ACTIVE_WINDOW_MS
        fun newTurn(agent: Agent) = before[agent.id]?.latestRunId.let { it != null && it != agent.latestRunId }
        val candidates = _state.value.agents.asSequence()
            .filter { it.latestRunId != null && !it.isArchived && it.id !in pendingLaunches }
            .filter { (newTurn(it) && recent(it)) || it.isRunning || justActive(it) || (it.runStatusUnknown && recent(it)) }
            .sortedWith(compareByDescending<Agent> { newTurn(it) }.thenByDescending { it.isRunning }.thenByDescending { it.updatedAtMillis })
            .take(MAX_VERIFIED_RUNS)
            .toList()
        if (candidates.isEmpty()) return
        coroutineScope {
            candidates.map { agent ->
                async {
                    val run = runCatching { api.getRun(agent.id, agent.latestRunId!!) }.getOrNull() ?: return@async
                    publish { s -> s.copy(agents = s.agents.map { if (it.id == agent.id) it.withLatestRun(run) else it }) }
                }
            }.awaitAll()
        }
    }

    /**
     * Merges one v1 page: known rows are refreshed in place (keeping what richer sources knew), new ones appended.
     * Pages are cursor-based over a list that changes underneath, so an agent can appear twice; the sidebar keys its
     * rows on the id, and a row is never added twice.
     */
    private fun AgentListState.withPage(items: List<AgentSummaryDto>): AgentListState {
        val current = agents.associateBy { it.id }
        val fresh = items.associate { it.id to it.toAgent(current[it.id]) }
        val kept = agents.map { fresh[it.id] ?: it }
        val added = items.distinctBy { it.id }.mapNotNull { if (it.id in current) null else fresh.getValue(it.id) }
        return copy(agents = kept + added, hasLoaded = true)
    }

    private fun AgentListState.withLegacy(legacy: Map<String, V0AgentDto>): AgentListState =
        copy(agents = agents.map { a -> legacy[a.id]?.let(a::withLegacy) ?: a })

    /** Rows whose source [sources] names take it; the others keep what they had (a row is never made to forget its source). */
    private fun AgentListState.withSources(sources: Map<String, AgentSource>): AgentListState {
        if (sources.isEmpty()) return this
        var changed = false
        val next = agents.map { a ->
            val source = sources[a.id]
            if (source == null || source == a.source) a else a.copy(source = source).also { changed = true }
        }
        return if (changed) copy(agents = next) else this
    }

    /**
     * Folds in where each agent was started from, as the account's list reports it (see [AgentSource]). The public
     * list this repository draws its rows from never says, so this is the one way a row learns its source; once
     * learned it is kept across refreshes and on disk with the row, like the model. The account list is read after
     * every completed fetch (by the pin sync), so a new row's source lands a moment after the row itself.
     */
    fun applySources(sources: Map<String, AgentSource>, startedIn: Int = token()) {
        if (sources.isEmpty()) return
        publish(null, startedIn) { it.withSources(sources) }
    }

    /**
     * After a complete listing, rows the server no longer returns were deleted elsewhere. Kept anyway: rows that were
     * not known when the fetch started (launched here while the pages were in flight), agents created shortly before
     * it started (the listing can lag a creation by a moment), and [pinned] agents, which the listing window may
     * simply have left behind.
     */
    private fun AgentListState.withoutUnseen(seen: Set<String>, knownBefore: Set<String>, startedAt: Long, pinned: Set<String>): AgentListState =
        copy(agents = agents.filter { it.id in seen || it.id !in knownBefore || it.createdAtMillis > startedAt - RECENT_WINDOW_MS || it.id in pinned })

    private suspend fun persist() {
        val backend = session.current
        if (cache == null || backend.isDemo) return
        // The cache generation belongs to the list being written, so a wipe between here and the file refuses it.
        val (token, agents) = synchronized(publishLock) {
            val s = _state.value
            if (!s.hasLoaded || s.isFromCache) return
            cache.token() to s.agents.filterNot { it.id in pendingLaunches }
        }
        cache.write(agents, token)
    }

    /**
     * Loads the full agent record plus its latest run and folds them into the cached row. A [knownRun] the caller
     * already holds (from the runs list) is used instead of fetching it again when it is still the latest.
     */
    suspend fun loadDetail(id: String, knownRun: RunDto? = null): Result<Agent> = runCatching {
        val startedIn = token()
        val api = session.current.api
        val dto = api.getAgent(id)
        val run: RunDto? = if (knownRun != null && (dto.latestRunId == null || dto.latestRunId == knownRun.id)) {
            knownRun
        } else {
            dto.latestRunId?.let { runId -> runCatching { api.getRun(id, runId) }.getOrNull() }
        }
        val merged = dto.mergeInto(agent(id), run)
        upsert(merged, startedIn)
        merged
    }

    /**
     * Puts the chat about to be launched at the top of the list before the server has answered, so the screens that
     * open on it (its header, the sidebar row) have something to show at once. The row is named after the prompt and
     * carries what the request already knows — repository, ref, model — as a `CREATING` run without an id yet;
     * [launch] replaces it with the server's record, or removes it when the request fails. Requires a client-minted
     * [LaunchRequest.agentId]; without one there is nothing to key the row on and null is returned.
     */
    fun beginLaunch(request: LaunchRequest, modelDisplayName: String?): Agent? {
        val id = request.agentId ?: return null
        val now = AppClock.now()
        val row = agent(id) ?: Agent(
            id = id,
            name = request.provisionalName,
            lifecycle = AgentLifecycle.ACTIVE,
            runStatus = RunStatus.CREATING,
            envType = request.env.type.takeIf { it != EnvType.UNKNOWN } ?: EnvType.CLOUD,
            envName = request.env.name,
            url = "https://cursor.com/agents/$id",
            createdAtMillis = now,
            updatedAtMillis = now,
            latestRunId = null,
            repoUrl = request.repoUrl,
            startingRef = request.ref,
            autoCreatePr = request.autoCreatePr,
            modelDisplayName = modelDisplayName,
            modelId = request.modelId,
            modelParams = if (request.modelId != null) request.modelParams else emptyList(),
            // What the account will record for a chat started with an API key, this app's included.
            source = AgentSource.API,
        )
        // A retry of a launch whose reply was lost finds the row from the first attempt: it stays as it is.
        if (agent(id) == null) {
            pendingLaunches += id
            upsert(row)
        }
        return row
    }

    /** Takes a [beginLaunch] row back out of the list; a no-op once the server has confirmed the chat. */
    fun discardLaunch(agentId: String, startedIn: Int = token()) {
        if (!pendingLaunches.remove(agentId)) return
        publish(null, startedIn) { s -> s.copy(agents = s.agents.filterNot { it.id == agentId }) }
    }

    /**
     * Creates the agent and its first run. When [LaunchRequest.agentId] is set and the server answers
     * `409 agent_id_conflict`, an earlier attempt already went through (its reply was lost to a timeout, a dropped
     * connection or a cancel), so that agent is adopted instead of failing or creating a duplicate. A row
     * [beginLaunch] put in the list is replaced by the server's record, or removed when the request fails.
     * [saveImages] is off for a caller that staged the prompt's images itself and files them under the run.
     */
    suspend fun launch(request: LaunchRequest, modelDisplayName: String?, saveImages: Boolean = true): Result<Launched> {
        val startedIn = token()
        val result = runCatching {
            val api = session.current.api
            val (dto, run) = try {
                // Off the main thread: base64-encoding the images and serializing the body happen before the call is
                // enqueued, on whichever thread makes it.
                withContext(Dispatchers.IO) {
                    val body = CreateAgentRequestDto(
                        prompt = PromptEncoding.toPromptDto(request.prompt, request.images),
                        agentId = request.agentId,
                        model = modelRef(request.modelId, request.modelParams),
                        name = request.name,
                        env = request.env.toEnvDto(hasRepo = request.repoUrl != null),
                        repos = request.repoUrl?.let { listOf(RepoConfigDto(url = it, startingRef = request.ref?.ifBlank { null })) },
                        autoCreatePR = request.autoCreatePr.takeIf { it },
                        mcpServers = request.mcpServers.toInlineServers(),
                        mode = if (request.planMode) "plan" else null,
                    )
                    api.createAgent(body)
                }.let { it.agent to it.run }
            } catch (t: Throwable) {
                if (request.agentId == null || t.toCursorError()?.code != AGENT_ID_CONFLICT) throw t
                val existing = api.getAgent(request.agentId)
                existing to existing.latestRunId?.let { runId -> runCatching { api.getRun(existing.id, runId) }.getOrNull() }
            }
            // The provisional row, when there is one, fills in what the server's record leaves blank — its name
            // above all, which the server may not have generated yet.
            val agent = dto.mergeInto(agent(dto.id), run).copy(
                runStatus = run?.let { RunStatus.parse(it.status) } ?: RunStatus.CREATING,
                modelDisplayName = modelDisplayName,
                modelId = request.modelId,
                modelParams = if (request.modelId != null) request.modelParams else emptyList(),
                source = AgentSource.API,
            )
            pendingLaunches -= agent.id
            upsert(agent, startedIn)
            // The agent exists now; a full disk must not turn that into a launch error. A retry that found the agent
            // already created files the images under the same run, so this stays idempotent.
            if (saveImages) run?.let { runCatching { attachments.save(agent.id, it.id, request.images) } }
            prefs.markLaunchedHere(agent.id)
            // Read as of now; the finished run will bump updatedAt past this and surface the unread dot.
            prefs.markRead(agent.id, AppClock.now())
            Launched(agent, run)
        }
        if (result.isFailure) {
            request.agentId?.let { discardLaunch(it, startedIn) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
        return result
    }

    /**
     * Enabled [mcpServers] ride along inline and replace the agent's create-time inline servers for this run; with
     * none enabled the field is omitted and the agent keeps whatever it was created with. A [modelId] switches the
     * agent to that model — for this run and, as the server keeps the override, every run after it — so the row
     * records it (under [modelDisplayName]) once the server has accepted the run; null keeps the current model.
     * [planMode] asks for plan or agent mode explicitly; null keeps the conversation's mode.
     */
    suspend fun followUp(
        agentId: String,
        text: String,
        images: List<PromptImage> = emptyList(),
        planMode: Boolean? = null,
        mcpServers: List<McpServer> = emptyList(),
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
    ): Result<RunDto> = runCatching {
        val startedIn = token()
        val api = session.current.api
        val response = withContext(Dispatchers.IO) {
            api.createRun(
                agentId,
                CreateRunRequestDto(
                    prompt = PromptEncoding.toPromptDto(text, images),
                    mcpServers = mcpServers.toInlineServers(),
                    model = modelRef(modelId, modelParams),
                    mode = planMode?.let { if (it) "plan" else "agent" },
                ),
            )
        }
        patch(agentId, startedIn) { current ->
            // The old label would describe the old model, so without a new one the id stands in.
            val switched = if (modelId != null) {
                current.copy(modelId = modelId, modelParams = modelParams, modelDisplayName = modelDisplayName ?: modelId)
            } else {
                current
            }
            switched.copy(runStatus = RunStatus.parse(response.run.status), latestRunId = response.run.id, lifecycle = AgentLifecycle.ACTIVE, updatedAtMillis = AppClock.now())
        }
        response.run
    }

    /** The request's `model` field: the id with the variant's parameters, or null so the field is omitted. */
    private fun modelRef(modelId: String?, params: List<ModelParam>): ModelRefDto? = modelId?.let { id ->
        ModelRefDto(id = id, params = params.takeIf { it.isNotEmpty() }?.map { ModelParamDto(it.id, it.value) })
    }

    suspend fun cancelRun(agentId: String, runId: String): Result<Unit> = runCatching {
        val startedIn = token()
        session.current.api.cancelRun(agentId, runId)
        patch(agentId, startedIn) { it.copy(runStatus = RunStatus.CANCELLED, lifecycle = AgentLifecycle.IDLE) }
    }

    suspend fun archive(agentId: String): Result<Unit> = setArchived(agentId, archived = true)

    suspend fun unarchive(agentId: String): Result<Unit> = setArchived(agentId, archived = false)

    /**
     * Writes the archive flag the official apps share ([ComposerLifecycleApi]) and the public v1 lifecycle, then
     * updates the row. Either write landing is enough for this device; both failing is the only failure. Demo
     * mode only has the in-memory public API.
     */
    private suspend fun setArchived(agentId: String, archived: Boolean): Result<Unit> = runCatching {
        val startedIn = token()
        var wrote = false
        var lastError: Throwable? = null
        if (!session.isDemo && account != null) {
            try {
                if (archived) account.archive(agentId) else account.unarchive(agentId)
                wrote = true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
            }
        }
        try {
            if (archived) session.current.api.archive(agentId) else session.current.api.unarchive(agentId)
            wrote = true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            lastError = t
        }
        if (!wrote) throw lastError ?: IllegalStateException(if (archived) "Couldn't archive this chat." else "Couldn't unarchive this chat.")
        patch(agentId, startedIn) { it.copy(lifecycle = if (archived) AgentLifecycle.ARCHIVED else AgentLifecycle.IDLE) }
    }

    /**
     * Renames the chat the way the official apps do (`RenameBackgroundComposer`). The public Cloud Agents API has
     * no rename; demo mode only updates the in-memory row.
     */
    suspend fun rename(agentId: String, name: String): Result<Unit> = runCatching {
        val startedIn = token()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Give the chat a name.")
        if (trimmed.length > MAX_NAME_LENGTH) throw IllegalArgumentException("Name must be $MAX_NAME_LENGTH characters or less.")
        if (agent(agentId)?.name == trimmed) return@runCatching
        if (!session.isDemo) {
            val api = account ?: throw IllegalStateException("Can't rename this chat from here.")
            api.rename(agentId, trimmed)
        }
        patch(agentId, startedIn) { it.copy(name = trimmed) }
    }

    /**
     * Folds the account list's name and archive flag onto the rows already shown. Official apps rename and archive
     * here, and a v1 list that has not caught up (or never will — the public archive is a different write) would
     * otherwise keep the old title or leave a chat sitting in the open list.
     */
    fun applyAccountSnapshots(composers: List<ComposerSnapshot>, startedIn: Int = token()) {
        if (composers.isEmpty()) return
        val byId = composers.associateBy { it.id }
        publish(null, startedIn) { s ->
            s.copy(
                agents = s.agents.map { agent ->
                    val snap = byId[agent.id] ?: return@map agent
                    val name = snap.name?.trim()?.takeIf { it.isNotEmpty() } ?: agent.name
                    val lifecycle = when (snap.archived) {
                        true -> AgentLifecycle.ARCHIVED
                        false -> if (agent.lifecycle == AgentLifecycle.ARCHIVED) AgentLifecycle.IDLE else agent.lifecycle
                        null -> agent.lifecycle
                    }
                    if (name == agent.name && lifecycle == agent.lifecycle) agent else agent.copy(name = name, lifecycle = lifecycle)
                },
            )
        }
    }

    suspend fun delete(agentId: String): Result<Unit> = runCatching {
        val startedIn = token()
        session.current.api.delete(agentId)
        publish(null, startedIn) { s -> s.copy(agents = s.agents.filterNot { it.id == agentId }) }
        attachments.delete(agentId)
    }

    /** [startedIn] is the account the caller's operation started under; a reset since then drops the row. */
    fun upsert(agent: Agent, startedIn: Int = token()) {
        publish(null, startedIn) { s ->
            val exists = s.agents.any { it.id == agent.id }
            val list = if (exists) s.agents.map { if (it.id == agent.id) agent else it } else listOf(agent) + s.agents
            s.copy(agents = list)
        }
    }

    /** Read and write in one critical section, so the row [transform] saw is the row it replaces. */
    fun patch(agentId: String, startedIn: Int = token(), transform: (Agent) -> Agent) {
        synchronized(publishLock) {
            val current = _state.value.agents.firstOrNull { it.id == agentId } ?: return
            upsert(transform(current), startedIn)
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        /** 500 agents per windowed refresh; the newest come first, and older ones wait for a complete listing. */
        const val MAX_PAGES = 5
        /** The safety bound on a complete listing: 3000 agents, past which the app stops asking for more. */
        const val MAX_DEEP_PAGES = 30
        const val COMPLETE_LISTING_EVERY_MS = 60 * 60 * 1000L
        const val PERSIST_DELAY_MS = 1_500L
        const val RECENT_WINDOW_MS = 5 * 60 * 1000L
        /** Run records read per refresh to settle rows the lists left in question (see [verifyRunStatuses]). */
        const val MAX_VERIFIED_RUNS = 12
        /** A row without a run-level status is only worth a record read while its activity is this recent. */
        const val VERIFY_RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
        /** A row that reads finished but was active this recently may still be going; its record settles it. */
        const val VERIFY_JUST_ACTIVE_WINDOW_MS = 5 * 60 * 1000L
        const val AGENT_ID_CONFLICT = "agent_id_conflict"
        /** Same cap as `POST /v1/agents` `name` and the official rename field. */
        const val MAX_NAME_LENGTH = 100
    }
}
