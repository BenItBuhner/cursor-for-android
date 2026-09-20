package com.cursorforandroid.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.local.PreferencesStore.ComposerDefaults
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.AttachmentUploads
import com.cursorforandroid.data.repo.LaunchCancelledException
import com.cursorforandroid.data.repo.LaunchIdempotency
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.data.repo.MachineStartRefusedException
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.data.repo.SlashScope
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.BranchOption
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.KnownBranches
import com.cursorforandroid.domain.KnownDevices
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelResolution
import com.cursorforandroid.domain.ModelSlugs
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.attachmentOnlyText
import com.cursorforandroid.domain.autoOption
import com.cursorforandroid.domain.named
import com.cursorforandroid.domain.RecentRepositories
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.components.FileUploadState
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.components.withinSlots
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class NewAgentUiState(
    val prompt: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    /** Files of any type (Extended mode); a launch with any goes through the account's start, on Cursor's cloud only. */
    val files: List<PendingFile> = emptyList(),
    /** Where each attached file's upload stands, by [PendingFile.id], from the moment it is attached. */
    val fileUploads: Map<String, FileUploadState> = emptyMap(),
    /** Why send is held — "Uploading 2 of 3…" while a file is still going up — or null. */
    val uploadHint: String? = null,
    /** Whether the "+" menu offers Files: the `promptFiles` capability, off in the default mode and the demo. */
    val canAttachFiles: Boolean = false,
    val repositories: List<Repository> = emptyList(),
    /**
     * Repositories an agent has been active in within the last week, newest first, at most ten. Empty when nothing
     * has been touched recently — the picker then lists the catalogue alone, without a recent block.
     */
    val recentRepositories: List<Repository> = emptyList(),
    val selectedRepo: Repository? = null,
    val noRepo: Boolean = false,
    /**
     * The branch (or commit) the agent starts from; blank leaves it to the repository's default branch, which is
     * where a chat starts until a launch has recorded a choice. Nothing here knows what a repository's default
     * branch is called — `GET /v1/repositories` returns bare URLs — so it is never guessed at.
     */
    val ref: String = "",
    /** What the branch picker lists for [selectedRepo]: the branches its agents started from or pushed, most recent first. */
    val branches: List<BranchOption> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val selectedModel: ModelOption? = null,
    val selectedVariant: ModelVariant? = null,
    val autoCreatePr: Boolean = false,
    val planMode: Boolean = false,
    /** Where the next chat runs; Cloud until the user picks a machine or team pool. */
    val selectedDevice: DeviceTarget = DeviceTarget.Cloud,
    /** Cloud, then live / harvested machines and pools. Always contains Cloud. */
    val devices: List<DeviceOption> = listOf(KnownDevices.cloud),
    val isLoadingDevices: Boolean = false,
    /**
     * The repository the selected device is checked out at (see [DeviceOption.repoUrl]): what the repository
     * selector defaults to on a machine or pool, since Cursor runs a machine only in a checkout of the requested
     * repository. Null on Cloud, on an any-repo worker or pool, and for a device nothing has said anything about.
     */
    val deviceRepoUrl: String? = null,
    /**
     * True while [selectedRepo] is the device's own ([deviceRepoUrl]) rather than a pick made here: a machine
     * whose checkout changes moves the selection with it, and going back to Cloud leaves it behind.
     */
    val repoFollowsDevice: Boolean = false,
    /**
     * True from the tap on Send until the chat is on screen — the moment it takes to pack the draft, not the time the
     * server takes to answer, which the composer no longer waits for.
     */
    val isLaunching: Boolean = false,
    val isLoadingRepos: Boolean = false,
    val isLoadingModels: Boolean = false,
    val error: String? = null,
    /** With [error], the call the account refused and what it answered: the composer then shows it as a compact notice with an `Asked:` line. */
    val errorAsked: String? = null,
    val reposUnavailable: Boolean = false,
    /** `GET /v1/models` failed and nothing is cached; the picker offers a retry. */
    val modelsUnavailable: Boolean = false,
    /** Model ids pinned in the picker, most recently pinned first. */
    val pinnedModelIds: List<String> = emptyList(),
) {
    /** Something to send, nothing on its way out, a destination, and no attached file still going up. */
    val canLaunch: Boolean get() = (prompt.isNotBlank() || attachments.isNotEmpty() || files.isNotEmpty()) && !isLaunching && (selectedRepo != null || noRepo) && uploadHint == null
    /** The `/` catalog this composer needs: the repository's at its branch, or the repository-less one. */
    val commandScope: SlashScope get() = selectedRepo?.takeIf { !noRepo }?.let { SlashScope.Repo(it.url, ref.trim()) } ?: SlashScope.None
    /**
     * The chip's text: the model's name alone; its parameters show in the picker, under the model, not here. Never
     * a bare "Model": until the catalog has answered, the chat would start on Auto — the configured default a
     * request without a `model` gets — and that is what the chip says.
     */
    val modelLabel: String get() = selectedModel?.displayName ?: AccountModel.AUTO_LABEL
    val deviceLabel: String get() = selectedDevice.label
    /**
     * The source chip's text: the repository's short name, or — with none chosen — what the web composer calls the
     * same choice, "Start from scratch"; "Repository" only while there is neither yet.
     */
    val repoLabel: String
        get() = when {
            noRepo -> START_FROM_SCRATCH
            selectedRepo != null -> selectedRepo.shortName
            isLoadingRepos -> "Loading…"
            else -> "Repository"
        }
    /** The device's repository as a picker entry — the catalogue's own row for it when the catalogue lists it. */
    val deviceRepository: Repository? get() = deviceRepoUrl?.let { url -> repositories.firstOrNull { it.isAt(url) } ?: Repository(url) }
    /** Something written or attached: what makes the composer's contents a draft worth keeping. */
    val hasContent: Boolean get() = prompt.isNotBlank() || attachments.isNotEmpty() || files.isNotEmpty()
    /** Nothing written, nothing attached and nothing on its way out: a draft that comes back may take the composer. */
    val isFree: Boolean get() = !hasContent && !isLaunching

    companion object {
        /** The web composer's name for a chat with no repository: the source the Agents Window offers beside the repositories. */
        const val START_FROM_SCRATCH = "Start from scratch"
    }
}

