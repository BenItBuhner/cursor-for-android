package com.cursorforandroid.ui.projects

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.AgentListState
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.panel.sectionRow
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The Project section of a conversation's right-side panel — a Cursor Project's one surface in the app. For a
 * coordinator's chat: the Project's primaries with their live status and each one's menu, the coordinator's hands
 * (New primary, Adopt a chat, the icon and colour editor), its subagents and its shared context
 * ([projectSection]). For a primary, side chat or subagent: the coordinator's chat it belongs to, a tap away.
 * Self-contained — it owns its view model, keyed on the chat, and attaches the Project's polling only while it is
 * on screen — so the panel needs nothing beyond the graph, a way to open a chat and a way to say what an action did.
 * What it holds is composed here; it returns the section's rows, which the panel's list composes as they come on
 * screen (see `PanelSection.items`).
 */
@Composable
fun projectPanelItems(
    graph: AppGraph,
    agentId: String,
    onOpenAgent: (String) -> Unit,
    onNotify: (String) -> Unit,
): LazyListScope.() -> Unit {
    val chat by graph.rememberAgentsPick(agentId) { list ->
        val agent = list.agent(agentId)
        agent to agent?.parent?.let { p -> list.agent(p.id) }
    }
    val (agent, root) = chat
    if (agent != null && agent.looksLikeProject) return projectBodyItems(graph, agentId, onOpenAgent, onNotify)
    val parent = agent?.parent
    return {
        when {
            agent == null -> sectionRow("project-not-loaded") { NoticeRow("This chat isn't loaded yet.", icon = CursorIcons.Clock) }
            parent != null -> sectionRow("project-coordinator-link") {
                val role = when (parent.kind) {
                    AgentParentKind.PROJECT_WORKER -> "A primary of"
                    AgentParentKind.SIDE_CHAT -> "A side chat of"
                    AgentParentKind.SUBAGENT -> "A subagent of"
                }
                ActionRow(
                    CursorIcons.project(root?.projectAppearance?.icon),
                    root?.name ?: "its Project",
                    "$role this chat \u00B7 open the coordinator's chat",
                    enabled = true,
                    onClick = { onOpenAgent(parent.id) },
                    modifier = Modifier.testTag("project-coordinator-link"),
                )
            }
            else -> sectionRow("project-none") { NoticeRow("This chat isn't part of a Project.", icon = CursorIcons.Folder) }
        }
    }
}

@Composable
private fun projectBodyItems(graph: AppGraph, projectId: String, onOpenAgent: (String) -> Unit, onNotify: (String) -> Unit): LazyListScope.() -> Unit {
    val viewModel: ProjectViewModel = viewModel(key = "project-panel-$projectId", factory = ProjectViewModel.Factory(graph, projectId))
    // Read only in the returned rows and the open sheets: this composable returns a value, so a read here would
    // recompose the panel around it on every publication.
    val panel = viewModel.panel.collectAsStateWithLifecycle()
    val local by graph.prefs.localAgentState.collectAsStateWithLifecycle(initialValue = LocalAgentState())
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()
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
    val openAgent by rememberUpdatedState(onOpenAgent)
    val actions = remember(viewModel) {
        ProjectActions(
            onOpenAgent = { agent -> viewModel.markRead(agent); openAgent(agent.id) },
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
    }
    // The sheets are windows of their own, so they sit here beside the list rather than in a row that can scroll away.
    when (val open = sheet) {
        null -> Unit
        ProjectSheet.NewWorker -> NewWorkerSheet(root = panel.value.view.root, onLaunch = { prompt, name -> viewModel.createWorker(prompt, name, repoUrl = null, baseBranch = null) }, onDismiss = { sheet = null })
        ProjectSheet.Adopt -> {
            val adoptable by graph.rememberAgentsPick(viewModel) { viewModel.adoptable(it) }
            AdoptSheet(candidates = adoptable, onPick = viewModel::adopt, onDismiss = { sheet = null })
        }
        ProjectSheet.Appearance -> AppearanceSheet(current = panel.value.view.root?.projectAppearance, onPick = viewModel::updateAppearance, onDismiss = { sheet = null })
        ProjectSheet.EditProject -> ProjectEditorHost(graph, ProjectEditorTarget.Edit(projectId), onOpenAgent = onOpenAgent, onDismiss = { sheet = null })
        is ProjectSheet.Steer -> SteerSheet(workerName = open.name, onSteer = { text -> viewModel.steer(open.agentId, text) }, onDismiss = { sheet = null })
        is ProjectSheet.Move -> {
            val otherProjects by graph.rememberAgentsPick(viewModel) { viewModel.otherProjects(it) }
            MoveSheet(workerName = open.name, projects = otherProjects, onPick = { viewModel.reparent(open.agentId, it) }, onDismiss = { sheet = null })
        }
    }
    contextFile?.let { file -> ContextFileSheet(file, onDismiss = viewModel::closeContextFile) }
    val now = viewModel.now()
    val clock = produceState(now) {
        while (true) {
            delay(AgentsViewModel.CLOCK_TICK_MS)
            value = AppClock.now()
        }
    }
    // The list reads the rows' shape and nothing else, and the policy keeps a publication that leaves it as it was from
    // reaching the list at all; each row reads its own line from the panel (see projectSection).
    val shape = remember(panel) { derivedStateOf(structuralEqualityPolicy()) { ProjectShape.of(panel.value) } }
    return { projectSection(shape.value, panel, local, busy, actions, nowMillis = now, clock = clock) }
}

/**
 * What [pick] takes from the agent list, read so that its reader recomposes when that changes — not on every change
 * to the list (a rename of an unrelated chat, a status elsewhere). [keys] are what [pick] closes over.
 */
@Composable
private fun <T> AppGraph.rememberAgentsPick(vararg keys: Any?, pick: (AgentListState) -> T): State<T> {
    val picked = remember(this, *keys) { agents.state.map(pick).distinctUntilChanged().flowOn(Dispatchers.Default) }
    return picked.collectAsStateWithLifecycle(initialValue = remember(picked) { pick(agents.state.value) })
}
