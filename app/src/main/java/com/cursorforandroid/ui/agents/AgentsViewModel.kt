package com.cursorforandroid.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.data.repo.AgentListState
import com.cursorforandroid.data.repo.RefreshOutcome
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.EnvironmentFilter
import com.cursorforandroid.domain.FilterKind
import com.cursorforandroid.domain.GitFilter
import com.cursorforandroid.domain.GroupBy
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.PendingWork
import com.cursorforandroid.domain.KnownRoot
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.SortOrder
import com.cursorforandroid.domain.SourceFilter
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The sidebar's tail: the one row past the last chat, and never more than one. A spinner there is work in flight —
 * the pages and passes the list registered ([com.cursorforandroid.domain.PendingWork]), named in the diagnostics —
 * and every state ends within the bounds of that work: in rows, in nothing (the end of the list), or in the server's
 * words with Retry. Nothing spins for a flag; nothing is asked for again behind a spinner once it has failed.
 */
sealed interface SidebarTail {
    /** Nothing past the last chat: the list is whole, or a refresh shows its own indicator at the top. */
    data object None : SidebarTail

    /** "Loading more…": [work] is what is in flight, as the diagnostics name it. */
    data class Loading(val work: List<String>) : SidebarTail

    /** The server has older chats and nothing fetches them: a line that asks for the next page. */
    data object More : SidebarTail

    /** The last page could not be fetched: the server's words, and Retry. */
    data class Failed(val message: String) : SidebarTail
}

/**
 * The tail from the list's state and the work in flight: nothing while the refresh indicator is up (one indicator
 * at a time); a spinner while a page is being fetched or the work the user asked for is still in flight — the
 * items named for the diagnostics; the server's words with Retry for a page that failed; the ask for the next page
 * while the server has one; else nothing. The one rule the sidebar's end is drawn by, here so a test of the list
 * under a phone's weather reads its tail the way the sidebar would.
 */
internal fun sidebarTail(list: AgentListState, work: PendingWork.State): SidebarTail = when {
    !list.hasLoaded || list.isRefreshing -> SidebarTail.None
    list.isLoadingMore || work.shown -> SidebarTail.Loading(work.items.map { it.name })
    list.loadMoreError != null -> SidebarTail.Failed(list.loadMoreError)
    list.hasMore -> SidebarTail.More
    else -> SidebarTail.None
}

data class AgentListUiState(
    /** The sidebar's groups: filtered by [prefs] and [query], sorted per [prefs], pinned first. */
    val sections: List<AgentSection> = emptyList(),
    /**
     * New Chat recent cards: the same Chats filters as the sidebar, never the sidebar search query, every row once and
     * newest first — so both surfaces always agree on which chats the filters let through.
     */
    val recentRows: List<AgentRow> = emptyList(),
    /**
     * The New Chat pane's Project shortcuts: the sidebar's Projects group, never narrowed by the sidebar search, without
     * the "Project (loading)" stand-ins, which have nothing to open yet.
     */
    val projectRows: List<AgentRow> = emptyList(),
    val allAgents: List<Agent> = emptyList(),
    val repoSlugs: List<String> = emptyList(),
    val prefs: ListPreferences = ListPreferences(),
    val local: LocalAgentState = LocalAgentState(),
    val query: String = "",
    val isRefreshing: Boolean = false,
    /** The one row past the last chat: what the list is doing at its end, or nothing (see [SidebarTail]). */
    val tail: SidebarTail = SidebarTail.None,
    val hasLoaded: Boolean = false,
    /** The server lists agents older than the ones loaded; the sidebar's end asks for them (see [AgentsViewModel.loadMore]). */
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val unreadCount: Int = 0,
    val runningCount: Int = 0,
    /**
     * The sidebar groups folded closed, by [AgentSection.key], as the device remembers them across restarts (see
     * [AgentsViewModel.setSectionCollapsed]); a closed group's header still carries its count and its unread dot.
     */
    val collapsedSections: Set<String> = emptySet(),
    /**
     * Settings › Appearance › "Shorten long Projects list": a long Projects or Pinned group lists its first five rows
     * until "Show N more" is tapped (see [SidebarShortList]). On unless turned off.
     */
    val shortenLongGroups: Boolean = true,
    /**
     * The clock the state was computed against, refreshed every minute while the list is on screen. Rows format their
     * relative ages ("now", "4m") and the date groups ("Today", "Yesterday") against this, so a row does not go on
     * saying "now" for as long as nothing else about it happens to change.
     */
    val nowMillis: Long = AppClock.now(),
)

