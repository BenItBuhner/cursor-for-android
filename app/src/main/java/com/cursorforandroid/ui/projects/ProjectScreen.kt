package com.cursorforandroid.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.ContextState
import com.cursorforandroid.data.repo.ProjectRepository
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectWorker
import com.cursorforandroid.domain.SideChatAvailability
import com.cursorforandroid.ui.agents.MenuItem
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.GroupLabel
import com.cursorforandroid.ui.components.Pill
import com.cursorforandroid.ui.components.ProjectGlyph
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.StateGlyph
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * One Cursor Project: the coordinator chat, its primaries with their live status, its side chats and subagents,
 * and its shared context — with the coordinator's hands on the account service (spawn, adopt, release, steer,
 * hold, restyle, side chats) where Extended mode allows them, and a named state where it does not. Every row of a
 * chat opens that chat; back returns here.
 */
@Composable
fun ProjectScreen(
    graph: AppGraph,
    projectId: String,
    onBack: (() -> Unit)?,
    onOpenAgent: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSidebar: (() -> Unit)? = null,
) {
    val viewModel: ProjectViewModel = viewModel(key = "project-$projectId", factory = ProjectViewModel.Factory(graph, projectId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val local by graph.prefs.localAgentState.collectAsStateWithLifecycle(initialValue = LocalAgentState())
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()
    val adoptable by viewModel.adoptable.collectAsStateWithLifecycle()
    val otherProjects by viewModel.otherProjects.collectAsStateWithLifecycle()
    val contextFile by viewModel.contextFile.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.clearToast()
        }
    }
    LifecycleStartEffect(projectId) {
        viewModel.resume()
        onStopOrDispose { viewModel.pause() }
    }
    var sheet by rememberSaveable { mutableStateOf<ProjectSheet?>(null) }
    val actions = ProjectActions(
        onOpenAgent = { agent -> viewModel.markRead(agent); onOpenAgent(agent.id) },
        onSteer = { sheet = ProjectSheet.Steer(it.id, it.name) },
        onPause = viewModel::pauseWorker,
        onResume = viewModel::resumeWorker,
        onStop = viewModel::stop,
        onRelease = viewModel::release,
        onMove = { sheet = ProjectSheet.Move(it.id, it.name) },
        onNewWorker = { sheet = ProjectSheet.NewWorker },
        onAdopt = { sheet = ProjectSheet.Adopt },
        onNewSideChat = { sheet = ProjectSheet.NewSideChat },
        onEditAppearance = { sheet = ProjectSheet.Appearance },
        onLoadContext = viewModel::loadContext,
        onContextUp = viewModel::contextUp,
        onOpenContextFile = viewModel::openContextFile,
    )

    Column(modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
        ProjectHeader(state, onBack, onOpenSidebar, onRefresh = viewModel::refresh, onEditAppearance = actions.onEditAppearance, onOpenCoordinator = { state.root?.let(actions.onOpenAgent) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ProjectBody(state, local, busy, actions, nowMillis = viewModel.now(), modifier = Modifier.fillMaxSize())
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data ->
                Snackbar(snackbarData = data, containerColor = CursorTheme.colors.elevated, contentColor = CursorTheme.colors.textPrimary, shape = CursorTheme.shapes.lg)
            }
        }
    }

    when (val open = sheet) {
        null -> Unit
        ProjectSheet.NewWorker -> NewWorkerSheet(
            root = state.root,
            onLaunch = { prompt, name -> viewModel.createWorker(prompt, name, repoUrl = null, baseBranch = null) },
            onDismiss = { sheet = null },
        )
        ProjectSheet.Adopt -> AdoptSheet(candidates = adoptable, onPick = viewModel::adopt, onDismiss = { sheet = null })
        ProjectSheet.NewSideChat -> NameSheet(title = "New side chat", placeholder = "Name (optional)", action = "Start", onConfirm = viewModel::startSideChat, onDismiss = { sheet = null })
        ProjectSheet.Appearance -> AppearanceSheet(current = state.root?.projectAppearance, onPick = viewModel::updateAppearance, onDismiss = { sheet = null })
        is ProjectSheet.Steer -> SteerSheet(workerName = open.name, onSteer = { text -> viewModel.steer(open.agentId, text) }, onDismiss = { sheet = null })
        is ProjectSheet.Move -> MoveSheet(workerName = open.name, projects = otherProjects, onPick = { viewModel.reparent(open.agentId, it) }, onDismiss = { sheet = null })
    }
    contextFile?.let { file -> ContextFileSheet(file, onDismiss = viewModel::closeContextFile) }
}

