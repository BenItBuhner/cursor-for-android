package com.cursorforandroid.ui.agents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.StateGlyph
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat

data class AgentRowActions(
    val onOpen: (AgentRow) -> Unit,
    val onTogglePin: (AgentRow) -> Unit,
    val onArchive: (AgentRow) -> Unit,
    val onUnarchive: (AgentRow) -> Unit,
    val onDelete: (AgentRow) -> Unit,
)

/**
 * Sidebar row in the web's proportions: selection is a 6 % fill inset from both edges with radius 6, the state glyph
 * sits in a fixed slot so titles align whether or not a row has one, trailing metadata is at 36 %.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentRowItem(
    row: AgentRow,
    selected: Boolean,
    prefs: ListPreferences,
    actions: AgentRowActions,
    modifier: Modifier = Modifier,
    nowMillis: Long = AppClock.now(),
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val agent = row.agent

    Box(modifier.fillMaxWidth().padding(horizontal = CursorDimens.selectionInset)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.fillSoft else Color.Transparent, shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(color = colors.base),
                    onClick = { actions.onOpen(row) },
                    onLongClick = { menuOpen = true },
                )
                .height(CursorDimens.sidebarRow)
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateGlyph(row.indicator, hasBranch = agent.hasBranch, hasPullRequest = agent.hasPullRequest)
            Spacer(Modifier.width(10.dp))
            Text(
                agent.name,
                style = type.row,
                color = if (agent.isArchived) colors.textTertiary else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val trailing = buildList {
                if (prefs.showWorkspace) agent.repoShortName?.let { add(it) }
                if (prefs.showRuntime) add(TimeFormat.relativeShort(agent.updatedAtMillis, nowMillis))
            }
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(trailing.joinToString(" · "), style = type.base, color = colors.textQuaternary, maxLines = 1)
            }
            if (prefs.showBranchStatus && agent.envType == EnvType.MACHINE) {
                Spacer(Modifier.width(8.dp))
                Icon(CursorIcons.Desktop, "Self-hosted machine", tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
            val clipboard = LocalClipboardManager.current
            val uriHandler = LocalUriHandler.current
            MenuItem(if (row.isPinned) "Unpin" else "Pin", CursorIcons.Pin) { menuOpen = false; actions.onTogglePin(row) }
            MenuItem("Open on cursor.com", CursorIcons.ExternalLink) { menuOpen = false; uriHandler.openUri(agent.url) }
            MenuItem("Copy link", CursorIcons.Copy) { menuOpen = false; clipboard.setText(AnnotatedString(agent.url)) }
            if (agent.isArchived) {
                MenuItem("Unarchive", CursorIcons.Archive) { menuOpen = false; actions.onUnarchive(row) }
            } else {
                MenuItem("Archive", CursorIcons.Archive) { menuOpen = false; actions.onArchive(row) }
            }
            MenuItem("Delete", CursorIcons.Trash, tint = colors.red) { menuOpen = false; actions.onDelete(row) }
        }
    }
}

/** Context-menu row: 13sp label with a 16px glyph at 66 %, tall enough to tap without care. */
@Composable
internal fun MenuItem(label: String, icon: ImageVector, tint: Color = CursorTheme.colors.textPrimary, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = CursorTheme.typography.base, color = tint) },
        leadingIcon = { Icon(icon, null, tint = if (tint == CursorTheme.colors.textPrimary) CursorTheme.colors.iconSecondary else tint, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
        contentPadding = PaddingValues(start = 12.dp, end = 20.dp),
        modifier = Modifier.height(40.dp),
    )
}
