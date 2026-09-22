package com.cursorforandroid.ui.projects

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.repo.ProjectEditor
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelResolution
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.ProjectGlyph
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.components.stylusWriting
import com.cursorforandroid.ui.home.ModelSheet
import com.cursorforandroid.ui.icons.ProjectIconGroup
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ProjectPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the Project editor is opened for; a `Serializable`, so `rememberSaveable` keeps it up across a rotation. */
sealed interface ProjectEditorTarget : java.io.Serializable {
    /** A new Project: name, repositories, look and model, created the desktop's way and opened. */
    data object Create : ProjectEditorTarget { private fun readResolve(): Any = Create }

    /** An existing Project: its name and look; the repositories it was created with are shown. */
    data class Edit(val projectId: String) : ProjectEditorTarget
}

/**
 * What the sheet hands back on confirm: the name as typed, the look (always for a new Project; for an existing one
 * only when it changed), the repositories chosen, and the model the coordinator runs on — the picker's choice, else
 * what the sheet opened on; null only when no model list was there to choose from, which the account reads as Auto.
 */
data class ProjectEditorResult(val name: String, val appearance: ProjectAppearance?, val repoUrls: List<String>, val model: ModelChoice? = null) {
    /** The choice as the account is told it: the model's id, the variant's parameters; Auto is the desktop's `default`. */
    val accountModel: AccountModel? get() = model?.let { AccountModel(if (it.model.isAuto) AccountModel.AUTO_ID else it.model.id, it.params) }
}

/**
 * The editor bound to the graph: the account's repositories for the picker, the Project's row for the fields, and
 * the editor's actions with their outcome — a created Project is opened, an edited one is left showing its new name
 * and look, a refusal is shown in the sheet with the fields kept.
 */
