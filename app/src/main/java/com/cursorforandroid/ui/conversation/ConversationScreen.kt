package com.cursorforandroid.ui.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.TraceStatus
import com.cursorforandroid.data.repo.RecordFallback
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CarriedFile
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.StretchSteps
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.DesktopEligibility
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.share.ShareTarget
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.ui.agents.RenameChatDialog
import com.cursorforandroid.ui.agents.SnoozeChatDialog
import com.cursorforandroid.ui.components.ChatHeader
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorMenu
import com.cursorforandroid.ui.components.CursorMenuItem
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.HeaderClearance
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.LocalRunStopConfirmation
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.RunInterruption
import com.cursorforandroid.ui.components.RunStopDialog
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.components.rememberRunStopConfirmation
import com.cursorforandroid.ui.components.ShimmerText
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.composerDockPadding
import com.cursorforandroid.ui.components.AttachmentCounts
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rememberFilePicker
import com.cursorforandroid.ui.components.rememberMediaPicker
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.compose.rememberComposerMenuActions
import com.cursorforandroid.ui.compose.rememberComposerVoice
import com.cursorforandroid.ui.files.FileOpenRequestSaver
import com.cursorforandroid.ui.files.FullFileDialog
import com.cursorforandroid.ui.files.FullFileResolver
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.home.ModelSheet
import com.cursorforandroid.ui.media.ConversationMedia
import com.cursorforandroid.ui.home.NoModelRow
import com.cursorforandroid.ui.panel.ConversationPanel
import com.cursorforandroid.ui.panel.DesktopDialog
import com.cursorforandroid.ui.panel.DesktopState
import com.cursorforandroid.ui.panel.LocalPanelGraph
import com.cursorforandroid.ui.panel.PanelViewModel
import com.cursorforandroid.ui.panel.SidePanel
import com.cursorforandroid.ui.panel.rememberPanelActions
import com.cursorforandroid.ui.panel.rememberSidePanelState
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * Whether the working row belongs above the transcript. It is redundant while the reply itself is arriving — the
 * text is the progress — but a dropped connection is not visible anywhere else, and the window in which one can
 * drop while a reply is the newest item is the longest there is. So a reconnect always brings the row back, which
 * is what stops a reply that stopped mid-sentence from looking like an agent that stopped thinking.
 */
internal fun ConversationState.showsWorkingRow(): Boolean {
    val active = runStatus?.isActive == true || isStreaming
    return active && (isReconnecting || items.lastOrNull().let { it !is AssistantMessage || !it.isStreaming })
}

/**
 * The working row's words. "Starting…" is a run the account has started and that is still booting — never the turn
 * under way while a message waits behind it on the card (Bennett's frame of 2026-09-22 19:42: his message to a
 * Project's busy coordinator under "Starting…" while that turn ran on).
 */
internal fun ConversationState.workingCaption(): String = when {
    runStatus == RunStatus.CREATING -> "Starting…"
    isReconnecting -> "Reconnecting…"
    else -> "Working…"
}

