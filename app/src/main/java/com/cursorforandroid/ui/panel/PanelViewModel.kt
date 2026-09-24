package com.cursorforandroid.ui.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.data.repo.DesktopOpen
import com.cursorforandroid.data.repo.FileRead
import com.cursorforandroid.data.repo.PullRequestLoad
import com.cursorforandroid.data.repo.VmRead
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.AgentUsage
import com.cursorforandroid.domain.ContextDocument
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.ContextStores
import com.cursorforandroid.domain.RecentContextFile
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.DesktopSession
import com.cursorforandroid.domain.DesktopTrace
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.MachineStatus
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.data.repo.SteeringRepository
import com.cursorforandroid.domain.AgentParentKind
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
    /**
     * Nothing could be shown: [title] why, [message] what it means, [asked] the request refused and what came back;
     * [retryable] offers Retry, [wakeable] waking the agent's machine first.
     */
    data class Failed(
        override val path: String,
        val message: String,
        val title: String = "Couldn't open this file",
        val asked: String? = null,
        val retryable: Boolean = false,
        val wakeable: Boolean = false,
        val copyablePath: String? = null,
    ) : FileView
    data class Repository(val file: RepoFile) : FileView { override val path: String get() = file.path }
    data class Transcript(val content: ToolPayload.FileContent) : FileView { override val path: String get() = content.path }
    data class Changes(val change: TranscriptContent.FileChange) : FileView { override val path: String get() = change.path }
    /** A file of the agent's live workspace (`ReadBinaryFile`), typed like a repository file so the same viewer shows it. */
    data class Workspace(val file: RepoFile) : FileView { override val path: String get() = file.path }
    /** One file of the branch's diff against its base (`GetBackgroundComposerDiffDetails`): its patch, or the file as it now stands. */
    data class BranchDiff(val file: AgentDiffFile) : FileView { override val path: String get() = file.path }
}

/** The title of a file the transcript names and nothing here can read. */
private const val NO_COPY = "The transcript carried no copy of this file"

/** Where the Files › Workspace tab stands: the directory on screen, and the tree it walks (read once, whole). */
data class WorkspaceBrowserState(
    val path: String = "",
    val tree: RemoteLoad<WorkspaceTree> = RemoteLoad.Idle,
) {
    val isAtRoot: Boolean get() = path.isEmpty()
}

/** The agent's desktop, as the viewer over the chat sees it: every state but [Idle] is on screen. */
sealed interface DesktopState {
    data object Idle : DesktopState

    /** `GetMachine` and the probe are out; [trace] names the step under way. */
    data class Opening(val trace: DesktopTrace, val viewOnly: Boolean = true) : DesktopState

    /** A websockify URL answered; the WebView is up on it. */
    data class Open(val session: DesktopSession) : DesktopState

    /** A step before the viewer failed; the viewer shows which, with the trace, a retry and the diagnostics to share. */
    data class Failed(val failure: DesktopFailure, val viewOnly: Boolean = true) : DesktopState
}

/**
 * The Project panel's Context reads (see [PanelTab.Project], [PanelTab.Document]): which stores the chat
 * has, the Project's notes, each folder listed so far by store and path, the folders open in the tree, the Recents
 * row, and the documents open in tabs with whether each shows its source.
 */
