package com.cursorforandroid.ui.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.Dot
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.Pill
import com.cursorforandroid.ui.components.RunningGlyph
import com.cursorforandroid.ui.components.SelectorChip
import com.cursorforandroid.ui.components.SelectorRow
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rememberImagePicker
import com.cursorforandroid.ui.compose.NewAgentViewModel
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * The "New Chat" pane — the home of the official app: context selectors, the composer, then the recent chats
 * list with preview cards (cursor.com/agents). On phones a 44dp header carries the sidebar toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    graph: AppGraph,
    listState: AgentListUiState,
    onOpenSidebar: (() -> Unit)?,
    onOpenAgent: (AgentRow) -> Unit,
    onLaunched: (Agent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: NewAgentViewModel = viewModel(factory = NewAgentViewModel.Factory(graph))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var repoSheet by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var editingRef by remember { mutableStateOf(false) }

    val recent = remember(listState.sections) { listState.sections.flatMap { it.rows }.distinctBy { it.agent.id }.sortedByDescending { it.agent.updatedAtMillis } }
    val pickImages = rememberImagePicker(
        currentCount = state.attachments.size,
        onPicked = viewModel::addAttachments,
        onError = viewModel::reportError,
    )

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        if (onOpenSidebar != null) {
            CursorHeader(leading = { FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar) })
        }
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (onOpenSidebar != null) 12.dp else 50.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item("composer") {
                Column(Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()) {
                    SelectorRow {
                        val repoLabel = when {
                            state.noRepo -> "No repository"
                            state.selectedRepo != null -> state.selectedRepo!!.shortName
                            state.isLoadingRepos -> "Loading…"
                            else -> "Repository"
                        }
                        SelectorChip(repoLabel, onClick = { repoSheet = true })
                        if (!state.noRepo) {
                            if (editingRef) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(
                                        value = state.ref,
                                        onValueChange = viewModel::setRef,
                                        singleLine = true,
                                        textStyle = type.code.copy(color = colors.textPrimary, fontSize = type.base.fontSize),
                                        cursorBrush = SolidColor(colors.textPrimary),
                                        modifier = Modifier.widthIn(min = 56.dp, max = 160.dp).background(colors.fillFaint, CursorTheme.shapes.base).padding(horizontal = 6.dp, vertical = 3.dp),
                                    )
                                    FlatIconButton(CursorIcons.Check, "Done", onClick = { editingRef = false }, size = 24.dp, iconSize = 16.dp)
                                }
                            } else {
                                SelectorChip(state.ref.ifBlank { "default" }, onClick = { editingRef = true })
                            }
                        }
                        SelectorChip("", onClick = {}, icon = CursorIcons.Cloud, enabled = false)
                        Spacer(Modifier.weight(1f))
                    }
                    ComposerBox(
                        value = state.prompt,
                        onValueChange = viewModel::setPrompt,
                        placeholder = "Ask Cursor to build, fix bugs, explore",
                        onSend = { viewModel.launch(onLaunched) },
                        canSend = state.canLaunch,
                        minLines = 3,
                        onPlus = pickImages,
                        attachments = state.attachments,
                        onRemoveAttachment = viewModel::removeAttachment,
                        modelLabel = state.modelLabel + if (state.planMode) " · Plan" else "",
                        onModel = { modelSheet = true },
                        footerExtra = {
                            if (state.isLaunching) {
                                Spacer(Modifier.width(8.dp))
                                SpinnerRing()
                            }
                        },
                    )
                    state.error?.let { Text(it, style = type.small, color = colors.red, modifier = Modifier.padding(top = 8.dp, start = 2.dp)) }
                }
            }
            item("gap") { Spacer(Modifier.height(30.dp)) }
            if (recent.isEmpty() && listState.hasLoaded) {
                item("empty") {
                    Text("No chats yet", style = type.base, color = colors.textQuaternary, modifier = Modifier.padding(top = 24.dp))
                }
            }
            items(recent, key = { it.agent.id }) { row ->
                RecentChatRow(row, onClick = { onOpenAgent(row) }, modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth().padding(horizontal = 7.dp))
            }
        }
    }

    if (repoSheet) {
        RepositorySheet(
            repos = state.repositories,
            selected = state.selectedRepo,
            noRepo = state.noRepo,
            loading = state.isLoadingRepos,
            unavailable = state.reposUnavailable,
            onSelect = { viewModel.selectRepo(it); repoSheet = false },
            onRefresh = viewModel::refreshRepositories,
            onDismiss = { repoSheet = false },
        )
    }
    if (modelSheet) {
        ModelSheet(
            models = state.models,
            selectedModel = state.selectedModel,
            selectedVariant = state.selectedVariant,
            planMode = state.planMode,
            autoCreatePr = state.autoCreatePr,
            onPlanMode = viewModel::setPlanMode,
            onAutoCreatePr = viewModel::setAutoCreatePr,
            onSelect = { m, v -> viewModel.selectModel(m, v); modelSheet = false },
            onDismiss = { modelSheet = false },
        )
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable({ onChange(!checked) }, CursorTheme.shapes.base).height(36.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CursorTheme.typography.base, color = CursorTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        CursorToggle(checked, onChange)
    }
}

/**
 * Recent chat row from cursor.com/agents: a 120x80 preview card on the left, then the title with the unread dot
 * and a metadata line (state glyph · model · repo · age).
 */