/**
 * One chat: a header of controls alone (back, its pull request once it has one, the panel, the menu; the agent's name
 * is the header's accessibility label and the panel's header), the transcript, and the follow-up composer.
 *
 * The transcript follows the newest row while the reader is at the bottom, bottom-anchored, and holds what is on
 * screen, top-anchored, once they have scrolled away or opened a dropdown (see [TranscriptScroll]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    graph: AppGraph,
    agentId: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenSidebar: (() -> Unit)? = null,
    /** Where the transcript's worker cards and the panel send the reader: another chat (a primary, a side chat, a Project's coordinator). */
    onOpenAgent: ((String) -> Unit)? = null,
    /**
     * The chat was switched to from the hardware keyboard (Ctrl+Tab, Ctrl+1 … Ctrl+0): the composer takes the caret,
     * and [onComposerFocused] says the ask was taken. The keyboard app decides whether its own keys come up, which
     * with a hardware keyboard attached they do not.
     */
    focusComposer: Boolean = false,
    onComposerFocused: () -> Unit = {},
) {
    val viewModel: ConversationViewModel = viewModel(key = "conversation-$agentId", factory = ConversationViewModel.Factory(graph, agentId))
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    // The chat as last presented: its state and the rows drawn for it, computed off the main thread (see
    // [ConversationViewModel.presented]); everything below reads the two together so they agree.
    val presentedTranscript by viewModel.presented.collectAsStateWithLifecycle()
    val conversation = presentedTranscript.state
    val draft by viewModel.draftText.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()
    val isPinned by viewModel.isPinned.collectAsStateWithLifecycle()
    val isSnoozed by viewModel.isSnoozed.collectAsStateWithLifecycle()
    val attachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val files by viewModel.pendingFiles.collectAsStateWithLifecycle()
    val fileUploads by viewModel.fileUploads.collectAsStateWithLifecycle()
    val uploadHint by viewModel.uploadHint.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val thumbnails by viewModel.imageThumbnails.collectAsStateWithLifecycle()
    val picker by viewModel.modelPicker.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    val goal by viewModel.goal.collectAsStateWithLifecycle()
    val hiddenNotices by viewModel.hiddenNotices.collectAsStateWithLifecycle()
    val extendedMode by graph.extendedMode.enabled.collectAsStateWithLifecycle(initialValue = false)
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val accountControls by viewModel.controls.collectAsStateWithLifecycle()
    // The account's queue as the card shows it, projected from the very frame the transcript is drawn from: a message
    // the transcript files under its run leaves the card in the same composition, whatever the last queue read said
    // (see QueuePlacement); read off two frames, the card and the bubble could both show it, as they did on Bennett's
    // phone (2026-09-20).
    val controls = remember(accountControls, conversation.queuePlacement) { accountControls.placed(conversation.queuePlacement) }
    val isDemo = graph.session.isDemo
    // A Project coordinator's cards name its workers by the list's live rows and open their chats (either mode).
    val agentList by graph.agents.state.collectAsStateWithLifecycle()
    val agentsById = remember(agentList.agents) { agentList.agents.associateBy { it.id } }
    // A subagent's row names its model off the catalog, and draws where it runs against where this chat does.
    val models by graph.catalog.models.collectAsStateWithLifecycle()
    val placement = SubagentPlacement.of(agent?.envType)
    val subagents = presentedTranscript.subagents
    val subagentRuns = conversation.subagentRuns
    // The transcript's rows answer the question they show and stop the step they show through the account
    // (Extended mode); with the surfaces off the hands are null and the rows stay read-only.
    // A Project's coordinator speaks to the user through its SendMessage tool; its plain replies are its working
    // notes. The chat is read as a coordinator's on any of three words, whichever comes first: the account calls it a
    // Project, the account's record says its prompts were sent in Project mode, or — needing no account at all —
    // its own transcript carries the coordinator's tools, under any name and however an earlier build filed them
    // (see [CoordinatorTranscript]). The list's word arrives late or not at all on a large account; the content is
    // in hand from the first frame. Decided with the rows, off the main thread (see [TranscriptPresenter]).
    val coordinatorMode = presentedTranscript.coordinatorMode
    val outgoing by viewModel.outgoingStatuses.collectAsStateWithLifecycle()
    // A file a tool call names, tapped: the full-file viewer over the chat (pictures, recordings and sounds open the
    // media viewer from the row instead). Kept across a rotation and a process death, like the store sheet.
    var openFile by rememberSaveable(agentId, stateSaver = FileOpenRequestSaver) { mutableStateOf<FileOpenRequest?>(null) }
    val transcriptControls = remember(controls, capabilities, agentsById, onOpenAgent, coordinatorMode, outgoing, models, placement, subagents, subagentRuns) {
        TranscriptControls(
            state = controls,
            onAnswer = if (capabilities.interactions && !isDemo) ({ callId, answers -> viewModel.answerQuestion(callId, answers) }) else null,
            onCancelToolCall = if (capabilities.steering && !isDemo) ({ callId -> viewModel.cancelToolCall(callId) }) else null,
            onOpenAgent = onOpenAgent,
            agentById = { id -> agentsById[id] },
            coordinatorMode = coordinatorMode,
            onReloadTranscript = viewModel::reloadTranscript,
            // A message sent from here rides in the transcript from the tap: its send's progress and any failure on its bubble.
            outgoing = outgoing,
            onRetryOutgoing = viewModel::retryOutgoing,
            onEditOutgoing = viewModel::editOutgoing,
            onDismissNotice = viewModel::dismissInlineNotice,
            onOpenFile = { openFile = it },
            onAskToCopyFile = if (isDemo) null else viewModel::askToCopyFileIntoWorkspace,
            models = models,
            subagents = subagents,
            placement = placement,
            subagentActivity = graph.subagentActivity::of,
            subagentRuns = subagentRuns,
        )
    }
    // The "+" menu's two pickers: the gallery — images alone in the default mode, images and videos as real files in
    // Extended mode — and, in Extended mode, the document picker for files of any type.
    val extendedFiles = capabilities.promptFiles && !isDemo
    val counts = AttachmentCounts.of(attachments, files)
    val pickMedia = rememberMediaPicker(
        extended = extendedFiles,
        counts = counts,
        onPickedImages = viewModel::addAttachments,
        onPickedFiles = viewModel::addFiles,
        onError = viewModel::showMessage,
    )
    val pickFiles = rememberFilePicker(counts = counts, onPickedFiles = viewModel::addFiles, onError = viewModel::showMessage)
    val plusMenu = rememberComposerMenuActions(graph, onPickMedia = pickMedia, onPickFiles = if (extendedFiles) pickFiles else null)
    val voice = rememberComposerVoice(graph)
    val share by graph.share.offer.collectAsStateWithLifecycle()
    LaunchedEffect(share?.generation, share?.target) {
        val incoming = share ?: return@LaunchedEffect
        val target = incoming.target as? ShareTarget.Chat ?: return@LaunchedEffect
        if (target.agentId != agentId) return@LaunchedEffect
        viewModel.applyShare(incoming.text, incoming.attachments, incoming.warning)
        graph.share.consume(incoming.generation)
    }
    val snackbar = remember { SnackbarHostState() }
    // The turns just past the top edge are composed ahead of time, so the keyboard leaving uncovers rows that are
    // already built rather than building them on the frames of its animation (see TranscriptPrefetchStrategy).
    val listState = rememberLazyListState(prefetchStrategy = remember { TranscriptPrefetchStrategy() })
    val transcriptScroll = rememberTranscriptScroll(listState, agentId)
    val scope = rememberCoroutineScope()
    // The pull past the newest message that catches the chat up (see CatchUpOverscroll), drawn as the sidebar's pull
    // to refresh is, from the transcript's bottom edge (see CatchUpIndicator). Read only where it is drawn, and its
    // status only by the indicator: a frame of the pull recomposes nothing, an answer the indicator alone.
    val density = LocalDensity.current
    val catchUpPull = remember(agentId, density) { with(density) { CatchUpPull(CatchUpPullThreshold.toPx()) } }
    val readerScroll = rememberReaderScroll(transcriptScroll, pull = catchUpPull, canCatchUp = viewModel::canCatchUp, onCatchUp = viewModel::catchUp)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var snoozeOpen by rememberSaveable { mutableStateOf(false) }
    // Every tap here that would stop, pause or interrupt the run asks first while the setting is on (see
    // RunStopConfirmation): the composer's Stop, the menu's, the queues' Send now, the panel's controls.
    val stopConfirmation = rememberRunStopConfirmation(graph.prefs)
    val haptics = rememberHaptics()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.clearToast()
        }
    }
    // Coming back to the foreground: the network may have taken the stream down while the app was away, or the run
    // finished meanwhile. On the first composition the initial load is still in flight and this is a no-op. Stopping
    // lets the run go for the notification service to watch, rather than streaming into a screen nobody can see.
    LifecycleStartEffect(agentId) {
        viewModel.resume()
        onStopOrDispose { viewModel.pause() }
    }

    // The items as the transcript shows them: every cached call re-read by this build, in a coordinator's chat the
    // brief remark after an injected turn folded under the turn's row (see [CoordinatorTranscript.present]), and the
    // agent's goal calls lifted out of their stretches of work into rows of their own (see [GoalTranscript.lift]) —
    // and the rows the list draws: the messages as themselves, and everything the agent did between two of them
    // behind one summary line (see [TranscriptRows]); the newest stretch reads "Working" while the run still
    // writes. Both come presented, a turn at a time, off the main thread (see [TranscriptPresenter]).
    val items = presentedTranscript.items
    val isActive = conversation.runStatus?.isActive == true || conversation.isStreaming
    LaunchedEffect(isActive) { if (!isActive) stopConfirmation.dismissFor(agentId) }
    ChatHaptics(agentId, conversation.runStatus, outgoing)
    // The notices among the rows the reader has put away for this chat (see NoticeCard.dismissKey, NoticeDismissals) are left out.
    val rows = remember(presentedTranscript, hiddenNotices) {
        val closed = NoticeDismissals.inlineKeys(hiddenNotices)
        if (closed.isEmpty()) presentedTranscript.rows else presentedTranscript.rows.filterNot { row -> row is TranscriptRow.Item && (row.item as? NoticeCard)?.dismissKey in closed }
    }
    // The steps of the stretches the reader has opened, listed after them as rows of their own (see StretchSteps):
    // the list lays out what is on screen of a stretch of hundreds of calls, not the whole of it on the tap.
    val openStretches = rememberOpenStretches(agentId)
    val stepCache = remember(agentId) { arrayOf<Map<String, TranscriptRow.Step>>(emptyMap()) }
    val listedRows by remember(rows, openStretches) {
        derivedStateOf { StretchSteps.list(rows, openStretches::of, stepCache[0]).also { stepCache[0] = it.steps }.rows }
    }
    // A live stretch says "Working" itself; the caption below the list is for a run with nothing on screen yet, and
    // for a connection being re-established, which only it can say.
    val showWorking = conversation.showsWorkingRow() && (conversation.isReconnecting || (rows.lastOrNull() as? TranscriptRow.Stretch)?.live != true)
    val canReadStores = capabilities.projects && !isDemo
    var openStorePath by rememberSaveable(agentId) { mutableStateOf<String?>(null) }

    val hasOlder = conversation.hasOlder
    val isLoadingOlder = conversation.isLoadingOlder
    val showTraces = items.isNotEmpty() && conversation.traceStatus.let { it.pending + it.expired + it.failed > 0 }
    val loadingRow = conversation.isLoading && items.isEmpty()
    val emptyRow = !conversation.isLoading && items.isEmpty()
    // Everything the list holds, top to bottom: the rows between the items above them and the working caption below.
    val order = remember(listedRows, showWorking, showTraces, hasOlder, loadingRow, emptyRow) {
        TranscriptOrder(
            above = listOfNotNull(
                LOADING_KEY.takeIf { loadingRow },
                EMPTY_KEY.takeIf { emptyRow },
                OLDER_KEY.takeIf { hasOlder && items.isNotEmpty() },
                TRACES_KEY.takeIf { showTraces },
            ),
            rows = listedRows,
            below = listOfNotNull(WORKING_KEY.takeIf { showWorking }),
        )
    }
    val following = transcriptScroll.following
    // The order the list was last measured in, which is what its scroll bounds are in: the fades read the two together.
    val listReversed by remember(listState) { derivedStateOf { listState.layoutInfo.reverseLayout } }
    // Following, a new row lands past the bottom edge, where the list's keyed anchoring leaves it; the list is taken
    // back to it. Pinned, it stays there.
    LaunchedEffect(listedRows.size, listedRows.lastOrNull()?.key, showWorking) {
        if (transcriptScroll.following) listState.requestScrollToItem(0)
    }
    // The chat opens on its newest turns; the ones before them are paged in when the reader scrolls up to them:
    // nearing the top is having only a few items above the top-most one in view — and only the reader's scroll asks:
    // a gesture arms one page, a fling under way keeps asking as its rows come into reach, and a transcript short
    // enough to show its oldest row at rest asks for nothing until the reader moves it. Until 0.3.47 the list asked
    // whenever its end was in view: a coordinator's turns fold into a few rows, so a Project of 240 turns paged itself
    // in whole, page after page, every turn's log replayed behind it, and again on every reopen (Bennett,
    // 2026-09-20). "Older messages" stays a tap away at rest.
    var olderArmed by remember(agentId) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (scrolling) olderArmed = true }
    }
    LaunchedEffect(listState, hasOlder, isLoadingOlder) {
        if (!hasOlder || isLoadingOlder) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.let { info -> TranscriptScroll.itemsAbove(info) to info.totalItemsCount } }
            .collect { (above, total) ->
                if ((olderArmed || listState.isScrollInProgress) && total > 0 && above < OlderTurnsPrefetchRows) {
                    olderArmed = false
                    viewModel.loadOlder()
                }
            }
    }

    // The right-side panel: the chat's files, changes, pull request, media, artifacts and usage, read off the same
    // repositories as the transcript plus the documented reads only it needs. Opened by the header button or a drag
    // toward the start edge across the chat; it is per chat, like the view model behind it.
    val panelViewModel: PanelViewModel = viewModel(key = "panel-$agentId", factory = PanelViewModel.Factory(graph, agentId))
    val panelState = rememberSidePanelState()
    val panel by panelViewModel.state.collectAsStateWithLifecycle()
    val panelActions = rememberPanelActions(panelViewModel, onToast = viewModel::showMessage, onOpenAgent = onOpenAgent, onAskToCopyFile = if (isDemo) null else viewModel::askToCopyFileIntoWorkspace)
    ChatKeyboardShortcuts(agentId, viewModel, panelState)
    TranscriptHitScroll(agentId, rows, conversation, transcriptScroll, viewModel)
    var composerFocusRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusComposer) {
        if (focusComposer) {
            composerFocusRequests++
            onComposerFocused()
        }
    }
    // Replies reference screenshots and recordings by their VM path; resolving them needs this agent's id. A path
    // into an Agent Store (`/cursor/stores/…`, a Project's context) is read through the account in Extended mode and
    // opens in the document sheet; without the account it points at the Project on cursor.com. A tapped figure opens
    // the media viewer among the chat's media in transcript order (see [ConversationMedia]), the artifacts the panel
    // has listed after them; the list is read at the tap, off the items as they are then.
    val latestItems = rememberUpdatedState(conversation.items)
    val latestArtifacts = rememberUpdatedState(panel.artifacts.valueOrNull.orEmpty())
    // A link to an agent — a coordinator cites its workers by id — opens that agent's chat the way a worker card does,
    // read by its id first when the list does not hold it (see [AgentLinkOpener]); a store sheet it was tapped in goes
    // away with the chat it stood over.
    val agentLinks = rememberAgentLinkOpener(graph, onOpenChat = onOpenAgent?.let { open -> { id: String -> openStorePath = null; open(id) } })
    val markdownMedia = remember(agentId, canReadStores, agentLinks) {
        MarkdownMediaContext(
            agentId, graph.media,
            canReadStores = canReadStores,
            onOpenStorePath = { path ->
                val target = storeRef(path, agentId)
                if (canReadStores && target != null) openStorePath = path.text else runCatching { uriHandler.openUri(StorePath.webUrl(target?.ownerId ?: agentId)) }
            },
            entries = { ConversationMedia.of(latestItems.value, latestArtifacts.value) },
            onOpenAgentLink = agentLinks::open,
        )
    }
    // The agent's VM desktop is reached from the header menu (Extended mode, `GetMachine` then noVNC), for the chats
    // that have one to show — the Agents Window's rule, a cloud composer, narrowed to the chats GetMachine would not
    // refuse (DesktopEligibility). It opens over the whole screen for the whole of the way there: the steps while the
    // machine is found, the failing step with a retry and the diagnostics to share, then the viewer.
    val canOpenDesktop = capabilities.remoteDesktop && !isDemo && DesktopEligibility.canOpen(agent)
    DesktopDialog(
        state = panel.desktop,
        agentName = agent?.name,
        onViewOnlyChange = panelActions::setDesktopViewOnly,
        onRetry = { viewOnly -> panelActions.openDesktop(viewOnly) },
        onFail = panelActions::failDesktop,
        onShare = panelActions::shareText,
        onClose = panelActions::closeDesktop,
    )

    SidePanel(
        state = panelState,
        modifier = modifier,
        panelContent = {
            // The panel's figures — generated images, recordings, artifacts — resolve through the same media context and
            // open into the same viewer as the transcript's, among the same pages.
            CompositionLocalProvider(LocalMarkdownMedia provides markdownMedia, LocalPanelGraph provides graph, LocalRunStopConfirmation provides stopConfirmation) {
                ConversationPanel(panel, panelActions, onClose = { scope.launch { panelState.close() } })
            }
        },
    ) {
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        val touchHeight = CursorDimens.minTouchTarget
        // Where the header's buttons stand in the margin beside the transcript's column (a wide pane), the header gives
        // its band to the transcript, which then reads up to the status bar; where they reach the column it keeps it.
        val headerClearance = remember { HeaderClearance() }
        ChatHeader(
            label = agent?.name ?: "Chat",
            clearance = headerClearance,
            leading = {
                when {
                    onBack != null -> FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack, touchHeight = touchHeight)
                    onOpenSidebar != null -> FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar, touchHeight = touchHeight)
                }
            },
            trailing = {
                // The pull request lives here and nowhere else in the chat: its own button beside the panel's, in the
                // same green glyph the list rows use for it. It is the one thing a reader most often leaves the chat
                // for, and the header stays in reach however long the transcript gets. The panel's header names the branch.
                agent?.prUrl?.let { prUrl ->
                    FlatIconButton(CursorIcons.GitPullRequest, "Open pull request", tint = colors.gitAdded, onClick = { uriHandler.openUri(prUrl) }, touchHeight = touchHeight)
                }
                // The panel's button: the sidebar glyph mirrored, for the sheet that comes in from the other side.
                FlatIconButton(CursorIcons.Sidebar, "Open panel", onClick = { scope.launch { panelState.open() } }, modifier = Modifier.scale(scaleX = -1f, scaleY = 1f), touchHeight = touchHeight)
                Box {
                    FlatIconButton(CursorIcons.More, "More", onClick = { menuOpen = true }, touchHeight = touchHeight)
                    CursorMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (agent?.let(AgentListOrganizer::canPin) != false) CursorMenuItem(if (isPinned) "Unpin" else "Pin", CursorIcons.Pin) { menuOpen = false; viewModel.togglePinned() }
                        // The public API has no rename; the demo renames its in-memory row, Extended mode the account's.
                        if (isDemo || extendedMode) CursorMenuItem("Rename", CursorIcons.Pencil) { menuOpen = false; renameOpen = true }
                        CursorMenuItem("Reload transcript", CursorIcons.Refresh) { menuOpen = false; viewModel.reloadTranscript() }
                        CursorMenuItem("Open on cursor.com", CursorIcons.ExternalLink) { menuOpen = false; agent?.url?.let(uriHandler::openUri) }
                        CursorMenuItem("Copy link", CursorIcons.Copy) { menuOpen = false; agent?.url?.let { clipboard.setText(AnnotatedString(it)) } }
                        CursorMenuItem("Share…", CursorIcons.Link) { menuOpen = false; panelActions.shareText(agent?.url ?: CursorEndpoints.webUrl(agentId)) }
                        // The load's redacted account of this chat (see [TranscriptDiagnostics]), from any chat that
                        // looks wrong — not only one that has failed outright: window bounds, runs and their order,
                        // each turn's trace state, the live follow, the last errors; no message text.
                        CursorMenuItem("Share diagnostics", CursorIcons.Warning) { menuOpen = false; scope.launch { panelActions.shareText(viewModel.loadDiagnosticsReport()) } }
                        // The agent's VM desktop (Extended mode): view it, or take control of it to try what it is
                        // building. A Remote Control chat's machine has no desktop to reach from here.
                        if (canOpenDesktop) {
                            CursorMenuItem("View desktop", CursorIcons.Eye) { menuOpen = false; panelActions.openDesktop(viewOnly = true) }
                            CursorMenuItem("Take control of desktop", CursorIcons.Desktop) { menuOpen = false; panelActions.openDesktop(viewOnly = false) }
                        }
                        if (isActive) CursorMenuItem("Stop", CursorIcons.Stop) { menuOpen = false; stopConfirmation.ask(RunInterruption.Stop, agentId, viewModel::cancelRun) }
                        if (agent?.isArchived != true) {
                            if (isSnoozed) {
                                CursorMenuItem("Unsnooze", CursorIcons.Clock) { menuOpen = false; viewModel.unsnooze() }
                            } else {
                                CursorMenuItem("Snooze", CursorIcons.Clock) { menuOpen = false; snoozeOpen = true }
                            }
                        }
                        if (agent?.isArchived == true) {
                            CursorMenuItem("Unarchive", CursorIcons.Archive) { menuOpen = false; viewModel.unarchive() }
                        } else {
                            CursorMenuItem("Archive", CursorIcons.Archive) { menuOpen = false; viewModel.archive(onDone = { onBack?.invoke() }) }
                        }
                    }
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.matchParentSize().readerBackdrop(readerScroll))
            val paneWidth = Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()
            // The column the rows are laid out in, measured whether or not there are any rows yet.
            Box(Modifier.align(Alignment.TopCenter).padding(horizontal = TranscriptGutter).then(paneWidth).then(headerClearance.transcriptColumn))
            // The items above and below the rows, by their keys in [order].
            val edgeItem: @Composable (String) -> Unit = { key ->
                when (key) {
                    WORKING_KEY -> {
                        // A dropped connection is not the run's problem: the agent keeps working while the stream
                        // is re-established, so the caption keeps shimmering and only its wording says what is
                        // going on. The caption is the whole indicator, as in the web chat: no glyph beside it.
                        Box(paneWidth) {
                            ShimmerText(conversation.workingCaption(), style = type.base)
                        }
                    }
                    // Where the window's traces stand, when not every turn shown has its activity: the turns being
                    // read or replayed, the ones whose logs Cursor no longer has, the ones that could not be read
                    // this time (with a Retry). Above the oldest turn shown, where the missing activity would be
                    // noticed; nothing when every turn is whole.
                    TRACES_KEY -> TraceStatusRow(conversation.traceStatus, onRetry = viewModel::retryTraces, modifier = paneWidth)
                    // Past the oldest turn shown: the turns before it, being paged in, or a tap away when the
                    // reader's scroll did not reach far enough to ask for them.
                    OLDER_KEY -> OlderTurnsRow(isLoading = isLoadingOlder, onLoad = viewModel::loadOlder, modifier = paneWidth)
                    EMPTY_KEY -> {
                        val failure = conversation.error ?: conversation.transcriptError?.let { "Couldn't load the transcript: $it" }
                        if (failure != null) {
                            // Nothing loaded at all: the failure is the screen, with the same two ways out, at
                            // the transcript's own margins rather than the dock's.
                            LoadErrorRow(
                                message = failure,
                                onRetry = viewModel::reload,
                                onShareDiagnostics = { scope.launch { panelActions.shareText(viewModel.loadDiagnosticsReport()) } },
                                modifier = paneWidth.padding(top = 32.dp),
                                docked = false,
                            )
                        } else {
                            Text(
                                if (conversation.transcriptUnavailable) "The transcript isn't available for this chat." else "Nothing here yet.",
                                style = type.base,
                                color = colors.textQuaternary,
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                    }
                    LOADING_KEY -> Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        SpinnerRing(size = 14.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading…", style = type.base, color = colors.textQuaternary)
                    }
                }
            }
            // The media context is the same for every row, so it is provided once around the list rather than
            // opening a provider scope per item.
            CompositionLocalProvider(
                LocalMarkdownMedia provides markdownMedia,
                LocalTranscriptControls provides transcriptControls,
                LocalDisclosureTaps provides transcriptScroll,
                LocalOpenStretches provides openStretches,
            ) {
                LazyColumn(
                    state = listState,
                    reverseLayout = following,
                    // The reader's scroll is the transcript's own, the same way in both orders (see readerScrolling).
                    userScrollEnabled = false,
                    // Not fillMaxSize: a short transcript then sizes to its content and reads from the top. Once it
                    // overflows, the items dissolve at whichever edge still has transcript past it rather than clipping
                    // flat against the header or the composer — or against the status bar, where the header has given
                    // its band back, so no row is cut through under the bar's icons and nothing is dimmed at rest. The
                    // fade is painted in the canvas colour: this list is resized on every frame the keyboard moves, and
                    // an offscreen dissolve would re-allocate and re-render a full-screen layer on each of them.
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .scrollEdgeFade(listState, reverseLayout = listReversed, surface = colors.canvas)
                        .readerScrolling(readerScroll)
                        .testTag("transcript"),
                    contentPadding = PaddingValues(start = TranscriptGutter, end = TranscriptGutter, top = 6.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(TranscriptItemSpacing),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // A following list is declared bottom-up, the newest row first (see TranscriptScroll). Each kind of
                    // item has one call site for both orders: a row declared from two would be a different group in
                    // each, and every switch would rebuild every row on screen and drop what the reader had opened.
                    fun edge(key: String) = item(key) { edgeItem(key) }
                    val (before, after) = if (following) order.below.asReversed() to order.above.asReversed() else order.above to order.below
                    before.forEach(::edge)
                    // Without a content type the lazy layout offers a scrolled-off user bubble's slot to an activity
                    // group, whose subtree shares nothing with it: the reuse always fails and costs more than it saves.
                    items(if (following) listedRows.asReversed() else listedRows, key = { it.key }, contentType = ::transcriptContentType) { row -> TranscriptRowView(row, paneWidth.then(stepAppearance(row))) }
                    after.forEach(::edge)
                }
                SideEffect { transcriptScroll.orient(following, order) }
            }
            // Out of the area's bottom edge, the top of the composer's stack; a word up there gives way as it rises.
            CatchUpIndicator(
                catchUpPull,
                viewModel.catchUpStatus,
                onSettled = viewModel::catchUpSettled,
                modifier = Modifier.align(Alignment.BottomCenter),
                onRise = { snackbar.currentSnackbarData?.dismiss() },
            )

            val jumpShown = !following && items.size > 2
            androidx.compose.animation.AnimatedVisibility(
                visible = jumpShown,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = JumpButtonGap),
                enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
            ) {
                Box(
                    Modifier
                        // The flat icon-button box: one step up from the composer's round buttons it floats above.
                        .size(CursorDimens.iconButton)
                        .cursorSurface(colors.elevated, colors.strokeStrong, CircleShape)
                        .pressable({ scope.launch { transcriptScroll.jumpToBottom() } }, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CursorIcons.ArrowDown, "Scroll to latest", tint = colors.iconPrimary, modifier = Modifier.size(16.dp))
                }
            }
            // A word up while the jump button is out sits above it, never over it: the button stays the reader's to
            // tap (a catch-up's answer lands there as the reader flings back to the newest message). Read at layout.
            val snackbarLift = animateDpAsState(if (jumpShown) CursorDimens.iconButton + JumpButtonGap else 0.dp, tween(160), label = "snackbar-lift")
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, -snackbarLift.value.roundToPx()) }) { data ->
                Snackbar(snackbarData = data, containerColor = colors.elevated, contentColor = colors.textPrimary, shape = CursorTheme.shapes.lg)
            }
        }

        val archived = agent?.isArchived == true
        // Extended mode keeps the queue on the account, where the desktop and the web keep theirs; otherwise on this device.
        val accountQueue = capabilities.accountQueue && !isDemo
        val willQueue = isActive || queue.isNotEmpty() || (accountQueue && controls.queue.isNotEmpty())
        // The composer and the strips over it dock at the bottom (composerDockPadding): the gutter at each side, and
        // under the box a gap a shade wider than the gutter, above the keyboard's edge while there is one and above
        // the navigation bar — or the window's edge — otherwise. The transcript above takes whatever height is left
        // and keeps its newest turn on the composer through the change, being a bottom-anchored list. Every card in
        // the stack is a dockedCard: stood in from the box's sides so its corners are concentric with the box's, the
        // same gap between each, whether one is stacked or five.
        Column(
            Modifier.fillMaxWidth().composerDockPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A fetch that did not go through, said rather than swallowed, in the server's own words: the load's
            // failure, or — with the runs answering and the transcript not — the transcript's, and Extended mode's
            // record refused with the documented endpoints standing in (never a quiet fallback that looks like the
            // chat itself; Bennett's 2026-09-20 frame: a Project shown as run activity alone, nothing saying why) —
            // each with the way to ask again and the load's diagnostics a tap away (the same redacted block Settings
            // exports), and an X that puts it away (see LoadNotices, NoticeDismissals). First in the stack, at the
            // seam between the transcript they are about and the strips under them: the queue keeps its place on the
            // box it came from, as the desktop stacks its trays.
            for (notice in LoadNotices.shown(conversation, hiddenNotices)) {
                key(notice.identity) {
                    LoadNoticeRow(
                        notice = notice,
                        onRetry = viewModel::reload,
                        onShareDiagnostics = { scope.launch { panelActions.shareText(viewModel.loadDiagnosticsReport()) } },
                        onDismiss = { viewModel.dismissNotice(notice) },
                        modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp),
                    )
                }
            }
            // The chat's goal, when it has one, stands over whatever is queued: the order the desktop stacks its
            // trays in above the composer. Each strip keeps the same width and the same gap to the next.
            goal?.let { current ->
                GoalStrip(goal = current, modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp))
            }
            // What is waiting to go out sits right above the box it came from, oldest first, one line each.
            if (queue.isNotEmpty()) {
                QueuedFollowUps(
                    queue = queue,
                    thumbnails = thumbnails,
                    onEdit = { viewModel.editQueued(it.id) },
                    // Sent now while a turn is under way, a message cancels that turn for it.
                    onSteer = { item ->
                        if (isActive) {
                            stopConfirmation.ask(RunInterruption.SendNow, agentId) { viewModel.steerQueued(item.id) }
                        } else {
                            haptics.perform(Haptic.Confirm)
                            viewModel.steerQueued(item.id)
                        }
                    },
                    onRemove = { viewModel.removeQueued(it.id) },
                    modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp),
                )
            }
            if (accountQueue && controls.queue.isNotEmpty()) {
                AccountQueueRows(
                    queue = controls.queue,
                    inFlightIds = controls.inFlightQueueIds,
                    // `SubmitPendingFollowupNow` sends the message in place of the turn under way.
                    onSendNow = { item ->
                        if (isActive) {
                            stopConfirmation.ask(RunInterruption.SendNow, agentId) { viewModel.queueSendNow(item.id) }
                        } else {
                            haptics.perform(Haptic.Confirm)
                            viewModel.queueSendNow(item.id)
                        }
                    },
                    onRemove = { viewModel.queueDelete(it.id) },
                    onUpdate = { item, text -> viewModel.queueUpdate(item.id, text) },
                    onEditing = { item, editing -> viewModel.queueMarkEditing(item.id, editing) },
                    // A queued message can be delivered into the turn under way as a steer while there is one to steer.
                    onSteerNow = if (capabilities.steering && isActive) ({ haptics.perform(Haptic.Confirm); viewModel.queueSteerNow(it.id) }) else null,
                    onMove = { item, up -> viewModel.queueMove(item.id, up) },
                    modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp),
                )
            }
            ComposerBox(
                value = draft,
                onValueChange = viewModel::setDraft,
                placeholder = when {
                    archived -> "Unarchive to follow up"
                    // A send now joins the queue rather than interrupting; the placeholder says so before the tap.
                    willQueue && accountQueue -> "Follow up (queues on your account)…"
                    willQueue -> "Follow up (sends when the turn ends)…"
                    else -> "Follow up…"
                },
                onSend = viewModel::send,
                // Free the moment send is tapped: the message, its files' uploads and its send are the transcript's from then on.
                canSend = (draft.isNotBlank() || attachments.isNotEmpty() || files.isNotEmpty()) && !archived,
                isRunning = isActive,
                onStop = { stopConfirmation.ask(RunInterruption.Stop, agentId, viewModel::cancelRun) },
                plusMenu = plusMenu,
                commands = commands,
                attachments = attachments,
                onRemoveAttachment = viewModel::removeAttachment,
                onAddAttachments = viewModel::addAttachments,
                onAttachmentError = viewModel::showMessage,
                files = files,
                onRemoveFile = viewModel::removeFile,
                fileUploads = fileUploads,
                onRetryFile = viewModel::retryFile,
                sendHint = uploadHint,
                mediaAgentId = agentId,
                media = graph.media,
                // The chip names the model the chat runs on and, like on cursor.com/agents, switches it for the next
                // follow-up; an archived chat takes no follow-ups, so there is nothing to switch.
                modelLabel = picker.chipLabel,
                onModel = if (archived) null else ({ modelSheet = true }),
                // The mode for the next run is a pill beside "+", as on cursor.com/agents, not a suffix on the chip:
                // Plan in either mode; Ask and Debug where the account's follow-up can carry them (Extended mode).
                modePill = picker.modePill,
                onModePill = viewModel::setModePill,
                extendedModes = capabilities.agentModes && !isDemo,
                focusRequests = composerFocusRequests,
                // An archived chat takes no follow-ups, so there is nothing to dictate into.
                voice = voice.takeUnless { archived },
                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).then(headerClearance.composerColumn).testTag("follow-up-composer"),
            )
        }
    }
    }

    openFile?.let { request ->
        val resolver = remember(graph) { FullFileResolver.of(graph.agentFileReads, { graph.extendedMode.capabilities().workspaceFiles && !graph.session.isDemo }, graph.media) }
        val viewer = LocalMediaViewer.current
        FullFileDialog(
            request,
            load = { ask -> resolver.resolve(agentId, request, CarriedFile.of(latestItems.value, request.path, request.callId), ask.force, ask.wake) },
            onClose = { openFile = null },
            // Bytes that turned out to be a picture, a recording or a sound are the media viewer's, never a text screen's.
            onOpenMedia = viewer?.let { v -> { entry -> openFile = null; v.open(agentId, listOf(entry), entry.src, null, fallback = entry, autoplay = entry.isPlayable) } },
            onAskToCopy = if (isDemo) null else { path -> openFile = null; viewModel.askToCopyFileIntoWorkspace(path) },
        )
    }

    openStorePath?.let { text -> StorePath.parse(text)?.let { path -> storeRef(path, agentId) } }?.let { ref ->
        CompositionLocalProvider(LocalMarkdownMedia provides markdownMedia) {
            StoreDocumentSheet(ref, graph.storeFiles, onDismiss = { openStorePath = null })
        }
    }

    if (modelSheet) {
        ModelSheet(
            models = picker.models,
            selectedModel = picker.selected?.model,
            selectedVariant = picker.selected?.variant,
            planMode = picker.planMode == true,
            autoCreatePr = false,
            loading = picker.isLoading,
            unavailable = picker.unavailable,
            onPlanMode = viewModel::setPlanMode,
            onAutoCreatePr = null,
            onRefresh = viewModel::refreshModels,
            onSelect = viewModel::selectModel,
            onDismiss = { modelSheet = false },
            pinnedIds = picker.pinnedModelIds,
            onTogglePin = viewModel::togglePinnedModel,
            // The chat's model has its own row only while the catalog cannot show it checked in the list: nothing
            // reports it (a chat started elsewhere, in default mode — Auto is assumed and the row says so), or the
            // catalog no longer offers it. Picking the row keeps whatever the chat has been using.
            noModelRow = when {
                picker.current != null -> null
                picker.currentAssumed -> NoModelRow("Current model", "Auto, assumed: Cursor doesn't report this chat's model to the app. Follow-ups keep the model it has been using.")
                else -> NoModelRow(
                    "Current model",
                    picker.currentLabel?.let { label -> listOfNotNull(label, picker.currentDetail).joinToString(" · ") } ?: "Keep the model this chat has been using",
                )
            },
        )
    }

    if (renameOpen) {
        RenameChatDialog(
            initialName = agent?.name.orEmpty(),
            onConfirm = { name -> renameOpen = false; viewModel.rename(name) },
            onDismiss = { renameOpen = false },
        )
    }
    if (snoozeOpen) {
        SnoozeChatDialog(
            onPick = { until -> snoozeOpen = false; viewModel.snooze(until) },
            onDismiss = { snoozeOpen = false },
        )
    }
    RunStopDialog(stopConfirmation)
    AgentLinkDialog(agentLinks)
}

