package com.cursorforandroid.ui.customize

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.domain.EnvironmentFilter
import com.cursorforandroid.domain.FilterKind
import com.cursorforandroid.domain.GitFilter
import com.cursorforandroid.domain.GroupBy
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.SortOrder
import com.cursorforandroid.domain.SourceFilter
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SheetHeaderHeight
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rewind
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The "Chats" filter menu (the filter icon in the sidebar header) as a bottom sheet: grouping, sort, the
 * Repo / Status / Git / Source / Environment filters, Read All, and the metadata toggles. Flat 40dp rows, 13sp,
 * desktop-size toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeSheet(viewModel: AgentsViewModel, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<FilterKind?>(null) }
    // Seekable so a back gesture scrubs the drill-in page out and the root page in; committed on release.
    val pageTransition = remember { SeekableTransitionState<FilterKind?>(null) }
    var rewindJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(page) {
        rewindJob?.cancel()
        pageTransition.animateTo(page)
    }

    CursorSheet(onDismiss = onDismiss) {
        PredictiveBackHandler(enabled = page != null) { events ->
            rewindJob?.cancel()
            try {
                events.collect { pageTransition.seekTo(it.progress, targetState = null) }
            } catch (e: CancellationException) {
                rewindJob = scope.launch { pageTransition.rewind(PageTransitionMillis) }
                return@PredictiveBackHandler
            }
            page = null
        }
        val transition = rememberTransition(pageTransition, label = "customize-page")
        Column(Modifier.fillMaxWidth().heightIn(min = 280.dp)) {
            // The header rides the same transition as the body, so the title changes with the gesture, not after it.
            transition.AnimatedContent(
                transitionSpec = { fadeIn(tween(PageTransitionMillis)) togetherWith fadeOut(tween(PageTransitionMillis)) using null },
            ) { current ->
                Row(
                    Modifier.fillMaxWidth().height(SheetHeaderHeight).padding(start = if (current == null) 20.dp else 8.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current != null) {
                        FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = { page = null })
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(current?.label ?: "Chats", style = type.sectionTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    if (current == null && !state.prefs.isDefault) {
                        Text(
                            "Reset",
                            style = type.baseMedium,
                            color = colors.link,
                            modifier = Modifier.pressable(viewModel::resetPrefs, CursorTheme.shapes.base).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            HairlineDivider()

            transition.AnimatedContent(
                transitionSpec = {
                    // Decelerating, so the page answers the first millimetres of the gesture and settles on release.
                    val slide = tween<IntOffset>(PageTransitionMillis, easing = LinearOutSlowInEasing)
                    val pages =
                        if (targetState != null) slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it }
                        else slideInHorizontally(slide) { -it } togetherWith slideOutHorizontally(slide) { it }
                    // The height change runs on the same clock; with the default spring the seek would be stretched
                    // over the spring's estimated duration and stop tracking the gesture 1:1.
                    pages using SizeTransform { _, _ -> tween(PageTransitionMillis, easing = LinearOutSlowInEasing) }
                },
            ) { current ->
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                    when (current) {
                        null -> rows { RootPage(state.prefs, state.unreadCount, viewModel, onOpen = { page = it }) }
                        FilterKind.Repo -> repoPage(state.repoSlugs, state.prefs.repos, onSelectAll = { viewModel.setRepos(null) }) { slug ->
                            val selected = state.prefs.repos ?: state.repoSlugs.toSet()
                            val next = if (slug in selected) selected - slug else selected + slug
                            viewModel.setRepos(if (next.size == state.repoSlugs.size) null else next)
                        }
                        FilterKind.Status -> rows { ChecklistPage(StatusFilter.entries.map { it.label to (it in state.prefs.statuses) }) { viewModel.toggleStatus(StatusFilter.entries[it]) } }
                        FilterKind.Git -> rows { ChecklistPage(GitFilter.entries.map { it.label to (it in state.prefs.git) }) { viewModel.toggleGit(GitFilter.entries[it]) } }
                        FilterKind.Source -> rows {
                            ChecklistPage(SourceFilter.entries.map { it.label to (it in state.prefs.sources) }) { viewModel.toggleSource(SourceFilter.entries[it]) }
                            PageNote("Where each chat was started, as your Cursor account records it. Chats started from this app count as \"This device\"; the account sees them as API.")
                        }
                        FilterKind.Environment -> rows { ChecklistPage(EnvironmentFilter.entries.map { it.label to (it in state.prefs.environments) }) { viewModel.toggleEnvironment(EnvironmentFilter.entries[it]) } }
                    }
                }
            }
        }
    }
}

private const val PageTransitionMillis = 300

/** A page whose rows are few and fixed: one lazy item holding the lot, so it measures as a plain column would. */
private fun LazyListScope.rows(content: @Composable ColumnScope.() -> Unit) = item { Column(content = content) }

@Composable
private fun RootPage(prefs: ListPreferences, unreadCount: Int, viewModel: AgentsViewModel, onOpen: (FilterKind) -> Unit) {
    SectionLabel("Grouping")
    PickerRow(CursorIcons.Layers, "Group by", GroupBy.entries.map { it.label }, prefs.groupBy.ordinal) { viewModel.setGroupBy(GroupBy.entries[it]) }
    PickerRow(CursorIcons.Filter, "Sort by", SortOrder.entries.map { it.label }, prefs.sortOrder.ordinal) { viewModel.setSortOrder(SortOrder.entries[it]) }

    SectionLabel("Filter")
    DrillRow(CursorIcons.Repo, "Repo", prefs.summaryFor(FilterKind.Repo)) { onOpen(FilterKind.Repo) }
    DrillRow(CursorIcons.Sparkle, "Status", prefs.summaryFor(FilterKind.Status)) { onOpen(FilterKind.Status) }
    DrillRow(CursorIcons.GitBranch, "Git", prefs.summaryFor(FilterKind.Git)) { onOpen(FilterKind.Git) }
    DrillRow(CursorIcons.Globe, "Source", prefs.summaryFor(FilterKind.Source)) { onOpen(FilterKind.Source) }
    DrillRow(CursorIcons.Cloud, "Environment", prefs.summaryFor(FilterKind.Environment)) { onOpen(FilterKind.Environment) }
    ReadAllRow(unreadCount = unreadCount, onClick = viewModel::markAllRead)

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
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

/** A sheet list row: 17px glyph at 66 %, label, trailing control; inset 8dp so the press highlight has a margin. */
@Composable
private fun RowShell(
    icon: ImageVector?,
    label: String,
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = CursorTheme.colors
    val iconTint = if (enabled) colors.iconSecondary else colors.iconQuaternary
    val labelColor = if (enabled) colors.textPrimary else colors.textQuaternary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .then(if (onClick != null && enabled) Modifier.pressable(onClick, CursorTheme.shapes.base) else Modifier)
            .height(CursorDimens.listRow)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = CursorTheme.typography.base, color = labelColor, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** Marks every loaded conversation read. Dims when nothing is unread. */
@Composable
private fun ReadAllRow(unreadCount: Int, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val enabled = unreadCount > 0
    RowShell(CursorIcons.CheckCheck, "Read All", onClick = onClick, enabled = enabled) {
        if (enabled) {
            Text(
                unreadCount.toString(),
                style = CursorTheme.typography.base,
                color = colors.textTertiary,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "$unreadCount unread" },
            )
        }
    }
}

@Composable
private fun DrillRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    RowShell(icon, label, onClick) {
        Text(value, style = CursorTheme.typography.base, color = colors.textTertiary, maxLines = 1)
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
                Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(15.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(option, style = CursorTheme.typography.base, color = colors.textPrimary) },
                        trailingIcon = { if (i == selectedIndex) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp)) },
                        onClick = { open = false; onSelect(i) },
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp),
                        modifier = Modifier.height(40.dp),
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
private fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit, icon: ImageVector? = null, detail: String? = null) {
    val colors = CursorTheme.colors
    RowShell(icon, label, onClick) {
        if (detail != null) {
            Text(detail, style = CursorTheme.typography.small, color = colors.textQuaternary, maxLines = 1)
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.size(16.dp)) {
            if (checked) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ChecklistPage(items: List<Pair<String, Boolean>>, onToggle: (Int) -> Unit) {
    Spacer(Modifier.height(6.dp))
    items.forEachIndexed { index, (label, checked) -> CheckRow(label, checked, onClick = { onToggle(index) }) }
}

/** A line of explanation under a page's rows. */
@Composable
private fun PageNote(text: String) {
    Text(
        text,
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.textQuaternary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
    )
}

/** An account can have hundreds of repositories, so this page is the one that composes only what is on screen. */
private fun LazyListScope.repoPage(slugs: List<String>, selected: Set<String>?, onSelectAll: () -> Unit, onToggle: (String) -> Unit) {
    item(key = "repo-header") {
        Column {
            Spacer(Modifier.height(6.dp))
            CheckRow("All repositories", checked = selected == null, onClick = onSelectAll)
            HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if (slugs.isEmpty()) {
                Text("No repositories yet", style = CursorTheme.typography.small, color = CursorTheme.colors.textQuaternary, modifier = Modifier.padding(20.dp))
            }
        }
    }
    items(slugs, key = { it }) { slug ->
        CheckRow(
            label = slug.substringAfterLast('/'),
            checked = selected == null || slug in selected,
            onClick = { onToggle(slug) },
            icon = CursorIcons.Repo,
            detail = slug.substringBeforeLast('/', ""),
        )
    }
}
