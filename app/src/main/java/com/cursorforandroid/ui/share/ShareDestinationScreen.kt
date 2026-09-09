package com.cursorforandroid.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentRowItem
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.GroupLabel
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * Picks where a share lands: New Chat (the home composer) or an existing chat (its follow-up composer). The list
 * is the sidebar's — same rows, same groups, same filters — so the chats look and behave as they do everywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareDestinationScreen(
    listState: AgentListUiState,
    draft: ShareDraft,
    onNewChat: () -> Unit,
    onPickChat: (AgentRow) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var query by rememberSaveable { mutableStateOf("") }
    // The sidebar search is its own field; this list is the Chats filters plus the picker's query.
    val sections = remember(listState.allAgents, listState.prefs, listState.local, query, listState.nowMillis) {
        AgentListOrganizer.organize(listState.allAgents, listState.prefs, listState.local, query, listState.nowMillis)
    }
    val pickActions = AgentRowActions(
        onOpen = onPickChat,
        onTogglePin = {},
        onArchive = {},
        onUnarchive = {},
        onDelete = {},
    )

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorHeader(
            title = "Add to",
            subtitle = draft.summary().ifBlank { null },
            leading = { FlatIconButton(CursorIcons.Close, "Close", onClick = onDismiss) },
        )
        SearchField(value = query, onValueChange = { query = it }, onClear = { query = "" })
        PullToRefreshBox(isRefreshing = listState.isRefreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            val scroll = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize().scrollEdgeFade(scroll).navigationBarsPadding(),
                state = scroll,
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
            ) {
                if (query.isBlank()) {
                    item("new-chat") {
                        NewChatRow(onClick = onNewChat)
                    }
                }
                if (!listState.hasLoaded && sections.isEmpty()) {
                    item("loading") {
                        Text("Loading chats…", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
                if (listState.hasLoaded && sections.isEmpty()) {
                    item("empty") {
                        Text(
                            if (query.isNotBlank()) "No chats match \"$query\"" else "No chats yet",
                            style = type.small,
                            color = colors.textQuaternary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                listState.error?.let { err ->
                    item("error") {
                        Text(err, style = type.small, color = colors.red, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                sections.forEach { section ->
                    item("hdr-${section.key}") {
                        GroupLabel(section.title, Modifier.padding(start = 16.dp, end = 16.dp).height(CursorDimens.sidebarRow + CursorDimens.sidebarRowGap))
                    }
                    items(section.rows, key = { "${section.key}:${it.agent.id}" }) { row ->
                        AgentRowItem(
                            row = row,
                            selected = false,
                            prefs = listState.prefs,
                            actions = pickActions,
                            showMenu = false,
                            modifier = Modifier.padding(vertical = CursorDimens.sidebarRowGap / 2),
                            nowMillis = listState.nowMillis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatRow(onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset)
            .clip(shape)
            .pressable(onClick, shape)
            .height(CursorDimens.sidebarRow)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Plus, null, tint = colors.iconSecondary, modifier = Modifier.size(CursorDimens.glyph))
        Spacer(Modifier.width(10.dp))
        Text("New chat", style = type.row, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.selectionInset, vertical = 4.dp)
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
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text("Search chats", style = type.base, color = colors.textQuaternary); inner() }
            },
        )
        if (value.isNotEmpty()) {
            FlatIconButton(CursorIcons.Close, "Clear search", onClick = onClear, size = 28.dp, iconSize = 14.dp)
        }
    }
}
