package com.cursorforandroid.ui.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.DesktopOpen
import com.cursorforandroid.data.repo.PullRequestLoad
import com.cursorforandroid.data.repo.VmRead
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.AgentUsage
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.DesktopSession
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.MachineStatus
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.WorkspaceTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One remote read the panel shows: not asked yet, in flight, answered, refused, or impossible from here. */
sealed interface RemoteLoad<out T> {
    data object Idle : RemoteLoad<Nothing>
    data object Loading : RemoteLoad<Nothing>
    data class Loaded<T>(val value: T) : RemoteLoad<T>
    /** The read failed for now; [retryable] unless the answer would be the same again (a private repository, say). */
    data class Failed(val message: String, val retryable: Boolean = true) : RemoteLoad<Nothing>
    /** Nothing this app can read from here (Origin without a token, an SCM it has no client for): offer [url] in the browser instead. */
    data class Unsupported(val reason: String, val url: String? = null) : RemoteLoad<Nothing>

    val valueOrNull: T? get() = (this as? Loaded<T>)?.value
    val isLoading: Boolean get() = this is Loading
}

/** Where the Files › Repository tab stands: the directory on screen, and the file opened from it, if any. */
data class RepoBrowserState(
    val repoUrl: String? = null,
    /** The branch or commit the tree is read at; null is the host's default branch. */
    val ref: String? = null,
    /** Which host serves it, or null when the repository cannot be browsed from here. */
    val host: ScmHost? = null,
    val path: String = "",
    val listing: RemoteLoad<RepoContents.Directory> = RemoteLoad.Idle,
    /** A file being viewed: from the repository, or the copy a tool call carried ([FileView.Transcript]). */
    val file: FileView? = null,
) {
    val canBrowse: Boolean get() = host != null && repoUrl != null
    val isAtRoot: Boolean get() = path.isEmpty()
}

/** The file the viewer shows: fetched from the repository, or what a tool call carried, with the read that produced it. */
sealed interface FileView {
    val path: String
    data class Loading(override val path: String) : FileView
    data class Failed(override val path: String, val message: String) : FileView
    data class Repository(val file: RepoFile) : FileView { override val path: String get() = file.path }
    data class Transcript(val content: ToolPayload.FileContent) : FileView { override val path: String get() = content.path }
    data class Changes(val change: TranscriptContent.FileChange) : FileView { override val path: String get() = change.path }
    /** A file of the agent's live workspace (`ReadBinaryFile`), typed like a repository file so the same viewer shows it. */
    data class Workspace(val file: RepoFile) : FileView { override val path: String get() = file.path }
    /** One file of the branch's diff against its base (`GetBackgroundComposerDiffDetails`): its patch, or the file as it now stands. */
    data class BranchDiff(val file: AgentDiffFile) : FileView { override val path: String get() = file.path }
}

/** Where the Files › Workspace tab stands: the directory on screen, and the tree it walks (read once, whole). */
data class WorkspaceBrowserState(
    val path: String = "",
    val tree: RemoteLoad<WorkspaceTree> = RemoteLoad.Idle,
) {
    val isAtRoot: Boolean get() = path.isEmpty()
}

/** The agent's desktop, as the Remote section and the WebView behind it see it. */
sealed interface DesktopState {
    data object Idle : DesktopState

    /** `GetMachine` and the probe are out. */
    data object Opening : DesktopState

    /** A websockify URL answered; the WebView is up on it. */
    data class Open(val session: DesktopSession) : DesktopState

    data class Failed(val failure: DesktopFailure) : DesktopState
}

