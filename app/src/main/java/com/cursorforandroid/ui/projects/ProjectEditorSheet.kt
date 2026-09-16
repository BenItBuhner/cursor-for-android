package com.cursorforandroid.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.home.SheetRow
import com.cursorforandroid.ui.home.SheetSearchField
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ProjectPalette
import kotlinx.coroutines.launch

/** What the Project editor is opened for; a `Serializable`, so `rememberSaveable` keeps it up across a rotation. */
sealed interface ProjectEditorTarget : java.io.Serializable {
    /** A new Project: name, look and repositories, created the desktop's way and opened. */
    data object Create : ProjectEditorTarget { private fun readResolve(): Any = Create }

    /** An existing Project: its name and look; the repositories it was created with are shown. */
    data class Edit(val projectId: String) : ProjectEditorTarget
}

/** What the sheet hands back on confirm: the name as typed, the look if one was chosen, the repositories picked. */
data class ProjectEditorResult(val name: String, val appearance: ProjectAppearance?, val repoUrls: List<String>)

/**
 * The editor bound to the graph: the account's repositories for the picker, the Project's row for the fields, and
 * the editor's actions with their outcome — a created Project is opened, an edited one is left showing its new name
 * and look, a refusal is shown in the sheet with the fields kept.
 */
@Composable
fun ProjectEditorHost(graph: AppGraph, target: ProjectEditorTarget, onOpenAgent: (String) -> Unit, onDismiss: () -> Unit) {
    val list by graph.agents.state.collectAsStateWithLifecycle()
    val repositories by graph.catalog.repositories.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reposLoading by remember { mutableStateOf(false) }
    val project = (target as? ProjectEditorTarget.Edit)?.let { edit -> list.agents.firstOrNull { it.id == edit.projectId } }
    // The catalog's repositories, read once the sheet opens for a new Project; a refresh re-reads them.
    fun loadRepositories(force: Boolean) {
        if (target !is ProjectEditorTarget.Create) return
        scope.launch {
            reposLoading = true
            try {
                graph.catalog.loadRepositories(force)
            } finally {
                reposLoading = false
            }
        }
    }
    LaunchedEffect(target) { loadRepositories(force = false) }
    ProjectEditorSheet(
        target = target,
        initialName = project?.name?.takeIf { target is ProjectEditorTarget.Edit }.orEmpty(),
        initialAppearance = project?.projectAppearance,
        repositories = repositories,
        ownedRepoUrls = listOfNotNull(project?.repoUrl),
        repositoriesLoading = reposLoading,
        busy = busy,
        error = error,
        onRefreshRepositories = { loadRepositories(force = true) },
        onConfirm = { result ->
            busy = true
            error = null
            scope.launch {
                val outcome = when (target) {
                    ProjectEditorTarget.Create -> graph.projectEditor.create(result.name, result.appearance, result.repoUrls).map { id -> { onOpenAgent(id) } }
                    is ProjectEditorTarget.Edit -> graph.projectEditor.update(target.projectId, result.name, result.appearance).map { { } }
                }
                busy = false
                outcome.fold(
                    onSuccess = { then -> onDismiss(); then() },
                    onFailure = { error = it.message ?: "Cursor didn't answer." },
                )
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * The desktop's Create Project dialog on a phone (Cursor 3.20.21 `CreateProjectDialog`): the icon and colour with the
 * name beside them, the repositories the Project owns, and the action. For an existing Project the same sheet edits
 * the name and the look; the repositories a Project was created with are shown — the account service has no way to
 * change them afterwards, and neither has the desktop. The look starts unchosen for a new Project, as on the desktop,
 * where an unchosen look is given at random when the Project is created.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectEditorSheet(
    target: ProjectEditorTarget,
    initialName: String,
    initialAppearance: ProjectAppearance?,
    repositories: List<Repository>,
    ownedRepoUrls: List<String>,
    repositoriesLoading: Boolean,
    busy: Boolean,
    error: String?,
    onRefreshRepositories: () -> Unit,
    onConfirm: (ProjectEditorResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val creating = target is ProjectEditorTarget.Create
    var name by rememberSaveable { mutableStateOf(initialName) }
    var icon by rememberSaveable { mutableStateOf(ProjectIcons.canonical(initialAppearance?.icon) ?: initialAppearance?.icon) }
    var colorId by rememberSaveable { mutableStateOf(initialAppearance?.colorId?.takeIf(ProjectPalette::isKnown) ?: ProjectPalette.DEFAULT_ID) }
    var chosen by rememberSaveable { mutableStateOf(initialAppearance != null) }
    // The icon catalog opens on the icon itself, as the desktop's picker does, and closes on a pick: the repositories stay a scroll away, not a catalog away.
    var pickingIcon by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var repoFilter by rememberSaveable { mutableStateOf("") }
    // An ArrayList, which the saved state can hold; the repositories picked, in the order picked (the first is primary).
    var picked by rememberSaveable { mutableStateOf(ArrayList(ownedRepoUrls)) }
    val tone = colors.projectTone(colorId)
    val sections = remember(query) {
        ProjectIcons.groups.map { group -> group.copy(ids = ProjectIcons.search(query, within = group.ids)) }.filter { it.ids.isNotEmpty() }
    }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader(if (creating) "Create Project" else "Edit Project")
        Text(
            if (creating) "Create a focused chat where Agents coordinate work" else "The name and look show on desktop and cursor.com as well as here.",
            style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            val perRow = ((maxWidth - 32.dp) / 44.dp).toInt().coerceAtLeast(4)
            LazyColumn(Modifier.fillMaxWidth().testTag("project-editor-list"), contentPadding = PaddingValues(bottom = 8.dp)) {
                item("identity") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        val shown = if (chosen) icon else null
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(tone.copy(alpha = 0.14f), CircleShape)
                                .then(if (pickingIcon) Modifier.border(CursorDimens.hairline, tone, CircleShape) else Modifier)
                                .pressable({ pickingIcon = !pickingIcon }, CircleShape)
                                .semantics { contentDescription = if (shown != null) "Chosen icon ${ProjectIcons.label(shown)}" else "Choose an icon" }
                                .testTag("project-icon"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (shown != null) CursorIcons.project(shown) else CursorIcons.Plus, null, tint = tone, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        NameField(value = name, onValueChange = { name = it.take(MAX_NAME) }, placeholder = "New Project", modifier = Modifier.weight(1f).testTag("project-name"))
                    }
                }
                item("look-label") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Icon and colour", style = type.small, color = colors.textTertiary, modifier = Modifier.weight(1f))
                        Text(
                            if (pickingIcon) "Hide icons" else "Browse icons",
                            style = type.small, color = colors.textSecondary,
                            modifier = Modifier.pressable({ pickingIcon = !pickingIcon }, CursorTheme.shapes.base).padding(horizontal = 6.dp, vertical = 2.dp).testTag("browse-icons"),
                        )
                    }
                }
                item("palette") { PaletteRow(colorId = colorId, onPick = { colorId = it; chosen = true }) }
                if (pickingIcon) {
                    item("search") {
                        Spacer(Modifier.height(10.dp))
                        SheetSearchField(value = query, onValueChange = { query = it }, placeholder = "Search ${ProjectIcons.ids.size} icons")
                        Spacer(Modifier.height(2.dp))
                    }
                    if (sections.isEmpty()) {
                        item("no-icons") {
                            Text("No icons match \u201C${query.trim()}\u201D", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                        }
                    }
                    sections.forEach { section ->
                        item("icons-header:${section.label}") {
                            Text(section.label, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
                        }
                        section.ids.chunked(perRow).forEachIndexed { index, rowIds ->
                            item("icons:${section.label}:$index") {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    rowIds.forEach { candidate ->
                                        IconCell(candidate = candidate, selected = chosen && candidate == icon, tone = tone, onPick = { icon = candidate; chosen = true; pickingIcon = false; query = "" })
                                    }
                                }
                            }
                        }
                    }
                }
                item("repos-header") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (creating) "Repositories" else "Repositories · set when the Project was created", style = type.small, color = colors.textTertiary, modifier = Modifier.weight(1f))
                        if (creating) {
                            if (repositoriesLoading) SpinnerRing(modifier = Modifier.padding(end = 8.dp)) else FlatIconButton(CursorIcons.Refresh, "Refresh repositories", onClick = onRefreshRepositories)
                        }
                    }
                }
                if (creating) {
                    item("repos-filter") {
                        Spacer(Modifier.height(6.dp))
                        SheetSearchField(value = repoFilter, onValueChange = { repoFilter = it }, placeholder = "Filter repositories")
                        Spacer(Modifier.height(4.dp))
                    }
                    val visible = repositories.distinctBy { it.url }.filter { repoFilter.isBlank() || it.slug.contains(repoFilter.trim(), ignoreCase = true) }
                    // The picked repositories lead, in the order picked — the first of them is the primary one.
                    val pickedFirst = visible.sortedWith(compareBy({ if (it.url in picked) 0 else 1 }, { picked.indexOf(it.url) }))
                    items(pickedFirst.size, key = { "repo:${pickedFirst[it].url}" }) { index ->
                        val repo = pickedFirst[index]
                        val on = repo.url in picked
                        val owner = repo.slug.substringBeforeLast('/', "")
                        SheetRow(title = repo.shortName, subtitle = if (on && picked.firstOrNull() == repo.url) listOf(owner, "primary").filter { it.isNotEmpty() }.joinToString(" \u00B7 ") else owner, checked = on, icon = CursorIcons.Repo) {
                            picked = ArrayList(if (on) picked - repo.url else picked + repo.url)
                        }
                    }
                    if (visible.isEmpty()) {
                        item("repos-none") {
                            Text(
                                when {
                                    repositoriesLoading -> "Loading repositories\u2026"
                                    repoFilter.isNotBlank() -> "No repositories match \u201C${repoFilter.trim()}\u201D"
                                    else -> "No repositories yet. Without one the Project starts in an empty cloud environment."
                                },
                                style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    item("repos-note") {
                        Text(
                            if (picked.isEmpty()) "Optional: the Project's agents work in the repositories it owns. With none, it starts in an empty cloud environment." else "${picked.size} chosen \u00B7 the first is the primary repository.",
                            style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    if (ownedRepoUrls.isEmpty()) {
                        item("repos-none") { Text("No repository: the Project runs in an empty cloud environment.", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                    }
                    items(ownedRepoUrls.size, key = { "owned:${ownedRepoUrls[it]}" }) { index ->
                        val repo = Repository(ownedRepoUrls[index])
                        SheetRow(title = repo.shortName, subtitle = repo.slug.substringBeforeLast('/', ""), checked = false, icon = CursorIcons.Repo) {}
                    }
                    item("repos-note") {
                        Text("Cursor has no way to change a Project's repositories once it is created.", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }
            }
        }
        HairlineDivider(Modifier.padding(horizontal = 20.dp))
        error?.let { Text(it, style = type.small, color = colors.red, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("project-editor-error")) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (chosen) "${ProjectIcons.label(icon ?: "")} \u00B7 ${ProjectPalette.label(colorId)}" else "Icon and colour: chosen for you unless you pick",
                style = type.small, color = colors.textQuaternary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            CursorButton("Cancel", onClick = dismiss, enabled = !busy)
            Spacer(Modifier.width(8.dp))
            if (busy) {
                SpinnerRing(modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                CursorButton(
                    if (creating) "Create" else "Save",
                    primary = true,
                    enabled = creating || name.isNotBlank() || chosen,
                    onClick = { onConfirm(ProjectEditorResult(name, if (chosen && icon != null) ProjectAppearance(icon!!, colorId) else null, picked)) },
                )
            }
        }
    }
}

/** The name field: one line in the composer's idiom, the desktop's cap of a hundred characters. */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Box(
        modifier
            .background(colors.fillFaint, shape)
            .border(CursorDimens.hairline, colors.strokeSubtle, shape)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = type.base.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(placeholder, style = type.base, color = colors.textQuaternary); inner() }
            },
        )
    }
}

private const val MAX_NAME = 100
