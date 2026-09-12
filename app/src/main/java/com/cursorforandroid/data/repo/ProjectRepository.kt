package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.ProjectActionsApi
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.WorkerLaunch
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.ProjectContext
import com.cursorforandroid.domain.ProjectWorker
import com.cursorforandroid.domain.SideChatAvailability
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A Project's shared context (Agent Store) as its view shows it. */
sealed interface ContextState {
    /** Not asked yet. */
    data object Idle : ContextState

    data object Loading : ContextState

    data class Loaded(val context: ProjectContext) : ContextState

    /** The account lists no store for this Project (a Project that has written no context yet, or the demo). */
    data object NoStore : ContextState

    /** Extended mode is off, or the account service would not answer: [reason] is the named state the view shows. */
    data class Unavailable(val reason: String) : ContextState
}

/**
 * One Project as its view shows it: the coordinator, its primaries with how each came to belong, its side chats and
 * subagents, its shared context, and what the account has said about side chats. Derived from the agent list
 * (which carries the rows and their live status) and what the account's membership reads added.
 */
data class ProjectViewState(
    val projectId: String,
    val root: Agent? = null,
    val workers: List<ProjectWorker> = emptyList(),
    val sideChats: List<Agent> = emptyList(),
    val subagents: List<Agent> = emptyList(),
    /** The account's memberships are being read. */
    val isSyncing: Boolean = false,
    /** True once the account has been asked at least once this session (or it is not going to be: default mode, demo). */
    val hasSynced: Boolean = false,
    /** Why the account's word is missing, when it is: the named state under the primaries. */
    val lineageNotice: String? = null,
    val sideChatAvailability: SideChatAvailability = SideChatAvailability.UNKNOWN,
    val context: ContextState = ContextState.Idle,
    /** Whether the Project's actions may be offered at all (Extended mode, or the demo's in-memory stand-ins). */
    val actionsAvailable: Boolean = false,
) {
    val name: String get() = root?.name ?: "Project"
    val isEmpty: Boolean get() = workers.isEmpty() && sideChats.isEmpty() && subagents.isEmpty()
}

/**
 * Cursor Projects as the agent list needs them: who belongs to whom, so that a Project's workers, side chats and
 * subagents sit inside the Project and never among the account's own chats (see [com.cursorforandroid.domain.AgentScope]).
 *
 * Two sources feed it. In Extended mode the account's own word: after every account list read, each Project's
 * memberships are read the way the Agents Window reads them (`ListWorkersForManager`, `ListBackgroundComposerChildren`)
 * and folded onto the rows ([syncLineage]). In either mode, a parent the list names but does not hold — a Project
 * beyond the listing window, a chat archived elsewhere — is fetched by id through the public API so that it can head
 * its tree ([materializeParents]); until it lands, the organizer keeps its children under a stand-in row. The
 * coordinator's transcript, the one signal default mode has, reaches the rows through the conversation repository.
 *
 * It is also the Project view's model ([view], [attach]) and the coordinator's hands ([createWorker], [adopt],
 * [release], [reparent], [updateAppearance], [startSideChat], [steer], [pause], [resume], the context reads): every
 * one of them is an account-service call, so with Extended mode off each answers with the named refusal
 * [NEEDS_EXTENDED_MODE] and makes no call; the demo answers from memory where it can and says so where it cannot.
 */
class ProjectRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val api: ProjectLineageApi,
    private val actions: ProjectActionsApi? = null,
    private val store: AgentStoreApi? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = AppClock::now,
    private val maxRootsPerSync: Int = MAX_ROOTS_PER_SYNC,
    private val maxMaterialized: Int = MAX_MATERIALIZED,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    /** Whether the account's Project reads and writes may be made (Extended mode). Everything, for tests of the sync itself. */
    private val capabilities: suspend () -> Capabilities = { Capabilities.EXTENDED },
) {
    /** What the account's reads add to one Project beyond the agent list. */
    private data class Extras(
        val memberships: Map<String, WorkerMembership> = emptyMap(),
        val isSyncing: Boolean = false,
        val hasSynced: Boolean = false,
        val lineageNotice: String? = null,
        val sideChatAvailability: SideChatAvailability = SideChatAvailability.UNKNOWN,
        val context: ContextState = ContextState.Idle,
        val actionsAvailable: Boolean = false,
    )

    /** One membership pass at a time; a second request while one runs waits for it rather than doubling the calls. */
    private val syncMutex = Mutex()
    private val materializeMutex = Mutex()

    /** Parents fetched by id this session, with when; a failed one is asked for again after [RETRY_AFTER_MS]. */
    private val materialized = ConcurrentHashMap<String, Long>()

    private val extras = ConcurrentHashMap<String, MutableStateFlow<Extras>>()
    private val attached = ConcurrentHashMap<String, Int>()
    private val pollers = ConcurrentHashMap<String, Job>()

    private var watcher: Job? = null

    init {
        scope.launch { session.backend.drop(1).collect { reset() } }
    }

    private fun extrasOf(projectId: String): MutableStateFlow<Extras> = extras.getOrPut(projectId) { MutableStateFlow(Extras()) }

    /**
     * Starts fetching the parents the list names but lacks, whenever the list changes. Idempotent; called once the
     * graph is up, so that constructing it stays side-effect free.
     */
    fun watchList() {
        if (watcher != null) return
        watcher = scope.launch {
            agents.state
                .filter { it.hasLoaded && !it.isFromCache }
                .map { AgentListOrganizer.missingParentIds(it.agents) }
                .distinctUntilChanged()
                .collect { missing -> if (missing.isNotEmpty()) materializeParents(missing) }
        }
    }

    /** [syncLineage] on this repository's own scope, for a caller that must not wait on it (the pin sync's round). */
    fun scheduleLineageSync(rootIds: Collection<String>) {
        if (rootIds.isEmpty()) return
        scope.launch { syncLineage(rootIds) }
    }

    /**
     * Reads the memberships of each Project in [rootIds] from the account and folds them onto the rows: every worker
     * and child becomes the root's, whatever the list said before. Nothing happens without Extended mode, or for the
     * demo; a root whose reads fail is skipped and asked again at the next pass. Bounded per pass to keep a list
     * with many Projects from turning one refresh into a flood of calls.
     */
    suspend fun syncLineage(rootIds: Collection<String>) {
        if (rootIds.isEmpty() || session.isDemo || !capabilities().projects) return
        syncMutex.withLock {
            val token = agents.token()
            for (rootId in rootIds.distinct().take(maxRootsPerSync)) {
                if (!readLineage(rootId, token)) return
            }
        }
    }

    /** One root's memberships, folded onto the rows and kept for its view; false when the list has been reset meanwhile. */
    private suspend fun readLineage(rootId: String, token: Int): Boolean {
        val flow = extrasOf(rootId)
        flow.update { it.copy(isSyncing = true) }
        val lineage = try {
            api.lineage(rootId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            flow.update { it.copy(isSyncing = false, hasSynced = true, lineageNotice = describeLineageFailure(t)) }
            return true
        }
        if (agents.token() != token) return false
        agents.applyLineage(rootId, lineage.members, authoritative = true, startedIn = token)
        flow.update { it.copy(memberships = lineage.workers.associateBy { m -> m.workerId }, isSyncing = false, hasSynced = true, lineageNotice = null) }
        // Members the list does not hold (beyond its window, or created moments ago) are fetched by id so the view can list them.
        val missing = lineage.members.keys.filter { agents.agent(it) == null }.take(maxMaterialized)
        for (id in missing) {
            if (agents.token() != token) return false
            agents.loadDetail(id)
        }
        return true
    }

    /**
     * Fetches the rows of [parentIds] through the public API so that each can take its place at the head of its
     * tree; a parent that workers name as their manager is a Project's coordinator, and is marked as one. A few per
     * pass, each at most once per [RETRY_AFTER_MS]; the demo's list names nothing it does not hold.
     */
    suspend fun materializeParents(parentIds: Collection<String>) {
        if (parentIds.isEmpty() || session.isDemo) return
        materializeMutex.withLock {
            val token = agents.token()
            val due = parentIds.filter { id -> materialized[id]?.let { now() - it >= RETRY_AFTER_MS } ?: true }.take(maxMaterialized)
            for (id in due) {
                if (agents.token() != token) return
                materialized[id] = now()
                if (agents.agent(id) != null) continue
                if (agents.loadDetail(id).isFailure) continue
                val managesWorkers = agents.state.value.agents.any { it.parent?.id == id && it.parent.kind == AgentParentKind.PROJECT_WORKER }
                if (managesWorkers) agents.applyLineage(id, emptyMap(), authoritative = true, startedIn = token)
            }
        }
    }

    // ---- the Project view -------------------------------------------------------------------------------------------

    /** The Project as its view shows it, kept current from the agent list and the account's reads. */
    fun view(projectId: String): Flow<ProjectViewState> = combine(agents.state, extrasOf(projectId)) { list, extra ->
        val root = list.agents.firstOrNull { it.id == projectId }
        val members = list.agents.filter { it.parent?.id == projectId }
        // Workers the account named but the list has not shown yet stand in with what the membership says.
        val listedWorkerIds = members.mapTo(HashSet()) { it.id }
        ProjectViewState(
            projectId = projectId,
            root = root,
            workers = members.filter { it.parent?.kind == AgentParentKind.PROJECT_WORKER }.map { ProjectWorker(it, extra.memberships[it.id]) } +
                extra.memberships.values.filter { it.workerId !in listedWorkerIds }.map { ProjectWorker(placeholderWorker(it), it) },
            sideChats = members.filter { it.parent?.kind == AgentParentKind.SIDE_CHAT },
            subagents = members.filter { it.parent?.kind == AgentParentKind.SUBAGENT },
            isSyncing = extra.isSyncing,
            hasSynced = extra.hasSynced,
            lineageNotice = extra.lineageNotice,
            sideChatAvailability = extra.sideChatAvailability,
            context = extra.context,
            actionsAvailable = extra.actionsAvailable,
        )
    }

    /** A worker the account named but the list has not shown yet: its id, until the row arrives. */
    private fun placeholderWorker(membership: WorkerMembership): Agent = Agent(
        id = membership.workerId,
        name = "Worker ${membership.workerId.takeLast(6)}",
        lifecycle = AgentLifecycle.UNKNOWN,
        runStatus = null,
        envType = EnvType.UNKNOWN,
        envName = null,
        url = "https://cursor.com/agents/${membership.workerId}",
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        latestRunId = null,
        repoUrl = null,
        startingRef = null,
        parent = AgentParent(membership.managerId, AgentParentKind.PROJECT_WORKER),
        knownScope = AgentScope.PROJECT_CHILD,
    )

    /**
     * A Project's view is on screen: its memberships are read now and every [pollIntervalMs] while it is, the list
     * refreshed alongside so the primaries' status stays live (`StreamBackgroundComposerUpdates` would replace the
     * poll once a Connect streaming client exists). Balanced by [detach].
     */
    fun attach(projectId: String) {
        val count = attached.merge(projectId, 1, Int::plus) ?: 1
        if (count > 1) return
        pollers[projectId]?.cancel()
        pollers[projectId] = scope.launch {
            refreshView(projectId)
            while (isActive) {
                delay(pollIntervalMs)
                agents.refreshIfStale(pollIntervalMs / 2, RefreshDepth.Quick)
                refreshView(projectId)
            }
        }
    }

    fun detach(projectId: String) {
        val count = attached.merge(projectId, -1, Int::plus) ?: 0
        if (count > 0) return
        attached.remove(projectId)
        pollers.remove(projectId)?.cancel()
    }

    /** One read of what the Project's view shows beyond the list: the account's memberships, when they may be read. */
    suspend fun refreshView(projectId: String) {
        val flow = extrasOf(projectId)
        val allowed = capabilities()
        val available = session.isDemo || allowed.projects
        flow.update { it.copy(actionsAvailable = available) }
        if (session.isDemo) {
            flow.update { it.copy(hasSynced = true, lineageNotice = null, context = if (it.context == ContextState.Idle) ContextState.NoStore else it.context) }
            return
        }
        if (!allowed.projects) {
            flow.update { it.copy(hasSynced = true, lineageNotice = NEEDS_EXTENDED_MODE, context = ContextState.Unavailable(NEEDS_EXTENDED_MODE)) }
            return
        }
        syncMutex.withLock { readLineage(projectId, agents.token()) }
        if (agents.agent(projectId) == null) materializeParents(listOf(projectId))
    }

    // ---- the coordinator's actions -----------------------------------------------------------------------------------

    /** Spawns a new primary under [projectId] (`CreateProjectWorker`); the worker's row joins the list at once. */
    suspend fun createWorker(projectId: String, launch: WorkerLaunch): Result<String> = action(needs = { it.projects }) { api ->
        val created = api.createWorker(projectId, launch)
        agents.applyLineage(projectId, mapOf(created.id to AgentParentKind.PROJECT_WORKER), authoritative = true)
        agents.loadDetail(created.id)
        refreshView(projectId)
        created.id
    }

    /** Makes an existing chat one of [projectId]'s primaries (`SetWorkerManager`, adopted). */
    suspend fun adopt(projectId: String, agentId: String): Result<Unit> = action(needs = { it.projects }) { api ->
        api.setWorkerManager(agentId, projectId)
        agents.applyLineage(projectId, mapOf(agentId to AgentParentKind.PROJECT_WORKER), authoritative = true)
        refreshView(projectId)
    }

    /** Releases a primary from [projectId] (`ClearWorkerManager`): a chat of its own again. */
    suspend fun release(projectId: String, agentId: String): Result<Unit> = action(needs = { it.projects }) { api ->
        api.clearWorkerManager(agentId)
        agents.patch(agentId) { it.copy(parent = null, knownScope = AgentScope.PRIMARY) }
        extrasOf(projectId).update { it.copy(memberships = it.memberships - agentId) }
    }

    /** Moves [agentId] under [newParentId] as its cloud subagent (`ReparentBackgroundComposer`). */
    suspend fun reparent(agentId: String, newParentId: String): Result<Unit> = action(needs = { it.projects }) { api ->
        api.reparent(agentId, newParentId)
        agents.applyLineage(newParentId, mapOf(agentId to AgentParentKind.SUBAGENT), authoritative = true)
    }

    /** Sets the Project's icon and colour (`UpdateProjectAppearance`); the row shows the account's answer. */
    suspend fun updateAppearance(projectId: String, appearance: ProjectAppearance): Result<Unit> = action(needs = { it.projects }) { api ->
        val recorded = api.updateAppearance(projectId, appearance) ?: appearance
        agents.patch(projectId) { it.copy(projectAppearance = recorded, isProject = true) }
    }

    /**
     * Starts a side chat off [projectId] (`StartSideChatBackgroundComposer`). The attempt is the probe the help page
     * leaves us: a refusal that reads as "not offered" moves the view to [SideChatAvailability.COMING_TO_CURSOR], a
     * side chat that comes back to [SideChatAvailability.AVAILABLE].
     */
    suspend fun startSideChat(projectId: String, name: String?): Result<String> {
        val flow = extrasOf(projectId)
        val result = action(needs = { it.projects }) { api ->
            val created = api.startSideChat(projectId, name)
            agents.applyLineage(projectId, mapOf(created.id to AgentParentKind.SIDE_CHAT), authoritative = true)
            agents.loadDetail(created.id)
            created.id
        }
        result.onSuccess { flow.update { it.copy(sideChatAvailability = SideChatAvailability.AVAILABLE) } }
        result.onFailure { t -> if (t is ConnectRpcException && t.isNotOffered) flow.update { it.copy(sideChatAvailability = SideChatAvailability.COMING_TO_CURSOR) } }
        return result
    }

    /** Steers the running turn of [agentId] (`InjectBackgroundComposerContext`); the outcome is the message to show. */
    suspend fun steer(agentId: String, text: String): Result<SteerOutcome> = action(needs = { it.steering }) { api ->
        api.steer(agentId, text.trim(), agents.agent(agentId)?.latestRunId?.takeUnless { it.startsWith("local-") })
    }

    suspend fun pause(agentId: String): Result<Unit> = action(needs = { it.steering }) { api ->
        api.pause(agentId, agents.agent(agentId)?.latestRunId?.takeUnless { it.startsWith("local-") })
    }

    suspend fun resume(agentId: String): Result<Unit> = action(needs = { it.steering }) { api -> api.resume(agentId) }

    // ---- shared context ---------------------------------------------------------------------------------------------

    /** Reads the Project's shared context, root directory first; [relativePath] to open a directory of it. */
    suspend fun loadContext(projectId: String, relativePath: String = "") {
        val flow = extrasOf(projectId)
        if (session.isDemo) {
            flow.update { it.copy(context = ContextState.NoStore) }
            return
        }
        if (!capabilities().projects) {
            flow.update { it.copy(context = ContextState.Unavailable(NEEDS_EXTENDED_MODE)) }
            return
        }
        val reads = store ?: run {
            flow.update { it.copy(context = ContextState.Unavailable(NOT_WIRED)) }
            return
        }
        // The store, once found, is kept across the folders opened in it; only the listing is read again.
        val knownStore = (flow.value.context as? ContextState.Loaded)?.context?.storeId
        flow.update { it.copy(context = ContextState.Loading) }
        try {
            val storeId = knownStore ?: reads.storeFor(projectId)
            if (storeId == null) {
                flow.update { it.copy(context = ContextState.NoStore) }
                return
            }
            val entries = reads.entries(storeId, relativePath)
            flow.update { it.copy(context = ContextState.Loaded(ProjectContext(storeId, entries, relativePath))) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            flow.update { it.copy(context = ContextState.Unavailable(describeFailure(t))) }
        }
    }

    /** The text of one file of the Project's shared context. */
    suspend fun readContextFile(projectId: String, entry: ContextEntry): Result<String> {
        val storeId = (extrasOf(projectId).value.context as? ContextState.Loaded)?.context?.storeId
            ?: return Result.failure(IllegalStateException("The Project's context has not been opened."))
        val reads = store ?: return Result.failure(IllegalStateException(NOT_WIRED))
        if (!capabilities().projects) return Result.failure(IllegalStateException(NEEDS_EXTENDED_MODE))
        return runCatching { reads.readFile(storeId, entry.relativePath) }.recoverCatching { t ->
            if (t is CancellationException) throw t
            throw IOException(describeFailure(t), t)
        }
    }

    /** On sign-out or a backend switch: nothing fetched for the previous account counts for the next. */
    fun reset() {
        materialized.clear()
        extras.clear()
        pollers.values.forEach { it.cancel() }
        pollers.clear()
        attached.clear()
    }

    /**
     * One account-service write, gated: with the setting off it is refused with [NEEDS_EXTENDED_MODE] and nothing is
     * called; the demo has no account to write to and says so; a failure is turned into the words the view shows.
     */
    private suspend fun <T> action(needs: (Capabilities) -> Boolean, block: suspend (ProjectActionsApi) -> T): Result<T> {
        if (session.isDemo) return Result.failure(IllegalStateException(NOT_IN_DEMO))
        if (!needs(capabilities())) return Result.failure(IllegalStateException(NEEDS_EXTENDED_MODE))
        val api = actions ?: return Result.failure(IllegalStateException(NOT_WIRED))
        return try {
            Result.success(block(api))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(if (t is ConnectRpcException) t else IOException(describeFailure(t), t))
        }
    }

    private fun describeLineageFailure(t: Throwable): String = when (t) {
        is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) NEEDS_EXTENDED_MODE else t.message ?: NO_SESSION
        is ConnectRpcException -> if (t.isNotOffered) ENDPOINT_CHANGED else "Cursor refused the Project's memberships (${t.message})."
        is IOException -> t.userMessage()
        else -> t.message ?: "Couldn't read the Project's memberships."
    }

    private fun describeFailure(t: Throwable): String = when (t) {
        is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) NEEDS_EXTENDED_MODE else t.message ?: NO_SESSION
        is ConnectRpcException -> if (t.isNotOffered) ENDPOINT_CHANGED else "Cursor refused (${t.message})."
        is IOException -> t.userMessage()
        else -> t.message ?: "Something went wrong."
    }

    /** A refusal that says the method is not offered (gone, gated or not for this account) rather than that the request was wrong. */
    private val ConnectRpcException.isNotOffered: Boolean
        get() = httpCode == 404 || code == "unimplemented" || code == "failed_precondition" || code == "permission_denied"

    companion object {
        private const val MAX_ROOTS_PER_SYNC = 20
        private const val MAX_MATERIALIZED = 8
        private const val RETRY_AFTER_MS = 30 * 60_000L
        private const val POLL_INTERVAL_MS = 20_000L

        /** Why a Project action or read is refused with Extended mode off; the view names it instead of the action. */
        const val NEEDS_EXTENDED_MODE = "Needs Extended mode: spawning, adopting, steering, side chats and Context use Cursor's account service."
        const val NOT_IN_DEMO = "Not available in the demo."
        const val ENDPOINT_CHANGED = "Cursor changed a private endpoint; this part of Projects is unavailable until the app is updated."
        private const val NOT_WIRED = "Projects are not wired to the account service in this build."
        private const val NO_SESSION = "Cursor couldn't start a session for this key."
    }
}
