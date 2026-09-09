package com.cursorforandroid.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.data.repo.RefreshOutcome
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    /** True while GitHub refuses to say where some of the listed pull requests stand — private repositories, read without a token. */
    val pullRequestsUnreadable: Boolean = false,
    val hasGitHubToken: Boolean = false,
    /**
     * The clock the state was computed against, refreshed every minute while the list is on screen. Rows format their
     * relative ages ("now", "4m") and the date groups ("Today", "Yesterday") against this, so a row does not go on
     * saying "now" for as long as nothing else about it happens to change.
     */
    val nowMillis: Long = AppClock.now(),
)

/**
 * What this device knows about the agents beyond the API, ready for the organizer: pins, read markers and launches
 * from the preferences, with the pull request states GitHub last gave folded in; alongside, the pull requests GitHub
 * refused to answer for and whether a GitHub token is set, for the Git filter page's hint, and the last row action
 * the server refused, which the list shows where a failed refresh would show its own error.
 */
private class DeviceState(
    val local: LocalAgentState,
    val unreadablePullRequests: Set<String>,
    val hasGitHubToken: Boolean,
    val actionError: String?,
)

class AgentsViewModel(
    private val graph: AppGraph,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val clockTickMs: Long = CLOCK_TICK_MS,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** Ticks once a minute so everything relative to "now" is recomputed even while the data stands still. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(AppClock.now())
            delay(clockTickMs)
        }
    }

    /** An archive / unarchive / delete the server refused, until the next one is attempted. */
    private val actionError = MutableStateFlow<String?>(null)

    /** Consecutive polls whose fetch could not reach the server; a success or a refresh by hand clears it. */
    @Volatile private var pollFailures = 0

    private val device: Flow<DeviceState> = combine(
        graph.prefs.localAgentState,
        graph.pullRequests.statuses,
        graph.pullRequests.hasToken,
        actionError,
    ) { local, statuses, hasToken, failed ->
        DeviceState(
            local = local.copy(pullRequests = statuses.mapNotNull { (url, status) -> status.state?.let { url to it } }.toMap()),
            unreadablePullRequests = statuses.filterValues { it.state == null }.keys,
            hasGitHubToken = hasToken,
            actionError = failed,
        )
    }

    val uiState: StateFlow<AgentListUiState> = combine(
        graph.agents.state,
        graph.prefs.listPreferences,
        device,
        query,
        clock,
    ) { list, prefs, device, q, now ->
        val local = device.local
        val sections = AgentListOrganizer.organize(list.agents, prefs, local, q, nowMillis = now)
        // The sidebar search narrows the sidebar only; while it is in use the recents are organized without it.
        val recentRows = if (q.isBlank()) AgentListOrganizer.recentRows(sections) else AgentListOrganizer.recentRows(list.agents, prefs, local, nowMillis = now)
        val rows = list.agents.map { AgentListOrganizer.toRow(it, local) }
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
            // A refused row action is the newer news, and the one the user is waiting on.
            error = device.actionError ?: list.error,
            unreadCount = rows.count { it.isUnread },
            runningCount = rows.count { it.indicator == AgentIndicator.Running },
            pullRequestsUnreadable = list.agents.any { it.prUrl in device.unreadablePullRequests },
            hasGitHubToken = device.hasGitHubToken,
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
        viewModelScope.launch {
            // Every completed list fetch, whoever asked for it — a pull, a poll, coming back to the foreground, the
            // widget — is also when the pull requests are re-read, so an open one that has since been merged does not
            // stay open for as long as the app is left running with the same chats in the list. What that costs is
            // decided by their own re-read intervals, not by how often the list is fetched.
            graph.agents.refreshCompleted.filter { it > 0L }.collect { refreshPullRequests() }
        }
    }

    fun refresh() = viewModelScope.launch {
        // Asking again by hand is also how a user gets out of a backed-off cadence after an outage, and the one
        // refresh that always pages to the end of the list rather than to the end of its window.
        pollFailures = 0
        graph.agents.refresh(depth = RefreshDepth.Deep)
        refreshPullRequests(eager = true)
    }

    /** For returning to the foreground: agents that changed while the app was away, without a spinner. */
    fun refreshIfStale() = viewModelScope.launch {
        if (graph.agents.refreshIfStale(STALE_AFTER_MS) == RefreshOutcome.Refreshed) pollFailures = 0
        refreshPullRequests()
    }

    private suspend fun refreshPullRequests(eager: Boolean = false) =
        graph.pullRequests.refresh(graph.agents.state.value.agents.mapNotNull { it.prUrl }, eager)

    /**
     * Keeps the list current while it is on screen, without anyone pulling to refresh: a run started on the web, a
     * follow-up sent from the desktop, a finish nobody was streaming. The newest page of each list every
     * [pollIntervalMs] (that is where new chats and fresh activity appear, and it is two small requests), a deeper
     * pass every [FULL_POLL_EVERY] ticks so a follow-up on an old chat further down is picked up too — all silent,
     * and skipped when something else (the live-notification monitor, a pull) refreshed moments ago. While the
     * server cannot be reached the interval backs off (see [pollDelayMs]) instead of knocking every half minute for
     * as long as the screen is up. Runs until the returned job is cancelled; the caller ties it to a list surface
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

    fun markRead(agent: Agent) = viewModelScope.launch { graph.prefs.markRead(agent.id, agent.updatedAtMillis) }

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
    fun setShowWorkspace(v: Boolean) = updatePrefs { it.copy(showWorkspace = v) }
    fun setShowBranchStatus(v: Boolean) = updatePrefs { it.copy(showBranchStatus = v) }
    fun setShowRuntime(v: Boolean) = updatePrefs { it.copy(showRuntime = v) }
    fun resetPrefs() = updatePrefs { ListPreferences() }

    fun archive(agentId: String) = viewModelScope.launch { report(graph.agents.archive(agentId)) }
    fun unarchive(agentId: String) = viewModelScope.launch { report(graph.agents.unarchive(agentId)) }

    /**
     * Deletes the chat on the server first. The transcript and the retained trace are the only copy of a run whose
     * server-side event log has expired, so they are dropped only once the server has accepted the delete; a request
     * that was refused or never went out leaves the chat, its transcript and its trace where they are, and says so.
     */
    fun delete(agentId: String) = viewModelScope.launch {
        if (report(graph.agents.delete(agentId))) graph.conversations.forget(agentId)
    }

    /** True when the action went through; a failure is shown in the list until the next action is attempted. */
    private fun report(result: Result<Unit>): Boolean {
        actionError.value = result.exceptionOrNull()?.userMessage()
        return result.isSuccess
    }

    fun filterLabel(kind: FilterKind): String = uiState.value.prefs.summaryFor(kind)

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

    private companion object {
        const val STALE_AFTER_MS = 30_000L
        const val POLL_INTERVAL_MS = 30_000L
        const val FULL_POLL_EVERY = 5
        const val CLOCK_TICK_MS = 60_000L
        /** Doublings of the polling interval a run of failures can reach. */
        const val MAX_POLL_BACKOFF_SHIFT = 5
        /** The most the interval can grow to, in multiples of itself: half a minute becomes eight. */
        const val MAX_POLL_BACKOFF_FACTOR = 16L
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentsViewModel(graph) as T
    }
}