/** The screen's hands, passed down to the rows; see [ProjectScreen]. */
internal class ProjectActions(
    val onOpenAgent: (Agent) -> Unit,
    val onSteer: (Agent) -> Unit,
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onStop: (String) -> Unit,
    val onRelease: (String) -> Unit,
    val onMove: (Agent) -> Unit,
    val onNewWorker: () -> Unit,
    val onAdopt: () -> Unit,
    val onNewSideChat: () -> Unit,
    val onEditAppearance: () -> Unit,
    val onLoadContext: (String) -> Unit,
    val onContextUp: () -> Unit,
    val onOpenContextFile: (ContextEntry) -> Unit,
)

@Composable
private fun ProjectHeader(
    state: ProjectViewState,
    onBack: (() -> Unit)?,
    onOpenSidebar: (() -> Unit)?,
    onRefresh: () -> Unit,
    onEditAppearance: () -> Unit,
    onOpenCoordinator: () -> Unit,
) {
    val colors = CursorTheme.colors
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val root = state.root
    CursorHeader(
        title = state.name,
        subtitle = listOfNotNull("Project", root?.repoShortName).joinToString(" \u00B7 "),
        leading = {
            when {
                onBack != null -> FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack)
                onOpenSidebar != null -> FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar)
            }
        },
        trailing = {
            FlatIconButton(CursorIcons.Refresh, "Refresh project", onClick = onRefresh)
            Box {
                FlatIconButton(CursorIcons.More, "More", onClick = { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                    MenuItem("Open coordinator chat", CursorIcons.Multitask) { menuOpen = false; onOpenCoordinator() }
                    if (state.actionsAvailable) MenuItem("Edit icon and colour", CursorIcons.Pencil) { menuOpen = false; onEditAppearance() }
                    MenuItem("Open on cursor.com", CursorIcons.ExternalLink) { menuOpen = false; root?.url?.let(uriHandler::openUri) }
                    MenuItem("Copy link", CursorIcons.Copy) { menuOpen = false; root?.url?.let { clipboard.setText(AnnotatedString(it)) } }
                }
            }
        },
    )
}

