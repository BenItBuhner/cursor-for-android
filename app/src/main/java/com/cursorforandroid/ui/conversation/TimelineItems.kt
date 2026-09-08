package com.cursorforandroid.ui.conversation

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.round
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.ActivityStep
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentsCard
import com.cursorforandroid.domain.SummaryRow
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.agents.MenuItem
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
        is UserMessage -> HumanMessage(item, modifier)
        is AssistantMessage -> AssistantMessageView(item, modifier)
        is SummaryRow -> SummaryLine(item.label, item.value, modifier)
        is ActivityGroup -> ActivityGroupView(item, modifier)
        is SubagentsCard -> SubagentsView(item, modifier)
        is NoticeCard -> NoticeView(item, modifier)
        is SystemNotification -> SystemNotificationView(item, modifier)
        is RunFooter -> RunFooterView(item, modifier)
    }
}

/**
 * `.composer-human-message` from the desktop build: `align-self: flex-end`, `width: fit-content`,
 * `min-width: 150px`, `background: input.background` (4 %), `border: 1px solid stroke-secondary` (12 %),
 * radius xl, padding 8px 10px, inset 32px from the opposite edge, 14/22 text. Attached images sit above the text,
 * as they do on the web. Press and hold the bubble for its actions; an image-only prompt has no text to copy.
 */
@Composable
private fun HumanMessage(item: UserMessage, modifier: Modifier) {
    val colors = CursorTheme.colors
    val hasText = item.text.isNotBlank()
    Box(modifier.fillMaxWidth().padding(start = 32.dp), contentAlignment = Alignment.CenterEnd) {
        MessageActions(
            text = item.text,
            enabled = hasText,
            modifier = Modifier.widthIn(min = 150.dp, max = 640.dp).cursorSurface(colors.fillFaint, colors.stroke, CursorTheme.shapes.xl),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (item.attachments.isNotEmpty()) {
                    MessageAttachments(item.attachments, Modifier.padding(bottom = if (hasText) 8.dp else 0.dp))
                }
                if (hasText || item.attachments.isEmpty()) {
                    MarkdownText(item.text, style = CursorTheme.typography.message, color = colors.textPrimary)
                }
            }
        }
    }
}

/**
 * A reply has no surface of its own, so its press highlight is a soft `lg` card reaching a few dp past the text on
 * every side. The margin is a [bleed] paired with an equal padding, which leaves the text laid out exactly as wide as
 * it is without the highlight.
 */
@Composable
private fun AssistantMessageView(item: AssistantMessage, modifier: Modifier) {
    MessageActions(
        text = item.markdown,
        modifier = modifier.fillMaxWidth().bleed(horizontal = 6.dp, vertical = 4.dp).clip(CursorTheme.shapes.lg),
    ) {
        MarkdownText(item.markdown, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), streaming = item.isStreaming)
    }
}

/**
 * A turn Cursor injected rather than the user: one row in the trace's own voice — "Subagent completed · Contacts and
 * clipping", "Goal continued · In the Verity photoreal engine…" — with the glyph of what it is and the tone of how it
 * went. A tap opens it onto the part worth reading (the subagent's report, the goal's objective) as markdown in a
 * faint card, when there is more of it than the row shows; press and hold copies the notification as injected,
 * markup and all, like any other message.
 */
