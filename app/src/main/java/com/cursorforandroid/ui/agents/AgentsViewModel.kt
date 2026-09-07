package com.cursorforandroid.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentListUiState(
    val sections: List<AgentSection> = emptyList(),
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
)

class AgentsViewModel(private val graph: AppGraph) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<AgentListUiState> = combine(
        graph.agents.state,
        graph.prefs.listPreferences,
        graph.prefs.localAgentState,
        query,
    ) { list, prefs, local, q ->
        val sections = AgentListOrganizer.organize(list.agents, prefs, local, q)
        val rows = list.agents.map { AgentListOrganizer.toRow(it, local) }
        AgentListUiState(
            sections = sections,
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
            graph.agents.refresh(silent = graph.agents.state.value.hasLoaded)
        }
        viewModelScope.launch {
            graph.agents.state.collect { s ->
                graph.catalog.seedRepositories(s.agents.mapNotNull { it.repoUrl })
            }
        }
    }

    fun refresh() = viewModelScope.launch { graph.agents.refresh() }

    /** For returning to the foreground: agents that changed while the app was away, without a spinner. */
    fun refreshIfStale() = viewModelScope.launch { graph.agents.refreshIfStale(STALE_AFTER_MS) }

    fun setQuery(value: String) { query.value = value }

    fun togglePinned(agentId: String) = viewModelScope.launch { graph.prefs.togglePinned(agentId) }

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