/** The keys of the list's items that are not rows of the transcript (see [TranscriptOrder]). */
private const val WORKING_KEY = "working"
private const val TRACES_KEY = "traces"
private const val OLDER_KEY = "older"
private const val EMPTY_KEY = "empty"
private const val LOADING_KEY = "loading"

/** The transcript's side margins, inside which its rows take [CursorDimens.composerMaxWidth] at most. */
private val TranscriptGutter = 16.dp

/** The jump button's lift off the transcript's bottom edge. */
private val JumpButtonGap = 10.dp

/**
 * How many rows from the oldest one shown the reader may be before the turns before it are asked for: about a
 * screen's worth, so a page is on its way while the reader is still reading the one above it rather than when they
 * have reached its end (the insert above them costs nothing to what they are looking at; see [TranscriptPresenter]).
 */
private const val OlderTurnsPrefetchRows = 6

/**
 * A load that did not go through: the server's words, Retry, and "Share diagnostics" — the redacted load block
 * (window bounds, runs and their order, each turn's trace state, the live follow, the last errors) handed to the
 * share sheet, so a chat that would not load can be reported from where it failed. A card over the composer
 * ([LoadNoticeCard]) under a transcript that did load; the whole screen, in the transcript's place, when nothing did
 * (not [docked]: there is no box edge for it to line up with there).
 */
