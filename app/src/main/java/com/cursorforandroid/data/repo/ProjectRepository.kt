package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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
 */
class ProjectRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val api: ProjectLineageApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = AppClock::now,
    private val maxRootsPerSync: Int = MAX_ROOTS_PER_SYNC,
    private val maxMaterialized: Int = MAX_MATERIALIZED,
    /** Whether the account's Project reads may be made (Extended mode). Everything, for tests of the sync itself. */
    private val capabilities: suspend () -> Capabilities = { Capabilities.EXTENDED },
) {
    /** One membership pass at a time; a second request while one runs waits for it rather than doubling the calls. */
    private val syncMutex = Mutex()
    private val materializeMutex = Mutex()

    /** Parents fetched by id this session, with when; a failed one is asked for again after [RETRY_AFTER_MS]. */
    private val materialized = ConcurrentHashMap<String, Long>()

    private var watcher: Job? = null

    init {
        scope.launch { session.backend.drop(1).collect { reset() } }
    }

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
                val lineage = try {
                    api.lineage(rootId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    continue
                }
                if (agents.token() != token) return
                agents.applyLineage(rootId, lineage.members, authoritative = true, startedIn = token)
            }
        }
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

    /** On sign-out or a backend switch: nothing fetched for the previous account counts for the next. */
    fun reset() {
        materialized.clear()
    }

    private companion object {
        const val MAX_ROOTS_PER_SYNC = 20
        const val MAX_MATERIALIZED = 8
        const val RETRY_AFTER_MS = 30 * 60_000L
    }
}