@Composable
fun RecentChatRow(row: AgentRow, onClick: () -> Unit, modifier: Modifier = Modifier, nowMillis: Long = System.currentTimeMillis()) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val agent = row.agent
    Row(
        modifier
            .pressable(onClick, CursorTheme.shapes.lg)
            .padding(vertical = CursorDimens.recentRowGap / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewCard(row)
        Spacer(Modifier.width(CursorDimens.previewToTitle))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.name, style = type.row, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (row.indicator == AgentIndicator.Unread) {
                    Spacer(Modifier.width(7.dp))
                    Dot(colors.unreadDot, size = CursorDimens.recentDot)
                }
                if (row.indicator == AgentIndicator.Error) {
                    Spacer(Modifier.width(7.dp))
                    Dot(colors.red, size = CursorDimens.recentDot)
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    agent.hasPullRequest -> Icon(CursorIcons.GitPullRequest, null, tint = colors.gitAdded, modifier = Modifier.size(16.dp))
                    agent.hasBranch -> Icon(CursorIcons.GitBranch, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
                    row.indicator == AgentIndicator.Running -> RunningGlyph(size = 16.dp, color = colors.iconTertiary)
                    else -> Icon(CursorIcons.Sparkle, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
                }
                agent.modelDisplayName?.let { Text(it, style = type.base, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val workspace = agent.envName?.takeIf { it.contains('#') } ?: agent.repoShortName
                workspace?.let { Text(it, style = type.base, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                Text(TimeFormat.relativeShort(agent.updatedAtMillis, nowMillis), style = type.base, color = colors.textQuaternary)
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
                agent.hasPullRequest -> Pill("Open", icon = CursorIcons.GitPullRequest, tint = colors.gitAdded, fill = colors.gitAdded.copy(alpha = 0.14f))
                row.indicator == AgentIndicator.Running -> Pill("Working", icon = CursorIcons.Sparkle)
                row.indicator == AgentIndicator.Error -> Pill("Failed", tint = colors.red, fill = colors.red.copy(alpha = 0.14f))
                agent.hasBranch -> Pill("Branch", icon = CursorIcons.GitBranch)
                !agent.summary.isNullOrBlank() -> Text(
                    agent.summary!!,
                    style = type.code.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = colors.textTertiary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Icon(CursorIcons.Cube, null, tint = colors.iconQuaternary, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepositorySheet(
    repos: List<Repository>,
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
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.elevated, shape = CursorTheme.shapes.sheet, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Repository", style = type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (loading) SpinnerRing() else FlatIconButton(CursorIcons.Refresh, "Refresh repositories", onClick = onRefresh)
            }
            BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                textStyle = type.base.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(colors.fillFaint, CursorTheme.shapes.base).padding(horizontal = 10.dp, vertical = 7.dp),
                decorationBox = { inner -> Box { if (filter.isEmpty()) Text("Filter repositories", style = type.base, color = colors.textQuaternary); inner() } },
            )
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f, fill = false)) {
                SheetRow(title = "No repository", subtitle = "Empty cloud VM", checked = noRepo, icon = CursorIcons.Cloud) { onSelect(null) }
                HairlineDivider(Modifier.padding(horizontal = 12.dp))
                val visible = repos.filter { it.slug.contains(filter, ignoreCase = true) }
                if (unavailable && repos.isEmpty()) {
                    Text(
                        "Cursor couldn't list your GitHub repositories right now (the endpoint is heavily rate limited). Refresh later or start without one.",
                        style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(visible, key = { it.url }) { repo ->
                        SheetRow(title = repo.shortName, subtitle = repo.slug.substringBeforeLast('/', ""), checked = !noRepo && repo.url == selected?.url, icon = CursorIcons.Folder) { onSelect(repo) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    models: List<ModelOption>,
    selectedModel: ModelOption?,
    selectedVariant: ModelVariant?,
    planMode: Boolean,
    autoCreatePr: Boolean,
    onPlanMode: (Boolean) -> Unit,
    onAutoCreatePr: (Boolean) -> Unit,
    onSelect: (ModelOption?, ModelVariant?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.elevated, shape = CursorTheme.shapes.sheet, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Model", style = type.title, color = colors.textPrimary)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item("default") {
                    SheetRow(title = "Default", subtitle = "Your Cursor default model", checked = selectedModel == null) { onSelect(null, null) }
                    HairlineDivider(Modifier.padding(horizontal = 12.dp))
                }
                if (models.isEmpty()) {
                    item("loading") {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            SpinnerRing(); Spacer(Modifier.width(8.dp))
                            Text("Loading models…", style = type.small, color = colors.textQuaternary)
                        }
                    }
                }
                item("options") {
                    Text("Options", style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                    OptionRow("Plan mode", planMode, onPlanMode)
                    OptionRow("Auto-create PR", autoCreatePr, onAutoCreatePr)
                    HairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                models.forEach { model ->
                    if (model.variants.size <= 1) {
                        item(model.id) { SheetRow(title = model.displayName, subtitle = model.description, checked = model.id == selectedModel?.id) { onSelect(model, model.variants.firstOrNull()) } }
                    } else {
                        item("hdr-${model.id}") { Text(model.displayName, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)) }
                        items(model.variants, key = { "${model.id}:${it.displayName}" }) { variant ->
                            SheetRow(
                                title = variant.displayName,
                                subtitle = variant.params.joinToString("  ") { "${it.id}=${it.value}" }.ifBlank { model.description },
                                checked = model.id == selectedModel?.id && variant == selectedVariant,
                            ) { onSelect(model, variant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, subtitle: String?, checked: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier.fillMaxWidth().pressable(onClick, CursorTheme.shapes.base).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
    }
}
