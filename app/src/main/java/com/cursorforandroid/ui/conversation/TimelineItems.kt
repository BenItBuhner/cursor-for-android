package com.cursorforandroid.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.DateHeader
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentsCard
import com.cursorforandroid.domain.SummaryRow
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolActivity
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorChip
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

@Composable
fun TimelineItemView(item: TimelineItem, modifier: Modifier = Modifier) {
    when (item) {
        is DateHeader -> DateHeaderView(item, modifier)
        is UserMessage -> UserBubble(item, modifier)
        is AssistantMessage -> MarkdownText(item.markdown, modifier.fillMaxWidth())
        is ThinkingBlock -> ThinkingView(item, modifier)
        is SummaryRow -> SummaryRowView(item.label, item.value, modifier)
        is ToolActivity -> ToolActivityView(item, modifier)
        is SubagentsCard -> SubagentsView(item, modifier)
        is NoticeCard -> NoticeView(item, modifier)
        is RunFooter -> RunFooterView(item, modifier)
    }
}

@Composable
private fun DateHeaderView(item: DateHeader, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Text(item.label, style = CursorTheme.typography.caption, color = CursorTheme.colors.textPlaceholder)
    }
}

/** Right-aligned user prompt: 7% base fill (#232323 on the canvas), radius 16, ≤ 88% width. */
@Composable
private fun UserBubble(item: UserMessage, modifier: Modifier) {
    val colors = CursorTheme.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.88f)
                .background(colors.surfaceRaised, CursorTheme.shapes.bubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MarkdownText(item.text, style = CursorTheme.typography.message, color = colors.textPrimary)
        }
    }
}

@Composable
fun SummaryRowView(label: String, value: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val colors = CursorTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CursorTheme.typography.secondary, color = colors.textSecondary)
        Spacer(Modifier.width(8.dp))
        Text(value, style = CursorTheme.typography.secondary, color = colors.textPlaceholder, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
private fun ThinkingView(item: ThinkingBlock, modifier: Modifier) {
    val colors = CursorTheme.colors
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.pressable({ expanded = !expanded }, CursorTheme.shapes.sm).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (item.isStreaming) "Thinking" else "Thought", style = CursorTheme.typography.secondary, color = colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            if (item.isStreaming) {
                SpinnerRing(size = 12.dp)
            } else {
                Text(item.durationSeconds?.let { "${it}s" } ?: "", style = CursorTheme.typography.secondary, color = colors.textPlaceholder)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ExpandMore, null, tint = colors.textPlaceholder, modifier = Modifier.size(14.dp).rotate(if (expanded) 180f else 0f))
        }
        AnimatedVisibility(visible = expanded || item.isStreaming) {
            Text(
                item.text.trim(),
                style = CursorTheme.typography.secondary,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }
    }
}

private fun ToolKind.icon(): ImageVector = when (this) {
    ToolKind.Read -> Icons.Outlined.Description
    ToolKind.List -> Icons.Outlined.FolderOpen
    ToolKind.Search -> Icons.Outlined.Search
    ToolKind.Edit -> Icons.Outlined.Edit
    ToolKind.Shell -> CursorIcons.Terminal
    ToolKind.Web -> Icons.Outlined.Language
    ToolKind.Task -> CursorIcons.Sparkle
    ToolKind.Mcp -> CursorIcons.Layers
    ToolKind.Other -> CursorIcons.Sparkle
}

