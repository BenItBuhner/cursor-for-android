package com.cursorforandroid.ui.conversation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.SlashCommandRepository
import com.cursorforandroid.data.repo.SlashScope
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.BuiltInSlashCommands
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.domain.SnoozeDuration
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.choiceFor
import com.cursorforandroid.domain.choiceLabelled
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.components.ModePills
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
    /**
     * The mode the next follow-up asks for; null keeps the conversation's mode. Agent and plan travel on the documented
     * run request; Ask and Debug only on the account's follow-up, which Extended mode alone allows (see [AgentMode]).
     */
    val mode: AgentMode? = null,
    /** Model ids pinned in the picker, most recently pinned first. */
    val pinnedModelIds: List<String> = emptyList(),
) {
    /** What the picker shows checked: the pick for the next run, else the chat's current model when the catalog has it. */
    val selected: ModelChoice? get() = override ?: current
    /** The model's name alone — never its parameters. The mode is the composer's pill, not part of the chip. */
    val chipLabel: String get() = override?.label ?: currentLabel ?: "Model"
    /** The documented request's plan flag for [mode]: null keeps the conversation's mode (or the mode is one it cannot carry). */
    val planMode: Boolean?
        get() = when (mode) {
            AgentMode.PLAN -> true
            AgentMode.AGENT -> false
            else -> null
        }
    /** The pill the composer wears for [mode], if any. */
    val modePill: ModePills.Pill? get() = ModePills.Pill.of(mode)
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

    /** Which private surfaces the screen may offer: the answer chips, the account's queue, steering, Ask and Debug. */
    val capabilities: StateFlow<Capabilities> = graph.extendedMode.capabilities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Capabilities.DOCUMENTED)

    /** What the account has said about this chat's controls: its queue, the last steer, what was answered from here. */
    val controls: StateFlow<ConversationControls> = graph.steering.state(agentId)

    /**
     * What `/` offers the follow-up composer: the agent's skills and the `.cursor/commands` its machine reported, over
     * the built-ins — and, in Extended mode, `/ask` and `/debug`, which only the account's follow-up can carry. The
     * saved (or built-in) list is there at once; the account service's answer replaces it.
     */
    val commands: StateFlow<SlashCatalog> = combine(graph.slashCommands.catalog(commandScope(graph.agents.agent(agentId))), capabilities) { catalog, caps ->
        if (caps.agentModes) catalog.withExtendedModes() else catalog
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.slashCommands.current(commandScope(graph.agents.agent(agentId))))

    init {
        graph.conversations.attach(agentId)
        graph.steering.attach(agentId)
        viewModelScope.launch { loadModels() }
        viewModelScope.launch {
            // The agent's machine reports its skills and commands a moment after it wakes; while the account says the
            // inventory is pending, ask again a few times rather than leave the popover on the built-ins.
            var catalog = graph.slashCommands.load(commandScope(agent.value))
            var retries = 0
            while (catalog.pending && retries++ < PENDING_RETRIES) {
                delay(SlashCommandRepository.PENDING_TTL_MS)
                catalog = graph.slashCommands.load(commandScope(agent.value), force = true)
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
        graph.steering.detach(agentId)
        graph.followUps.flush(agentId)
        super.onCleared()
    }

    /** `/ask` and `/debug` after the other commands and before the skills, unless the account already lists a command of that name. */
    private fun SlashCatalog.withExtendedModes(): SlashCatalog {
        val known = entries.mapTo(HashSet()) { it.name }
        val modes = BuiltInSlashCommands.extendedModes.filter { it.name !in known }
        if (modes.isEmpty()) return this
        return copy(entries = commands + modes + entries.filter { it.kind != SlashCommand.Kind.Command })
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
        val choice = model?.let { m -> ModelChoice(m, variant ?: m.defaultVariant) }
        picker.update { it.copy(override = choice) }
        // An explicit pick is the new-chat composer's next default — opening the app must not fall back to Auto.
        if (choice != null) {
            viewModelScope.launch {
                graph.prefs.rememberModel(choice.model.id, choice.params.associate { it.id to it.value })
            }
        }
    }

    /** The model sheet's plan toggle: on is plan mode, off asks for agent mode explicitly. */
    fun setPlanMode(value: Boolean) = setMode(if (value) AgentMode.PLAN else AgentMode.AGENT)

    /** The composer's pill: Plan, Ask or Debug on; the cross (null) puts the next run back to agent mode explicitly. */
    fun setModePill(pill: ModePills.Pill?) = setMode(pill?.agentMode ?: AgentMode.AGENT)

    /** The modes and `/multitask` are one slot: asking for one takes the command out of the draft. */
    fun setMode(mode: AgentMode?) {
        picker.update { it.copy(mode = mode) }
        if (mode != null && mode != AgentMode.AGENT && SlashCommands.has(draft.value, SlashCommands.MULTITASK)) {
            setDraft(SlashCommands.remove(draft.value, SlashCommands.MULTITASK))
        }
    }

    fun togglePinnedModel(modelId: String) = viewModelScope.launch { graph.prefs.togglePinnedModel(modelId) }

    fun setDraft(value: String) {
        draft.value = value
        graph.followUps.setDraftText(agentId, value)
        keepModesExclusive(value)
    }

    /**
     * The one-slot rule the other way round: a draft that carries `/multitask` — typed, picked, shared in, restored or
     * taken back from the queue — puts a mode that was asked for off (to agent mode, as the pill's cross does). A
     * mode never asked for stays not asked for.
     */
    private fun keepModesExclusive(text: String) {
        if (SlashCommands.has(text, SlashCommands.MULTITASK)) picker.update { if (it.mode != null && it.mode != AgentMode.AGENT) it.copy(mode = AgentMode.AGENT) else it }
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
        keepModesExclusive(saved.text)
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
     * queued instead and goes out in its turn: in Extended mode into the account's queue, the one the desktop and the
     * web show too (see [controls]); otherwise on this device (see [queue]). Only one run can be active per agent,
     * and the API refuses anything more; a send the server refuses as busy all the same (the row was a poll behind)
     * is queued too. A follow-up in Ask or Debug mode travels on the account's follow-up, which alone can carry it.
     */
    fun send() {
        val text = draft.value.trim()
        val images = attachments.value
        if ((text.isEmpty() && images.isEmpty()) || sending.value) return
        val options = picker.value
        val busy = conversation.value.let { it.runStatus?.isActive == true || it.isStreaming } || graph.agents.agent(agentId)?.isRunning == true
        val caps = capabilities.value
        val accountMode = options.mode?.needsAccountService == true
        if (accountMode && !caps.agentModes) {
            toast.value = "${options.mode?.label} mode needs Extended mode; turn it on in Settings, or take the pill off."
            return
        }
        val accountQueue = caps.accountQueue && !graph.session.isDemo
        when {
            busy && accountQueue -> queueOnAccount(text, images, options)
            busy || graph.followUps.state(agentId).value.queue.isNotEmpty() -> enqueue(text, images, options)
            accountMode -> sendViaAccount(text, images, options)
            else -> sendDocumented(text, images, options)
        }
    }

    /** A follow-up in a mode only the account service carries, on a free agent: filed there, shown and streamed like any other. */
    private fun sendViaAccount(text: String, images: List<PendingAttachment>, options: FollowUpModelState) {
        viewModelScope.launch {
            sending.value = true
            draft.value = ""
            attachments.value = emptyList()
            graph.conversations.sendFollowUpVia(
                agentId,
                text.ifEmpty { QueuedFollowUp.IMAGE_ONLY_TEXT },
                images.map { it.image },
                modelId = options.override?.model?.id,
                modelParams = options.override?.params.orEmpty(),
                modelDisplayName = options.override?.label,
            ) {
                graph.steering.sendFollowup(agentId, accountFollowup(text, images, options)).getOrThrow()
            }.onSuccess {
                graph.followUps.clearDraft(agentId)
                picker.update { if (it.override == options.override) it.copy(override = null) else it }
            }.onFailure { restoreDraft(text, images, it) }
            sending.value = false
        }
    }

    /** A follow-up sent mid-turn in Extended mode: into the account's queue, behind the turn under way. */
    private fun queueOnAccount(text: String, images: List<PendingAttachment>, options: FollowUpModelState) {
        viewModelScope.launch {
            sending.value = true
            draft.value = ""
            attachments.value = emptyList()
            graph.steering.sendFollowup(agentId, accountFollowup(text, images, options))
                .onSuccess { graph.followUps.clearDraft(agentId) }
                .onFailure { restoreDraft(text, images, it) }
            sending.value = false
        }
    }

    private fun accountFollowup(text: String, images: List<PendingAttachment>, options: FollowUpModelState) = AccountFollowup(
        text = text.ifEmpty { QueuedFollowUp.IMAGE_ONLY_TEXT },
        images = images.map { it.image },
        mode = options.mode,
        modelId = options.override?.model?.id,
    )

    /** A send that did not go out: what was typed since wins; the prompt only comes back to an empty composer. */
    private fun restoreDraft(text: String, images: List<PendingAttachment>, cause: Throwable) {
        if (draft.value.isBlank()) {
            draft.value = text
            graph.followUps.setDraftText(agentId, text)
        }
        if (attachments.value.isEmpty()) setAttachments(images)
        toast.value = cause.userMessage()
    }

    private fun sendDocumented(text: String, images: List<PendingAttachment>, options: FollowUpModelState) {
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
                // The composer stays editable while a follow-up is in flight, so what was typed since wins; the
                // prompt that did not go out only comes back to an empty one.
                if (it.toCursorError()?.code == "agent_busy") enqueue(text, images, options) else restoreDraft(text, images, it)
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

    // -- the account's controls (Extended mode) ----------------------------------------------------------------------

    /** One account-service action: its outcome, or the reason it did not happen, in the snackbar. */
    private fun control(block: suspend () -> Result<String?>) = viewModelScope.launch {
        block().fold(onSuccess = { message -> if (!message.isNullOrBlank()) toast.value = message }, onFailure = { toast.value = it.userMessage() })
    }

    /** Answers the question the agent is waiting on (`ask_question` call [callId]). */
    fun answerQuestion(callId: String, answers: List<ToolPayload.Question.Answer>) = control {
        graph.steering.answerQuestion(agentId, callId, answers).map { it.message }
    }

    /** Steers the turn under way without stopping it; the account's outcome is what shows. */
    fun steer(text: String) = control { graph.steering.steer(agentId, text).map { it.message } }

    fun pauseRun() = control { graph.steering.pause(agentId).map { "Paused; resume when you're ready." } }

    fun resumeRun() = control { graph.steering.resume(agentId).map { "Resumed." } }

    /** Stops one tool call of the turn under way. */
    fun cancelToolCall(callId: String) = control {
        graph.steering.cancelToolCall(agentId, callId).map { accepted -> if (accepted) "Stopping that step." else "That step had already finished." }
    }

    fun wake() = control { graph.steering.wake(agentId).map { signalled -> if (signalled) "Waking the agent's machine." else "The machine was already awake." } }

    /** The account's queue: send now (in place of the turn under way), take away, move, reword, or deliver as a steer. */
    fun queueSendNow(id: String) = control { graph.steering.submitPendingNow(agentId, id).map { null } }

    fun queueDelete(id: String) = control { graph.steering.deletePending(agentId, id).map { null } }

    fun queueMove(id: String, up: Boolean) = control { graph.steering.movePending(agentId, id, up).map { null } }

    fun queueUpdate(id: String, text: String) = control {
        graph.steering.updatePending(agentId, id, text).map { null }.also { graph.steering.markEditing(agentId, id, editing = false) }
    }

    fun queueMarkEditing(id: String, editing: Boolean) = control { graph.steering.markEditing(agentId, id, editing).map { null } }

    fun queueSteerNow(id: String) = control { graph.steering.promotePending(agentId, id).map { it.message } }

    fun refreshQueue() = viewModelScope.launch { graph.steering.refreshQueue(agentId) }

    fun cancelRun() = viewModelScope.launch {
        graph.conversations.cancelActiveRun(agentId).onFailure { toast.value = it.userMessage() }
    }

    fun reload() = graph.conversations.reload(agentId)

    /** The screen is back in the foreground: pick the run back up and catch up on what it did while away. */
    fun resume() = graph.conversations.resume(agentId)

    /** The screen stopped. The run keeps going — the notification service is what watches it now. */
    fun pause() = graph.conversations.pause(agentId)

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