@Composable
internal fun LoadErrorRow(message: String, onRetry: () -> Unit, onShareDiagnostics: () -> Unit, modifier: Modifier = Modifier, docked: Boolean = true, onDismiss: (() -> Unit)? = null) {
    LoadNoticeRow(LoadNotice(LoadNotice.Kind.LoadError, message), onRetry, onShareDiagnostics, modifier, onDismiss = onDismiss, docked = docked)
}

/**
 * The account's record refused or failed and the documented endpoints stand in for it (see
 * [ConversationState.recordFallback]): what the server said, what is on screen because of it — the transcript's
 * prompts and replies (a coordinator's messages among them) and the activity of the runs whose logs the server
 * still has, not the activity of the older turns, which the record alone holds — and the two ways out: Retry,
 * which asks the record again, and the diagnostics. In
 * the same card as a failed load ([LoadNoticeCard]), quieter: a degradation, not a failure, so the glyph is not red.
 * The words are [LoadNotices.recordFallback]'s.
 */
@Composable
internal fun RecordFallbackRow(fallback: RecordFallback, onRetry: () -> Unit, onShareDiagnostics: () -> Unit, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    LoadNoticeRow(LoadNotices.recordFallback(fallback), onRetry, onShareDiagnostics, modifier, onDismiss = onDismiss)
}