@Composable
internal fun ProjectBody(
    state: ProjectViewState,
    local: LocalAgentState,
    busy: Boolean,
    actions: ProjectActions,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val listState = rememberLazyListState()
    val root = state.root
    LazyColumn(modifier.scrollEdgeFade(listState), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item("hero") { ProjectHero(state, nowMillis) }
        item("coordinator-hdr") { SectionLabel("Coordinator") }
        item("coordinator") {
            if (root == null) {
                LoadingRow("Loading the Project\u2026")
            } else {
                AgentLine(root, local, nowMillis, subtitle = "Plans the work and delegates it", onOpen = { actions.onOpenAgent(root) })
            }
        }
        item("primaries-hdr") { SectionLabel(if (state.workers.isEmpty()) "Primaries" else "Primaries \u00B7 ${state.workers.size}", syncing = state.isSyncing) }
        if (state.workers.isEmpty()) {
            item("primaries-empty") {
                Text(
                    if (state.hasSynced || state.isSyncing) "No primaries yet. The coordinator creates them as it delegates; you can start one below." else "Loading\u2026",
                    style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(state.workers, key = { "worker:${it.id}" }) { worker ->
            WorkerRow(worker, local, nowMillis, actionsAvailable = state.actionsAvailable, busy = busy, actions = actions)
        }
        state.lineageNotice?.let { notice -> item("lineage-notice") { NoticeRow(notice) } }
        if (state.actionsAvailable) {
            item("new-worker") { ActionRow(CursorIcons.Plus, "New primary", "Start an agent under this Project", enabled = !busy, onClick = actions.onNewWorker) }
            item("adopt") { ActionRow(CursorIcons.Layers, "Adopt a chat", "Bring one of your chats into the Project", enabled = !busy, onClick = actions.onAdopt) }
        }

        item("side-hdr") { SectionLabel(if (state.sideChats.isEmpty()) "Side chats" else "Side chats \u00B7 ${state.sideChats.size}") }
        items(state.sideChats, key = { "side:${it.id}" }) { side ->
            AgentLine(side, local, nowMillis, subtitle = "Side chat", onOpen = { actions.onOpenAgent(side) })
        }
        item("side-action") {
            when {
                !state.actionsAvailable -> if (state.sideChats.isEmpty()) EmptyRow("No side chats.")
                state.sideChatAvailability == SideChatAvailability.COMING_TO_CURSOR -> NoticeRow(SIDE_CHATS_COMING, icon = CursorIcons.Clock)
                else -> ActionRow(CursorIcons.Plus, "New side chat", "Branch a conversation off the coordinator", enabled = !busy, onClick = actions.onNewSideChat)
            }
        }

        if (state.subagents.isNotEmpty()) {
            item("sub-hdr") { SectionLabel("Subagents \u00B7 ${state.subagents.size}") }
            items(state.subagents, key = { "sub:${it.id}" }) { sub ->
                AgentLine(sub, local, nowMillis, subtitle = "Cloud subagent", onOpen = { actions.onOpenAgent(sub) })
            }
        }

        item("context-hdr") { SectionLabel("Context") }
        contextItems(state.context, busy, actions)
    }
}

/** The Project's shared context (Agent Store), by state: an offer to open it, its listing, or a named reason it is not there. */
private fun androidx.compose.foundation.lazy.LazyListScope.contextItems(context: ContextState, busy: Boolean, actions: ProjectActions) {
    when (context) {
        ContextState.Idle -> item("context-open") { ActionRow(CursorIcons.Folder, "Show shared context", "The files this Project's agents share", enabled = !busy, onClick = { actions.onLoadContext("") }) }
        ContextState.Loading -> item("context-loading") { LoadingRow("Reading the Project's context\u2026") }
        ContextState.NoStore -> item("context-none") { EmptyRow("No shared context for this Project yet.") }
        is ContextState.Unavailable -> item("context-unavailable") { NoticeRow(context.reason) }
        is ContextState.Loaded -> {
            val path = context.context.relativePath
            if (path.isNotEmpty()) {
                item("context-up") { ActionRow(CursorIcons.ChevronLeft, path, "Back to the folder above", enabled = true, onClick = actions.onContextUp) }
            }
            if (context.context.entries.isEmpty()) item("context-empty") { EmptyRow("This folder is empty.") }
            items(context.context.entries, key = { "ctx:${it.relativePath}" }) { entry ->
                ContextRow(entry, onClick = { if (entry.isDirectory) actions.onLoadContext(entry.relativePath) else actions.onOpenContextFile(entry) })
            }
        }
    }
}

@Composable
private fun ProjectHero(state: ProjectViewState, nowMillis: Long) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val root = state.root
    val running = state.workers.count { it.agent.isRunning } + (if (root?.isRunning == true) 1 else 0)
    val needsInput = state.workers.count { it.agent.hasPendingInteraction }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(colors.projectTone(root?.projectAppearance?.colorId).copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(CursorIcons.project(root?.projectAppearance?.icon), "Project", tint = colors.projectTone(root?.projectAppearance?.colorId), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(state.name, style = type.sectionTitle, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = buildList {
                root?.repoSlug?.let { add(it) }
                root?.let { add("updated ${TimeFormat.relativeShort(it.updatedAtMillis, nowMillis)}") }
            }
            if (detail.isNotEmpty()) Text(detail.joinToString(" \u00B7 "), style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running > 0) Pill("$running working", tint = colors.textPrimary)
                if (needsInput > 0) Pill("$needsInput needs input", tint = colors.orange, fill = colors.orange.copy(alpha = 0.14f))
                if (running == 0 && needsInput == 0 && state.workers.isNotEmpty()) Pill("${state.workers.size} primaries", tint = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, syncing: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(text, Modifier.weight(1f))
        if (syncing) SpinnerRing(size = 11.dp)
    }
}

/** One primary: its state, name, how it came to belong and where it stands, a "needs input" mark, and its menu. */
@Composable
internal fun WorkerRow(
    worker: ProjectWorker,
    local: LocalAgentState,
    nowMillis: Long,
    actionsAvailable: Boolean,
    busy: Boolean,
    actions: ProjectActions,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val agent = worker.agent
    val row = AgentListOrganizer.toRow(agent, local, nowMillis)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val detail = buildList {
        worker.spawnKind?.let { add(it.label) }
        agent.branchName?.let { add(it) } ?: agent.repoShortName?.let { add(it) }
        if (agent.updatedAtMillis > 0) add(TimeFormat.relativeShort(agent.updatedAtMillis, nowMillis))
    }.joinToString(" \u00B7 ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset)
            .pressable({ actions.onOpenAgent(agent) }, CursorTheme.shapes.base)
            .heightIn(min = CursorDimens.listRow)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateGlyph(row.indicator, hasBranch = agent.hasBranch, hasPullRequest = agent.hasPullRequest, pullRequest = row.pullRequest)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.name, style = type.rowMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (agent.hasPendingInteraction) {
                    Spacer(Modifier.width(6.dp))
                    Pill("Needs input", tint = colors.orange, fill = colors.orange.copy(alpha = 0.14f))
                }
            }
            if (detail.isNotEmpty()) Text(detail, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            FlatIconButton(CursorIcons.More, "Actions for ${agent.name}", onClick = { menuOpen = true }, enabled = !busy)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                MenuItem("Open and message", CursorIcons.ChevronRight) { menuOpen = false; actions.onOpenAgent(agent) }
                if (actionsAvailable) {
                    if (agent.isRunning) MenuItem("Steer\u2026", CursorIcons.Target) { menuOpen = false; actions.onSteer(agent) }
                    if (agent.isRunning) MenuItem("Pause", CursorIcons.Pause) { menuOpen = false; actions.onPause(agent.id) }
                    MenuItem("Resume", CursorIcons.Play) { menuOpen = false; actions.onResume(agent.id) }
                }
                if (agent.isRunning) MenuItem("Stop", CursorIcons.Stop) { menuOpen = false; actions.onStop(agent.id) }
                if (actionsAvailable) {
                    MenuItem("Move under another Project\u2026", CursorIcons.Layers) { menuOpen = false; actions.onMove(agent) }
                    MenuItem("Release from the Project", CursorIcons.ExternalLink, tint = colors.red) { menuOpen = false; actions.onRelease(agent.id) }
                }
            }
        }
    }
}

/** A chat's row without the worker menu: the coordinator, a side chat, a subagent. */
@Composable
private fun AgentLine(agent: Agent, local: LocalAgentState, nowMillis: Long, subtitle: String, onOpen: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val row = AgentListOrganizer.toRow(agent, local, nowMillis)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset)
            .pressable(onOpen, CursorTheme.shapes.base)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (agent.looksLikeProject) ProjectGlyph(agent.projectAppearance, badge = if (row.indicator == AgentIndicator.Unread) colors.unreadDot else null)
        else StateGlyph(row.indicator, hasBranch = agent.hasBranch, hasPullRequest = agent.hasPullRequest, pullRequest = row.pullRequest)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(agent.name, style = type.rowMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(subtitle, TimeFormat.relativeShort(agent.updatedAtMillis, nowMillis).takeIf { agent.updatedAtMillis > 0 }).joinToString(" \u00B7 "), style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun ContextRow(entry: ContextEntry, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset)
            .pressable(onClick, CursorTheme.shapes.base)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (entry.isDirectory) CursorIcons.Folder else CursorIcons.File, null, tint = colors.iconSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(entry.name, style = type.row, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        entry.sizeBytes?.takeIf { !entry.isDirectory }?.let { Text(formatBytes(it), style = type.small, color = colors.textQuaternary) }
        if (entry.isDirectory) Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, title: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset)
            .pressable(onClick, CursorTheme.shapes.base, enabled = enabled)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CursorDimens.glyph), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (enabled) colors.accent else colors.iconQuaternary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.rowMedium, color = if (enabled) colors.textPrimary else colors.textQuaternary, maxLines = 1)
            if (subtitle != null) Text(subtitle, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A named state in place of an action or a list: what is missing and why, never a blank. */
@Composable
internal fun NoticeRow(text: String, icon: ImageVector = CursorIcons.Warning) {
    val colors = CursorTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = colors.iconTertiary, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = CursorTheme.typography.small, color = colors.textTertiary)
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun LoadingRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SpinnerRing(size = 12.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textQuaternary)
    }
}

/** "1.2 KB" the way file listings write sizes. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** The named state of a side chat Cursor will not start for a cloud chat yet (spec §4). */
const val SIDE_CHATS_COMING = "Side chats for cloud agents are coming to Cursor"

/** What the Project screen offers with Extended mode off, in one line under the primaries. */
val PROJECT_NEEDS_EXTENDED_MODE: String get() = ProjectRepository.NEEDS_EXTENDED_MODE
