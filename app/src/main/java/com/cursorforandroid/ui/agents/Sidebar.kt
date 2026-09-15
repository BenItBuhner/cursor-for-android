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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.AgentsWindowList
import com.cursorforandroid.domain.GroupBy
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.GroupLabel
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.settings.ExtendedModeCopy
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

enum class SidebarDestination { NewChat, Settings }

data class SidebarCallbacks(
    val onNewChat: () -> Unit,
    val onSettings: () -> Unit,
    val onCustomize: () -> Unit,
    /** The toggle at the sidebar's top-left: closes the drawer on a phone, hides the column on a wide window. Null hides the button. */
    val onToggleSidebar: (() -> Unit)?,
    val onRefresh: () -> Unit,
    val rowActions: AgentRowActions,
    /** The reader reached the end of the list and the server has older agents: the next page is asked for. */
    val onLoadMore: () -> Unit = {},
    /** The web sidebar's "Automations" row; the app has no automations surface of its own, so it opens theirs. Null leaves the row out. */
    val onAutomations: (() -> Unit)? = null,
)

/**
 * The Cursor sidebar as cursor.com/agents lays it out beside a Project chat: the sidebar toggle alone at the
 * top-left, then the four navigation rows — New Chat, Search, Automations, Customize — then the Projects group,
 * the pinned chats and the date buckets (Today, Yesterday, Last 7 Days, Last 30 Days, Older, each shown even when
 * empty), and the account row at the foot with the settings gear. A chat row carries its state dot at the start and
 * its pull request's glyph at the end, as the web's do. Surface is `--cursor-sidebar` (#181818).
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
    var collapsedKeys by rememberSaveable { mutableStateOf(listOf<String>()) }
    val focusRequester = remember { FocusRequester() }

    fun setSearchQuery(value: String) {
        query = value
        onQueryChange(value)
    }
    // A process death restores [searching] and [query] together; the ViewModel starts empty and has to be told.
    LaunchedEffect(Unit) { if (searching) onQueryChange(query) }

    Column(modifier.fillMaxSize().background(colors.sidebar).windowInsetsPadding(WindowInsets.statusBars).testTag("sidebar")) {
        // The web's header is the toggle alone, at the top-left; nothing else sits on the row.
        Row(Modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (callbacks.onToggleSidebar != null) {
                FlatIconButton(CursorIcons.Sidebar, "Toggle sidebar", onClick = callbacks.onToggleSidebar)
            }
        }
        NavRow(CursorIcons.Send, "New chat", selected = selectedDestination == SidebarDestination.NewChat, onClick = callbacks.onNewChat, modifier = Modifier.testTag("nav-new-chat"))
        NavRow(CursorIcons.Search, "Search", selected = searching, onClick = { searching = !searching; if (!searching) setSearchQuery("") }, modifier = Modifier.testTag("nav-search"))
        callbacks.onAutomations?.let { NavRow(CursorIcons.Bot, "Automations", selected = false, onClick = it, modifier = Modifier.testTag("nav-automations")) }
        NavRow(CursorIcons.Sliders, "Customize", selected = false, onClick = callbacks.onCustomize, modifier = Modifier.testTag("nav-customize"))

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
                // The date buckets the web always lists, empty or not, in its order; the organizer's own groups keep
                // their places and the missing buckets are drawn as labels alone.
                val sections = withEmptyDateBuckets(state.sections, state.prefs)
                val firstDateKey = sections.firstOrNull { section -> DATE_BUCKETS.any { it.first == section.key } }?.key
                sections.forEach { section ->
                    val expanded = section.key !in collapsedKeys
                    item("hdr-${section.key}") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = CursorDimens.sidebarGroupGap).height(CursorDimens.sidebarRow + CursorDimens.sidebarRowGap), verticalAlignment = Alignment.CenterVertically) {
                            GroupLabel(
                                section.title,
                                Modifier.weight(1f),
                                expanded = expanded,
                                onToggle = if (section.rows.isEmpty()) null else ({
                                    collapsedKeys = if (expanded) collapsedKeys + section.key else collapsedKeys - section.key
                                }),
                            )
                            // The web puts the list's filter on the first date bucket's label.
                            if (section.key == firstDateKey) {
                                FlatIconButton(CursorIcons.Filter, "Filter and group chats", onClick = callbacks.onCustomize, size = 24.dp, iconSize = 14.dp, tint = if (state.prefs.isDefault) colors.iconTertiary else colors.accent)
                            }
                        }
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
                        // The web's sidebar lists a Project's row alone; its workers and side chats are the coordinator's
                        // panel's. A search still shows every match where it sits in the tree.
                        val expandedIds = if (query.isNotBlank()) section.rows.flatMap { listOf(it) + it.descendants() }.mapTo(HashSet()) { it.agent.id } else emptySet()
                        items(AgentListOrganizer.flatten(section.rows, expandedIds), key = { "${section.key}:${it.row.agent.id}" }) { (row, depth) ->
                            AgentRowItem(
                                row = row,
                                selected = row.agent.id == selectedAgentId,
                                prefs = state.prefs,
                                actions = callbacks.rowActions,
                                modifier = Modifier.animateItem().padding(vertical = CursorDimens.sidebarRowGap / 2),
                                nowMillis = state.nowMillis,
                                depth = depth,
                            )
                        }
                    }
                }
                // Past the last row, while the server has older agents: the page being fetched, or a tap away.
                if (state.hasLoaded && hasMore) {
                    item("more") { MoreAgentsRow(isLoading = isLoadingMore, onLoad = callbacks.onLoadMore) }
                }
            }
        }

        // Like the list above it, the hint sits on the fade with no rule; the accent colour sets it apart.
        if (updateHint != null) {
            UpdateHintRow(updateHint, onClick = callbacks.onSettings)
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

/** The desktop app's "Restart to update" affordance, sized to the sidebar rows; leads to the Updates card in Settings. */
@Composable
private fun UpdateHintRow(text: String, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(onClick, RectangleShape).height(CursorDimens.sidebarRow).padding(start = 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.ArrowDown, null, tint = colors.accent, modifier = Modifier.size(14.dp))
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
        FlatIconButton(CursorIcons.Gear, "Settings", onClick = onClick)
    }
}

