package com.cursorforandroid.ui.panel

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RepoEntry
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.Pill
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.conversation.LineCounts
import com.cursorforandroid.ui.theme.CursorTheme

// -- Files › Workspace ----------------------------------------------------------------------------------------------

internal const val WORKSPACE_REASON = "The agent's live workspace (ListWorkspaceFiles, ReadBinaryFile) is only reachable through undocumented endpoints"

/**
 * The agent's workspace as a tree: the listing is read whole once (`ListWorkspaceFiles`) and walked here; a file
 * opens through `ReadBinaryFile` into the same viewer the repository's files use. Behind the `workspaceFiles`
 * capability; the demo has no VM.
 */
@Composable
internal fun WorkspaceBrowser(state: PanelState, actions: PanelActions) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    if (!state.capabilities.workspaceFiles) {
        RequiresExtendedRow(SectionAvailability.RequiresExtended(WORKSPACE_REASON, ready = true), extendedOn = state.capabilities.anyExtended)
        return
    }
    if (state.isDemo) {
        EmptyRow("The demo has no workspace to browse")
        return
    }
    val workspace = state.workspace
    LaunchedEffect(state.agentId) { if (workspace.tree is RemoteLoad.Idle) actions.loadWorkspace() }
    val segments = workspace.path.split('/').filter { it.isNotEmpty() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("workspace", style = type.small, color = if (segments.isEmpty()) colors.textSecondary else colors.link, modifier = Modifier.pressable({ actions.browseWorkspace("") }, CursorTheme.shapes.sm, enabled = segments.isNotEmpty()).padding(2.dp))
        segments.forEachIndexed { index, segment ->
            Text(" / ", style = type.small, color = colors.textQuaternary)
            val last = index == segments.lastIndex
            Text(segment, style = type.small, color = if (last) colors.textSecondary else colors.link, modifier = Modifier.pressable({ actions.browseWorkspace(segments.take(index + 1).joinToString("/")) }, CursorTheme.shapes.sm, enabled = !last).padding(2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Pill("Live VM", icon = CursorIcons.Server, tint = colors.textTertiary, fill = colors.fillFaint)
    }
    when (val tree = workspace.tree) {
        RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Listing the agent's workspace…")
        is RemoteLoad.Failed -> FailedRow(tree.message, onRetry = if (tree.retryable) ({ actions.loadWorkspace(force = true) }) else null)
        is RemoteLoad.Unsupported -> RequiresExtendedRow(SectionAvailability.RequiresExtended(tree.reason, ready = true), extendedOn = state.capabilities.anyExtended)
        is RemoteLoad.Loaded -> {
            val entries = tree.value.list(workspace.path)
            if (!workspace.isAtRoot) PanelRow(title = "..", icon = CursorIcons.Folder, onClick = actions::browseWorkspaceUp, modifier = Modifier.testTag("workspace-up"))
            when {
                tree.value.isEmpty -> EmptyRow("The workspace is empty", "The agent's VM lists no files yet.")
                entries.isEmpty() -> EmptyRow("Empty directory")
                else -> entries.forEach { entry -> WorkspaceEntryRow(entry, onClick = { if (entry.isDirectory) actions.browseWorkspace(entry.path) else actions.openWorkspaceFile(entry.path) }) }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                CursorButton("Refresh", { actions.loadWorkspace(force = true) }, icon = CursorIcons.Refresh, height = 28.dp, modifier = Modifier.testTag("workspace-refresh"))
            }
        }
    }
}

@Composable
private fun WorkspaceEntryRow(entry: RepoEntry, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    PanelRow(
        title = entry.name,
        icon = if (entry.isDirectory) CursorIcons.Folder else iconForExtension(entry.extension),
        iconTint = if (entry.isDirectory) colors.iconSecondary else colors.iconTertiary,
        trailing = if (entry.isDirectory) ({ Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp)) }) else null,
        onClick = onClick,
        modifier = Modifier.testTag(if (entry.isDirectory) "workspace-dir" else "workspace-file"),
    )
}

