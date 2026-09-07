package com.cursorforandroid.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.Dot
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.Pill
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

@Composable
fun TimelineItemView(item: TimelineItem, modifier: Modifier = Modifier) {
    when (item) {
        is DateHeader -> DateHeaderView(item, modifier)
        is UserMessage -> HumanMessage(item, modifier)
        is AssistantMessage -> MarkdownText(item.markdown, modifier.fillMaxWidth())
        is ThinkingBlock -> ThinkingView(item, modifier)
        is SummaryRow -> SummaryLine(item.label, item.value, modifier)
        is ToolActivity -> ToolActivityView(item, modifier)
        is SubagentsCard -> SubagentsView(item, modifier)
        is NoticeCard -> NoticeView(item, modifier)
        is RunFooter -> RunFooterView(item, modifier)
    }
}

/** Timestamp above a prompt: 11sp at 36 %, aligned with the message. */
@Composable
private fun DateHeaderView(item: DateHeader, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
        Text(item.label, style = CursorTheme.typography.tiny, color = CursorTheme.colors.textQuaternary)
    }
}

/**
 * `.composer-human-message` from the desktop build: `align-self: flex-end`, `width: fit-content`,
 * `min-width: 150px`, `background: input.background` (4 %), `border: 1px solid stroke-secondary` (12 %),
 * radius xl, padding 8px 10px, inset 32px from the opposite edge, 14/22 text.
 */
@Composable
private fun HumanMessage(item: UserMessage, modifier: Modifier) {
    val colors = CursorTheme.colors
    Box(modifier.fillMaxWidth().padding(start = 32.dp), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .widthIn(min = 150.dp, max = 640.dp)
                .cursorSurface(colors.fillFaint, colors.stroke, CursorTheme.shapes.xl)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            MarkdownText(item.text, style = CursorTheme.typography.message, color = colors.textPrimary)
        }
    }
}

/** "Worked 3m 5s" — label at 60 %, value at 36 %. */
@Composable
fun SummaryLine(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CursorTheme.typography.base, color = colors.textTertiary)
        Spacer(Modifier.width(6.dp))
        Text(value, style = CursorTheme.typography.base, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Collapsible 13sp row with a small chevron — the desktop treatment for "Thought for 1s" and "Explored …". */
@Composable
private fun DisclosureRow(
    label: String,
    value: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    busy: Boolean = false,
) {
    val colors = CursorTheme.colors
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "chevron")
    Row(
        Modifier
            .offset(x = (-6).dp)
            .pressable(onToggle, CursorTheme.shapes.base)
            .heightIn(min = 28.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CursorTheme.typography.base, color = colors.textTertiary)
        if (value != null) {
            Spacer(Modifier.width(6.dp))
            Text(value, style = CursorTheme.typography.base, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        if (busy) SpinnerRing(size = 11.dp) else Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
    }
}

@Composable
private fun ThinkingView(item: ThinkingBlock, modifier: Modifier) {
    val colors = CursorTheme.colors
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        DisclosureRow(
            label = if (item.isStreaming) "Thinking" else "Thought",
            value = if (item.isStreaming) null else item.durationSeconds?.let { "for ${it}s" },
            expanded = expanded,
            onToggle = { expanded = !expanded },
            busy = item.isStreaming,
        )
        AnimatedVisibility(visible = expanded || item.isStreaming) {
            Text(
                item.text.trim(),
                style = CursorTheme.typography.base,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 2.dp),
            )
        }
    }
}

private fun ToolKind.icon(): ImageVector = when (this) {
    ToolKind.Read -> CursorIcons.File
    ToolKind.List -> CursorIcons.Folder
    ToolKind.Search -> CursorIcons.Search
    ToolKind.Edit -> CursorIcons.Pencil
    ToolKind.Shell -> CursorIcons.Terminal
    ToolKind.Web -> CursorIcons.Globe
    ToolKind.Task -> CursorIcons.Sparkle
    ToolKind.Mcp -> CursorIcons.Layers
    ToolKind.Other -> CursorIcons.Sparkle
}

