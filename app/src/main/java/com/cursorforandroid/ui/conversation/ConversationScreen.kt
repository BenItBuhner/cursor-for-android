package com.cursorforandroid.ui.conversation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.share.ShareTarget
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.MenuItem
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.RunningGlyph
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rememberImagePicker
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.compose.rememberComposerMenuActions
import com.cursorforandroid.ui.home.ModelSheet
import com.cursorforandroid.ui.home.NoModelRow
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * One chat: header with the agent's name and repo · branch (and a button to its pull request once it has one), the
 * transcript, and the follow-up composer.
 *
 * The transcript is a bottom-anchored (`reverseLayout`) list, which is what keeps it stable while a run streams: the
 * newest item grows upward from the bottom edge without moving anything the reader is looking at, and a reader who
 * has scrolled up stays put. New items snap the list back to the bottom only while the reader is following along.
 */
@Composable
fun ConversationScreen(
    graph: AppGraph,
    agentId: String,
    onBack: (() -> Unit)?,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSidebar: (() -> Unit)? = null,
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
    val attachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val picker by viewModel.modelPicker.collectAsStateWithLifecycle()
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.clearToast()
        }
    }
    // Coming back to the foreground: the network may have taken the stream down while the app was away, or the run
    // finished meanwhile. On the first composition the initial load is still in flight and this is a no-op.
    LifecycleStartEffect(agentId) {
        viewModel.revalidate()
        onStopOrDispose { }
    }

    val items = conversation.items
    val isActive = conversation.runStatus?.isActive == true || conversation.isStreaming
    val showWorking = isActive && items.lastOrNull().let { it !is AssistantMessage || !it.isStreaming }
    // Replies reference screenshots and recordings by their VM path; resolving them needs this agent's id.
    val markdownMedia = remember(agentId) { MarkdownMediaContext(agentId, graph.media) }

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
    val newestKey = items.lastOrNull()?.id
    LaunchedEffect(items.size, newestKey, showWorking) {
        if (following) listState.requestScrollToItem(0)
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
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
                Box {
                    FlatIconButton(CursorIcons.More, "More", onClick = { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                        MenuItem(if (isPinned) "Unpin" else "Pin", CursorIcons.Pin) { menuOpen = false; viewModel.togglePinned() }
                        MenuItem("Refresh", CursorIcons.Refresh) { menuOpen = false; viewModel.reload() }
                        MenuItem("Open on cursor.com", CursorIcons.ExternalLink) { menuOpen = false; agent?.url?.let(uriHandler::openUri) }
                        MenuItem("Copy link", CursorIcons.Copy) { menuOpen = false; agent?.url?.let { clipboard.setText(AnnotatedString(it)) } }
                        if (isActive) MenuItem("Stop", CursorIcons.Stop) { menuOpen = false; viewModel.cancelRun() }
                        if (agent?.isArchived == true) {
                            MenuItem("Unarchive", CursorIcons.Archive) { menuOpen = false; viewModel.unarchive() }
                        } else {
                            MenuItem("Archive", CursorIcons.Archive) { menuOpen = false; viewModel.archive(onDone = { onBack?.invoke() }) }
                        }
                        MenuItem("Delete", CursorIcons.Trash, tint = colors.red) { menuOpen = false; confirmDelete = true }
                    }
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val paneWidth = Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()
            LazyColumn(
                state = listState,
                reverseLayout = true,
                // Not fillMaxSize: a short transcript then sizes to its content and reads from the top. Once it
                // overflows, the items dissolve at whichever edge still has transcript past it rather than clipping
                // flat against the header or the composer.
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).scrollEdgeFade(listState, reverseLayout = true),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showWorking) {
                    item("working") {
                        // A dropped connection is not the run's problem: the agent keeps working while the stream is
                        // re-established, so the glyph keeps stepping and only the caption says what is going on.
                        val caption = when {
                            conversation.runStatus == RunStatus.CREATING -> "Starting…"
                            conversation.isReconnecting -> "Reconnecting…"
                            else -> "Working…"
                        }
                        Row(paneWidth, verticalAlignment = Alignment.CenterVertically) {
                            RunningGlyph(size = 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(caption, style = type.base, color = colors.textTertiary)
                        }
                    }
                }
                items(items.asReversed(), key = { it.id }) { item ->
                    CompositionLocalProvider(LocalMarkdownMedia provides markdownMedia) {
                        TimelineItemView(item, paneWidth)
                    }
                }
                if (!conversation.isLoading && items.isEmpty()) {
                    item("empty") {
                        Text(
                            if (conversation.transcriptUnavailable) "The transcript isn't available for this chat." else conversation.error ?: "Nothing here yet.",
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

        conversation.error?.takeIf { items.isNotEmpty() }?.let { err ->
            Row(
                Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(err, style = type.small, color = colors.red, maxLines = 2)
            }
        }

        val archived = agent?.isArchived == true
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            ComposerBox(
                value = draft,
                onValueChange = viewModel::setDraft,
                placeholder = if (archived) "Unarchive to follow up" else "Follow up…",
                onSend = viewModel::send,
                canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && !isSending && !archived,
                isRunning = isActive,
                onStop = viewModel::cancelRun,
                isSending = isSending,
                plusMenu = plusMenu,
                attachments = attachments,
                onRemoveAttachment = viewModel::removeAttachment,
                onAddAttachments = viewModel::addAttachments,
                onAttachmentError = viewModel::showMessage,
                // The chip names the model the chat runs on and, like on cursor.com/agents, switches it for the next
                // follow-up; an archived chat takes no follow-ups, so there is nothing to switch.
                modelLabel = picker.chipLabel,
                onModel = if (archived) null else ({ modelSheet = true }),
                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
            )
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.elevated,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            shape = CursorTheme.shapes.xl,
            title = { Text("Delete chat?", style = type.sectionTitle) },
            text = { Text("This permanently deletes the agent and its transcript. Archive it instead if you might need it later.", style = type.base) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onDone = onDeleted) }) { Text("Delete", style = type.baseMedium, color = colors.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", style = type.baseMedium, color = colors.textSecondary) } },
        )
    }
}

/** How far (px) the newest item may be scrolled past before the reader counts as having left the bottom. */
private const val BottomTolerancePx = 48
