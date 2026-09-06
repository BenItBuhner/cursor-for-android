package com.cursorforandroid.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.CursorIconButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSwitch
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SelectorChip
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.CursorTopBar
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme

/** The "New Agent" pane: repo / branch / environment selectors above the prompt, model + send below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAgentScreen(
    graph: AppGraph,
    onOpenSidebar: (() -> Unit)?,
    onLaunched: (Agent) -> Unit,
    modifier: Modifier = Modifier,
    isDemo: Boolean = false,
) {
    val viewModel: NewAgentViewModel = viewModel(factory = NewAgentViewModel.Factory(graph))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = CursorTheme.colors
    var repoSheet by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var optionsMenu by remember { mutableStateOf(false) }
    var editingRef by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorTopBar(
            title = "New Agent",
            leading = { if (onOpenSidebar != null) CursorIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar) },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Spacer(Modifier.weight(1f))
            if (isDemo) {
                Text(
                    "Demo mode — agents you launch here run against a local mock, nothing leaves your device.",
                    style = CursorTheme.typography.caption,
                    color = colors.textPlaceholder,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
            state.error?.let { Text(it, style = CursorTheme.typography.caption, color = colors.danger, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) }
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp).navigationBarsPadding().imePadding()) {
                ComposerBox(
                    value = state.prompt,
                    onValueChange = viewModel::setPrompt,
                    placeholder = "Ask Cursor to build, fix bugs, explore",
                    onSend = { viewModel.launch(onLaunched) },
                    canSend = state.canLaunch,
                    minLines = 3,
                    onPlus = { optionsMenu = true },
                    header = {
                        val repoLabel = when {
                            state.noRepo -> "No repository"
                            state.selectedRepo != null -> state.selectedRepo!!.shortName
                            state.isLoadingRepos -> "Loading repos…"
                            else -> "Choose repository"
                        }
                        SelectorChip(repoLabel, onClick = { repoSheet = true }, icon = CursorIcons.Repo)
                        if (!state.noRepo) {
                            if (editingRef) {
                                BasicTextField(
                                    value = state.ref,
                                    onValueChange = viewModel::setRef,
                                    singleLine = true,
                                    textStyle = CursorTheme.typography.code.copy(color = colors.textPrimary, fontSize = CursorTheme.typography.secondary.fontSize),
                                    cursorBrush = SolidColor(colors.textPrimary),
                                    modifier = Modifier.widthIn(min = 60.dp, max = 160.dp).background(colors.wash, CursorTheme.shapes.sm).padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                                Icon(Icons.Outlined.Check, "Done", tint = colors.textSecondary, modifier = Modifier.size(16.dp).pressable({ editingRef = false }, CursorTheme.shapes.sm))
                            } else {
                                SelectorChip(state.ref.ifBlank { "default branch" }, onClick = { editingRef = true }, icon = CursorIcons.GitBranch, mono = true)
                            }
                        }
                        SelectorChip("Cloud", onClick = {}, icon = CursorIcons.Cloud)
                    },
                    footer = {
                        Box {
                            SelectorChip(state.modelLabel, onClick = { modelSheet = true })
                            DropdownMenu(expanded = optionsMenu, onDismissRequest = { optionsMenu = false }, containerColor = colors.surface) {
                                DropdownMenuItem(
                                    text = { Text("Plan mode", style = CursorTheme.typography.body, color = colors.textPrimary) },
                                    trailingIcon = { CursorSwitch(state.planMode, viewModel::setPlanMode) },
                                    onClick = { viewModel.setPlanMode(!state.planMode) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Auto-create PR", style = CursorTheme.typography.body, color = colors.textPrimary) },
                                    trailingIcon = { CursorSwitch(state.autoCreatePr, viewModel::setAutoCreatePr) },
                                    onClick = { viewModel.setAutoCreatePr(!state.autoCreatePr) },
                                )
                            }
                        }
                        if (state.planMode) {
                            Spacer(Modifier.width(6.dp))
                            Text("Plan", style = CursorTheme.typography.caption, color = colors.orange)
                        }
                        if (state.isLaunching) {
                            Spacer(Modifier.width(8.dp))
                            SpinnerRing(size = 12.dp)
                        }
                    },
                )
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
            onSelect = { m, v -> viewModel.selectModel(m, v); modelSheet = false },
            onDismiss = { modelSheet = false },
        )
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
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, shape = CursorTheme.shapes.sheet, dragHandle = null) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Repository", style = CursorTheme.typography.sheetTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (loading) SpinnerRing(size = 14.dp) else CursorIconButton(Icons.Outlined.Refresh, "Refresh repositories", onClick = onRefresh, size = 32.dp, iconSize = 16.dp)
            }
            BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                textStyle = CursorTheme.typography.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(colors.wash, CursorTheme.shapes.md).padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner -> Box { if (filter.isEmpty()) Text("Filter repositories", style = CursorTheme.typography.body, color = colors.textPlaceholder); inner() } },
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                SheetRow(title = "No repository", subtitle = "Start on an empty cloud VM", checked = noRepo, icon = CursorIcons.Cloud) { onSelect(null) }
                HairlineDivider(Modifier.padding(horizontal = 16.dp))
                val visible = repos.filter { it.slug.contains(filter, ignoreCase = true) }
                if (unavailable && repos.isEmpty()) {
                    Text(
                        "Cursor couldn't list your GitHub repositories right now (this endpoint is heavily rate limited). Pull to refresh later, or start with no repository.",
                        style = CursorTheme.typography.secondary,
                        color = colors.textPlaceholder,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                visible.forEach { repo ->
                    SheetRow(title = repo.shortName, subtitle = repo.slug.substringBeforeLast('/', ""), checked = !noRepo && repo.url == selected?.url, icon = CursorIcons.Repo) { onSelect(repo) }
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
    onSelect: (ModelOption?, ModelVariant?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, shape = CursorTheme.shapes.sheet, dragHandle = null) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Model", style = CursorTheme.typography.sheetTitle, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                SheetRow(title = "Default", subtitle = "Your Cursor default model", checked = selectedModel == null) { onSelect(null, null) }
                HairlineDivider(Modifier.padding(horizontal = 16.dp))
                if (models.isEmpty()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SpinnerRing(size = 12.dp); Spacer(Modifier.width(8.dp))
                        Text("Loading models…", style = CursorTheme.typography.secondary, color = colors.textPlaceholder)
                    }
                }
                models.forEach { model ->
                    if (model.variants.size <= 1) {
                        SheetRow(title = model.displayName, subtitle = model.description, checked = model.id == selectedModel?.id) { onSelect(model, model.variants.firstOrNull()) }
                    } else {
                        Text(model.displayName, style = CursorTheme.typography.sectionLabel, color = colors.textPlaceholder, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
                        model.variants.forEach { variant ->
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
    Row(
        Modifier.fillMaxWidth().pressable(onClick, CursorTheme.shapes.md).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = CursorTheme.typography.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = CursorTheme.typography.caption, color = colors.textPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) Icon(Icons.Outlined.Check, null, tint = colors.accentBlue, modifier = Modifier.size(18.dp))
    }
}