/**
 * What the row says when the account's record could not be read and the documented endpoints stand in. Precisely
 * what the fallback lacks and nothing more: the prompts, the replies and a coordinator's messages are on screen
 * from the documented transcript and the runs' logs (Bennett's frame of 2026-09-20 showed both under this row), so
 * what is missing is the activity — the tool calls and thoughts — of turns whose log the server has let go (about
 * a day after the run), which only the account's record still holds.
 */
internal const val RECORD_FALLBACK_TITLE = "Account transcript unavailable"
/** Ahead of the request path and the server's answer, as sent and as received (see `RecordFallback.asked`). */
internal const val RECORD_FALLBACK_ASKED = "Asked:"
internal const val RECORD_FALLBACK_DETAIL = "Showing the transcript and the runs' logs. Turns older than about a day have no activity to show until the account's copy can be read again."

/**
 * The same row when Cursor's server failed to send the record, its retries spent (see `RecordFallback.serverError`):
 * a 5xx — Bennett's frame of 2026-09-23, a bare `HTTP 502` from the load balancer on one blob — or, with no answer
 * at all, a connection that kept dropping. The chat is not gone and the row does not say it is.
 */
internal const val RECORD_SERVER_ERROR_TITLE = "Cursor's server errored"
internal const val RECORD_UNREACHABLE_TITLE = "Couldn't reach Cursor's server"
internal const val RECORD_SERVER_ERROR_DETAIL = "Nothing is lost: the chat is still on the account, and the server failed to send it even after retrying. Showing the transcript and the runs' logs until it can be read again."
internal const val RECORD_UNREACHABLE_DETAIL = "Nothing is lost: the chat is still on the account, and the connection to Cursor kept failing even after retrying. Showing the transcript and the runs' logs until it can be read again."

