package com.cursorforandroid.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.TranscriptHit
import com.cursorforandroid.domain.UpdateState
import com.cursorforandroid.notifications.NotificationPermissionPrompt
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.agents.DraftRow
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.agents.SidebarGroup
import com.cursorforandroid.ui.agents.SidebarShortLists
import com.cursorforandroid.ui.agents.sidebarGroups
import com.cursorforandroid.ui.components.CursorDrawer
import com.cursorforandroid.ui.components.rememberCursorDrawerState
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.customize.CustomizeSheet
import com.cursorforandroid.share.ShareTarget
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.hitTestBoundary
import com.cursorforandroid.ui.home.HomeScreen
import com.cursorforandroid.ui.media.MediaViewerHost
import com.cursorforandroid.ui.media.rememberMediaViewerState
import com.cursorforandroid.ui.settings.ExtendedModeUpgradeNotice
import com.cursorforandroid.ui.projects.ProjectEditorHost
import com.cursorforandroid.ui.projects.ProjectEditorTarget
import com.cursorforandroid.ui.settings.KeyboardShortcutsScreen
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.settings.UpdateCopy
import com.cursorforandroid.ui.settings.WhatsNewCopy
import com.cursorforandroid.ui.settings.WhatsNewScreen
import com.cursorforandroid.ui.share.ShareDestinationScreen
import com.cursorforandroid.ui.shortcuts.LocalChatShortcuts
import com.cursorforandroid.ui.shortcuts.LocalKeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.LocalTranscriptFocus
import com.cursorforandroid.ui.shortcuts.PaletteMode
import com.cursorforandroid.ui.shortcuts.ShortcutAction
import com.cursorforandroid.ui.shortcuts.ShortcutHandler
import com.cursorforandroid.ui.shortcuts.shortcutsBeforeIme
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
 * Same shell as the official app: the New Chat pane is home; the sidebar is a column on wide screens (a [SidebarRail],
 * collapsible with the drawer's slide) and an edge-swipe drawer on phones. Destinations live on a [NavStack] rendered
 * by [CursorNavHost].
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AppNavHost(
    graph: AppGraph,
    user: CursorUser,
    isDemo: Boolean,
    deepLinkAgentId: String?,
    onDeepLinkConsumed: () -> Unit,
    newChatRequested: Boolean = false,
    onNewChatConsumed: () -> Unit = {},
    searchRequested: Boolean = false,
    onSearchConsumed: () -> Unit = {},
) {
    // The window size class is measured from the activity; without one (a wrapped context) the phone layout stands in
    // rather than the cast bringing the app down.
    val activity = LocalContext.current.findActivity()
    val wide = if (activity == null) false else calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact
    AppShell(
        graph = graph,
        user = user,
        isDemo = isDemo,
        wide = wide,
        deepLinkAgentId = deepLinkAgentId,
        onDeepLinkConsumed = onDeepLinkConsumed,
        newChatRequested = newChatRequested,
        onNewChatConsumed = onNewChatConsumed,
        searchRequested = searchRequested,
        onSearchConsumed = onSearchConsumed,
    )
}

/** [AppNavHost] with the layout decision handed in, so a test can flip it without a configuration change. */
@Composable
internal fun AppShell(
    graph: AppGraph,
    user: CursorUser,
    isDemo: Boolean,
    wide: Boolean,
    deepLinkAgentId: String?,
    onDeepLinkConsumed: () -> Unit,
    newChatRequested: Boolean = false,
    onNewChatConsumed: () -> Unit = {},
    searchRequested: Boolean = false,
    onSearchConsumed: () -> Unit = {},
) {
    val stack = rememberSaveable(saver = NavStack.Saver) { NavStack(Screen.Home) }
    val agentsViewModel: AgentsViewModel = viewModel(factory = AgentsViewModel.Factory(graph))
    val listState by agentsViewModel.uiState.collectAsStateWithLifecycle()
    val topScreen = stack.top.screen
    val selectedAgentId = (topScreen as? Screen.Agent)?.id
    val drawerState = rememberCursorDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var customizeOpen by remember { mutableStateOf(false) }
    // The Project editor, opened from the Projects group's plus or a Project row's menu; kept up across a rotation.
    var projectEditor by rememberSaveable { mutableStateOf<ProjectEditorTarget?>(null) }
    // Wide layout: the sidebar collapses like on the web, and the toggle moves into the detail pane header.
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    // Where a key or the window last left the rail, beside the chat or away (see byKey), until anything else moves it.
    var railKeyed by remember { mutableStateOf<Boolean?>(null) }
    // The long groups the reader listed in full, for this visit to the sidebar: not saved, cut back on leaving.
    val shortLists = remember { SidebarShortLists() }
    val colors = CursorTheme.colors
    // The activity's hardware-keyboard reader (see MainActivity.dispatchKeyEvent and the shell's pre-IME node below);
    // null where the shell is composed without one, which leaves every key to the views.
    val keyboard = LocalKeyboardShortcuts.current
    val shortcuts = remember { ShellShortcuts() }
    LaunchedEffect(selectedAgentId) { selectedAgentId?.let(shortcuts::visit) }
    val mediaViewer = rememberMediaViewerState()
    val focusManager = LocalFocusManager.current
    // How the wide window shares its width between the rail, the chat and a panel pinned beside it (see ShellPanes).
    val panes = remember { ShellPanes(graph.prefs, scope, railExpanded = { !sidebarCollapsed }, chatOnTop = { stack.top.screen is Screen.Agent }) }
    // A fold, an unfold or a turn lays the whole window out again in one frame, so the rail is where the new window has
    // it in that frame, as a key leaves it: a rail still sliding once the window has changed would drag the chat and a
    // pinned panel through widths of their own after it.
    if (panes.configure(LocalConfiguration.current.screenWidthDp.dp)) railKeyed = panes.widths.railShown
    LaunchedEffect(panes) { panes.load() }
    // Read coarse, so a drag at either edge resizes the panes without recomposing the shell.
    val railShown by remember(panes) { derivedStateOf { panes.widths.railShown } }
    val pinnable by remember(panes) { derivedStateOf { panes.widths.pinnable } }
    // The drawer on a wide window: the rail over the chat, where the window has no room for it beside the chat.
    val flyoutOpen by remember(drawerState) { derivedStateOf { drawerState.isOpen || drawerState.fraction > 0f } }

    fun closeDrawer() {
        if (!drawerState.isOpen) return
        if (shortcuts.keyed) drawerState.jumpTo(DrawerValue.Closed, scope) else scope.launch { drawerState.close() }
    }

    /**
     * A key's action, taken as the keyboard takes it everywhere on desktop: the screen it opens, the rail and the
     * drawer are where it puts them in the frame the key lands, with no slide (see [NavStack.instantly]). The rail
     * lands with whatever the key moved it by: Ctrl+B, or a panel pinned open or shut, or a screen opened, that takes
     * or gives back its room.
     */
    fun <T> byKey(action: () -> T): T = shortcuts.fromKeyboard {
        val railBefore = panes.widths.railShown
        stack.instantly(action).also { if (panes.widths.railShown != railBefore) railKeyed = panes.widths.railShown }
    }
    // Anything else that moves the rail after it, a tap or a drag, slides it again.
    val railSlides = railKeyed != railShown
    SideEffect { if (railKeyed != null && railKeyed != railShown) railKeyed = null }

    /**
     * The reader left the sidebar — a chat or a Project opened, another destination: its long groups go back to five
     * rows. A drawer still on screen is cut back once it is off it (below), so its rows do not jump while it slides.
     */
    fun leaveSidebar() {
        if (!drawerState.isOpen && drawerState.fraction == 0f) shortLists.reset()
    }

    /**
     * The wide window's sidebar asked for: the rail beside the chat where the window has room for it, and otherwise —
     * a panel pinned open where the rail and the chat would not both fit beside it — over the chat, as the phone's
     * drawer comes, until it is put away or there is room for it again.
     */
    fun revealSidebar() {
        when {
            panes.widths.railFits -> sidebarCollapsed = false
            shortcuts.keyed -> drawerState.jumpTo(DrawerValue.Open, scope)
            else -> scope.launch { drawerState.open() }
        }
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { !drawerState.isOpen && drawerState.fraction == 0f }.collect { shut -> if (shut) shortLists.reset() }
    }
    // Back, a launch that failed, anything else that changes what is on top is leaving too; and a composer a key asked
    // for that is no longer the one on screen is not to be focused once it comes back some other way.
    LaunchedEffect(stack.top) {
        leaveSidebar()
        if (shortcuts.composerFocus.let { it != null && it != stack.top.screen }) shortcuts.composerFocus = null
    }

    // The activity handles size and orientation changes itself, so unfolding a Fold, or turning a phone on its side,
    // swaps the drawer for the rail in place, and the two must agree: a drawer the user had open (or was opening)
    // carries over as the expanded rail, and the drawer is put away so that folding back meets the detail pane rather
    // than a drawer that opened on its own.
    LaunchedEffect(wide) {
        if (wide && drawerState.targetValue == DrawerValue.Open) {
            sidebarCollapsed = false
            // Where a panel pinned open leaves the rail no room, the drawer stays open over the chat as the rail would come.
            if (panes.widths.railFits) drawerState.snapTo(DrawerValue.Closed)
        }
        // Folding back puts the rail away behind a shut drawer.
        if (!wide) shortLists.reset()
    }
    // Room for the rail again — the panel put away, the window widened: the rail takes over from the drawer over the chat.
    LaunchedEffect(wide, railShown) {
        if (wide && railShown && drawerState.targetValue == DrawerValue.Open) drawerState.snapTo(DrawerValue.Closed)
    }

    // The navigation callbacks below read the stack when they run, never `topScreen` / `selectedAgentId` as they were
    // at composition. A screen can hold on to an older callback: `HomeScreen` is composed underneath a chat while a
    // back gesture reveals it, and when the pop lands its parameters compare equal (a `::localFunction` reference is
    // equal to any other reference to the same function, whatever it captured), so it is skipped and keeps the ones
    // it has. A callback that trusted its captured "a chat is on top" then swapped the New Chat root out for the new
    // chat, cleared its view models — and with them the launch in flight — and left the chat with nothing under it.
    /**
     * Opens a chat from the screen on top — a worker's row or a subagent in the transcript, the panel's Project
     * section or side chats, a link to another chat, the chat a send from the New Chat pane launched — so back returns
     * to that screen as it was left (see [NavStack.open]). A Project's row is its coordinator's chat — the Project is
     * that conversation, with its primaries, context and actions in the chat's right-side panel — so every chat,
     * Project or not, opens the same way.
     */
    fun openAgent(id: String) {
        closeDrawer()
        stack.openAgent(id)
        // Also when the chat was already on top, which changes nothing on the stack.
        leaveSidebar()
    }

    fun navigateTop(screen: Screen) {
        closeDrawer()
        stack.resetTo(screen)
        leaveSidebar()
    }

    /**
     * Switches to a chat picked from the top level: the sidebar, the New Chat pane's lists, a notification, a widget,
     * a launcher shortcut, a link from outside the app. It sits on the New Chat pane rather than on whatever was
     * showing, which it has nothing to do with; back from it is the pane, never a trail the reader already left by
     * going to the sidebar, and one reached from outside has the pane under it to go back to instead of leaving the
     * app. Picked again, a chat still on the stack comes back as it was left (see [NavStack.resetTo]).
     */
    fun switchToAgent(id: String) = navigateTop(Screen.Agent(id))

    fun openRow(row: AgentRow) = switchToAgent(row.agent.id)

    /**
     * Settings, over whatever is on top — from the sidebar's account row, the New Chat pane, the Extended mode notice:
     * a detour the reader comes back from, so back from it is the chat (or pane) they left, not the New Chat pane.
     */
    fun openSettings() {
        closeDrawer()
        stack.open(Screen.Settings)
        leaveSidebar()
    }

    /** "New chat": a fresh composer; what the composer held stays in the sidebar as a draft. */
    fun startNewChat() {
        graph.newChatDrafts.request(NewChatDrafts.Request.Fresh)
        navigateTop(Screen.Home)
    }

    /** A draft's row: the New Chat composer opens it, with everything it was written with. */
    fun openDraft(row: DraftRow) {
        graph.newChatDrafts.request(NewChatDrafts.Request.Open(row.id))
        navigateTop(Screen.Home)
    }

    /** The What's new page, over whatever is on top — Settings' row or the sidebar's card is where it was asked for. */
    fun openWhatsNew() {
        closeDrawer()
        stack.open(Screen.WhatsNew)
        leaveSidebar()
    }

    /** Settings › Keyboard shortcuts, over Settings. */
    fun openKeyboardShortcuts() {
        closeDrawer()
        stack.open(Screen.KeyboardShortcuts)
        leaveSidebar()
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
        deepLinkAgentId?.let { id ->
            // A notification, a widget's row, a launcher shortcut, a link from another app: entered from outside, the
            // chat gets the New Chat pane under it. A Project's rolled-up notification names the Project: that is its
            // coordinator's chat, like any other id.
            switchToAgent(id)
            onDeepLinkConsumed()
        }
    }
    // The widget's "+": a fresh New Chat pane, as the sidebar's "+" reaches it.
    LaunchedEffect(newChatRequested) {
        if (newChatRequested) {
            startNewChat()
            onNewChatConsumed()
        }
    }
    LaunchedEffect(Unit) { graph.newChatDrafts.load() }
    val draftsState by graph.newChatDrafts.state.collectAsStateWithLifecycle()
    val openDraftId by graph.newChatDrafts.open.collectAsStateWithLifecycle()
    // The draft open in the New Chat pane is the one being written while the pane is on screen; left, it is listed.
    val draftRows = remember(draftsState, openDraftId, topScreen) { DraftRow.listed(draftsState.drafts, open = openDraftId.takeIf { topScreen == Screen.Home }) }
    // The widget's search button: the list surface with the sidebar's search field open. Counted rather than
    // flagged so the sidebar sees a second request after the first was closed.
    var searchRequests by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(searchRequested) {
        if (searchRequested) {
            navigateTop(Screen.Home)
            if (!wide) drawerState.open() else revealSidebar()
            searchRequests++
            onSearchConsumed()
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
        drawerState.fraction > 0f ||
        (wide && railShown)
    LifecycleStartEffect(listOnScreen) {
        val polling = if (listOnScreen) agentsViewModel.pollWhileVisible() else null
        onStopOrDispose { polling?.cancel() }
    }
    NotificationPermissionPrompt(graph = graph, hasRunningAgents = listState.runningCount > 0)

    // Extended mode: the account's rename is offered, and the sidebar footer says the mode is on. Until the setting
    // has been read the shell assumes the default, which only ever hides what the setting would allow.
    val extendedMode by graph.extendedMode.enabled.collectAsStateWithLifecycle(initialValue = false)
    val extendedNoticePending by graph.extendedMode.noticePending.collectAsStateWithLifecycle(initialValue = false)
    // Null until read, so a pane set to Projects never shows the recents for a frame first.
    val newChatHome by graph.prefs.newChatHome.collectAsStateWithLifecycle(initialValue = null)
    if (extendedNoticePending && !isDemo) {
        ExtendedModeUpgradeNotice(
            onOpenSettings = {
                scope.launch { graph.extendedMode.dismissNotice() }
                openSettings()
            },
            onDismiss = { scope.launch { graph.extendedMode.dismissNotice() } },
        )
    }

    val rowActions = AgentRowActions(
        onOpen = { row ->
            agentsViewModel.markRead(row.agent)
            openRow(row)
        },
        onTogglePin = { agentsViewModel.togglePinned(it.agent.id) },
        onArchive = { agentsViewModel.archive(it.agent.id) },
        onUnarchive = { agentsViewModel.unarchive(it.agent.id) },
        // The public API has no rename; the demo renames its in-memory row, Extended mode the account's.
        onRename = if (isDemo || extendedMode) ({ row, name -> agentsViewModel.rename(row.agent.id, name) }) else null,
        onSnooze = { row, until -> agentsViewModel.snooze(row.agent.id, until) },
        onUnsnooze = { agentsViewModel.unsnooze(it.agent.id) },
        // Projects are the account's: their editor, like the rename, is offered with Extended mode (the demo edits its own rows).
        onEditProject = if (isDemo || extendedMode) ({ row -> projectEditor = ProjectEditorTarget.Edit(row.agent.id) }) else null,
    )
    val destination = when (topScreen) {
        Screen.Home -> SidebarDestination.NewChat
        Screen.Settings -> SidebarDestination.Settings
        Screen.WhatsNew, Screen.KeyboardShortcuts, is Screen.Agent -> null
    }
    val updateState by graph.updates.state.collectAsStateWithLifecycle()
    val updateHint = when (val s = updateState) {
        is UpdateState.Available -> if (s.signatureMismatch) null else UpdateCopy.available(s.release)
        is UpdateState.Downloaded -> "Update ready to install · ${s.release.versionName}"
        is UpdateState.Installing -> if (s.awaitingConfirmation) "Update waiting for your confirmation" else null
        else -> null
    }
    // The installed version's notes, until the page has been opened once; the Settings row reads the same flow.
    val whatsNewUnread by graph.whatsNew.unread.collectAsStateWithLifecycle(initialValue = null)
    val whatsNewHint = whatsNewUnread?.let { WhatsNewCopy.title(it.versionName) }

    @Composable
    fun sidebar(inDrawer: Boolean, modifier: Modifier = Modifier) {
        Sidebar(
            state = listState,
            user = user,
            isDemo = isDemo,
            selectedAgentId = selectedAgentId,
            selectedDestination = destination,
            updateHint = updateHint,
            whatsNewHint = whatsNewHint,
            extendedMode = extendedMode && !isDemo,
            onQueryChange = agentsViewModel::setQuery,
            drafts = draftRows,
            shortLists = shortLists,
            searchRequests = searchRequests,
            callbacks = SidebarCallbacks(
                onNewChat = ::startNewChat,
                onSettings = ::openSettings,
                onWhatsNew = ::openWhatsNew,
                onCustomize = { customizeOpen = true },
                onToggleSidebar = if (inDrawer) ({ closeDrawer() }) else ({ sidebarCollapsed = true; shortLists.reset() }),
                onRefresh = agentsViewModel::refresh,
                rowActions = rowActions,
                onLoadMore = { agentsViewModel.loadMore() },
                onNewProject = if (isDemo || extendedMode) ({ closeDrawer(); projectEditor = ProjectEditorTarget.Create }) else null,
                onSectionCollapsed = agentsViewModel::setSectionCollapsed,
                onVisibleRows = agentsViewModel::rowsVisible,
                onRetryLoadMore = { agentsViewModel.retryLoadMore() },
                onOpenDraft = ::openDraft,
                onDeleteDraft = { row -> scope.launch { graph.newChatDrafts.remove(row.id) } },
                onShortcutRows = { shortcuts.railRows = it },
            ),
            modifier = modifier,
            showShortcutNumbers = keyboard?.showNumbers == true,
        )
    }

    val openSidebar: (() -> Unit)? = when {
        !wide -> ({ scope.launch { drawerState.open() } })
        railShown -> null
        else -> ::revealSidebar
    }
    val onBack: (() -> Unit)? = if (wide) null else ({ stack.pop() })

    // The pane is composed under the Row on a wide window and under the drawer on a narrow one, so a rotation that
    // flips between them would tear the whole navigation stack down and build it again from nothing: its view models,
    // its saved state, its scroll positions and whatever a screen has open — the full-screen image viewer above all.
    // Moving the content instead keeps the subtree itself alive across the swap. What the screens read changes from
    // one recomposition to the next, so it is passed in rather than captured when the movable content is created.
    val detailHost = remember {
        movableContentOf<Modifier, DetailPane> { modifier, pane ->
            CursorNavHost(stack = stack, modifier = modifier, backEnabled = pane.backEnabled) { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(
                        graph = graph,
                        listState = pane.listState,
                        onOpenSidebar = pane.openSidebar,
                        onOpenAgent = pane.onOpenAgent,
                        onLaunchOpen = ::openAgent,
                        rowActions = pane.rowActions,
                        home = pane.newChatHome,
                        projectsAvailable = pane.projectsAvailable,
                        onNewProject = pane.onNewProject,
                        onOpenSettings = ::openSettings,
                        onReorderProjects = pane.onReorderProjects,
                        focusComposer = pane.composerFocus == screen,
                        onComposerFocused = pane.onComposerFocused,
                    )
                    Screen.Settings -> SettingsScreen(
                        graph = graph,
                        user = pane.user,
                        isDemo = pane.isDemo,
                        onOpenSidebar = pane.openSidebar,
                        onBack = pane.onBack,
                        onOpenWhatsNew = ::openWhatsNew,
                        newChatList = pane.listState,
                        onOpenKeyboardShortcuts = ::openKeyboardShortcuts,
                    )
                    // A page of its own under Settings (or the sidebar's card); back is the stack's in either layout.
                    Screen.WhatsNew -> WhatsNewScreen(graph = graph, onBack = { stack.pop() })
                    Screen.KeyboardShortcuts -> KeyboardShortcutsScreen(onBack = { stack.pop() })
                    is Screen.Agent -> ConversationScreen(
                        graph = graph,
                        agentId = screen.id,
                        onOpenSidebar = if (pane.wide) pane.openSidebar else null,
                        onBack = pane.onBack,
                        onOpenAgent = ::openAgent,
                        focusComposer = pane.composerFocus == screen,
                        onComposerFocused = pane.onComposerFocused,
                    )
                }
            }
        }
    }
    val pane = DetailPane(
        listState = listState,
        user = user,
        isDemo = isDemo,
        wide = wide,
        openSidebar = openSidebar,
        onBack = onBack,
        onOpenAgent = rowActions.onOpen,
        rowActions = rowActions,
        backEnabled = !drawerState.isOpen,
        newChatHome = newChatHome,
        projectsAvailable = isDemo || extendedMode,
        onNewProject = if (isDemo || extendedMode) ({ projectEditor = ProjectEditorTarget.Create }) else null,
        onReorderProjects = { ids -> agentsViewModel.setProjectOrder(ids) },
        composerFocus = shortcuts.composerFocus,
        onComposerFocused = { shortcuts.composerFocus = null },
    )

    /**
     * A chat picked from the keyboard — the palette, the quick switcher, Ctrl+1 … Ctrl+0 — is a pick from the top
     * level, and opens as its sidebar row does ([switchToAgent]), marked read on the way; with a transcript [hit],
     * scrolled to it once its rows are in; with [focusComposer] (a switch, rather than a search), with the caret in
     * its composer, ready for the next message as on desktop.
     */
    fun openFromKeyboard(agentId: String, hit: TranscriptHit? = null, focusComposer: Boolean = false) {
        shortcuts.palette.close()
        if (hit != null) shortcuts.transcriptFocus.request(agentId, hit)
        val agent = listState.allAgents.firstOrNull { it.id == agentId }
        if (agent != null) rowActions.onOpen(AgentListOrganizer.toRow(agent, listState.local, listState.nowMillis)) else switchToAgent(agentId)
        if (focusComposer) shortcuts.composerFocus = Screen.Agent(agentId)
    }

    fun chatOnTop() = (stack.top.screen as? Screen.Agent)?.let { shortcuts.chats.target(it.id) }

    fun toggleSidebar() {
        when {
            !wide -> drawerState.jumpTo(if (drawerState.targetValue == DrawerValue.Open) DrawerValue.Closed else DrawerValue.Open, scope)
            drawerState.targetValue == DrawerValue.Open -> closeDrawer()
            railShown -> {
                sidebarCollapsed = true
                shortLists.reset()
            }
            else -> revealSidebar()
        }
    }

    fun shortcut(action: ShortcutAction): Boolean {
        val palette = shortcuts.palette
        when (action) {
            ShortcutAction.Search -> palette.openSearch()
            ShortcutAction.SwitchNext, ShortcutAction.SwitchPrevious -> shortcuts.stepSwitcher(
                forward = action == ShortcutAction.SwitchNext,
                current = (stack.top.screen as? Screen.Agent)?.id,
            ) { ShellShortcuts.entries(listState, archived = false) }
            ShortcutAction.ToggleSidebar -> toggleSidebar()
            ShortcutAction.TogglePanel -> return chatOnTop()?.togglePanel() == true
            is ShortcutAction.OpenRailItem -> {
                // A collapsed rail is not composed, so has told nothing: its rows are what it would show on opening.
                val rows = if (wide && !railShown && !flyoutOpen) {
                    val current = (stack.top.screen as? Screen.Agent)?.id
                    SidebarGroup.numbered(sidebarGroups(listState, listState.query, emptyList(), current, shortLists))
                } else {
                    shortcuts.railRows
                }
                rows.getOrNull(action.position)?.let { openFromKeyboard(it.agent.id, focusComposer = true) }
            }
            ShortcutAction.NewChat -> {
                palette.close()
                startNewChat()
                shortcuts.composerFocus = Screen.Home
            }
            ShortcutAction.NewProject -> {
                if (!isDemo && !extendedMode) return false
                palette.close()
                closeDrawer()
                projectEditor = ProjectEditorTarget.Create
            }
            ShortcutAction.OpenSettings -> {
                palette.close()
                openSettings()
            }
            ShortcutAction.ShowShortcuts -> if (palette.mode == PaletteMode.Shortcuts) palette.close() else palette.openShortcuts()
            ShortcutAction.CatchUp -> {
                val chat = chatOnTop() ?: return false
                palette.close()
                chat.catchUp()
            }
            ShortcutAction.ReloadTranscript -> {
                val chat = chatOnTop() ?: return false
                palette.close()
                chat.reloadTranscript()
            }
        }
        return true
    }

    fun escape() {
        when {
            shortcuts.palette.isOpen -> shortcuts.palette.close()
            mediaViewer.isOpen -> mediaViewer.closeNow()
            drawerState.isOpen -> closeDrawer()
            chatOnTop()?.escape() == true -> Unit
            else -> focusManager.clearFocus()
        }
    }

    // Built afresh each composition, so it reads the layout and the list as they are now; the stack it reads when a
    // key comes, like the navigation callbacks above.
    val shortcutHandler = object : ShortcutHandler {
        override fun onShortcut(action: ShortcutAction): Boolean = byKey { shortcut(action) }

        // Always the app's: the platform's fallback turns an Esc no one took into Back, which would pop the chat.
        override fun onEscape(): Boolean {
            byKey { escape() }
            return true
        }

        override fun onCtrlReleased(commit: Boolean) = byKey {
            shortcuts.releaseSwitcher(commit)?.let { openFromKeyboard(it.agentId, focusComposer = true) }
            Unit
        }
    }
    SideEffect { keyboard?.handler = shortcutHandler }
    DisposableEffect(keyboard) {
        onDispose { keyboard?.handler = null }
    }

    val shareOffer by graph.share.offer.collectAsStateWithLifecycle()
    val shareLoading by graph.share.loading.collectAsStateWithLifecycle()
    val pendingShare = shareOffer
    // Every layer of the shell under one node that reads the keys before the IME does: with a field focused anywhere
    // in it — the composer, the palette's, the sidebar's search — the keyboard app is handed the key after the shell.
    Box(Modifier.fillMaxSize().shortcutsBeforeIme(keyboard)) {
        CompositionLocalProvider(LocalChatShortcuts provides shortcuts.chats, LocalTranscriptFocus provides shortcuts.transcriptFocus) {
            // The media viewer is a layer over the whole shell — sidebar, chat and panel alike, in either layout — so
            // a figure opens over all of it, and the open viewer rides out the swap between the layouts like the pane.
            MediaViewerHost(state = mediaViewer, loader = graph.media, saves = graph.mediaSaves) {
                if (wide) {
                    // The drawer here is the rail come over the chat, where a panel pinned open leaves it no room
                    // beside the chat: asked for by its button or Ctrl+B, never dragged out, and composed only while
                    // it shows.
                    CursorDrawer(
                        state = drawerState,
                        drawerWidth = if (flyoutOpen) panes.flyoutWidth else CursorDimens.sidebarWidth,
                        gesturesEnabled = flyoutOpen,
                        containerColor = colors.sidebar,
                        contentColor = colors.textPrimary,
                        drawerContent = { if (flyoutOpen) sidebar(inDrawer = true, modifier = Modifier.fillMaxSize()) },
                    ) {
                        WidePanes(
                            panes = panes,
                            railShown = railShown,
                            railSlides = railSlides,
                            pinnable = pinnable,
                            rail = { sidebar(inDrawer = false, modifier = Modifier.fillMaxSize()) },
                            detail = { modifier -> detailHost(modifier, pane) },
                        )
                    }
                } else {
                    CursorDrawer(
                        state = drawerState,
                        drawerWidth = CursorDimens.sidebarWidth,
                        containerColor = colors.sidebar,
                        contentColor = colors.textPrimary,
                        drawerContent = { sidebar(inDrawer = true, modifier = Modifier.fillMaxSize()) },
                    ) {
                        detailHost(Modifier.fillMaxSize(), pane)
                    }
                }
            }
        }
        PaletteHost(shortcuts, listState, graph.transcriptSearch) { entry, hit -> byKey { openFromKeyboard(entry.agentId, hit) } }

        if (customizeOpen) {
            CustomizeSheet(viewModel = agentsViewModel, onDismiss = { customizeOpen = false })
        }
        projectEditor?.let { target ->
            // Asked for from the sidebar or the New Chat pane: a Project created here is a new top-level chat.
            ProjectEditorHost(graph, target, onOpenAgent = ::switchToAgent, onDismiss = { projectEditor = null })
        }

        when {
            shareLoading -> {
                Box(Modifier.fillMaxSize().background(colors.canvas).hitTestBoundary(), contentAlignment = Alignment.Center) {
                    SpinnerRing(size = 22.dp, strokeWidth = 2.dp)
                }
            }
            pendingShare != null && pendingShare.target == null -> {
                ShareDestinationScreen(
                    listState = listState,
                    draft = pendingShare,
                    onNewChat = {
                        graph.share.setTarget(ShareTarget.NewChat)
                        navigateTop(Screen.Home)
                    },
                    onPickChat = { row ->
                        agentsViewModel.markRead(row.agent)
                        graph.share.setTarget(ShareTarget.Chat(row.agent.id))
                        switchToAgent(row.agent.id)
                    },
                    onRefresh = agentsViewModel::refresh,
                    onDismiss = graph.share::clear,
                )
            }
        }
    }
}

/** What the detail pane's screens read from the shell around them; see the movable content in [AppShell]. */
private class DetailPane(
    val listState: AgentListUiState,
    val user: CursorUser,
    val isDemo: Boolean,
    val wide: Boolean,
    val openSidebar: (() -> Unit)?,
    val onBack: (() -> Unit)?,
    val onOpenAgent: (AgentRow) -> Unit,
    val rowActions: AgentRowActions,
    /** False while the drawer is over the pane: the gesture is the drawer's to close, not the stack's to pop. */
    val backEnabled: Boolean,
    /** Settings › New chat page; null until the preference has been read. */
    val newChatHome: NewChatHome?,
    /** Projects exist to pin: Extended mode is on, or this is the demo. */
    val projectsAvailable: Boolean,
    val onNewProject: (() -> Unit)?,
    /** The Projects as arranged on the New Chat page, first to last. */
    val onReorderProjects: (List<String>) -> Unit,
    /** The screen whose composer a key asked for (see [ShellShortcuts.composerFocus]); [onComposerFocused] once it has the caret. */
    val composerFocus: Screen?,
    val onComposerFocused: () -> Unit,
)
