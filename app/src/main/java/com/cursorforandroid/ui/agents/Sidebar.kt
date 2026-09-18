package com.cursorforandroid.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.settings.ExtendedModeCopy
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.flow.distinctUntilChanged

enum class SidebarDestination { NewChat, Settings }

data class SidebarCallbacks(
    val onNewChat: () -> Unit,
    val onSettings: () -> Unit,
    val onCustomize: () -> Unit,
    /** Present when the sidebar is a drawer; drives the sidebar-toggle glyph top-right. */
    val onToggleSidebar: (() -> Unit)?,
    val onRefresh: () -> Unit,
    val rowActions: AgentRowActions,
    /** The reader reached the end of the list and the server has older agents: the next page is asked for. */
    val onLoadMore: () -> Unit = {},
    /** Opens the Create Project sheet from the Projects group's header; null hides the plus (default mode, where Projects are the account's). */
    val onNewProject: (() -> Unit)? = null,
    /**
     * A group's header was tapped: fold it closed ([collapsed] true) or open. The fold is the device's to remember
     * ([AgentListUiState.collapsedSections]); the sidebar reads it back from the state rather than keeping its own.
     */
    val onSectionCollapsed: (sectionKey: String, collapsed: Boolean) -> Unit = { _, _ -> },
    /** The rows on screen, by agent id, as the list scrolls: what the pull request badges are read for (see `AgentsViewModel.rowsVisible`). */
    val onVisibleRows: (List<String>) -> Unit = {},
    /** The "What's new in …" card above the account footer was tapped: opens the installed version's notes. */
    val onWhatsNew: () -> Unit = {},
)

/** Test tags for the card slot above the account footer: one card at a time, the update's or the notes'. */
object SidebarTags {
    const val UPDATE_HINT = "sidebar_update_hint"
    const val WHATS_NEW_HINT = "sidebar_whats_new_hint"
}

