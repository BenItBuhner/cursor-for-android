package com.cursorforandroid.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.choiceFor
import com.cursorforandroid.domain.choiceLabelled
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.components.PendingAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUiState(
    val agent: Agent? = null,
    val conversation: ConversationState,
    val draft: String = "",
    val isSending: Boolean = false,
    val toast: String? = null,
    val isPinned: Boolean = false,
)

/**
 * The follow-up composer's model picker. The API never says which model a chat runs on, so [currentLabel] and
 * [current] are what this device recorded when it launched the chat or last switched it, and stay null for chats
 * started elsewhere. [override] is the pick for the next follow-up: the request carries it and, as the server keeps
 * the switch for the runs after, it becomes the chat's model once that run is accepted. Without one the request
 * carries no model and the chat stays on whatever it has been using.
 */
data class FollowUpModelState(
    val models: List<ModelOption> = emptyList(),
    val isLoading: Boolean = false,
    /** `GET /v1/models` failed and nothing is cached; the picker offers a retry. */
    val unavailable: Boolean = false,
    /** The name of the chat's model — the catalog's when [current] is resolved, the recorded one otherwise. */
    val currentLabel: String? = null,
    /** The chat's model's entry in [models]; null when the chat's model is unknown or the catalog no longer lists it. */
    val current: ModelChoice? = null,
    val override: ModelChoice? = null,
    /** null keeps the conversation's mode; true / false asks the next run for plan / agent mode explicitly. */
    val planMode: Boolean? = null,
    /** Model ids pinned in the picker, most recently pinned first. */
    val pinnedModelIds: List<String> = emptyList(),
) {
    /** What the picker shows checked: the pick for the next run, else the chat's current model when the catalog has it. */
    val selected: ModelChoice? get() = override ?: current
    /** The model's name alone — never its parameters — with the plan-mode flag when it is asked for. */
    val chipLabel: String get() = (override?.label ?: currentLabel ?: "Model") + if (planMode == true) " · Plan" else ""
}