/**
 * Where the activity of the turns shown stands when not every turn has it (see [TraceStatus]): "Loading the activity
 * of 4 turns…" while the logs are read or replayed; "Cursor no longer has the activity of 7 turns" once their logs
 * have expired with no copy here; "Couldn't load the activity of 3 turns" with a Retry when the network failed.
 * What used to be silent — text with no tool calls behind it, and no word why.
 */
@Composable
internal fun TraceStatusRow(status: TraceStatus, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    fun turns(n: Int) = if (n == 1) "1 turn" else "$n turns"
    Column(modifier.padding(vertical = 4.dp).testTag("trace-status"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (status.pending > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 12.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading the activity of ${turns(status.pending)}…", style = type.small, color = colors.textQuaternary)
            }
        }
        if (status.expired > 0) {
            Text("Cursor no longer has the activity of ${turns(status.expired)}; their replies are shown.", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(top = if (status.pending > 0) 4.dp else 0.dp))
        }
        if (status.failed > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (status.pending + status.expired > 0) 4.dp else 0.dp)) {
                Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text("Couldn't load the activity of ${turns(status.failed)}.", style = type.small, color = colors.red)
                Text(
                    "Retry",
                    style = type.small,
                    color = colors.textSecondary,
                    modifier = Modifier.pressable(onRetry, CursorTheme.shapes.base).padding(horizontal = 8.dp, vertical = 2.dp).testTag("retry-traces"),
                )
            }
        }
    }
}

/**
 * The row past the oldest turn shown, while the chat has older ones: "Loading older…" while they are being paged in
 * (their run records fetched, their traces read or replayed), else a line that asks for them — for a reader whose
 * scroll stopped just short of where the list asks by itself.
 */
@Composable
internal fun OlderTurnsRow(isLoading: Boolean, onLoad: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    // Both lines are the same height: scrolled to the top, the row is the one the list holds still, and a load
    // starting under the reader would otherwise move every row below it by the difference (22 px at 420 dpi).
    val inset = 4.dp
    Box(modifier.padding(vertical = 6.dp).testTag(if (isLoading) "loading-older" else "load-older"), contentAlignment = Alignment.Center) {
        if (isLoading) {
            Row(Modifier.padding(vertical = inset), verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 12.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading older…", style = type.small, color = colors.textQuaternary)
            }
        } else {
            Text(
                "Older messages",
                style = type.small,
                color = colors.textTertiary,
                modifier = Modifier.pressable(onLoad, CursorTheme.shapes.base).padding(horizontal = 12.dp, vertical = inset),
            )
        }
    }
}