/** "Explored 6 files, 7 searches" — tap to expand the tool-call list. */
@Composable
private fun ToolActivityView(item: ToolActivity, modifier: Modifier) {
    val colors = CursorTheme.colors
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.pressable({ expanded = !expanded }, CursorTheme.shapes.sm).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.verb, style = CursorTheme.typography.secondary, color = colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text(item.headline, style = CursorTheme.typography.secondary, color = colors.textPlaceholder)
            Spacer(Modifier.width(6.dp))
            if (item.isRunning) SpinnerRing(size = 12.dp) else Icon(Icons.Outlined.ExpandMore, null, tint = colors.textPlaceholder, modifier = Modifier.size(14.dp).rotate(if (expanded) 180f else 0f))
        }
        AnimatedVisibility(visible = expanded) {
            CursorCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                item.calls.forEachIndexed { index, call ->
                    ToolCallRow(call)
                    if (index != item.calls.lastIndex) HairlineDivider(Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolCallRow(call: ToolCall) {
    val colors = CursorTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(call.kind.icon(), null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
        Text(ToolNames.verb(call.kind, call.status), style = CursorTheme.typography.secondary, color = colors.textSecondary)
        Spacer(Modifier.width(8.dp))
        Text(
            call.summary,
            style = if (call.kind == ToolKind.Shell || call.kind == ToolKind.Read || call.kind == ToolKind.Edit || call.kind == ToolKind.List) CursorTheme.typography.code else CursorTheme.typography.secondary,
            color = if (call.kind == ToolKind.Shell || call.kind == ToolKind.Read || call.kind == ToolKind.Edit) colors.accentBlue else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (call.isRunning) SpinnerRing(size = 12.dp)
    }
}

/** The "Subagents 3" card from the iOS conversation view. */
@Composable
private fun SubagentsView(item: SubagentsCard, modifier: Modifier) {
    val colors = CursorTheme.colors
    CursorCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Subagents", style = CursorTheme.typography.secondary, color = colors.textSecondary)
            Spacer(Modifier.width(6.dp))
            Text(item.subagents.size.toString(), style = CursorTheme.typography.secondary, color = colors.textPlaceholder)
        }
        HairlineDivider()
        item.subagents.forEachIndexed { index, sub ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sub.status == "Running") {
                    SpinnerRing(size = 10.dp, color = colors.statusRunning)
                } else {
                    Box(Modifier.size(8.dp).background(colors.statusIdle, CircleShape))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(sub.title, style = CursorTheme.typography.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${sub.status} · ${sub.kind}", style = CursorTheme.typography.caption, color = colors.textPlaceholder)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = colors.textPlaceholder, modifier = Modifier.size(18.dp))
            }
            if (index != item.subagents.lastIndex) HairlineDivider(Modifier.padding(start = 34.dp))
        }
    }
}

/** "Background task completed" style card. */
@Composable
private fun NoticeView(item: NoticeCard, modifier: Modifier) {
    val colors = CursorTheme.colors
    val (icon, tint) = when (item.tone) {
        NoticeTone.Neutral -> Icons.Outlined.NotificationsNone to colors.textSecondary
        NoticeTone.Success -> Icons.Outlined.Check to colors.green
        NoticeTone.Warning -> Icons.Outlined.WarningAmber to colors.orange
        NoticeTone.Error -> Icons.Outlined.ErrorOutline to colors.danger
    }
    CursorCard(modifier.fillMaxWidth(), fill = colors.surfaceRaised.copy(alpha = if (colors.isDark) 1f else 0.6f), border = Color.Transparent) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(item.title, style = CursorTheme.typography.secondary, color = colors.textPrimary)
                item.subtitle?.let { Text(it, style = CursorTheme.typography.caption, color = colors.textPlaceholder, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** "Worked 3m 5s" plus branch / PR chips for a finished run. */
@Composable
private fun RunFooterView(item: RunFooter, modifier: Modifier) {
    val colors = CursorTheme.colors
    val uriHandler = LocalUriHandler.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val label = when (item.status) {
            RunStatus.FINISHED -> "Worked"
            RunStatus.ERROR -> "Failed after"
            RunStatus.CANCELLED -> "Cancelled after"
            RunStatus.EXPIRED -> "Expired after"
            else -> "Worked"
        }
        val duration = TimeFormat.duration(item.durationMs)
        if (duration != null || item.status != RunStatus.FINISHED) {
            SummaryRowView(label, duration ?: item.status.name.lowercase())
        }
        if (item.branches.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item.branches.forEach { b ->
                    if (b.prUrl != null) {
                        CursorChip(
                            text = b.prUrl.substringAfter("github.com/").ifBlank { "Pull request" },
                            icon = CursorIcons.GitPullRequest,
                            tint = colors.green,
                            fill = colors.green.copy(alpha = 0.14f),
                            mono = true,
                            onClick = { uriHandler.openUri(b.prUrl) },
                        )
                    } else if (b.branch != null) {
                        CursorChip(text = b.branch, icon = CursorIcons.GitBranch, mono = true, tint = colors.accentBlue)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