class ConversationViewModel(private val graph: AppGraph, val agentId: String) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    private val sending = MutableStateFlow(false)
    private val toast = MutableStateFlow<String?>(null)
    /** The picker's own state; the catalog and the chat's current model are folded in by [modelPicker]. */
    private val picker = MutableStateFlow(FollowUpModelState())

    val agent: StateFlow<Agent?> = graph.agents.state.map { s -> s.agents.firstOrNull { it.id == agentId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.agents.agent(agentId))

    val conversation: StateFlow<ConversationState> = graph.conversations.state(agentId)
    val draftText: StateFlow<String> = draft.asStateFlow()
    val pendingAttachments: StateFlow<List<PendingAttachment>> = attachments.asStateFlow()
    val isSending: StateFlow<Boolean> = sending.asStateFlow()
    val toastMessage: StateFlow<String?> = toast.asStateFlow()
    val isPinned: StateFlow<Boolean> = graph.prefs.localAgentState.map { agentId in it.pinnedIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val modelPicker: StateFlow<FollowUpModelState> = combine(agent, graph.catalog.models, picker, graph.prefs.pinnedModelIds) { a, models, local, pinned ->
        pickerState(a, models, local).copy(pinnedModelIds = pinned)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), pickerState(graph.agents.agent(agentId), graph.catalog.models.value, picker.value))

    init {
        graph.conversations.attach(agentId)
        viewModelScope.launch { loadModels() }
    }

    override fun onCleared() {
        graph.conversations.detach(agentId)
        super.onCleared()
    }

    /**
     * Finds the chat's model in the catalog: by the id recorded with it (its parameters only decide which variant
     * shows selected), else by its label for rows recorded before the id was kept. The chip then carries the catalog's
     * name for it; unresolved — the catalog not loaded yet, or the model gone from it — the recorded name stands in,
     * and the picker shows it on its "Current model" row instead.
     */
    private fun pickerState(agent: Agent?, models: List<ModelOption>, local: FollowUpModelState): FollowUpModelState {
        val current = agent?.modelId?.let { models.choiceFor(it, agent.modelParams) } ?: agent?.modelDisplayName?.let(models::choiceLabelled)
        return local.copy(models = models, currentLabel = current?.label ?: agent?.modelName, current = current)
    }

    /** The catalog is shared with the home composer and fetched once per session; a saved copy shows meanwhile. */
    private suspend fun loadModels(force: Boolean = false) {
        picker.update { it.copy(isLoading = true) }
        graph.catalog.loadModels(force)
            .onSuccess { picker.update { it.copy(isLoading = false, unavailable = false) } }
            .onFailure { picker.update { it.copy(isLoading = false, unavailable = graph.catalog.models.value.isEmpty()) } }
    }

    fun refreshModels() = viewModelScope.launch { loadModels(force = true) }

    /** A model picks the next follow-up's model (its default variant unless one is given); null keeps the chat's current one. */
    fun selectModel(model: ModelOption?, variant: ModelVariant?) {
        picker.update { it.copy(override = model?.let { m -> ModelChoice(m, variant ?: m.defaultVariant) }) }
    }

    fun setPlanMode(value: Boolean) { picker.update { it.copy(planMode = value) } }

    fun togglePinnedModel(modelId: String) = viewModelScope.launch { graph.prefs.togglePinnedModel(modelId) }

    fun setDraft(value: String) { draft.value = value }

    fun addAttachments(items: List<PendingAttachment>) { attachments.value = (attachments.value + items).take(PromptImage.MAX_COUNT) }
    fun removeAttachment(item: PendingAttachment) { attachments.value = attachments.value.filterNot { it.id == item.id } }
    fun showMessage(message: String) { toast.value = message }
    /** See [com.cursorforandroid.ui.compose.NewAgentViewModel.applyShare]: same merge into this chat's follow-up. */
    fun applyShare(text: String, items: List<PendingAttachment>, warning: String? = null) {
        draft.value = ShareDraft.mergeText(draft.value, text)
        attachments.value = (attachments.value + items).take(PromptImage.MAX_COUNT)
        if (warning != null) toast.value = warning
    }

    fun send() {
        val text = draft.value.trim()
        val images = attachments.value
        if ((text.isEmpty() && images.isEmpty()) || sending.value) return
        val options = picker.value
        viewModelScope.launch {
            sending.value = true
            draft.value = ""
            attachments.value = emptyList()
            graph.conversations.sendFollowUp(
                agentId,
                text.ifEmpty { "See the attached image." },
                images.map { it.image },
                mcpServers = graph.mcpServers.enabled(),
                planMode = options.planMode,
                modelId = options.override?.model?.id,
                modelParams = options.override?.params.orEmpty(),
                modelDisplayName = options.override?.label,
            ).onSuccess {
                // The row now records the switch and the server keeps it for the runs after, so the pick is the
                // chat's model rather than a pending override — unless another one was made while this was in flight.
                picker.update { if (it.override == options.override) it.copy(override = null) else it }
            }.onFailure {
                draft.value = text
                attachments.value = images
                toast.value = it.userMessage()
            }
            sending.value = false
        }
    }

    fun cancelRun() = viewModelScope.launch {
        graph.conversations.cancelActiveRun(agentId).onFailure { toast.value = it.userMessage() }
    }

    fun reload() = graph.conversations.reload(agentId)

    /** The screen is back in the foreground: catch up on whatever the run did while the app was away. */
    fun revalidate() = graph.conversations.revalidate(agentId)

    fun togglePinned() = viewModelScope.launch {
        // The pin is applied either way; the toast only says when the account has not been told yet.
        graph.pins.toggle(agentId).onFailure { toast.value = "Saved on this device; it syncs with your Cursor account when it's reachable." }
    }

    fun archive(onDone: () -> Unit) = viewModelScope.launch {
        graph.agents.archive(agentId).onSuccess { toast.value = "Agent archived"; onDone() }.onFailure { toast.value = it.userMessage() }
    }

    fun unarchive() = viewModelScope.launch {
        graph.agents.unarchive(agentId).onSuccess { toast.value = "Agent unarchived" }.onFailure { toast.value = it.userMessage() }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        graph.conversations.forget(agentId)
        graph.agents.delete(agentId).onSuccess { onDone() }.onFailure { toast.value = it.userMessage() }
    }

    fun clearToast() { toast.value = null }

    class Factory(private val graph: AppGraph, private val agentId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConversationViewModel(graph, agentId) as T
    }
}
