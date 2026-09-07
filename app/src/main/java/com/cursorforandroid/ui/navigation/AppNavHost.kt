package com.cursorforandroid.ui.navigation

import android.app.Activity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.compose.NewAgentScreen
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.customize.CustomizeSheet
import com.cursorforandroid.ui.inbox.InboxScreen
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

object Routes {
    const val AGENTS = "agents"
    const val NEW = "new"
    const val INBOX = "inbox"
    const val SETTINGS = "settings"
    const val AGENT = "agent/{id}"
    fun agent(id: String) = "agent/$id"
}

/**
 * Adaptive shell. Compact widths: the sidebar is the home screen and also lives in a swipe-in drawer over
 * every detail screen. Medium/expanded widths: a permanent 300dp sidebar next to the detail pane, like iPad.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    graph: AppGraph,
    user: CursorUser,
    isDemo: Boolean,
    deepLinkAgentId: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val activity = LocalContext.current as Activity
    val widthClass = calculateWindowSizeClass(activity).widthSizeClass
    val wide = widthClass != WindowWidthSizeClass.Compact
    val navController = rememberNavController()
    val agentsViewModel: AgentsViewModel = viewModel(factory = AgentsViewModel.Factory(graph))
    val listState by agentsViewModel.uiState.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val selectedAgentId = backStack?.arguments?.getString("id")?.takeIf { route == Routes.AGENT }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var customizeOpen by remember { mutableStateOf(false) }
    val colors = CursorTheme.colors

    LaunchedEffect(deepLinkAgentId) {
        deepLinkAgentId?.let {
            navController.navigate(Routes.agent(it)) { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }

    fun openAgent(id: String) {
        scope.launch { drawerState.close() }
        navController.navigate(Routes.agent(id)) {
            launchSingleTop = true
            // From one agent to another: replace so back returns to the list / composer, not a chain of agents.
            if (route == Routes.AGENT) popUpTo(Routes.AGENT) { inclusive = true }
        }
    }

    fun navigateTop(target: String) {
        scope.launch { drawerState.close() }
        navController.navigate(target) {
            launchSingleTop = true
            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
        }
    }

    val rowActions = AgentRowActions(
        onOpen = { row -> agentsViewModel.markRead(row.agent); openAgent(row.agent.id) },
        onTogglePin = { agentsViewModel.togglePinned(it.agent.id) },
        onArchive = { agentsViewModel.archive(it.agent.id) },
        onUnarchive = { agentsViewModel.unarchive(it.agent.id) },
        onDelete = { row ->
            agentsViewModel.delete(row.agent.id)
            if (selectedAgentId == row.agent.id) navigateTop(if (wide) Routes.NEW else Routes.AGENTS)
        },
    )
    val destination = when (route) {
        Routes.NEW -> SidebarDestination.NewAgent
        Routes.INBOX -> SidebarDestination.Inbox
        Routes.SETTINGS -> SidebarDestination.Settings
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
            onQueryChange = agentsViewModel::setQuery,
            callbacks = SidebarCallbacks(
                onNewAgent = { navigateTop(Routes.NEW) },
                onInbox = { navigateTop(Routes.INBOX) },
                onSettings = { navigateTop(Routes.SETTINGS) },
                onCustomize = { customizeOpen = true },
                onCloseDrawer = if (inDrawer) ({ scope.launch { drawerState.close() } }) else null,
                onRefresh = agentsViewModel::refresh,
                rowActions = rowActions,
            ),
            modifier = modifier,
        )
    }

    val openSidebar: (() -> Unit)? = if (wide) null else ({ scope.launch { drawerState.open() } })
    val startDestination = if (wide) Routes.NEW else Routes.AGENTS

    @Composable
    fun detailHost(modifier: Modifier) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = modifier,
            enterTransition = { slideIn() },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = { slideOutPop() },
        ) {
            composable(Routes.AGENTS) {
                sidebar(inDrawer = false, modifier = Modifier.fillMaxSize())
            }
            composable(Routes.NEW) {
                NewAgentScreen(
                    graph = graph,
                    onOpenSidebar = openSidebar,
                    onLaunched = { agent -> openAgent(agent.id) },
                    isDemo = isDemo,
                )
            }
            composable(Routes.INBOX) {
                InboxScreen(state = listState, actions = rowActions, onOpenSidebar = openSidebar)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(graph = graph, user = user, isDemo = isDemo, onOpenSidebar = openSidebar)
            }
            composable(Routes.AGENT) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ConversationScreen(
                    graph = graph,
                    agentId = id,
                    onOpenSidebar = openSidebar,
                    onBack = if (wide) null else ({ navController.popBackStack() }),
                    onDeleted = { navigateTop(if (wide) Routes.NEW else Routes.AGENTS) },
                )
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().background(colors.canvas)) {
            sidebar(inDrawer = false, modifier = Modifier.width(CursorDimens.sidebarWidth).fillMaxHeight())
            Box(Modifier.fillMaxHeight().width(1.dp).background(colors.borderSubtle))
            detailHost(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = route != Routes.AGENTS,
            scrimColor = Color.Black.copy(alpha = 0.5f),
            drawerContent = {
                // Keep the sheet width stable so the drag anchors never collapse; only the inner content is
                // skipped on the home route, which already shows the sidebar full-screen.
                ModalDrawerSheet(
                    drawerState = drawerState,
                    drawerContainerColor = colors.surface,
                    drawerContentColor = colors.textPrimary,
                    drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                    modifier = Modifier.width(CursorDimens.sidebarWidth),
                ) {
                    if (route != Routes.AGENTS) sidebar(inDrawer = true, modifier = Modifier.fillMaxSize())
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

private fun AnimatedContentTransitionScope<*>.slideIn() =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) + fadeIn(tween(200))

private fun AnimatedContentTransitionScope<*>.slideOutPop() =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)) + fadeOut(tween(200))