@Composable
fun ProjectEditorHost(graph: AppGraph, target: ProjectEditorTarget, onOpenAgent: (String) -> Unit, onDismiss: () -> Unit) {
    val list by graph.agents.state.collectAsStateWithLifecycle()
    val repositories by graph.catalog.repositories.collectAsStateWithLifecycle()
    val models by graph.catalog.models.collectAsStateWithLifecycle()
    val pinnedModelIds by graph.prefs.pinnedModelIds.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reposLoading by remember { mutableStateOf(false) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsUnavailable by remember { mutableStateOf(false) }
    var remembered by remember { mutableStateOf<ModelResolution.Candidate.Remembered?>(null) }
    val project = (target as? ProjectEditorTarget.Edit)?.let { edit -> list.agents.firstOrNull { it.id == edit.projectId } }
    // The look the desktop would give a new Project at random, drawn up front so the preview shows it and it can be changed.
    val suggestedLook = remember { graph.projectEditor.defaultAppearance() }
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
    // The model list, and what this device last launched with or picked, for the model the sheet opens on.
    fun loadModels(force: Boolean) {
        if (target !is ProjectEditorTarget.Create) return
        scope.launch {
            modelsLoading = true
            try {
                modelsUnavailable = graph.catalog.loadModels(force).isFailure && graph.catalog.models.value.isEmpty()
            } finally {
                modelsLoading = false
            }
        }
    }
    LaunchedEffect(target) {
        loadRepositories(force = false)
        loadModels(force = false)
        if (target is ProjectEditorTarget.Create) {
            val defaults = graph.prefs.composerDefaults.first()
            remembered = defaults.takeIf { it.modelChosen && it.modelId != null }?.let { ModelResolution.Candidate.Remembered(it.modelId!!, it.modelParams, it.modelChosenAtMillis) }
        }
    }
    // The composer's resolution (see [ModelResolution.forNewChat]): the newer of the model this device last used
    // and the account's newest chat's model, else Auto — the row the desktop's dialog opens its Model picker on.
    val defaultModel = remember(models, remembered, list.agents) {
        ModelResolution.forNewChat(models, listOfNotNull(remembered, ModelResolution.newestAccountModel(list.agents)), settleOnAuto = true)?.choice
    }
    ProjectEditorSheet(
        target = target,
        initialName = project?.name?.takeIf { target is ProjectEditorTarget.Edit }.orEmpty(),
        initialAppearance = if (target is ProjectEditorTarget.Create) suggestedLook else project?.projectAppearance,
        repositories = repositories,
        ownedRepoUrls = listOfNotNull(project?.repoUrl),
        repositoriesLoading = reposLoading,
        models = models,
        defaultModel = defaultModel,
        modelsLoading = modelsLoading,
        modelsUnavailable = modelsUnavailable,
        pinnedModelIds = pinnedModelIds,
        onTogglePinnedModel = { id -> scope.launch { graph.prefs.togglePinnedModel(id) } },
        onRetryModels = { loadModels(force = true) },
        busy = busy,
        error = error,
        onRefreshRepositories = { loadRepositories(force = true) },
        onConfirm = { result ->
            busy = true
            error = null
            scope.launch {
                val outcome = when (target) {
                    ProjectEditorTarget.Create -> graph.projectEditor.create(result.name, result.appearance, result.repoUrls, result.accountModel).map { id ->
                        // A model picked here is the model this device last picked, as the composer would remember it.
                        result.model?.takeIf { it != defaultModel }?.let { choice -> graph.prefs.rememberModel(choice.model.id, choice.params.associate { it.id to it.value }) }
                        val open: () -> Unit = { onOpenAgent(id) }
                        open
                    }
                    is ProjectEditorTarget.Edit -> graph.projectEditor.update(target.projectId, result.name, result.appearance).map { val nothing: () -> Unit = {}; nothing }
                }
                busy = false
                outcome.fold(
                    onSuccess = { then -> onDismiss(); then() },
                    onFailure = { error = refusalText(it) },
                )
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * The account's refusal, in the account's words: the Connect error's message as the server sent it, with its code
 * and status when it gave them, so a refusal such as "At least one model details is required" can be read back to
 * what the server checked. Anything else is the exception's own message.
 */
internal fun refusalText(failure: Throwable): String = when (failure) {
    is ConnectRpcException -> buildString {
        append(failure.message?.takeIf { it.isNotBlank() } ?: "Cursor refused the request.")
        val detail = listOfNotNull(failure.code?.takeIf { it.isNotBlank() }, "HTTP ${failure.httpCode}".takeIf { failure.httpCode != 200 })
        if (detail.isNotEmpty()) append(" (Cursor: ").append(detail.joinToString(", ")).append(')')
    }
    else -> failure.message?.takeIf { it.isNotBlank() } ?: "Cursor didn't answer."
}

/**
 * The desktop's Create Project dialog on a phone (Cursor 3.20.21 `CreateProjectDialog`), laid out as four steps in
 * the order they are set up: the name, the repositories (at least one, chosen and confirmed in a picker of their
 * own), the icon and colour beside a preview of the sidebar row they make, and the model. Create stays off, with the
 * reason beside it, until the name and a repository are there. For an existing Project the same sheet edits the name
 * and the look; its repositories are listed but fixed — the account service has no way to change them afterwards,
 * and neither has the desktop. A new Project opens on the look the desktop would have given it at random.
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
    /** The catalog for the Model step of a new Project, and the model it opens on (see [ProjectEditorHost]). */
    models: List<ModelOption> = emptyList(),
    defaultModel: ModelChoice? = null,
    modelsLoading: Boolean = false,
    modelsUnavailable: Boolean = false,
    pinnedModelIds: List<String> = emptyList(),
    onTogglePinnedModel: (String) -> Unit = {},
    onRetryModels: () -> Unit = {},
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val creating = target is ProjectEditorTarget.Create
    // An icon this build cannot draw is kept as the account has it unless another is picked.
    val startIcon = ProjectIcons.canonical(initialAppearance?.icon) ?: initialAppearance?.icon ?: ProjectIcons.DEFAULT_ICON
    val startColor = initialAppearance?.colorId?.takeIf(ProjectPalette::isKnown) ?: ProjectPalette.DEFAULT_ID
    var name by rememberSaveable { mutableStateOf(initialName) }
    var icon by rememberSaveable { mutableStateOf(startIcon) }
    var colorId by rememberSaveable { mutableStateOf(startColor) }
    // An ArrayList, which the saved state can hold: the repositories confirmed in the picker, in the order picked.
    var repos by rememberSaveable { mutableStateOf(ArrayList(ownedRepoUrls)) }
    var pickingRepos by rememberSaveable { mutableStateOf(false) }
    var browsingIcons by rememberSaveable { mutableStateOf(false) }
    // The model: the picker's choice once one is made, else what the sheet opened on (which can settle late, as the list loads).
    var pickedModel by remember { mutableStateOf<ModelChoice?>(null) }
    var modelSheetOpen by rememberSaveable { mutableStateOf(false) }
    val model = pickedModel ?: defaultModel
    val look = ProjectAppearance(icon, colorId)
    val restyled = look != ProjectAppearance(startIcon, startColor)
    val trimmed = name.trim()
    val blocker = when {
        creating && trimmed.isEmpty() && repos.isEmpty() -> "Add a name and a repository"
        trimmed.isEmpty() -> if (creating) "Add a name to continue" else "Add a name to save"
        creating && repos.isEmpty() -> "Choose a repository to continue"
        !creating && trimmed == initialName.trim() && !restyled -> "No changes yet"
        else -> null
    }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader(if (creating) "New Project" else "Edit Project")
        Text(
            if (creating) "One chat that plans the work and runs agents to do it." else "Changes also show on desktop and on cursor.com.",
            style = type.small, color = colors.textTertiary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp),
        )
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .scrollEdgeFade(clippedAtTop = scroll.canScrollBackward, clippedAtBottom = scroll.canScrollForward, surface = colors.elevated)
                .verticalScroll(scroll)
                .padding(bottom = 6.dp)
                .testTag("project-editor-list"),
        ) {
            StepHeader(step = 1.takeIf { creating }, title = "Name", done = trimmed.isNotEmpty(), status = "Required".takeIf { trimmed.isEmpty() })
            NameField(value = name, onValueChange = { name = it.take(MAX_NAME) })

            StepHeader(
                step = 2.takeIf { creating },
                title = "Repositories",
                helper = if (creating) "The code this Project works on." else "Repositories can't be changed after a Project is created.",
                done = creating && repos.isNotEmpty(),
                status = when {
                    !creating -> null
                    repos.isEmpty() -> "Required"
                    else -> "${repos.size} selected"
                },
            )
            StepCard(Modifier.testTag("project-repos")) {
                repos.forEach { url -> RepositoryLine(Repository(url), confirmed = creating) }
                when {
                    creating && repos.isEmpty() -> CardRow(CursorIcons.Plus, "Choose repositories", "Pick one or more", onClick = { pickingRepos = true }, chevron = true)
                    creating -> CardRow(CursorIcons.Pencil, "Change repositories", null, onClick = { pickingRepos = true }, chevron = true)
                    repos.isEmpty() -> CardRow(CursorIcons.Repo, "No repository", "This Project runs without one.", onClick = null)
                }
            }

            StepHeader(step = 3.takeIf { creating }, title = "Icon and colour", helper = "How the Project shows in your sidebar.", status = "Optional".takeIf { creating })
            StepCard {
                ProjectRowPreview(name = trimmed, look = look, repo = repos.firstOrNull()?.let { Repository(it).shortName })
                Spacer(Modifier.height(12.dp))
                CardLabel("Colour")
                ColourSwatches(colorId = colorId, onPick = { colorId = it })
                Spacer(Modifier.height(12.dp))
                CardLabel("Icon")
                IconSuggestions(selected = icon, tone = colors.projectTone(colorId), onPick = { icon = it })
                Spacer(Modifier.height(4.dp))
                CardRow(CursorIcons.Search, "Search all ${ProjectIcons.ids.size} icons", null, onClick = { browsingIcons = true }, chevron = true, modifier = Modifier.testTag("browse-icons"))
            }

            if (creating) {
                StepHeader(step = 4, title = "Model", helper = "The AI model the Project works with.", status = "Optional")
                StepCard {
                    val label = when {
                        model != null -> model.label
                        modelsLoading -> "Loading models\u2026"
                        else -> AccountModel.AUTO_LABEL
                    }
                    val variant = model?.variant?.takeIf { !it.isDefault && model.model.variants.size > 1 }?.displayName
                    val detail = when {
                        variant != null -> variant
                        modelsUnavailable && model == null -> "Couldn't load the model list"
                        model?.model?.isAuto != false -> "Cursor picks the model for each task"
                        else -> null
                    }
                    CardRow(CursorIcons.Sparkle, label, detail, onClick = { modelSheetOpen = true }, chevron = true, modifier = Modifier.testTag("project-model"))
                }
            }
        }
        error?.let {
            // The account's words, whole: what it refused and why, not a line to squint at.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CardGutter).padding(top = 6.dp).background(colors.red.copy(alpha = 0.1f), CardShape).padding(12.dp).testTag("project-editor-error"),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (creating) "Cursor didn't create the Project" else "Cursor didn't save the Project", style = type.baseMedium, color = colors.textPrimary)
                    Text(it, style = type.small, color = colors.textSecondary)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = CardGutter).padding(top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                blocker.orEmpty(),
                style = type.small, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("project-create-reason"),
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
                    enabled = blocker == null,
                    onClick = { onConfirm(ProjectEditorResult(name, look.takeIf { creating || restyled }, repos, model)) },
                )
            }
        }
    }
    if (pickingRepos) {
        RepositoryPickerSheet(
            repositories = repositories,
            chosen = repos,
            loading = repositoriesLoading,
            onRefresh = onRefreshRepositories,
            onConfirm = { repos = ArrayList(it) },
            onDismiss = { pickingRepos = false },
        )
    }
    if (browsingIcons) {
        IconCatalogSheet(selected = icon, tone = colors.projectTone(colorId), onPick = { icon = it }, onDismiss = { browsingIcons = false })
    }
    if (modelSheetOpen) {
        ModelSheet(
            models = models,
            selectedModel = model?.model,
            selectedVariant = model?.variant,
            planMode = false,
            autoCreatePr = false,
            loading = modelsLoading,
            unavailable = modelsUnavailable,
            onPlanMode = null,
            onAutoCreatePr = null,
            onRetry = onRetryModels,
            onSelect = { chosenModel, chosenVariant -> if (chosenModel != null) pickedModel = ModelChoice(chosenModel, chosenVariant) },
            onDismiss = { modelSheetOpen = false },
            pinnedIds = pinnedModelIds,
            onTogglePin = onTogglePinnedModel,
        )
    }
}

