package com.cursorforandroid.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.GroupLabel
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

enum class SidebarDestination { NewChat, Settings }

data class SidebarCallbacks(
    val onNewChat: () -> Unit,
    val onSettings: () -> Unit,
    val onCustomize: () -> Unit,
    /** Present when the sidebar is a drawer; drives the sidebar-toggle glyph top-right. */
    val onToggleSidebar: (() -> Unit)?,
    val onRefresh: () -> Unit,
    val rowActions: AgentRowActions,
)

/**
 * The Cursor sidebar as it appears on cursor.com/agents and in the desktop Agents window: cube logo, flat
 * sidebar-toggle + search + new-chat ("+") icons, a "Chats" label with the filter icon, Pinned / date groups of
 * 32dp rows, and the account footer. Surface is `--cursor-sidebar` (#181818).
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
    /** "Update available · 0.3.0" and the like; a row above the account footer that opens Settings. Null hides it. */
    updateHint: String? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var searching by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Column(modifier.fillMaxSize().background(colors.sidebar).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CursorIcons.Cube, "Cursor", tint = colors.iconPrimary, modifier = Modifier.size(CursorDimens.logo))
            Spacer(Modifier.weight(1f))
            if (callbacks.onToggleSidebar != null) {
                FlatIconButton(CursorIcons.Sidebar, "Toggle sidebar", onClick = callbacks.onToggleSidebar)
            }
            FlatIconButton(
                CursorIcons.Search,
                "Search chats",
                onClick = { searching = !searching; if (!searching) onQueryChange("") },
                tint = if (searching) colors.iconPrimary else colors.iconSecondary,
            )
            FlatIconButton(CursorIcons.Plus, "New chat", onClick = callbacks.onNewChat)
        }

        AnimatedVisibility(visible = searching, enter = expandVertically(tween(160)) + fadeIn(tween(160)), exit = shrinkVertically(tween(140)) + fadeOut(tween(100))) {
            SearchField(
                value = state.query,
                onValueChange = onQueryChange,
                onClose = { searching = false; onQueryChange("") },
                focusRequester = focusRequester,
            )
        }
        if (searching) LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Spacer(Modifier.height(4.dp))
        GroupLabel("Chats", Modifier.padding(start = 16.dp, end = 6.dp).height(32.dp)) {
            FlatIconButton(
                CursorIcons.Filter,
                "Filter and group chats",
                onClick = callbacks.onCustomize,
                tint = if (state.prefs.isDefault) colors.iconSecondary else colors.accent,
            )
        }

        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = callbacks.onRefresh, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)) {
                if (!state.hasLoaded && state.sections.isEmpty()) {
                    item("loading") { Text("Loading chats…", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                }
                if (state.hasLoaded && state.sections.isEmpty()) {
                    item("empty") {
                        Text(
                            when {
                                state.query.isNotBlank() -> "No chats match \"${state.query}\""
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
                    item("hdr-${section.key}") {
                        GroupLabel(section.title, Modifier.padding(start = 16.dp, end = 16.dp).height(CursorDimens.sidebarRow + CursorDimens.sidebarRowGap))
                    }
                    items(section.rows, key = { "${section.key}:${it.agent.id}" }) { row ->
                        AgentRowItem(
                            row = row,
                            selected = row.agent.id == selectedAgentId,
                            prefs = state.prefs,
                            actions = callbacks.rowActions,
                            modifier = Modifier.animateItem().padding(vertical = CursorDimens.sidebarRowGap / 2),
                        )
                    }
                }
            }
        }

        HairlineDivider()
        if (updateHint != null) {
            UpdateHintRow(updateHint, onClick = callbacks.onSettings)
            HairlineDivider()
        }
        AccountFooter(user, isDemo, selected = selectedDestination == SidebarDestination.Settings, onClick = callbacks.onSettings)
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
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = type.base.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text("Search chats", style = type.base, color = colors.textQuaternary); inner() }
            },
        )
        FlatIconButton(CursorIcons.Close, "Close search", onClick = onClose, size = 28.dp, iconSize = 14.dp)
    }
}

@Composable
private fun AccountFooter(user: CursorUser, isDemo: Boolean, selected: Boolean, onClick: () -> Unit) {
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
            // The API key's dashboard label is not identity, so it stays in Settings next to "Manage API keys". Skip the
            // line when there is no email or the display name already had to fall back to it.
            val subtitle = if (isDemo) "Demo" else user.email?.takeIf { it != user.displayName }
            if (subtitle != null) {
                Text(subtitle, style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FlatIconButton(CursorIcons.More, "Account", onClick = onClick)
    }
}

@Composable
fun Avatar(user: CursorUser, size: Dp = CursorDimens.avatar) {
    val colors = CursorTheme.colors
    Box(Modifier.size(size).background(colors.fillMedium, CircleShape), contentAlignment = Alignment.Center) {
        Text(user.initials, style = CursorTheme.typography.small.copy(fontWeight = FontWeight.Medium), color = colors.textPrimary)
    }
}
