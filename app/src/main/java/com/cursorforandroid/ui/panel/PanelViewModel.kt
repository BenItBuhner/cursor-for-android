package com.cursorforandroid.ui.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.PullRequestLoad
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentUsage
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.data.repo.SteeringRepository
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.domain.UserMessage
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
    /** What the account has said about the chat's controls (Extended mode): its queue, the last steer, what was held or answered from here. */
    val controls: ConversationControls = ConversationControls.EMPTY,
) {
    val prUrl: String? get() = agent?.prUrl
    val hasPullRequest: Boolean get() = prUrl != null
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
    private var browseJob: Job? = null
    private var fileJob: Job? = null
    private var pullRequestJob: Job? = null

    private val agent: StateFlow<Agent?> = graph.agents.state.map { s -> s.agents.firstOrNull { it.id == agentId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.agents.agent(agentId))

    /** The tool payloads read off the timeline, recomputed only when the items change and off the main thread. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val content: StateFlow<Pair<TranscriptContent, List<MessageAttachment>>> = graph.conversations.state(agentId)
        .map { it.items }
        .distinctUntilChanged { a, b -> a === b }
        .mapLatest { items -> withContext(Dispatchers.Default) { TranscriptContent.of(items) to promptImagesOf(items) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptContent.EMPTY to emptyList())

    val state: StateFlow<PanelState> = combine(
        agent,
        graph.conversations.state(agentId),
        content,
        graph.extendedMode.capabilities,
        combine(pullRequest, artifacts, usage, browser, graph.steering.state(agentId)) { pr, art, use, br, controls -> Loads(pr, art, use, br, controls) },
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
            controls = loads.controls,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PanelState(agentId, agent = graph.agents.agent(agentId), isDemo = graph.session.isDemo))

    private data class Loads(
        val pullRequest: RemoteLoad<PullRequestView>,
        val artifacts: RemoteLoad<List<Artifact>>,
        val usage: RemoteLoad<AgentUsage>,
        val browser: RepoBrowserState,
        val controls: ConversationControls,
    )

    init {
        // The account's queue is kept current while the panel lives, like while the chat's screen does.
        graph.steering.attach(agentId)
    }

    override fun onCleared() {
        graph.steering.detach(agentId)
        super.onCleared()
    }

    // -- the chat's controls on the account (Extended mode) -----------------------------------------------------------

    /** Each answers what to tell the reader — the account's outcome, or nothing — or fails with the reason; see [SteeringRepository]. */
    suspend fun answerQuestion(callId: String, answers: List<ToolPayload.Question.Answer>): Result<String?> = graph.steering.answerQuestion(agentId, callId, answers).map { it.message }
    suspend fun steer(text: String): Result<String?> = graph.steering.steer(agentId, text).map { it.message }
    suspend fun pauseRun(): Result<String?> = graph.steering.pause(agentId).map { "Paused; resume when you're ready." }
    suspend fun resumeRun(): Result<String?> = graph.steering.resume(agentId).map { "Resumed." }
    suspend fun stopRun(): Result<String?> = graph.conversations.cancelActiveRun(agentId).map { null }
    suspend fun wake(): Result<String?> = graph.steering.wake(agentId).map { if (it) "Waking the agent's machine." else "The machine was already awake." }
    suspend fun cancelToolCall(callId: String): Result<String?> = graph.steering.cancelToolCall(agentId, callId).map { if (it) "Stopping that step." else "That step had already finished." }
    suspend fun refreshQueue() = graph.steering.refreshQueue(agentId)
    suspend fun queueSendNow(followupId: String): Result<String?> = graph.steering.submitPendingNow(agentId, followupId).map { null }
    suspend fun queueDelete(followupId: String): Result<String?> = graph.steering.deletePending(agentId, followupId).map { null }
    suspend fun queueMove(followupId: String, up: Boolean): Result<String?> = graph.steering.movePending(agentId, followupId, up).map { null }
    suspend fun queueUpdate(followupId: String, text: String): Result<String?> =
        graph.steering.updatePending(agentId, followupId, text).map { null }.also { graph.steering.markEditing(agentId, followupId, editing = false) }
    suspend fun queueMarkEditing(followupId: String, editing: Boolean): Result<String?> = graph.steering.markEditing(agentId, followupId, editing).map { null }
    suspend fun queueSteerNow(followupId: String): Result<String?> = graph.steering.promotePending(agentId, followupId).map { it.message }

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
