package com.cursorforandroid.ui.projects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.ui.components.CursorIcons

/**
 * The Project section of a conversation's right-side panel — a Cursor Project's one surface in the app. For a
 * coordinator's chat: the Project's primaries with their live status and each one's menu, the coordinator's hands
 * (New primary, Adopt a chat, the icon and colour editor), its subagents and its shared context
 * ([ProjectSectionBody]). For a primary, side chat or subagent: the coordinator's chat it belongs to, a tap away.
 * Self-contained — it owns its view model, keyed on the chat, and attaches the Project's polling only while it is
 * on screen — so the panel needs nothing beyond the graph, a way to open a chat and a way to say what an action did.
 */
@Composable
fun ProjectPanelSection(
    graph: AppGraph,
    agentId: String,
    onOpenAgent: (String) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list by graph.agents.state.collectAsStateWithLifecycle()
    val agent = list.agents.firstOrNull { it.id == agentId }
    val parent = agent?.parent
    when {
        agent == null -> NoticeRow("This chat isn't loaded yet.", icon = CursorIcons.Clock)
        agent.looksLikeProject -> ProjectPanelBody(graph, agentId, onOpenAgent, onNotify, modifier)
        parent != null -> {
            val root = list.agents.firstOrNull { it.id == parent.id }
            val role = when (parent.kind) {
                AgentParentKind.PROJECT_WORKER -> "A primary of"
                AgentParentKind.SIDE_CHAT -> "A side chat of"
                AgentParentKind.SUBAGENT -> "A subagent of"
            }
            Column(modifier.fillMaxWidth()) {
                ActionRow(
                    CursorIcons.project(root?.projectAppearance?.icon),
                    root?.name ?: "its Project",
                    "$role this chat \u00B7 open the coordinator's chat",
                    enabled = true,
                    onClick = { onOpenAgent(parent.id) },
                    modifier = Modifier.testTag("project-coordinator-link"),
                )
            }
        }
        else -> NoticeRow("This chat isn't part of a Project.", icon = CursorIcons.Folder)
    }
}

@Composable
private fun ProjectPanelBody(graph: AppGraph, projectId: String, onOpenAgent: (String) -> Unit, onNotify: (String) -> Unit, modifier: Modifier) {
    val viewModel: ProjectViewModel = viewModel(key = "project-panel-$projectId", factory = ProjectViewModel.Factory(graph, projectId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val local by graph.prefs.localAgentState.collectAsStateWithLifecycle(initialValue = LocalAgentState())
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()
    val adoptable by viewModel.adoptable.collectAsStateWithLifecycle()
    val otherProjects by viewModel.otherProjects.collectAsStateWithLifecycle()
    val contextFile by viewModel.contextFile.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<ProjectSheet?>(null) }
    LifecycleStartEffect(projectId) {
        viewModel.resume()
        onStopOrDispose { viewModel.pause() }
    }
    // What an action came back with — "Started a new primary.", a refusal — goes out the panel's own way.
    LaunchedEffect(toast) {
        toast?.let {
            onNotify(it)
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
        onEditAppearance = { sheet = ProjectSheet.EditProject },
        onLoadContext = viewModel::loadContext,
        onContextUp = viewModel::contextUp,
        onOpenContextFile = viewModel::openContextFile,
        onRefresh = { viewModel.refresh() },
    )
    ProjectSectionBody(state, local, busy, actions, nowMillis = viewModel.now(), modifier = modifier)
    when (val open = sheet) {
        null -> Unit
        ProjectSheet.NewWorker -> NewWorkerSheet(root = state.root, onLaunch = { prompt, name -> viewModel.createWorker(prompt, name, repoUrl = null, baseBranch = null) }, onDismiss = { sheet = null })
        ProjectSheet.Adopt -> AdoptSheet(candidates = adoptable, onPick = viewModel::adopt, onDismiss = { sheet = null })
        ProjectSheet.Appearance -> AppearanceSheet(current = state.root?.projectAppearance, onPick = viewModel::updateAppearance, onDismiss = { sheet = null })
        ProjectSheet.EditProject -> ProjectEditorHost(graph, ProjectEditorTarget.Edit(projectId), onOpenAgent = onOpenAgent, onDismiss = { sheet = null })
        is ProjectSheet.Steer -> SteerSheet(workerName = open.name, onSteer = { text -> viewModel.steer(open.agentId, text) }, onDismiss = { sheet = null })
        is ProjectSheet.Move -> MoveSheet(workerName = open.name, projects = otherProjects, onPick = { viewModel.reparent(open.agentId, it) }, onDismiss = { sheet = null })
    }
    contextFile?.let { file -> ContextFileSheet(file, onDismiss = viewModel::closeContextFile) }
}
