package com.cursorforandroid.ui.conversation

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.TraceStatus
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GoalTranscript
import com.cursorforandroid.share.ShareTarget
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.ui.agents.MenuItem
import com.cursorforandroid.ui.agents.RenameChatDialog
import com.cursorforandroid.ui.agents.SnoozeChatDialog
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.FigureLightbox
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.ShimmerText
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.keyboardInsetPadding
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rememberImagePicker
import com.cursorforandroid.ui.components.rememberLightboxState
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.compose.rememberComposerMenuActions
import com.cursorforandroid.ui.home.ModelSheet
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
 * One chat: header with the agent's name and repo · branch (and a button to its pull request once it has one), the
 * transcript, and the follow-up composer.
 *
 * The transcript is a bottom-anchored (`reverseLayout`) list, which is what keeps it stable while a run streams: the
 * newest item grows upward from the bottom edge without moving anything the reader is looking at, and a reader who
 * has scrolled up stays put. New items snap the list back to the bottom only while the reader is following along.
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
) {
    val viewModel: ConversationViewModel = viewModel(key = "conversation-$agentId", factory = ConversationViewModel.Factory(graph, agentId))
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val draft by viewModel.draftText.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()
    val isPinned by viewModel.isPinned.collectAsStateWithLifecycle()
    val isSnoozed by viewModel.isSnoozed.collectAsStateWithLifecycle()
    val attachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val thumbnails by viewModel.imageThumbnails.collectAsStateWithLifecycle()
    val picker by viewModel.modelPicker.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    val goal by viewModel.goal.collectAsStateWithLifecycle()
    val extendedMode by graph.extendedMode.enabled.collectAsStateWithLifecycle(initialValue = false)
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()
    val isDemo = graph.session.isDemo
    // A Project coordinator's cards name its workers by the list's live rows and open their chats (either mode).
    val agentList by graph.agents.state.collectAsStateWithLifecycle()
    val agentsById = remember(agentList.agents) { agentList.agents.associateBy { it.id } }
    // The transcript's rows answer the question they show and stop the step they show through the account
    // (Extended mode); with the surfaces off the hands are null and the rows stay read-only.
    // A Project's coordinator speaks to the user through its SendMessage tool; its plain replies are its working
    // notes. The chat is read as a coordinator's on any of three words, whichever comes first: the account calls it a
    // Project, the account's record says its prompts were sent in Project mode, or — needing no account at all —
    // its own transcript carries the coordinator's tools, under any name and however an earlier build filed them
    // (see [CoordinatorTranscript]). The list's word arrives late or not at all on a large account; the content is
    // in hand from the first frame.
    val coordinatorMode = agent?.looksLikeProject == true || conversation.isProjectConversation || remember(conversation.items) { CoordinatorTranscript.hasCoordinatorContent(conversation.items) }
    val transcriptControls = remember(controls, capabilities, agentsById, onOpenAgent, coordinatorMode) {
        TranscriptControls(
            state = controls,
            onAnswer = if (capabilities.interactions && !isDemo) ({ callId, answers -> viewModel.answerQuestion(callId, answers) }) else null,
            onCancelToolCall = if (capabilities.steering && !isDemo) ({ callId -> viewModel.cancelToolCall(callId) }) else null,
            onOpenAgent = onOpenAgent,
            agentById = { id -> agentsById[id] },
            coordinatorMode = coordinatorMode,
        )
    }
    val pickImages = rememberImagePicker(currentCount = attachments.size, onPicked = viewModel::addAttachments, onError = viewModel::showMessage)
    val plusMenu = rememberComposerMenuActions(graph, onPickFiles = pickImages)
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
    val scope = rememberCoroutineScope()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var snoozeOpen by rememberSaveable { mutableStateOf(false) }
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
    // agent's goal calls lifted out of their stretches of work into rows of their own (see [GoalTranscript.lift]).
    val items = remember(conversation.items, coordinatorMode) { GoalTranscript.lift(CoordinatorTranscript.present(conversation.items, coordinatorMode)) }
    val isActive = conversation.runStatus?.isActive == true || conversation.isStreaming
    // The rows the list draws: the messages as themselves, and everything the agent did between two of them behind
    // one summary line (see [TranscriptRows]); the newest stretch reads "Working" while the run still writes.
    val rows = remember(items, coordinatorMode, isActive) { TranscriptRows.of(items, coordinatorMode, runActive = isActive) }
    // A live stretch says "Working" itself; the caption below the list is for a run with nothing on screen yet, and
    // for a connection being re-established, which only it can say.
    val showWorking = conversation.showsWorkingRow() && (conversation.isReconnecting || (rows.lastOrNull() as? TranscriptRow.Stretch)?.live != true)
    // Replies reference screenshots and recordings by their VM path; resolving them needs this agent's id. A path
    // into an Agent Store (`/cursor/stores/…`, a Project's context) is read through the account in Extended mode and
    // opens in the document sheet; without the account it points at the Project on cursor.com.
    val lightbox = rememberLightboxState(agentId)
    val canReadStores = capabilities.projects && !isDemo
    var openStorePath by rememberSaveable(agentId) { mutableStateOf<String?>(null) }
    val markdownMedia = remember(agentId, lightbox, canReadStores) {
        MarkdownMediaContext(
            agentId, graph.media, lightbox,
            canReadStores = canReadStores,
            onOpenStorePath = { path ->
                val target = storeRef(path, agentId)
                if (canReadStores && target != null) openStorePath = path.text else runCatching { uriHandler.openUri(StorePath.webUrl(target?.ownerId ?: agentId)) }
            },
        )
    }

    // In a reversed list index 0 is the newest item, so "at the bottom" is "first item, (almost) no offset".
    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= BottomTolerancePx }
    }
    // Whether the reader wants to follow the newest content. Only the reader's own scrolls change it: a new item
    // arriving cannot knock the list out of follow mode.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling) following = atBottom }
    }
    val newestKey = rows.lastOrNull()?.key
    LaunchedEffect(rows.size, newestKey, showWorking) {
        if (following) listState.requestScrollToItem(0)
    }
    // The chat opens on its newest turns; the ones before them are paged in when the reader nears the top. In a
    // reversed list the top is the highest index, so nearing it is the last visible item being within a few rows of
    // the end. Asked once per approach: the repository ignores a request while one is under way.
    val hasOlder = conversation.hasOlder
    val isLoadingOlder = conversation.isLoadingOlder
    LaunchedEffect(listState, hasOlder, isLoadingOlder) {
        if (!hasOlder || isLoadingOlder) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.let { info -> (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount } }
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - OlderTurnsPrefetchRows) viewModel.loadOlder()
            }
    }

    // The right-side panel: the chat's files, changes, pull request, media, artifacts and usage, read off the same
    // repositories as the transcript plus the documented reads only it needs. Opened by the header button or a swipe
    // in from the end edge; it is per chat, like the view model behind it.
    val panelViewModel: PanelViewModel = viewModel(key = "panel-$agentId", factory = PanelViewModel.Factory(graph, agentId))
    val panelState = rememberSidePanelState()
    val panel by panelViewModel.state.collectAsStateWithLifecycle()
    val panelActions = rememberPanelActions(panelViewModel, onToast = viewModel::showMessage, onOpenAgent = onOpenAgent)
    // The agent's VM desktop is reached from the header menu (Extended mode, `GetMachine` then noVNC); it opens over
    // the whole screen, panel or no panel, and what went wrong on the way is said on the snackbar.
    val canOpenDesktop = capabilities.remoteDesktop && !isDemo && agent?.let { it.envType != EnvType.MACHINE && !it.isArchived } == true
    (panel.desktop as? DesktopState.Open)?.let { open ->
        DesktopDialog(
            session = open.session,
            agentName = agent?.name,
            onViewOnlyChange = panelActions::setDesktopViewOnly,
            onReconnect = { panelActions.openDesktop(open.session.viewOnly) },
            onClose = panelActions::closeDesktop,
        )
    }
    LaunchedEffect(panel.desktop) {
        when (val desktop = panel.desktop) {
            DesktopState.Opening -> viewModel.showMessage("Finding the agent's desktop…")
            is DesktopState.Failed -> {
                viewModel.showMessage(desktop.failure.message)
                panelActions.closeDesktop()
            }
            else -> Unit
        }
    }

    SidePanel(
        state = panelState,
        modifier = modifier,
        panelContent = {
            // The panel's figures — generated images, recordings, artifacts — resolve through the same media context and
            // open into the same lightbox as the transcript's.
            CompositionLocalProvider(LocalMarkdownMedia provides markdownMedia, LocalPanelGraph provides graph) {
                ConversationPanel(panel, panelActions, onClose = { scope.launch { panelState.close() } })
            }
        },
    ) {
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        CursorHeader(
            title = agent?.name ?: "Chat",
            subtitle = agent?.let { a -> listOfNotNull(a.repoShortName, a.branchName).joinToString(" · ").ifBlank { null } },
            leading = {
                when {
                    onBack != null -> FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack)
                    onOpenSidebar != null -> FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar)
                }
            },
            trailing = {
                // The pull request lives here and nowhere else in the chat: its own button beside the menu, in the same
                // green glyph the list rows use for it. It is the one thing a reader most often leaves the chat for, and
                // the header stays in reach however long the transcript gets. The subtitle already names the branch.
                agent?.prUrl?.let { prUrl ->
                    FlatIconButton(CursorIcons.GitPullRequest, "Open pull request", tint = colors.gitAdded, onClick = { uriHandler.openUri(prUrl) })
                }
                // The panel's button: the sidebar glyph mirrored, for the sheet that comes in from the other side.
                FlatIconButton(CursorIcons.Sidebar, "Open panel", onClick = { scope.launch { panelState.open() } }, modifier = Modifier.scale(scaleX = -1f, scaleY = 1f))
                Box {
                    FlatIconButton(CursorIcons.More, "More", onClick = { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                        MenuItem(if (isPinned) "Unpin" else "Pin", CursorIcons.Pin) { menuOpen = false; viewModel.togglePinned() }
                        // The public API has no rename; the demo renames its in-memory row, Extended mode the account's.
                        if (isDemo || extendedMode) MenuItem("Rename", CursorIcons.Pencil) { menuOpen = false; renameOpen = true }
                        MenuItem("Refresh", CursorIcons.Refresh) { menuOpen = false; viewModel.reload() }
                        MenuItem("Open on cursor.com", CursorIcons.ExternalLink) { menuOpen = false; agent?.url?.let(uriHandler::openUri) }
                        MenuItem("Copy link", CursorIcons.Copy) { menuOpen = false; agent?.url?.let { clipboard.setText(AnnotatedString(it)) } }
                        MenuItem("Share…", CursorIcons.Link) { menuOpen = false; panelActions.shareText(agent?.url ?: CursorEndpoints.webUrl(agentId)) }
                        // The agent's VM desktop (Extended mode): view it, or take control of it to try what it is
                        // building. A Remote Control chat's machine has no desktop to reach from here.
                        if (canOpenDesktop) {
                            MenuItem("View desktop", CursorIcons.Eye) { menuOpen = false; panelActions.openDesktop(viewOnly = true) }
                            MenuItem("Take control of desktop", CursorIcons.Desktop) { menuOpen = false; panelActions.openDesktop(viewOnly = false) }
                        }
                        if (isActive) MenuItem("Stop", CursorIcons.Stop) { menuOpen = false; viewModel.cancelRun() }
                        if (agent?.isArchived != true) {
                            if (isSnoozed) {
                                MenuItem("Unsnooze", CursorIcons.Clock) { menuOpen = false; viewModel.unsnooze() }
                            } else {
                                MenuItem("Snooze", CursorIcons.Clock) { menuOpen = false; snoozeOpen = true }
                            }
                        }
                        if (agent?.isArchived == true) {
                            MenuItem("Unarchive", CursorIcons.Archive) { menuOpen = false; viewModel.unarchive() }
                        } else {
                            MenuItem("Archive", CursorIcons.Archive) { menuOpen = false; viewModel.archive(onDone = { onBack?.invoke() }) }
                        }
                    }
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val paneWidth = Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()
            // The media context is the same for every row, so it is provided once around the list rather than
            // opening a provider scope per item.
            CompositionLocalProvider(LocalMarkdownMedia provides markdownMedia, LocalTranscriptControls provides transcriptControls) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    // Not fillMaxSize: a short transcript then sizes to its content and reads from the top. Once it
                    // overflows, the items dissolve at whichever edge still has transcript past it rather than clipping
                    // flat against the header or the composer. The fade is painted in the canvas colour: this list is
                    // resized on every frame the keyboard moves, and an offscreen dissolve would re-allocate and
                    // re-render a full-screen layer on each of them.
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).scrollEdgeFade(listState, reverseLayout = true, surface = colors.canvas),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (showWorking) {
                        item("working") {
                            // A dropped connection is not the run's problem: the agent keeps working while the stream
                            // is re-established, so the caption keeps shimmering and only its wording says what is
                            // going on. The caption is the whole indicator, as in the web chat: no glyph beside it.
                            val caption = when {
                                conversation.runStatus == RunStatus.CREATING -> "Starting…"
                                conversation.isReconnecting -> "Reconnecting…"
                                else -> "Working…"
                            }
                            Box(paneWidth) {
                                ShimmerText(caption, style = type.base)
                            }
                        }
                    }
                    // Without a content type the lazy layout offers a scrolled-off user bubble's slot to an activity
                    // group, whose subtree shares nothing with it: the reuse always fails and costs more than it saves.
                    items(rows.asReversed(), key = { it.key }, contentType = { it::class }) { row ->
                        TranscriptRowView(row, paneWidth)
                    }
                    // Where the window's traces stand, when not every turn shown has its activity: the turns being
                    // read or replayed, the ones whose logs Cursor no longer has, the ones that could not be read
                    // this time (with a Retry). Above the oldest turn shown, where the missing activity would be
                    // noticed; nothing when every turn is whole.
                    if (items.isNotEmpty() && conversation.traceStatus.let { it.pending + it.expired + it.failed > 0 }) {
                        item("traces") {
                            TraceStatusRow(conversation.traceStatus, onRetry = viewModel::retryTraces, modifier = paneWidth)
                        }
                    }
                    // Past the oldest turn shown: the turns before it, being paged in, or a tap away when the
                    // reader's scroll did not reach far enough to ask for them.
                    if (hasOlder && items.isNotEmpty()) {
                        item("older") {
                            OlderTurnsRow(isLoading = isLoadingOlder, onLoad = viewModel::loadOlder, modifier = paneWidth)
                        }
                    }
                    if (!conversation.isLoading && items.isEmpty()) {
                        item("empty") {
                            Text(
                                when {
                                    conversation.transcriptError != null -> "Couldn't load the transcript: ${conversation.transcriptError}"
                                    conversation.transcriptUnavailable -> "The transcript isn't available for this chat."
                                    else -> conversation.error ?: "Nothing here yet."
                                },
                                style = type.base,
                                color = colors.textQuaternary,
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                    }
                    if (conversation.isLoading && items.isEmpty()) {
                        item("loading") {
                            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                SpinnerRing(size = 14.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Loading…", style = type.base, color = colors.textQuaternary)
                            }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !following && items.size > 2,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
            ) {
                Box(
                    Modifier
                        // The flat icon-button box: one step up from the composer's round buttons it floats above.
                        .size(CursorDimens.iconButton)
                        .cursorSurface(colors.elevated, colors.strokeStrong, CircleShape)
                        .pressable({ scope.launch { listState.animateScrollToItem(0) } }, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CursorIcons.ArrowDown, "Scroll to latest", tint = colors.iconPrimary, modifier = Modifier.size(16.dp))
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data ->
                Snackbar(snackbarData = data, containerColor = colors.elevated, contentColor = colors.textPrimary, shape = CursorTheme.shapes.lg)
            }
        }

        // A fetch that did not go through, said under the transcript rather than swallowed: the load's failure, or —
        // with the runs answering and the transcript not — the transcript's, with the way to ask again.
        (conversation.error ?: conversation.transcriptError?.let { "Couldn't refresh the transcript: $it" })?.takeIf { items.isNotEmpty() }?.let { err ->
            Row(
                Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 4.dp).testTag("load-error"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(err, style = type.small, color = colors.red, maxLines = 2, modifier = Modifier.weight(1f))
                Text(
                    "Retry",
                    style = type.small,
                    color = colors.textSecondary,
                    modifier = Modifier.pressable(viewModel::reload, CursorTheme.shapes.base).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        val archived = agent?.isArchived == true
        // Extended mode keeps the queue on the account, where the desktop and the web keep theirs; otherwise on this device.
        val accountQueue = capabilities.accountQueue && !isDemo
        val willQueue = isActive || queue.isNotEmpty() || (accountQueue && controls.queue.isNotEmpty())
        // The composer rests 10dp above the keyboard's edge while there is one, and above the navigation bar otherwise
        // (keyboardInsetPadding); the transcript above takes whatever height is left and keeps its newest turn on the
        // composer through the change, being a bottom-anchored list.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).keyboardInsetPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                    onSteer = { viewModel.steerQueued(it.id) },
                    onRemove = { viewModel.removeQueued(it.id) },
                    modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp),
                )
            }
            if (accountQueue && controls.queue.isNotEmpty()) {
                AccountQueueRows(
                    queue = controls.queue,
                    inFlightIds = controls.inFlightQueueIds,
                    onSendNow = { viewModel.queueSendNow(it.id) },
                    onRemove = { viewModel.queueDelete(it.id) },
                    onUpdate = { item, text -> viewModel.queueUpdate(item.id, text) },
                    onEditing = { item, editing -> viewModel.queueMarkEditing(item.id, editing) },
                    // A queued message can be delivered into the turn under way as a steer while there is one to steer.
                    onSteerNow = if (capabilities.steering && isActive) ({ viewModel.queueSteerNow(it.id) }) else null,
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
                canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && !isSending && !archived,
                isRunning = isActive,
                onStop = viewModel::cancelRun,
                isSending = isSending,
                plusMenu = plusMenu,
                commands = commands,
                attachments = attachments,
                onRemoveAttachment = viewModel::removeAttachment,
                onAddAttachments = viewModel::addAttachments,
                onAttachmentError = viewModel::showMessage,
                // The chip names the model the chat runs on and, like on cursor.com/agents, switches it for the next
                // follow-up; an archived chat takes no follow-ups, so there is nothing to switch.
                modelLabel = picker.chipLabel,
                onModel = if (archived) null else ({ modelSheet = true }),
                // The mode for the next run is a pill beside "+", as on cursor.com/agents, not a suffix on the chip:
                // Plan in either mode; Ask and Debug where the account's follow-up can carry them (Extended mode).
                modePill = picker.modePill,
                onModePill = viewModel::setModePill,
                extendedModes = capabilities.agentModes && !isDemo,
                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
            )
        }
    }
    }

    // Above the transcript rather than inside the row that opened it: the lazy list disposes a row as soon as it
    // scrolls off, which a running agent's replies do on their own, and that used to close the viewer with it.
    FigureLightbox(lightbox, graph.media, agentId)
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
            onRetry = viewModel::refreshModels,
            onSelect = viewModel::selectModel,
            onDismiss = { modelSheet = false },
            pinnedIds = picker.pinnedModelIds,
            onTogglePin = viewModel::togglePinnedModel,
            // The chat's model has its own row only while the catalog cannot show it checked in the list: unknown
            // (started elsewhere), or no longer offered. It reads as the label when there is one.
            noModelRow = if (picker.current != null) null else NoModelRow("Current model", picker.currentLabel ?: "Keep the model this chat has been using"),
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
}

/** How far (px) the newest item may be scrolled past before the reader counts as having left the bottom. */
private const val BottomTolerancePx = 48

/** How many rows from the oldest one shown the reader may be before the turns before it are asked for. */
private const val OlderTurnsPrefetchRows = 3

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
    Box(modifier.padding(vertical = 6.dp).testTag(if (isLoading) "loading-older" else "load-older"), contentAlignment = Alignment.Center) {
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 12.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading older…", style = type.small, color = colors.textQuaternary)
            }
        } else {
            Text(
                "Older messages",
                style = type.small,
                color = colors.textTertiary,
                modifier = Modifier.pressable(onLoad, CursorTheme.shapes.base).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