/**
 * The repositories a new Project works on, picked from the account's list and confirmed with Done; Cancel, or
 * closing the sheet, keeps what was confirmed before. The rows keep the order they opened in, the ones already
 * chosen first, so nothing moves under the finger while picking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepositoryPickerSheet(
    repositories: List<Repository>,
    chosen: List<String>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var picked by rememberSaveable { mutableStateOf(ArrayList(chosen)) }
    var query by rememberSaveable { mutableStateOf("") }
    val ordered = remember(repositories) {
        val all = repositories.distinctBy { it.url }
        all.sortedBy { repo -> chosen.indexOf(repo.url).takeIf { it >= 0 } ?: (chosen.size + all.indexOf(repo)) }
    }
    val visible = ordered.filter { query.isBlank() || it.slug.contains(query.trim(), ignoreCase = true) }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader(
            "Choose repositories",
            trailing = {
                if (loading) SpinnerRing(modifier = Modifier.padding(end = 12.dp)) else FlatIconButton(CursorIcons.Refresh, "Refresh repositories", onClick = onRefresh)
            },
        )
        Text("Pick every repository this Project should work on.", style = type.small, color = colors.textTertiary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
        SearchField(value = query, onValueChange = { query = it }, placeholder = "Search repositories")
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("repo-picker-list"), contentPadding = PaddingValues(bottom = 6.dp)) {
            items(visible, key = { it.url }) { repo ->
                val on = repo.url in picked
                RepositoryChoice(repo, on) { picked = ArrayList(if (on) picked - repo.url else picked + repo.url) }
            }
            if (visible.isEmpty()) {
                item("none") {
                    Text(
                        when {
                            loading -> "Loading your repositories\u2026"
                            query.isNotBlank() -> "No repositories match \u201C${query.trim()}\u201D"
                            else -> "No repositories found on your account. Refresh to try again."
                        },
                        style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = CardGutter).padding(top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (picked.isEmpty()) "Pick at least one" else "${picked.size} selected",
                style = type.small, color = colors.textTertiary, maxLines = 1, modifier = Modifier.weight(1f),
            )
            CursorButton("Cancel", onClick = dismiss, modifier = Modifier.testTag("repo-picker-cancel"))
            Spacer(Modifier.width(8.dp))
            CursorButton("Done", primary = true, enabled = picked.isNotEmpty(), onClick = { onConfirm(picked); dismiss() }, modifier = Modifier.testTag("repo-picker-done"))
        }
    }
}

/**
 * Every icon a Project can have, searched the desktop's way: the suggested ones first, then the desktop's sections;
 * a pick closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconCatalogSheet(selected: String, tone: Color, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(query) {
        val found = ProjectIcons.groups.map { group -> group.copy(ids = ProjectIcons.search(query, within = group.ids)) }.filter { it.ids.isNotEmpty() }
        if (query.isBlank()) listOf(ProjectIconGroup("Suggested", ProjectEditor.DEFAULT_ICONS)) + found else found
    }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader("All icons")
        SearchField(value = query, onValueChange = { query = it }, placeholder = "Search ${ProjectIcons.ids.size} icons")
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = IconCellSize + 4.dp),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("icon-catalog"),
            contentPadding = PaddingValues(start = CardGutter, end = CardGutter, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (sections.isEmpty()) {
                item("none", span = { GridItemSpan(maxLineSpan) }) {
                    Text("No icons match \u201C${query.trim()}\u201D", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
                }
            }
            sections.forEach { section ->
                item("header:${section.label}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(section.label, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
                }
                items(section.ids, key = { "${section.label}:$it" }) { candidate ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LookIconCell(candidate, selected = candidate == selected, tone = tone) { onPick(candidate); dismiss() }
                    }
                }
            }
        }
    }
}

/**
 * A step's title: its number in a disc that turns into a check once a required step is filled in, a line saying
 * what the step is for, and on the right whether it is required, optional or how much is chosen. An existing
 * Project's sheet has no steps to work through, so its titles carry no number.
 */
