package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.Subscriptions
import com.cursorforandroid.domain.lineStatsOf
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * What the pills above the composer say, read off what the screen already has (see `ConversationScreen`): the
 * chat's agents (a Project's workers and subagents, by the list's rows), what it is listening to (its transcript's
 * subscriptions), the size of its changes (the pull request's, else the branch diff's, else the transcript's edits)
 * and whether its desktop can be opened from here.
 */
data class ConversationPillsState(
    val agents: AgentsSummary? = null,
    val listening: Subscriptions.Listening = Subscriptions.NONE,
    val changes: ChangesSummary? = null,
    val canOpenDesktop: Boolean = false,
) {
    val isEmpty: Boolean get() = agents == null && listening.isEmpty && changes == null && !canOpenDesktop

    /** The agents a coordinator (or any chat) has under it, and where they stand. */
    data class AgentsSummary(val total: Int, val working: Int, val needsInput: Int) {
        companion object {
            /** The rows the list hangs off [agentId] as workers or subagents; null when there are none and the chat is no Project. */
            fun of(agentId: String, agents: List<Agent>, isCoordinator: Boolean): AgentsSummary? {
                val under = agents.filter { it.parent?.id == agentId && it.parent.kind != AgentParentKind.SIDE_CHAT }
                if (under.isEmpty() && !isCoordinator) return null
                return AgentsSummary(total = under.size, working = under.count { it.isRunning }, needsInput = under.count { it.hasPendingInteraction })
            }
        }
    }

    /** "+3167 −139" and how many files, from whichever source the panel's Changes section would list. */
    data class ChangesSummary(val additions: Int?, val deletions: Int?, val files: Int) {
        val lineStats: String? get() = lineStatsOf(additions, deletions)
        val isEmpty: Boolean get() = lineStats == null && files == 0
    }
}

/**
 * The row of pills cursor.com keeps above a chat's composer: `● Agents` (the dot in the workers' colour — orange
 * while one needs input, the accent while any works, grey otherwise, with the count working), `Listening N`,
 * `Changes +x −y` and `Open Desktop`. Each is a control: the first three open the panel where the fact lives, the
 * last the agent's desktop. One scrollable line, so a phone on its side keeps the composer's height.
 */
@Composable
fun ConversationPills(
    state: ConversationPillsState,
    onAgents: () -> Unit,
    onChanges: () -> Unit,
    onOpenDesktop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isEmpty) return
    val colors = CursorTheme.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("conversation-pills"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.agents?.let { agents ->
            val dot = when {
                agents.needsInput > 0 -> colors.orange
                agents.working > 0 -> colors.accent
                else -> colors.iconQuaternary
            }
            val label = if (agents.working > 0) "Agents · ${agents.working}" else "Agents"
            ComposerPill(
                label = label,
                leading = { Box(Modifier.size(7.dp).background(dot, CircleShape)) },
                onClick = onAgents,
                description = "Agents: ${agents.total} under this chat, ${agents.working} working" + if (agents.needsInput > 0) ", ${agents.needsInput} need input" else "",
                modifier = Modifier.testTag("pill-agents"),
            )
        }
        if (!state.listening.isEmpty) ListeningPill(state.listening)
        state.changes?.takeIf { !it.isEmpty }?.let { changes ->
            ComposerPill(
                label = "Changes",
                trailing = {
                    changes.additions?.let { Text("+$it", style = CursorTheme.typography.small.copy(fontFeatureSettings = "tnum"), color = colors.gitAdded, maxLines = 1) }
                    changes.deletions?.let { Text("−$it", style = CursorTheme.typography.small.copy(fontFeatureSettings = "tnum"), color = colors.gitRemoved, maxLines = 1) }
                    if (changes.lineStats == null) Text("${changes.files} ${if (changes.files == 1) "file" else "files"}", style = CursorTheme.typography.small, color = colors.textTertiary, maxLines = 1)
                },
                onClick = onChanges,
                description = "Changes" + (changes.lineStats?.let { " $it" } ?: " ${changes.files} files"),
                modifier = Modifier.testTag("pill-changes"),
            )
        }
        if (state.canOpenDesktop) {
            ComposerPill(label = "Open Desktop", onClick = onOpenDesktop, description = "Open Desktop", modifier = Modifier.testTag("pill-desktop"))
        }
    }
}

/** `Listening N`: the count in a small badge; a tap lists what is listened to. */
@Composable
private fun ListeningPill(listening: Subscriptions.Listening) {
    val colors = CursorTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        ComposerPill(
            label = "Listening",
            trailing = {
                Box(Modifier.size(16.dp).background(colors.fillMedium, CircleShape), contentAlignment = Alignment.Center) {
                    Text(listening.count.toString(), style = CursorTheme.typography.tiny, color = colors.textSecondary, maxLines = 1)
                }
            },
            onClick = { open = true },
            description = "Listening to ${listening.count} ${if (listening.count == 1) "subscription" else "subscriptions"}",
            modifier = Modifier.testTag("pill-listening"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
            listening.kinds.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind, style = CursorTheme.typography.base, color = colors.textPrimary) },
                    leadingIcon = { Icon(CursorIcons.Bell, null, tint = colors.iconSecondary, modifier = Modifier.size(14.dp)) },
                    onClick = { open = false },
                )
            }
            DropdownMenuItem(
                text = { Text("From this chat's subscribe calls", style = CursorTheme.typography.small, color = colors.textQuaternary) },
                onClick = { open = false },
            )
        }
    }
}

/** One pill: a stadium on the composer's surface with a hairline, the label in the secondary colour, a slot on either side. */
@Composable
internal fun ComposerPill(
    label: String,
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    icon: ImageVector? = null,
    tint: Color = CursorTheme.colors.textSecondary,
) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.full
    Row(
        modifier
            .height(PillHeight)
            .cursorSurface(colors.elevated, colors.strokeSubtle, shape)
            .pressable(onClick, shape, role = Role.Button)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) leading()
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(label, style = CursorTheme.typography.small, color = tint, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(0.dp))
            trailing()
        }
    }
}

private val PillHeight = 26.dp