/**
 * One of the sidebar's navigation rows — New Chat, Search, Automations, Customize — as the web draws them: the
 * glyph, the label, the selection fill on the one that names the screen on show.
 */
@Composable
private fun NavRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.base
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset, vertical = CursorDimens.sidebarRowGap / 2)
            .background(if (selected) colors.fillSoft else Color.Transparent, shape)
            .pressable(onClick, shape)
            .height(CursorDimens.sidebarRow)
            .padding(start = 8.dp, end = 10.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CursorDimens.glyph), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = CursorTheme.typography.row, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The web's date buckets, in its order, by the organizer's section keys; a bucket it produced no rows for is still listed as a label. */
internal val DATE_BUCKETS: List<Pair<String, String>> = AgentsWindowList.TimeBucket.entries.map { "date:${it.label}" to it.label }

/**
 * [sections] with the web's date buckets filled in: an empty bucket is added as a label at its place in the order
 * when the list is grouped by date. The organizer's own sections (Projects, Pinned, the buckets it filled) are kept
 * as they came, in their order.
 */
internal fun withEmptyDateBuckets(sections: List<AgentSection>, prefs: ListPreferences): List<AgentSection> {
    if (prefs.groupBy != GroupBy.Date) return sections
    val keys = sections.mapTo(HashSet()) { it.key }
    val bucketKeys = DATE_BUCKETS.map { it.first }
    if (bucketKeys.all { it in keys }) return sections
    val result = ArrayList<AgentSection>()
    // Everything before the first date bucket keeps its place; the buckets follow in the web's order.
    val firstBucket = sections.indexOfFirst { it.key in bucketKeys }
    val head = if (firstBucket < 0) sections else sections.subList(0, firstBucket)
    result += head
    val byKey = sections.associateBy { it.key }
    DATE_BUCKETS.forEach { (key, title) -> result += byKey[key] ?: AgentSection(key, title, emptyList()) }
    // Anything the organizer put after the buckets (none today) follows them.
    sections.filter { it.key !in bucketKeys && it !in head }.forEach { result += it }
    return result
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