@Composable
private fun StepHeader(step: Int?, title: String, helper: String? = null, done: Boolean = false, status: String? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        if (step != null) {
            val disc by animateColorAsState(if (done) colors.accent else colors.fill, tween(160), label = "step")
            Box(Modifier.padding(top = 1.dp).size(StepDisc).background(disc, CircleShape), contentAlignment = Alignment.Center) {
                if (done) {
                    Icon(CursorIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(11.dp))
                } else {
                    Text(step.toString(), style = type.tiny, color = colors.textSecondary)
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.baseMedium, color = colors.textPrimary)
            if (helper != null) Text(helper, style = type.small, color = colors.textTertiary)
        }
        if (status != null) {
            Spacer(Modifier.width(8.dp))
            Text(status, style = type.small, color = if (done) colors.textSecondary else colors.textQuaternary, maxLines = 1)
        }
    }
}

/** A step's surface: a soft lift off the sheet, no stroke, its rows inset so their corners are concentric with its own. */
@Composable
private fun StepCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = CardGutter).background(CursorTheme.colors.fillSoft, CardShape).padding(CardPadding),
        content = content,
    )
}

/** The name as one line on the step's own surface, a shade stronger while it has the keyboard. */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(if (focused) colors.fill else colors.fillSoft, tween(160), label = "name")
    Box(
        Modifier.fillMaxWidth().padding(horizontal = CardGutter).stylusWriting().background(fill, CardShape).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = type.base.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = NAME_PLACEHOLDER }.testTag("project-name"),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(NAME_PLACEHOLDER, style = type.base, color = colors.textQuaternary); inner() }
            },
        )
    }
}

