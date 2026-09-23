package com.cursorforandroid.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.repo.ContextState
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectWorker
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorMenu
import com.cursorforandroid.ui.components.CursorMenuItem
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.GroupLabel
import com.cursorforandroid.ui.components.LocalRunStopConfirmation
import com.cursorforandroid.ui.components.Pill
import com.cursorforandroid.ui.components.ProjectGlyph
import com.cursorforandroid.ui.components.RunInterruption
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.StateGlyph
import com.cursorforandroid.ui.components.askOrRun
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.rememberThumbnailSlot
import com.cursorforandroid.ui.media.thumbnailSlot
import com.cursorforandroid.ui.panel.sectionRow
import com.cursorforandroid.ui.panel.sectionRows
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * A Cursor Project as its coordinator chat's panel shows it — the Project's one surface: what it is doing at a
 * glance with its icon and colour (editable in Extended mode), its primaries with their live status and each one's
 * menu (open, steer, hold, stop, move, release), the coordinator's hands (New primary, Adopt a chat), its cloud
 * subagents, and its shared context (the Agent Store) folder by folder. Every row of a chat opens that chat. The
 * coordinator's side chats are the panel's own Side chats section, as any chat's are. Its rows are the panel list's
 * own items, so a Project with hundreds of primaries composes the ones on screen rather than all of them as the panel
 * opens; the named state stands where Extended mode is off.
 */
