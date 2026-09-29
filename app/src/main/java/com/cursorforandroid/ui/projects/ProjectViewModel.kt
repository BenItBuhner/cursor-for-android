package com.cursorforandroid.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.WorkerLaunch
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.AgentListState
import com.cursorforandroid.data.repo.ContextState
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.MediaKind
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A file of the Project's shared context, opened in the sheet: its text, or — for a document that is not text, a PDF
 * or an archive — the copy of its bytes kept on the device ([keptPath]), which the sheet hands to another app.
 */
data class OpenContextFile(val entry: ContextEntry, val text: String, val keptPath: String? = null, val format: FileFormat? = null)

/** What the Project's panel section draws: the view, and its primaries' rows built from it (see [ProjectViewModel.panel]). */
internal data class ProjectPanel(val view: ProjectViewState, val rows: ProjectRows)

/**
 * One Project's view: what the repository derives from the agent list and the account, and the coordinator's
 * actions, each answered with one line for the snackbar. The view attaches while the screen is visible
 * ([resume] / [pause]) so the memberships are read and polled only then.
 */
class ProjectViewModel(private val graph: AppGraph, val projectId: String) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val openFile = MutableStateFlow<OpenContextFile?>(null)

    private val rows = ProjectRowsBuilder()

    /**
     * The Project's view and its primaries' rows, both built off the main thread and each time from the list, the device's
     * own state and the minute; the first frame has them as they stand.
     */
    internal val panel: StateFlow<ProjectPanel> = combine(graph.projects.view(projectId), graph.prefs.localAgentState.onStart { emit(LocalAgentState()) }, minutes()) { view, local, _ ->
        ProjectPanel(view, rows.build(view, local, AppClock.now()))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.projects.viewNow(projectId).let { ProjectPanel(it, rows.build(it, LocalAgentState(), AppClock.now())) })

    /** The Project's view as the panel last had it. */
    val state: ProjectViewState get() = panel.value.view

    /** Rows built rather than kept from the build before, for the scale benchmark. */
    internal val rowsBuilt: Long get() = rows.rowsBuilt

    /** Extended mode is on: what the header and the sections say about the actions they offer. */
    val extendedMode: StateFlow<Boolean> = graph.extendedMode.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val toastMessage: StateFlow<String?> = message.asStateFlow()
    val isBusy: StateFlow<Boolean> = busy.asStateFlow()
    val contextFile: StateFlow<OpenContextFile?> = openFile.asStateFlow()

    /** Chats the coordinator could adopt: the account's own chats, running or not, archived ones aside. Read while the Adopt sheet is open. */
    fun adoptable(list: AgentListState): List<Agent> =
        list.agents.filter { it.scope == AgentScope.PRIMARY && !it.isArchived && it.id != projectId }.sortedByDescending { it.listedAtMillis }

    /** Other Projects a primary could be moved under. Read while the Move sheet is open. */
    fun otherProjects(list: AgentListState): List<Agent> =
        list.agents.filter { it.isProjectRoot && it.id != projectId && !it.isArchived }.sortedBy { it.name.lowercase() }

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
        val root = state.root
        graph.projects.createWorker(projectId, WorkerLaunch(prompt = prompt.trim(), name = name?.trim()?.takeIf { it.isNotEmpty() }, repoUrl = repoUrl ?: root?.repoUrl, baseBranch = baseBranch ?: root?.startingRef))
            .map { "Started a new primary." }
    }

    fun adopt(agentId: String) = act { graph.projects.adopt(projectId, agentId).map { "Adopted into ${state.name}." } }

    fun release(agentId: String) = act { graph.projects.release(projectId, agentId).map { "Released from the Project." } }

    fun reparent(agentId: String, newParentId: String) = act { graph.projects.reparent(agentId, newParentId).map { "Moved." } }

    fun updateAppearance(appearance: ProjectAppearance) = act { graph.projects.updateAppearance(projectId, appearance).map { "Look saved." } }

    fun steer(agentId: String, text: String) = act { graph.projects.steer(agentId, text).map { it.message } }

    fun pauseWorker(agentId: String) = act { graph.projects.pause(agentId).map { "Paused; resume when you're ready." } }

    fun resumeWorker(agentId: String) = act { graph.projects.resume(agentId).map { "Resumed." } }

    /** The public cancel: works in either mode, ends the turn for good. */
    fun stop(agentId: String) = act { graph.conversations.cancelActiveRun(agentId).map { "Stopped." } }

    fun loadContext(relativePath: String = "") = viewModelScope.launch { graph.projects.loadContext(projectId, relativePath) }

    /** Up one directory of the context, or back to the root. */
    fun contextUp() {
        val current = (state.context as? ContextState.Loaded)?.context?.relativePath.orEmpty()
        loadContext(current.trimEnd('/').substringBeforeLast('/', ""))
    }

    fun openContextFile(entry: ContextEntry) = viewModelScope.launch {
        busy.value = true
        val format = FileFormat.ofName(entry.name)
        if (format != null && format.kind != MediaKind.Text) {
            // Bytes, never the text read (`ReadAgentStoreFile` carries a string): the presigned read the store's own mount makes.
            val ref = MediaRef.Store(projectId, entry.relativePath.trimStart('/'))
            runCatching { graph.media.keep(graph.storeFiles.readBytes(ref), entry.name) }
                .onSuccess { openFile.value = OpenContextFile(entry, "", keptPath = it.removePrefix("file://"), format = format) }
                .onFailure { if (it is CancellationException) throw it else message.value = MediaLoader.problemOf(it).title }
        } else {
            graph.projects.readContextFile(projectId, entry)
                .onSuccess { openFile.value = OpenContextFile(entry, it) }
                .onFailure { message.value = it.userMessage() }
        }
        busy.value = false
    }

    fun closeContextFile() { openFile.value = null }

    fun markRead(agent: Agent) = viewModelScope.launch { graph.prefs.markRead(agent.id, agent.listedAtMillis) }

    /** Runs one action, holds the screen's controls meanwhile, and shows the outcome or the refusal. */
    private fun act(block: suspend () -> Result<String>) = viewModelScope.launch {
        busy.value = true
        val result = block()
        busy.value = false
        message.value = result.getOrElse { it.userMessage() }
    }

    /** The clock the rows format their ages against. */
    fun now(): Long = AppClock.now()

    /** A beat a minute, so a snooze that has lifted shows without waiting for the list to move. */
    private fun minutes(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(AgentsViewModel.CLOCK_TICK_MS)
        }
    }

    class Factory(private val graph: AppGraph, private val projectId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProjectViewModel(graph, projectId) as T
    }
}