/** The pickers' search: the steps' surface with a glyph and one line, no stroke. */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CardGutter).stylusWriting().background(colors.fillSoft, CardShape).heightIn(min = 44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Search, null, tint = colors.iconTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = type.base.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = type.base, color = colors.textQuaternary); inner() } },
        )
    }
}

/** A row inside a step's surface: a glyph, a title and an optional line under it, a chevron when it opens a picker. */
@Composable
private fun CardRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    chevron: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick, InnerShape) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A repository the Project has: its name and owner, with the accent check while it is a new Project's confirmed pick. */
@Composable
private fun RepositoryLine(repo: Repository, confirmed: Boolean) {
    val colors = CursorTheme.colors
    CardRow(CursorIcons.Repo, repo.shortName, repo.slug.substringBeforeLast('/', "").ifEmpty { null }, onClick = null) {
        if (confirmed) {
            Spacer(Modifier.width(8.dp))
            Icon(CursorIcons.Check, "Selected", tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}

/** A repository in the picker: tap to add it or take it out; the round box on the right says which. */
@Composable
private fun RepositoryChoice(repo: Repository, on: Boolean, onToggle: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .pressable(onToggle, CursorTheme.shapes.base, role = Role.Checkbox)
            .semantics { selected = on }
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Repo, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(repo.shortName, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            repo.slug.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { Text(it, style = type.small, color = colors.textQuaternary, maxLines = 1) }
        }
        Spacer(Modifier.width(12.dp))
        val box = Modifier.size(20.dp)
        if (on) {
            Box(box.background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(CursorIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
            }
        } else {
            Box(box.border(1.5.dp, colors.iconQuaternary, CircleShape))
        }
    }
}

/**
 * The Project's row as the sidebar will draw it — its icon in its colour, its name, its first repository at the
 * trailing edge — on the sidebar's own surface, so the look is judged where it will be seen.
 */
@Composable
private fun ProjectRowPreview(name: String, look: ProjectAppearance, repo: String?) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.sidebar, InnerShape)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp)
            .clearAndSetSemantics { contentDescription = "Preview: ${ProjectIcons.label(look.icon)} icon in ${ProjectPalette.label(look.colorId)}" }
            .testTag("project-preview"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectGlyph(look)
        Spacer(Modifier.width(10.dp))
        Text(
            name.ifEmpty { NAME_PLACEHOLDER },
            style = type.row, color = if (name.isEmpty()) colors.textQuaternary else colors.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (repo != null) {
            Spacer(Modifier.width(8.dp))
            Text(repo, style = type.base, color = colors.textQuaternary, maxLines = 1)
        }
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
}

/** The ten tones across the step's width, as large as the width allows up to 32dp; the chosen one ringed. */
@Composable
private fun ColourSwatches(colorId: String, onPick: (String) -> Unit) {
    val colors = CursorTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        val gap = 6.dp
        val count = ProjectPalette.tones.size
        val size: Dp = min(32.dp, (maxWidth - gap * (count - 1)) / count)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ProjectPalette.tones.forEach { candidate ->
                val chosen = candidate.id == colorId
                Box(
                    Modifier
                        .size(size)
                        .then(if (chosen) Modifier.border(1.5.dp, colors.textPrimary, CircleShape) else Modifier)
                        .pressable({ onPick(candidate.id) }, CircleShape)
                        .semantics { contentDescription = "Colour ${candidate.label}"; this.selected = chosen }
                        .padding(if (chosen) 4.dp else 0.dp)
                        .background(colors.projectTone(candidate.id), CircleShape),
                )
            }
        }
    }
}

