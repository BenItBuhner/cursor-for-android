package com.cursorforandroid.ui.customize

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.UnfoldMore
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.domain.FilterKind
import com.cursorforandroid.domain.GitFilter
import com.cursorforandroid.domain.GroupBy
import com.cursorforandroid.domain.SortOrder
import com.cursorforandroid.domain.SourceFilter
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.components.CursorIconButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSwitch
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The "Customize" sheet from the iOS app rebuilt on a Material bottom sheet: Grouping, Filter (Repo / Status /
 * Git / Source drill-downs) and Agent Metadata toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeSheet(viewModel: AgentsViewModel, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf<FilterKind?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
        shape = CursorTheme.shapes.sheet,
        dragHandle = null,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
    ) {
        BackHandler(enabled = page != null) { page = null }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 320.dp)) {
            // Header: close (or back) on the left, centred title.
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                if (page == null) {
                    CursorIconButton(Icons.Outlined.Close, "Close", onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart))
                } else {
                    CursorIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = { page = null }, modifier = Modifier.align(Alignment.CenterStart))
                }
                Text(page?.label ?: "Customize", style = CursorTheme.typography.sheetTitle, color = colors.textPrimary, modifier = Modifier.align(Alignment.Center))
                if (page == null && !state.prefs.isDefault) {
                    Text(
                        "Reset",
                        style = CursorTheme.typography.secondary,
                        color = colors.accentBlue,
                        modifier = Modifier.align(Alignment.CenterEnd).pressable(viewModel::resetPrefs, CursorTheme.shapes.sm).padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (targetState != null) slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    else slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                },
                label = "customize-page",
            ) { current ->
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    when (current) {
                        null -> RootPage(state.prefs, viewModel, onOpen = { page = it })
                        FilterKind.Repo -> RepoPage(state.repoSlugs, state.prefs.repos, onSelectAll = { viewModel.setRepos(null) }) { slug ->
                            val current = state.prefs.repos ?: state.repoSlugs.toSet()
                            val next = if (slug in current) current - slug else current + slug
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
private fun RootPage(prefs: com.cursorforandroid.domain.ListPreferences, viewModel: AgentsViewModel, onOpen: (FilterKind) -> Unit) {
    GroupHeader("Grouping")
    PickerRow(CursorIcons.Layers, "Group by", GroupBy.entries.map { it.label }, prefs.groupBy.ordinal) { viewModel.setGroupBy(GroupBy.entries[it]) }
    HairlineDivider(Modifier.padding(start = 52.dp, end = 16.dp))
    PickerRow(Icons.Outlined.UnfoldMore, "Sort by", SortOrder.entries.map { it.label }, prefs.sortOrder.ordinal) { viewModel.setSortOrder(SortOrder.entries[it]) }

    GroupHeader("Filter")
    DrillRow(CursorIcons.Repo, "Repo", prefs.summaryFor(FilterKind.Repo)) { onOpen(FilterKind.Repo) }
    HairlineDivider(Modifier.padding(start = 52.dp, end = 16.dp))
    DrillRow(CursorIcons.Sparkle, "Status", prefs.summaryFor(FilterKind.Status)) { onOpen(FilterKind.Status) }
    HairlineDivider(Modifier.padding(start = 52.dp, end = 16.dp))
    DrillRow(CursorIcons.GitBranch, "Git", prefs.summaryFor(FilterKind.Git)) { onOpen(FilterKind.Git) }
    HairlineDivider(Modifier.padding(start = 52.dp, end = 16.dp))
    DrillRow(CursorIcons.Wave, "Source", prefs.summaryFor(FilterKind.Source)) { onOpen(FilterKind.Source) }

    GroupHeader("Agent Metadata")
    ToggleRow(CursorIcons.Repo, "Workspace", prefs.showWorkspace, viewModel::setShowWorkspace)
    HairlineDivider(Modifier.padding(start = 52.dp, end = 16.dp))
    ToggleRow(CursorIcons.GitPullRequest, "Branch Status", prefs.showBranchStatus, viewModel::setShowBranchStatus)
    HairlineDivider(Modifier.padding(start = 52.dp, end = 16.dp))
    ToggleRow(CursorIcons.Desktop, "Runtime", prefs.showRuntime, viewModel::setShowRuntime)
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = CursorTheme.typography.sectionLabel,
        color = CursorTheme.colors.textPlaceholder,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun RowShell(icon: ImageVector, label: String, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick, CursorTheme.shapes.md) else Modifier)
            .padding(horizontal = 16.dp)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = CursorTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun DrillRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    RowShell(icon, label, onClick) {
        Text(value, style = CursorTheme.typography.secondary, color = colors.textSecondary)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.ChevronRight, null, tint = colors.textPlaceholder, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = CursorTheme.colors
    var open by remember { mutableStateOf(false) }
    RowShell(icon, label, onClick = { open = true }) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options[selectedIndex], style = CursorTheme.typography.secondary, color = colors.textSecondary)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Outlined.UnfoldMore, null, tint = colors.textPlaceholder, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(option, style = CursorTheme.typography.body, color = colors.textPrimary) },
                        trailingIcon = { if (i == selectedIndex) Icon(Icons.Outlined.Check, null, tint = colors.textPrimary, modifier = Modifier.size(16.dp)) },
                        onClick = { open = false; onSelect(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowShell(icon, label, onClick = { onChange(!checked) }) {
        CursorSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChecklistPage(items: List<Pair<String, Boolean>>, onToggle: (Int) -> Unit) {
    val colors = CursorTheme.colors
    Spacer(Modifier.height(8.dp))
    items.forEachIndexed { index, (label, checked) ->
        Row(
            Modifier.fillMaxWidth().pressable({ onToggle(index) }, CursorTheme.shapes.md).padding(horizontal = 20.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = CursorTheme.typography.body, color = colors.textPrimary)
            if (checked) Icon(Icons.Outlined.Check, null, tint = colors.accentBlue, modifier = Modifier.size(18.dp))
        }
        if (index != items.lastIndex) HairlineDivider(Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun RepoPage(slugs: List<String>, selected: Set<String>?, onSelectAll: () -> Unit, onToggle: (String) -> Unit) {
    val colors = CursorTheme.colors
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().pressable(onSelectAll, CursorTheme.shapes.md).padding(horizontal = 20.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("All repositories", style = CursorTheme.typography.body, color = colors.textPrimary)
        if (selected == null) Icon(Icons.Outlined.Check, null, tint = colors.accentBlue, modifier = Modifier.size(18.dp))
    }
    HairlineDivider(Modifier.padding(horizontal = 16.dp))
    if (slugs.isEmpty()) {
        Text("No repositories yet", style = CursorTheme.typography.secondary, color = colors.textPlaceholder, modifier = Modifier.padding(20.dp))
    }
    slugs.forEachIndexed { index, slug ->
        val checked = selected == null || slug in selected
        Row(
            Modifier.fillMaxWidth().pressable({ onToggle(slug) }, CursorTheme.shapes.md).padding(horizontal = 20.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CursorIcons.Repo, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(slug.substringAfterLast('/'), style = CursorTheme.typography.body, color = colors.textPrimary)
                Text(slug.substringBeforeLast('/', ""), style = CursorTheme.typography.caption, color = colors.textPlaceholder)
            }
            if (checked) Icon(Icons.Outlined.Check, null, tint = colors.accentBlue, modifier = Modifier.size(18.dp))
        }
        if (index != slugs.lastIndex) HairlineDivider(Modifier.padding(horizontal = 16.dp))
    }
}
