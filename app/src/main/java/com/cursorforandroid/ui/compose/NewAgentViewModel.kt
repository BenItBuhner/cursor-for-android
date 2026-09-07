package com.cursorforandroid.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.PreferencesStore.ComposerDefaults
import com.cursorforandroid.data.repo.LaunchIdempotency
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.domain.Agent
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
    val ref: String = "main",
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

    init {
        viewModelScope.launch {
            val loaded = graph.prefs.composerDefaults.first()
            defaults = loaded
            _state.update { it.copy(autoCreatePr = loaded.autoCreatePr, ref = loaded.ref ?: "main") }
            // Independent endpoints, and /v1/repositories alone can take tens of seconds: never queue one behind the other.
            launch { loadRepositories(loaded.repoUrl) }
            launch { loadModels() }
        }
    }

    private suspend fun loadRepositories(preferredUrl: String?) {
        _state.update { it.copy(isLoadingRepos = true) }
        val seeded = graph.catalog.repositories.value
        if (seeded.isNotEmpty()) applyRepos(seeded, preferredUrl)
        graph.catalog.loadRepositories().onSuccess { applyRepos(it, preferredUrl) }.onFailure {
            _state.update { s -> s.copy(reposUnavailable = s.repositories.isEmpty()) }
        }
        _state.update { it.copy(isLoadingRepos = false) }
    }

    private fun applyRepos(repos: List<Repository>, preferredUrl: String?) {
        _state.update { s ->
            val selected = s.selectedRepo ?: repos.firstOrNull { it.url == preferredUrl } ?: repos.firstOrNull()
            s.copy(repositories = repos, selectedRepo = selected, reposUnavailable = false)
        }
    }

    private suspend fun loadModels(force: Boolean = false) {
        _state.update { it.copy(isLoadingModels = true) }
        graph.catalog.loadModels(force)
            .onSuccess { models -> _state.update { it.withModels(models) } }
            .onFailure { _state.update { it.copy(isLoadingModels = false, modelsUnavailable = it.models.isEmpty()) } }
    }

    /**
     * Adopts a freshly loaded model list. A selection already made on this screen is re-resolved against the new
     * instances; otherwise the last launch's choice is restored — "Default" stays "Default", and a variant is matched
     * on its exact parameters, so a parameter-less variant is not swapped for the model's default one — and a
     * user who has never picked anything starts on the first recommended model.
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
        modelSelectionResolved = true
        return copy(models = models, selectedModel = model, selectedVariant = variant, isLoadingModels = false, modelsUnavailable = false)
    }

    fun setPrompt(value: String) = _state.update { it.copy(prompt = value, error = null) }
    fun addAttachments(items: List<PendingAttachment>) = _state.update { it.copy(attachments = (it.attachments + items).take(PromptImage.MAX_COUNT), error = null) }
    fun removeAttachment(item: PendingAttachment) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == item.id }) }
    fun reportError(message: String) = _state.update { it.copy(error = message) }
    fun selectRepo(repo: Repository?) = _state.update { it.copy(selectedRepo = repo, noRepo = repo == null) }
    fun setRef(value: String) = _state.update { it.copy(ref = value) }
    fun selectModel(model: ModelOption?, variant: ModelVariant?) = _state.update {
        it.copy(selectedModel = model, selectedVariant = variant ?: model?.defaultVariant)
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

    fun launch(onLaunched: (Agent) -> Unit) {
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
            )
            // Same draft, same id: retrying after a timeout or a cancel adopts the agent the first attempt may have
            // created instead of launching a duplicate.
            val request = draft.copy(agentId = withContext(Dispatchers.Default) { LaunchIdempotency.agentId(draft, launchNonce) })
            graph.agents.launch(request, s.modelLabel).fold(
                onSuccess = { agent ->
                    // The agent exists now: a cancel arriving this late must not strand it behind an intact draft, and
                    // the composer only reports the launch done once the defaults it will restore next time are saved.
                    withContext(NonCancellable) {
                        launchNonce = LaunchIdempotency.newNonce()
                        graph.prefs.setComposerDefaults(
                            repoUrl = request.repoUrl,
                            ref = request.ref,
                            modelId = request.modelId,
                            params = request.modelParams.associate { p: ModelParam -> p.id to p.value },
                            autoCreatePr = request.autoCreatePr,
                        )
                        _state.update { it.copy(isLaunching = false, prompt = "", attachments = emptyList()) }
                        onLaunched(agent)
                    }
                },
                onFailure = { t -> _state.update { it.copy(isLaunching = false, error = t.userMessage()) } },
            )
        }
    }

    /** Abandons a launch that is taking too long. The draft stays in the composer so it can be sent again. */
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
