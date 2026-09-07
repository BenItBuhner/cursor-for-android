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
import androidx.compose.ui.graphics.RectangleShape
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

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val AGENT = "agent/{id}"
    fun agent(id: String) = "agent/$id"
}

/**
 * Same shell as the official app: the New Chat pane is home; the sidebar is a permanent 280dp column on wide
 * screens and an edge-swipe drawer on phones.
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
    val wide = calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact
    val navController = rememberNavController()
    val agentsViewModel: AgentsViewModel = viewModel(factory = AgentsViewModel.Factory(graph))
    val listState by agentsViewModel.uiState.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val selectedAgentId = backStack?.arguments?.getString("id")?.takeIf { route == Routes.AGENT }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var customizeOpen by remember { mutableStateOf(false) }
    // Wide layout: the sidebar collapses like on the web, and the toggle moves into the detail pane header.
    var sidebarCollapsed by remember { mutableStateOf(false) }
    val colors = CursorTheme.colors

    LaunchedEffect(deepLinkAgentId) {
        deepLinkAgentId?.let {
            navController.navigate(Routes.agent(it)) { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }
    NotificationPermissionPrompt(graph = graph, hasRunningAgents = listState.runningCount > 0)

    fun openAgent(id: String) {
        scope.launch { drawerState.close() }
        navController.navigate(Routes.agent(id)) {
            launchSingleTop = true
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
            if (selectedAgentId == row.agent.id) navigateTop(Routes.HOME)
        },
    )
    val destination = when (route) {
        Routes.HOME -> SidebarDestination.NewChat
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
                onNewChat = { navigateTop(Routes.HOME) },
                onSettings = { navigateTop(Routes.SETTINGS) },
                onCustomize = { customizeOpen = true },
                onToggleSidebar = if (inDrawer) ({ scope.launch { drawerState.close() } }) else ({ sidebarCollapsed = true }),
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

    @Composable
    fun detailHost(modifier: Modifier) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = modifier,
            enterTransition = { slideIn() },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { slideOutPop() },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    graph = graph,
                    listState = listState,
                    onOpenSidebar = openSidebar,
                    onOpenAgent = rowActions.onOpen,
                    onLaunched = { agent -> openAgent(agent.id) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(graph = graph, user = user, isDemo = isDemo, onOpenSidebar = openSidebar, onBack = if (wide) null else ({ navController.popBackStack() }))
            }
            composable(Routes.AGENT) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ConversationScreen(
                    graph = graph,
                    agentId = id,
                    onBack = if (wide) null else ({ navController.popBackStack() }),
                    onDeleted = { navigateTop(Routes.HOME) },
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

private fun AnimatedContentTransitionScope<*>.slideIn() =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(240)) + fadeIn(tween(180))

private fun AnimatedContentTransitionScope<*>.slideOutPop() =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(240)) + fadeOut(tween(180))