/** Everything the panel's sections read. Derived from the repositories the conversation already keeps, plus the reads the panel asks for. */
data class PanelState(
    val agentId: String,
    val agent: Agent? = null,
    val runStatus: RunStatus? = null,
    val isStreaming: Boolean = false,
    val content: TranscriptContent = TranscriptContent.EMPTY,
    /** The images attached to prompts from this device, in transcript order. */
    val promptImages: List<MessageAttachment> = emptyList(),
    val capabilities: Capabilities = Capabilities.DOCUMENTED,
    val isDemo: Boolean = false,
    val pullRequest: RemoteLoad<PullRequestView> = RemoteLoad.Idle,
    val artifacts: RemoteLoad<List<Artifact>> = RemoteLoad.Idle,
    val usage: RemoteLoad<AgentUsage> = RemoteLoad.Idle,
    val browser: RepoBrowserState = RepoBrowserState(),
    /** The branch's diff against its base from the account (Extended); [RemoteLoad.Unsupported] names why not, otherwise. */
    val diff: RemoteLoad<AgentDiff> = RemoteLoad.Idle,
    val workspace: WorkspaceBrowserState = WorkspaceBrowserState(),
    /** A Remote Control chat's machine, from the fleet endpoint; idle for a chat that runs in the cloud. */
    val machine: RemoteLoad<MachineStatus> = RemoteLoad.Idle,
    val desktop: DesktopState = DesktopState.Idle,
    /** Opening the pull request from here: idle until asked; the URL once Cursor has opened it. */
    val pullRequestCreation: RemoteLoad<String> = RemoteLoad.Idle,
) {
    val prUrl: String? get() = agent?.prUrl
    val hasPullRequest: Boolean get() = prUrl != null
    /** The files the Changes section lists: the pull request's when it has them, else the branch diff's (Extended). */
    val branchDiffFiles: List<AgentDiffFile> get() = diff.valueOrNull?.files.orEmpty()
}

/**
 * The right-side panel of one conversation. What the transcript already knows — the agent row, the run, the tool
 * payloads — is derived here from the same repositories the screen reads; the reads only the panel needs (the pull
 * request from its host, the repository's tree, the artifacts list, the usage) are asked for when their section
 * opens and kept for the panel's life.
 */
class PanelViewModel(private val graph: AppGraph, val agentId: String) : ViewModel() {

    private val pullRequest = MutableStateFlow<RemoteLoad<PullRequestView>>(RemoteLoad.Idle)
    private val artifacts = MutableStateFlow<RemoteLoad<List<Artifact>>>(RemoteLoad.Idle)
    private val usage = MutableStateFlow<RemoteLoad<AgentUsage>>(RemoteLoad.Idle)
    private val browser = MutableStateFlow(RepoBrowserState())
    private val diff = MutableStateFlow<RemoteLoad<AgentDiff>>(RemoteLoad.Idle)
    private val workspace = MutableStateFlow(WorkspaceBrowserState())
    private val machine = MutableStateFlow<RemoteLoad<MachineStatus>>(RemoteLoad.Idle)
    private val desktop = MutableStateFlow<DesktopState>(DesktopState.Idle)
    private val pullRequestCreation = MutableStateFlow<RemoteLoad<String>>(RemoteLoad.Idle)
    private var browseJob: Job? = null
    private var fileJob: Job? = null
    private var pullRequestJob: Job? = null
    private var diffJob: Job? = null
    private var workspaceJob: Job? = null
    private var machineJob: Job? = null
    private var desktopJob: Job? = null
    private var creationJob: Job? = null

