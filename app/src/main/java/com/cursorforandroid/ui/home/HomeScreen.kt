package com.cursorforandroid.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.MediaMarkup
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.ChatOverflowMenu
import com.cursorforandroid.ui.agents.RenameChatDialog
import com.cursorforandroid.ui.agents.SnoozeChatDialog
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.Dot
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.Pill
import com.cursorforandroid.ui.components.PullRequestPill
import com.cursorforandroid.ui.components.RunningGlyph
import com.cursorforandroid.ui.components.SelectorChip
import com.cursorforandroid.ui.components.SelectorRow
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.pullRequestTint
import com.cursorforandroid.ui.components.rememberImagePicker
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.share.ShareTarget
import com.cursorforandroid.ui.compose.NewAgentViewModel
import com.cursorforandroid.ui.compose.rememberComposerMenuActions
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat

/**
 * The "New Chat" pane — the home of the official app: context selectors, the composer, then the recent chats
 * list with preview cards (cursor.com/agents). On phones a 44dp header carries the sidebar toggle.
 *
 * Sending opens the new chat through [onLaunchOpen] right away, before the server has answered, and leaves the
 * composer empty behind it: the launch is on its own from there (see [NewAgentViewModel.launch]), so this pane is
 * ready for the next chat whatever the last one is still waiting on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    graph: AppGraph,
    listState: AgentListUiState,
    onOpenSidebar: (() -> Unit)?,
    onOpenAgent: (AgentRow) -> Unit,
    onLaunchOpen: (agentId: String) -> Unit,
    rowActions: AgentRowActions,
    modifier: Modifier = Modifier,
) {
    val viewModel: NewAgentViewModel = viewModel(factory = NewAgentViewModel.Factory(graph))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var repoSheet by rememberSaveable { mutableStateOf(false) }
    var branchSheet by rememberSaveable { mutableStateOf(false) }
    var deviceSheet by rememberSaveable { mutableStateOf(false) }
    var modelSheet by rememberSaveable { mutableStateOf(false) }

    // The Chats filters chosen in the sidebar's menu apply here just the same (the sidebar search does not), so the two
    // lists never disagree about which chats are visible; the cards are newest first.
    val recent = listState.recentRows
    val pickImages = rememberImagePicker(
        currentCount = state.attachments.size,
        onPicked = viewModel::addAttachments,
        onError = viewModel::reportError,
    )
    val plusMenu = rememberComposerMenuActions(graph, onPickFiles = pickImages)
    val share by graph.share.offer.collectAsStateWithLifecycle()
    LaunchedEffect(share?.generation, share?.target) {
        val draft = share ?: return@LaunchedEffect
        if (draft.target != ShareTarget.NewChat) return@LaunchedEffect
        viewModel.applyShare(draft.text, draft.attachments, draft.warning)
        graph.share.consume(draft.generation)
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        if (onOpenSidebar != null) {
            CursorHeader(leading = { FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar) })
        }
        // The list scrolls edge to edge; the last row must still clear the navigation bar (48dp with three buttons).
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // The fade sits inside the IME padding so it tracks the visible viewport when the keyboard is up.
        val recentState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().imePadding().scrollEdgeFade(recentState),
            state = recentState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (onOpenSidebar != null) 8.dp else 48.dp, bottom = 32.dp + navigationBar),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item("composer") {
                Column(Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()) {
                    SelectorRow {
                        val selectedRepo = state.selectedRepo
                        val repoLabel = when {
                            state.noRepo -> "No repository"
                            selectedRepo != null -> selectedRepo.shortName
                            state.isLoadingRepos -> "Loading…"
                            else -> "Repository"
                        }
                        SelectorChip(repoLabel, onClick = { repoSheet = true }, icon = CursorIcons.Repo, modifier = Modifier.weight(1f, fill = false))
                        if (!state.noRepo) {
                            // A blank ref leaves the starting point to the repository's default branch.
                            SelectorChip(state.ref.ifBlank { "default" }, onClick = { branchSheet = true }, icon = CursorIcons.GitBranch)
                        }
                        SelectorChip(state.deviceLabel, onClick = { deviceSheet = true }, icon = deviceIcon(state.selectedDevice))
                    }
                    ComposerBox(
                        value = state.prompt,
                        onValueChange = viewModel::setPrompt,
                        placeholder = "Ask Cursor to build, fix bugs, explore",
                        onSend = { viewModel.launch(onOpen = onLaunchOpen) },
                        canSend = state.canLaunch,
                        isSending = state.isLaunching,
                        minLines = 3,
                        plusMenu = plusMenu,
                        commands = commands,
                        attachments = state.attachments,
                        onRemoveAttachment = viewModel::removeAttachment,
                        onAddAttachments = viewModel::addAttachments,
                        onAttachmentError = viewModel::reportError,
                        modelLabel = state.modelLabel,
                        onModel = { modelSheet = true },
                        // Plan mode is a pill beside "+" rather than a suffix on the model chip, as on cursor.com/agents.
                        planMode = state.planMode,
                        onPlanMode = viewModel::setPlanMode,
                    )
                    state.error?.let {
                        Row(Modifier.padding(top = 8.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(it, style = type.small, color = colors.red)
                        }
                    }
                }
            }
            item("gap") { Spacer(Modifier.height(26.dp)) }
            if (recent.isEmpty() && listState.hasLoaded) {
                item("empty") {
                    // A failed list request is not "no chats": say what happened (offline, rejected key, ...).
                    val error = listState.error
                    Text(
                        error ?: "No chats yet",
                        style = type.base,
                        color = if (error != null) colors.red else colors.textQuaternary,
                        modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(top = 24.dp, start = 7.dp, end = 7.dp),
                    )
                }
            }
            items(recent, key = { it.agent.id }) { row ->
                RecentChatRow(row, onClick = { onOpenAgent(row) }, actions = rowActions, modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth(), nowMillis = listState.nowMillis)
            }
        }
    }

    if (repoSheet) {
        RepositorySheet(
            repos = state.repositories,
            recent = state.recentRepositories,
            selected = state.selectedRepo,
            noRepo = state.noRepo,
            loading = state.isLoadingRepos,
            unavailable = state.reposUnavailable,
            onSelect = viewModel::selectRepo,
            onRefresh = viewModel::refreshRepositories,
            onDismiss = { repoSheet = false },
        )
    }
    if (branchSheet) {
        BranchSheet(
            repo = state.selectedRepo,
            branches = state.branches,
            selected = state.ref,
            onSelect = viewModel::setRef,
            onDismiss = { branchSheet = false },
        )
    }
    if (deviceSheet) {
        DeviceSheet(
            devices = state.devices,
            selected = state.selectedDevice,
            loading = state.isLoadingDevices,
            onSelect = viewModel::selectDevice,
            onRefresh = viewModel::refreshDevices,
            onDismiss = { deviceSheet = false },
        )
    }
    if (modelSheet) {
        ModelSheet(
            models = state.models,
            selectedModel = state.selectedModel,
            selectedVariant = state.selectedVariant,
            planMode = state.planMode,
            autoCreatePr = state.autoCreatePr,
            loading = state.isLoadingModels,
            unavailable = state.modelsUnavailable,
            onPlanMode = viewModel::setPlanMode,
            onAutoCreatePr = viewModel::setAutoCreatePr,
            onRetry = viewModel::refreshModels,
            onSelect = viewModel::selectModel,
            onDismiss = { modelSheet = false },
            pinnedIds = state.pinnedModelIds,
            onTogglePin = viewModel::togglePinnedModel,
        )
    }
}

/**
 * Recent chat row from cursor.com/agents: a preview card on the left, then the title with the unread dot and a
 * metadata line (state glyph · model · repo · age). Long-press opens the same chat menu as the sidebar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentChatRow(
    row: AgentRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = AppClock.now(),
    actions: AgentRowActions? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val agent = row.agent
    val shape = CursorTheme.shapes.xl
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var snoozeOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (actions != null) {
                    Modifier
                        .clip(shape)
                        .combinedClickable(
                            interactionSource = interaction,
                            indication = ripple(color = colors.base),
                            onClick = onClick,
                            onLongClick = { menuOpen = true },
                        )
                } else {
                    Modifier.pressable(onClick, shape)
                },
            )
            .padding(horizontal = 6.dp, vertical = CursorDimens.recentRowGap / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewCard(row)
        Spacer(Modifier.width(CursorDimens.previewToTitle))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.name, style = type.rowMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (row.indicator == AgentIndicator.Unread) {
                    Spacer(Modifier.width(7.dp))
                    Dot(colors.unreadDot, size = CursorDimens.recentDot)
                }
                if (row.indicator == AgentIndicator.Error) {
                    Spacer(Modifier.width(7.dp))
                    Dot(colors.red, size = CursorDimens.recentDot)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    row.isSnoozed -> Icon(CursorIcons.Clock, "Snoozed", tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
                    agent.hasPullRequest -> Icon(CursorIcons.GitPullRequest, row.pullRequest?.label ?: "Pull request", tint = pullRequestTint(row.pullRequest), modifier = Modifier.size(14.dp))
                    agent.hasBranch -> Icon(CursorIcons.GitBranch, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp))
                    row.indicator == AgentIndicator.Running -> RunningGlyph(size = 14.dp, color = colors.iconTertiary)
                    else -> Icon(CursorIcons.Sparkle, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
                }
                agent.modelName?.let { Text(it, style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val workspace = agent.envName?.takeIf { it.contains('#') } ?: agent.repoShortName
                workspace?.let { Text(it, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                Text(TimeFormat.relativeShort(agent.updatedAtMillis, nowMillis), style = type.small, color = colors.textQuaternary)
            }
        }
    }
        if (actions != null) {
            ChatOverflowMenu(
                row = row,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onRename = { menuOpen = false; renameOpen = true },
                onSnooze = { menuOpen = false; snoozeOpen = true },
                actions = actions,
            )
            if (renameOpen) {
                RenameChatDialog(
                    initialName = agent.name,
                    onConfirm = { name -> renameOpen = false; actions.onRename(row, name) },
                    onDismiss = { renameOpen = false },
                )
            }
            if (snoozeOpen) {
                SnoozeChatDialog(
                    onPick = { until -> snoozeOpen = false; actions.onSnooze(row, until) },
                    onDismiss = { snoozeOpen = false },
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(row: AgentRow) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val agent = row.agent
    CursorCard(Modifier.size(width = CursorDimens.previewCardWidth, height = CursorDimens.previewCardHeight), shape = CursorTheme.shapes.lg) {
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            when {
                agent.hasPullRequest -> PullRequestPill(row.pullRequest)
                row.isSnoozed -> Pill("Snoozed", icon = CursorIcons.Clock)
                row.indicator == AgentIndicator.Running -> Pill("Working", icon = CursorIcons.Sparkle)
                row.indicator == AgentIndicator.Error -> Pill("Failed", icon = CursorIcons.Warning, tint = colors.red, fill = colors.red.copy(alpha = 0.14f))
                agent.hasBranch -> Pill("Branch", icon = CursorIcons.GitBranch)
                !agent.summary.isNullOrBlank() -> Text(
                    remember(agent.summary) { MediaMarkup.stripped(agent.summary.orEmpty()) },
                    style = type.code.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = colors.textTertiary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Icon(CursorIcons.Cube, null, tint = colors.iconQuaternary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepositorySheet(
    repos: List<Repository>,
    recent: List<Repository>,
    selected: Repository?,
    noRepo: Boolean,
    loading: Boolean,
    unavailable: Boolean,
    onSelect: (Repository?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var filter by rememberSaveable { mutableStateOf("") }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        // Picking a row plays the sheet's hide animation before the selection is applied.
        fun pick(repo: Repository?) {
            onSelect(repo)
            dismiss()
        }
        SheetHeader("Repository") {
            if (loading) SpinnerRing(modifier = Modifier.padding(end = 8.dp)) else FlatIconButton(CursorIcons.Refresh, "Refresh repositories", onClick = onRefresh)
        }
        SheetSearchField(value = filter, onValueChange = { filter = it }, placeholder = "Filter repositories")
        Spacer(Modifier.height(6.dp))
        // The list keys rows on the URL, so duplicates from the catalogue must go before they reach the LazyColumn.
        fun matches(repo: Repository) = repo.slug.contains(filter, ignoreCase = true)
        val recentVisible = recent.distinctBy { it.url }.filter(::matches)
        val recentSlugs = recent.map { it.slug.lowercase() }.toSet()
        val restVisible = repos.distinctBy { it.url }.filter { matches(it) && it.slug.lowercase() !in recentSlugs }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
            item("none") {
                SheetRow(title = "No repository", subtitle = "Empty cloud VM", checked = noRepo, icon = CursorIcons.Cloud) { pick(null) }
                HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (unavailable && repos.isEmpty()) {
                item("unavailable") {
                    Text(
                        "Cursor couldn't list your GitHub repositories right now (the endpoint is heavily rate limited). Refresh later or start without one.",
                        style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            if (recentVisible.isEmpty() && restVisible.isEmpty() && repos.isNotEmpty()) {
                item("no-match") {
                    Text("No repositories match \"$filter\"", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            items(recentVisible, key = { "recent:${it.url}" }) { repo ->
                SheetRow(title = repo.shortName, subtitle = repo.slug.substringBeforeLast('/', ""), checked = !noRepo && repo.url == selected?.url, icon = CursorIcons.Repo) { pick(repo) }
            }
            // Recent activity (last week, at most ten) sits above the catalogue; the hairline is the only mark.
            if (recentVisible.isNotEmpty() && restVisible.isNotEmpty()) {
                item("recent-divider") {
                    HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
            }
            items(restVisible, key = { it.url }) { repo ->
                SheetRow(title = repo.shortName, subtitle = repo.slug.substringBeforeLast('/', ""), checked = !noRepo && repo.url == selected?.url, icon = CursorIcons.Repo) { pick(repo) }
            }
        }
    }
}

/** Small 12sp label above a group of sheet rows ("Options", a model's name above its variants). */
@Composable
internal fun SheetSectionLabel(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp))
}

@Composable
internal fun SheetSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(colors.fillFaint, shape)
            .border(CursorDimens.hairline, colors.strokeSubtle, shape)
            .height(38.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Search, null, tint = colors.iconTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = type.base.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = type.base, color = colors.textQuaternary); inner() } },
        )
    }
}

/** Sheet list row: optional 17px glyph, title with an optional 12sp detail line, accent check when selected. */
@Composable
internal fun SheetRow(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .pressable(onClick, CursorTheme.shapes.base)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) {
            Spacer(Modifier.width(12.dp))
            Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}
