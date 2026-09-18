package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AgentStartApi
import com.cursorforandroid.data.api.ComposerLifecycleApi
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.StartRequest
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelRefDto
import com.cursorforandroid.data.api.dto.RepoConfigDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.isLostReply
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.CachedLineage
import com.cursorforandroid.data.local.CachedPlacement
import com.cursorforandroid.data.local.CachedRecord
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.KnownRoot
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.RunningScan
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
import java.io.IOException
import java.time.Instant
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
    /** The server has agents older than the ones loaded: the next page is a scroll to the end of the list away (see [AgentRepository.loadMore]). */
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
)

/**
 * How much of the list a refresh fetches. [Quick] reads the newest page of each endpoint, which is where agents
 * started elsewhere appear (the API lists newest first); [Full] re-reads the pages the list has been paged to so
 * far — the window on screen — and no deeper; [Deep] is the same window read again by hand, to the end of the
 * list when the window already reaches it. Nothing pages past the window on its own: the pages behind it are
 * fetched as the reader scrolls to the end of the list ([AgentRepository.loadMore]).
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
    /**
     * Files of any type (Extended mode). The documented create request cannot carry them, so a launch with any goes
     * through the account's start instead (see [AgentRepository.launch]); the images ride along inline as they do there.
     */
    val files: List<PromptFile> = emptyList(),
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
    /** `autoCreatePR` as it goes out: a pull request wants a repository, so the switch only counts with one. */
    val opensPullRequest: Boolean get() = autoCreatePr && repoUrl != null

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
 * `env` on Create An Agent. The ordinary Cursor-hosted cloud is the API's default and goes unsaid, with or without a
 * repository: the reference wants `env` left out for a no-repo agent, and the `{ type: cloud }` once sent in its
 * place is what the server answered with a `400`. A named cloud environment, a team pool and a machine always go out.
 */
fun DeviceTarget.toEnvDto(): AgentEnvDto? = when (type) {
    EnvType.POOL -> AgentEnvDto(type = "pool", name = apiName)
    EnvType.MACHINE -> AgentEnvDto(type = "machine", name = apiName)
    EnvType.CLOUD, EnvType.UNKNOWN -> apiName?.let { AgentEnvDto(type = "cloud", name = it) }
}

/**
 * The Create An Agent body for this launch. A repository is the target and goes out as `repos[0]` with its starting
 * ref. Without one, what goes out depends on where the chat runs, as the reference has it (`CreateAgentRequest.repos`:
 * "Mutually exclusive with a named cloud environment. Omit both `repos` and `env` (or pass `repos: []`) to start a
 * no-repo agent"; `AgentEnv.name`: "Omit `repos` with `type: pool` to target a repo-less pool"):
 *  - the ordinary cloud — the web's "Start from scratch" — sends `repos: []` and no `env`. The explicit form is the
 *    one that cannot be read as anything else: an absent `repos` is also what a request that means "whatever the
 *    account's default repository is" looks like (the server's `repository_required` refusal offers "configure a
 *    default repository" as the alternative to `repos[0].url`), and this app has no such default to fall back on;
 *  - a named cloud environment sends `env` alone: the environment carries its repositories, and `repos` beside it
 *    is refused;
 *  - a pool or a machine sends `env` alone: the worker runs in its own checkout. The reference allows this for a
 *    pool; for a machine the server has been seen to answer `400 repository_required`, which is then shown as it is.
 * `autoCreatePR` stays out of every repository-less request (see [LaunchRequest.opensPullRequest]).
 */
fun LaunchRequest.toCreateAgentDto(): CreateAgentRequestDto {
    val envDto = env.toEnvDto()
    return CreateAgentRequestDto(
        prompt = PromptEncoding.toPromptDto(prompt, images),
        agentId = agentId,
        model = modelRef(modelId, modelParams),
        name = name,
        env = envDto,
        repos = when {
            repoUrl != null -> listOf(RepoConfigDto(url = repoUrl, startingRef = ref?.ifBlank { null }))
            envDto == null -> emptyList()
            else -> null
        },
        autoCreatePR = opensPullRequest.takeIf { it },
        mcpServers = mcpServers.toInlineServers(),
        mode = if (planMode) "plan" else null,
    )
}

/**
 * The launch's request went out and its answer never came back — the server silent past the read timeout, the
 * connection dropped — and the chat is nowhere on the account either, as far as `GET /v1/agents/{id}` could tell in
 * the moments after (see [AgentRepository.launch]). Worded for the composer, where the draft comes back with it.
 */
class LaunchUnansweredException(cause: Throwable) : IOException(
    "Cursor didn't answer the request to start this chat, and no chat appeared on your account. " +
        "Send it again to retry: a chat Cursor did create after all is picked up rather than started twice.",
    cause,
)