    private val agent: StateFlow<Agent?> = graph.agents.state.map { s -> s.agents.firstOrNull { it.id == agentId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.agents.agent(agentId))

    /** The tool payloads read off the timeline, recomputed only when the items change and off the main thread. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val content: StateFlow<Pair<TranscriptContent, List<MessageAttachment>>> = graph.conversations.state(agentId)
        .map { it.items }
        .distinctUntilChanged { a, b -> a === b }
        .mapLatest { items -> withContext(Dispatchers.Default) { TranscriptContent.of(items) to promptImagesOf(items) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptContent.EMPTY to emptyList())

    /** The reads of the agent's VM and of the account, folded so the main combine stays within its arity. */
    private val vmLoads = combine(diff, workspace, machine, desktop, pullRequestCreation) { d, w, m, dk, c -> VmLoads(d, w, m, dk, c) }

    val state: StateFlow<PanelState> = combine(
        agent,
        graph.conversations.state(agentId),
        content,
        graph.extendedMode.capabilities,
        combine(pullRequest, artifacts, usage, browser, vmLoads) { pr, art, use, br, vm -> Loads(pr, art, use, br, vm) },
    ) { a, conversation, (transcript, prompts), capabilities, loads ->
        PanelState(
            agentId = agentId,
            agent = a,
            runStatus = conversation.runStatus,
            isStreaming = conversation.isStreaming,
            content = transcript,
            promptImages = prompts,
            capabilities = capabilities,
            isDemo = graph.session.isDemo,
            pullRequest = loads.pullRequest,
            artifacts = loads.artifacts,
            usage = loads.usage,
            browser = loads.browser.withRepository(a),
            diff = loads.vm.diff,
            workspace = loads.vm.workspace,
            machine = loads.vm.machine,
            desktop = loads.vm.desktop,
            pullRequestCreation = loads.vm.pullRequestCreation,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PanelState(agentId, agent = graph.agents.agent(agentId), isDemo = graph.session.isDemo))

    private data class Loads(val pullRequest: RemoteLoad<PullRequestView>, val artifacts: RemoteLoad<List<Artifact>>, val usage: RemoteLoad<AgentUsage>, val browser: RepoBrowserState, val vm: VmLoads)

    private data class VmLoads(
        val diff: RemoteLoad<AgentDiff>,
        val workspace: WorkspaceBrowserState,
        val machine: RemoteLoad<MachineStatus>,
        val desktop: DesktopState,
        val pullRequestCreation: RemoteLoad<String>,
    )

    private fun promptImagesOf(items: List<TimelineItem>): List<MessageAttachment> = items.filterIsInstance<UserMessage>().flatMap { it.attachments }

    /** The browser's repository and ref follow the agent row: its repository at its branch, else its starting ref. */
    private fun RepoBrowserState.withRepository(agent: Agent?): RepoBrowserState {
        val repoUrl = agent?.repoUrl
        val ref = agent?.branchName ?: agent?.startingRef
        if (repoUrl == this.repoUrl && ref == this.ref) return this
        return copy(repoUrl = repoUrl, ref = ref, host = repoUrl?.let { graph.reviews.contentsHost(it) })
    }

    // -- the pull request --------------------------------------------------------------------------------------------

    fun loadPullRequest(force: Boolean = false) {
        val url = agent.value?.prUrl ?: return
        if (!force && pullRequest.value !is RemoteLoad.Idle && pullRequest.value !is RemoteLoad.Failed) return
        if (pullRequestJob?.isActive == true && !force) return
        pullRequestJob?.cancel()
        pullRequest.value = RemoteLoad.Loading
        pullRequestJob = viewModelScope.launch {
            pullRequest.value = when (val load = graph.reviews.pullRequest(url, force)) {
                is PullRequestLoad.Loaded -> RemoteLoad.Loaded(load.view)
                is PullRequestLoad.Unsupported -> RemoteLoad.Unsupported(load.reason, url)
                is PullRequestLoad.Failed -> RemoteLoad.Failed(load.message, retryable = !load.notFound)
            }
        }
    }

    // -- artifacts and usage -----------------------------------------------------------------------------------------

    fun loadArtifacts(force: Boolean = false) {
        if (!force && artifacts.value is RemoteLoad.Loaded) return
        if (artifacts.value is RemoteLoad.Loading) return
        artifacts.value = RemoteLoad.Loading
        viewModelScope.launch {
            artifacts.value = try {
                RemoteLoad.Loaded(graph.artifacts.list(agentId, force))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                RemoteLoad.Failed(t.userMessage())
            }
        }
    }

    fun loadUsage(force: Boolean = false) {
        if (!force && usage.value is RemoteLoad.Loaded) return
        if (usage.value is RemoteLoad.Loading) return
        usage.value = RemoteLoad.Loading
        viewModelScope.launch {
            usage.value = try {
                RemoteLoad.Loaded(graph.reviews.usage(agentId, force))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                RemoteLoad.Failed(t.userMessage())
            }
        }
    }

    /** A presigned URL for [artifact], good for a quarter of an hour; for opening in the browser. */
    suspend fun artifactUrl(artifact: Artifact): Result<String> = runCatching { graph.artifacts.downloadUrl(agentId, artifact.path) }

    // -- the agent's VM: branch diff, workspace ------------------------------------------------------------------------

    /** The branch's diff against its base (Extended). Without the capability the load names why, and makes no call. */
    fun loadDiff(force: Boolean = false) {
        if (!force && diff.value is RemoteLoad.Loaded) return
        if (diffJob?.isActive == true && !force) return
        diffJob?.cancel()
        diff.value = RemoteLoad.Loading
        diffJob = viewModelScope.launch {
            diff.value = graph.workspace.diff(agentId, force).toLoad()
        }
    }

    /** Every file of the agent's workspace (Extended); the tree is read whole and walked locally. */
    fun loadWorkspace(force: Boolean = false) {
        val current = workspace.value.tree
        if (!force && (current is RemoteLoad.Loaded || current is RemoteLoad.Loading)) return
        workspaceJob?.cancel()
        workspace.update { it.copy(tree = RemoteLoad.Loading) }
        workspaceJob = viewModelScope.launch {
            val load = graph.workspace.tree(agentId, force).toLoad()
            workspace.update { it.copy(tree = load) }
        }
    }

    /** Shows [path] of the workspace tree; the root when empty. */
    fun browseWorkspace(path: String = "") {
        val clean = path.trim().trim('/')
        workspace.update { it.copy(path = clean) }
        browser.update { it.copy(file = null) }
    }

    fun browseWorkspaceUp() {
        val path = workspace.value.path
        if (path.isEmpty()) return
        browseWorkspace(path.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    /** Reads [path] off the agent's VM (`ReadBinaryFile`) into the viewer. */
    fun openWorkspaceFile(path: String) {
        fileJob?.cancel()
        browser.update { it.copy(file = FileView.Loading(path)) }
        fileJob = viewModelScope.launch {
            val read = graph.workspace.file(agentId, path)
            browser.update { b ->
                if (b.file?.path != path) return@update b
                b.copy(
                    file = when (read) {
                        is VmRead.Loaded -> FileView.Workspace(read.value)
                        is VmRead.NotAvailable -> FileView.Failed(path, read.reason)
                        is VmRead.Failed -> FileView.Failed(path, read.message)
                    },
                )
            }
        }
    }

    /** Shows one file of the branch diff in the viewer: its patch, or the file as it now stands. */
    fun openBranchDiffFile(file: AgentDiffFile) {
        browser.update { it.copy(file = FileView.BranchDiff(file)) }
    }

    // -- the Remote section -------------------------------------------------------------------------------------------

    /** Where a Remote Control chat's machine stands (`GET /v0/private-workers`); nothing for a chat in the cloud. */
    fun loadMachine(force: Boolean = false) {
        val current = agent.value ?: return
        if (current.envType != EnvType.MACHINE) {
            machine.value = RemoteLoad.Idle
            return
        }
        if (!force && (machine.value is RemoteLoad.Loaded || machine.value is RemoteLoad.Loading)) return
        machineJob?.cancel()
        machine.value = RemoteLoad.Loading
        machineJob = viewModelScope.launch {
            val status = graph.remote.machineStatus(current, force)
            machine.value = when {
                status == null -> RemoteLoad.Idle
                status.isSuccess -> RemoteLoad.Loaded(status.getOrThrow())
                else -> RemoteLoad.Failed(status.exceptionOrNull()?.userMessage() ?: "Couldn't read the machine's state.")
            }
        }
    }

    /**
     * Opens the agent's desktop: `GetMachine`, the probe of the websockify URLs, then the WebView on the one that
     * answered. [viewOnly] decides whether the viewer's touches reach the VM; it can be changed while open.
     */
    fun openDesktop(viewOnly: Boolean = true) {
        val current = agent.value ?: return
        if (desktop.value is DesktopState.Opening) return
        desktopJob?.cancel()
        desktop.value = DesktopState.Opening
        desktopJob = viewModelScope.launch {
            desktop.value = when (val opened = graph.remote.openDesktop(current, viewOnly)) {
                is DesktopOpen.Opened -> DesktopState.Open(opened.session)
                is DesktopOpen.Failed -> DesktopState.Failed(opened.failure)
            }
        }
    }

    fun setDesktopViewOnly(viewOnly: Boolean) {
        desktop.update { if (it is DesktopState.Open) it.copy(session = it.session.copy(viewOnly = viewOnly)) else it }
    }

    /** Ends the desktop session: the WebView goes, and with it the connection; the ticket is not kept. */
    fun closeDesktop() {
        desktopJob?.cancel()
        desktop.value = DesktopState.Idle
    }

    // -- the pull request, from here -----------------------------------------------------------------------------------

    /** Asks Cursor to open the agent's pull request (`MakePRBackgroundComposer`); the chat row and the section follow. */
    fun createPullRequest() {
        if (pullRequestCreation.value is RemoteLoad.Loading) return
        val current = agent.value ?: return
        creationJob?.cancel()
        pullRequestCreation.value = RemoteLoad.Loading
        creationJob = viewModelScope.launch {
            val result = graph.reviews.createPullRequest(agentId, current.branchName)
            pullRequestCreation.value = result.fold(
                onSuccess = { created ->
                    // The row learns its pull request from the public record, the section from its host.
                    graph.agents.loadDetail(agentId)
                    pullRequest.value = RemoteLoad.Idle
                    loadPullRequest(force = true)
                    RemoteLoad.Loaded(created.url.orEmpty())
                },
                onFailure = { RemoteLoad.Failed(it.message ?: "Cursor could not open the pull request.", retryable = it !is IllegalStateException) },
            )
        }
    }

    private fun <T> VmRead<T>.toLoad(): RemoteLoad<T> = when (this) {
        is VmRead.Loaded -> RemoteLoad.Loaded(value)
        is VmRead.NotAvailable -> RemoteLoad.Unsupported(reason)
        is VmRead.Failed -> RemoteLoad.Failed(message, retryable = !endpointChanged)
    }

    // -- the repository browser --------------------------------------------------------------------------------------

    /** Lists [path] of the agent's repository at its branch; the root when empty. */
    fun browse(path: String = "", force: Boolean = false) {
        val current = state.value.browser
        val repoUrl = current.repoUrl ?: return
        val clean = path.trim().trim('/')
        if (!force && current.path == clean && current.listing is RemoteLoad.Loaded) {
            browser.update { it.copy(file = null) }
            return
        }
        browseJob?.cancel()
        browser.update { it.copy(path = clean, listing = RemoteLoad.Loading, file = null) }
        browseJob = viewModelScope.launch {
            val result = graph.reviews.contents(repoUrl, current.ref, clean, force)
            browser.update { b ->
                if (b.path != clean) return@update b
                b.copy(
                    listing = result.fold(
                        onSuccess = { contents ->
                            when (contents) {
                                is RepoContents.Directory -> RemoteLoad.Loaded(contents)
                                // Asked for a directory, got a file: show it, and keep the parent as the listing.
                                is RepoContents.File -> RemoteLoad.Loaded(RepoContents.Directory(clean, emptyList()))
                            }
                        },
                        onFailure = { failureOf(it) },
                    ),
                    file = (result.getOrNull() as? RepoContents.File)?.let { FileView.Repository(it.file) },
                )
            }
        }
    }

    /** Goes up one directory. */
    fun browseUp() {
        val path = state.value.browser.path
        if (path.isEmpty()) return
        browse(path.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    /** Opens [path] of the repository at the agent's branch in the viewer. */
    fun openRepoFile(path: String) {
        val current = state.value.browser
        val repoUrl = current.repoUrl ?: return
        fileJob?.cancel()
        browser.update { it.copy(file = FileView.Loading(path)) }
        fileJob = viewModelScope.launch {
            val result = graph.reviews.contents(repoUrl, current.ref, path)
            browser.update { b ->
                if (b.file?.path != path) return@update b
                b.copy(
                    file = result.fold(
                        onSuccess = { contents -> if (contents is RepoContents.File) FileView.Repository(contents.file) else FileView.Failed(path, "$path is a directory.") },
                        onFailure = { FileView.Failed(path, graph.reviews.describe(it)) },
                    ),
                )
            }
        }
    }

    /**
     * Opens a file the agent touched: the text a tool call carried when there is one (a read shows what the agent
     * saw, a write what it left), its diffs when it was only edited, else the file from the repository.
     */
    fun openTouched(path: String) {
        val content = state.value.content
        val change = content.changes.firstOrNull { it.path == path }
        val written = change?.contentAfter
        val read = readOf(path)
        when {
            written != null -> browser.update { it.copy(file = FileView.Transcript(written)) }
            change != null && change.diffs.isNotEmpty() -> browser.update { it.copy(file = FileView.Changes(change)) }
            read != null -> browser.update { it.copy(file = FileView.Transcript(read)) }
            state.value.browser.canBrowse && !state.value.isDemo -> openRepoFile(path)
            else -> browser.update { it.copy(file = FileView.Failed(path, "The stream carried no copy of this file, and the repository cannot be browsed from here.")) }
        }
    }

    /** Shows one change's diffs in the viewer. */
    fun openChange(change: TranscriptContent.FileChange) {
        browser.update { it.copy(file = FileView.Changes(change)) }
    }

    fun closeFile() = browser.update { it.copy(file = null) }

    private fun readOf(path: String): ToolPayload.FileContent? {
        val items = graph.conversations.state(agentId).value.items
        return items.asSequence().filterIsInstance<com.cursorforandroid.domain.ActivityGroup>()
            .flatMap { it.calls.asSequence() }
            .mapNotNull { it.payload as? ToolPayload.FileContent }
            .lastOrNull { it.path == path }
    }

    private fun failureOf(t: Throwable): RemoteLoad.Failed = RemoteLoad.Failed(graph.reviews.describe(t))

    class Factory(private val graph: AppGraph, private val agentId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PanelViewModel(graph, agentId) as T
    }
}
