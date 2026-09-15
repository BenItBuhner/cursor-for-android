package com.cursorforandroid.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.local.PreferencesStore.ComposerDefaults
import com.cursorforandroid.data.repo.FailedLaunch
import com.cursorforandroid.data.repo.LaunchIdempotency
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.data.repo.SlashScope
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.BranchOption
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.KnownBranches
import com.cursorforandroid.domain.KnownDevices
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelResolution
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.autoOption
import com.cursorforandroid.domain.named
import com.cursorforandroid.domain.RecentRepositories
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val reposUnavailable: Boolean = false,
    /** `GET /v1/models` failed and nothing is cached; the picker offers a retry. */
    val modelsUnavailable: Boolean = false,
    /** Model ids pinned in the picker, most recently pinned first. */
    val pinnedModelIds: List<String> = emptyList(),
) {
    val canLaunch: Boolean get() = (prompt.isNotBlank() || attachments.isNotEmpty()) && !isLaunching && (selectedRepo != null || noRepo)
    /** The `/` catalog this composer needs: the repository's at its branch, or the repository-less one. */
    val commandScope: SlashScope get() = selectedRepo?.takeIf { !noRepo }?.let { SlashScope.Repo(it.url, ref.trim()) } ?: SlashScope.None
    /**
     * The chip's text: the model's name alone; its parameters show in the picker, under the model, not here. Never
     * a bare "Model": until the catalog has answered, the chat would start on Auto — the configured default a
     * request without a `model` gets — and that is what the chip says.
     */
    val modelLabel: String get() = selectedModel?.displayName ?: AccountModel.AUTO_LABEL
    val deviceLabel: String get() = selectedDevice.label
    /** The device's repository as a picker entry — the catalogue's own row for it when the catalogue lists it. */
    val deviceRepository: Repository? get() = deviceRepoUrl?.let { url -> repositories.firstOrNull { it.isAt(url) } ?: Repository(url) }
    /** Nothing written, nothing attached and nothing on its way out: a draft that comes back may take the composer. */
    val isFree: Boolean get() = prompt.isBlank() && attachments.isEmpty() && !isLaunching
}

