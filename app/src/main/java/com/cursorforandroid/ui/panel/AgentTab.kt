package com.cursorforandroid.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.ShimmerText
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.OlderTurnsRow
import com.cursorforandroid.ui.conversation.PresentedTranscript
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.conversation.workingCaption
import com.cursorforandroid.ui.media.ConversationMedia
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Another chat — a worker, a subagent, a side chat, the Project's coordinator — read beside this one, as its own tab:
 * a row with back, the chat's name and where it stands, and the way to open it as the conversation; under it the
 * chat's transcript as its own screen draws it, the newest turn at the bottom. Read-only: answering, stopping and
 * opening its files are the chat's own screen's, a tap away.
 */
@Composable
internal fun AgentTab(tab: PanelTab.Agent, state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    val graph = LocalPanelGraph.current
    val agent = state.tabAgents[tab.agentId]
    Column(modifier.fillMaxSize().testTag("agent-tab")) {
        AgentTabBar(agent, onBack = actions::back, onOpenChat = { actions.openAgentAsChat(tab.agentId) })
        if (graph == null) {
            PanelNote("The transcript opens here inside a running conversation.", Modifier.padding(vertical = 8.dp))
        } else {
            AgentTranscript(graph, tab.agentId, agent, actions)
        }
    }
}

@Composable
private fun AgentTabBar(agent: Agent?, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    TabBar(
        onBack = onBack,
        height = AgentBarHeight,
        trailing = {
            FlatIconButton(PanelIcons.ArrowUpRight, "Open chat", onClick = onOpenChat, size = 32.dp, iconSize = 15.dp, tint = colors.iconSecondary, modifier = Modifier.testTag("agent-tab-open"))
        },
    ) {
        Column(Modifier.weight(1f)) {
            Text(agent?.name ?: "Agent", style = type.baseMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("agent-tab-title"))
            agentCaption(agent)?.let { caption ->
                Text(caption, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Where the chat stands, then its repository and branch: "Running · cursor-for-android · cursor/icons-c930". */
internal fun agentCaption(agent: Agent?): String? {
    agent ?: return null
    val status = when (agent.runStatus) {
        RunStatus.CREATING -> "Starting"
        RunStatus.RUNNING -> "Running"
        RunStatus.FINISHED -> "Finished"
        RunStatus.ERROR -> "Failed"
        RunStatus.CANCELLED -> "Cancelled"
        RunStatus.EXPIRED -> "Expired"
        RunStatus.UNKNOWN, null -> null
    }
    return listOfNotNull(status, agent.repoShortName, agent.branchName).joinToString(" · ").ifBlank { null }
}

/**
 * The tab's transcript: the chat attached while the tab shows (the conversation repository counts its screens, so the
 * chat's own screen and this tab can both hold it), presented through the chat's presenter off the main thread, and
 * laid out bottom-up. Its figures resolve against its own agent, its links to agents and store files open as tabs of
 * the panel, and a worker's card opens that worker's tab.
 */
@Composable
private fun AgentTranscript(graph: AppGraph, agentId: String, agent: Agent?, actions: PanelActions) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    DisposableEffect(graph, agentId) {
        graph.conversations.attach(agentId)
        onDispose { graph.conversations.detach(agentId) }
    }
    // A chat the list has not loaded — a worker of a large Project, a subagent — is read by its id, and the list keeps it.
    LaunchedEffect(graph, agentId) { if (graph.agents.agent(agentId) == null) graph.agents.loadDetail(agentId) }
    val presenter = remember(graph, agentId) { graph.presenters.forAgent(agentId) }
    val initial = remember(presenter) {
        val now = graph.conversations.state(agentId).value
        val project = graph.agents.agent(agentId)?.looksLikeProject == true
        // A chat a screen showed before keeps its rows (see TranscriptPresenters): the first frame draws them.
        val presented = if (presenter.isWarm && now.items.isNotEmpty()) presenter.present(now.items, project || now.isProjectConversation, now.runStatus?.isActive == true || now.isStreaming) else TranscriptPresenter.Presented.EMPTY
        PresentedTranscript(now, presented)
    }
    val presentations = remember(presenter) {
        combine(graph.conversations.state(agentId), graph.agents.state.map { s -> s.agents.firstOrNull { it.id == agentId }?.looksLikeProject == true }.distinctUntilChanged()) { c, project -> c to project }
            .conflate()
            .map { (c, project) ->
                withContext(Dispatchers.Default) {
                    PresentedTranscript(c, presenter.present(c.items, coordinatorMode = project || c.isProjectConversation, runActive = c.runStatus?.isActive == true || c.isStreaming))
                }
            }
    }
    val presented by presentations.collectAsStateWithLifecycle(initial)
    val conversation = presented.state
    val rows = presented.rows
    val agentList by graph.agents.state.collectAsStateWithLifecycle()
    val agentsById = remember(agentList.agents) { agentList.agents.associateBy { it.id } }
    val models by graph.catalog.models.collectAsStateWithLifecycle()
    val placement = SubagentPlacement.of(agent?.envType)
    val controls = remember(actions, agentsById, presented.coordinatorMode, models, presented.subagents, conversation.subagentRuns, placement) {
        TranscriptControls(
            onOpenAgent = actions::openAgent,
            agentById = { id -> agentsById[id] },
            coordinatorMode = presented.coordinatorMode,
            models = models,
            subagents = presented.subagents,
            placement = placement,
            subagentActivity = graph.subagentActivity::of,
            subagentRuns = conversation.subagentRuns,
        )
    }
    val parent = LocalMarkdownMedia.current
    val latestItems = rememberUpdatedState(presented.items)
    val media = remember(parent, agentId, actions) {
        parent?.let { chat ->
            MarkdownMediaContext(
                agentId = agentId,
                loader = chat.loader,
                canReadStores = chat.canReadStores,
                onOpenStorePath = { path -> actions.openStorePath(path, agentId) { path.ownerId(agentId)?.let { actions.openUrl(StorePath.webUrl(it)) } } },
                entries = { ConversationMedia.of(latestItems.value) },
                onBeforeOpen = chat.onBeforeOpen,
                onOpenAgentLink = { link -> if (link.isDesktop) actions.openUrl(link.webUrl) else actions.openAgent(link.agentId) },
            )
        }
    }
    val listState = rememberLazyListState()
    val hasOlder = conversation.hasOlder && rows.isNotEmpty()
    // Older turns are paged in only as the reader scrolls up to them, as the chat's own screen does; at rest they are a tap away.
    LaunchedEffect(listState, hasOlder, conversation.isLoadingOlder) {
        if (!hasOlder || conversation.isLoadingOlder) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress && listState.layoutInfo.let { info -> (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - OlderPrefetchRows } }
            .first { it }
        graph.conversations.loadOlder(agentId)
    }
    val working = conversation.runStatus?.isActive == true || conversation.isStreaming
    if (rows.isEmpty()) {
        val failure = conversation.error ?: conversation.transcriptError?.let { "Couldn't load the transcript: $it" }
        val edge = Modifier.padding(top = 8.dp)
        when {
            presented === initial && conversation.items.isNotEmpty() || conversation.isLoading -> LoadingRow("Loading…", edge)
            failure != null -> FailedRow(failure, onRetry = { graph.conversations.reload(agentId) }, modifier = edge)
            else -> PanelNote(if (conversation.transcriptUnavailable) "The transcript isn't available for this chat." else "Nothing here yet.", edge.testTag("agent-transcript-empty"))
        }
        return
    }
    CompositionLocalProvider(LocalMarkdownMedia provides media, LocalTranscriptControls provides controls) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                // Not fillMaxSize: a short transcript sizes to its rows and reads from the top.
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).scrollEdgeFade(listState, reverseLayout = true).testTag("agent-transcript"),
                contentPadding = PaddingValues(start = PanelGutter, end = PanelGutter, top = 6.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Declared bottom-up. The first item is a sliver the list stays anchored on while the reader is at the
                // bottom, so a new turn lands above it in view; scrolled away, the rows on screen hold still instead.
                item(key = "end", contentType = "end") { Spacer(Modifier.height(1.dp)) }
                if (working) item(key = "working", contentType = "working") { ShimmerText(conversation.workingCaption(), style = type.base, color = colors.textTertiary) }
                items(rows.asReversed(), key = { it.key }, contentType = { it::class }) { row -> TranscriptRowView(row, Modifier.fillMaxWidth()) }
                if (hasOlder) {
                    item(key = "older", contentType = "older") {
                        OlderTurnsRow(isLoading = conversation.isLoadingOlder, onLoad = { graph.conversations.loadOlder(agentId) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** The agent tab's second row, a line taller than a file's: the chat's name over where it stands. */
private val AgentBarHeight = 48.dp

/** How near the oldest row the reader's scroll asks for the turns before it. */
private const val OlderPrefetchRows = 3
