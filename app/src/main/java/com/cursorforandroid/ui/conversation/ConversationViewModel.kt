package com.cursorforandroid.ui.conversation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.UploadedFile
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.AttachmentUploads
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.SlashCommandRepository
import com.cursorforandroid.data.repo.SlashScope
import com.cursorforandroid.data.repo.TraceStatus
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.BuiltInSlashCommands
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.DraftFile
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.GoalTranscript
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelResolution
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.attachmentOnlyText
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.domain.SnoozeDuration
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.components.FileUploadState
import com.cursorforandroid.ui.components.MarkdownCache
import com.cursorforandroid.ui.components.ModePills
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.components.thumbnailOf
import com.cursorforandroid.ui.components.withinSlots
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * The follow-up composer's model picker. What the chat runs on is resolved in order (see [ModelResolution.forChat]):
 * the account's record of the chat (Extended mode — the documented API never says), what this device recorded when
 * it launched or last switched the chat, and, with neither, Auto as an assumption ([currentAssumed]). [override] is
 * the pick for the next follow-up: the request carries it and, as the server keeps the switch for the runs after,
 * it becomes the chat's model once that run is accepted. Without one the request carries no model and the chat
 * stays on whatever it has been using — the assumed Auto included.
 */
data class FollowUpModelState(
    val models: List<ModelOption> = emptyList(),
    val isLoading: Boolean = false,
    /** `GET /v1/models` failed and nothing is cached; the picker offers a retry. */
    val unavailable: Boolean = false,
    /**
     * The name of the chat's model — the catalog's when [current] is resolved, the record's or the recorded one
     * otherwise; null only while the chat's row has not been read at all.
     */
    val currentLabel: String? = null,
    /** The chat's model's entry in [models]; null when the chat's model is unknown or the catalog no longer lists it. */
    val current: ModelChoice? = null,
    /** Nothing reports the chat's model: [currentLabel] is Auto by assumption, and the picker says so. */
    val currentAssumed: Boolean = false,
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
    /**
     * The model's name alone — never its parameters. The mode is the composer's pill, not part of the chip. Never a
     * bare "Model": before the row has been read, the chip already assumes Auto, as [ModelResolution.forChat] would.
     */
    val chipLabel: String get() = override?.label ?: currentLabel ?: AccountModel.AUTO_LABEL
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

/** One published state of the chat with the rows the screen draws for it (see [ConversationViewModel.presented]). */
class PresentedTranscript(val state: ConversationState, val presented: TranscriptPresenter.Presented) {
    val rows: List<TranscriptRow> get() = presented.rows
    val items get() = presented.items
    val coordinatorMode: Boolean get() = presented.coordinatorMode
}

/** Where every screen's transcript is presented: one thread at a time, off the main one, in the order the states came. */
@OptIn(ExperimentalCoroutinesApi::class)
private val presenting = Dispatchers.Default.limitedParallelism(1)

class ConversationViewModel(private val graph: AppGraph, val agentId: String) : ViewModel() {

    /**
     * The composer's text and images. The screen reads these directly so typing is never a frame behind; every change
     * is mirrored to [FollowUpRepository][com.cursorforandroid.data.repo.FollowUpRepository], which keeps the draft
     * across visits and restarts and hands it back on the next open.
     */
    private val draft = MutableStateFlow("")
    private val attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    /**
     * Files of any type (Extended mode). Each goes up the moment it is attached (see [AttachmentUploads]) and stays
     * in the strip, its chip filling, until the send is through; a file that is up carries its reference.
     */
    private val files = MutableStateFlow<List<PendingFile>>(emptyList())
    private val sending = MutableStateFlow(false)
    private val toast = MutableStateFlow<String?>(null)
    /** The picker's own state; the catalog and the chat's current model are folded in by [modelPicker]. */
    private val picker = MutableStateFlow(FollowUpModelState())
    /** Decoded previews of the images the queue cards and a restored draft show, by [DraftImage.id]. */
    private val thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    val agent: StateFlow<Agent?> = graph.agents.state.map { s -> s.agents.firstOrNull { it.id == agentId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.agents.agent(agentId))

    val conversation: StateFlow<ConversationState> = graph.conversations.state(agentId)

    /**
     * The transcript as the screen draws it: each published state presented into rows off the main thread (see
     * [TranscriptPresenter]) — incrementally, so a live delta or a page of older turns costs its own turns and not
     * the whole chat — with the markdown of the newest page parsed ahead of the rows being composed. Conflated: a
     * state that lands while the one before it is being presented is presented next, and the ones between go
     * unpresented, as the screen would only ever have shown the latest anyway. The state it was presented from
     * travels with it, so what the screen reads of the chat (loading, running, older turns) agrees with its rows.
     */
    private val presenter = graph.presenters.forAgent(agentId)

    val presented: StateFlow<PresentedTranscript> = combine(conversation, agent.map { it?.looksLikeProject == true }.distinctUntilChanged()) { c, project -> c to project }
        .conflate()
        .map { (c, project) -> withContext(presenting) { presentNow(c, project) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialPresentation())

    /**
     * What the first frame draws: a chat whose presenter is warm — a screen showed it before and its rows are kept
     * (see [TranscriptPresenters]) — is presented here and now, from those rows, which for an unchanged transcript is
     * a walk over its turns and no cutting; a chat opened for the first time waits for the presentation off the main
     * thread, as it did before, with nothing to show meanwhile anyway.
     */
    private fun initialPresentation(): PresentedTranscript {
        val state = conversation.value
        if (!presenter.isWarm || state.items.isEmpty()) return PresentedTranscript(state, TranscriptPresenter.Presented.EMPTY)
        return presentNow(state, graph.agents.agent(agentId)?.looksLikeProject == true)
    }

    private fun presentNow(state: ConversationState, listSaysProject: Boolean): PresentedTranscript {
        val presented = presenter.present(state.items, coordinatorMode = listSaysProject || state.isProjectConversation, runActive = state.runStatus?.isActive == true || state.isStreaming)
        // The newest rows are what the first frame composes: their markdown is parsed here, not on that frame.
        presented.rows.asReversed().asSequence().take(PRIMED_ROWS).forEach { row ->
            when (row) {
                is TranscriptRow.Message -> (row.call.payload as? ToolPayload.CoordinatorMessage)?.message?.let(MarkdownCache::prime)
                is TranscriptRow.Item -> when (val item = row.item) {
                    is UserMessage -> MarkdownCache.prime(item.text)
                    is AssistantMessage -> if (!item.isStreaming) MarkdownCache.prime(item.markdown)
                    else -> Unit
                }
                else -> Unit
            }
        }
        return PresentedTranscript(state, presented)
    }

    val draftText: StateFlow<String> = draft.asStateFlow()
    val pendingAttachments: StateFlow<List<PendingAttachment>> = attachments.asStateFlow()
    val pendingFiles: StateFlow<List<PendingFile>> = files.asStateFlow()
    /** Where each attached file's upload stands, by [PendingFile.id], from the moment it is attached. */
    val fileUploads: StateFlow<Map<String, FileUploadState>> = combine(files, graph.attachmentUploads.states) { fs, states ->
        fs.mapNotNull { f -> states[f.id]?.let { f.id to FileUploadState.of(it) } }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    /**
     * Why send is held: "Uploading 2 of 3…" while an attached file is still going up, null otherwise. The send
     * button follows it; once every file carries its reference the prompt goes out with no upload in the way.
     */
    val uploadHint: StateFlow<String?> = combine(files, graph.attachmentUploads.states) { fs, states -> AttachmentUploads.hint(states, fs.map { it.id }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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

    /** The notices about the load the reader has closed over this chat's composer, and when a closed one comes back (see [NoticeDismissals]). */
    private val dismissals = NoticeDismissals(agentId, graph.prefs, conversation, viewModelScope)

    /**
     * The identities of the closed notices ([LoadNotice.identity]) the dock leaves out; null until the device's record
     * of them has been read, when it shows none (see [LoadNotices.shown]).
     */
    val hiddenNotices: StateFlow<Set<String>?> = dismissals.hidden

    /** The reader's X on a notice's card: hidden for this chat until its words change or its condition clears and recurs. */
    fun dismissNotice(notice: LoadNotice) = dismissals.dismiss(notice)

    /** Which private surfaces the screen may offer: the answer chips, the account's queue, steering, Ask and Debug. */
    val capabilities: StateFlow<Capabilities> = graph.extendedMode.capabilities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Capabilities.DOCUMENTED)

    /** What the account has said about this chat's controls: its queue, the last steer, what was answered from here. */
    val controls: StateFlow<ConversationControls> = graph.steering.state(agentId)

    /**
     * The goal on the chat, for the strip above the composer: the account's word (Extended mode, once read) when it
     * names an open goal, else what the chat's own transcript says — the goal the agent set and where its calls and
     * Cursor's continuations have taken it, including one completed in the newest turn (see [GoalTranscript.derive]).
     * Null when there is no goal to show.
     */
    val goal: StateFlow<Goal?> = combine(presented, controls) { p, ctrl -> goalOf(p.presented.goal, ctrl) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), goalOf(presented.value.presented.goal, controls.value))

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
            // Ask and Debug travel on the account's follow-up alone: with the mode turned off under a worn pill, the
            // pill comes off (to "not asked", as if never picked) rather than stay on to refuse the next send.
            capabilities.collect { caps ->
                if (!caps.agentModes) picker.update { if (it.mode?.needsAccountService == true) it.copy(mode = null) else it }
            }
        }
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
            if (draft.value.isEmpty() && attachments.value.isEmpty() && files.value.isEmpty()) adoptDraft(restored.draft)
        }
        viewModelScope.launch {
            graph.followUps.state(agentId).map { s -> s.queue.flatMap { it.images } + s.draft.images }.collect(::decodeThumbnails)
        }
        viewModelScope.launch {
            // A file whose upload has completed takes its reference onto the draft, so the copy on disk sends what is
            // already up after a restart rather than uploading it again.
            graph.attachmentUploads.states.collect { states -> adoptUploadRefs(states) }
        }
    }

    private fun adoptUploadRefs(states: Map<String, AttachmentUploads.Status>) {
        val current = files.value
        val updated = current.map { f ->
            val ref = (states[f.id] as? AttachmentUploads.Status.Done)?.file?.ref
            if (ref != null && f.file.upload != ref) PendingFile(f.id, f.file.withUpload(ref), f.thumbnail) else f
        }
        if (updated != current) setFiles(updated)
    }

    /** The agent's own catalog; the repository and branch, when the row has them, help the server before its machine has reported. */
    private fun commandScope(agent: Agent?): SlashScope = SlashScope.Agent(agentId, agent?.repoUrl, agent?.startingRef)

    /**
     * The account's open goal stands over the transcript's reading; when the account says the goal is over (or has
     * none), the transcript still gets to show a goal completed in the newest turn, and nothing otherwise.
     */
    private fun goalOf(derived: Goal?, controls: ConversationControls): Goal? {
        if (!controls.goalKnown) return derived
        val account = controls.goal
        if (account != null && account.status.isOpen) return account
        return derived?.takeIf { it.status == GoalStatus.COMPLETE }
    }

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
     * The chat's model, resolved as [ModelResolution.forChat] resolves it: the account's record first, this device's
     * record second, Auto by assumption last. The chip carries the catalog's name for it when the catalog places it;
     * unresolved — the catalog not loaded yet, or the model gone from it — the record's own name stands in, and the
     * picker shows it on its "Current model" row instead. A row not read yet resolves to the same assumption.
     */
    private fun pickerState(agent: Agent?, models: List<ModelOption>, local: FollowUpModelState): FollowUpModelState {
        val resolved = ModelResolution.forChat(agent, models)
        return local.copy(models = models, currentLabel = resolved.label, current = resolved.choice, currentAssumed = resolved.isAssumed)
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

    /** Files of any type from the document picker (Extended mode); refused with a word when the mode is off. */
    fun addFiles(items: List<PendingFile>) {
        if (items.isEmpty()) return
        if (!capabilities.value.promptFiles || graph.session.isDemo) {
            toast.value = AgentRepository.FILES_NEED_EXTENDED
            return
        }
        val kept = (files.value + items).withinSlots(imagesElsewhere = attachments.value.size)
        // Up they go, the moment they are attached; the send waits on nothing once they are.
        kept.filter { f -> items.any { it.id == f.id } }.forEach { graph.attachmentUploads.start(it.id, it.file) }
        setFiles(kept)
    }

    /** Takes a file off the message: its upload, under way or done, is cancelled with it. */
    fun removeFile(item: PendingFile) {
        setFiles(files.value.filterNot { it.id == item.id })
        graph.attachmentUploads.cancel(item.id)
    }

    private fun setFiles(items: List<PendingFile>) {
        files.value = items
        graph.followUps.setDraftFiles(agentId, items.map { DraftFile(it.id, it.file) })
    }

    /** A file whose upload failed: the upload alone is tried again, in its chip; the message waits where it is. */
    fun retryFile(item: PendingFile) {
        if (files.value.none { it.id == item.id }) return
        graph.attachmentUploads.retry(item.id)
    }

    /** Puts the repository's draft in the composer: a restored one, or a queued message taken back for editing. */
    private suspend fun adoptDraft(saved: FollowUpDraft) {
        val restored = withContext(Dispatchers.Default) {
            saved.images.map { PendingAttachment.of(it.image, it.id, thumbnails.value[it.id]) }
        }
        // An image file's chip thumbnail is decoded on the way back too, off the main thread.
        val restoredFiles = withContext(Dispatchers.Default) { saved.files.map { PendingFile.of(it.file, it.id) } }
        thumbnails.update { cache -> cache + restored.mapNotNull { a -> a.thumbnail?.let { a.id to it } } }
        draft.value = saved.text
        attachments.value = restored
        files.value = restoredFiles
        // A file that came back with its reference is up already; one without goes up now.
        restoredFiles.forEach { graph.attachmentUploads.start(it.id, it.file) }
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
        val attached = files.value
        if ((text.isEmpty() && images.isEmpty() && attached.isEmpty()) || sending.value) return
        val options = picker.value
        // One reading, one source at a time — the freshest that has spoken — shared with the queue's dispatcher, so
        // what the composer decides and what the queue does never disagree (see SendGate; the `send:` diagnostics).
        val busy = graph.followUps.decide(agentId).busy
        val caps = capabilities.value
        val accountMode = options.mode?.needsAccountService == true
        if (accountMode && !caps.agentModes) {
            toast.value = "${options.mode?.label} mode needs Extended mode; turn it on in Settings, or take the pill off."
            return
        }
        // A file of any type travels on the account's follow-up alone, as Ask and Debug do: uploaded first, then
        // named in the message as the desktop names it (see AgentRepository.FILES_NEED_EXTENDED for the mode off).
        val withFiles = attached.isNotEmpty()
        if (withFiles && (!caps.promptFiles || graph.session.isDemo)) {
            toast.value = AgentRepository.FILES_NEED_EXTENDED
            return
        }
        // The send button is held while a file is still going up; a send that gets here all the same waits its turn too.
        uploadHint.value?.let { hint ->
            toast.value = "$hint The message goes out once the files are up."
            return
        }
        val accountQueue = caps.accountQueue && !graph.session.isDemo
        val waiting = graph.followUps.state(agentId).value.queue.isNotEmpty()
        when {
            busy && accountQueue -> queueOnAccount(text, images, attached, options)
            // This device's queue sends the documented run request, which cannot carry Ask or Debug: a message in
            // either mode is not put behind the ones waiting there to go out as an agent turn without a word.
            accountMode && (busy || waiting) -> toast.value = "${options.mode?.label} mode follow-ups cannot wait in this device's queue. Let the queued messages go first, or take the pill off."
            busy || waiting -> enqueue(text, images, attached, options)
            accountMode || withFiles -> sendViaAccount(text, images, attached, options)
            else -> sendDocumented(text, images, options)
        }
    }

    /**
     * A follow-up only the account service carries — a mode, or files — on a free agent: filed there, shown and
     * streamed like any other. The files went up when they were attached, so the message goes out at once by their
     * references; a file that did not get up is tried again first, its chip filling. They leave the strip once the
     * account has the message, and stay, marked, when an upload failed, so the same send can be tried again.
     */
    private fun sendViaAccount(text: String, images: List<PendingAttachment>, attached: List<PendingFile>, options: FollowUpModelState) {
        viewModelScope.launch {
            sending.value = true
            draft.value = ""
            attachments.value = emptyList()
            val followupId = AccountFollowup.newId()
            graph.conversations.sendFollowUpVia(
                agentId,
                text.ifEmpty { attachmentOnlyText(images.size, attached.size) },
                images.map { it.image },
                attached.map { it.file },
                modelId = options.override?.model?.id,
                modelParams = options.override?.params.orEmpty(),
                modelDisplayName = options.override?.label,
                followupId = followupId,
            ) {
                val uploaded = uploadFiles(attached)
                graph.steering.sendFollowup(agentId, accountFollowup(text, images, attached.size, uploaded, options, followupId)).getOrThrow()
            }.onSuccess {
                clearFiles(attached)
                graph.followUps.clearDraft(agentId)
                picker.update { if (it.override == options.override) it.copy(override = null) else it }
            }.onFailure { restoreDraft(text, images, it) }
            sending.value = false
        }
    }

    /**
     * A follow-up sent mid-turn in Extended mode: into the account's queue, behind the turn under way. The card above
     * the composer (see [controls]) is what shows it until the account delivers it; the transcript then shows it
     * under the run the account started on it — or, should the account have been free after all and started the
     * run at once, from the moment the account names that run (see `ConversationRepository.expectDelivery`).
     */
    private fun queueOnAccount(text: String, images: List<PendingAttachment>, attached: List<PendingFile>, options: FollowUpModelState) {
        viewModelScope.launch {
            sending.value = true
            draft.value = ""
            attachments.value = emptyList()
            val message = text.ifEmpty { attachmentOnlyText(images.size, attached.size) }
            val staged = graph.conversations.stageFollowUp(agentId, message, images.map { it.image }, attached.map { it.file }, show = false)
            // The account's id for the follow-up is minted here, so the card's row and the transcript's copy are one message (see QueuePlacement).
            val followupId = AccountFollowup.newId()
            graph.conversations.sendStagedVia(agentId, staged, options.override?.model?.id, options.override?.params.orEmpty(), options.override?.label, followupId = followupId) {
                val uploaded = uploadFiles(attached)
                graph.steering.sendFollowup(agentId, accountFollowup(text, images, attached.size, uploaded, options, followupId)).getOrThrow()
            }.onSuccess {
                clearFiles(attached)
                graph.followUps.clearDraft(agentId)
            }.onFailure { restoreDraft(text, images, it) }
            sending.value = false
        }
    }

    /**
     * What the prompt names [attached] as: the references their uploads settled on when they were attached — at
     * once, the case a send meets — or, for a file that did not get up, the upload tried again now, its chip filling.
     */
    private suspend fun uploadFiles(attached: List<PendingFile>): List<UploadedFile> {
        if (attached.isEmpty()) return emptyList()
        return graph.attachmentUploads.awaitAll(attached.map { it.id to it.file })
    }

    /** The files went out with the message: the chips go, unless something else was attached meanwhile. */
    private fun clearFiles(sent: List<PendingFile>) {
        val sentIds = sent.mapTo(HashSet()) { it.id }
        setFiles(files.value.filterNot { it.id in sentIds })
        graph.attachmentUploads.forget(sentIds)
    }

    private fun accountFollowup(text: String, images: List<PendingAttachment>, fileCount: Int, uploaded: List<UploadedFile>, options: FollowUpModelState, followupId: String) = AccountFollowup(
        text = text.ifEmpty { attachmentOnlyText(images.size, fileCount) },
        images = images.map { it.image },
        files = uploaded,
        mode = options.mode,
        modelId = options.override?.model?.id,
        followupId = followupId,
    )

    /**
     * A send that did not go out: what was typed since wins; the prompt only comes back to an empty composer. The files
     * never left the strip, and a failed upload's chip says so and offers a retry.
     */
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
                // prompt that did not go out only comes back to an empty one. Refused as busy against every word
                // here — the server still winding down the last turn — the message goes where the composer would
                // have put it had it known: the account's queue in Extended mode, which sends it when the agent is
                // free; this device's otherwise, which waits with a growing pause and says so on the card.
                if (it.toCursorError()?.code == "agent_busy") {
                    if (capabilities.value.accountQueue && !graph.session.isDemo) queueOnAccount(text, images, emptyList(), options) else enqueue(text, images, emptyList(), options, refusedAsBusy = true)
                } else {
                    restoreDraft(text, images, it)
                }
            }
            sending.value = false
        }
    }

    private fun enqueue(text: String, images: List<PendingAttachment>, attached: List<PendingFile>, options: FollowUpModelState, refusedAsBusy: Boolean = false) {
        graph.followUps.enqueue(
            agentId,
            text,
            images.map { DraftImage(it.id, it.image) },
            planMode = options.planMode,
            modelId = options.override?.model?.id,
            modelParams = options.override?.params.orEmpty(),
            modelDisplayName = options.override?.label,
            // The queued message carries the references its files' uploads settled on, so it goes out by them when its turn comes.
            files = attached.map { DraftFile(it.id, it.file.withUpload(graph.attachmentUploads.ref(it.id) ?: it.file.upload)) },
            refusedAsBusy = refusedAsBusy,
        )
        draft.value = ""
        attachments.value = emptyList()
        files.value = emptyList()
        graph.attachmentUploads.forget(attached.map { it.id })
        graph.followUps.clearDraft(agentId)
    }

    /** Takes a queued follow-up back into the composer; a draft already there is queued in its place, so nothing is lost. */
    fun editQueued(id: String) {
        val displaced = !draft.value.isBlank() || attachments.value.isNotEmpty() || files.value.isNotEmpty()
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

    /** Clears this chat's cached transcript and traces and fetches it again (see `ConversationRepository.reloadTranscript`). */
    fun reloadTranscript() = graph.conversations.reloadTranscript(agentId)

    /** The reader neared the oldest turn shown: the turns before it are paged in (see [ConversationState.hasOlder]). */
    fun loadOlder() = graph.conversations.loadOlder(agentId)

    /** The reader's Retry on the turns whose activity could not be read (see [TraceStatus.failed]). */
    fun retryTraces() = graph.conversations.retryTraces(agentId)

    /**
     * The redacted account of this chat's load, for the reader to share from the transcript when a load fails: the
     * same block Settings › Advanced exports (see `TranscriptDiagnostics`) — window bounds, runs and their order,
     * each turn's trace state, the live follow, the last errors; no message text.
     */
    suspend fun loadDiagnosticsReport(): String = graph.transcriptDiagnosticsReport(agentId)

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
        /** How many of the newest rows have their markdown parsed with the rows, ahead of the screen: a phone's worth and the next page. */
        const val PRIMED_ROWS = 24
    }

    class Factory(private val graph: AppGraph, private val agentId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConversationViewModel(graph, agentId) as T
    }
}