internal fun LazyListScope.projectSection(
    state: ProjectViewState,
    local: LocalAgentState,
    busy: Boolean,
    actions: ProjectActions,
    nowMillis: Long,
) {
    sectionRow("project-summary") {
        ProjectSummary(state, nowMillis, onEditAppearance = if (state.actionsAvailable) actions.onEditAppearance else null, enabled = !busy)
    }

    sectionRow("project-primaries-label") {
        SectionLabel(if (state.workers.isEmpty()) "Primaries" else "Primaries \u00B7 ${state.workers.size}", syncing = state.isSyncing)
    }
    if (state.workers.isEmpty()) {
        sectionRow("project-primaries-empty") {
            Text(
                if (state.hasSynced || state.isSyncing) "No primaries yet. The coordinator creates them as it delegates; you can start one below." else "Loading\u2026",
                style = CursorTheme.typography.small, color = CursorTheme.colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
    sectionRows(state.workers.distinctBy { it.agent.id }, key = { "project-primary-${it.agent.id}" }, contentType = { "project-primary" }) { worker ->
        WorkerRow(worker, local, nowMillis, actionsAvailable = state.actionsAvailable, busy = busy, actions = actions)
    }
    state.lineageNotice?.let { notice -> sectionRow("project-lineage-notice") { NoticeRow(notice) } }
    if (state.actionsAvailable) {
        sectionRow("project-new-primary") {
            ActionRow(CursorIcons.Plus, "New primary", "Start an agent under this Project", enabled = !busy, onClick = actions.onNewWorker, modifier = Modifier.testTag("project-new-primary"))
        }
        sectionRow("project-adopt") {
            ActionRow(CursorIcons.Layers, "Adopt a chat", "Bring one of your chats into the Project", enabled = !busy, onClick = actions.onAdopt, modifier = Modifier.testTag("project-adopt"))
        }
    }

    if (state.subagents.isNotEmpty()) {
        sectionRow("project-subagents-label") { SectionLabel("Subagents \u00B7 ${state.subagents.size}") }
        sectionRows(state.subagents.distinctBy { it.id }, key = { "project-subagent-${it.id}" }, contentType = { "project-subagent" }) { sub ->
            AgentLine(sub, local, nowMillis, subtitle = "Cloud subagent", onOpen = { actions.onOpenAgent(sub) })
        }
    }

    sectionRow("project-context-label") { SectionLabel("Context") }
    contextItems(state.projectId, state.context, busy, actions)

    sectionRow("project-refresh") {
        ActionRow(CursorIcons.Refresh, "Refresh", "Re-read the primaries and the account's memberships", enabled = !busy, onClick = actions.onRefresh, modifier = Modifier.testTag("project-refresh"))
    }
    sectionRow("project-end") { Spacer(Modifier.fillMaxWidth().height(6.dp)) }
}

/** The screen's hands, passed down to the rows; see [projectSection]. */
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
    val onEditAppearance: () -> Unit,
    val onLoadContext: (String) -> Unit,
    val onContextUp: () -> Unit,
    val onOpenContextFile: (ContextEntry) -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * The Project at a glance: its glyph in its colour, how many primaries are working or waiting on an answer, when it
 * last moved — and, in Extended mode, the pencil that opens the Project editor (name, icon and colour).
 */
@Composable
private fun ProjectSummary(state: ProjectViewState, nowMillis: Long, onEditAppearance: (() -> Unit)?, enabled: Boolean) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val root = state.root
    val running = state.workers.count { it.agent.isRunning } + (if (root?.isRunning == true) 1 else 0)
    val needsInput = state.workers.count { it.agent.hasPendingInteraction }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(colors.projectTone(root?.projectAppearance?.colorId).copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(CursorIcons.project(root?.projectAppearance?.icon), "Project", tint = colors.projectTone(root?.projectAppearance?.colorId), modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val detail = buildList {
                add("${state.workers.size} ${if (state.workers.size == 1) "primary" else "primaries"}")
                root?.let { add("updated ${TimeFormat.relativeShort(it.listedAtMillis, nowMillis)}") }
            }
            Text(detail.joinToString(" \u00B7 "), style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (running > 0 || needsInput > 0) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (running > 0) Pill("$running working", tint = colors.textPrimary)
                    if (needsInput > 0) Pill("$needsInput needs input", tint = colors.orange, fill = colors.orange.copy(alpha = 0.14f))
                }
            }
        }
        if (onEditAppearance != null) {
            FlatIconButton(CursorIcons.Pencil, "Edit Project", onClick = onEditAppearance, enabled = enabled, modifier = Modifier.testTag("project-appearance"))
        }
    }
}

/**
 * The Project's shared context (Agent Store), by state: an offer to open it, its listing, or a named reason it is not
 * there. A picture, a recording or a sound in the listing opens the media viewer out of its row, among the folder's
 * other media, read through the store's presigned bytes — never its text read, which carries a string.
 */
private fun LazyListScope.contextItems(projectId: String, context: ContextState, busy: Boolean, actions: ProjectActions) {
    when (context) {
        ContextState.Idle -> sectionRow("project-context-open") {
            ActionRow(CursorIcons.Folder, "Show shared context", "The files this Project's agents share", enabled = !busy, onClick = { actions.onLoadContext("") }, modifier = Modifier.testTag("project-context-open"))
        }
        ContextState.Loading -> sectionRow("project-context-loading") { LoadingRow("Reading the Project's context\u2026") }
        ContextState.NoStore -> sectionRow("project-context-none") { EmptyRow("No shared context for this Project yet.") }
        is ContextState.Unavailable -> sectionRow("project-context-unavailable") { NoticeRow(context.reason) }
        is ContextState.Loaded -> {
            val path = context.context.relativePath
            val entries = context.context.entries
            if (path.isNotEmpty()) {
                sectionRow("project-context-up") { ActionRow(CursorIcons.ChevronLeft, path, "Back to the folder above", enabled = true, onClick = actions.onContextUp) }
            }
            if (entries.isEmpty()) sectionRow("project-context-empty") { EmptyRow("This folder is empty.") }
            val folderMedia = contextMedia(projectId, entries)
            sectionRows(entries.distinctBy { it.relativePath }, key = { "project-context-entry:${it.relativePath}" }, contentType = { "project-context-entry" }) { entry ->
                val viewer = LocalMediaViewer.current
                val media = folderMedia.firstOrNull { it.fileName == entry.name && !entry.isDirectory }
                if (media != null && viewer != null) {
                    val slot = rememberThumbnailSlot(media.src, CursorTheme.shapes.base, crop = true)
                    ContextRow(entry, Modifier.thumbnailSlot(slot)) {
                        viewer.open(projectId, folderMedia, media.src, slot, fallback = media, autoplay = media.isPlayable)
                    }
                } else {
                    ContextRow(entry, onClick = { if (entry.isDirectory) actions.onLoadContext(entry.relativePath) else actions.onOpenContextFile(entry) })
                }
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
    // Pause and Stop ask first while Settings › Confirm before stopping is on; the question goes with the run.
    val confirmation = LocalRunStopConfirmation.current
    LaunchedEffect(agent.isRunning) { if (!agent.isRunning) confirmation?.dismissFor(agent.id) }
    val detail = buildList {
        worker.spawnKind?.let { add(it.label) }
        agent.branchName?.let { add(it) } ?: agent.repoShortName?.let { add(it) }
        if (agent.listedAtMillis > 0) add(TimeFormat.relativeShort(agent.listedAtMillis, nowMillis))
    }.joinToString(" \u00B7 ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset)
            .pressable({ actions.onOpenAgent(agent) }, CursorTheme.shapes.base)
            .heightIn(min = CursorDimens.listRow)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("project-primary"),
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
            CursorMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                CursorMenuItem("Open and message", CursorIcons.ChevronRight) { menuOpen = false; actions.onOpenAgent(agent) }
                if (actionsAvailable) {
                    if (agent.isRunning) CursorMenuItem("Steer\u2026", CursorIcons.Target) { menuOpen = false; actions.onSteer(agent) }
                    if (agent.isRunning) CursorMenuItem("Pause", CursorIcons.Pause) { menuOpen = false; confirmation.askOrRun(RunInterruption.Pause, agent.id) { actions.onPause(agent.id) } }
                    CursorMenuItem("Resume", CursorIcons.Play) { menuOpen = false; actions.onResume(agent.id) }
                }
                if (agent.isRunning) CursorMenuItem("Stop", CursorIcons.Stop) { menuOpen = false; confirmation.askOrRun(RunInterruption.Stop, agent.id) { actions.onStop(agent.id) } }
                if (actionsAvailable) {
                    CursorMenuItem("Move under another Project\u2026", CursorIcons.Layers) { menuOpen = false; actions.onMove(agent) }
                    CursorMenuItem("Release from the Project", CursorIcons.ExternalLink, tint = colors.red) { menuOpen = false; actions.onRelease(agent.id) }
                }
            }
        }
    }
}

/** A chat's row without the worker menu: a subagent, or the coordinator a primary's panel points back to. */
@Composable
internal fun AgentLine(agent: Agent, local: LocalAgentState, nowMillis: Long, subtitle: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val row = AgentListOrganizer.toRow(agent, local, nowMillis)
    Row(
        modifier
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
            Text(listOfNotNull(subtitle, TimeFormat.relativeShort(agent.listedAtMillis, nowMillis).takeIf { agent.listedAtMillis > 0 }).joinToString(" \u00B7 "), style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun ContextRow(entry: ContextEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val format = if (entry.isDirectory) null else FileFormat.ofName(entry.name)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset)
            .pressable(onClick, CursorTheme.shapes.base)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("project-context-entry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                entry.isDirectory -> CursorIcons.Folder
                format?.isImage == true -> CursorIcons.Image
                format?.isVideo == true -> CursorIcons.Video
                format?.isAudio == true -> CursorIcons.Music
                else -> CursorIcons.File
            },
            null,
            tint = colors.iconSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(entry.name, style = type.row, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        entry.sizeBytes?.takeIf { !entry.isDirectory }?.let { Text(formatBytes(it), style = type.small, color = colors.textQuaternary) }
        if (entry.isDirectory) Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
    }
}

/** The folder's pictures, recordings and sounds, as the viewer pages through them: by their store path, in listing order. */
internal fun contextMedia(projectId: String, entries: List<ContextEntry>): List<MediaEntry> = entries.mapNotNull { entry ->
    if (entry.isDirectory) return@mapNotNull null
    val kind = MediaEntry.kindOf(FileFormat.ofName(entry.name)) ?: return@mapNotNull null
    MediaEntry(StorePath(projectId, entry.relativePath.trimStart('/')).text, kind, fileName = entry.name)
}

@Composable
internal fun ActionRow(icon: ImageVector, title: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
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
