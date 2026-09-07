package com.cursorforandroid.ui.conversation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AssistantMessage
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
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

@Composable
fun ConversationScreen(
    graph: AppGraph,
    agentId: String,
    onBack: (() -> Unit)?,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
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
    val pickImages = rememberImagePicker(currentCount = attachments.size, onPicked = viewModel::addAttachments, onError = viewModel::showMessage)
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 48
        }
    }
    LaunchedEffect(conversation.items.size, conversation.items.lastOrNull()?.hashCode(), conversation.isStreaming) {
        if (isAtBottom && conversation.items.isNotEmpty()) listState.scrollToItem(conversation.items.lastIndex)
    }

    val isActive = conversation.runStatus?.isActive == true || conversation.isStreaming
    // Replies reference screenshots and recordings by their VM path; resolving them needs this agent's id.
    val markdownMedia = remember(agentId) { MarkdownMediaContext(agentId, graph.media) }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorHeader(
            title = agent?.name ?: "Chat",
            subtitle = agent?.let { a -> listOfNotNull(a.repoShortName, a.branchName).joinToString(" · ").ifBlank { null } },
            leading = { if (onBack != null) FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack) },
            trailing = {
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
            val items = conversation.items
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (conversation.isLoading && items.isEmpty()) {
                    item("loading") {
                        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            SpinnerRing(size = 14.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Loading…", style = type.base, color = colors.textQuaternary)
                        }
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
                items(items, key = { it.id }) { item ->
                    CompositionLocalProvider(LocalMarkdownMedia provides markdownMedia) {
                        TimelineItemView(item, Modifier.widthIn(max = CursorDimens.composerMaxWidth))
                    }
                }
                if (isActive && items.lastOrNull().let { it !is AssistantMessage || !it.isStreaming }) {
                    item("working") {
                        Row(Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RunningGlyph(size = 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (conversation.runStatus == RunStatus.CREATING) "Starting…" else "Working…", style = type.base, color = colors.textTertiary)
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !isAtBottom && items.size > 2,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .cursorSurface(colors.elevated, colors.stroke, CircleShape)
                        .pressable({ scope.launch { listState.animateScrollToItem(items.lastIndex.coerceAtLeast(0)) } }, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CursorIcons.ChevronDown, "Scroll to bottom", tint = colors.iconSecondary, modifier = Modifier.size(18.dp))
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data ->
                Snackbar(snackbarData = data, containerColor = colors.elevated, contentColor = colors.textPrimary, shape = CursorTheme.shapes.lg)
            }
        }

        conversation.error?.takeIf { conversation.items.isNotEmpty() }?.let { err ->
            Text(err, style = type.small, color = colors.red, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            ComposerBox(
                value = draft,
                onValueChange = viewModel::setDraft,
                placeholder = if (agent?.isArchived == true) "Unarchive to follow up" else "Follow up…",
                onSend = viewModel::send,
                canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && !isSending && agent?.isArchived != true,
                isRunning = isActive,
                onStop = viewModel::cancelRun,
                onPlus = pickImages,
                attachments = attachments,
                onRemoveAttachment = viewModel::removeAttachment,
                modelLabel = agent?.modelDisplayName,
                onModel = null,
                footerExtra = { if (isSending) { Spacer(Modifier.width(8.dp)); SpinnerRing() } },
                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.elevated,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            shape = CursorTheme.shapes.xl,
            title = { Text("Delete chat?", style = type.title) },
            text = { Text("This permanently deletes the agent and its transcript. Archive it instead if you might need it later.", style = type.base) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onDone = onDeleted) }) { Text("Delete", style = type.baseMedium, color = colors.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", style = type.baseMedium, color = colors.textSecondary) } },
        )
    }
}