@Composable
private fun SystemNotificationView(item: SystemNotification, modifier: Modifier) {
    val colors = CursorTheme.colors
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    // A one-line notification is said in full by the row unless the row had to cut it short.
    var summaryCut by remember(item.summary) { mutableStateOf(false) }
    val body = item.body?.takeIf { it != item.summary || summaryCut }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "chevron")
    val icon = when (item.kind) {
        SystemNotification.Kind.Goal -> CursorIcons.Target
        SystemNotification.Kind.Subagent -> CursorIcons.Sparkle
        SystemNotification.Kind.Task -> CursorIcons.Terminal
        SystemNotification.Kind.Other -> CursorIcons.Bell
    }
    val tint = when (item.tone) {
        NoticeTone.Neutral -> colors.iconTertiary
        NoticeTone.Success -> colors.green.copy(alpha = 0.8f)
        NoticeTone.Warning -> colors.orange
        NoticeTone.Error -> colors.red
    }
    Column(modifier.fillMaxWidth()) {
        MessageActions(
            text = item.raw,
            onClick = { if (body != null) expanded = !expanded },
            modifier = Modifier.offset(x = (-6).dp).clip(CursorTheme.shapes.base),
        ) {
            Row(Modifier.heightIn(min = 28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Text(item.title, style = CursorTheme.typography.base, color = colors.textTertiary)
                item.summary?.let { summary ->
                    Spacer(Modifier.width(6.dp))
                    // Yields to the chevron: a long summary ellipsizes rather than pushing the chevron off the row.
                    Text(
                        summary,
                        style = CursorTheme.typography.base,
                        color = colors.textQuaternary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { summaryCut = it.hasVisualOverflow },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (body != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
                }
            }
        }
        if (body != null) {
            AnimatedVisibility(visible = expanded) {
                CursorCard(Modifier.fillMaxWidth().padding(top = 6.dp), fill = colors.fillFaint, border = Color.Transparent) {
                    MarkdownText(body, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = CursorTheme.typography.base, color = colors.textSecondary)
                }
            }
        }
    }
}

/**
 * Press and hold on a message: a haptic tick, then a context menu at the finger with "Copy message" (the raw text or
 * markdown, so it pastes back into a prompt or an editor as written). A plain tap runs [onClick], which is nothing
 * for a message, and links, images and code blocks inside keep their own gestures. The caller clips [modifier] to the
 * shape the highlight should take.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageActions(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = CursorTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }
    var pressedAt by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Press) pressedAt = it.pressPosition.round() }
    }
    Box(
        modifier.combinedClickable(
            interactionSource = interaction,
            indication = ripple(color = colors.base),
            enabled = enabled,
            onLongClickLabel = "Message actions",
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menuOpen = true
            },
            onClick = onClick,
        ),
    ) {
        content()
        // A zero-size anchor at the press point, so the menu opens under the finger rather than below a tall reply.
        Box(Modifier.offset { pressedAt }) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                MenuItem("Copy message", CursorIcons.Copy) {
                    menuOpen = false
                    clipboard.setText(AnnotatedString(text))
                    // Android 13+ confirms clipboard writes with its own overlay; earlier versions show nothing.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/**
 * A negative margin: the node is laid out [horizontal] / [vertical] smaller than its content on each side, so what is
 * inside can be drawn (and pressed) a little past the bounds the parent allotted without being laid out any narrower.
 */
private fun Modifier.bleed(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val dx = horizontal.roundToPx()
    val dy = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * dx, vertical = 2 * dy))
    layout(placeable.width - 2 * dx, placeable.height - 2 * dy) { placeable.place(-dx, -dy) }
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
            // Yields to the chevron: a long value ellipsizes rather than pushing the chevron off the row.
            Text(
                value,
                style = CursorTheme.typography.base,
                color = colors.textQuaternary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.width(4.dp))
        if (busy) SpinnerRing(size = 11.dp) else Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
    }
}

/**
 * The agent's work between two messages behind one row — "Explored 6 files, 1 search · thought for 7s", with a spinner
 * in place of the chevron while it is still going — that opens onto the trace in the order it happened: each thought
 * as prose, each run of tool calls as a card. A thought being written is read along as it streams even while the row
 * is closed, as it was when thoughts had rows of their own.
 */
@Composable
private fun ActivityGroupView(item: ActivityGroup, modifier: Modifier) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        DisclosureRow(label = item.verb, value = item.detail, expanded = expanded, onToggle = { expanded = !expanded }, busy = item.isBusy)
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.segments().forEach { segment ->
                    when (val step = segment.first()) {
                        is ThinkingBlock -> ThoughtText(step.text)
                        is ToolCall -> ToolCallsCard(segment.filterIsInstance<ToolCall>())
                    }
                }
            }
        }
        // The last thought is the one streaming; it stays the last while this fades out once it has closed.
        AnimatedVisibility(visible = !expanded && item.isThinking) {
            ThoughtText(item.thoughts.lastOrNull()?.text.orEmpty(), Modifier.padding(top = 4.dp, bottom = 2.dp))
        }
    }
}

/** The steps in display order: each thought on its own, consecutive tool calls together so they share a card. */
private fun ActivityGroup.segments(): List<List<ActivityStep>> = buildList<MutableList<ActivityStep>> {
    steps.forEach { step ->
        val open = lastOrNull()
        if (step is ToolCall && open != null && open.last() is ToolCall) open += step else add(mutableListOf(step))
    }
}

@Composable
private fun ThoughtText(text: String, modifier: Modifier = Modifier) {
    Text(text.trim(), style = CursorTheme.typography.base, color = CursorTheme.colors.textTertiary, modifier = modifier.padding(horizontal = 2.dp))
}

@Composable
private fun ToolCallsCard(calls: List<ToolCall>) {
    CursorCard(Modifier.fillMaxWidth()) {
        calls.forEachIndexed { index, call ->
            ToolCallRow(call)
            if (index != calls.lastIndex) HairlineDivider(Modifier.padding(horizontal = 10.dp))
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
                            onClick = { uriHandler.openUri(b.prUrl) },
                        )
                    } else if (b.branch != null) {
                        Pill(text = b.branch, icon = CursorIcons.GitBranch)
                    }
                }
            }
        }
    }
}
