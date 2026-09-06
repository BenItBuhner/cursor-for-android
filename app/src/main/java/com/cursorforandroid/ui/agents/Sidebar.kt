package com.cursorforandroid.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.components.CursorIconButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SectionLabel
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme

/** Which non-agent destination the sidebar currently highlights. */
enum class SidebarDestination { NewAgent, Inbox, Settings }

data class SidebarCallbacks(
    val onNewAgent: () -> Unit,
    val onInbox: () -> Unit,
    val onSettings: () -> Unit,
    val onCustomize: () -> Unit,
    val onCloseDrawer: (() -> Unit)?,
    val onRefresh: () -> Unit,
    val rowActions: AgentRowActions,
)

/**
 * The Cursor sidebar: Search / New Agent / Inbox, then Pinned + date/repo/status groups, account footer.
 * Level-1 surface (#181818), 300dp on wide screens, full-width when used as the phone home screen.
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
    showTopBar: Boolean = true,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var searching by rememberSaveable { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val focusRequester = remember { FocusRequester() }

    Column(modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.statusBars)) {
        if (showTopBar) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Traffic-light-style brand pill from the iOS sidebar header.
                BrandPill()
                Spacer(Modifier.weight(1f))
                CursorIconButton(CursorIcons.Filter, "Customize", onClick = callbacks.onCustomize)
                Spacer(Modifier.width(8.dp))
                if (callbacks.onCloseDrawer != null) {
                    CursorIconButton(CursorIcons.Sidebar, "Close sidebar", onClick = callbacks.onCloseDrawer)
                } else {
                    CursorIconButton(CursorIcons.Sidebar, "Sidebar", onClick = {}, filled = true, enabled = false)
                }
            }
        }

        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = callbacks.onRefresh, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                item("search") {
                    if (searching) {
                        SearchField(
                            value = state.query,
                            onValueChange = onQueryChange,
                            onClose = { searching = false; onQueryChange("") },
                            focusRequester = focusRequester,
                        )
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    } else {
                        NavRow(icon = Icons.Outlined.Search, label = "Search", selected = false) { searching = true }
                    }
                }
                item("new") { NavRow(CursorIcons.NewAgent, "New Agent", selected = selectedDestination == SidebarDestination.NewAgent, onClick = callbacks.onNewAgent) }
                item("inbox") {
                    NavRow(CursorIcons.Inbox, "Inbox", selected = selectedDestination == SidebarDestination.Inbox, badge = state.unreadCount.takeIf { it > 0 }, onClick = callbacks.onInbox)
                }
                item("spacer-top") { Spacer(Modifier.height(8.dp)) }

                if (state.hasLoaded && state.sections.isEmpty()) {
                    item("empty") { EmptyState(state) }
                }
                if (!state.hasLoaded && state.sections.isEmpty()) {
                    item("loading") {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Loading agents…", style = type.secondary, color = colors.textPlaceholder)
                        }
                    }
                }
                state.error?.let { err ->
                    item("error") {
                        Text(err, style = type.caption, color = colors.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }

                state.sections.forEach { section ->
                    val isCollapsed = collapsed[section.key] == true
                    item("hdr-${section.key}") {
                        SectionLabel(
                            section.title,
                            Modifier.padding(horizontal = 8.dp).pressable({ collapsed[section.key] = !isCollapsed }, CursorTheme.shapes.sm),
                        ) {
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                Icons.Outlined.ExpandMore, null,
                                tint = colors.textPlaceholder,
                                modifier = Modifier.size(14.dp).rotate(if (isCollapsed) -90f else 0f),
                            )
                        }
                    }
                    items(if (isCollapsed) emptyList() else section.rows, key = { "${section.key}:${it.agent.id}" }) { row ->
                        AgentRowItem(
                            row = row,
                            selected = row.agent.id == selectedAgentId,
                            prefs = state.prefs,
                            actions = callbacks.rowActions,
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item("gap-${section.key}") { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        HairlineDivider()
        AccountFooter(user, isDemo, selected = selectedDestination == SidebarDestination.Settings, onClick = callbacks.onSettings)
    }
}

@Composable
private fun BrandPill() {
    val colors = CursorTheme.colors
    Row(
        Modifier
            .background(colors.selected, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(colors.danger, colors.orange, colors.green).forEach { c ->
            Box(Modifier.size(7.dp).background(c, CircleShape))
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.md
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(if (selected) colors.selected else Color.Transparent, shape)
            .pressable(onClick, shape)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) colors.textPrimary else colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = if (selected) CursorTheme.typography.bodyMedium else CursorTheme.typography.body,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Box(Modifier.background(colors.cyan, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp)) {
                Text(badge.toString(), style = CursorTheme.typography.caption.copy(fontWeight = FontWeight.Medium), color = colors.canvas)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit, focusRequester: FocusRequester) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.md
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(colors.wash, shape)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = CursorTheme.typography.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Search agents", style = CursorTheme.typography.body, color = colors.textPlaceholder)
                    inner()
                }
            },
        )
        Icon(
            Icons.Outlined.Close, "Close search",
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp).pressable(onClose, CircleShape),
        )
    }
}

@Composable
private fun EmptyState(state: AgentListUiState) {
    val colors = CursorTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(CursorIcons.Cube, null, tint = colors.textPlaceholder, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.query.isNotBlank()) "No agents match \"${state.query}\"" else if (!state.prefs.isDefault) "No agents match your filters" else "No agents yet",
            style = CursorTheme.typography.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.query.isBlank() && state.prefs.isDefault) "Start one with New Agent." else "Try adjusting the search or filters.",
            style = CursorTheme.typography.secondary,
            color = colors.textPlaceholder,
        )
    }
}

@Composable
private fun AccountFooter(user: CursorUser, isDemo: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.selected else Color.Transparent)
            .pressable(onClick, CursorTheme.shapes.md)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = CursorTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (isDemo) "Demo mode" else user.email ?: user.apiKeyName,
                style = CursorTheme.typography.caption,
                color = colors.textPlaceholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Outlined.MoreHoriz, "Account", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun Avatar(user: CursorUser, size: androidx.compose.ui.unit.Dp = 32.dp) {
    val colors = CursorTheme.colors
    Box(Modifier.size(size).background(colors.selected, CircleShape), contentAlignment = Alignment.Center) {
        Text(user.initials, style = CursorTheme.typography.caption.copy(fontWeight = FontWeight.Medium), color = colors.textPrimary)
    }
}
