package com.cursorforandroid.ui.conversation

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.CursorChip
import com.cursorforandroid.ui.components.CursorIconButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.RunningGlyph
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.StatusIndicator
import com.cursorforandroid.ui.components.CursorTopBar
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

@Composable
fun ConversationScreen(
    graph: AppGraph,
    agentId: String,
    onOpenSidebar: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ConversationViewModel = viewModel(key = "conversation-$agentId", factory = ConversationViewModel.Factory(graph, agentId))
    val colors = CursorTheme.colors
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val draft by viewModel.draftText.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()
    val isPinned by viewModel.isPinned.collectAsStateWithLifecycle()
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
    // Follow the stream while the user is parked at the bottom.
    LaunchedEffect(conversation.items.size, conversation.items.lastOrNull()?.hashCode(), conversation.isStreaming) {
        if (isAtBottom && conversation.items.isNotEmpty()) listState.scrollToItem(conversation.items.lastIndex)
    }

    val isActive = conversation.runStatus?.isActive == true || conversation.isStreaming

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorTopBar(
            title = agent?.name ?: "Agent",
            subtitle = agent?.repoShortName?.let { repo -> listOfNotNull(repo, agent?.branchName).joinToString(" · ") },
            leading = {
                if (onBack != null) {
                    CursorIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
                } else if (onOpenSidebar != null) {
                    CursorIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar)
                }
            },
            trailing = {
                Box {
                    CursorIconButton(Icons.Outlined.MoreHoriz, "More", onClick = { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.surface) {
                        MenuEntry(if (isPinned) "Unpin" else "Pin", Icons.Outlined.PushPin) { menuOpen = false; viewModel.togglePinned() }
                        MenuEntry("Refresh", Icons.Outlined.Refresh) { menuOpen = false; viewModel.reload() }
                        MenuEntry("Open in browser", Icons.AutoMirrored.Outlined.OpenInNew) { menuOpen = false; agent?.url?.let(uriHandler::openUri) }
                        MenuEntry("Copy link", Icons.Outlined.ContentCopy) { menuOpen = false; agent?.url?.let { clipboard.setText(AnnotatedString(it)) } }
                        if (isActive) MenuEntry("Stop run", Icons.Outlined.Stop) { menuOpen = false; viewModel.cancelRun() }
                        if (agent?.isArchived == true) {
                            MenuEntry("Unarchive", Icons.Outlined.Unarchive) { menuOpen = false; viewModel.unarchive() }
                        } else {
                            MenuEntry("Archive", Icons.Outlined.Archive) { menuOpen = false; viewModel.archive(onDone = { onBack?.invoke() }) }
                        }
                        MenuEntry("Delete", Icons.Outlined.DeleteOutline, tint = colors.danger) { menuOpen = false; confirmDelete = true }
                    }
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val items = conversation.items
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                agent?.let { a ->
                    item("meta") {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            a.repoSlug?.let { CursorChip(it, icon = CursorIcons.Repo, mono = true) }
                            CursorChip(
                                when (a.envType) {
                                    com.cursorforandroid.domain.EnvType.MACHINE -> "My machine"
                                    com.cursorforandroid.domain.EnvType.POOL -> a.envName?.let { "Pool · $it" } ?: "Team pool"
                                    else -> "Cloud"
                                },
                                icon = if (a.envType == com.cursorforandroid.domain.EnvType.CLOUD) CursorIcons.Cloud else CursorIcons.Desktop,
                            )
                            a.modelDisplayName?.let { CursorChip(it) }
                        }
                    }
                }
                if (conversation.isLoading && items.isEmpty()) {
                    item("loading") {
                        Row(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            SpinnerRing(size = 16.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Loading conversation…", style = CursorTheme.typography.secondary, color = colors.textPlaceholder)
                        }
                    }
                }
                if (!conversation.isLoading && items.isEmpty()) {
                    item("empty") {
                        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(CursorIcons.Cube, null, tint = colors.textPlaceholder, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (conversation.transcriptUnavailable) "The transcript isn't available for this agent." else conversation.error ?: "Nothing here yet.",
                                style = CursorTheme.typography.secondary,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
                items(items, key = { it.id }) { item -> TimelineItemView(item) }
                if (isActive && items.lastOrNull().let { it !is com.cursorforandroid.domain.AssistantMessage || !it.isStreaming }) {
                    item("working") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RunningGlyph(size = 14.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (conversation.runStatus == RunStatus.CREATING) "Starting the agent…" else "Working…",
                                style = CursorTheme.typography.secondary,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !isAtBottom && items.size > 2,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                CursorIconButton(
                    Icons.Outlined.KeyboardArrowDown, "Scroll to bottom",
                    onClick = { scope.launch { listState.animateScrollToItem(items.lastIndex.coerceAtLeast(0)) } },
                    size = 40.dp,
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data ->
                Snackbar(snackbarData = data, containerColor = colors.surfaceRaised, contentColor = colors.textPrimary, shape = CursorTheme.shapes.md)
            }
        }

        conversation.error?.takeIf { conversation.items.isNotEmpty() }?.let { err ->
            Text(err, style = CursorTheme.typography.caption, color = colors.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).navigationBarsPadding().imePadding()) {
            ComposerBox(
                value = draft,
                onValueChange = viewModel::setDraft,
                placeholder = if (agent?.isArchived == true) "Unarchive to follow up" else "Follow up…",
                onSend = viewModel::send,
                canSend = draft.isNotBlank() && !isSending && agent?.isArchived != true,
                isRunning = isActive,
                onStop = viewModel::cancelRun,
                onPlus = null,
                footer = {
                    if (isActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpinnerRing(size = 12.dp, color = colors.statusRunning)
                            Spacer(Modifier.width(6.dp))
                            Text("Running", style = CursorTheme.typography.caption, color = colors.textSecondary)
                        }
                    } else if (isSending) {
                        SpinnerRing(size = 12.dp)
                    } else {
                        agent?.let { a ->
                            StatusIndicator(AgentListOrganizer.indicatorFor(a, LocalAgentState(readMarkers = mapOf(a.id to Long.MAX_VALUE))), size = 8.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    a.isArchived -> "Archived"
                                    a.runStatus == RunStatus.ERROR -> "Failed"
                                    a.runStatus == RunStatus.CANCELLED -> "Cancelled"
                                    else -> "Idle"
                                },
                                style = CursorTheme.typography.caption,
                                color = colors.textPlaceholder,
                            )
                        }
                    }
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            shape = CursorTheme.shapes.xl,
            title = { Text("Delete agent?", style = CursorTheme.typography.sheetTitle) },
            text = { Text("This permanently deletes the agent and its transcript. Archive it instead if you might need it later.", style = CursorTheme.typography.secondary) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onDone = onDeleted) }) { Text("Delete", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = colors.textSecondary) } },
        )
    }
}

@Composable
private fun MenuEntry(label: String, icon: ImageVector, tint: Color = CursorTheme.colors.textPrimary, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = CursorTheme.typography.body, color = tint) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}