class NewAgentViewModel(
    private val graph: AppGraph,
    /** How long typing settles before the draft is written to disk; shortened in tests. */
    private val draftSaveDelayMs: Long = DRAFT_SAVE_DELAY_MS,
) : ViewModel() {

    private val _state = MutableStateFlow(NewAgentUiState())
    val state: StateFlow<NewAgentUiState> = _state.asStateFlow()

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
     * Rotated once a draft has been handed over, so an identical prompt sent again on purpose gets its own agent. A
     * draft that comes back from a failed launch brings the nonce it went out under (see [takeBack]).
     */
    private var launchNonce: String = LaunchIdempotency.newNonce()

    /**
     * Drafts whose launch failed while something else was being written here, oldest first. Each comes back the
     * moment the composer is free — sent, or cleared — rather than over what the user is writing.
     */
    private val waiting = ArrayDeque<ReturnedDraft>()

    /** The agent list as last published; the branch picker's contents are derived from it (see [KnownBranches]). */
    private var agents: List<Agent> = emptyList()
    /** Live workers and pools from the fleet endpoints; merged with [agents] for the device picker. */
    private var liveDevices: List<DeviceOption> = emptyList()

    /** Where each attachment's bytes already are on disk, by attachment id, so a save rewrites none of them. */
    @Volatile private var savedImages: Map<String, DraftStore.Image> = emptyMap()
    /** One writer at a time, so a save that was already under way cannot land on top of a clear. */
    private val draftMutex = Mutex()

    init {
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
        // Whichever composer is showing takes a failed launch's draft back: the one that sent it may be long gone.
        viewModelScope.launch { graph.launcher.failures.collect { takeBack(it) } }
        viewModelScope.launch {
            // What was left unsent takes precedence over the last launch's choices: the repository and model it was
            // written against are resolved by the loaders below, exactly as remembered ones are.
            val loaded = restore() ?: graph.prefs.composerDefaults.first()
            defaults = loaded
            // Cloud comes back to the repository last launched there; before any launch on Cloud, to the last launch's.
            cloudRepo = RepoChoice(loaded.cloudRepoUrl ?: loaded.repoUrl, noRepo = false, ref = if (loaded.cloudRepoUrl == null || loaded.cloudRepoUrl == loaded.repoUrl) loaded.ref.orEmpty() else "")
            // A blank ref is the repository's default branch; nothing here knows what it is called (see [NewAgentUiState.ref]).
            // A machine restored from the last launch brings its repository with it once the device list says which.
            _state.update {
                it.copy(autoCreatePr = loaded.autoCreatePr, ref = loaded.ref.orEmpty(), selectedDevice = loaded.env, repoFollowsDevice = !loaded.env.isCloud, isLoadingDevices = true).withPickerLists()
            }
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
    }

    /**
     * The draft left over from a process that was killed, as the defaults to open on. Its images come back with it;
     * so does the nonce it was going to go out under, so sending it now still adopts anything the interrupted attempt
     * managed to create.
     */
    private suspend fun restore(): ComposerDefaults? {
        val draft = graph.drafts.read() ?: return null
        val images = draft.images.mapNotNull { stored -> graph.drafts.readImage(stored)?.let { stored to it } }
        // Decodes a bitmap per image, so not on the main thread.
        val attachments = withContext(Dispatchers.IO) { images.map { (stored, image) -> stored to PendingAttachment.of(image) } }
        savedImages = attachments.associate { (stored, attachment) -> attachment.id to stored }
        if (draft.nonce.isNotBlank()) launchNonce = draft.nonce
        _state.update {
            it.copy(
                prompt = draft.prompt,
                attachments = attachments.map { (_, attachment) -> attachment },
                noRepo = draft.noRepo,
                planMode = draft.planMode,
            ).withExclusiveModes()
        }
        val saved = graph.prefs.composerDefaults.first()
        return ComposerDefaults(
            repoUrl = draft.repoUrl,
            ref = draft.ref,
            modelId = draft.modelId,
            modelParams = draft.modelParams,
            autoCreatePr = draft.autoCreatePr,
            modelChosen = draft.modelChosen,
            // The draft records what was typed, not where it would run: the device stays the last launch's.
            env = saved.env,
            cloudRepoUrl = saved.cloudRepoUrl,
            // A draft being restored is as fresh as now: its model stands over the account's newest chat's.
            modelChosenAtMillis = if (draft.modelChosen) AppClock.now() else 0L,
        )
    }

    /** What is on screen, as the draft it would be restored from; an empty composer has no draft to keep. */
    private suspend fun save() = draftMutex.withLock {
        val s = _state.value
        if (s.prompt.isBlank() && s.attachments.isEmpty()) {
            if (savedImages.isNotEmpty() || graph.drafts.read() != null) {
                savedImages = emptyMap()
                graph.drafts.clear()
            }
            return@withLock
        }
        val stored = s.attachments.mapNotNull { a -> (savedImages[a.id] ?: graph.drafts.writeImage(a.image))?.let { a.id to it } }
        savedImages = stored.toMap()
        graph.drafts.write(
            DraftStore.Draft(
                prompt = s.prompt,
                images = stored.map { it.second },
                repoUrl = if (s.noRepo) null else s.selectedRepo?.url,
                noRepo = s.noRepo,
                ref = s.ref,
                modelId = s.selectedModel?.id,
                modelParams = s.selectedVariant?.params?.associate { it.id to it.value } ?: emptyMap(),
                // A model picked here comes back as the draft's; a default does not, and is derived afresh.
                modelChosen = modelPicked,
                autoCreatePr = s.autoCreatePr,
                planMode = s.planMode,
                nonce = launchNonce,
            ),
        )
    }

    private suspend fun forgetDraft() = draftMutex.withLock {
        savedImages = emptyMap()
        graph.drafts.clear()
    }

    /** Everything the draft is written from: what is on screen changes constantly, this only when the draft does. */
    private fun NewAgentUiState.draftFields(): List<Any?> = listOf(
        prompt,
        attachments.map { it.id },
        selectedRepo?.url,
        noRepo,
        ref,
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
        return (if (already) this else withRepo(repo)).copy(repoFollowsDevice = true, deviceRepoUrl = url)
    }

    /**
     * Where the next chat runs. A machine or pool brings its repository with it: the repository the worker is
     * checked out at (see [DeviceOption.repoUrl]) becomes the selection and the branch list refreshes for it, since
     * Cursor runs a machine only in a checkout of the requested repository (a request for another is refused, never
     * run on the wrong checkout). Coming back to Cloud restores what was chosen there. A device that pins no
     * repository — an any-repo pool, a machine nothing has described — keeps a pick made here, while a repository
     * that was the previous device's goes back to the Cloud one.
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
                next.deviceRepoUrl != null -> next
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

    fun setPrompt(value: String) {
        _state.update { it.copy(prompt = value, error = null).withExclusiveModes() }
        restoreWaitingIfFree()
    }
    fun addAttachments(items: List<PendingAttachment>) = _state.update { it.copy(attachments = (it.attachments + items).take(PromptImage.MAX_COUNT), error = null) }
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
        ).withExclusiveModes()
    }
    fun removeAttachment(item: PendingAttachment) {
        _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == item.id }) }
        restoreWaitingIfFree()
    }
    fun reportError(message: String) = _state.update { it.copy(error = message) }
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
            .onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
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
     * the draft comes back here (see [takeBack]).
     */
    fun launch(onOpen: (agentId: String) -> Unit) {
        val s = _state.value
        if (!s.canLaunch) return
        val nonce = launchNonce
        // Held only for as long as the draft takes to pack and put on screen, so a second tap cannot send it twice.
        _state.update { it.copy(isLaunching = true, error = null) }
        viewModelScope.launch {
            val remembered = defaults?.takeIf { it.modelChosen }
            val draft = LaunchRequest(
                prompt = s.prompt.trim(),
                images = s.attachments.map { it.image },
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
            )
            // Same draft, same id: retrying after a timeout or a cancel adopts the agent the first attempt may have
            // created instead of launching a duplicate. It is also what the chat is shown under before the server answers.
            val agentId = withContext(Dispatchers.Default) { LaunchIdempotency.agentId(draft, nonce) }
            val request = draft.copy(agentId = agentId)
            graph.launcher.launch(request, s.modelLabel, nonce)
            onOpen(agentId)
            // The draft is on its way, whatever becomes of it: the next one gets its own id, and the choices this one
            // was made with are what the composer restores next time. Only once they are saved does it report itself free.
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
            _state.update { it.copy(isLaunching = false, prompt = "", attachments = emptyList()) }
            // The draft is the launcher's now; what is kept on disk is whatever is written next.
            forgetDraft()
            restoreWaitingIfFree()
        }
    }

    /**
     * A launch did not go through: its draft comes back here to be sent again — with the reason, unless the chat was
     * stopped on purpose — and, under the nonce it went out with, an unchanged retry keeps the chat's id. A composer
     * already being written into is not overwritten: the draft waits, and comes back the moment the composer is free.
     */
    private suspend fun takeBack(failed: FailedLaunch) {
        val images = failed.request.images
        // The strip's thumbnails are decoded off the main thread, as they were when the images were picked.
        val attachments = if (images.isEmpty()) emptyList() else withContext(Dispatchers.IO) { images.map(PendingAttachment::of) }
        waiting += ReturnedDraft(failed, attachments)
        restoreWaitingIfFree()
    }

    private fun restoreWaitingIfFree() {
        if (waiting.isEmpty() || !_state.value.isFree) return
        val draft = waiting.removeFirst()
        launchNonce = draft.failed.nonce
        // A model the list has, or a launch that sent none: the pick is settled, and a list arriving later
        // re-resolves it rather than the remembered one.
        if (_state.value.resolvesModelOf(draft.failed.request)) modelPicked = true
        _state.update { it.restored(draft) }
    }

    /**
     * The composer as it was when [draft] went out — its text, its images, the choices it was made with — and why it
     * is back. A repository or model the lists no longer offer leaves the current pick; the saved defaults, recorded
     * when the draft was sent, restore it when the list next arrives.
     */
    private fun NewAgentUiState.restored(draft: ReturnedDraft): NewAgentUiState {
        val request = draft.failed.request
        val repo = request.repoUrl?.let { url -> repositories.firstOrNull { it.url == url } }
        val model = modelFor(request)
        val modelResolved = resolvesModelOf(request)
        // A launch that sent no model was going to run on Auto; that is what comes back checked.
        val auto = models.autoOption() ?: models.firstOrNull()
        return copy(
            prompt = request.prompt,
            attachments = draft.attachments,
            error = draft.failed.reason,
            noRepo = request.repoUrl == null,
            selectedRepo = repo ?: selectedRepo,
            ref = request.ref ?: "",
            selectedModel = if (modelResolved) model ?: auto else selectedModel,
            selectedVariant = when {
                !modelResolved -> selectedVariant
                model != null -> model.variantWithParams(request.modelParams.associate { it.id to it.value }) ?: model.defaultVariant
                else -> auto?.defaultVariant
            },
            autoCreatePr = request.autoCreatePr,
            planMode = request.planMode,
            selectedDevice = request.env,
            // The draft's repository and device went out together: the repository is the draft's, whatever the device pins.
            repoFollowsDevice = false,
        ).withExclusiveModes().withPickerLists()
    }

    private fun NewAgentUiState.modelFor(request: LaunchRequest): ModelOption? = request.modelId?.let { id -> models.firstOrNull { it.id == id } }

    /** True when [request]'s model is one the current list has, or the launch sent none, so the composer can show it as picked. */
    private fun NewAgentUiState.resolvesModelOf(request: LaunchRequest): Boolean = request.modelId == null || modelFor(request) != null

    /** A failed launch's draft with its images ready for the strip again. */
    private class ReturnedDraft(val failed: FailedLaunch, val attachments: List<PendingAttachment>)

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NewAgentViewModel(graph) as T
    }

    private companion object {
        const val DRAFT_SAVE_DELAY_MS = 400L
    }
}
