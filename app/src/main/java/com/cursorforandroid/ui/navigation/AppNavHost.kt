package com.cursorforandroid.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.UpdateState
import com.cursorforandroid.notifications.NotificationPermissionPrompt
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.customize.CustomizeSheet
import com.cursorforandroid.ui.home.HomeScreen
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/** The hosting activity through any number of wrappers (a themed context, a display context); null outside one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Same shell as the official app: the New Chat pane is home; the sidebar is a permanent column on wide screens and
 * an edge-swipe drawer on phones. Destinations live on a [NavStack] rendered by [CursorNavHost].
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    graph: AppGraph,
    user: CursorUser,
    isDemo: Boolean,
    deepLinkAgentId: String?,
    onDeepLinkConsumed: () -> Unit,
    newChatRequested: Boolean = false,
    onNewChatConsumed: () -> Unit = {},
) {
    // The window size class is measured from the activity; without one (a wrapped context) the phone layout stands in
    // rather than the cast bringing the app down.
    val activity = LocalContext.current.findActivity()
    val wide = if (activity == null) false else calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact
    val stack = rememberSaveable(saver = NavStack.Saver) { NavStack(Screen.Home) }
    val agentsViewModel: AgentsViewModel = viewModel(factory = AgentsViewModel.Factory(graph))
    val listState by agentsViewModel.uiState.collectAsStateWithLifecycle()
    val topScreen = stack.top.screen
    val selectedAgentId = (topScreen as? Screen.Agent)?.id
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var customizeOpen by remember { mutableStateOf(false) }
    // Wide layout: the sidebar collapses like on the web, and the toggle moves into the detail pane header.
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    val colors = CursorTheme.colors

    fun closeDrawer() {
        if (drawerState.isOpen) scope.launch { drawerState.close() }
    }

    // The navigation callbacks below read the stack when they run, never `topScreen` / `selectedAgentId` as they were
    // at composition. A screen can hold on to an older callback: `HomeScreen` is composed underneath a chat while a
    // back gesture reveals it, and when the pop lands its parameters compare equal (a `::localFunction` reference is
    // equal to any other reference to the same function, whatever it captured), so it is skipped and keeps the ones
    // it has. A callback that trusted its captured "a chat is on top" then swapped the New Chat root out for the new
    // chat, cleared its view models — and with them the launch in flight — and left the chat with nothing under it.
    fun openAgent(id: String) {
        closeDrawer()
        stack.openAgent(id)
    }

    fun navigateTop(screen: Screen) {
        closeDrawer()
        stack.resetTo(screen)
    }

    /** A chat opened on its launch that did not go through: back to the composer, if the user is still looking at it. */
    fun leaveFailedLaunch(agentId: String) {
        if ((stack.top.screen as? Screen.Agent)?.id == agentId) stack.pop()
    }

    // The launch outlives the composer that sent it, so the chat's fate is heard from the launcher rather than from
    // the New Chat pane: the composer takes the draft back on its own, this only leaves the chat that never was.
    LaunchedEffect(Unit) {
        graph.launcher.failures.collect { leaveFailedLaunch(it.agentId) }
    }

    LaunchedEffect(deepLinkAgentId) {
        deepLinkAgentId?.let {
            openAgent(it)
            onDeepLinkConsumed()
        }
    }
    // The widget's "+": the New Chat pane, as the sidebar's "+" reaches it.
    LaunchedEffect(newChatRequested) {
        if (newChatRequested) {
            navigateTop(Screen.Home)
            onNewChatConsumed()
        }
    }
    // Coming back to the foreground (runs that finished meanwhile would otherwise stay "Working" until a manual
    // refresh), and signing in again: the view model is activity-scoped, so its init refresh ran for the previous
    // session, whose list sign-out cleared.
    LifecycleStartEffect(Unit) {
        agentsViewModel.refreshIfStale()
        onStopOrDispose { }
    }
    // A list surface is actually on screen: the New Chat pane's recent cards, the drawer pulled open, or the
    // permanent sidebar of a wide window. Polling runs while one of them is, and not merely while the app is —
    // a chat or Settings with the drawer shut has nothing the list would keep current.
    val listOnScreen = topScreen == Screen.Home ||
        drawerState.isOpen ||
        drawerState.targetValue == DrawerValue.Open ||
        (wide && !sidebarCollapsed)
    LifecycleStartEffect(listOnScreen) {
        val polling = if (listOnScreen) agentsViewModel.pollWhileVisible() else null
        onStopOrDispose { polling?.cancel() }
    }
    NotificationPermissionPrompt(graph = graph, hasRunningAgents = listState.runningCount > 0)

    val rowActions = AgentRowActions(
        onOpen = { row -> agentsViewModel.markRead(row.agent); openAgent(row.agent.id) },
        onTogglePin = { agentsViewModel.togglePinned(it.agent.id) },
        onArchive = { agentsViewModel.archive(it.agent.id) },
        onUnarchive = { agentsViewModel.unarchive(it.agent.id) },
        onDelete = { row ->
            agentsViewModel.delete(row.agent.id)
            if ((stack.top.screen as? Screen.Agent)?.id == row.agent.id) navigateTop(Screen.Home)
        },
    )
    val destination = when (topScreen) {
        Screen.Home -> SidebarDestination.NewChat
        Screen.Settings -> SidebarDestination.Settings
        is Screen.Agent -> null
    }
    val updateState by graph.updates.state.collectAsStateWithLifecycle()
    val updateHint = when (val s = updateState) {
        is UpdateState.Available -> if (s.signatureMismatch) null else "Update available · ${s.release.versionName}"
        is UpdateState.Downloaded -> "Update ready to install · ${s.release.versionName}"
        is UpdateState.Installing -> if (s.awaitingConfirmation) "Update waiting for your confirmation" else null
        else -> null
    }

    @Composable
    fun sidebar(inDrawer: Boolean, modifier: Modifier = Modifier) {
        Sidebar(
            state = listState,
            user = user,
            isDemo = isDemo,
            selectedAgentId = selectedAgentId,
            selectedDestination = destination,
            updateHint = updateHint,
            onQueryChange = agentsViewModel::setQuery,
            callbacks = SidebarCallbacks(
                onNewChat = { navigateTop(Screen.Home) },
                onSettings = { navigateTop(Screen.Settings) },
                onCustomize = { customizeOpen = true },
                onToggleSidebar = if (inDrawer) ({ closeDrawer() }) else ({ sidebarCollapsed = true }),
                onRefresh = agentsViewModel::refresh,
                rowActions = rowActions,
            ),
            modifier = modifier,
        )
    }

    val openSidebar: (() -> Unit)? = when {
        !wide -> ({ scope.launch { drawerState.open() } })
        sidebarCollapsed -> ({ sidebarCollapsed = false })
        else -> null
    }
    val onBack: (() -> Unit)? = if (wide) null else ({ stack.pop() })

    @Composable
    fun detailHost(modifier: Modifier) {
        CursorNavHost(stack = stack, modifier = modifier) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(
                    graph = graph,
                    listState = listState,
                    onOpenSidebar = openSidebar,
                    onOpenAgent = rowActions.onOpen,
                    onLaunchOpen = ::openAgent,
                )
                Screen.Settings -> SettingsScreen(graph = graph, user = user, isDemo = isDemo, onOpenSidebar = openSidebar, onBack = onBack)
                is Screen.Agent -> ConversationScreen(
                    graph = graph,
                    agentId = screen.id,
                    onOpenSidebar = if (wide) openSidebar else null,
                    onBack = onBack,
                    onDeleted = { navigateTop(Screen.Home) },
                )
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().background(colors.canvas)) {
            if (!sidebarCollapsed) {
                sidebar(inDrawer = false, modifier = Modifier.width(CursorDimens.sidebarWidth).fillMaxHeight())
                Box(Modifier.fillMaxHeight().width(1.dp).background(colors.strokeSubtle))
            }
            detailHost(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            scrimColor = Color.Black.copy(alpha = 0.45f),
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawerState,
                    drawerContainerColor = colors.sidebar,
                    drawerContentColor = colors.textPrimary,
                    drawerShape = RectangleShape,
                    modifier = Modifier.width(CursorDimens.sidebarWidth),
                ) {
                    sidebar(inDrawer = true, modifier = Modifier.fillMaxSize())
                }
            },
        ) {
            detailHost(Modifier.fillMaxSize())
        }
    }

    if (customizeOpen) {
        CustomizeSheet(viewModel = agentsViewModel, onDismiss = { customizeOpen = false })
    }
}