// -- Changes › branch diff ------------------------------------------------------------------------------------------

internal const val DIFF_REASON = "The branch's full diff against its base, before a pull request exists, comes from GetBackgroundComposerDiffDetails, an undocumented endpoint"

/** The branch's changed files from the account, drawn like the pull request's; a row opens its patch in the viewer. */
@Composable
internal fun BranchDiffFiles(files: List<AgentDiffFile>, actions: PanelActions) {
    val colors = CursorTheme.colors
    files.forEach { file ->
        val (icon, tint) = when (file.status) {
            ChangedFileStatus.Added, ChangedFileStatus.Copied -> CursorIcons.Plus to colors.gitAdded
            ChangedFileStatus.Removed -> CursorIcons.Trash to colors.gitRemoved
            ChangedFileStatus.Renamed -> CursorIcons.ArrowUp to colors.gitModified
            else -> CursorIcons.Pencil to colors.gitModified
        }
        val openable = file.patch != null || file.modifiedContent != null
        PanelRow(
            title = file.name,
            icon = icon,
            iconTint = tint,
            subtitle = listOfNotNull(file.previousPath?.let { "was $it" }, file.path.takeIf { it != file.name }).firstOrNull(),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineCounts(file.additions, file.deletions)
                    if (openable) {
                        Spacer(Modifier.width(4.dp))
                        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
                    }
                }
            },
            onClick = if (openable) ({ actions.openBranchDiffFile(file) }) else null,
            modifier = Modifier.testTag("branch-diff-file"),
        )
    }
}

// -- Remote ---------------------------------------------------------------------------------------------------------

/** The Remote Control floors Cursor documents, named rather than discovered the hard way. */
internal const val REMOTE_CONTROL_FLOORS = "Remote Control needs Cursor 3.9.8 or later on the machine, the Agents Window, a paid plan, and cloud data storage left on. It is in beta."
internal const val DESKTOP_REASON = "Viewing or taking control of the agent's desktop needs GetMachine and the VM's VNC endpoint, which are undocumented"
internal const val REMOTE_CONTROL_RELAY = "The machine's desktop streams through a relay this build doesn't carry yet; its screen is available in Cursor on the desktop."

/** "Remote Control · <machine>" for a chat on one of the user's machines; the environment's own word otherwise. */
internal fun remoteLabel(agent: Agent): String = when (agent.envType) {
    EnvType.MACHINE -> listOfNotNull("Remote Control", agent.envName?.substringBefore('#')?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
    EnvType.POOL -> listOfNotNull("Team pool", agent.envName).joinToString(" · ")
    EnvType.CLOUD -> "Cloud VM"
    EnvType.UNKNOWN -> agent.envName ?: "Unknown"
}

/**
 * Two halves. For a Remote Control chat — one that runs on the user's own machine through Cursor's managed
 * worker — the machine's name and connection state from the documented fleet endpoint, and the floors Cursor
 * documents; nothing here needs Extended mode. For a chat in the cloud, the agent's VM desktop over noVNC, view-only
 * or in control, behind the `remoteDesktop` capability; a stopped VM, a moved endpoint and a refused ticket are each
 * a named state.
 */
@Composable
internal fun RemoteSection(state: PanelState, actions: PanelActions) {
    val agent = state.agent
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("remote-section")) {
        if (agent == null) {
            LoadingRow("Loading the chat…")
            return@Column
        }
        if (agent.envType == EnvType.MACHINE) {
            RemoteControlHalf(state, agent, actions)
        } else {
            DesktopHalf(state, agent, actions)
        }
    }
}

