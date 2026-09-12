package com.cursorforandroid.ui.projects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.ui.components.CursorIcons

/**
 * The Project section of a conversation's right-side panel (spec §7), for the panel to mount once its section
 * registry exists on `main`: for a Project's coordinator, the Project's primaries with their live status and the
 * coordinator's actions; for a primary, side chat or subagent, the Project it belongs to and a way there; for any
 * other chat, a named "not part of a Project" state. Self-contained — it owns its view model, keyed on the chat,
 * and attaches the Project's polling only while it is on screen — so the panel needs nothing beyond the graph and
 * two navigation hands. [maxHeight] bounds the list inside a scrolling panel.
 */
@Composable
fun ProjectPanelSection(
    graph: AppGraph,
    agentId: String,
    onOpenAgent: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 480.dp,
) {
    val list by graph.agents.state.collectAsStateWithLifecycle()
    val agent = list.agents.firstOrNull { it.id == agentId }
    val parent = agent?.parent
    when {
        agent == null -> NoticeRow("This chat isn't loaded yet.", icon = CursorIcons.Clock)
        agent.isProjectRoot -> ProjectPanelBody(graph, agentId, onOpenAgent, modifier.heightIn(max = maxHeight))
        parent != null -> {
            val root = list.agents.firstOrNull { it.id == parent.id }
            val role = when (parent.kind) {
                AgentParentKind.PROJECT_WORKER -> "A primary of"
                AgentParentKind.SIDE_CHAT -> "A side chat of"
                AgentParentKind.SUBAGENT -> "A subagent of"
            }
            Column(modifier.fillMaxWidth()) {
                ActionRow(CursorIcons.project(root?.projectAppearance?.icon), root?.name ?: "its Project", "$role this chat \u00B7 open the Project", enabled = true, onClick = { onOpenProject(parent.id) })
            }
        }
        else -> NoticeRow("This chat isn't part of a Project.", icon = CursorIcons.Folder)
    }
}

@Composable
private fun ProjectPanelBody(graph: AppGraph, projectId: String, onOpenAgent: (String) -> Unit, modifier: Modifier) {
    val viewModel: ProjectViewModel = viewModel(key = "project-panel-$projectId", factory = ProjectViewModel.Factory(graph, projectId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val local by graph.prefs.localAgentState.collectAsStateWithLifecycle(initialValue = LocalAgentState())
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()
    val adoptable by viewModel.adoptable.collectAsStateWithLifecycle()
    val otherProjects by viewModel.otherProjects.collectAsStateWithLifecycle()
    val contextFile by viewModel.contextFile.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<ProjectSheet?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleStartEffect(projectId) {
        viewModel.resume()
        onStopOrDispose { viewModel.pause() }
    }
    // The panel has no snackbar of its own: the last outcome reads as a line under the section.
    LaunchedEffect(toast) {
        toast?.let {
            notice = it
            viewModel.clearToast()
        }
    }
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
    Column(modifier.fillMaxWidth()) {
        notice?.let { NoticeRow(it, icon = CursorIcons.Check) }
        ProjectBody(state, local, busy, actions, nowMillis = viewModel.now())
    }
    when (val open = sheet) {
        null -> Unit
        ProjectSheet.NewWorker -> NewWorkerSheet(root = state.root, onLaunch = { prompt, name -> viewModel.createWorker(prompt, name, repoUrl = null, baseBranch = null) }, onDismiss = { sheet = null })
        ProjectSheet.Adopt -> AdoptSheet(candidates = adoptable, onPick = viewModel::adopt, onDismiss = { sheet = null })
        ProjectSheet.NewSideChat -> NameSheet(title = "New side chat", placeholder = "Name (optional)", action = "Start", onConfirm = viewModel::startSideChat, onDismiss = { sheet = null })
        ProjectSheet.Appearance -> AppearanceSheet(current = state.root?.projectAppearance, onPick = viewModel::updateAppearance, onDismiss = { sheet = null })
        is ProjectSheet.Steer -> SteerSheet(workerName = open.name, onSteer = { text -> viewModel.steer(open.agentId, text) }, onDismiss = { sheet = null })
        is ProjectSheet.Move -> MoveSheet(workerName = open.name, projects = otherProjects, onPick = { viewModel.reparent(open.agentId, it) }, onDismiss = { sheet = null })
    }
    contextFile?.let { file -> ContextFileSheet(file, onDismiss = viewModel::closeContextFile) }
}
