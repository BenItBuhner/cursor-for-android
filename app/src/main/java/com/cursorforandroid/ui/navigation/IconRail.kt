package com.cursorforandroid.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.Avatar
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.ProjectGlyph
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/** What the icon-only rail's glyphs do; the expanded sidebar's callbacks, less what needs a row's text. */
data class IconRailCallbacks(
    val onNewChat: () -> Unit,
    /** Search needs the field: the rail expands with it. */
    val onSearch: () -> Unit,
    val onCustomize: () -> Unit,
    val onOpenRow: (AgentRow) -> Unit,
    val onSettings: () -> Unit,
    /** The rail's own toggle: to the next state (see [RailState.next]). */
    val onToggle: () -> Unit,
)

/**
 * The sidebar as a column of glyphs alone, [WindowPosture.RAIL_ICON_DP] wide: the cube, New chat, Search (which
 * expands the rail, since a search needs its field), Customize, then one glyph per Project — its icon in its
 * colour, with the unread dot — that opens the coordinator's chat, and at the foot the rail's toggle and the account
 * avatar (Settings). The rows are the ones the organizer already hands the expanded sidebar; nothing is placed here
 * that the sidebar would not place.
 */
@Composable
fun IconRail(
    state: AgentListUiState,
    user: CursorUser,
    selectedAgentId: String?,
    selectedDestination: SidebarDestination?,
    callbacks: IconRailCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val projects = state.sections.firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()
    Column(
        modifier.fillMaxSize().background(colors.sidebar).windowInsetsPadding(WindowInsets.statusBars).testTag("icon-rail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(CursorDimens.headerHeight), contentAlignment = Alignment.Center) {
            Icon(CursorIcons.Cube, "Cursor", tint = colors.iconPrimary, modifier = Modifier.size(CursorDimens.logo))
        }
        FlatIconButton(CursorIcons.Plus, "New chat", onClick = callbacks.onNewChat, tint = if (selectedDestination == SidebarDestination.NewChat) colors.iconPrimary else colors.iconSecondary)
        FlatIconButton(CursorIcons.Search, "Search chats", onClick = callbacks.onSearch)
        FlatIconButton(CursorIcons.Filter, "Filter and group chats", onClick = callbacks.onCustomize, tint = if (state.prefs.isDefault) colors.iconSecondary else colors.accent)
        if (projects.isNotEmpty()) {
            HairlineDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(vertical = 2.dp)) {
                items(projects, key = { it.agent.id }) { row -> ProjectRailGlyph(row, selected = row.agent.id == selectedAgentId, onOpen = { callbacks.onOpenRow(row) }) }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        FlatIconButton(CursorIcons.Sidebar, "Toggle sidebar", onClick = callbacks.onToggle, modifier = Modifier.testTag("icon-rail-toggle"))
        Box(
            Modifier
                .padding(bottom = 8.dp, top = 4.dp)
                .navigationBarsPadding()
                .size(40.dp)
                .background(if (selectedDestination == SidebarDestination.Settings) colors.fillSoft else Color.Transparent, CursorTheme.shapes.lg)
                .pressable(callbacks.onSettings, CursorTheme.shapes.lg)
                .semantics { contentDescription = "Account" }
                .testTag("icon-rail-account"),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(user, 26.dp)
        }
    }
}

/** One Project as its glyph in its colour, on the selection fill while its chat is open; an unread badge as the list row has it. */
@Composable
private fun ProjectRailGlyph(row: AgentRow, selected: Boolean, onOpen: () -> Unit) {
    val colors = CursorTheme.colors
    val name = row.agent.name
    Box(
        Modifier
            .padding(vertical = 1.dp)
            .size(40.dp)
            .background(if (selected) colors.fillSoft else Color.Transparent, CursorTheme.shapes.lg)
            .pressable(onOpen, CursorTheme.shapes.lg)
            .semantics {
                contentDescription = "Project $name"
                this.selected = selected
            }
            .testTag("icon-rail-project"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.scale(1.15f)) {
            ProjectGlyph(row.agent.projectAppearance, badge = if (row.indicator == AgentIndicator.Unread) colors.unreadDot else null)
        }
    }
}