/**
 * Two rows of the icons the desktop picks from for a new Project, drawn in the chosen colour; the chosen icon is
 * always among them, first when it came from the full catalog.
 */
@Composable
private fun IconSuggestions(selected: String, tone: Color, onPick: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        val perRow = ((maxWidth + 4.dp) / (IconCellSize + 4.dp)).toInt().coerceAtLeast(4)
        val shown = perRow * SUGGESTED_ROWS
        val pool = ProjectEditor.DEFAULT_ICONS
        val icons = if (selected in pool.take(shown) || !ProjectIcons.isKnown(selected)) pool.take(shown) else listOf(selected) + pool.filter { it != selected }.take(shown - 1)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            icons.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { candidate -> LookIconCell(candidate, selected = candidate == selected, tone = tone) { onPick(candidate) } }
                }
            }
        }
    }
}

/** One icon, in the chosen colour; the chosen one sits on a wash of that colour. */
@Composable
private fun LookIconCell(candidate: String, selected: Boolean, tone: Color, onPick: () -> Unit) {
    Box(
        Modifier
            .size(IconCellSize)
            .background(if (selected) tone.copy(alpha = 0.18f) else Color.Transparent, CircleShape)
            .pressable(onPick, CircleShape)
            .semantics { contentDescription = "Icon ${ProjectIcons.label(candidate)}"; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(CursorIcons.project(candidate), null, tint = tone, modifier = Modifier.size(18.dp))
    }
}

private const val MAX_NAME = 100
private const val NAME_PLACEHOLDER = "Project name"
private const val SUGGESTED_ROWS = 2

private val CardGutter = 16.dp
private val CardPadding = 6.dp
private val CardShape: Shape @Composable get() = CursorTheme.shapes.xl
/** The step surface's radius less its padding (12 − 6), so a row's corners share the surface's centres. */
private val InnerShape: Shape @Composable get() = CursorTheme.shapes.base
private val StepDisc = 18.dp
private val IconCellSize = 40.dp
