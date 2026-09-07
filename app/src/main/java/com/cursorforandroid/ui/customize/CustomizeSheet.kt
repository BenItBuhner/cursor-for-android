package com.cursorforandroid.ui.customize

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.domain.FilterKind
import com.cursorforandroid.domain.GitFilter
import com.cursorforandroid.domain.GroupBy
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.SortOrder
import com.cursorforandroid.domain.SourceFilter
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The "Chats" filter menu (the filter icon next to the "Chats" label) as a bottom sheet: grouping, sort, the
 * Repo / Status / Git / Source filters and the metadata toggles. Flat 40dp rows, 13sp, desktop-size toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeSheet(viewModel: AgentsViewModel, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf<FilterKind?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.elevated,
        contentColor = colors.textPrimary,
        shape = CursorTheme.shapes.sheet,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        BackHandler(enabled = page != null) { page = null }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 280.dp)) {
            Row(Modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(start = if (page == null) 16.dp else 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != null) {
                    FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = { page = null })
                    Spacer(Modifier.width(2.dp))
                }
                Text(page?.label ?: "Chats", style = type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (page == null && !state.prefs.isDefault) {
                    Text(
                        "Reset",
                        style = type.base,
                        color = colors.link,
                        modifier = Modifier.pressable(viewModel::resetPrefs, CursorTheme.shapes.base).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            HairlineDivider()

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (targetState != null) slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    else slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                },
                label = "customize-page",
            ) { current ->
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                    when (current) {
                        null -> RootPage(state.prefs, viewModel, onOpen = { page = it })
                        FilterKind.Repo -> RepoPage(state.repoSlugs, state.prefs.repos, onSelectAll = { viewModel.setRepos(null) }) { slug ->
                            val selected = state.prefs.repos ?: state.repoSlugs.toSet()
                            val next = if (slug in selected) selected - slug else selected + slug
                            viewModel.setRepos(if (next.size == state.repoSlugs.size) null else next)
                        }
                        FilterKind.Status -> ChecklistPage(StatusFilter.entries.map { it.label to (it in state.prefs.statuses) }) { viewModel.toggleStatus(StatusFilter.entries[it]) }
                        FilterKind.Git -> ChecklistPage(GitFilter.entries.map { it.label to (it in state.prefs.git) }) { viewModel.toggleGit(GitFilter.entries[it]) }
                        FilterKind.Source -> ChecklistPage(SourceFilter.entries.map { it.label to (it in state.prefs.sources) }) { viewModel.toggleSource(SourceFilter.entries[it]) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootPage(prefs: ListPreferences, viewModel: AgentsViewModel, onOpen: (FilterKind) -> Unit) {
    SectionLabel("Grouping")
    PickerRow(CursorIcons.Layers, "Group by", GroupBy.entries.map { it.label }, prefs.groupBy.ordinal) { viewModel.setGroupBy(GroupBy.entries[it]) }
    PickerRow(CursorIcons.Filter, "Sort by", SortOrder.entries.map { it.label }, prefs.sortOrder.ordinal) { viewModel.setSortOrder(SortOrder.entries[it]) }

    SectionLabel("Filter")
    DrillRow(CursorIcons.Folder, "Repo", prefs.summaryFor(FilterKind.Repo)) { onOpen(FilterKind.Repo) }
    DrillRow(CursorIcons.Sparkle, "Status", prefs.summaryFor(FilterKind.Status)) { onOpen(FilterKind.Status) }
    DrillRow(CursorIcons.GitBranch, "Git", prefs.summaryFor(FilterKind.Git)) { onOpen(FilterKind.Git) }
    DrillRow(CursorIcons.Cloud, "Source", prefs.summaryFor(FilterKind.Source)) { onOpen(FilterKind.Source) }

    SectionLabel("Metadata")
    ToggleRow(CursorIcons.Folder, "Workspace", prefs.showWorkspace, viewModel::setShowWorkspace)
    ToggleRow(CursorIcons.GitPullRequest, "Branch status", prefs.showBranchStatus, viewModel::setShowBranchStatus)
    ToggleRow(CursorIcons.Clock, "Runtime", prefs.showRuntime, viewModel::setShowRuntime)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.textTertiary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun RowShell(icon: ImageVector, label: String, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .then(if (onClick != null) Modifier.pressable(onClick, CursorTheme.shapes.base) else Modifier)
            .height(38.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun DrillRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    RowShell(icon, label, onClick) {
        Text(value, style = CursorTheme.typography.base, color = colors.textTertiary)
        Spacer(Modifier.width(4.dp))
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = CursorTheme.colors
    var open by remember { mutableStateOf(false) }
    RowShell(icon, label, onClick = { open = true }) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options[selectedIndex], style = CursorTheme.typography.base, color = colors.textTertiary)
                Spacer(Modifier.width(3.dp))
                Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(option, style = CursorTheme.typography.base, color = colors.textPrimary) },
                        trailingIcon = { if (i == selectedIndex) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp)) },
                        onClick = { open = false; onSelect(i) },
                        modifier = Modifier.height(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowShell(icon, label, onClick = { onChange(!checked) }) { CursorToggle(checked = checked, onCheckedChange = onChange) }
}

@Composable
private fun ChecklistPage(items: List<Pair<String, Boolean>>, onToggle: (Int) -> Unit) {
    val colors = CursorTheme.colors
    Spacer(Modifier.height(6.dp))
    items.forEachIndexed { index, (label, checked) ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).pressable({ onToggle(index) }, CursorTheme.shapes.base).height(36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = CursorTheme.typography.base, color = colors.textPrimary)
            if (checked) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RepoPage(slugs: List<String>, selected: Set<String>?, onSelectAll: () -> Unit, onToggle: (String) -> Unit) {
    val colors = CursorTheme.colors
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).pressable(onSelectAll, CursorTheme.shapes.base).height(36.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("All repositories", style = CursorTheme.typography.base, color = colors.textPrimary)
        if (selected == null) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
    }
    HairlineDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    if (slugs.isEmpty()) Text("No repositories yet", style = CursorTheme.typography.small, color = colors.textQuaternary, modifier = Modifier.padding(16.dp))
    slugs.forEach { slug ->
        val checked = selected == null || slug in selected
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).pressable({ onToggle(slug) }, CursorTheme.shapes.base).height(36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CursorIcons.Folder, null, tint = colors.iconSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(slug.substringAfterLast('/'), style = CursorTheme.typography.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(slug.substringBeforeLast('/', ""), style = CursorTheme.typography.small, color = colors.textQuaternary)
            Spacer(Modifier.width(10.dp))
            if (checked) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}
