package com.cursorforandroid.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.RefreshDepth
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentListUiState(
    /** The sidebar's groups: filtered by [prefs] and [query], sorted per [prefs], pinned first. */
    val sections: List<AgentSection> = emptyList(),
    /**
     * New Chat recent cards: the same Chats filters as the sidebar, never the sidebar search query, every row once and
     * newest first — so both surfaces always agree on which chats the filters let through.
     */
    val recentRows: List<AgentRow> = emptyList(),
    val allAgents: List<Agent> = emptyList(),
    val repoSlugs: List<String> = emptyList(),
    val prefs: ListPreferences = ListPreferences(),
    val local: LocalAgentState = LocalAgentState(),
    val query: String = "",
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val unreadCount: Int = 0,
    val runningCount: Int = 0,
    /**
     * The clock the state was computed against, refreshed every minute while the list is on screen. Rows format their
     * relative ages ("now", "4m") and the date groups ("Today", "Yesterday") against this, so a row does not go on
     * saying "now" for as long as nothing else about it happens to change.
     */
    val nowMillis: Long = AppClock.now(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class AgentsViewModel(
    private val graph: AppGraph,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val clockTickMs: Long = CLOCK_TICK_MS,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /**
     * What this device knows about the agents beyond the API, ready for the organizer: pins, read markers and launches
     * from the preferences, with the pull request states the account last gave folded in.
     */
    private val local: Flow<LocalAgentState> = combine(graph.prefs.localAgentState, graph.pullRequests.states) { local, states ->
        local.copy(pullRequests = states)
    }

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
    private val snoozeAlarm: Flow<Long> = local.flatMapLatest { state ->
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
        local,
        query,
        clock,
    ) { list, prefs, local, q, now ->
        val sections = AgentListOrganizer.organize(list.agents, prefs, local, q, nowMillis = now)
        // The sidebar search narrows the sidebar only; while it is in use the recents are organized without it.
        val recentRows = if (q.isBlank()) AgentListOrganizer.recentRows(sections) else AgentListOrganizer.recentRows(list.agents, prefs, local, nowMillis = now)
        val rows = list.agents.map { AgentListOrganizer.toRow(it, local, now) }
        AgentListUiState(
            sections = sections,
            recentRows = recentRows,
            allAgents = list.agents,
            repoSlugs = list.agents.mapNotNull { it.repoSlug }.distinct().sortedBy { it.lowercase() },
            prefs = prefs,
            local = local,
            query = q,
            isRefreshing = list.isRefreshing,
            hasLoaded = list.hasLoaded,
            error = list.error,
            unreadCount = rows.count { it.isUnread },
            runningCount = rows.count { it.indicator == AgentIndicator.Running },
            nowMillis = now,
        )
    }
        // Grouping, filtering and sorting a few hundred rows is cheap, but not free on every keystroke of the search
        // field or every streamed patch; it runs off the main thread and only the result reaches the UI.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentListUiState())

    init {
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
            // A pull request the list has not seen before — restored from disk, paged in, or opened by a run that just
            // finished — is looked up as soon as it appears; states already known are left to their own schedule.
            graph.agents.state.map { s -> s.agents.mapNotNullTo(LinkedHashSet()) { it.prUrl } }.distinctUntilChanged().collect { urls ->
                graph.pullRequests.refresh(urls)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        graph.agents.refresh()
        refreshPullRequests(eager = true)
    }

    /**
     * For returning to the foreground: agents that changed while the app was away, without a spinner — and the pull
     * requests that could have moved meanwhile, re-read the way a pull does, since coming back is the moment a
     * merge made elsewhere is looked for.
     */
    fun refreshIfStale() = viewModelScope.launch {
        graph.agents.refreshIfStale(STALE_AFTER_MS)
        refreshPullRequests(eager = true)
    }

    private suspend fun refreshPullRequests(eager: Boolean = false) =
        graph.pullRequests.refresh(graph.agents.state.value.agents.mapNotNull { it.prUrl }, eager)

    /**
     * Keeps the list current while it is on screen, without anyone pulling to refresh: a run started on the web, a
     * follow-up sent from the desktop, a finish nobody was streaming. The newest page of each list every
     * [pollIntervalMs] (that is where new chats and fresh activity appear, and it is two small requests), the whole
     * list every [FULL_POLL_EVERY] ticks so a follow-up on an old chat further down is picked up too — all silent, and
     * skipped when something else (the live-notification monitor, a pull) refreshed moments ago. Each tick also gives
     * the pull request states their turn: the account's list, read after each fetch, says where they stand at once,
     * and the pass re-reads from the SCM whichever are due on their own schedule (cheap when none is). Runs until the
     * returned job is cancelled; the caller ties it to the list being visible.
     */
    fun pollWhileVisible(): Job = viewModelScope.launch {
        var tick = 0
        while (true) {
            delay(pollIntervalMs)
            tick++
            val depth = if (tick % FULL_POLL_EVERY == 0) RefreshDepth.Full else RefreshDepth.Quick
            graph.agents.refreshIfStale(pollIntervalMs / 2, depth)
            refreshPullRequests()
        }
    }

    fun setQuery(value: String) { query.value = value }

    /** Applies at once; the account hears about it now or at the next sync, so a failure needs no attention here. */
    fun togglePinned(agentId: String) = viewModelScope.launch { graph.pins.toggle(agentId) }

    fun markRead(agent: Agent) = viewModelScope.launch { graph.prefs.markRead(agent.id, agent.updatedAtMillis) }

    /** Marks every loaded conversation read at its current `updatedAt`, the same stamp opening a chat would write. */
    fun markAllRead() = viewModelScope.launch {
        val agents = graph.agents.state.value.agents
        if (agents.isEmpty()) return@launch
        graph.prefs.markAllRead(agents.associate { it.id to it.updatedAtMillis })
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

    fun archive(agentId: String) = viewModelScope.launch { graph.agents.archive(agentId) }
    fun unarchive(agentId: String) = viewModelScope.launch { graph.agents.unarchive(agentId) }
    fun rename(agentId: String, name: String) = viewModelScope.launch { graph.agents.rename(agentId, name) }

    /** Silences notifications for the chat until [untilMillis]; `Long.MAX_VALUE` until they unsnooze. */
    fun snooze(agentId: String, untilMillis: Long) = viewModelScope.launch { graph.prefs.snooze(agentId, untilMillis) }
    fun unsnooze(agentId: String) = viewModelScope.launch { graph.prefs.unsnooze(agentId) }

    fun filterLabel(kind: FilterKind): String = uiState.value.prefs.summaryFor(kind)

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

    private companion object {
        const val STALE_AFTER_MS = 30_000L
        const val POLL_INTERVAL_MS = 30_000L
        const val FULL_POLL_EVERY = 5
        const val CLOCK_TICK_MS = 60_000L
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentsViewModel(graph) as T
    }
}