/**
 * What this device knows about the agents beyond the API, ready for the organizer: pins, read markers and launches
 * from the preferences, with the pull request states the account last gave folded in; alongside, the last row action
 * the server refused, which the list shows where a failed refresh would show its own error, and the Projects the
 * list names but the server would not give, whose stand-ins say so.
 */
private class DeviceState(
    val local: LocalAgentState,
    val actionError: String?,
    val unavailableProjects: Set<String>,
    /** The root registry (see [KnownRoot]): every Project the account has, whether or not its row is loaded. */
    val knownRoots: List<KnownRoot> = emptyList(),
    /** The account's member count per Project, for the rows' counts. */
    val memberCounts: Map<String, Int> = emptyMap(),
    /** The sidebar groups the reader folded closed (see [AgentListUiState.collapsedSections]). */
    val collapsedSections: Set<String> = emptySet(),
    /** Whether long Projects and Pinned groups are cut to five rows (see [AgentListUiState.shortenLongGroups]). */
    val shortenLongGroups: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class AgentsViewModel(
    private val graph: AppGraph,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val clockTickMs: Long = CLOCK_TICK_MS,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** An archive / unarchive / delete the server refused, until the next one is attempted. */
    private val actionError = MutableStateFlow<String?>(null)

    /** Consecutive polls whose fetch could not reach the server; a success or a refresh by hand clears it. */
    @Volatile private var pollFailures = 0

    private val localState: Flow<LocalAgentState> = combine(graph.prefs.localAgentState, graph.pullRequests.states) { local, states ->
        local.copy(pullRequests = states)
    }

    /** The three smallest device facts, bundled so the device combine stays within its arity. */
    private val countsAndFolds: Flow<Triple<Map<String, Int>, Set<String>, Boolean>> =
        combine(graph.projects.memberCounts, graph.prefs.collapsedSidebarSections, graph.prefs.shortenSidebarLists) { counts, folds, shorten -> Triple(counts, folds, shorten) }

    private val device: Flow<DeviceState> = combine(localState, actionError, graph.projects.unavailableParents, graph.agents.knownRoots, countsAndFolds) { local, failed, unavailable, roots, (counts, folds, shorten) ->
        DeviceState(local = local, actionError = failed, unavailableProjects = unavailable.keys, knownRoots = roots, memberCounts = counts, collapsedSections = folds, shortenLongGroups = shorten)
    }

    /**
     * The list's work in flight, and whether the user asked for it (see [com.cursorforandroid.domain.PendingWork]):
     * what the tail's spinner stands for. The account layer's own passes — a membership read, a discovery scan —
     * are not the list's tail and show nothing here; they had a row of their own that outlived every request
     * behind it (0.3.39–0.3.59), which is what this replaces.
     */
    private val pending = graph.pendingWork.state

    /** The rows on screen, as the sidebar reports them (see [rowsVisible]): what the pull request badges are read for. */
    private val visibleIds = MutableStateFlow<List<String>>(emptyList())

    /** Ticks once a minute so everything relative to "now" is recomputed even while the data stands still. */
    private val minuteClock: Flow<Long> = flow {
        while (true) {
            emit(AppClock.now())
            delay(clockTickMs)
        }
    }

    /**
     * Fires the moment a timed snooze lifts, so a 5-minute mute does not sit around until the next minute tick.
     * Completes while nothing is snoozed on a timer (Forever never wakes on its own).
     */
    private val snoozeAlarm: Flow<Long> = localState.flatMapLatest { state ->
        flow {
            while (true) {
                val now = AppClock.now()
                val next = state.nextSnoozeExpiry(now) ?: return@flow
                delay((next - now).coerceAtLeast(0L))
                emit(AppClock.now())
            }
        }
    }

    private val clock: Flow<Long> = merge(minuteClock, snoozeAlarm)

    // Expiry is [LocalAgentState.isSnoozed] against this clock. Do not persist it from a collector here:
    // writing DataStore while the list flow is on Default races Robolectric's Compose slot table.

    val uiState: StateFlow<AgentListUiState> = combine(
        graph.agents.state,
        graph.prefs.listPreferences,
        device,
        query,
        combine(clock, pending) { now, work -> now to work },
    ) { list, prefs, device, q, (now, work) ->
        val local = device.local
        val sections = AgentListOrganizer.organize(list.agents, prefs, local, q, nowMillis = now, unavailableProjects = device.unavailableProjects, knownRoots = device.knownRoots, memberCounts = device.memberCounts)
        // The sidebar search narrows the sidebar only; while it is in use the recents are organized without it.
        val recentRows = if (q.isBlank()) AgentListOrganizer.recentRows(sections) else AgentListOrganizer.recentRows(list.agents, prefs, local, nowMillis = now)
        val unsearched = if (q.isBlank()) sections else AgentListOrganizer.organize(list.agents, prefs, local, nowMillis = now, unavailableProjects = device.unavailableProjects, knownRoots = device.knownRoots, memberCounts = device.memberCounts)
        val projectRows = AgentListOrganizer.projectRows(unsearched)
        val rows = list.agents.map { AgentListOrganizer.toRow(it, local, now) }
        AgentListUiState(
            sections = sections,
            recentRows = recentRows,
            projectRows = projectRows,
            allAgents = list.agents,
            repoSlugs = list.agents.mapNotNull { it.repoSlug }.distinct().sortedBy { it.lowercase() },
            prefs = prefs,
            local = local,
            query = q,
            isRefreshing = list.isRefreshing,
            tail = sidebarTail(list, work),
            hasLoaded = list.hasLoaded,
            hasMore = list.hasMore,
            isLoadingMore = list.isLoadingMore,
            // A refused row action is the newer news, and the one the user is waiting on.
            error = device.actionError ?: list.error,
            unreadCount = rows.count { it.isUnread },
            runningCount = rows.count { it.indicator == AgentIndicator.Running },
            collapsedSections = device.collapsedSections,
            shortenLongGroups = device.shortenLongGroups,
            nowMillis = now,
        )
    }
        // Grouping, filtering and sorting a few hundred rows is cheap, but not free on every keystroke of the search
        // field or every streamed patch; it runs off the main thread and only the result reaches the UI.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentListUiState())

    init {
        // A Project the list names but lacks is fetched by id so its workers can sit under it rather than under a
        // stand-in; the list surface being up is when that matters.
        graph.projects.watchList()
        viewModelScope.launch {
            // Disk first, so the list is on screen before the network is consulted; the refresh is then silent
            // when there was something to show and visible (pull-to-refresh indicator) on a truly cold start.
            graph.agents.restoreFromCache()
            graph.pullRequests.restoreFromCache()
            graph.agents.refresh(silent = graph.agents.state.value.hasLoaded)
            refreshPullRequests()
        }
        viewModelScope.launch {
            graph.agents.state.collect { s ->
                graph.catalog.seedRepositories(s.agents.mapNotNull { it.repoUrl })
            }
        }
        viewModelScope.launch {
            // The badges are read for the rows on screen, as they come on screen — not for every row the list holds:
            // a pull request the reader has not scrolled to costs nothing until they do. A settled scroll position
            // (a short pause) is when the visible rows are looked up; states already known keep their own schedule.
            visibleIds.debounce(VISIBLE_DEBOUNCE_MS).map { ids -> visibleUrls(ids) }.distinctUntilChanged().collect { urls ->
                if (urls.isNotEmpty()) graph.pullRequests.refresh(urls)
            }
        }
        viewModelScope.launch {
            // Every completed list fetch, whoever asked for it — a pull, a poll, coming back to the foreground, the
            // widget — is also when the pull requests are re-read, so an open one that has since been merged does not
            // stay open for as long as the app is left running with the same chats in the list. What that costs is
            // decided by their own re-read intervals, not by how often the list is fetched.
            graph.agents.refreshCompleted.filter { it > 0L }.collect { refreshPullRequests() }
        }
    }

    fun refresh() = viewModelScope.launch {
        // Asking again by hand is also how a user gets out of a backed-off cadence after an outage. The pull re-reads
        // the window on screen; the account's discovery scan behind it stops at the page older than every Project
        // known. [deepRefresh] is the one that reads everything.
        pollFailures = 0
        graph.agents.refresh(depth = RefreshDepth.Full)
        refreshPullRequests(eager = true)
    }

    /**
     * The refresh that reads everything: the window and, behind it, the whole account list for the root registry —
     * for a Project that never showed up, older than every Project the registry knows. Asked for by hand only.
     */
    fun deepRefresh() = viewModelScope.launch {
        pollFailures = 0
        graph.agents.refresh(depth = RefreshDepth.Deep)
        refreshPullRequests(eager = true)
    }

    /** The sidebar's rows on screen, by id, as they scroll into view (see [visibleIds]). */
    fun rowsVisible(ids: List<String>) {
        visibleIds.value = ids
    }

    /** The pull requests of the rows on screen — of the newest rows, while the sidebar has not said what is on screen. */
    private fun visibleUrls(ids: List<String>): List<String> {
        val agents = graph.agents.state.value.agents
        val rows = if (ids.isEmpty()) agents.take(VISIBLE_FALLBACK_ROWS) else agents.filter { it.id in ids.toHashSet() }
        return rows.mapNotNull { it.prUrl }.distinct()
    }

    /**
     * For returning to the foreground: agents that changed while the app was away, without a spinner — and the pull
     * requests that could have moved meanwhile, re-read the way a pull does, since coming back is the moment a
     * merge made elsewhere is looked for.
     */
    fun refreshIfStale() = viewModelScope.launch {
        if (graph.agents.refreshIfStale(STALE_AFTER_MS) == RefreshOutcome.Refreshed) pollFailures = 0
        refreshPullRequests(eager = true)
    }

    /**
     * The reader reached the end of the list: the next page of agents is fetched, and the account's list is paged
     * alongside it (Extended mode) so the rows it adds are placed among the Projects and named as the account names
     * them. The pull requests the page brings are looked up like any row's. Ignored while a page is already on its way.
     */
    fun loadMore() = viewModelScope.launch {
        val list = graph.agents.state.value
        if (!list.hasMore || list.isLoadingMore || list.isRefreshing) return@launch
        // The repository reads the account's page ahead of the public one (see AgentRepository.accountPage) and
        // marks the ask at once, so a second ask while the page is on its way is a no-op rather than another page.
        if (graph.agents.loadMore() != RefreshOutcome.Refreshed) return@launch
        refreshPullRequests()
    }

    /** The tail's Retry: the page that failed, asked for again — the one way a failed page is asked for again. */
    fun retryLoadMore() = viewModelScope.launch {
        if (graph.agents.state.value.isRefreshing) return@launch
        if (graph.agents.loadMore() != RefreshOutcome.Refreshed) return@launch
        refreshPullRequests()
    }

    private suspend fun refreshPullRequests(eager: Boolean = false) =
        graph.pullRequests.refresh(visibleUrls(visibleIds.value), eager)

    /**
     * Keeps the list current while it is on screen, without anyone pulling to refresh: a run started on the web, a
     * follow-up sent from the desktop, a finish nobody was streaming. The newest page of each list every
     * [pollIntervalMs] (that is where new chats and fresh activity appear, and it is two small requests), a deeper
     * pass every [FULL_POLL_EVERY] ticks so a follow-up on an old chat further down is picked up too — all silent,
     * and skipped when something else (the live-notification monitor, a pull) refreshed moments ago. Each tick also
     * gives the pull request states their turn: the account's list, read after each fetch, says where they stand at
     * once, and the pass re-reads from the SCM whichever are due on their own schedule (cheap when none is). While
     * the server cannot be reached the interval backs off (see [pollDelayMs]) instead of knocking every half minute
     * for as long as the screen is up. Runs until the returned job is cancelled; the caller ties it to a list surface
     * actually being on screen.
     */
    fun pollWhileVisible(): Job = viewModelScope.launch {
        var tick = 0
        while (true) {
            delay(pollDelayMs())
            tick++
            val depth = if (tick % FULL_POLL_EVERY == 0) RefreshDepth.Full else RefreshDepth.Quick
            when (graph.agents.refreshIfStale(pollIntervalMs / 2, depth)) {
                RefreshOutcome.Refreshed -> pollFailures = 0
                RefreshOutcome.Failed -> pollFailures++
                RefreshOutcome.Skipped -> Unit
            }
            refreshPullRequests()
        }
    }

    /**
     * The wait before the next poll: the plain interval while the server answers, doubled per consecutive failure up
     * to [MAX_POLL_BACKOFF_FACTOR] times it, plus a little jitter so an outage does not leave every client that
     * weathered it knocking in step afterwards.
     */
    private fun pollDelayMs(): Long {
        val failures = pollFailures
        if (failures <= 0) return pollIntervalMs
        val backed = pollIntervalMs * (1L shl minOf(failures, MAX_POLL_BACKOFF_SHIFT))
        val capped = minOf(backed, pollIntervalMs * MAX_POLL_BACKOFF_FACTOR)
        return capped + Random.nextLong(capped / 4 + 1)
    }

    fun setQuery(value: String) { query.value = value }

    /** Applies at once; the account hears about it now or at the next sync, so a failure needs no attention here. */
    fun togglePinned(agentId: String) = viewModelScope.launch { graph.pins.toggle(agentId) }

    fun markRead(agent: Agent) = viewModelScope.launch { graph.prefs.markRead(agent.id, agent.listedAtMillis) }

    /** Folds a sidebar group closed or open, remembered on the device across restarts (see [AgentListUiState.collapsedSections]). */
    fun setSectionCollapsed(sectionKey: String, collapsed: Boolean) = viewModelScope.launch { graph.prefs.setSidebarSectionCollapsed(sectionKey, collapsed) }

    /** Marks every loaded conversation read at its current `updatedAt`, the same stamp opening a chat would write. */
    fun markAllRead() = viewModelScope.launch {
        val agents = graph.agents.state.value.agents
        if (agents.isEmpty()) return@launch
        graph.prefs.markAllRead(agents.associate { it.id to it.listedAtMillis })
    }

    /** Against the stored preferences, in one transaction, so quick successive changes compose (see the store). */
    fun updatePrefs(transform: (ListPreferences) -> ListPreferences) = viewModelScope.launch {
        graph.prefs.updateListPreferences(transform)
    }

    fun setGroupBy(groupBy: GroupBy) = updatePrefs { it.copy(groupBy = groupBy) }
    fun setSortOrder(order: SortOrder) = updatePrefs { it.copy(sortOrder = order) }
    fun setRepos(repos: Set<String>?) = updatePrefs { it.copy(repos = repos) }
    fun toggleStatus(status: StatusFilter) = updatePrefs { it.copy(statuses = it.statuses.toggle(status)) }
    fun toggleGit(git: GitFilter) = updatePrefs { it.copy(git = it.git.toggle(git)) }
    fun toggleSource(source: SourceFilter) = updatePrefs { it.copy(sources = it.sources.toggle(source)) }
    fun toggleEnvironment(environment: EnvironmentFilter) = updatePrefs { it.copy(environments = it.environments.toggle(environment)) }
    fun setShowWorkspace(v: Boolean) = updatePrefs { it.copy(showWorkspace = v) }
    fun setShowBranchStatus(v: Boolean) = updatePrefs { it.copy(showBranchStatus = v) }
    fun setShowRuntime(v: Boolean) = updatePrefs { it.copy(showRuntime = v) }
    fun resetPrefs() = updatePrefs { ListPreferences() }

    fun archive(agentId: String) = viewModelScope.launch { report(graph.agents.archive(agentId)) }
    fun unarchive(agentId: String) = viewModelScope.launch { report(graph.agents.unarchive(agentId)) }
    fun rename(agentId: String, name: String) = viewModelScope.launch { graph.agents.rename(agentId, name) }

    /**
     * Deletes the chat on the server first. The transcript and the retained trace are the only copy of a run whose
     * server-side event log has expired, so they are dropped only once the server has accepted the delete; a request
     * that was refused or never went out leaves the chat, its transcript and its trace where they are, and says so.
     */
    fun delete(agentId: String) = viewModelScope.launch {
        if (report(graph.agents.delete(agentId))) { graph.conversations.forget(agentId); graph.presenters.forget(agentId) }
    }

    /** Silences notifications for the chat until [untilMillis]; `Long.MAX_VALUE` until they unsnooze. */
    fun snooze(agentId: String, untilMillis: Long) = viewModelScope.launch { graph.prefs.snooze(agentId, untilMillis) }
    fun unsnooze(agentId: String) = viewModelScope.launch { graph.prefs.unsnooze(agentId) }

    /** True when the action went through; a failure is shown in the list until the next action is attempted. */
    private fun report(result: Result<Unit>): Boolean {
        actionError.value = result.exceptionOrNull()?.userMessage()
        return result.isSuccess
    }

    fun filterLabel(kind: FilterKind): String = uiState.value.prefs.summaryFor(kind)

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

    companion object {
        /** A scroll's pause before the rows on screen have their pull requests looked up. */
        const val VISIBLE_DEBOUNCE_MS = 400L
        /** Rows whose badges are read while the sidebar has not yet said what is on screen: about a screenful. */
        const val VISIBLE_FALLBACK_ROWS = 24
        private const val STALE_AFTER_MS = 30_000L
        private const val POLL_INTERVAL_MS = 30_000L
        private const val FULL_POLL_EVERY = 5
        private const val CLOCK_TICK_MS = 60_000L
        /** Doublings of the polling interval a run of failures can reach. */
        private const val MAX_POLL_BACKOFF_SHIFT = 5
        /** The most the interval can grow to, in multiples of itself: half a minute becomes eight. */
        private const val MAX_POLL_BACKOFF_FACTOR = 16L
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentsViewModel(graph) as T
    }
}
