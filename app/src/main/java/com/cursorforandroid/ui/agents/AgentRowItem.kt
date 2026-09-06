package com.cursorforandroid.ui.agents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.StatusIndicator
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

data class AgentRowActions(
    val onOpen: (AgentRow) -> Unit,
    val onTogglePin: (AgentRow) -> Unit,
    val onArchive: (AgentRow) -> Unit,
    val onUnarchive: (AgentRow) -> Unit,
    val onDelete: (AgentRow) -> Unit,
)

/**
 * Sidebar / list row: status indicator, title, optional workspace line, trailing branch glyph + runtime.
 * Selected rows get the 12% base fill with radius 8, exactly like the desktop sidebar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentRowItem(
    row: AgentRow,
    selected: Boolean,
    prefs: ListPreferences,
    actions: AgentRowActions,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.md
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val agent = row.agent

    val titleColor = when {
        selected || row.indicator == AgentIndicator.Unread || row.indicator == AgentIndicator.Running -> colors.textPrimary
        row.indicator == AgentIndicator.Archived -> colors.textPlaceholder
        else -> colors.textSecondary
    }

    Box(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.selected else Color.Transparent, shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(color = colors.base),
                    onClick = { actions.onOpen(row) },
                    onLongClick = { menuOpen = true },
                )
                .heightIn(min = 40.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                StatusIndicator(row.indicator)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    agent.name,
                    style = if (row.isUnread || selected) type.body.copy(fontWeight = FontWeight.Medium) else type.body,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val workspace = when {
                    !prefs.showWorkspace -> null
                    agent.envName != null && agent.envName.contains('#') -> agent.envName
                    else -> agent.repoSlug
                }
                val runtime = if (prefs.showRuntime) listOfNotNull(agent.modelDisplayName, TimeFormat.relativeShort(agent.updatedAtMillis, nowMillis)).joinToString("  ") else null
                val subtitle = listOfNotNull(workspace, runtime).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = type.caption,
                        color = colors.textPlaceholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (prefs.showBranchStatus) {
                BranchGlyph(row)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.surface) {
            val clipboard = LocalClipboardManager.current
            val uriHandler = LocalUriHandler.current
            MenuItem(if (row.isPinned) "Unpin" else "Pin", Icons.Outlined.PushPin) { menuOpen = false; actions.onTogglePin(row) }
            MenuItem("Open in browser", Icons.AutoMirrored.Outlined.OpenInNew) { menuOpen = false; uriHandler.openUri(agent.url) }
            MenuItem("Copy link", Icons.Outlined.ContentCopy) { menuOpen = false; clipboard.setText(AnnotatedString(agent.url)) }
            if (agent.isArchived) {
                MenuItem("Unarchive", Icons.Outlined.Unarchive) { menuOpen = false; actions.onUnarchive(row) }
            } else {
                MenuItem("Archive", Icons.Outlined.Archive) { menuOpen = false; actions.onArchive(row) }
            }
            MenuItem("Delete", Icons.Outlined.DeleteOutline, tint = colors.danger) { menuOpen = false; actions.onDelete(row) }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, tint: Color = CursorTheme.colors.textPrimary, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = CursorTheme.typography.body, color = tint) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

/** Trailing git state: green PR glyph, grey branch glyph, or nothing. */
@Composable
fun BranchGlyph(row: AgentRow, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val agent = row.agent
    when {
        agent.hasPullRequest -> Icon(CursorIcons.GitPullRequest, "Pull request", tint = colors.green, modifier = modifier.size(16.dp))
        agent.hasBranch -> Icon(CursorIcons.GitBranch, "Branch", tint = colors.textPlaceholder, modifier = modifier.size(16.dp))
        agent.envType == com.cursorforandroid.domain.EnvType.MACHINE -> Icon(CursorIcons.Desktop, "Self-hosted machine", tint = colors.textPlaceholder, modifier = modifier.size(16.dp))
        else -> Unit
    }
}