/**
 * The New Chat composer. What it holds is a draft ([DraftStore.Record], kept by [NewChatDrafts]): written as it
 * changes, listed in the sidebar once the composer is left, opened again from there with everything it was written
 * with — text, attachments, repository and branch, device, the model with every parameter, the switches. One draft is
 * open at a time ([draftId]); opening another, or starting afresh, leaves the one before as it was.
 */
class NewAgentViewModel(
    private val graph: AppGraph,
    /** How long typing settles before the draft is written to disk; shortened in tests. */
    private val draftSaveDelayMs: Long = DRAFT_SAVE_DELAY_MS,
    /**
     * The draft this composer had open when its process was ended, as the screen saved it: opened again rather than a
     * fresh one. Null — a first open, or the app started afresh — begins a new draft, the others waiting in the sidebar.
     */
    private val resume: String? = null,
    /** Which composer writes the drafts ([DraftStore.ORIGIN_COMPOSER] here; the quick composer names itself). */
    private val origin: String = DraftStore.ORIGIN_COMPOSER,
) : ViewModel() {

    private val _state = MutableStateFlow(NewAgentUiState())
    val state: StateFlow<NewAgentUiState> = _state.asStateFlow()

    private val _draftId = MutableStateFlow(resume ?: newDraftId())

    /** The draft this composer has open; the screen keeps it across a process death (see [resume]). */
    val draftId: StateFlow<String> = _draftId.asStateFlow()

    /**
     * What `/` offers for the selected repository and branch. The catalog follows the selection: the saved (or
     * built-in) list is there at once, the account service's answer replaces it, and switching repositories switches
     * lists. Shared while the composer is on screen; nothing is fetched for a pane nobody is looking at.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val commands: StateFlow<SlashCatalog> = _state.map { it.commandScope }.distinctUntilChanged()
        .flatMapLatest { scope ->
            channelFlow {
                launch { graph.slashCommands.load(scope) }
                graph.slashCommands.catalog(scope).collect { send(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.slashCommands.current(_state.value.commandScope))

    /** The last launch's choices, applied the first time the model list arrives (which may be after a retry). */
    private var defaults: ComposerDefaults? = null
    /**
     * A model was picked on this screen (or came back with a draft): the selection is settled, and a list arriving
     * afterwards re-resolves it by id. Until then the selection is the default, and it moves with what it is derived
     * from — the choice this device remembers, the account's newest chat — as either arrives (see [withModelSelection]).
     */
    private var modelPicked = false

    /**
     * The repository chosen on Cloud, kept while a machine or pool is selected: a device's repository is the
     * device's own, and Cloud comes back to this one (across restarts, the repository last launched on Cloud).
     */
    private var cloudRepo: RepoChoice? = null

    /** A repository selection as the composer holds it: the repository (or none) and the branch. */
    private data class RepoChoice(val repoUrl: String?, val noRepo: Boolean, val ref: String)

    /**
     * The draft's own, kept with it: rotated when a new draft begins, so an identical prompt sent again on purpose gets
     * its own agent, and brought back with a draft that is opened again — a failed launch's included (see [adopt]).
     */
    private var launchNonce: String = LaunchIdempotency.newNonce()

    /** When the open draft was first written; null until it has been. */
    private var createdAtMillis: Long? = null

    /** The agent list as last published; the branch picker's contents are derived from it (see [KnownBranches]). */
    private var agents: List<Agent> = emptyList()
    /** Live workers and pools from the fleet endpoints; merged with [agents] for the device picker. */
    private var liveDevices: List<DeviceOption> = emptyList()

    /** Where each attachment's bytes already are in the open draft's directory, by attachment id, so a save rewrites none of them. */
    @Volatile private var savedImages: Map<String, DraftStore.Image> = emptyMap()
    @Volatile private var savedFiles: Map<String, DraftStore.StoredFile> = emptyMap()
    /** One writer at a time, so a save that was already under way cannot land on top of a clear or a switch of draft. */
    private val draftMutex = Mutex()
    private val drafts: NewChatDrafts get() = graph.newChatDrafts
    private val draftSave: suspend () -> Unit = { save() }
    /**
     * The New Chat pane's composer is the one the sidebar talks to: it opens the sidebar's drafts and hears what becomes
     * of them. Another composer — the quick composer over the launcher — files its drafts beside the pane's and keeps to
     * its own.
     */
    private val ownsPane = origin == DraftStore.ORIGIN_COMPOSER
    /** True once the drafts on disk have been read and the one to open is open: only then does what is on screen stand for it. */
    @Volatile private var draftRestored = false
    /** The chat a launch waited out ([launch] with `awaitServer`) is in flight under, for [cancelLaunch]; null otherwise. */
    private var awaitedAgentId: String? = null

    init {
        if (ownsPane) {
            drafts.setOpen(_draftId.value)
            drafts.composerSave = draftSave
        }
        viewModelScope.launch {
            // The list is already on screen (restored from disk, then refreshed) by the time the composer shows, and
            // every later page, launch or finished run may teach the picker a branch.
            graph.agents.state.collect { s ->
                agents = s.agents
                // The account's records ride on the list too, and the newest chat's model is a new chat's default
                // until something is picked here (see [withModelSelection]).
                _state.update { it.withPickerLists().withModelSelection() }
            }
        }
        viewModelScope.launch {
            drafts.load()
            val loaded = graph.prefs.composerDefaults.first()
            defaults = loaded
            // Cloud comes back to the repository last launched there; before any launch on Cloud, to the last launch's.
            cloudRepo = RepoChoice(loaded.cloudRepoUrl ?: loaded.repoUrl, noRepo = false, ref = if (loaded.cloudRepoUrl == null || loaded.cloudRepoUrl == loaded.repoUrl) loaded.ref.orEmpty() else "")
            // A blank ref is the repository's default branch; nothing here knows what it is called (see [NewAgentUiState.ref]).
            // A machine restored from the last launch brings its repository with it once the device list says which.
            _state.update {
                it.copy(autoCreatePr = loaded.autoCreatePr, ref = loaded.ref.orEmpty(), selectedDevice = loaded.env, repoFollowsDevice = !loaded.env.isCloud, isLoadingDevices = true).withPickerLists()
            }
            // The draft this composer had open when its process was ended — or one the sidebar asked for before this
            // composer existed — stands over the last launch's choices: the repository and model it was written
            // against are resolved by the loaders below, as remembered ones are. One sent meanwhile is the chat's
            // now, and the composer starts a draft of its own.
            // The sidebar's asks — open a draft, start afresh, a draft deleted or back from a failed launch — in order,
            // from here: one heard earlier would have been undone by the defaults just applied.
            launch { drafts.requests.collect { handle(it) } }
            val asked = if (ownsPane) (drafts.takePending() as? NewChatDrafts.Request.Open)?.id else null
            val resumed = (asked ?: resume)?.let(drafts::record) ?: if (ownsPane) null else lastLeft()
            when {
                resumed == null -> Unit
                resumed.launchedAs != null -> draftMutex.withLock { switchTo(newDraftId()) }
                else -> draftMutex.withLock { adopt(resumed) }
            }
            draftRestored = true
            // Both catalogs were saved by the previous session: they are adopted below before either network call
            // is made, and the fetches only revalidate them.
            graph.catalog.restoreFromCache()
            // Independent endpoints, and /v1/repositories alone can take tens of seconds: never queue one behind the other.
            launch { loadRepositories(loaded.repoUrl) }
            launch { loadModels() }
            launch { loadDevices() }
            // Only once the draft is back does what is on screen start standing for it.
            launch(Dispatchers.Default) {
                // Once typing settles: writing a file per keystroke would be an odd way to make the app steadier.
                _state.map { it.draftFields() }.distinctUntilChanged().collectLatest {
                    delay(draftSaveDelayMs)
                    save()
                }
            }
        }
        viewModelScope.launch {
            graph.prefs.pinnedModelIds.collect { ids -> _state.update { it.copy(pinnedModelIds = ids) } }
        }
        viewModelScope.launch {
            // Files of any type ride the account's start alone: with the mode turned off under attached files, the
            // files come off rather than stay to refuse the launch.
            graph.extendedMode.capabilities.collect { caps ->
                val allowed = caps.promptFiles && !graph.session.isDemo
                if (!allowed) _state.value.files.forEach { graph.attachmentUploads.cancel(it.id) }
                _state.update { s -> if (allowed) s.copy(canAttachFiles = true) else s.copy(canAttachFiles = false, files = emptyList()) }
            }
        }
        viewModelScope.launch {
            // The chips follow the uploads that started when their files were attached; a completed one puts its
            // reference on the file, so the draft on disk sends what is already up after a restart.
            graph.attachmentUploads.states.collect { states ->
                _state.update { s ->
                    val files = s.files.map { f ->
                        val ref = (states[f.id] as? AttachmentUploads.Status.Done)?.file?.ref
                        if (ref != null && f.file.upload != ref) PendingFile(f.id, f.file.withUpload(ref), f.thumbnail) else f
                    }
                    s.copy(
                        files = files,
                        fileUploads = files.mapNotNull { f -> states[f.id]?.let { f.id to FileUploadState.of(it) } }.toMap(),
                        uploadHint = AttachmentUploads.hint(states, files.map { it.id }),
                    )
                }
            }
        }
    }

    /**
     * Puts [record] in the composer: its text, its images and files (read back from its directory; a file whose upload
     * had completed comes back with its reference and is not uploaded again), the repository and branch, the device,
     * the model with the parameters it was written with, the switches, and the nonce it would have gone out under — so
     * sending it now still adopts anything an interrupted attempt created. Called with [draftMutex] held.
     */
    private suspend fun adopt(record: DraftStore.Record) {
        val images = record.images.mapNotNull { stored -> graph.drafts.readImage(record.id, stored)?.let { stored to it } }
        // Decodes a bitmap per image, so not on the main thread.
        val attachments = withContext(Dispatchers.IO) { images.map { (stored, image) -> stored to PendingAttachment.of(image) } }
        // An image file's chip thumbnail is decoded on the way back, off the main thread like the strip's.
        val files = withContext(Dispatchers.IO) { record.files.mapNotNull { stored -> graph.drafts.readFile(record.id, stored)?.let { stored to PendingFile.of(it) } } }
        switchTo(record.id)
        savedImages = attachments.associate { (stored, attachment) -> attachment.id to stored }
        savedFiles = files.associate { (stored, file) -> file.id to stored }
        createdAtMillis = record.createdAtMillis
        launchNonce = record.nonce.ifBlank { LaunchIdempotency.newNonce() }
        files.forEach { (_, file) -> graph.attachmentUploads.start(file.id, file.file) }
        // A model picked for the draft is the draft's, re-resolved by id once the list is here; a default is derived afresh.
        modelPicked = record.modelChosen
        _state.update { s ->
            val repo = s.withRepoChoice(RepoChoice(record.repoUrl.takeUnless { record.noRepo }, record.noRepo, record.ref))
            repo.copy(
                prompt = record.prompt,
                attachments = attachments.map { (_, attachment) -> attachment },
                files = files.map { (_, file) -> file },
                autoCreatePr = record.autoCreatePr,
                planMode = record.planMode,
                // A draft 0.3.61 kept never said where it would run: it opens on the last launch's device, as it did.
                selectedDevice = record.device ?: s.selectedDevice,
                // A machine's repository is the checkout it reports now, not the one saved with the draft; a pick made
                // over it stays the draft's.
                repoFollowsDevice = when {
                    record.device == null -> s.repoFollowsDevice
                    record.device.isCloud -> false
                    else -> !record.repoPicked
                },
                error = record.error,
                errorAsked = record.errorAsked,
            ).withDraftModel(record).withExclusiveModes().withPickerLists().withModelSelection(settleOnAuto = false)
        }
    }

    /**
     * The draft's model as the selection, until the list says more: the list's own entry when it places the model
     * (any spelling of it, see [ModelSlugs.resolve]), else a stand-in with the id as saved, a readable name and the
     * parameters, which [withModelSelection] resolves when the list comes.
     */
    private fun NewAgentUiState.withDraftModel(record: DraftStore.Record): NewAgentUiState {
        if (!record.modelChosen) return this
        val id = record.modelId ?: return copy(selectedModel = models.autoOption() ?: models.firstOrNull(), selectedVariant = (models.autoOption() ?: models.firstOrNull())?.defaultVariant)
        models.named(id)?.let { listed -> return copy(selectedModel = listed, selectedVariant = listed.variantNearest(record.modelParams)) }
        ModelSlugs.resolve(models, id, record.modelParams)?.let { placed -> return copy(selectedModel = placed.model, selectedVariant = placed.variant) }
        val name = record.modelLabel?.takeIf { it.isNotBlank() && it != id } ?: ModelSlugs.readableName(models, id)
        return copy(selectedModel = ModelOption(id, name), selectedVariant = ModelVariant(name, record.modelParams, isDefault = false))
    }

    /**
     * The newest draft this kind of composer was left with and has not sent — what a quick composer, left without a
     * word, opens on next time — unless the New Chat pane has it open.
     */
    private fun lastLeft(): DraftStore.Record? =
        drafts.state.value.drafts.firstOrNull { it.origin == origin && it.launchedAs == null && it.id != drafts.open.value }

    /** The composer's open draft is [id] from now: what is on disk for it is its own, nothing of the previous one's. */
    private fun switchTo(id: String) {
        _draftId.value = id
        if (ownsPane) drafts.setOpen(id)
        savedImages = emptyMap()
        savedFiles = emptyMap()
        createdAtMillis = null
    }

    /**
     * What is on screen, as the draft it would be opened from. An emptied composer has no draft to keep: the user has
     * cleared it. A draft that has not changed is not written again, so opening one does not make it the newest.
     */
    private suspend fun save() = draftMutex.withLock {
        val s = _state.value
        val id = _draftId.value
        val existing = drafts.record(id)
        if (!s.hasContent) {
            savedImages = emptyMap()
            savedFiles = emptyMap()
            if (existing != null && existing.launchedAs == null) drafts.remove(id, byComposer = true)
            return@withLock
        }
        val stored = s.attachments.mapNotNull { a -> (savedImages[a.id] ?: graph.drafts.writeImage(id, a.image))?.let { a.id to it } }
        savedImages = stored.toMap()
        // A file already on disk is not written again; its record takes the reference its upload has settled on since.
        val storedFiles = s.files.mapNotNull { f -> (savedFiles[f.id]?.withRef(f.file.upload) ?: graph.drafts.writeFile(id, f.file))?.let { f.id to it } }
        savedFiles = storedFiles.toMap()
        val now = AppClock.now()
        val record = DraftStore.Record(
            id = id,
            createdAtMillis = createdAtMillis ?: existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
            origin = existing?.origin ?: origin,
            prompt = s.prompt,
            images = stored.map { it.second },
            files = storedFiles.map { it.second },
            repoUrl = if (s.noRepo) null else s.selectedRepo?.url,
            noRepo = s.noRepo,
            ref = s.ref,
            device = s.selectedDevice,
            repoPicked = !s.repoFollowsDevice && !s.selectedDevice.isCloud,
            modelId = s.selectedModel?.id,
            modelParams = s.selectedVariant?.params.orEmpty(),
            modelLabel = s.selectedModel?.displayName,
            // A model picked here comes back as the draft's; a default does not, and is derived afresh.
            modelChosen = modelPicked,
            autoCreatePr = s.autoCreatePr,
            planMode = s.planMode,
            nonce = launchNonce,
            launchedAs = existing?.launchedAs,
        )
        // Written into since its launch came back: the reason goes with the change; unchanged, it stays.
        if (record.sameContentAs(existing?.copy(error = null, errorAsked = null))) return@withLock
        createdAtMillis = record.createdAtMillis
        drafts.save(record)
    }

    /** What the sidebar asks: see [NewChatDrafts.Request]. */
    private suspend fun handle(request: NewChatDrafts.Request) {
        // Another composer hears only that its own draft was deleted from the sidebar.
        if (!ownsPane && request !is NewChatDrafts.Request.Deleted) return
        if (request is NewChatDrafts.Request.Open || request is NewChatDrafts.Request.Fresh) drafts.takePending()
        when (request) {
            is NewChatDrafts.Request.Open -> {
                if (request.id == _draftId.value) return
                val record = drafts.record(request.id)?.takeIf { it.launchedAs == null } ?: return
                save()
                draftMutex.withLock { adopt(record) }
            }
            NewChatDrafts.Request.Fresh -> if (_state.value.hasContent) startFresh()
            is NewChatDrafts.Request.Deleted -> if (request.id == _draftId.value) letGo()
            // A launch that came back is taken up by a composer with nothing in it; otherwise it waits in the sidebar.
            is NewChatDrafts.Request.Returned -> {
                if (!_state.value.isFree) return
                val record = drafts.record(request.id) ?: return
                draftMutex.withLock { adopt(record) }
            }
        }
    }

    /**
     * A fresh composer, on the last launch's choices, as a cold start opens one; what it held stays a draft of its own
     * in the sidebar.
     */
    private suspend fun startFresh() {
        save()
        val saved = graph.prefs.composerDefaults.first()
        draftMutex.withLock {
            switchTo(newDraftId())
            launchNonce = LaunchIdempotency.newNonce()
            modelPicked = false
            defaults = saved
            _state.update { s ->
                val base = s.copy(
                    prompt = "",
                    attachments = emptyList(),
                    files = emptyList(),
                    error = null,
                    errorAsked = null,
                    planMode = false,
                    autoCreatePr = saved.autoCreatePr,
                    selectedDevice = saved.env,
                    repoFollowsDevice = !saved.env.isCloud,
                )
                val repo = saved.repoUrl?.let { base.withRepoChoice(RepoChoice(it, noRepo = false, ref = saved.ref.orEmpty())) } ?: base
                repo.withPickerLists().withModelSelection()
            }
        }
    }

    /** The open draft was deleted from the sidebar: the composer is emptied onto a new draft, its choices kept. */
    private suspend fun letGo() = draftMutex.withLock {
        _state.value.files.forEach { graph.attachmentUploads.cancel(it.id) }
        switchTo(newDraftId())
        launchNonce = LaunchIdempotency.newNonce()
        _state.update { it.copy(prompt = "", attachments = emptyList(), files = emptyList(), error = null, errorAsked = null) }
    }

    override fun onCleared() {
        if (drafts.composerSave === draftSave) drafts.composerSave = null
        if (ownsPane && drafts.open.value == _draftId.value) drafts.setOpen(null)
        super.onCleared()
    }

    /** Everything the draft is written from: what is on screen changes constantly, this only when the draft does. */
    private fun NewAgentUiState.draftFields(): List<Any?> = listOf(
        prompt,
        attachments.map { it.id },
        // A completed upload is a change to the draft too: its reference is what a restart sends.
        files.map { it.id to it.file.upload?.uploadId },
        selectedRepo?.url,
        noRepo,
        ref,
        selectedDevice,
        repoFollowsDevice,
        selectedModel?.id,
        selectedVariant?.params?.map { it.id to it.value },
        autoCreatePr,
        planMode,
    )

    private suspend fun loadRepositories(preferredUrl: String?) {
        _state.update { it.copy(isLoadingRepos = true) }
        // The saved list, or one seeded from the agent list, shows right away; the fetch is skipped while it is fresh.
        val known = graph.catalog.repositories.value
        if (known.isNotEmpty()) applyRepos(known, preferredUrl)
        graph.catalog.loadRepositories().onSuccess { applyRepos(it, preferredUrl) }.onFailure {
            _state.update { s -> s.copy(reposUnavailable = s.repositories.isEmpty()) }
        }
        _state.update { it.copy(isLoadingRepos = false) }
    }

    private fun applyRepos(repos: List<Repository>, preferredUrl: String?) {
        _state.update { s ->
            // A selection made before the catalogue arrived — a device's repository, typically — becomes the
            // catalogue's own row for it, so the picker's check lands on the same entry.
            val selected = s.selectedRepo?.let { current -> repos.firstOrNull { it.url == current.url } ?: repos.firstOrNull { it.isAt(current.url) } ?: current }
                ?: repos.firstOrNull { it.url == preferredUrl }
                ?: preferredUrl?.let { url -> repos.firstOrNull { it.isAt(url) } }
                ?: repos.firstOrNull()
            s.copy(repositories = repos, selectedRepo = selected, reposUnavailable = false).withPickerLists()
        }
    }

    /** The devices first: the selected device may move the repository, and the branches are the repository's. */
    private fun NewAgentUiState.withPickerLists(): NewAgentUiState = withDevices().withBranches().withRecentRepos()

    private fun NewAgentUiState.withBranches(): NewAgentUiState {
        val repo = selectedRepo?.takeIf { !noRepo } ?: return copy(branches = emptyList())
        return copy(branches = KnownBranches.forRepository(agents, repo.url))
    }

    /**
     * The device list as the agent list and the fleet endpoints have it, and what it says about the selected device's
     * repository: a machine or pool whose repository is (or becomes) known moves a device-driven selection onto it —
     * the fleet's word arrives after the pick, and a restored machine's after the restart.
     */
    private fun NewAgentUiState.withDevices(): NewAgentUiState {
        val listed = KnownDevices.compose(agents, liveDevices, selectedDevice)
        val pinned = KnownDevices.repositoryOf(listed, selectedDevice)
        val next = copy(devices = listed, deviceRepoUrl = pinned)
        return if (pinned != null && repoFollowsDevice) next.following(pinned) else next
    }

    /**
     * Puts the device's repository [url] in the selection, as a pick of it would (see [withRepo]), and marks the
     * selection the device's. The catalogue's own row stands for it when the catalogue lists it.
     */
    private fun NewAgentUiState.following(url: String): NewAgentUiState {
        val repo = repositories.firstOrNull { it.isAt(url) } ?: Repository(url)
        val already = !noRepo && selectedRepo?.isAt(url) == true
        // A branch chosen for the repository this replaces says nothing about the machine's checkout.
        val replaced = noRepo || (selectedRepo != null && !already)
        val next = when {
            already -> this
            replaced -> withRepo(repo).copy(ref = "")
            else -> withRepo(repo)
        }
        return next.copy(repoFollowsDevice = true, deviceRepoUrl = url)
    }

    /**
     * Where the next chat runs. A machine or pool brings its repository with it: the repository the worker is
     * checked out at (see [DeviceOption.repoUrl]) becomes the selection and the branch list refreshes for it, since
     * Cursor runs a machine only in a checkout of the requested repository (a request for another is refused, never
     * run on the wrong checkout). The branch goes blank with it, as the desktop's pick of a worker leaves it: no
     * `startingRef` goes out until one is picked on the machine. Coming back to Cloud restores what was chosen there. A
     * device that pins no repository — an any-repo pool, a machine nothing has described — keeps a pick made here,
     * while a repository that was the previous device's goes back to the Cloud one.
     */
    fun selectDevice(device: DeviceTarget) {
        val before = _state.value
        // The same device again is no change: a repository picked over the device's stays picked.
        if (device == before.selectedDevice) return
        if (before.selectedDevice.isCloud) cloudRepo = before.repoChoice()
        val cloudChoice = cloudRepo?.takeIf { device.isCloud }
        _state.update { s ->
            // On a machine or pool the repository is the device's to set — now, or when the fleet endpoints answer.
            val next = s.copy(selectedDevice = device, repoFollowsDevice = !device.isCloud).withDevices()
            when {
                device.isCloud -> cloudChoice?.let { next.withRepoChoice(it) } ?: next
                // The device pins a repository: [withDevices] has already moved the selection onto it.
                next.deviceRepoUrl != null -> next.copy(ref = "")
                // The repository was the previous device's; it does not come along to one that pins none.
                s.repoFollowsDevice -> cloudRepo?.let { next.withRepoChoice(it) } ?: next
                else -> next
            }.withBranches().withRecentRepos()
        }
    }

    private fun NewAgentUiState.repoChoice(): RepoChoice = RepoChoice(selectedRepo?.takeIf { !noRepo }?.url, noRepo, ref)

    /**
     * The selection [choice] describes: its repository (the catalogue's row for it when listed) or none, and its
     * branch. A choice that named nothing — Cloud left before any repository was on screen — changes nothing.
     */
    private fun NewAgentUiState.withRepoChoice(choice: RepoChoice): NewAgentUiState {
        val restored = when {
            choice.noRepo -> withRepo(null)
            choice.repoUrl != null -> withRepo(repositories.firstOrNull { it.isAt(choice.repoUrl) } ?: Repository(choice.repoUrl))
            else -> return this
        }
        return restored.copy(ref = choice.ref)
    }

    /**
     * The selection with [repo] (null for no repository). Switching repositories keeps the branch only when the new
     * one is known to have it; otherwise the choice belonged to the previous repository (a `cursor/…` branch,
     * typically) and the agent starts from the default branch.
     */
    private fun NewAgentUiState.withRepo(repo: Repository?): NewAgentUiState {
        val sameRepo = repo != null && !noRepo && repo.url == selectedRepo?.url
        val next = copy(selectedRepo = repo, noRepo = repo == null).withBranches()
        val keepRef = sameRepo || repo == null || ref.isBlank() || next.branches.any { it.name == ref.trim() }
        return if (keepRef) next else next.copy(ref = "")
    }

    private fun NewAgentUiState.withRecentRepos(): NewAgentUiState =
        copy(recentRepositories = RecentRepositories.partition(repositories, agents, AppClock.now()).recent)

    private suspend fun loadModels(force: Boolean = false) {
        // The saved list shows right away (the picker spins in its header while it is revalidated).
        if (_state.value.models.isEmpty()) {
            graph.catalog.models.value.takeIf { it.isNotEmpty() }?.let { saved ->
                _state.update { it.withModels(saved, fallbackIfMissing = false) }
            }
        }
        _state.update { it.copy(isLoadingModels = true) }
        graph.catalog.loadModels(force)
            .onSuccess { models -> _state.update { it.withModels(models, fallbackIfMissing = true) } }
            .onFailure { _state.update { it.copy(isLoadingModels = false, modelsUnavailable = it.models.isEmpty()) } }
    }

    /**
     * Adopts a freshly loaded model list and settles the selection on it (see [withModelSelection]). [fallbackIfMissing]
     * is false for the saved copy of the catalog, which may predate the wanted model: the choice is then left
     * unresolved for the fresh list to restore, rather than stood in for.
     */
    private fun NewAgentUiState.withModels(models: List<ModelOption>, fallbackIfMissing: Boolean): NewAgentUiState =
        copy(models = models, isLoadingModels = false, modelsUnavailable = false).withModelSelection(settleOnAuto = fallbackIfMissing)

    /**
     * The selection against the current list. A pick made on this screen is re-resolved against the list's
     * instances by id — its variant on its exact parameters, so a parameter-less variant is not swapped for the
     * model's default one — and only a fresh list that no longer offers it moves it to Auto. Otherwise the default
     * is derived, in order (see [ModelResolution.forNewChat]): the newer of the model this device last launched with
     * or picked and the account's newest chat's model — what the desktop's picker would open on, in Extended mode,
     * where the account's records carry it — then Auto. The first row is never stood in for a wanted id: on the live
     * catalogue that row is Auto, which is what made every cold start look like the picker had reset.
     */
    private fun NewAgentUiState.withModelSelection(settleOnAuto: Boolean = true): NewAgentUiState {
        if (models.isEmpty()) return this
        val wanted = selectedModel?.takeIf { modelPicked }
        if (wanted != null) {
            // A stand-in restored from a draft in another spelling (a slug) is placed the way a chat's record is.
            if (models.named(wanted.id) == null) {
                ModelSlugs.resolve(models, wanted.id, selectedVariant?.params.orEmpty())?.let { placed ->
                    return copy(selectedModel = placed.model, selectedVariant = placed.variant)
                }
            }
            val model = models.named(wanted.id) ?: if (settleOnAuto) models.autoOption() ?: models.firstOrNull() ?: return this else return this
            val params = selectedVariant?.params?.associate { it.id to it.value }
            val variant = params?.takeIf { model.id == wanted.id }?.let(model::variantWithParams) ?: model.defaultVariant
            return copy(selectedModel = model, selectedVariant = variant)
        }
        val remembered = defaults?.takeIf { it.modelChosen && it.modelId != null }?.let {
            ModelResolution.Candidate.Remembered(it.modelId!!, it.modelParams, it.modelChosenAtMillis)
        }
        val candidates = listOfNotNull(remembered, ModelResolution.newestAccountModel(agents))
        val resolved = ModelResolution.forNewChat(models, candidates, settleOnAuto) ?: return this
        return copy(selectedModel = resolved.choice.model, selectedVariant = resolved.choice.variant)
    }

    fun setPrompt(value: String) = _state.update { it.copy(prompt = value, error = null, errorAsked = null).withExclusiveModes() }
    fun addAttachments(items: List<PendingAttachment>) = _state.update { it.copy(attachments = (it.attachments + items).take(PromptImage.MAX_COUNT), error = null, errorAsked = null) }
    /**
     * Drops a share into this composer: the incoming text is appended under whatever is already written, images
     * fill the remaining attachment slots, and a warning from the share (an unsupported file, too many images)
     * shows as the composer's error line.
     */
    fun applyShare(text: String, items: List<PendingAttachment>, warning: String? = null) = _state.update { s ->
        s.copy(
            prompt = ShareDraft.mergeText(s.prompt, text),
            attachments = (s.attachments + items).take(PromptImage.MAX_COUNT),
            error = warning,
            errorAsked = null,
        ).withExclusiveModes()
    }
    fun removeAttachment(item: PendingAttachment) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == item.id }) }
    /** Files of any type from the document picker (Extended mode); refused with a word when the mode is off. */
    fun addFiles(items: List<PendingFile>) {
        if (items.isEmpty()) return
        if (!_state.value.canAttachFiles) {
            reportError(AgentRepository.FILES_NEED_EXTENDED)
            return
        }
        var kept: List<PendingFile> = emptyList()
        _state.update { s -> s.copy(files = (s.files + items).withinSlots(imagesElsewhere = s.attachments.size).also { kept = it }, error = null, errorAsked = null) }
        // Up they go, the moment they are attached; the launch waits on nothing once they are.
        kept.filter { f -> items.any { it.id == f.id } }.forEach { graph.attachmentUploads.start(it.id, it.file) }
    }
    /** Takes a file off the draft: its upload, under way or done, is cancelled with it. */
    fun removeFile(item: PendingFile) {
        _state.update { s -> s.copy(files = s.files.filterNot { it.id == item.id }) }
        graph.attachmentUploads.cancel(item.id)
    }
    /** A file whose upload failed: the upload alone is tried again, in its chip. */
    fun retryFile(item: PendingFile) {
        if (_state.value.files.none { it.id == item.id }) return
        graph.attachmentUploads.retry(item.id)
    }
    fun reportError(message: String) = _state.update { it.copy(error = message, errorAsked = null) }
    fun dismissError() = _state.update { it.copy(error = null, errorAsked = null) }
    /**
     * A repository picked here (see [NewAgentUiState.withRepo] for the branch). On a machine or pool it is a pick
     * over the device's own repository — allowed, since a worker may serve more roots than the one it reports
     * (`--worker-dir` once per checkout) — and it stays until the device changes.
     */
    fun selectRepo(repo: Repository?) = _state.update { s -> s.withRepo(repo).copy(repoFollowsDevice = false).withRecentRepos() }
    fun setRef(value: String) = _state.update { it.copy(ref = value) }
    fun selectModel(model: ModelOption?, variant: ModelVariant?) {
        // An explicit pick settles the selection: a list arriving afterwards re-resolves it, never the remembered one.
        // There is no "Default" model; a null pick lands on Auto (the first catalog entry, failing an Auto row). The
        // pick is written at once so opening the app again — which creates a new composer — opens on this model.
        modelPicked = true
        val chosen = model ?: _state.value.models.let { it.autoOption() ?: it.firstOrNull() }
        val picked = variant ?: chosen?.defaultVariant
        _state.update { it.copy(selectedModel = chosen, selectedVariant = picked) }
        rememberModel(chosen?.id, picked)
    }
    fun togglePinnedModel(modelId: String) = viewModelScope.launch { graph.prefs.togglePinnedModel(modelId) }

    /** Writes the pick so the next composer — a new process, a new view model — opens on it. */
    private fun rememberModel(modelId: String?, variant: ModelVariant?) {
        val params = variant?.params?.associate { it.id to it.value } ?: emptyMap()
        val now = AppClock.now()
        defaults = (defaults ?: emptyDefaults()).copy(modelId = modelId, modelParams = params, modelChosen = true, modelChosenAtMillis = now)
        viewModelScope.launch { graph.prefs.rememberModel(modelId, params, now) }
    }

    private fun emptyDefaults() = ComposerDefaults(
        repoUrl = null,
        ref = null,
        modelId = null,
        modelParams = emptyMap(),
        autoCreatePr = false,
        modelChosen = false,
        env = DeviceTarget.Cloud,
    )

    fun setAutoCreatePr(value: Boolean) = _state.update { it.copy(autoCreatePr = value) }

    /** Plan mode and `/multitask` are one slot: asking for a plan takes the command out of the prompt. */
    fun setPlanMode(value: Boolean) = _state.update {
        it.copy(planMode = value, prompt = if (value) SlashCommands.remove(it.prompt, SlashCommands.MULTITASK) else it.prompt)
    }

    /**
     * The one-slot rule the other way round: a prompt that carries `/multitask` — typed, picked, shared in or restored —
     * puts plan mode off. A run is planned or fanned out to subagents, never asked for both.
     */
    private fun NewAgentUiState.withExclusiveModes(): NewAgentUiState =
        if (planMode && SlashCommands.has(prompt, SlashCommands.MULTITASK)) copy(planMode = false) else this

    fun refreshRepositories() = viewModelScope.launch {
        _state.update { it.copy(isLoadingRepos = true) }
        graph.catalog.loadRepositories(force = true)
            .onSuccess { applyRepos(it, _state.value.selectedRepo?.url) }
            .onFailure { t -> _state.update { it.copy(error = t.userMessage(), errorAsked = null) } }
        _state.update { it.copy(isLoadingRepos = false) }
    }

    fun refreshModels() = viewModelScope.launch { loadModels(force = true) }

    fun refreshDevices() = viewModelScope.launch { loadDevices() }

    private suspend fun loadDevices() {
        _state.update { it.copy(isLoadingDevices = true) }
        liveDevices = graph.catalog.devices.value
        _state.update { it.withPickerLists() }
        liveDevices = graph.catalog.loadDevices().getOrDefault(emptyList())
        _state.update { it.copy(isLoadingDevices = false).withPickerLists() }
    }

    /**
     * Sends the draft. The chat opens through [onOpen] as soon as its prompt is on screen — before the server has
     * answered — and the composer is clear from that moment on: the request completes in the launcher
     * ([com.cursorforandroid.data.repo.ChatLauncher]), on its own, so coming back here, or starting another chat,
     * meets an empty composer whatever the server has yet to say. Should the launch fail, or be stopped from the chat,
     * the draft comes back: into this composer if nothing has been written in it since, else to the sidebar, with the
     * reason and the nonce it went out under, so an unchanged retry keeps the chat's id (see [handle]).
     *
     * With [awaitServer] the chat is opened only once the server has created it: the composer stays busy meanwhile —
     * its send slot the ring, then Stop ([cancelLaunch]) — and a refusal leaves the draft where it is, with the reason
     * under it, without [onOpen] ever running. For a composer with nothing of the app behind it to show the chat in
     * (the quick composer over the launcher), where an optimistic open would be an app opened on a chat that may
     * never exist.
     */
    fun launch(onOpen: (agentId: String) -> Unit, awaitServer: Boolean = false) {
        val s = _state.value
        if (!s.canLaunch) return
        // A file rides the account's start, which this composer can only ask of Cursor's cloud (see AgentRepository.startWithFiles).
        if (s.files.isNotEmpty()) {
            if (!s.canAttachFiles) {
                reportError(AgentRepository.FILES_NEED_EXTENDED)
                return
            }
            if (s.selectedDevice.type == EnvType.POOL || s.selectedDevice.type == EnvType.MACHINE) {
                reportError(AgentRepository.FILES_NEED_CLOUD)
                return
            }
        }
        val nonce = launchNonce
        // Held only for as long as the draft takes to pack and put on screen, so a second tap cannot send it twice.
        _state.update { it.copy(isLaunching = true, error = null, errorAsked = null) }
        viewModelScope.launch {
            val remembered = defaults?.takeIf { it.modelChosen }
            val draft = LaunchRequest(
                // The account's start refuses a message with no text (`$0n`: "Cannot start Cloud Agent without a
                // message", images excepted), so a files-only draft says what it carries, as the follow-up composer does.
                prompt = s.prompt.trim().ifEmpty { if (s.files.isNotEmpty()) attachmentOnlyText(s.attachments.size, s.files.size) else "" },
                images = s.attachments.map { it.image },
                // Each file carries the reference its upload settled on when it was attached, so the start waits on no upload.
                files = s.files.map { it.file.withUpload(graph.attachmentUploads.ref(it.id) ?: it.file.upload) },
                repoUrl = if (s.noRepo) null else s.selectedRepo?.url,
                ref = s.ref.trim().ifBlank { null },
                // The last-used id goes out even when this list has not resolved it yet, so a send during a
                // catalog refresh does not silently launch (and then remember) Auto.
                modelId = s.selectedModel?.id ?: remembered?.modelId,
                modelParams = s.selectedVariant?.params
                    ?: remembered?.modelParams?.map { (id, value) -> ModelParam(id, value) }
                    ?: emptyList(),
                autoCreatePr = s.autoCreatePr,
                planMode = s.planMode,
                mcpServers = graph.mcpServers.enabled(),
                env = s.selectedDevice,
                // A machine that has dropped off the listing is still asked for by the worker it was last listed as.
                worker = s.selectedDevice.takeIf { it.type == EnvType.MACHINE }?.let { device ->
                    s.devices.firstOrNull { it.key == DeviceOption.keyOf(device) }?.worker ?: graph.catalog.lastSeenWorker(device)
                },
            )
            // Same draft, same id: retrying after a timeout or a cancel adopts the agent the first attempt may have
            // created instead of launching a duplicate. It is also what the chat is shown under before the server answers.
            val agentId = withContext(Dispatchers.Default) { LaunchIdempotency.agentId(draft, nonce) }
            val request = draft.copy(agentId = agentId)
            if (awaitServer) {
                // The draft stays this composer's until the server has it: on disk as it goes out, and on a refusal
                // where it is — under the nonce it went out with, so an unchanged retry keeps the chat's id and adopts
                // anything the attempt created — with the server's words under it; a stop on purpose says nothing.
                save()
                awaitedAgentId = agentId
                val outcome = try {
                    graph.launcher.launchAndAwait(request, s.modelLabel, nonce)
                } finally {
                    awaitedAgentId = null
                }
                outcome.exceptionOrNull()?.let { t ->
                    val reason = if (t is LaunchCancelledException) null else t.userMessage()
                    _state.update { it.copy(isLaunching = false, error = reason, errorAsked = (t as? MachineStartRefusedException)?.asked?.takeIf { reason != null }) }
                    return@launch
                }
                // Created: the draft is the chat's now. Not cancellable, as opening the chat finishes the screen this
                // composer belongs to, and a draft cleared on screen but left on disk would come back as new.
                withContext(NonCancellable) {
                    val sent = _draftId.value
                    draftMutex.withLock {
                        switchTo(newDraftId())
                        _state.update { it.copy(prompt = "", attachments = emptyList(), files = emptyList()) }
                    }
                    drafts.remove(sent, byComposer = true)
                }
            } else {
                // The draft is filed as it goes out and is the chat's from here: out of the sidebar before the chat's
                // row is listed, deleted once the server has it, back — with the reason — if the launch does not go
                // through. The composer moves on to a draft of its own in the same step as it empties, so nothing of
                // this one's can be saved again under the next.
                save()
                drafts.markLaunched(_draftId.value, agentId)
                draftMutex.withLock {
                    switchTo(newDraftId())
                    _state.update { it.copy(prompt = "", attachments = emptyList(), files = emptyList()) }
                }
                graph.launcher.launch(request, s.modelLabel, nonce)
            }
            onOpen(agentId)
            // The next draft gets its own id, and the choices this one was made with are what the composer restores
            // next time. Only once they are saved does it report itself free. Not cancellable: opening the chat may
            // finish the screen this composer belongs to (the quick composer over the launcher).
            withContext(NonCancellable) {
                launchNonce = LaunchIdempotency.newNonce()
                val now = AppClock.now()
                val params = request.modelParams.associate { p: ModelParam -> p.id to p.value }
                graph.prefs.setComposerDefaults(
                    repoUrl = request.repoUrl,
                    // Blank is a choice too (the repository's default branch), saved as such rather than removed.
                    ref = request.ref ?: "",
                    modelId = request.modelId,
                    params = params,
                    autoCreatePr = request.autoCreatePr,
                    env = request.env,
                    nowMillis = now,
                )
                // What this composer remembers is what the disk now says, so the next default is derived from the launch.
                defaults = (defaults ?: emptyDefaults()).copy(
                    repoUrl = request.repoUrl,
                    ref = request.ref ?: "",
                    modelId = request.modelId,
                    modelParams = params,
                    autoCreatePr = request.autoCreatePr,
                    modelChosen = true,
                    env = request.env,
                    cloudRepoUrl = if (request.env.isCloud) request.repoUrl else defaults?.cloudRepoUrl,
                    modelChosenAtMillis = now,
                )
                _state.update { it.copy(isLaunching = false) }
                // The files are the launch's now, by their references; a launch that fails brings them back with those.
                graph.attachmentUploads.forget(s.files.map { it.id })
            }
        }
    }

    /**
     * Stops a launch being waited out ([launch] with `awaitServer`) before the server has answered: the request is
     * abandoned and the draft stays without a word, as a chat stopped on purpose does. Nothing to stop is nothing.
     */
    fun cancelLaunch() {
        awaitedAgentId?.let { graph.conversations.cancelLaunch(it) }
    }

    /**
     * Writes the draft now rather than once typing settles: for a composer about to leave the screen for good — the
     * quick composer dismissed over the launcher — whose last keystrokes the debounce would otherwise lose with the
     * view model. Not cancellable: the write outlives the scope that asked for it. Nothing is written before the
     * draft on disk has been read, so an early leave cannot clear what it never showed.
     */
    fun keepDraft() {
        if (!draftRestored) return
        viewModelScope.launch { withContext(NonCancellable) { save() } }
    }

    /**
     * Throws the draft away — the text, the images, the files and their uploads, the copy on disk — as the quick
     * composer's Cancel does. The choices around it (repository, model, device) stay: they are the next chat's too.
     */
    fun discardDraft() {
        // A send still being waited out goes with it: a chat created after Cancel would open an app nobody asked for.
        cancelLaunch()
        val s = _state.value
        s.files.forEach { graph.attachmentUploads.cancel(it.id) }
        _state.update { it.copy(prompt = "", attachments = emptyList(), files = emptyList(), error = null, errorAsked = null) }
        // An emptied composer's save deletes the draft it had open, on disk too.
        viewModelScope.launch { withContext(NonCancellable) { save() } }
    }

    class Factory(
        private val graph: AppGraph,
        private val resume: String? = null,
        private val origin: String = DraftStore.ORIGIN_COMPOSER,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NewAgentViewModel(graph, resume = resume, origin = origin) as T
    }

    private companion object {
        const val DRAFT_SAVE_DELAY_MS = 400L

        fun newDraftId(): String = "draft-" + java.util.UUID.randomUUID()
    }
}