@Composable
private fun RemoteControlHalf(state: PanelState, agent: Agent, actions: PanelActions) {
    val colors = CursorTheme.colors
    PanelCaption("Remote Control")
    FactRow("Runs on", remoteLabel(agent))
    PanelNote("The agent loop runs in the cloud while its tools run on your machine, through the worker Cursor manages there. Messages and steering reach it like any cloud chat.")
    when (val machine = state.machine) {
        RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Checking the machine…")
        is RemoteLoad.Failed -> FailedRow(machine.message, onRetry = if (machine.retryable) ({ actions.loadMachine(force = true) }) else null)
        is RemoteLoad.Unsupported -> UnsupportedRow(machine.reason, machine.url, actions::openUrl)
        is RemoteLoad.Loaded -> {
            val status = machine.value
            val tint = when {
                !status.connected -> colors.textQuaternary
                status.isBusyWith(state.agentId) -> colors.green
                status.isInUse -> colors.orange
                else -> colors.green
            }
            PanelRow(
                title = status.label(state.agentId),
                icon = CursorIcons.Desktop,
                iconTint = tint,
                subtitle = if (status.connected) "Listed by Cursor's worker endpoint" else "Cursor's worker endpoint doesn't list ${status.name} right now: the machine is off, asleep, or Cursor isn't running there.",
                trailing = { Pill(if (status.connected) "Online" else "Offline", tint = tint, fill = tint.copy(alpha = 0.12f)) },
                modifier = Modifier.testTag("machine-status"),
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                CursorButton("Refresh", { actions.loadMachine(force = true) }, icon = CursorIcons.Refresh, height = 28.dp, modifier = Modifier.testTag("machine-refresh"))
            }
        }
    }
    PanelNote(REMOTE_CONTROL_FLOORS)
    PanelCaption("Desktop")
    if (!state.capabilities.remoteDesktop) {
        RequiresExtendedRow(SectionAvailability.RequiresExtended(DESKTOP_REASON, ready = true), extendedOn = state.capabilities.anyExtended)
    } else {
        StateRow(CursorIcons.Desktop, "Not from this app yet", REMOTE_CONTROL_RELAY, modifier = Modifier.testTag("desktop-relay"))
    }
}

@Composable
private fun DesktopHalf(state: PanelState, agent: Agent, actions: PanelActions) {
    val colors = CursorTheme.colors
    PanelCaption("Desktop")
    FactRow("Runs on", remoteLabel(agent))
    if (!state.capabilities.remoteDesktop) {
        RequiresExtendedRow(SectionAvailability.RequiresExtended(DESKTOP_REASON, ready = true), extendedOn = state.capabilities.anyExtended)
        return
    }
    if (state.isDemo) {
        EmptyRow("The demo has no desktop to show")
        return
    }
    when (val desktop = state.desktop) {
        DesktopState.Idle -> {
            PanelNote("Watch the agent's screen as it works, or take control of it to try the software it is building. Hand control back by switching to view only.")
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CursorButton("View desktop", { actions.openDesktop(viewOnly = true) }, icon = CursorIcons.Eye, height = 30.dp, modifier = Modifier.testTag("desktop-view"))
                CursorButton("Take control", { actions.openDesktop(viewOnly = false) }, icon = CursorIcons.Desktop, height = 30.dp, modifier = Modifier.testTag("desktop-control"))
            }
            if (!agent.isRunning) PanelNote("A finished chat's VM is hibernated and a stopped one has no desktop; a follow-up wakes it.")
        }
        DesktopState.Opening -> LoadingRow("Finding the desktop…")
        is DesktopState.Failed -> FailedRow(desktop.failure.message, onRetry = { actions.openDesktop() }, modifier = Modifier.testTag("desktop-failed"))
        is DesktopState.Open -> {
            PanelRow(
                title = if (desktop.session.viewOnly) "Desktop open · view only" else "Desktop open · in control",
                icon = CursorIcons.Desktop,
                iconTint = colors.green,
                subtitle = desktop.session.port?.let { "noVNC over websockify, port $it" },
                modifier = Modifier.testTag("desktop-open"),
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                CursorButton("Disconnect", actions::closeDesktop, icon = CursorIcons.Close, height = 28.dp)
            }
        }
    }
}
