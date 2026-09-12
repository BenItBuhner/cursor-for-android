package com.cursorforandroid.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.WorkerLaunch
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.ContextState
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A file of the Project's shared context, opened in the viewer. */
data class OpenContextFile(val entry: ContextEntry, val text: String)

/**
 * One Project's view: what the repository derives from the agent list and the account, and the coordinator's
 * actions, each answered with one line for the snackbar. The view attaches while the screen is visible
 * ([resume] / [pause]) so the memberships are read and polled only then.
 */
class ProjectViewModel(private val graph: AppGraph, val projectId: String) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val openFile = MutableStateFlow<OpenContextFile?>(null)

    val state: StateFlow<ProjectViewState> = graph.projects.view(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectViewState(projectId, root = graph.agents.agent(projectId)))

    /** Extended mode is on: what the header and the sections say about the actions they offer. */
    val extendedMode: StateFlow<Boolean> = graph.extendedMode.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val toastMessage: StateFlow<String?> = message.asStateFlow()
    val isBusy: StateFlow<Boolean> = busy.asStateFlow()
    val contextFile: StateFlow<OpenContextFile?> = openFile.asStateFlow()

    /** Chats the coordinator could adopt: the account's own chats, running or not, archived ones aside. */
    val adoptable: StateFlow<List<Agent>> = graph.agents.state
        .map { s -> s.agents.filter { it.scope == AgentScope.PRIMARY && !it.isArchived && it.id != projectId }.sortedByDescending { it.updatedAtMillis } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Other Projects a primary could be moved under. */
    val otherProjects: StateFlow<List<Agent>> = graph.agents.state
        .map { s -> s.agents.filter { it.isProjectRoot && it.id != projectId && !it.isArchived }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isDemo: Boolean get() = graph.session.isDemo

    fun resume() = graph.projects.attach(projectId)

    fun pause() = graph.projects.detach(projectId)

    /** By hand: the list (for the primaries' status) and the account's memberships, whatever the poll's schedule says. */
    fun refresh() = viewModelScope.launch {
        graph.agents.refreshIfStale(0L)
        graph.projects.refreshView(projectId)
    }

    fun clearToast() { message.value = null }

    fun showMessage(text: String) { message.value = text }

    fun createWorker(prompt: String, name: String?, repoUrl: String?, baseBranch: String?) = act {
        val root = state.value.root
        graph.projects.createWorker(projectId, WorkerLaunch(prompt = prompt.trim(), name = name?.trim()?.takeIf { it.isNotEmpty() }, repoUrl = repoUrl ?: root?.repoUrl, baseBranch = baseBranch ?: root?.startingRef))
            .map { "Started a new primary." }
    }

    fun adopt(agentId: String) = act { graph.projects.adopt(projectId, agentId).map { "Adopted into ${state.value.name}." } }

    fun release(agentId: String) = act { graph.projects.release(projectId, agentId).map { "Released from the Project." } }

    fun reparent(agentId: String, newParentId: String) = act { graph.projects.reparent(agentId, newParentId).map { "Moved." } }

    fun updateAppearance(appearance: ProjectAppearance) = act { graph.projects.updateAppearance(projectId, appearance).map { "Look saved." } }

    fun startSideChat(name: String?) = act { graph.projects.startSideChat(projectId, name?.trim()?.takeIf { it.isNotEmpty() }).map { "Side chat started." } }

    fun steer(agentId: String, text: String) = act { graph.projects.steer(agentId, text).map { it.message } }

    fun pauseWorker(agentId: String) = act { graph.projects.pause(agentId).map { "Paused; resume when you're ready." } }

    fun resumeWorker(agentId: String) = act { graph.projects.resume(agentId).map { "Resumed." } }

    /** The public cancel: works in either mode, ends the turn for good. */
    fun stop(agentId: String) = act { graph.conversations.cancelActiveRun(agentId).map { "Stopped." } }

    fun loadContext(relativePath: String = "") = viewModelScope.launch { graph.projects.loadContext(projectId, relativePath) }

    /** Up one directory of the context, or back to the root. */
    fun contextUp() {
        val current = (state.value.context as? ContextState.Loaded)?.context?.relativePath.orEmpty()
        loadContext(current.trimEnd('/').substringBeforeLast('/', ""))
    }

    fun openContextFile(entry: ContextEntry) = viewModelScope.launch {
        busy.value = true
        graph.projects.readContextFile(projectId, entry)
            .onSuccess { openFile.value = OpenContextFile(entry, it) }
            .onFailure { message.value = it.userMessage() }
        busy.value = false
    }

    fun closeContextFile() { openFile.value = null }

    fun markRead(agent: Agent) = viewModelScope.launch { graph.prefs.markRead(agent.id, agent.updatedAtMillis) }

    /** Runs one action, holds the screen's controls meanwhile, and shows the outcome or the refusal. */
    private fun act(block: suspend () -> Result<String>) = viewModelScope.launch {
        busy.value = true
        val result = block()
        busy.value = false
        message.value = result.getOrElse { it.userMessage() }
    }

    /** The clock the rows format their ages against. */
    fun now(): Long = AppClock.now()

    class Factory(private val graph: AppGraph, private val projectId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProjectViewModel(graph, projectId) as T
    }
}