/**
 * The Cursor sidebar as it appears on cursor.com/agents and in the desktop Agents window: cube logo with the flat
 * new-chat ("+") + search + filter + sidebar-toggle icons in one header row (the web's separate "Chats" label is
 * folded into it), Projects / Pinned / date groups of 32dp rows, and the account footer. A chat's workers, side chats
 * and subagents sit under it as a tree, closed until its count is tapped. Each group folds closed from its header
 * (see [SidebarSectionHeader]), and stays folded across restarts. Surface is `--cursor-sidebar` (#181818).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sidebar(
    state: AgentListUiState,
    user: CursorUser,
    isDemo: Boolean,
    selectedAgentId: String?,
    selectedDestination: SidebarDestination?,
    onQueryChange: (String) -> Unit,
    callbacks: SidebarCallbacks,
    modifier: Modifier = Modifier,
    /** "Update available: v0.3.0" and the like; a row above the account footer that opens Settings. Null hides it. */
    updateHint: String? = null,
    /**
     * "What's new in 0.3.37": the same card slot, once the installed version's notes are there and unread. The update
     * takes the slot when both are due — after it lands, the new version's notes are what is new.
     */
    whatsNewHint: String? = null,
    /** Extended mode is on: the account footer says so, quietly, for as long as it is. */
    extendedMode: Boolean = false,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var searching by rememberSaveable { mutableStateOf(false) }
    // The field owns what is typed. [state.query] is the organized list's copy, computed off the main thread, and
    // feeding it back here put the cursor at the start of the box on every keystroke — the next character then
    // inserted on the left, so a search could not be typed. Closing still clears both.
    var query by rememberSaveable { mutableStateOf("") }
    // Chats whose nested chats — a Project's workers, side chats, subagents — are listed beneath them. Closed until
    // opened: a Project can have dozens of workers, and the row's count says they are there.
    var expandedParents by rememberSaveable { mutableStateOf(listOf<String>()) }
    val focusRequester = remember { FocusRequester() }

    fun setSearchQuery(value: String) {
        query = value
        onQueryChange(value)
    }
    // A process death restores [searching] and [query] together; the ViewModel starts empty and has to be told.
    LaunchedEffect(Unit) { if (searching) onQueryChange(query) }

    Column(modifier.fillMaxSize().background(colors.sidebar).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CursorIcons.Cube, "Cursor", tint = colors.iconPrimary, modifier = Modifier.size(CursorDimens.logo))
            Spacer(Modifier.weight(1f))
            FlatIconButton(CursorIcons.Plus, "New chat", onClick = callbacks.onNewChat)
            FlatIconButton(
                CursorIcons.Search,
                "Search chats",
                onClick = { searching = !searching; if (!searching) setSearchQuery("") },
                tint = if (searching) colors.iconPrimary else colors.iconSecondary,
            )
            FlatIconButton(
                CursorIcons.Filter,
                "Filter and group chats",
                onClick = callbacks.onCustomize,
                tint = if (state.prefs.isDefault) colors.iconSecondary else colors.accent,
            )
            if (callbacks.onToggleSidebar != null) {
                FlatIconButton(CursorIcons.Sidebar, "Toggle sidebar", onClick = callbacks.onToggleSidebar)
            }
        }

        AnimatedVisibility(visible = searching, enter = expandVertically(tween(160)) + fadeIn(tween(160)), exit = shrinkVertically(tween(140)) + fadeOut(tween(100))) {
            SearchField(
                value = query,
                onValueChange = ::setSearchQuery,
                onClose = { searching = false; setSearchQuery("") },
                focusRequester = focusRequester,
            )
        }
        if (searching) LaunchedEffect(Unit) { focusRequester.requestFocus() }

        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = callbacks.onRefresh, modifier = Modifier.weight(1f)) {
            // Rows dissolve at the top and bottom of the pane while more of the list sits past that edge; there is no
            // rule above the footer, the fade is what separates the two.
            val listState = rememberLazyListState()
            KeepAtTop(listState, sidebarTopKey(state))
            // The list holds the newest agents; the pages behind them are fetched as the reader nears its end. In
            // the sidebar that is the last row being within a few of the bottom, whatever filter is on: with a
            // narrow filter the loaded pages may match little, and the ones behind them are where more matches are.
            val hasMore = state.hasMore
            val isLoadingMore = state.isLoadingMore
            // The rows on screen, reported as the list settles: their keys are `<section>:<agent id>`.
            LaunchedEffect(listState) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { key -> key.contains(':') && !key.startsWith("hdr-") }?.substringAfterLast(':') } }
                    .distinctUntilChanged()
                    .collect { callbacks.onVisibleRows(it) }
            }
            LaunchedEffect(listState, hasMore, isLoadingMore) {
                if (!hasMore || isLoadingMore) return@LaunchedEffect
                snapshotFlow { listState.layoutInfo.let { info -> (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount } }
                    .collect { (lastVisible, total) -> if (total > 0 && lastVisible >= total - MoreAgentsPrefetchRows) callbacks.onLoadMore() }
            }
            LazyColumn(Modifier.fillMaxSize().scrollEdgeFade(listState), state = listState, contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)) {
                if (!state.hasLoaded && state.sections.isEmpty()) {
                    item("loading") { Text("Loading chats…", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                }
                if (state.hasLoaded && state.sections.isEmpty()) {
                    item("empty") {
                        Text(
                            when {
                                query.isNotBlank() -> "No chats match \"$query\""
                                !state.prefs.isDefault -> "No chats match the current filters"
                                else -> "No chats yet"
                            },
                            style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                state.error?.let { err ->
                    item("error") { Text(err, style = type.small, color = colors.red, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                }
                state.sections.forEach { section ->
                    // Folded groups are the device's memory, read back from the state; a search opens every group
                    // for as long as it is typed, since its matches may sit behind a fold.
                    val expanded = query.isNotBlank() || section.key !in state.collapsedSections
                    item("hdr-${section.key}") {
                        SidebarSectionHeader(
                            section = section,
                            expanded = expanded,
                            onToggle = { callbacks.onSectionCollapsed(section.key, expanded) },
                            // The composer's plus, much smaller, beside the chevron: a new Project, created the desktop's way.
                            onNewProject = callbacks.onNewProject?.takeIf { section.key == AgentListOrganizer.PROJECTS_KEY },
                        )
                    }
                    if (expanded && section.key == AgentListOrganizer.PROJECTS_KEY && !extendedMode && !isDemo) {
                        // Without the account service only a coordinator's own transcript says which chats are its
                        // workers; the rest of them look like chats of their own.
                        item("projects-notice") {
                            Text(
                                PROJECTS_DEFAULT_MODE_NOTICE,
                                style = type.small,
                                color = colors.textQuaternary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    if (expanded) {
                        // A search shows every match where it sits in the tree, so the tree is open while one is typed.
                        val expandedIds = if (query.isNotBlank()) section.rows.flatMap { listOf(it) + it.descendants() }.mapTo(HashSet()) { it.agent.id } else expandedParents.toSet()
                        items(AgentListOrganizer.flatten(section.rows, expandedIds), key = { "${section.key}:${it.row.agent.id}" }) { (row, depth) ->
                            val id = row.agent.id
                            AgentRowItem(
                                row = row,
                                selected = id == selectedAgentId,
                                prefs = state.prefs,
                                actions = callbacks.rowActions,
                                modifier = Modifier.animateItem().padding(vertical = CursorDimens.sidebarRowGap / 2),
                                nowMillis = state.nowMillis,
                                depth = depth,
                                // A Project whose chats the pages do not hold yet still shows the account's count of them.
                                childrenExpanded = if (row.children.isEmpty() && (row.memberCount ?: 0) == 0) null else id in expandedIds,
                                onToggleChildren = {
                                    expandedParents = if (id in expandedIds) expandedParents - id else expandedParents + id
                                },
                            )
                        }
                    }
                }
                // Past the last row, while the server has older agents: the page being fetched, or a tap away.
                if (state.hasLoaded && hasMore) {
                    item("more") { MoreAgentsRow(isLoading = isLoadingMore, onLoad = callbacks.onLoadMore) }
                }
                // The pull's indicator is let go once the first page is on screen; the rest of the refresh — the
                // older pages, the rows fetched by id, the account's round — says so quietly here until it settles.
                if (state.isSyncingOlder) {
                    item("syncing") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("syncing-older"), verticalAlignment = Alignment.CenterVertically) {
                            SpinnerRing()
                            Spacer(Modifier.width(8.dp))
                            Text("Still syncing older items\u2026", style = type.small, color = colors.textQuaternary)
                        }
                    }
                }
            }
        }

        // Like the list above it, the hint sits on the fade with no rule; the accent colour sets it apart. One card
        // at a time: an update to move to, else the notes of the version that was moved to.
        when {
            updateHint != null -> SidebarHintRow(CursorIcons.ArrowDown, updateHint, onClick = callbacks.onSettings, tag = SidebarTags.UPDATE_HINT)
            whatsNewHint != null -> SidebarHintRow(CursorIcons.Sparkle, whatsNewHint, onClick = callbacks.onWhatsNew, tag = SidebarTags.WHATS_NEW_HINT)
        }
        AccountFooter(user, isDemo, extendedMode, selected = selectedDestination == SidebarDestination.Settings, onClick = callbacks.onSettings)
    }
}

/**
 * Keeps a list that is at its top at its top when rows land above it. A `LazyColumn` holds the first visible row in
 * place by its key when the rows change, and the sidebar's rows land in several publishes — the pages first, then
 * the Projects group with the account's word, above "Pinned" — so a sidebar composed while the list loads (the
 * closed drawer on a phone, the permanent column on a tablet) would otherwise sit past its own Projects group after
 * a cold start, its first row still the "Pinned" header it opened on.
 *
 * Told by the keys rather than by timing: the row that leads the list is [topKey], and the row the list is anchored
 * on keeps its key whether this runs before or after the remeasure that moves it. A list anchored on the row that
 * led it before the change was at the top, and is asked to stay there ([LazyListState.requestScrollToItem] wins
 * over the anchoring at the next measure); a list anchored anywhere else was scrolled, and is left alone.
 */
@Composable
internal fun KeepAtTop(listState: LazyListState, topKey: Any?) {
    var previousTopKey by remember { mutableStateOf(topKey) }
    LaunchedEffect(topKey) {
        // The item the list is anchored on: the one at the first visible index, not the first of the visible items,
        // which can be the row above it showing through the content padding.
        val index = listState.firstVisibleItemIndex
        val anchored = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
        val wasAtTop = anchored != null && anchored == previousTopKey && listState.firstVisibleItemScrollOffset == 0
        if (wasAtTop && topKey != previousTopKey) listState.requestScrollToItem(0)
        previousTopKey = topKey
    }
}

/** The key of the row that leads the sidebar's list as it is composed below — what [KeepAtTop] watches. */
internal fun sidebarTopKey(state: AgentListUiState): String? = when {
    !state.hasLoaded && state.sections.isEmpty() -> "loading"
    state.hasLoaded && state.sections.isEmpty() -> "empty"
    state.error != null -> "error"
    else -> state.sections.firstOrNull()?.let { "hdr-${it.key}" }
}

/** The one line the Projects group carries without Extended mode: what the list can and cannot tell about workers. */
const val PROJECTS_DEFAULT_MODE_NOTICE = "Project workers appear as plain chats without Extended mode"

/** How many rows from the end of the list the reader may be before the next page of agents is asked for. */
private const val MoreAgentsPrefetchRows = 4

/** The row past the last agent while the server has older ones: "Loading more…" as a page comes, else a line that asks for one. */
@Composable
internal fun MoreAgentsRow(isLoading: Boolean, onLoad: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(if (isLoading) "loading-more-agents" else "load-more-agents")) {
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 12.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading more…", style = type.small, color = colors.textQuaternary)
            }
        } else {
            Text(
                "Load more chats",
                style = type.small,
                color = colors.textTertiary,
                modifier = Modifier.pressable(onLoad, CursorTheme.shapes.base).padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * The desktop app's "Restart to update" affordance, sized to the sidebar rows: one card in the slot above the account
 * footer, an accent glyph and line leading somewhere — the Updates card in Settings, or the What's new page.
 */
@Composable
private fun SidebarHintRow(icon: ImageVector, text: String, onClick: () -> Unit, tag: String) {
    val colors = CursorTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(onClick, RectangleShape).testTag(tag).height(CursorDimens.sidebarRow).padding(start = 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = CursorTheme.typography.small, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit, focusRequester: FocusRequester) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    // Selection lives here. The String BasicTextField rebuilds a TextFieldValue from [value] on every
    // recomposition and loses the cursor; the list above this row recomposes often enough that that was every key.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset, vertical = 2.dp)
            .background(colors.fillFaint, shape)
            .border(CursorDimens.hairline, colors.strokeSubtle, shape)
            .height(CursorDimens.sidebarRow)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Search, null, tint = colors.iconTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != value) onValueChange(it.text)
            },
            singleLine = true,
            textStyle = type.base.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            decorationBox = { inner ->
                Box { if (field.text.isEmpty()) Text("Search chats", style = type.base, color = colors.textQuaternary); inner() }
            },
        )
        FlatIconButton(CursorIcons.Close, "Close search", onClick = onClose, size = 28.dp, iconSize = 14.dp)
    }
}

@Composable
private fun AccountFooter(user: CursorUser, isDemo: Boolean, extendedMode: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.fillSoft else Color.Transparent)
            .pressable(onClick, RectangleShape)
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user, CursorDimens.avatar)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = type.rowMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The second line is for the two states worth being reminded of: the demo, and Extended mode (the
            // persistent indicator that undocumented endpoints are in use). Email lives in Settings; it is not
            // identity here.
            when {
                isDemo -> Text("Demo", style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                extendedMode -> Text(ExtendedModeCopy.INDICATOR, style = type.small, color = colors.orange, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FlatIconButton(CursorIcons.More, "Account", onClick = onClick)
    }
}

@Composable
fun Avatar(user: CursorUser, size: Dp = CursorDimens.avatar) {
    val colors = CursorTheme.colors
    val loader = LocalMediaLoader.current
    val url = user.profilePictureUrl
    val px = with(LocalDensity.current) { size.roundToPx() }
    // The picture, once it has been fetched (cached by URL, so a second sidebar or screen shows it at once); the
    // initials underneath stand in until then, and stay when there is no picture or it cannot be loaded.
    val picture by produceState<ImageBitmap?>(initialValue = null, url, loader, px) {
        value = if (url == null || loader == null) null else runCatching { loader.image(MediaRef.Remote(url), px, px).asImageBitmap() }.getOrNull()
    }
    // The circle is drawn as before (pixel for pixel, for the screenshots); only the picture is clipped to it.
    Box(Modifier.size(size).background(colors.fillMedium, CircleShape), contentAlignment = Alignment.Center) {
        Text(user.initials, style = CursorTheme.typography.small.copy(fontWeight = FontWeight.Medium), color = colors.textPrimary)
        picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape)) }
    }
}

/** How [Avatar] fetches profile pictures; null (the default, and the screenshot tests' case) leaves the initials. */
val LocalMediaLoader = staticCompositionLocalOf<MediaLoader?> { null }
