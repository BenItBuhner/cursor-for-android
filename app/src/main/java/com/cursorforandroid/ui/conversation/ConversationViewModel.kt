package com.cursorforandroid.ui.conversation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.SlashCommandRepository
import com.cursorforandroid.data.repo.SlashScope
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SnoozeDuration
import com.cursorforandroid.domain.choiceFor
import com.cursorforandroid.domain.choiceLabelled
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.thumbnailOf
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * The composer's text and images. The screen reads these directly so typing is never a frame behind; every change
     * is mirrored to [FollowUpRepository][com.cursorforandroid.data.repo.FollowUpRepository], which keeps the draft
     * across visits and restarts and hands it back on the next open.
     */
    private val draft = MutableStateFlow("")
    private val attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    private val sending = MutableStateFlow(false)
    private val toast = MutableStateFlow<String?>(null)
    /** The picker's own state; the catalog and the chat's current model are folded in by [modelPicker]. */
    private val picker = MutableStateFlow(FollowUpModelState())
    /** Decoded previews of the images the queue cards and a restored draft show, by [DraftImage.id]. */
    private val thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    val agent: StateFlow<Agent?> = graph.agents.state.map { s -> s.agents.firstOrNull { it.id == agentId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.agents.agent(agentId))

    val conversation: StateFlow<ConversationState> = graph.conversations.state(agentId)
    val draftText: StateFlow<String> = draft.asStateFlow()
    val pendingAttachments: StateFlow<List<PendingAttachment>> = attachments.asStateFlow()
    val isSending: StateFlow<Boolean> = sending.asStateFlow()
    val toastMessage: StateFlow<String?> = toast.asStateFlow()
    /**
     * Follow-ups sent while the agent was busy, oldest first; they go out by themselves once it is free. A steered
     * one is not among them: it shows in the transcript as a pending prompt instead.
     */
    val queue: StateFlow<List<QueuedFollowUp>> = graph.followUps.state(agentId).map { s -> s.queue.filterNot { it.isSteered } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.followUps.state(agentId).value.queue.filterNot { it.isSteered })
    val imageThumbnails: StateFlow<Map<String, ImageBitmap>> = thumbnails.asStateFlow()
    val isPinned: StateFlow<Boolean> = graph.prefs.localAgentState.map { agentId in it.pinnedIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val isSnoozed: StateFlow<Boolean> = graph.prefs.localAgentState.map { it.isSnoozed(agentId, AppClock.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val modelPicker: StateFlow<FollowUpModelState> = combine(agent, graph.catalog.models, picker, graph.prefs.pinnedModelIds) { a, models, local, pinned ->
        pickerState(a, models, local).copy(pinnedModelIds = pinned)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), pickerState(graph.agents.agent(agentId), graph.catalog.models.value, picker.value))

    /**
     * What `/` offers the follow-up composer: the agent's skills and the `.cursor/commands` its machine reported, over
     * the built-ins. The saved (or built-in) list is there at once; the account service's answer replaces it.
     */
    val commands: StateFlow<SlashCatalog> = graph.slashCommands.catalog(commandScope(graph.agents.agent(agentId)))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.slashCommands.current(commandScope(graph.agents.agent(agentId))))

    init {
        graph.conversations.attach(agentId)
        viewModelScope.launch { loadModels() }
        viewModelScope.launch {
            // The agent's machine reports its skills and commands a moment after it wakes; while the account says the
            // inventory is pending, ask again a few times rather than leave the popover on the built-ins.
            var catalog = graph.slashCommands.load(commandScope(agent.value))
            var retries = 0
            while (catalog.pending && retries++ < PENDING_RETRIES) {
                delay(SlashCommandRepository.PENDING_TTL_MS)
                catalog = graph.slashCommands.load(commandScope(agent.value))
            }
        }
        viewModelScope.launch {
            // The draft left here last time comes back once the disk has been read — unless something was typed first.
            val restored = graph.followUps.state(agentId).first { it.restored }
            if (draft.value.isEmpty() && attachments.value.isEmpty()) adoptDraft(restored.draft)
        }
        viewModelScope.launch {
            graph.followUps.state(agentId).map { s -> s.queue.flatMap { it.images } + s.draft.images }.collect(::decodeThumbnails)
        }
    }

    /** The agent's own catalog; the repository and branch, when the row has them, help the server before its machine has reported. */
    private fun commandScope(agent: Agent?): SlashScope = SlashScope.Agent(agentId, agent?.repoUrl, agent?.startingRef)

    override fun onCleared() {
        graph.conversations.detach(agentId)
        graph.followUps.flush(agentId)
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

    fun setDraft(value: String) {
        draft.value = value
        graph.followUps.setDraftText(agentId, value)
    }

    fun addAttachments(items: List<PendingAttachment>) {
        thumbnails.update { cache -> cache + items.mapNotNull { a -> a.thumbnail?.let { a.id to it } } }
        setAttachments((attachments.value + items).take(PromptImage.MAX_COUNT))
    }

    fun removeAttachment(item: PendingAttachment) = setAttachments(attachments.value.filterNot { it.id == item.id })

    private fun setAttachments(items: List<PendingAttachment>) {
        attachments.value = items
        graph.followUps.setDraftImages(agentId, items.map { DraftImage(it.id, it.image) })
    }

    /** Puts the repository's draft in the composer: a restored one, or a queued message taken back for editing. */
    private suspend fun adoptDraft(saved: FollowUpDraft) {
        val restored = withContext(Dispatchers.Default) {
            saved.images.map { PendingAttachment.of(it.image, it.id, thumbnails.value[it.id]) }
        }
        thumbnails.update { cache -> cache + restored.mapNotNull { a -> a.thumbnail?.let { a.id to it } } }
        draft.value = saved.text
        attachments.value = restored
    }

    private suspend fun decodeThumbnails(images: List<DraftImage>) {
        val missing = images.filter { it.id !in thumbnails.value }.distinctBy { it.id }
        if (missing.isEmpty()) return
        val decoded = withContext(Dispatchers.Default) { missing.mapNotNull { d -> thumbnailOf(d.image)?.let { d.id to it } } }
        thumbnails.update { it + decoded }
    }

    fun showMessage(message: String) { toast.value = message }
    /** See [com.cursorforandroid.ui.compose.NewAgentViewModel.applyShare]: same merge into this chat's follow-up. */
    fun applyShare(text: String, items: List<PendingAttachment>, warning: String? = null) {
        setDraft(ShareDraft.mergeText(draft.value, text))
        addAttachments(items)
        if (warning != null) toast.value = warning
    }

    /**
     * Sends the composer's message. While the agent is on a turn — or other follow-ups are already waiting — it is
     * queued instead and goes out in its turn (see [queue]); only one run can be active per agent, and the API
     * refuses anything more. A send the server refuses as busy all the same (the row was a poll behind) is queued too.
     */
    fun send() {
        val text = draft.value.trim()
        val images = attachments.value
        if ((text.isEmpty() && images.isEmpty()) || sending.value) return
        val options = picker.value
        val busy = conversation.value.let { it.runStatus?.isActive == true || it.isStreaming } || graph.agents.agent(agentId)?.isRunning == true
        if (busy || graph.followUps.state(agentId).value.queue.isNotEmpty()) {
            enqueue(text, images, options)
            return
        }
        viewModelScope.launch {
            sending.value = true
            draft.value = ""
            attachments.value = emptyList()
            graph.conversations.sendFollowUp(
                agentId,
                text.ifEmpty { QueuedFollowUp.IMAGE_ONLY_TEXT },
                images.map { it.image },
                mcpServers = graph.mcpServers.enabled(),
                planMode = options.planMode,
                modelId = options.override?.model?.id,
                modelParams = options.override?.params.orEmpty(),
                modelDisplayName = options.override?.label,
            ).onSuccess {
                graph.followUps.clearDraft(agentId)
                // The row now records the switch and the server keeps it for the runs after, so the pick is the
                // chat's model rather than a pending override — unless another one was made while this was in flight.
                picker.update { if (it.override == options.override) it.copy(override = null) else it }
            }.onFailure {
                if (it.toCursorError()?.code == "agent_busy") {
                    enqueue(text, images, options)
                } else {
                    draft.value = text
                    graph.followUps.setDraftText(agentId, text)
                    setAttachments(images)
                    toast.value = it.userMessage()
                }
            }
            sending.value = false
        }
    }

    private fun enqueue(text: String, images: List<PendingAttachment>, options: FollowUpModelState) {
        graph.followUps.enqueue(
            agentId,
            text,
            images.map { DraftImage(it.id, it.image) },
            planMode = options.planMode,
            modelId = options.override?.model?.id,
            modelParams = options.override?.params.orEmpty(),
            modelDisplayName = options.override?.label,
        )
        draft.value = ""
        attachments.value = emptyList()
        graph.followUps.clearDraft(agentId)
    }

    /** Takes a queued follow-up back into the composer; a draft already there is queued in its place, so nothing is lost. */
    fun editQueued(id: String) {
        val displaced = !draft.value.isBlank() || attachments.value.isNotEmpty()
        graph.followUps.takeForEdit(agentId, id) ?: return
        viewModelScope.launch {
            adoptDraft(graph.followUps.state(agentId).value.draft)
            if (displaced) toast.value = "Your draft was queued in its place."
        }
    }

    fun removeQueued(id: String) = graph.followUps.remove(agentId, id)

    /**
     * Sends a queued follow-up now: it shows in the transcript as a pending prompt at once, the turn under way is
     * stopped, and the message goes out ahead of the others. A failure brings it back among the cards with the reason.
     */
    fun steerQueued(id: String) { graph.followUps.sendNow(agentId, id) }

    fun retryQueued(id: String) = graph.followUps.retry(agentId, id)

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
        graph.agents.archive(agentId).onSuccess { toast.value = "Chat archived"; onDone() }.onFailure { toast.value = it.userMessage() }
    }

    fun unarchive() = viewModelScope.launch {
        graph.agents.unarchive(agentId).onSuccess { toast.value = "Chat unarchived" }.onFailure { toast.value = it.userMessage() }
    }

    fun rename(name: String) = viewModelScope.launch {
        graph.agents.rename(agentId, name).onFailure { toast.value = it.userMessage() }
    }

    fun snooze(untilMillis: Long) = viewModelScope.launch {
        graph.prefs.snooze(agentId, untilMillis)
        toast.value = if (untilMillis == SnoozeDuration.FOREVER) "Snoozed" else "Chat snoozed"
    }

    fun unsnooze() = viewModelScope.launch {
        graph.prefs.unsnooze(agentId)
        toast.value = "Chat unsnoozed"
    }

    fun clearToast() { toast.value = null }

    private companion object {
        /** Eight more asks at the pending interval: about two minutes, longer than a machine takes to come up. */
        const val PENDING_RETRIES = 8
    }

    class Factory(private val graph: AppGraph, private val agentId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConversationViewModel(graph, agentId) as T
    }
}
