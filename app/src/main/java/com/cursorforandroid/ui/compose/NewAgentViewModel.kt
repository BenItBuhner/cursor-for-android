package com.cursorforandroid.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.PreferencesStore.ComposerDefaults
import com.cursorforandroid.data.repo.LaunchCancelledException
import com.cursorforandroid.data.repo.LaunchIdempotency
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.BranchOption
import com.cursorforandroid.domain.KnownBranches
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.components.PendingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NewAgentUiState(
    val prompt: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val repositories: List<Repository> = emptyList(),
    val selectedRepo: Repository? = null,
    val noRepo: Boolean = false,
    /** The branch (or commit) the agent starts from; blank leaves it to the repository's default branch. */
    val ref: String = "main",
    /** What the branch picker lists for [selectedRepo]: the branches its agents started from or pushed, most recent first. */
    val branches: List<BranchOption> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val selectedModel: ModelOption? = null,
    val selectedVariant: ModelVariant? = null,
    val autoCreatePr: Boolean = false,
    val planMode: Boolean = false,
    val isLaunching: Boolean = false,
    val isLoadingRepos: Boolean = false,
    val isLoadingModels: Boolean = false,
    val error: String? = null,
    val reposUnavailable: Boolean = false,
    /** `GET /v1/models` failed and nothing is cached; the picker offers a retry and "Default" keeps working. */
    val modelsUnavailable: Boolean = false,
) {
    val canLaunch: Boolean get() = (prompt.isNotBlank() || attachments.isNotEmpty()) && !isLaunching && (selectedRepo != null || noRepo)
    val modelLabel: String get() = selectedModel?.labelFor(selectedVariant) ?: "Default model"
}

class NewAgentViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(NewAgentUiState())
    val state: StateFlow<NewAgentUiState> = _state.asStateFlow()

    /** The last launch's choices, applied the first time the model list arrives (which may be after a retry). */
    private var defaults: ComposerDefaults? = null
    private var modelSelectionResolved = false

    private var launchJob: Job? = null
    /** Rotated after each successful launch so an identical prompt sent again on purpose gets its own agent. */
    private var launchNonce: String = LaunchIdempotency.newNonce()

    /** The agent list as last published; the branch picker's contents are derived from it (see [KnownBranches]). */
    private var agents: List<Agent> = emptyList()

    init {
        viewModelScope.launch {
            // The list is already on screen (restored from disk, then refreshed) by the time the composer shows, and
            // every later page, launch or finished run may teach the picker a branch.
            graph.agents.state.collect { s ->
                agents = s.agents
                _state.update { it.withBranches() }
            }
        }
        viewModelScope.launch {
            val loaded = graph.prefs.composerDefaults.first()
            defaults = loaded
            _state.update { it.copy(autoCreatePr = loaded.autoCreatePr, ref = loaded.ref ?: "main") }
            // Both catalogs were saved by the previous session: they are adopted below before either network call
            // is made, and the fetches only revalidate them.
            graph.catalog.restoreFromCache()
            // Independent endpoints, and /v1/repositories alone can take tens of seconds: never queue one behind the other.
            launch { loadRepositories(loaded.repoUrl) }
            launch { loadModels() }
        }
    }

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
            val selected = s.selectedRepo ?: repos.firstOrNull { it.url == preferredUrl } ?: repos.firstOrNull()
            s.copy(repositories = repos, selectedRepo = selected, reposUnavailable = false).withBranches()
        }
    }

    private fun NewAgentUiState.withBranches(): NewAgentUiState {
        val repo = selectedRepo?.takeIf { !noRepo } ?: return copy(branches = emptyList())
        return copy(branches = KnownBranches.forRepository(agents, repo.url))
    }

    private suspend fun loadModels(force: Boolean = false) {
        // The saved list shows right away (the picker spins in its header while it is revalidated).
        if (_state.value.models.isEmpty()) {
            graph.catalog.models.value.takeIf { it.isNotEmpty() }?.let { saved -> _state.update { it.withModels(saved) } }
        }
        _state.update { it.copy(isLoadingModels = true) }
        graph.catalog.loadModels(force)
            .onSuccess { models -> _state.update { it.withModels(models) } }
            .onFailure { _state.update { it.copy(isLoadingModels = false, modelsUnavailable = it.models.isEmpty()) } }
    }

    /**
     * Adopts a freshly loaded model list. A selection already made on this screen is re-resolved against the new
     * instances; otherwise the last launch's choice is restored — "Default" stays "Default", and a variant is matched
     * on its exact parameters, so a parameter-less variant is not swapped for the model's default one — and a
     * user who has never picked anything starts on the first recommended model. A list that lacks the wanted model
     * (a saved copy that predates it) leaves the choice unresolved, so the fresh list restores it rather than the
     * stand-in.
     */
    private fun NewAgentUiState.withModels(models: List<ModelOption>): NewAgentUiState {
        val remembered = defaults
        val (wantedId, wantedParams) = when {
            modelSelectionResolved -> selectedModel?.id to selectedVariant?.params?.associate { it.id to it.value }
            remembered?.modelChosen == true -> remembered.modelId to remembered.modelParams
            else -> models.firstOrNull()?.id to null
        }
        val model = wantedId?.let { id -> models.firstOrNull { it.id == id } ?: models.firstOrNull() }
        val variant = model?.let { m -> wantedParams?.let(m::variantWithParams) ?: m.defaultVariant }
        modelSelectionResolved = wantedId == null || model?.id == wantedId
        return copy(models = models, selectedModel = model, selectedVariant = variant, isLoadingModels = false, modelsUnavailable = false)
    }

    fun setPrompt(value: String) = _state.update { it.copy(prompt = value, error = null) }
    fun addAttachments(items: List<PendingAttachment>) = _state.update { it.copy(attachments = (it.attachments + items).take(PromptImage.MAX_COUNT), error = null) }
    fun removeAttachment(item: PendingAttachment) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == item.id }) }
    fun reportError(message: String) = _state.update { it.copy(error = message) }
    /**
     * Switching repositories keeps the branch only when the new one is known to have it; otherwise the choice
     * belonged to the previous repository (a `cursor/…` branch, typically) and the agent starts from the default branch.
     */
    fun selectRepo(repo: Repository?) = _state.update { s ->
        val sameRepo = repo != null && !s.noRepo && repo.url == s.selectedRepo?.url
        val next = s.copy(selectedRepo = repo, noRepo = repo == null).withBranches()
        val keepRef = sameRepo || repo == null || s.ref.isBlank() || next.branches.any { it.name == s.ref.trim() }
        if (keepRef) next else next.copy(ref = "")
    }
    fun setRef(value: String) = _state.update { it.copy(ref = value) }
    fun selectModel(model: ModelOption?, variant: ModelVariant?) {
        // An explicit pick settles the selection: a list arriving afterwards re-resolves it, never the remembered one.
        modelSelectionResolved = true
        _state.update { it.copy(selectedModel = model, selectedVariant = variant ?: model?.defaultVariant) }
    }
    fun setAutoCreatePr(value: Boolean) = _state.update { it.copy(autoCreatePr = value) }
    fun setPlanMode(value: Boolean) = _state.update { it.copy(planMode = value) }

    fun refreshRepositories() = viewModelScope.launch {
        _state.update { it.copy(isLoadingRepos = true) }
        graph.catalog.loadRepositories(force = true)
            .onSuccess { applyRepos(it, _state.value.selectedRepo?.url) }
            .onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
        _state.update { it.copy(isLoadingRepos = false) }
    }

    fun refreshModels() = viewModelScope.launch { loadModels(force = true) }

    /**
     * Sends the draft. The chat opens through [onOpen] as soon as its prompt is on screen — before the server has
     * answered — so sending feels immediate; the request completes behind it. Should it fail, or be stopped from the
     * chat, [onFailed] takes the user back here, where the draft is still intact and, for a failure, the reason shows.
     */
    fun launch(onOpen: (agentId: String) -> Unit, onFailed: (agentId: String) -> Unit) {
        val s = _state.value
        if (!s.canLaunch) return
        launchJob = viewModelScope.launch {
            _state.update { it.copy(isLaunching = true, error = null) }
            val draft = LaunchRequest(
                prompt = s.prompt.trim(),
                images = s.attachments.map { it.image },
                repoUrl = if (s.noRepo) null else s.selectedRepo?.url,
                ref = s.ref.trim().ifBlank { null },
                modelId = s.selectedModel?.id,
                modelParams = s.selectedVariant?.params ?: emptyList(),
                autoCreatePr = s.autoCreatePr,
                planMode = s.planMode,
                mcpServers = graph.mcpServers.enabled(),
            )
            // Same draft, same id: retrying after a timeout or a cancel adopts the agent the first attempt may have
            // created instead of launching a duplicate. It is also what the chat is shown under before the server answers.
            val agentId = withContext(Dispatchers.Default) { LaunchIdempotency.agentId(draft, launchNonce) }
            val request = draft.copy(agentId = agentId)
            graph.conversations.launch(request, s.modelLabel, onStaged = { onOpen(agentId) }).fold(
                onSuccess = {
                    // The agent exists now: a cancel arriving this late must not strand it behind an intact draft, and
                    // the composer only reports the launch done once the defaults it will restore next time are saved.
                    withContext(NonCancellable) {
                        launchNonce = LaunchIdempotency.newNonce()
                        graph.prefs.setComposerDefaults(
                            repoUrl = request.repoUrl,
                            // Blank is a choice too (the repository's default branch), saved as such so it is not
                            // mistaken for a first launch and replaced with "main" next time.
                            ref = request.ref ?: "",
                            modelId = request.modelId,
                            params = request.modelParams.associate { p: ModelParam -> p.id to p.value },
                            autoCreatePr = request.autoCreatePr,
                        )
                        _state.update { it.copy(isLaunching = false, prompt = "", attachments = emptyList()) }
                    }
                },
                onFailure = { t ->
                    // Stopped on purpose from the chat: nothing to explain, the draft is simply back.
                    _state.update { it.copy(isLaunching = false, error = if (t is LaunchCancelledException) null else t.userMessage()) }
                    onFailed(agentId)
                },
            )
        }
    }

    /**
     * Abandons a launch that is taking too long, request included. The draft stays in the composer so it can be
     * sent again; the chat it had opened is taken down by the repository.
     */
    fun cancelLaunch() {
        launchJob?.cancel()
        launchJob = null
        _state.update { it.copy(isLaunching = false) }
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NewAgentViewModel(graph) as T
    }
}