data class ContextPanelState(
    /** The Project tab shows its files rather than its notes: the header's toggle (see the web's All Files). */
    val allFiles: Boolean = false,
    val stores: RemoteLoad<ContextStores> = RemoteLoad.Idle,
    val notes: RemoteLoad<ContextDocument?> = RemoteLoad.Idle,
    val listings: Map<String, RemoteLoad<List<ContextEntry>>> = emptyMap(),
    val expandedFolders: Set<String> = emptySet(),
    val recents: RemoteLoad<List<RecentContextFile>> = RemoteLoad.Idle,
    val documents: Map<String, RemoteLoad<ContextDocument>> = emptyMap(),
    val sourceTabs: Set<String> = emptySet(),
) {
    fun listing(store: AgentStoreRef, path: String): RemoteLoad<List<ContextEntry>> = listings[folderKey(store, path)] ?: RemoteLoad.Idle
    fun isExpanded(store: AgentStoreRef, path: String): Boolean = folderKey(store, path) in expandedFolders
    fun document(tab: PanelTab.Document): RemoteLoad<ContextDocument> = documents[tab.key] ?: RemoteLoad.Idle
    fun showsSource(tab: PanelTab.Document): Boolean = tab.key in sourceTabs

    companion object {
        fun folderKey(store: AgentStoreRef, path: String): String = "${store.storeId}:${path.trim('/')}"
    }
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
    /** What the account has said about the chat's controls (Extended mode): its queue, the last steer, what was held or answered from here. */
    val controls: ConversationControls = ConversationControls.EMPTY,
    /** The chat this one hangs off — as a side chat, a worker or a subagent — when the list holds its row. */
    val parentAgent: Agent? = null,
    /** The chats branched off this one as side chats, as the agent list knows them, newest first. */
    val sideChats: List<Agent> = emptyList(),
    /** The account's read of the chat's children (Extended); [RemoteLoad.Unsupported] names why not, otherwise. */
    val sideChatsLoad: RemoteLoad<Unit> = RemoteLoad.Idle,
    /** Starting a side chat from here: idle until asked; the new chat's id once Cursor has started it. */
    val sideChatCreation: RemoteLoad<String> = RemoteLoad.Idle,
    /**
     * The sections the reader opened or closed, by id, kept for the panel's life: the panel is composed only while
     * it is open, so without this a reopened panel would forget what was expanded. A section absent here is at its
     * default.
     */
    val expandedSections: Map<PanelSectionId, Boolean> = emptyMap(),
    /** Which surface the panel shows: the Project panel or the chat's sections (see [PanelSurface]). */
    val surface: PanelSurface = PanelSurface.Chat,
    /** The Project panel's tabs: which are open, which is showing (see [PanelTab]). */
    val tabs: PanelTabsState = PanelTabsState(),
    /** The Context tabs' reads. */
    val context: ContextPanelState = ContextPanelState(),
) {
    val prUrl: String? get() = agent?.prUrl
    /**
     * The Project this chat belongs to — itself for a coordinator, the coordinator for a primary, a side chat or a
     * subagent whose parent the list shows to be a Project — or null for a chat of the account's own.
     */
    val projectRootId: String?
        get() {
            val current = agent ?: return null
            if (current.looksLikeProject) return current.id
            val parent = current.parent ?: return null
            return if (parent.kind == AgentParentKind.PROJECT_WORKER || parentAgent?.looksLikeProject == true) parent.id else null
        }
    /** The Project's row when it is loaded: this chat, or its parent. */
    val projectRoot: Agent? get() = projectRootId?.let { id -> if (agent?.id == id) agent else parentAgent?.takeIf { it.id == id } }
    /** Whether the panel has a Project tab: a Project's coordinator, or a chat inside one. */
    val hasProjectTab: Boolean get() = projectRootId != null
    val hasPullRequest: Boolean get() = prUrl != null
    /** The files the Changes section lists: the pull request's when it has them, else the branch diff's (Extended). */
    val branchDiffFiles: List<AgentDiffFile> get() = diff.valueOrNull?.files.orEmpty()
    /** A turn is under way, by the run, the stream or the row. */
    val isRunning: Boolean get() = runStatus?.isActive == true || isStreaming || agent?.isRunning == true
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
    private val sideChatsLoad = MutableStateFlow<RemoteLoad<Unit>>(RemoteLoad.Idle)
    private val sideChatCreation = MutableStateFlow<RemoteLoad<String>>(RemoteLoad.Idle)
    private val expandedSections = MutableStateFlow<Map<PanelSectionId, Boolean>>(emptyMap())
    /** The document and side chat tabs opened this session, in order, and the key of the tab the reader picked (null: the chat's default). */
    private val dynamicTabs = MutableStateFlow<List<PanelTab>>(emptyList())
    private val selectedTabKey = MutableStateFlow<String?>(null)
    /** The surface the reader asked for; null until then, when a chat in a Project opens on the Project panel and any other on its sections. */
    private val surfaceChoice = MutableStateFlow<PanelSurface?>(null)
    private val context = MutableStateFlow(ContextPanelState())
    private var contextJob: Job? = null
    private val folderJobs = HashMap<String, Job>()
    private val documentJobs = HashMap<String, Job>()
    private var browseJob: Job? = null
    private var fileJob: Job? = null
    private var pullRequestJob: Job? = null
    private var diffJob: Job? = null
    private var workspaceJob: Job? = null
    private var machineJob: Job? = null
    private var desktopJob: Job? = null
    private var creationJob: Job? = null
    private var sideChatsJob: Job? = null

    private val agent: StateFlow<Agent?> = graph.agents.state.map { s -> s.agents.firstOrNull { it.id == agentId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.agents.agent(agentId))

    /** The chats the list hangs off this one as side chats, newest first — in either mode, from whatever placed them. */
    private val sideChats: StateFlow<List<Agent>> = graph.agents.state
        .map { s -> s.agents.filter { it.parent?.id == agentId && it.parent.kind == AgentParentKind.SIDE_CHAT }.sortedByDescending { it.listedAtMillis } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The row of the chat this one hangs off, once the list holds it (a side chat's parent, a worker's coordinator). */
    private val parentAgent: StateFlow<Agent?> = graph.agents.state
        .map { s -> s.agents.firstOrNull { it.id == agentId }?.parent?.id?.let { parentId -> s.agents.firstOrNull { it.id == parentId } } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The tool payloads read off the timeline, recomputed only when the items change and off the main thread. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val content: StateFlow<Pair<TranscriptContent, List<MessageAttachment>>> = graph.conversations.state(agentId)
        .map { it.items }
        .distinctUntilChanged { a, b -> a === b }
        .mapLatest { items -> withContext(Dispatchers.Default) { TranscriptContent.of(items) to promptImagesOf(items) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptContent.EMPTY to emptyList())

    /** The reads of the agent's VM and of the account, folded so the main combine stays within its arity. */
    private val vmLoads = combine(diff, workspace, machine, desktop, pullRequestCreation) { d, w, m, dk, c -> VmLoads(d, w, m, dk, c) }
    /** The chat's kin — its parent, its side chats and the reads and writes about them — folded for the same reason. */
    private val sideChatLoads = combine(parentAgent, sideChats, sideChatsLoad, sideChatCreation) { parent, chats, load, creation -> SideChatLoads(parent, chats, load, creation) }
    /** The panel's tabs as the reader left them, and the Context tabs' reads, folded for the same reason. */
    private val tabLoads = combine(dynamicTabs, selectedTabKey, context, surfaceChoice) { dynamic, selected, ctx, surface -> TabLoads(dynamic, selected, ctx, surface) }
    /** [vmLoads] with what the account has said about the chat's controls, its side chats, the reader's expanded sections and tabs. */
    private val extendedLoads = combine(vmLoads, graph.steering.state(agentId), sideChatLoads, expandedSections, tabLoads) { vm, controls, side, expanded, tabs -> ExtendedLoads(vm, controls, side, expanded, tabs) }

    val state: StateFlow<PanelState> = combine(
        agent,
        graph.conversations.state(agentId),
        content,
        graph.extendedMode.capabilities,
        combine(pullRequest, artifacts, usage, browser, extendedLoads) { pr, art, use, br, extended -> Loads(pr, art, use, br, extended) },
    ) { a, conversation, (transcript, prompts), capabilities, loads ->
        val inProject = isInProject(a, loads.extended.sideChats.parent)
        val open = (if (inProject) listOf(PanelTab.Project) else emptyList()) + loads.extended.tabs.dynamic
        val selected = loads.extended.tabs.selectedKey?.let { key -> open.firstOrNull { it.key == key } } ?: open.firstOrNull()
        val surface = loads.extended.tabs.surface ?: if (inProject) PanelSurface.Project else PanelSurface.Chat
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
            diff = loads.extended.vm.diff,
            workspace = loads.extended.vm.workspace,
            machine = loads.extended.vm.machine,
            desktop = loads.extended.vm.desktop,
            pullRequestCreation = loads.extended.vm.pullRequestCreation,
            controls = loads.extended.controls,
            parentAgent = loads.extended.sideChats.parent,
            sideChats = loads.extended.sideChats.chats,
            sideChatsLoad = loads.extended.sideChats.load,
            sideChatCreation = loads.extended.sideChats.creation,
            expandedSections = loads.extended.expandedSections,
            surface = surface,
            tabs = PanelTabsState(open = open, selected = selected),
            context = loads.extended.tabs.context,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PanelState(agentId, agent = graph.agents.agent(agentId), isDemo = graph.session.isDemo))

    /** Whether the chat is a Project's coordinator or a chat inside a Project: what gives the panel its Project tab. */
    private fun isInProject(agent: Agent?, parent: Agent?): Boolean =
        agent != null && (agent.looksLikeProject || agent.parent?.kind == AgentParentKind.PROJECT_WORKER || (agent.parent != null && parent?.looksLikeProject == true))

    private data class Loads(
        val pullRequest: RemoteLoad<PullRequestView>,
        val artifacts: RemoteLoad<List<Artifact>>,
        val usage: RemoteLoad<AgentUsage>,
        val browser: RepoBrowserState,
        val extended: ExtendedLoads,
    )

    private data class ExtendedLoads(
        val vm: VmLoads,
        val controls: ConversationControls,
        val sideChats: SideChatLoads,
        val expandedSections: Map<PanelSectionId, Boolean>,
        val tabs: TabLoads,
    )

    private data class TabLoads(
        val dynamic: List<PanelTab>,
        val selectedKey: String?,
        val context: ContextPanelState,
        val surface: PanelSurface?,
    )

    private data class SideChatLoads(
        val parent: Agent?,
        val chats: List<Agent>,
        val load: RemoteLoad<Unit>,
        val creation: RemoteLoad<String>,
    )

    /** Remembers a section the reader opened or closed (see [PanelState.expandedSections]). */
    fun setSectionExpanded(id: PanelSectionId, expanded: Boolean) {
        expandedSections.update { it + (id to expanded) }
    }

    private data class VmLoads(
        val diff: RemoteLoad<AgentDiff>,
        val workspace: WorkspaceBrowserState,
        val machine: RemoteLoad<MachineStatus>,
        val desktop: DesktopState,
        val pullRequestCreation: RemoteLoad<String>,
    )

    init {
        // The account's queue is kept current while the panel lives, like while the chat's screen does.
        graph.steering.attach(agentId)
    }

    override fun onCleared() {
        graph.steering.detach(agentId)
        super.onCleared()
    }

    // -- the panel's surfaces and tabs ---------------------------------------------------------------------------------

    fun showSurface(surface: PanelSurface) {
        surfaceChoice.value = surface
    }

    fun selectTab(tab: PanelTab) {
        surfaceChoice.value = PanelSurface.Project
        selectedTabKey.value = tab.key
    }

    /** Closes a document or side chat tab; a closed selected tab hands the panel to the Project tab. */
    fun closeTab(tab: PanelTab) {
        if (!tab.closable) return
        dynamicTabs.update { open -> open.filterNot { it.key == tab.key } }
        if (selectedTabKey.value == tab.key) selectedTabKey.value = PanelTab.Project.key
        if (tab is PanelTab.Document) {
            documentJobs.remove(tab.key)?.cancel()
            context.update { it.copy(documents = it.documents - tab.key, sourceTabs = it.sourceTabs - tab.key) }
        }
    }

    /** Opens (or returns to) the side chat's tab beside the conversation. */
    fun openSideChat(sideChatId: String) = open(PanelTab.SideChat(sideChatId))

    /** Opens (or returns to) [path] of [store] as a document tab and reads it. */
    fun openDocument(store: AgentStoreRef, path: String) {
        val tab = PanelTab.Document(store.storeId, path.trim('/'))
        open(tab)
        loadDocument(tab)
    }

    /** The Project tab on its notes or, with [allFiles], on the Context stores. */
    fun openProject(allFiles: Boolean) {
        surfaceChoice.value = PanelSurface.Project
        selectedTabKey.value = PanelTab.Project.key
        context.update { it.copy(allFiles = allFiles) }
        loadContext()
    }

    /** The chat's sections with [section] open. */
    fun showSection(section: PanelSectionId) {
        surfaceChoice.value = PanelSurface.Chat
        setSectionExpanded(section, true)
    }

    private fun open(tab: PanelTab) {
        surfaceChoice.value = PanelSurface.Project
        dynamicTabs.update { open -> if (open.any { it.key == tab.key }) open else open + tab }
        selectedTabKey.value = tab.key
    }

    // -- Context: the Project's store and the user's -----------------------------------------------------------------------

    /**
     * Reads which stores the chat has, then — in parallel — the Project's notes, both roots' listings and the
     * Recents row. Idempotent while a read is out; [force] re-reads through the repository's cache.
     */
    fun loadContext(force: Boolean = false) {
        if (contextJob?.isActive == true && !force) return
        val current = context.value.stores
        if (!force && current is RemoteLoad.Loaded) return
        contextJob?.cancel()
        context.update { it.copy(stores = RemoteLoad.Loading) }
        contextJob = viewModelScope.launch {
            val root = state.value.projectRootId
            val read = graph.agentStores.storesFor(agentId, root, force)
            val stores = when (read) {
                is VmRead.Loaded -> read.value
                is VmRead.NotAvailable -> {
                    context.update { it.copy(stores = RemoteLoad.Unsupported(read.reason), notes = RemoteLoad.Unsupported(read.reason), recents = RemoteLoad.Unsupported(read.reason)) }
                    return@launch
                }
                is VmRead.Failed -> {
                    context.update { it.copy(stores = RemoteLoad.Failed(read.message, retryable = !read.endpointChanged)) }
                    return@launch
                }
            }
            // The roots open at once, as the web's tree does; deeper folders as they are tapped.
            context.update { c -> c.copy(stores = RemoteLoad.Loaded(stores), expandedFolders = c.expandedFolders + stores.all.map { ContextPanelState.folderKey(it, "") }) }
            stores.all.forEach { store -> listFolder(store, "", force) }
            val project = stores.project
            if (project != null) {
                context.update { it.copy(notes = RemoteLoad.Loading) }
                launch { context.update { it.copy(notes = graph.agentStores.notes(project, force).toLoad()) } }
            } else {
                context.update { it.copy(notes = RemoteLoad.Loaded(null)) }
            }
            context.update { it.copy(recents = RemoteLoad.Loading) }
            launch { context.update { it.copy(recents = graph.agentStores.recents(agentId, stores.all, force = force).toLoad()) } }
        }
    }

    /** Opens or closes a folder of the tree; an opened folder not yet listed is listed. */
    fun toggleFolder(store: AgentStoreRef, path: String) {
        val key = ContextPanelState.folderKey(store, path)
        val expanding = key !in context.value.expandedFolders
        context.update { it.copy(expandedFolders = if (expanding) it.expandedFolders + key else it.expandedFolders - key) }
        if (expanding && context.value.listings[key] !is RemoteLoad.Loaded) listFolder(store, path)
    }

    private fun listFolder(store: AgentStoreRef, path: String, force: Boolean = false) {
        val key = ContextPanelState.folderKey(store, path)
        if (folderJobs[key]?.isActive == true && !force) return
        folderJobs[key]?.cancel()
        context.update { it.copy(listings = it.listings + (key to RemoteLoad.Loading)) }
        folderJobs[key] = viewModelScope.launch {
            val load = graph.agentStores.entries(store, path, force).toLoad()
            context.update { it.copy(listings = it.listings + (key to load)) }
        }
    }

    /** Reads the document behind [tab]; the store is the one the listing named, or the id alone when the tab outlived it. */
    fun loadDocument(tab: PanelTab.Document, force: Boolean = false) {
        val current = context.value.documents[tab.key]
        if (!force && (current is RemoteLoad.Loaded || current is RemoteLoad.Loading)) return
        documentJobs[tab.key]?.cancel()
        context.update { it.copy(documents = it.documents + (tab.key to RemoteLoad.Loading)) }
        documentJobs[tab.key] = viewModelScope.launch {
            val store = context.value.stores.valueOrNull?.all?.firstOrNull { it.storeId == tab.storeId } ?: AgentStoreRef(tab.storeId)
            val load = graph.agentStores.document(store, tab.path, force).toLoad()
            context.update { it.copy(documents = it.documents + (tab.key to load)) }
        }
    }

    fun setDocumentSource(tab: PanelTab.Document, source: Boolean) {
        context.update { it.copy(sourceTabs = if (source) it.sourceTabs + tab.key else it.sourceTabs - tab.key) }
    }

    // -- the chat's controls on the account (Extended mode) -----------------------------------------------------------

    /** Each answers what to tell the reader — the account's outcome, or nothing — or fails with the reason; see [SteeringRepository]. */
    suspend fun answerQuestion(callId: String, answers: List<ToolPayload.Question.Answer>): Result<String?> = graph.steering.answerQuestion(agentId, callId, answers).map { it.message }
    suspend fun pauseRun(): Result<String?> = graph.steering.pause(agentId).map { "Paused; resume when you're ready." }
    suspend fun resumeRun(): Result<String?> = graph.steering.resume(agentId).map { "Resumed." }
    suspend fun stopRun(): Result<String?> = graph.conversations.cancelActiveRun(agentId).map { null }
    suspend fun wake(): Result<String?> = graph.steering.wake(agentId).map { if (it) "Waking the agent's machine." else "The machine was already awake." }

    // -- side chats ----------------------------------------------------------------------------------------------------

    /**
     * Re-reads the chat's children from the account (`ListBackgroundComposerChildren`, Extended); the rows follow
     * through the agent list. Without the capability the load names why, and no call is made.
     */
    fun refreshSideChats() {
        if (sideChatsJob?.isActive == true) return
        sideChatsLoad.value = RemoteLoad.Loading
        sideChatsJob = viewModelScope.launch {
            sideChatsLoad.value = when (val read = graph.projects.refreshChildren(agentId)) {
                is VmRead.Loaded -> RemoteLoad.Loaded(Unit)
                is VmRead.NotAvailable -> RemoteLoad.Unsupported(read.reason)
                is VmRead.Failed -> RemoteLoad.Failed(read.message, retryable = !read.endpointChanged)
            }
        }
    }

    /** Branches a side chat off this chat (`StartSideChatBackgroundComposer`); the new row joins the list under it. */
    suspend fun startSideChat(name: String?): Result<String?> {
        if (sideChatCreation.value is RemoteLoad.Loading) return Result.success(null)
        sideChatCreation.value = RemoteLoad.Loading
        val result = graph.projects.startSideChat(agentId, name?.trim()?.takeIf { it.isNotEmpty() })
        sideChatCreation.value = result.fold(
            onSuccess = { RemoteLoad.Loaded(it) },
            onFailure = { RemoteLoad.Failed(it.userMessage(), retryable = it !is IllegalStateException) },
        )
        // A side chat started from here opens beside the conversation at once, as a tab of the panel.
        result.onSuccess { openSideChat(it) }
        return result.map { "Side chat started." }
    }

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
        readPullRequest(url, force)
    }

    private fun readPullRequest(url: String, force: Boolean) {
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
        reopen = { openWorkspaceFile(path) }
        fileJob?.cancel()
        browser.update { it.copy(file = FileView.Loading(path)) }
        fileJob = viewModelScope.launch {
            val read = graph.workspace.file(agentId, path, force = true)
            browser.update { b ->
                if (b.file?.path != path) return@update b
                b.copy(
                    file = when (read) {
                        is VmRead.Loaded -> FileView.Workspace(read.value)
                        is VmRead.NotAvailable -> FileView.Failed(path, read.reason)
                        is VmRead.Failed -> FileView.Failed(path, read.message, asked = read.asked, retryable = true)
                    },
                )
            }
        }
    }

    /** What the viewer's Retry asks again: the last open, with the agent's machine woken first when [wake]. */
    private var reopen: ((wake: Boolean) -> Unit)? = null

    fun retryFile(wake: Boolean) {
        reopen?.invoke(wake)
    }

    /**
     * Reads [path] — named by a tool call, anywhere on the agent's machine — the way the transcript's links do (see
     * [com.cursorforandroid.data.repo.AgentFileRepository]): off the VM, as the path it is; the repository at the
     * agent's branch only for a file of the repository. What stops it is named: the machine asleep (with Wake), gone
     * with the chat, the file not there, the request refused and what came back.
     */
    private fun readAgentFile(path: String, wake: Boolean) {
        reopen = { again -> readAgentFile(path, again) }
        fileJob?.cancel()
        browser.update { it.copy(file = FileView.Loading(path)) }
        fileJob = viewModelScope.launch {
            val read = graph.agentFileReads.read(agentId, path, force = true, wake = wake)
            browser.update { b ->
                if (b.file?.path != path) return@update b
                b.copy(
                    file = when (read) {
                        is FileRead.Loaded -> if (read.source == FileRead.Source.Repository) FileView.Repository(read.file) else FileView.Workspace(read.file)
                        is FileRead.NotReadable -> FileView.Failed(path, read.reason, title = NO_COPY)
                        is FileRead.Failed -> when (read.reason) {
                            FileRead.Reason.MachineAsleep -> FileView.Failed(path, "Wake it and the file is read again.", title = "The agent's machine is asleep", asked = read.asked, retryable = true, wakeable = true)
                            FileRead.Reason.MachineGone -> FileView.Failed(path, "The chat expired or was archived, and its VM went with it; only what the transcript carried can be shown.", title = "The agent's machine is gone", asked = read.asked)
                            FileRead.Reason.NotFound -> FileView.Failed(path, "It was moved or deleted, or went with a machine that was replaced.", title = "The agent's machine has no such file", asked = read.asked, retryable = true)
                            FileRead.Reason.OutsideWorkspace -> FileView.Failed(path, AgentFileRepository.OUTSIDE_WORKSPACE + " This chat didn't include a copy.", title = "This file is outside the agent's workspace", asked = read.asked, copyablePath = path)
                            FileRead.Reason.Other -> FileView.Failed(path, read.message, asked = read.asked, retryable = read.retryable)
                        }
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
        desktop.value = DesktopState.Opening(DesktopTrace(agentId), viewOnly)
        desktopJob = viewModelScope.launch {
            val opened = graph.remote.openDesktop(current, viewOnly, progress = { trace -> desktop.update { if (it is DesktopState.Opening) it.copy(trace = trace) else it } })
            desktop.value = when (opened) {
                is DesktopOpen.Opened -> DesktopState.Open(opened.session)
                is DesktopOpen.Failed -> DesktopState.Failed(opened.failure, viewOnly)
            }
        }
    }

    /** The viewer's own failure — the page, its script, the socket or the first frame — with what it recorded. */
    fun failDesktop(failure: DesktopFailure) {
        val viewOnly = (desktop.value as? DesktopState.Open)?.session?.viewOnly ?: true
        desktop.value = DesktopState.Failed(failure, viewOnly)
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
                    // The row learns its pull request from the public record, the section from its host — by the
                    // URL Cursor answered with, since the row may take a moment to catch up.
                    graph.agents.loadDetail(agentId)
                    created.url?.let { readPullRequest(it, force = true) }
                    RemoteLoad.Loaded(created.url.orEmpty())
                },
                onFailure = { RemoteLoad.Failed(it.message ?: "Cursor could not open the pull request.", retryable = it !is IllegalStateException) },
            )
        }
    }

    private fun <T> VmRead<T>.toLoad(): RemoteLoad<T> = when (this) {
        is VmRead.Loaded -> RemoteLoad.Loaded(value)
        is VmRead.NotAvailable -> RemoteLoad.Unsupported(reason)
        // The request and its answer under the words, as the transcript's notices print them; Retry always, since a
        // refusal this build reads as a removal has been a missing file before (Bennett's 2026-09-22 frame).
        is VmRead.Failed -> RemoteLoad.Failed(listOfNotNull(message, asked?.let { "Asked: $it" }).joinToString("\n"))
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
        reopen = { openRepoFile(path) }
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
                        onFailure = { FileView.Failed(path, graph.reviews.describe(it), retryable = true) },
                    ),
                )
            }
        }
    }

    /**
     * Opens a file the agent touched: the text a tool call carried when there is one (a read shows what the agent
     * saw, a write what it left), its diffs when it was only edited, else — with the agent's machine readable
     * (Extended mode) — the file read off it wherever it is, `/tmp` as much as the workspace; else, for a file of
     * the repository, the repository at the agent's branch. A picture, a recording or a sound never gets here: its
     * row opens the media viewer.
     */
    fun openTouched(path: String) {
        val current = state.value
        val content = current.content
        val change = content.changes.firstOrNull { it.path == path }
        val written = change?.contentAfter
        val read = readOf(path)
        val inRepository = AgentFileRepository.isInWorkspace(path, null)
        when {
            written != null -> browser.update { it.copy(file = FileView.Transcript(written)) }
            change != null && change.diffs.isNotEmpty() -> browser.update { it.copy(file = FileView.Changes(change)) }
            read != null -> browser.update { it.copy(file = FileView.Transcript(read)) }
            current.capabilities.workspaceFiles && !current.isDemo -> readAgentFile(path, wake = false)
            inRepository && current.browser.canBrowse && !current.isDemo -> openRepoFile(AgentFileRepository.relativeGuess(path))
            inRepository -> browser.update { it.copy(file = FileView.Failed(path, "The repository can't be browsed from here, and reading the agent's workspace needs Extended mode.", title = NO_COPY)) }
            else -> browser.update { it.copy(file = FileView.Failed(path, AgentFileRepository.outsideNeedsExtended(path), title = NO_COPY)) }
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
