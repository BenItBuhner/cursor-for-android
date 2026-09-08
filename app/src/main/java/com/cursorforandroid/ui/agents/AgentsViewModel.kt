package com.cursorforandroid.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentListUiState(
    val sections: List<AgentSection> = emptyList(),
    /** New Chat recent cards: same Chats filters as the sidebar, never the sidebar search query. */
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
)

class AgentsViewModel(private val graph: AppGraph) : ViewModel() {

    private val query = MutableStateFlow("")

    private val pullRequests = combine(graph.pullRequests.statuses, graph.pullRequests.hasToken) { statuses, hasToken -> statuses to hasToken }

    val uiState: StateFlow<AgentListUiState> = combine(
        graph.agents.state,
        graph.prefs.listPreferences,
        graph.prefs.localAgentState,
        pullRequests,
        query,
    ) { list, prefs, device, (statuses, hasToken), q ->
        val local = device.copy(pullRequests = statuses.mapNotNull { (url, status) -> status.state?.let { url to it } }.toMap())
        val sections = AgentListOrganizer.organize(list.agents, prefs, local, q)
        val recentRows = if (q.isBlank()) {
            sections.flatMap { it.rows }.distinctBy { it.agent.id }.sortedByDescending { it.agent.updatedAtMillis }
        } else {
            AgentListOrganizer.recentRows(list.agents, prefs, local)
        }
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
            error = list.error,
            unreadCount = rows.count { it.isUnread },
            runningCount = rows.count { it.indicator == AgentIndicator.Running },
            pullRequestsUnreadable = list.agents.any { a -> a.prUrl?.let { statuses[it] }?.let { it.state == null } == true },
            hasGitHubToken = hasToken,
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

    /** For returning to the foreground: agents that changed while the app was away, without a spinner. */
    fun refreshIfStale() = viewModelScope.launch {
        graph.agents.refreshIfStale(STALE_AFTER_MS)
        refreshPullRequests()
    }

    private suspend fun refreshPullRequests(eager: Boolean = false) =
        graph.pullRequests.refresh(graph.agents.state.value.agents.mapNotNull { it.prUrl }, eager)

    fun setQuery(value: String) { query.value = value }

    /** Applies at once; the account hears about it now or at the next sync, so a failure needs no attention here. */
    fun togglePinned(agentId: String) = viewModelScope.launch { graph.pins.toggle(agentId) }

    fun markRead(agent: Agent) = viewModelScope.launch { graph.prefs.markRead(agent.id, agent.updatedAtMillis) }

    fun updatePrefs(transform: (ListPreferences) -> ListPreferences) = viewModelScope.launch {
        graph.prefs.setListPreferences(transform(uiState.value.prefs))
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

    fun archive(agentId: String) = viewModelScope.launch { graph.agents.archive(agentId) }
    fun unarchive(agentId: String) = viewModelScope.launch { graph.agents.unarchive(agentId) }
    fun delete(agentId: String) = viewModelScope.launch {
        graph.conversations.forget(agentId)
        graph.agents.delete(agentId)
    }

    fun filterLabel(kind: FilterKind): String = uiState.value.prefs.summaryFor(kind)

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

    private companion object {
        const val STALE_AFTER_MS = 30_000L
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentsViewModel(graph) as T
    }
}
