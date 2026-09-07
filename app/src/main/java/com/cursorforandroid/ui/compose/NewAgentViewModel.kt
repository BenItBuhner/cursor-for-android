package com.cursorforandroid.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.components.PendingAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val error: String? = null,
    val reposUnavailable: Boolean = false,
) {
    val canLaunch: Boolean get() = (prompt.isNotBlank() || attachments.isNotEmpty()) && !isLaunching && (selectedRepo != null || noRepo)
    val modelLabel: String get() = selectedVariant?.displayName ?: selectedModel?.displayName ?: "Default model"
}

class NewAgentViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(NewAgentUiState())
    val state: StateFlow<NewAgentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val defaults = graph.prefs.composerDefaults.first()
            _state.update { it.copy(autoCreatePr = defaults.autoCreatePr, ref = defaults.ref ?: "main") }
            loadRepositories(defaults.repoUrl)
            loadModels(defaults.modelId, defaults.modelParams)
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

    private suspend fun loadModels(preferredId: String?, preferredParams: Map<String, String>) {
        graph.catalog.loadModels().onSuccess { models ->
            _state.update { s ->
                val model = models.firstOrNull { it.id == preferredId } ?: models.firstOrNull()
                val variant = model?.variants?.let { variants ->
                    variants.firstOrNull { v -> v.params.associate { p -> p.id to p.value } == preferredParams && preferredParams.isNotEmpty() }
                        ?: variants.firstOrNull { it.isDefault } ?: variants.firstOrNull()
                }
                s.copy(models = models, selectedModel = model, selectedVariant = variant)
            }
        }
    }

    fun setPrompt(value: String) = _state.update { it.copy(prompt = value, error = null) }
    fun addAttachments(items: List<PendingAttachment>) = _state.update { it.copy(attachments = (it.attachments + items).take(PromptImage.MAX_COUNT), error = null) }
    fun removeAttachment(item: PendingAttachment) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == item.id }) }
    fun reportError(message: String) = _state.update { it.copy(error = message) }
    fun selectRepo(repo: Repository?) = _state.update { it.copy(selectedRepo = repo, noRepo = repo == null) }
    fun setRef(value: String) = _state.update { it.copy(ref = value) }
    fun selectModel(model: ModelOption?, variant: ModelVariant?) = _state.update {
        it.copy(selectedModel = model, selectedVariant = variant ?: model?.variants?.firstOrNull { v -> v.isDefault } ?: model?.variants?.firstOrNull())
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

    fun launch(onLaunched: (Agent) -> Unit) {
        val s = _state.value
        if (!s.canLaunch) return
        viewModelScope.launch {
            _state.update { it.copy(isLaunching = true, error = null) }
            val request = LaunchRequest(
                prompt = s.prompt.trim(),
                images = s.attachments.map { it.image },
                repoUrl = if (s.noRepo) null else s.selectedRepo?.url,
                ref = s.ref.trim().ifBlank { null },
                modelId = s.selectedModel?.id,
                modelParams = s.selectedVariant?.params ?: emptyList(),
                autoCreatePr = s.autoCreatePr,
                planMode = s.planMode,
            )
            graph.agents.launch(request, s.modelLabel).fold(
                onSuccess = { agent ->
                    graph.prefs.setComposerDefaults(
                        repoUrl = request.repoUrl,
                        ref = request.ref,
                        modelId = request.modelId,
                        params = request.modelParams.associate { p: ModelParam -> p.id to p.value },
                        autoCreatePr = request.autoCreatePr,
                    )
                    _state.update { it.copy(isLaunching = false, prompt = "", attachments = emptyList()) }
                    onLaunched(agent)
                },
                onFailure = { t -> _state.update { it.copy(isLaunching = false, error = t.userMessage()) } },
            )
        }
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NewAgentViewModel(graph) as T
    }
}