/** The request's `model` field: the id with the variant's parameters, or null so the field is omitted. */
private fun modelRef(modelId: String?, params: List<ModelParam>): ModelRefDto? = modelId?.let { id ->
    ModelRefDto(id = id, params = params.takeIf { it.isNotEmpty() }?.map { ModelParamDto(it.id, it.value) })
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
    /** What the demo's account list would say about its chats — which are Projects, which hang off which — for the same reason. */
    private val demoComposers: List<ComposerSnapshot> = emptyList(),
    /**
     * Whether [account] may be called, and whether what the account list said about the rows may be kept (Extended
     * mode). Everything, for tests of the list itself; the graph passes the setting, under which the default is the
     * public API alone: archive there only, no rename, and no row remembers a source the account gave it.
     */
    private val capabilities: suspend () -> Capabilities = { Capabilities.EXTENDED },
    /**
     * One chat's account record by id (Extended mode), for a pinned chat the public API will not give: the row is
     * stood in from the record so the pin is never blank. Null (the default) when there is no account to ask.
     */
    private val recordOf: suspend (String) -> ComposerSnapshot? = { null },
    /** How many `/v0/agents` pages the running pass reads on a refresh (see [RunningScan]). */
    private val runningScanPages: Int = RUNNING_SCAN_PAGES,
    /** How many agents the running scan named that no page holds are fetched by id per pass. */
    private val maxMaterializedRunning: Int = MAX_MATERIALIZED_RUNNING,
    /** How a launch whose reply was lost looks for its chat (see [launch]): reads of `GET /v1/agents/{id}`, and the wait between them. */
    private val lostReplyProbes: Int = LOST_REPLY_PROBES,
    private val lostReplyProbeDelayMs: Long = LOST_REPLY_PROBE_DELAY_MS,
    /**
     * The account's start (`StartBackgroundComposerFromSnapshot`) and its prompt uploads, for a launch whose prompt
     * carries files of any type — which the documented create request cannot (Extended mode, `promptFiles`). Null
     * when there is no account to start on; such a launch then fails before anything is sent.
     */
    private val start: (suspend () -> AgentStartApi)? = null,
    private val uploads: (suspend () -> PromptUploader)? = null,
) {
    private val restoreMutex = Mutex()

    private val _state = MutableStateFlow(AgentListState())
    val state: StateFlow<AgentListState> = _state.asStateFlow()

    private val _runningScan = MutableStateFlow(RunningScan())
    /** The account's running agents as the dedicated pass last found them (see [RunningScan]); reset with the list. */
    val runningScan: StateFlow<RunningScan> = _runningScan.asStateFlow()

    /**
     * The root registry (see [KnownRoot]): every Project root any source has named, by id, with what its record last
     * said of it. Fed by the account's records ([applyAccountSnapshots]), the roots memberships and transcripts name
     * ([applyLineage]), and kept on disk with the list; the Projects group is drawn from it, so a Project beyond the
     * loaded pages is listed all the same, and its row fetched by id ([materializeRoots]).
     */
    private val rootRecords = ConcurrentHashMap<String, KnownRoot>()
    private val _knownRoots = MutableStateFlow<List<KnownRoot>>(emptyList())
    val knownRoots: StateFlow<List<KnownRoot>> = _knownRoots.asStateFlow()

    /** Roots the list does not hold and could not fetch, by id, with when to try again. */
    private val rootUnresolved = ConcurrentHashMap<String, Long>()
    private val rootMutex = Mutex()

    /**
     * Pinned chats the list does not hold and could not fetch, by id, with when they were last tried: asked again
     * after [PINNED_RETRY_MS]. What the diagnostics report as unresolved pins.
     */
    private val pinnedUnresolved = ConcurrentHashMap<String, Long>()
    private val pinnedMutex = Mutex()

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
    /**
     * The parent links stamped onto chats beside their records, the way the desktop stamps them (see
     * [AgentsWindowList]): a root's `ListWorkersForManager` answer naming a worker (the desktop's seeded
     * `managerAgentId`), a root's children answer, an action taken here (`_stampListedCloudAgentManager`), a
     * coordinator's `create_agent` in default mode — by chat id. Applied to every row on every publication (see
     * [publish]) together with the row's own record, so a row the public list re-adds, a page that lands after the
     * word did, or a list restored from disk is placed the same. Cleared with the list. A stamp never names a root:
     * a Project is the record's `projectMetadata` and nothing else (the desktop's `kf`).
     */
    private val placements = ConcurrentHashMap<String, Placement>()

    private class Placement(val parent: AgentParent, val signal: LineageSignal, val atMillis: Long = AppClock.now())

    /** Bumped whenever the registry changes without a row changing, so the disk copy follows (see [persist]). */
    private val registryChanges = MutableStateFlow(0L)

    /**
     * Rows the account was asked for a record of and gave none (a chat the account does not list) or could not be
     * asked (offline), by id, with when to ask again (see [materializeRecords]).
     */
    private val recordUnresolved = ConcurrentHashMap<String, Long>()
    private val recordMutex = Mutex()

    /**
     * The account's records of chats the list does not hold yet, by id — the account list and the public list are
     * windowed differently, and the record read now dresses the row a later page brings (see [Agent.placed]), so
     * the row is published placed, as the desktop publishes every row with its record. Bounded, oldest first out;
     * kept on disk with the list. Guarded by [publishLock].
     */
    private val pendingRecords = object : LinkedHashMap<String, RecordFields>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecordFields>?): Boolean = size > MAX_PENDING_RECORDS
    }

    /** Epoch millis of the last completed fetch for the current backend; zero before the first one and after a [reset]. */
    @Volatile var lastRefreshedAt: Long = 0L
        private set

    /**
     * Where the list has been paged to: how many v1 pages the window holds, and the cursors past its last page on
     * each endpoint (null once an endpoint ran out). A refresh re-reads the window and lands here again; [loadMore]
     * takes the next page from here. Guarded by [publishLock] with the list they describe.
     */
    private var pagesLoaded = 0
    private var nextCursor: String? = null
    private var legacyCursor: String? = null
    /** The creation stamp of the oldest row the pages read in sequence reached (`Long.MIN_VALUE` past the last page): where the next page's stretch begins. */
    private var pagedFloor = Long.MAX_VALUE
    private var loadingMore: Job? = null

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
                combine(_state.filter { it.hasLoaded && !it.isFromCache }.map { it.agents }.distinctUntilChanged(), registryChanges) { agents, _ -> agents }.conflate().collect {
                    delay(persistDelayMs)
                    persist()
                }
            }
        }
        scope.launch {
            // A pin is a promise the chat is shown: whenever the pins or the rows change, any pinned chat the list
            // does not hold is fetched by id (see [resolvePinned]), whatever page it would have been on.
            combine(_state.filter { it.hasLoaded && !it.isFromCache }.map { st -> st.agents.mapTo(HashSet()) { it.id } }, prefs.localAgentState.map { it.pinnedIds }) { held, pinned -> pinned - held }
                .distinctUntilChanged()
                .collect { missing -> if (missing.isNotEmpty()) resolvePinned() }
        }
        scope.launch {
            // A row without its account record — brought by a page, the running scan, a pin, a fetch by id — is
            // asked for it (see [materializeRecords]) as soon as it shows, so it is placed the way the desktop
            // would place it and not the way the public API's bare row reads.
            _state.filter { it.hasLoaded && !it.isFromCache }.map { st -> st.agents.filter { it.record == null }.mapTo(HashSet()) { it.id } }
                .distinctUntilChanged()
                .collect { bare -> if (bare.isNotEmpty()) materializeRecords() }
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
        pagesLoaded = 0
        nextCursor = null
        legacyCursor = null
        pagedFloor = Long.MAX_VALUE
        _refreshCompleted.value = 0L
        _state.value = AgentListState()
        placements.clear()
        recordUnresolved.clear()
        pendingRecords.clear()
        rootRecords.clear()
        _knownRoots.value = emptyList()
        rootUnresolved.clear()
        _runningScan.value = RunningScan()
        pinnedUnresolved.clear()
    }

    /**
     * Records a root in the registry, merging what was known (see [KnownRoot.merged]); true when the registry
     * changed. Only the record's own flag names one: the desktop's `kf` (`projectMetadata` present on a chat with no
     * parent link); a membership answer, an action, a transcript or a source never does.
     */
    private fun noteRoot(root: KnownRoot): Boolean {
        if (root.id.isBlank() || !root.isEvidenced) return false
        val current = rootRecords[root.id]
        val next = current?.merged(root) ?: root
        if (next == current) return false
        rootRecords[root.id] = next
        publishRoots()
        return true
    }

    /**
     * Re-validates a registry root after a source's later word: [flagged] false when its record no longer carries
     * `projectMetadata` (the desktop's `isProject` gone: the root leaves, its row a chat of the account's own),
     * [membershipWorkers] what the last `ListWorkersForManager` answer counted (kept for the export, no evidence).
     */
    private fun revalidateRoot(id: String, flagged: Boolean? = null, membershipWorkers: Int? = null) {
        val current = rootRecords[id] ?: return
        val next = current.copy(
            flagged = flagged ?: current.flagged,
            membershipWorkers = membershipWorkers ?: current.membershipWorkers,
        )
        if (next.isEvidenced) {
            if (next != current) {
                rootRecords[id] = next
                publishRoots()
            }
            return
        }
        rootRecords.remove(id)
        publishRoots()
    }

    /**
     * Re-validates the registry against a pass over the account list: a root whose record the pass carried without
     * `projectMetadata` has the account's own word against it and leaves — its row a chat of the account's own. A
     * root the pass did not reach is left as it was (silence drops nothing). [managers] is what workers' records
     * named as manager: information for the export, since the desktop makes no Project of a manager.
     */
    fun revalidateRegistry(seen: Set<String>, roots: Set<String>, managers: Set<String> = emptySet(), startedIn: Int = token()) {
        if (seen.isEmpty()) return
        synchronized(publishLock) {
            if (generation.get() != startedIn) return
            val stale = rootRecords.values.filter { it.id in seen && it.id !in roots }.map { it.id }
            managers.forEach { id -> rootRecords[id]?.let { known -> if (known.namedBy == 0) rootRecords[id] = known.copy(namedBy = 1) } }
            stale.forEach { rootRecords.remove(it) }
            publishRoots()
            if (stale.isNotEmpty()) publish(null, startedIn) { it }
        }
    }

    private fun forgetRoot(id: String) {
        if (rootRecords.remove(id) != null) publishRoots()
    }

    private fun publishRoots() {
        _knownRoots.value = rootRecords.values.sortedByDescending { it.lastSeenMillis }
        registryChanges.update { it + 1 }
    }

    /**
     * Fetches by id every root the registry knows and the list does not hold, so its row heads its tree rather than
     * a stand-in: a few per pass, each tried again after a while when it failed. A root the server says is gone
     * (404) leaves the registry: that is the record's own word, the one thing that drops a root.
     */
    suspend fun materializeRoots(startedIn: Int = token(), budget: Int = MAX_MATERIALIZED_ROOTS) {
        if (session.isDemo || !_state.value.hasLoaded || _state.value.isFromCache) return
        rootMutex.withLock {
            val held = _state.value.agents.mapTo(HashSet()) { it.id }
            val now = AppClock.now()
            val due = rootRecords.keys.filter { it !in held && (rootUnresolved[it]?.let { until -> now >= until } ?: true) }.take(budget)
            for (id in due) {
                if (generation.get() != startedIn) return
                val fetched = loadDetail(id)
                if (fetched.isSuccess) {
                    rootUnresolved.remove(id)
                    rootFailures.remove(id)
                    continue
                }
                val failure = fetched.exceptionOrNull()
                val gone = (failure as? CursorApiException)?.httpCode == 404
                // The public API's refusal is not the last word while the account still has the record: the row
                // stands in from the record (its name, look and archive flag), as a pinned chat's does.
                val asked = runCatching { recordOf(id) }
                val record = asked.getOrNull()
                val accountAnswered = asked.isSuccess && !session.isDemo && capabilities().accountSession
                when {
                    record != null && generation.get() == startedIn -> {
                        upsert(record.toStandIn(), startedIn)
                        rootUnresolved[id] = now + PINNED_RETRY_MS
                        rootFailures[id] = "stood in from the account record; public API: ${failure?.describe() ?: "-"}"
                    }
                    gone && accountAnswered -> {
                        // Gone on both counts: the one word that drops a root.
                        rootUnresolved.remove(id)
                        rootFailures.remove(id)
                        synchronized(publishLock) {
                            if (generation.get() == startedIn) {
                                placements.remove(id)
                                forgetRoot(id)
                            }
                        }
                    }
                    else -> {
                        rootUnresolved[id] = now + if (gone) PINNED_GONE_RETRY_MS else PINNED_RETRY_MS
                        rootFailures[id] = failure?.describe() ?: "failed"
                    }
                }
            }
        }
    }

    private fun Throwable.describe(): String = "${javaClass.simpleName}: ${message ?: "-"}"

    /** The roots the registry knows and the list does not hold and could not fetch, for the diagnostics. */
    fun unresolvedRoots(): Set<String> = rootUnresolved.keys.toSet()

    /** What the last fetch by id of each unresolved root answered, for the diagnostics. */
    fun rootFailures(): Map<String, String> = rootFailures.toMap()

    private val rootFailures = ConcurrentHashMap<String, String>()

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
            // Every publication ends with the classification pass: whatever [transform] did to the rows — merged a
            // page, folded in a record, restored the disk — each row is placed by what is known of its lineage.
            _state.value = transform(_state.value).classified()
            true
        }

    /**
     * Places every row the desktop's way (see [AgentsWindowList]): the parent link is the record's own
     * `subagentParentId` — `cloudSubagentParent.parentAgentId`, else `sideChatInfo.parentBcId`, else `managerAgentId`
     * — with the stamps the desktop makes over it (an action taken here overrides the listed record until the record
     * has caught up; a membership or children answer, or a coordinator's `create_agent`, fills in what the record
     * did not carry); the Project flag is the record's `projectMetadata`, or the registry's word for a row whose
     * record is not read yet. Nothing else is read. The list is returned as it was when no row changes, so a
     * publication of the same rows stays the same value.
     */
    private fun AgentListState.classified(): AgentListState {
        if (agents.isEmpty()) return this
        var changed = false
        val next = agents.map { agent ->
            val placed = agent.placed()
            if (placed == agent) agent else placed.also { changed = true }
        }
        return if (changed) copy(agents = next) else this
    }

    private fun Agent.placed(): Agent {
        // The record: read with the row, or read before the row arrived (see [pendingRecords]).
        val record = record ?: synchronized(publishLock) { pendingRecords.remove(id) }
        val stamp = placements[id]?.takeIf { it.signal.isPlacing && it.parent.id != id }
            // A coordinator's transcript is default mode's word for a record it cannot read; a record read says all there is.
            ?.takeUnless { it.signal == LineageSignal.COORDINATOR_CREATED && record != null }
        // The record's own parent link: from the raw fields when the record has been read, else the link the row
        // was last given by a record (a row from the disk, or the demo's dataset).
        val recordParent = if (record != null) record.desktopParent else parent?.takeIf { scopeSignal == LineageSignal.ACCOUNT_RECORD }
        val action = stamp?.takeIf { it.signal == LineageSignal.ACTION }
        val (nextParent, nextSignal) = when {
            action != null -> action.parent to LineageSignal.ACTION
            recordParent != null -> recordParent to LineageSignal.ACCOUNT_RECORD
            stamp != null -> stamp.parent to stamp.signal
            else -> null to null
        }
        // The Project flag: the record's `projectMetadata` when the record has been read; the registry's word
        // (a record read elsewhere: a scan, an earlier round) for a row the public API alone gave.
        val root = rootRecords[id]
        val nextProject = when {
            record != null -> record.projectMetadata != null
            root != null -> true
            else -> isProject
        }
        val nextAppearance = projectAppearance ?: root?.appearance?.takeIf { nextProject }
        val signal = nextSignal ?: if (nextProject) (root?.signal ?: scopeSignal?.takeIf { it == LineageSignal.ACCOUNT_RECORD } ?: LineageSignal.ACCOUNT_RECORD) else null
        return if (nextParent == parent && nextProject == isProject && signal == scopeSignal && nextAppearance == projectAppearance && record == this.record) this
        else copy(parent = nextParent, isProject = nextProject, scopeSignal = signal, projectAppearance = nextAppearance, record = record)
    }

    /**
     * Stamps a parent link onto [id] the way the desktop does (see [placements]); true when the registry changed. A
     * stamp from a legacy signal (a source, a transcript's mention) is refused: nothing places by those any more.
     */
    private fun place(id: String, parent: AgentParent, signal: LineageSignal): Boolean {
        if (id.isBlank() || parent.id.isBlank() || parent.id == id || !signal.isPlacing) return false
        val current = placements[id]
        // A stamp made by an action here stands until a record has caught up with it; another source's word does
        // not replace it meanwhile. Nor does a coordinator's transcript replace the account's own word.
        if (current != null && current.signal == LineageSignal.ACTION && signal != LineageSignal.ACTION && AppClock.now() - current.atMillis < ACTION_GRACE_MS) return false
        if (current != null && current.signal.isAuthoritative && !signal.isAuthoritative) return false
        if (current != null && current.parent == parent && current.signal == signal) return false
        placements[id] = Placement(parent, signal)
        registryChanges.update { it + 1 }
        return true
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
            // Sources the account list gave the rows were written to disk with them; with Extended mode off (no
            // account session, so no list read) they are not this install's to show, whichever earlier launch or
            // build learned them.
            val accountSession = capabilities().accountSession
            // A row is placed by its record's raw fields, kept with it; a row an older build wrote without them
            // carries a flag that build may have set on `startedAsNewProject`, so the flag is dropped and re-read —
            // the registry dresses the roots it knows meanwhile. A parent link an older build stamped from a
            // source or a transcript's mention places nothing any more.
            val kept = entry.value.map { row ->
                when {
                    row.record != null -> row
                    row.parent != null && row.scopeSignal?.isPlacing == false -> row.copy(isProject = false, parent = null, scopeSignal = null)
                    row.isProject -> row.copy(isProject = false)
                    else -> row
                }
            }
            val rows = if (accountSession) kept else kept.withoutAccountSources(prefs.localAgentState.first().launchedHereIds)
            // The words kept with the list about chats no row carries (see [persist]).
            val lineage = cache.readLineage()
            val landed = publish(backend, startedIn) { s ->
                // A fetch that finished in the meantime wins over the disk.
                if (s.agents.isNotEmpty() || s.hasLoaded) s else {
                    // The stamps the rows were placed by last time — a membership's, an action's, a created worker's
                    // — so the rows the next fetch brings are placed the same before the account has spoken again.
                    rows.forEach { row ->
                        val parent = row.parent ?: return@forEach
                        val signal = row.scopeSignal ?: return@forEach
                        if (signal != LineageSignal.ACCOUNT_RECORD && signal.isPlacing) place(row.id, parent, signal)
                    }
                    lineage?.placements?.forEach { word ->
                        val parentId = word.parentId ?: return@forEach
                        if (word.signal.isPlacing) place(word.id, AgentParent(parentId, word.kind ?: AgentParentKind.PROJECT_WORKER), word.signal)
                    }
                    // The account's records of chats the rows did not hold, for the pages that bring them: facts
                    // already read, kept in either mode like the records the rows carry.
                    lineage?.records?.forEach { pendingRecords[it.id] = it.fields }
                    // Only entries whose record's flag was read with its fields come back: what an older build
                    // admitted on a bare flag, a membership, a transcript or a source is re-learned from the account.
                    lineage?.roots?.filter { it.isEvidencedStrictly }?.forEach { noteRoot(it) }
                    // The refresh re-reads about as deep as the disk copy reaches, so what it shows is what it refreshes.
                    pagesLoaded = ((rows.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceIn(1, MAX_PAGES)
                    s.copy(agents = rows, hasLoaded = true, isFromCache = true)
                }
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
        val (job, joined) = startOrJoin(silent, depth)
        if (joined && !silent) publish(null, startedIn) { it.copy(isRefreshing = true) }
        job.join()
    }

    /**
     * Fetches the page after the window's last one, on both endpoints, and adds its rows to the list: for the reader
     * reaching the end of the sidebar. One at a time; a refresh in flight is waited for first, since it lands the
     * cursors this reads from. [RefreshOutcome.Skipped] when there is no page to fetch or one is already being fetched.
     */
    suspend fun loadMore(): RefreshOutcome {
        val backend = session.current
        val startedIn = token()
        synchronized(this) { inFlight?.job?.takeIf { it.isActive } }?.join()
        val job = synchronized(publishLock) {
            if (generation.get() != startedIn || owner !== backend) return RefreshOutcome.Skipped
            loadingMore?.takeIf { it.isActive }?.let { return RefreshOutcome.Skipped }
            val cursor = nextCursor ?: return RefreshOutcome.Skipped
            scope.launch { fetchMore(backend, startedIn, cursor, legacyCursor) }.also { loadingMore = it }
        }
        job.join()
        return if (synchronized(publishLock) { generation.get() == startedIn && !_state.value.isLoadingMore && _state.value.error == null }) RefreshOutcome.Refreshed else RefreshOutcome.Failed
    }

    private suspend fun fetchMore(backend: CursorBackend, startedIn: Int, cursor: String, legacy: String?) {
        val api = backend.api
        val startedAt = AppClock.now()
        fun publish(transform: (AgentListState) -> AgentListState): Boolean = publish(backend, startedIn, transform)
        // What the page is about to cover: the rows known so far, and where the pages read in sequence so far end.
        val before = _state.value.agents
        val knownBefore = before.mapTo(HashSet()) { it.id }
        val ceiling = synchronized(publishLock) { pagedFloor }
        publish { it.copy(isLoadingMore = true) }
        try {
            coroutineScope {
                // The legacy list enriches the rows and never gates them: its page is read alongside, best effort.
                val legacyPage = async { legacy?.let { runCatching { api.listAgentsV0(limit = PAGE_SIZE, cursor = it) }.getOrNull() } }
                val page = api.listAgents(limit = PAGE_SIZE, cursor = cursor, includeArchived = true)
                val enrichment = legacyPage.await()
                val more = page.nextCursor?.isNotBlank() == true
                val pinned = prefs.localAgentState.first().pinnedIds
                // The page covers the list from where the pages before it ended down to its own oldest row — to the
                // very end when it is the last page — so a known row created in that stretch that the page did not
                // return is gone (deleted elsewhere), the way a refresh reconciles the window it re-reads.
                val pageFloor = if (!more) Long.MIN_VALUE else page.items.minOfOrNull { parseIsoMillis(it.createdAt) } ?: ceiling
                val seen = page.items.mapTo(HashSet()) { it.id }
                val landed = publish { s ->
                    (if (page.items.isNotEmpty()) s.withPage(page.items) else s)
                        .let { withRows -> enrichment?.agents?.takeIf { it.isNotEmpty() }?.let { v0 -> withRows.withLegacy(v0.associateBy { it.id }) } ?: withRows }
                        .let { s2 ->
                            s2.copy(agents = s2.agents.filter { it.id in seen || it.id !in knownBefore || it.createdAtMillis >= ceiling || it.createdAtMillis < pageFloor || it.createdAtMillis > startedAt - RECENT_WINDOW_MS || it.id in pinned })
                        }
                        .copy(isLoadingMore = false, hasMore = more, error = null)
                }
                if (landed) synchronized(publishLock) {
                    if (generation.get() == startedIn) {
                        pagesLoaded = (pagesLoaded + 1).coerceAtMost(MAX_PAGES)
                        nextCursor = page.nextCursor?.takeIf { it.isNotBlank() }
                        legacyCursor = enrichment?.nextCursor?.takeIf { it.isNotBlank() }
                        pagedFloor = pageFloor
                    }
                }
            }
            materializeRecords(startedIn)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            publish { it.copy(isLoadingMore = false, error = t.userMessage()) }
        }
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

    /**
     * The account's list read ahead of a fetch's first publication (Extended mode): set by the graph to the pin
     * repository's read, so the rows a page brings are published already placed — the workers under their
     * Projects, the coordinators as roots — rather than published bare and moved once the account has spoken. The
     * fetch starts it with the first page's request and waits for it [ACCOUNT_WORD_WAIT_MS] at most; a read that
     * is slower, or fails, holds nothing up (the rows are re-placed when it lands or is retried).
     */
    @Volatile var accountPrime: (suspend () -> Unit)? = null

    private suspend fun fetch(silent: Boolean, depth: RefreshDepth) {
        val backend = session.current
        val api = backend.api
        val startedAt = AppClock.now()
        dropForeignList(backend)
        val startedIn = generation.get()
        // The account's word is asked for alongside the first page, and waited for before that page is published.
        val prime = accountPrime?.takeIf { !backend.isDemo }?.let { hook -> scope.async { runCatching { hook() }.exceptionOrNull()?.let { if (it is CancellationException) throw it } } }
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
            var lastCursor: String? = null
            var pagesRead = 0
            // The oldest creation in the window read: a known row created after it that the pages did not return is gone.
            var windowFloor = Long.MAX_VALUE
            val seen = HashSet<String>()
            val pages = pagesToFetch(depth)
            var legacyRead = LegacyRead()
            coroutineScope {
                // The legacy list is read further than the window: its pages carry the one execution status per agent
                // the documented API has, so the pass over them is also the running scan (see [RunningScan]).
                val legacy = async { fetchLegacy(api, windowPages = pages, scanPages = maxOf(pages, runningScanPages)) }
                var cursor: String? = null
                do {
                    val page = api.listAgents(limit = PAGE_SIZE, cursor = cursor, includeArchived = true)
                    pagesRead++
                    seen += page.items.map { it.id }
                    page.items.minOfOrNull { parseIsoMillis(it.createdAt) }?.let { windowFloor = minOf(windowFloor, it) }
                    if (page.items.isNotEmpty()) {
                        if (pagesRead == 1) prime?.let { withTimeoutOrNull(ACCOUNT_WORD_WAIT_MS) { it.join() } }
                        publish { it.withPage(page.items) }
                    }
                    cursor = page.nextCursor?.takeIf { it.isNotBlank() }
                } while (cursor != null && pagesRead < pages)
                truncated = cursor != null
                lastCursor = cursor
                legacyRead = legacy.await()
                if (legacyRead.rows.isNotEmpty()) publish { it.withLegacy(legacyRead.rows) }
                synchronized(publishLock) { if (generation.get() == startedIn) legacyCursor = legacyRead.windowCursor }
            }
            verifyRunStatuses(api, before, startedAt) { transform -> publish(transform) }
            if (legacyRead.pagesRead > 0) {
                _runningScan.update { it.copy(ids = legacyRead.running, scannedAtMillis = AppClock.now(), pagesRead = legacyRead.pagesRead, complete = legacyRead.complete) }
            }
            // Pinned rows outlive the listing window, as they do in the desktop sidebar; the pin sync fetches the
            // ones the window never returned, and this keeps the next listing from dropping them again.
            val pinned = prefs.localAgentState.first().pinnedIds
            // A row the server did not return is gone when the pass reached the end of the list, or when the row
            // sits inside the window the pass did read (the list is newest first, so a row created after the
            // window's oldest would have been in it); a row beyond where the pass stopped is merely not reached.
            val complete = depth != RefreshDepth.Quick && !truncated
            val floor = if (complete) Long.MIN_VALUE else if (depth != RefreshDepth.Quick) windowFloor else Long.MAX_VALUE
            val landed = publish { s ->
                s.withoutUnseen(seen, knownBefore, startedAt, pinned, floor)
                    .let { if (backend.isDemo) it.withSources(demoSources).withAccountSnapshots(demoComposers) else it }
                    .copy(isRefreshing = false, hasLoaded = true, isFromCache = false, error = null, hasMore = truncated)
            }
            // Under the same lock as the publication: a completed fetch is the cue the account's pins are synced
            // on, and the previous account's must not give it.
            if (landed) synchronized(publishLock) {
                if (generation.get() == startedIn) {
                    lastRefreshedAt = AppClock.now()
                    pagesLoaded = pagesRead.coerceAtLeast(1)
                    nextCursor = lastCursor
                    pagedFloor = if (complete) Long.MIN_VALUE else windowFloor
                    _refreshCompleted.update { it + 1 }
                }
            }
            // The pass ends by reconciling the pages with what stands apart from them: the agents the running scan
            // named that no page holds, and the pinned chats no page holds, are fetched by id.
            if (landed) {
                materializeRunning(startedIn)
                resolvePinned(startedIn)
                materializeRoots(startedIn)
                // Then every row still without its account record is asked for it, so the desktop's predicates
                // place the rows the public endpoints brought bare.
                materializeRecords(startedIn)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            publish { s ->
                val keepQuiet = silent && s.agents.isNotEmpty()
                s.copy(isRefreshing = false, hasLoaded = true, error = if (keepQuiet) s.error else t.userMessage())
            }
        }
    }

    /**
     * What one pass over the legacy list read: its rows by id, the cursor past the window's pages (for [loadMore]),
     * and the running scan — every agent whose status the pages called active, how many pages that took, and
     * whether the pass reached the end of the list.
     */
    private class LegacyRead(
        val rows: Map<String, V0AgentDto> = emptyMap(),
        val windowCursor: String? = null,
        val running: Set<String> = emptySet(),
        val pagesRead: Int = 0,
        val complete: Boolean = false,
    )

    /**
     * The v0 list is best effort: it enriches rows but never gates them, and its failure is not the list's failure.
     * Read as many pages as the v1 window ([windowPages]) for the cursor past them, and on to [scanPages] for the
     * running scan; a failure part-way keeps what was read.
     */
    private suspend fun fetchLegacy(api: CursorApi, windowPages: Int, scanPages: Int): LegacyRead {
        val rows = LinkedHashMap<String, V0AgentDto>()
        val running = LinkedHashSet<String>()
        var windowCursor: String? = null
        var cursor: String? = null
        var pages = 0
        var complete = false
        try {
            do {
                val page = api.listAgentsV0(limit = PAGE_SIZE, cursor = cursor)
                pages++
                page.agents.forEach { row ->
                    rows[row.id] = row
                    if (RunStatus.parse(row.status).isActive) running += row.id
                }
                cursor = page.nextCursor?.takeIf { it.isNotBlank() }
                if (pages == windowPages) windowCursor = cursor
                if (cursor == null) complete = true
            } while (cursor != null && pages < scanPages)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // What was read stands; the scan is as far as it got.
        }
        return LegacyRead(rows, windowCursor, running, pages, complete)
    }

    /**
     * Fetches by id every agent the running scan named that the loaded pages do not hold — an old chat with a fresh
     * follow-up, a chat beyond the window — so the live tracking and the running count see it. A few per pass; the
     * next pass takes the rest.
     */
    private suspend fun materializeRunning(startedIn: Int) {
        val scan = _runningScan.value
        val held = _state.value.agents.mapTo(HashSet()) { it.id }
        val missing = scan.all.filter { it !in held }.take(maxMaterializedRunning)
        for (id in missing) {
            if (generation.get() != startedIn) return
            loadDetail(id)
        }
    }

    /**
     * A pinned chat is always shown, whatever page it would have been on, whatever it runs on and whatever it was
     * classified as: every pinned id the list does not hold is fetched by id through the public API, and when that
     * will not give it (a chat the public API does not know), stood in from its account record where there is one.
     * A chat that could not be fetched is asked for again after [PINNED_RETRY_MS]; the diagnostics name it meanwhile.
     */
    suspend fun resolvePinned(startedIn: Int = token()) {
        // The demo's list is its whole dataset: nothing of it is beyond a page.
        if (session.isDemo || !_state.value.hasLoaded || _state.value.isFromCache) return
        pinnedMutex.withLock {
            val pinned = prefs.localAgentState.first().pinnedIds
            val held = _state.value.agents.mapTo(HashSet()) { it.id }
            pinnedUnresolved.keys.retainAll(pinned)
            val now = AppClock.now()
            val due = pinned.filter { it !in held && (pinnedUnresolved[it]?.let { tried -> now >= tried } ?: true) }
            for (id in due) {
                if (generation.get() != startedIn) return
                val fetched = loadDetail(id)
                if (fetched.isSuccess) {
                    pinnedUnresolved.remove(id)
                    continue
                }
                val record = runCatching { recordOf(id) }.getOrNull()
                if (record != null && generation.get() == startedIn) {
                    upsert(record.toStandIn(), startedIn)
                    pinnedUnresolved.remove(id)
                } else {
                    // Tried again after a while (offline, a server that is down); a chat the server says is gone,
                    // much later — the account may still come to know it once Extended mode is on.
                    val gone = (fetched.exceptionOrNull() as? CursorApiException)?.httpCode == 404
                    pinnedUnresolved[id] = now + if (gone) PINNED_GONE_RETRY_MS else PINNED_RETRY_MS
                }
            }
        }
    }

    /** The pinned ids the list does not hold and could not fetch, for the diagnostics. */
    fun unresolvedPinned(): Set<String> = pinnedUnresolved.keys.toSet()

    /**
     * Extended mode: every row the public API alone gave — a page's row before the account's round reached it, an
     * agent the running scan named, a pinned chat, a root fetched by id — is asked for its account record by id, so
     * the desktop's predicates have the record's fields to read (see [Agent.placed]). The desktop never shows a row
     * without its record; this is the closest the public list can come. Running rows first, then the newest; a few
     * per pass, the next pass takes the rest; a chat the account gave no record for is asked again after
     * [RECORD_RETRY_MS]. Nothing is asked in default mode or in the demo.
     */
    suspend fun materializeRecords(startedIn: Int = token(), budget: Int = MAX_MATERIALIZED_RECORDS) {
        if (session.isDemo || !_state.value.hasLoaded || _state.value.isFromCache || !capabilities().accountSession) return
        recordMutex.withLock {
            val now = AppClock.now()
            val due = _state.value.agents
                .filter { it.record == null && it.id !in pendingLaunches && (recordUnresolved[it.id]?.let { until -> now >= until } ?: true) }
                .sortedWith(compareByDescending<Agent> { it.isRunning }.thenByDescending { it.updatedAtMillis })
                .take(budget)
            for (row in due) {
                if (generation.get() != startedIn) return
                val record = try {
                    recordOf(row.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    recordUnresolved[row.id] = now + PINNED_RETRY_MS
                    continue
                }
                if (record == null) {
                    recordUnresolved[row.id] = now + RECORD_RETRY_MS
                    continue
                }
                applyAccountSnapshots(listOf(record), startedIn)
            }
        }
    }

    /** Rows asked for an account record that gave none or could not be asked, for the diagnostics. */
    fun unresolvedRecords(): Set<String> = recordUnresolved.keys.toSet()

    /** A row stood in from an account record alone: the name, archive flag and times the record gives, nothing the public API would. */
    private fun ComposerSnapshot.toStandIn(): Agent = Agent(
        id = id,
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Chat",
        lifecycle = if (archived == true) AgentLifecycle.ARCHIVED else AgentLifecycle.UNKNOWN,
        runStatus = status,
        envType = EnvType.UNKNOWN,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = createdAtMillis ?: 0L,
        updatedAtMillis = listedAtMillis ?: 0L,
        activityAtMillis = activityAtMillis,
        latestRunId = null,
        repoUrl = null,
        startingRef = null,
        source = source,
        isProject = isProject,
        projectAppearance = projectAppearance,
        parent = parent,
        hasPendingInteraction = hasPendingInteraction,
        scopeSignal = if (parent != null || isProject) LineageSignal.ACCOUNT_RECORD else null,
        record = record,
        accountModel = model,
    )

    /**
     * How many pages a pass reads: the newest one for a poll's quick look, else the window the list has been paged
     * to (at least one page, and at most [MAX_PAGES] however far the reader scrolled — the rows past that stay as
     * they were loaded). Nothing reads past the window on its own; that is what [loadMore] is for.
     */
    private fun pagesToFetch(depth: RefreshDepth): Int = when (depth) {
        RefreshDepth.Quick -> 1
        RefreshDepth.Full, RefreshDepth.Deep -> synchronized(publishLock) { pagesLoaded }.coerceIn(1, MAX_PAGES)
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
     * For Extended mode being turned off: the rows forget where the account said they were started. The chats
     * launched from this device ([launchedHere]) keep their source — this device knows that on its own — and so does
     * the demo, whose sources are its own dataset's. The disk copy follows with the next persist.
     */
    fun forgetAccountSources(launchedHere: Set<String>) {
        if (session.isDemo) return
        _state.update { s -> s.copy(agents = s.agents.withoutAccountSources(launchedHere)) }
    }

    private fun List<Agent>.withoutAccountSources(launchedHere: Set<String>): List<Agent> =
        map { if (it.source == null || it.id in launchedHere) it else it.copy(source = null) }

    /**
     * Rows the server no longer returns inside the window it was read for were deleted elsewhere: every row created
     * at or after [floor] (`Long.MIN_VALUE` after a pass that reached the end of the list). Kept anyway: rows that
     * were not known when the fetch started (launched here while the pages were in flight), agents created shortly
     * before it started (the listing can lag a creation by a moment), rows older than the window, and [pinned]
     * agents, which the listing window may simply have left behind.
     */
    private fun AgentListState.withoutUnseen(seen: Set<String>, knownBefore: Set<String>, startedAt: Long, pinned: Set<String>, floor: Long): AgentListState =
        copy(agents = agents.filter { it.id in seen || it.id !in knownBefore || it.createdAtMillis < floor || it.createdAtMillis > startedAt - RECENT_WINDOW_MS || it.id in pinned })

    private suspend fun persist() {
        val backend = session.current
        if (cache == null || backend.isDemo) return
        // The cache generation belongs to the list being written, so a wipe between here and the file refuses it.
        val (token, agents, lineage) = synchronized(publishLock) {
            val s = _state.value
            if (!s.hasLoaded || s.isFromCache) return
            // The registry's word about chats the rows do not carry themselves — above all the ones no page holds
            // yet — goes to the disk with them, so a restart places a later page the same. Bounded: the words about
            // chats nowhere near the list are the first to go.
            val held = s.agents.mapTo(HashSet()) { it.id }
            val words = placements.entries.filter { (id, _) -> id !in held }.take(MAX_PERSISTED_PLACEMENTS).map { (id, p) -> CachedPlacement(id, p.parent.id, p.parent.kind, p.signal) }
            val records = pendingRecords.entries.filter { (id, _) -> id !in held }.map { (id, fields) -> CachedRecord(id, fields) }
            Triple(cache.token(), s.agents.filterNot { it.id in pendingLaunches }, CachedLineage(words, roots = rootRecords.values.sortedByDescending { it.lastSeenMillis }.take(MAX_PERSISTED_PLACEMENTS), records = records))
        }
        cache.write(agents, token, lineage)
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
        // Extended mode: the row lands with its account record, as every row of the desktop's list does, so it is
        // published placed rather than published bare and moved once the record has been read.
        val record = if (merged.record == null && !session.isDemo && capabilities().accountSession) runCatching { recordOf(id) }.getOrNull() else null
        if (record != null && noteRecords(listOf(record), startedIn)) {
            publish(null, startedIn) { s ->
                val exists = s.agents.any { it.id == merged.id }
                s.copy(agents = if (exists) s.agents.map { if (it.id == merged.id) merged else it } else listOf(merged) + s.agents).withAccountSnapshots(listOf(record))
            }
            noteRunning(listOf(record), startedIn)
        } else {
            upsert(merged, startedIn)
        }
        agent(id) ?: merged
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
            autoCreatePr = request.opensPullRequest,
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
     * connection or a cancel), so that agent is adopted instead of failing or creating a duplicate. The same id is
     * what a launch whose own reply is lost — the server silent past the read timeout, the connection gone — is
     * looked up by, on the spot: the chat is read by id a few times over the next moments and adopted when the
     * server did create it (see [recoverLostReply]); only when it is nowhere does the launch fail, with
     * [LaunchUnansweredException] saying so rather than a bare timeout. An answer the server gave — a `400` with
     * its message, a `5xx` — is never waited on or asked about again: it fails the launch at once, as it is. A row
     * [beginLaunch] put in the list is replaced by the server's record, or removed when the request fails.
     * [saveImages] is off for a caller that staged the prompt's images itself and files them under the run.
     */
    suspend fun launch(request: LaunchRequest, modelDisplayName: String?, saveImages: Boolean = true, progress: UploadProgress = UploadProgress.NONE): Result<Launched> {
        val startedIn = token()
        val result = runCatching {
            val api = session.current.api
            val (dto, run) = if (request.files.isNotEmpty()) {
                startWithFiles(api, request, progress)
            } else try {
                // Off the main thread: base64-encoding the images and serializing the body happen before the call is
                // enqueued, on whichever thread makes it.
                withContext(Dispatchers.IO) { api.createAgent(request.toCreateAgentDto()) }.let { it.agent to it.run }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val id = request.agentId ?: throw t
                when {
                    t.toCursorError()?.code == AGENT_ID_CONFLICT -> adopt(api, id)
                    t.isLostReply() -> recoverLostReply(api, id) ?: throw LaunchUnansweredException(t)
                    else -> throw t
                }
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
            if (saveImages) run?.let { runCatching { attachments.save(agent.id, it.id, request.images, request.files) } }
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
     * A launch whose prompt carries files, the way the desktop starts one: the files are up already, from the moment
     * they were attached (see [AttachmentUploads]), and `StartBackgroundComposerFromSnapshot` carries them by their
     * references as `selected_documents[]` — an image as a `SelectedImage` — beside the inline images (see
     * [ConnectAgentStartApi][com.cursorforandroid.data.api.ConnectAgentStartApi]); a file without one is uploaded here first. The chat is then
     * read back through the documented API under the id the request minted — the account answers with its record,
     * not a run, and the list and the transcript are built from `GET /v1/agents/{id}` like every other chat's; the
     * first run is read once it is named, asked for a few times as [recoverLostReply] does. A machine or pool is
     * refused here, before anything is sent: the desktop's private-worker start has no equivalent in this request.
     */
    private suspend fun startWithFiles(api: CursorApi, request: LaunchRequest, progress: UploadProgress): Pair<AgentDto, RunDto?> {
        val id = requireNotNull(request.agentId) { "A launch with files needs the client-minted agent id." }
        val startApi = start ?: throw IllegalStateException(FILES_NEED_EXTENDED)
        val uploader = uploads ?: throw IllegalStateException(FILES_NEED_EXTENDED)
        if (!capabilities().promptFiles) throw IllegalStateException(FILES_NEED_EXTENDED)
        if (request.env.type == EnvType.POOL || request.env.type == EnvType.MACHINE) throw IllegalArgumentException(FILES_NEED_CLOUD)
        // The files went up when they were attached and carry their references; one that did not is uploaded here.
        val uploaded = uploader().ensure(request.files, progress)
        startApi().start(
            StartRequest(
                agentId = id,
                text = request.prompt,
                images = request.images,
                files = uploaded,
                repoUrl = request.repoUrl,
                ref = request.ref,
                environmentName = request.env.apiName,
                modelId = request.modelId,
                modelParams = request.modelParams,
                planMode = request.planMode,
                autoCreatePr = request.autoCreatePr,
                name = request.name,
                mcpServers = request.mcpServers,
            ),
        )
        return recoverLostReply(api, id) ?: throw LaunchUnansweredException(IOException("The chat was started on your account but has not appeared in the API yet."))
    }

    /** The agent an earlier attempt created under the client-minted [id], with its latest run when that can be read. */
    private suspend fun adopt(api: CursorApi, id: String): Pair<AgentDto, RunDto?> {
        val existing = api.getAgent(id)
        return existing to existing.latestRunId?.let { runId -> runCatching { api.getRun(existing.id, runId) }.getOrNull() }
    }

    /**
     * Looks for the chat a launch whose reply was lost may have created: `GET /v1/agents/{id}` under the id the
     * request carried, up to [lostReplyProbes] times, [lostReplyProbeDelayMs] apart — the server may still be
     * finishing what the request started when the first read is made, so a `404` is asked again in a moment. The
     * agent as soon as a read returns it; null once the reads have run out, or the moment a read gets no readable
     * answer either (the server out of reach: asking again would only hold the composer up), which is the caller's
     * cue that no chat is known to have been created.
     */
    private suspend fun recoverLostReply(api: CursorApi, id: String): Pair<AgentDto, RunDto?>? {
        repeat(lostReplyProbes) { attempt ->
            if (attempt > 0) delay(lostReplyProbeDelayMs)
            val found = try {
                api.getAgent(id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t.toCursorError()?.httpCode == 404) null else return null
            }
            if (found != null) return found to found.latestRunId?.let { runId -> runCatching { api.getRun(found.id, runId) }.getOrNull() }
        }
        return null
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
            current.switchedTo(modelId, modelParams, modelDisplayName)
                .copy(runStatus = RunStatus.parse(response.run.status), latestRunId = response.run.id, lifecycle = AgentLifecycle.ACTIVE)
                .touched(AppClock.now())
        }
        response.run
    }

    /**
     * The row after a follow-up that switched the chat to [modelId] was accepted: the device's record of the model
     * (the old label would describe the old model, so without a new one the id stands in) and, where the account's
     * record had named a model, that word too — the server now holds this one, and the list's next read would only
     * confirm it. A follow-up that sent no model changes nothing.
     */
    private fun Agent.switchedTo(modelId: String?, modelParams: List<ModelParam>, modelDisplayName: String?): Agent {
        if (modelId == null) return this
        return copy(
            modelId = modelId,
            modelParams = modelParams,
            modelDisplayName = modelDisplayName ?: modelId,
            accountModel = accountModel?.let { AccountModel(modelId, modelParams) },
        )
    }

    /**
     * A follow-up filed through the account service rather than the documented run request — for a mode the
     * documented API cannot carry (see `SteeringApi.addFollowup`). [send] answers with the id of the run the account
     * started, or null when it named none; the row is updated the way [followUp] updates it, the run stamped now
     * since the account reports no record for it. Null from [send] is success with no run to stream: the caller
     * reloads the chat instead.
     */
    suspend fun followUpVia(
        agentId: String,
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
        send: suspend () -> String?,
    ): Result<RunDto?> = runCatching {
        val startedIn = token()
        val runId = send()
        val now = AppClock.now()
        val stamp = Instant.ofEpochMilli(now).toString()
        val run = runId?.let { RunDto(id = it, agentId = agentId, status = RunStatus.CREATING.name, createdAt = stamp, updatedAt = stamp) }
        patch(agentId, startedIn) { current ->
            current.switchedTo(modelId, modelParams, modelDisplayName)
                .copy(runStatus = RunStatus.CREATING, latestRunId = run?.id ?: current.latestRunId, lifecycle = AgentLifecycle.ACTIVE)
                .touched(now)
        }
        run
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
     * mode only has the in-memory public API, and so does Extended mode off — in which case a chat archived here can
     * stay in the open list on cursor.com, since the two flags are not the same one.
     */
    private suspend fun setArchived(agentId: String, archived: Boolean): Result<Unit> = runCatching {
        val startedIn = token()
        var wrote = false
        var lastError: Throwable? = null
        if (!session.isDemo && account != null && capabilities().accountLifecycle) {
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
     * no rename, so with Extended mode off there is none here either; demo mode only updates the in-memory row.
     */
    suspend fun rename(agentId: String, name: String): Result<Unit> = runCatching {
        val startedIn = token()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Give the chat a name.")
        if (trimmed.length > MAX_NAME_LENGTH) throw IllegalArgumentException("Name must be $MAX_NAME_LENGTH characters or less.")
        if (agent(agentId)?.name == trimmed) return@runCatching
        if (!session.isDemo) {
            if (!capabilities().accountLifecycle) throw IllegalStateException(RENAME_NEEDS_EXTENDED_MODE)
            val api = account ?: throw IllegalStateException("Can't rename this chat from here.")
            api.rename(agentId, trimmed)
        }
        patch(agentId, startedIn) { it.copy(name = trimmed) }
    }

    /**
     * Folds in the account's records: the desktop's `_makeAgentHeader` for each. A record names the chat, says
     * whether it is archived, carries the raw fields the desktop's predicates read (its `projectMetadata`, its
     * `cloudSubagentParent`, `sideChatInfo.parentBcId` and `managerAgentId`), its source, its pending question and
     * its execution status. The record's word is the row's from here on (see [Agent.placed]): a stamp made by an
     * action here stands over it until the record has caught up, a membership's fills in only what the record does
     * not carry, a coordinator's `create_agent` yields to it. Every Project the records flag goes to the root
     * registry, and a root whose record no longer carries the flag leaves it.
     */
    fun applyAccountSnapshots(composers: List<ComposerSnapshot>, startedIn: Int = token()) {
        if (composers.isEmpty()) return
        if (!noteRecords(composers, startedIn)) return
        publish(null, startedIn) { it.withAccountSnapshots(composers) }
        noteRunning(composers, startedIn)
    }

    /**
     * The registry's half of [applyAccountSnapshots], under the lock: every Project the records flag goes to the root
     * registry and a root whose record no longer carries the flag leaves it; a stamp the record has caught up with
     * goes; the record of a chat the list does not hold is kept for the row (see [pendingRecords]). False when the
     * list has been reset since [startedIn].
     */
    private fun noteRecords(composers: List<ComposerSnapshot>, startedIn: Int): Boolean = synchronized(publishLock) {
        if (generation.get() != startedIn) return false
        val now = AppClock.now()
        val held = _state.value.agents.mapTo(HashSet()) { it.id }
        var kept = false
        composers.forEach { snap ->
            if (snap.scope == AgentScope.PROJECT_ROOT) {
                noteRoot(KnownRoot(snap.id, snap.name, snap.projectAppearance, snap.archived == true, LineageSignal.ACCOUNT_RECORD, now, flagged = true, record = snap.record ?: RecordFields(projectMetadata = "{}")))
            } else if (rootRecords[snap.id]?.flagged == true) {
                revalidateRoot(snap.id, flagged = false)
            }
            // The record has caught up with a stamp — or has had long enough to — and is the word from here on.
            placements[snap.id]?.let { stamp ->
                val caughtUp = when (stamp.signal) {
                    LineageSignal.ACTION -> snap.parent == stamp.parent || now - stamp.atMillis >= ACTION_GRACE_MS
                    LineageSignal.COORDINATOR_CREATED -> true
                    else -> snap.parent != null
                }
                if (caughtUp) placements.remove(snap.id)
            }
            recordUnresolved.remove(snap.id)
            if (snap.id !in held) {
                val fields = snap.recordFields()
                if (pendingRecords.put(snap.id, fields) != fields) kept = true
            } else {
                pendingRecords.remove(snap.id)
            }
        }
        // A record kept for a row to come is the registry's to persist, whether or not a row changed.
        if (kept) registryChanges.update { it + 1 }
        true
    }

    /**
     * The account's list carries its own word on what is running, for every composer in its window — a Project's
     * workers and side chats included; it is the Extended half of the running scan (see [RunningScan]).
     */
    private fun noteRunning(composers: List<ComposerSnapshot>, startedIn: Int) {
        if (composers.none { it.status != null }) return
        synchronized(publishLock) {
            if (generation.get() == startedIn) {
                _runningScan.update { it.copy(accountIds = composers.filter { c -> c.isRunning }.mapTo(LinkedHashSet()) { c -> c.id }, accountAtMillis = AppClock.now()) }
            }
        }
    }

    /** The record's raw fields, or the same made from what the snapshot carries when it has none (the demo's, a test's). */
    private fun ComposerSnapshot.recordFields(): RecordFields = record ?: RecordFields(
        projectMetadata = if (isProject) "{}" else null,
        managerAgentId = parent?.takeIf { it.kind == AgentParentKind.PROJECT_WORKER }?.id,
        subagentParentId = parent?.takeIf { it.kind == AgentParentKind.SUBAGENT }?.id,
        sideChatParentId = parent?.takeIf { it.kind == AgentParentKind.SIDE_CHAT }?.id,
        source = source?.name,
    )

    /** [materializeRunning] for the pin sync's round, once the account list has landed. */
    suspend fun reconcileRunning(startedIn: Int = token()) = materializeRunning(startedIn)

    private fun AgentListState.withAccountSnapshots(composers: List<ComposerSnapshot>): AgentListState {
        if (composers.isEmpty()) return this
        val byId = composers.associateBy { it.id }
        var changed = false
        val next = agents.map { agent ->
            val snap = byId[agent.id] ?: return@map agent
            val name = snap.name?.trim()?.takeIf { it.isNotEmpty() } ?: agent.name
            val lifecycle = when (snap.archived) {
                true -> AgentLifecycle.ARCHIVED
                false -> if (agent.lifecycle == AgentLifecycle.ARCHIVED) AgentLifecycle.IDLE else agent.lifecycle
                null -> agent.lifecycle
            }
            // The record's fields are the row's; the classification pass ([Agent.placed]) reads the parent link and
            // the flag from them, over the stamps that still stand.
            val record = snap.recordFields()
            val updated = agent.copy(
                name = name,
                lifecycle = lifecycle,
                isProject = snap.isProject,
                projectAppearance = snap.projectAppearance ?: agent.projectAppearance?.takeIf { snap.isProject },
                parent = snap.parent,
                scopeSignal = if (snap.parent != null || snap.isProject) LineageSignal.ACCOUNT_RECORD else null,
                record = record,
                source = snap.source ?: agent.source,
                hasPendingInteraction = snap.hasPendingInteraction,
                // The account's word on the model outranks what this device remembers sending; a record that names
                // none leaves what an earlier record said.
                accountModel = snap.model ?: agent.accountModel,
                // The record's own time, as the desktop dates the chat (`lastMessageActivityAtMs ?? updatedAtMs`),
                // taken as it is: not raised to the public row's `updatedAt`, which the account bumps for its own
                // reasons, nor to when this refresh ran. A record that dates nothing leaves what an earlier one said.
                activityAtMillis = snap.activityAtMillis ?: agent.activityAtMillis,
            )
            if (updated == agent) agent else updated.also { changed = true }
        }
        return if (changed) copy(agents = next) else this
    }

    /**
     * Stamps who belongs to [rootId], the way the desktop does: every chat in [members] is its child in the capacity
     * given, by [signal]'s word — a membership answer (the desktop's seeded `managerAgentId`), a children answer, an
     * action taken here (`_stampListedCloudAgentManager`), a coordinator's `create_agent` in default mode. For the
     * kinds in [retract], which an answer covered in full, the stamps of that kind the root had and the answer no
     * longer names are dropped; a chat whose own record names the root keeps the record's link. Rows the list does
     * not hold yet are placed when they arrive: the stamp is kept. Nothing here makes a Project of [rootId]: that is
     * the record's `projectMetadata` alone (a membership count is kept on the registry's entry for the export).
     */
    fun applyLineage(rootId: String, members: Map<String, AgentParentKind>, signal: LineageSignal, retract: Set<AgentParentKind> = emptySet(), startedIn: Int = token()) {
        if (rootId.isBlank() || !signal.isPlacing) return
        synchronized(publishLock) {
            if (generation.get() != startedIn) return
            members.forEach { (id, kind) -> if (id != rootId) place(id, AgentParent(rootId, kind), signal) }
            if (retract.isNotEmpty() && signal.isAuthoritative) {
                placements.entries.removeAll { (id, placement) ->
                    placement.parent.id == rootId && placement.parent.kind in retract && id !in members && placement.signal.isRetractable
                }
            }
            if (signal == LineageSignal.MEMBERSHIP && (AgentParentKind.PROJECT_WORKER in retract || members.isNotEmpty())) {
                revalidateRoot(rootId, membershipWorkers = members.values.count { it == AgentParentKind.PROJECT_WORKER })
            }
            publish(null, startedIn) { it }
        }
    }

    /** [applyLineage] with the account's word ([authoritative]: a membership answer) or a coordinator's `create_agent`. */
    fun applyLineage(rootId: String, members: Map<String, AgentParentKind>, authoritative: Boolean, startedIn: Int = token()) =
        applyLineage(rootId, members, if (authoritative) LineageSignal.MEMBERSHIP else LineageSignal.COORDINATOR_CREATED, startedIn = startedIn)

    /**
     * Releases [agentId] from whatever it hung off (an action taken here: `ClearWorkerManager`, the desktop's
     * `_clearListedCloudAgentManager`): the stamp goes, and the listed record's `managerAgentId` with it, so the row
     * is a chat of its own until the account lists it again.
     */
    fun clearLineage(agentId: String, startedIn: Int = token()) {
        synchronized(publishLock) {
            if (generation.get() != startedIn) return
            placements.remove(agentId)
            publish(null, startedIn) { s ->
                val row = s.agents.firstOrNull { it.id == agentId } ?: return@publish s
                val record = row.record?.copy(managerAgentId = null)
                if (row.parent == null && record == row.record) return@publish s
                s.copy(agents = s.agents.map { if (it.id == agentId) it.copy(parent = null, scopeSignal = null, record = record) else it })
            }
        }
    }

    /** The stamp on [agentId], for the diagnostics; null when the row is placed by its record alone. */
    fun placementOf(agentId: String): Pair<AgentParent?, LineageSignal>? = placements[agentId]?.let { it.parent to it.signal }

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

    companion object {
        private const val PAGE_SIZE = 100
        /** `/v0/agents` pages the running scan reads on a refresh: the newest five hundred agents by the list's order. */
        const val RUNNING_SCAN_PAGES = 5
        /** Agents the running scan named that no page holds, fetched by id per pass. */
        private const val MAX_MATERIALIZED_RUNNING = 12
        /** A pinned chat that could not be fetched is tried again after this long; one the server called gone, after [PINNED_GONE_RETRY_MS]. */
        const val PINNED_RETRY_MS = 5 * 60_000L
        const val PINNED_GONE_RETRY_MS = 30 * 60_000L
        /** Registry words about chats the rows do not carry, kept on disk at most. */
        private const val MAX_PERSISTED_PLACEMENTS = 2_000
        /** Roots the registry knows and no page holds, fetched by id per pass. */
        private const val MAX_MATERIALIZED_ROOTS = 8
        /** Rows without an account record, asked for one by id per pass (see [materializeRecords]). */
        private const val MAX_MATERIALIZED_RECORDS = 16
        /** Records of chats the list does not hold, kept for the rows to come (see [pendingRecords]). */
        private const val MAX_PENDING_RECORDS = 3_000
        /** A row the account gave no record for is asked again after this long. */
        const val RECORD_RETRY_MS = 10 * 60_000L
        /** How long a stamp made by an action here stands over a record that has not caught up with it (the desktop keeps its stamp until the next list refresh). */
        const val ACTION_GRACE_MS = 5 * 60_000L
        /** How long a fetch's first publication waits for the account's list (see [accountPrime]). */
        const val ACCOUNT_WORD_WAIT_MS = 4_000L
        /**
         * The most a refresh re-reads: 500 agents, the newest first. The list pages past that only as the reader
         * scrolls to its end ([loadMore]), and the rows so loaded stay as they were between refreshes.
         */
        private const val MAX_PAGES = 5
        private const val PERSIST_DELAY_MS = 1_500L
        private const val RECENT_WINDOW_MS = 5 * 60 * 1000L
        /** Run records read per refresh to settle rows the lists left in question (see [verifyRunStatuses]). */
        private const val MAX_VERIFIED_RUNS = 12
        /** A row without a run-level status is only worth a record read while its activity is this recent. */
        private const val VERIFY_RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
        /** A row that reads finished but was active this recently may still be going; its record settles it. */
        private const val VERIFY_JUST_ACTIVE_WINDOW_MS = 5 * 60 * 1000L
        private const val AGENT_ID_CONFLICT = "agent_id_conflict"
        const val FILES_NEED_EXTENDED = "Attaching files needs Extended mode; turn it on in Settings, or take the files off."
        const val FILES_NEED_CLOUD = "Files can go on a new chat that runs on Cursor's cloud only. Start it on Cloud, or attach them in a follow-up once it is running."
        /** Reads of the chat by id after a launch's reply was lost, and the wait between them: about a quarter of a minute in all. */
        const val LOST_REPLY_PROBES = 5
        const val LOST_REPLY_PROBE_DELAY_MS = 3_000L
        /** Same cap as `POST /v1/agents` `name` and the official rename field. */
        private const val MAX_NAME_LENGTH = 100
        /** Why [rename] refuses with Extended mode off; the screens hide the action, this is for whatever still asks. */
        const val RENAME_NEEDS_EXTENDED_MODE = "Renaming a chat uses Cursor's account service, which is only used in Extended mode."
    }
}