@Composable
private fun ToolActivityView(item: ToolActivity, modifier: Modifier) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        DisclosureRow(label = item.verb, value = item.headline, expanded = expanded, onToggle = { expanded = !expanded }, busy = item.isRunning)
        AnimatedVisibility(visible = expanded) {
            CursorCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                item.calls.forEachIndexed { index, call ->
                    ToolCallRow(call)
                    if (index != item.calls.lastIndex) HairlineDivider(Modifier.padding(horizontal = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolCallRow(call: ToolCall) {
    val colors = CursorTheme.colors
    val mono = call.kind == ToolKind.Shell || call.kind == ToolKind.Read || call.kind == ToolKind.Edit || call.kind == ToolKind.List
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(call.kind.icon(), null, tint = colors.iconTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(ToolNames.verb(call.kind, call.status), style = CursorTheme.typography.small, color = colors.textTertiary)
        Spacer(Modifier.width(6.dp))
        Text(
            call.summary,
            style = if (mono) CursorTheme.typography.code else CursorTheme.typography.small,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (call.isRunning) SpinnerRing(size = 11.dp)
    }
}

@Composable
private fun SubagentsView(item: SubagentsCard, modifier: Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp).height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CursorIcons.Sparkle, null, tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(7.dp))
            Text("Subagents", style = type.small, color = colors.textTertiary)
            Spacer(Modifier.width(6.dp))
            Text(item.subagents.size.toString(), style = type.small, color = colors.textQuaternary)
        }
        HairlineDivider()
        item.subagents.forEachIndexed { index, sub ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sub.status == "Running") SpinnerRing(size = 10.dp) else Dot(if (sub.status == "Done") colors.green.copy(alpha = 0.8f) else colors.iconQuaternary, size = 6.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(sub.title, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${sub.status} · ${sub.kind}", style = type.small, color = colors.textQuaternary)
                }
            }
            if (index != item.subagents.lastIndex) HairlineDivider(Modifier.padding(start = 28.dp))
        }
    }
}

@Composable
private fun NoticeView(item: NoticeCard, modifier: Modifier) {
    val colors = CursorTheme.colors
    val (icon, tint) = when (item.tone) {
        NoticeTone.Neutral -> CursorIcons.Bell to colors.iconTertiary
        NoticeTone.Success -> CursorIcons.Check to colors.green
        NoticeTone.Warning -> CursorIcons.Warning to colors.orange
        NoticeTone.Error -> CursorIcons.Warning to colors.red
    }
    CursorCard(modifier.fillMaxWidth(), fill = colors.fillFaint, border = Color.Transparent) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(item.title, style = CursorTheme.typography.base, color = colors.textSecondary)
                item.subtitle?.let { Text(it, style = CursorTheme.typography.small, color = colors.textQuaternary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun RunFooterView(item: RunFooter, modifier: Modifier) {
    val colors = CursorTheme.colors
    val uriHandler = LocalUriHandler.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val label = when (item.status) {
            RunStatus.ERROR -> "Failed after"
            RunStatus.CANCELLED -> "Cancelled after"
            RunStatus.EXPIRED -> "Expired after"
            else -> "Worked"
        }
        val duration = TimeFormat.duration(item.durationMs)
        if (duration != null || item.status != RunStatus.FINISHED) SummaryLine(label, duration ?: item.status.name.lowercase())
        if (item.branches.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                item.branches.forEach { b ->
                    if (b.prUrl != null) {
                        Pill(
                            text = b.prUrl.substringAfter("github.com/").replace("/pull/", "#").ifBlank { "Pull request" },
                            icon = CursorIcons.GitPullRequest,
                            tint = colors.gitAdded,
                            fill = colors.gitAdded.copy(alpha = 0.14f),
                            mono = true,
                            onClick = { uriHandler.openUri(b.prUrl) },
                        )
                    } else if (b.branch != null) {
                        Pill(text = b.branch, icon = CursorIcons.GitBranch, mono = true)
                    }
                }
            }
        }
    }
}
