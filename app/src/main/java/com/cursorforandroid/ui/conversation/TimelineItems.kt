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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import com.cursorforandroid.domain.SummaryRow
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolOutput
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.agents.MenuItem
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.ShimmerText
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
    // A prompt the server has not acknowledged yet is drawn faded — through its colours, the way the rest of the app
    // fades things — and comes up to full strength once its run is filed.
    val alpha by animateFloatAsState(if (item.isPending) PendingMessageAlpha else 1f, tween(240), label = "pending")
    Box(modifier.fillMaxWidth().padding(start = 32.dp), contentAlignment = Alignment.CenterEnd) {
        MessageActions(
            text = item.text,
            enabled = hasText,
            modifier = Modifier.widthIn(min = 150.dp, max = 640.dp).cursorSurface(colors.fillFaint.faded(alpha), colors.stroke.faded(alpha), CursorTheme.shapes.xl),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (item.attachments.isNotEmpty()) {
                    MessageAttachments(item.attachments, Modifier.padding(bottom = if (hasText) 8.dp else 0.dp), alpha = alpha)
                }
                if (hasText || item.attachments.isEmpty()) {
                    MarkdownText(item.text, style = CursorTheme.typography.message, color = colors.textPrimary.faded(alpha))
                }
            }
        }
    }
}

/** [color] at [alpha] of its own opacity: 1 leaves it as it is. */
internal fun Color.faded(alpha: Float): Color = if (alpha >= 1f) this else copy(alpha = this.alpha * alpha)

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

/**
 * Cursor's collapsible step header (`Collapsible` in the desktop build): the verb in the secondary text colour, its
 * details after a 4px gap in the tertiary colour with tabular numerals, and a right-pointing chevron at the end that
 * turns down when the row is open. While the step is still going the verb shimmers — nothing spins beside it. Line
 * counts of edits ("+12 -3") are set apart in the git colours. The whole row is the toggle.
 */
@Composable
private fun DisclosureRow(
    action: String,
    details: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    busy: Boolean = false,
    lineStats: String? = null,
    expandable: Boolean = true,
) {
    val colors = CursorTheme.colors
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, tween(180), label = "chevron")
    Row(
        Modifier
            .offset(x = (-6).dp)
            .pressable(onToggle, CursorTheme.shapes.base, enabled = expandable)
            .heightIn(min = 28.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerText(action, style = CursorTheme.typography.base, color = colors.textSecondary, active = busy, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (details != null) {
            Spacer(Modifier.width(4.dp))
            // Yields to the chevron: long details ellipsize rather than pushing the chevron off the row.
            Text(
                details,
                style = CursorTheme.typography.base.copy(fontFeatureSettings = "tnum"),
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (lineStats != null) {
            Spacer(Modifier.width(4.dp))
            LineStats(lineStats)
        }
        if (expandable) {
            Spacer(Modifier.width(4.dp))
            Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
        }
    }
}

/** "+12 -3" in the git colours, tabular so the numbers hold still while a run edits. */
@Composable
private fun LineStats(stats: String, style: TextStyle = CursorTheme.typography.base) {
    val colors = CursorTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        stats.split(' ').forEach { part ->
            Text(part, style = style.copy(fontFeatureSettings = "tnum"), color = if (part.startsWith("-")) colors.gitRemoved else colors.gitAdded, maxLines = 1)
        }
    }
}

/**
 * The agent's work between two messages, laid out the way Cursor's client lays out a step group. A thought that came
 * before any tool call is its own "Thought 3s" row, open while it is being written so it can be read along, closed
 * once it is done. The tool calls that follow, with the thoughts between them, sit behind one summary row —
 * "Explored 6 files, 1 search, ran 2 commands", "Edited 2 files, explored 3 files +12 -3" — that opens onto the
 * steps in order; one or two bare reads are not worth a row of their own and stay as lines.
 */
@Composable
private fun ActivityGroupView(item: ActivityGroup, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (item.leadingThoughts.isNotEmpty()) ThoughtRow(item)
        if (item.work.isNotEmpty()) {
            if (item.isWorkGrouped) WorkRow(item) else StepList(item.work, Modifier.padding(top = if (item.leadingThoughts.isNotEmpty()) 2.dp else 0.dp))
        }
    }
}

/** "Thinking" while the thought streams — open, so it reads along — then "Thought 3s", closed onto its text. */
@Composable
private fun ThoughtRow(item: ActivityGroup) {
    var toggled by rememberSaveable(item.id) { mutableStateOf<Boolean?>(null) }
    val text = item.leadingThoughts.joinToString("\n\n") { it.text.trim() }.trim()
    val expandable = text.isNotEmpty()
    val expanded = expandable && (toggled ?: item.isLeadingThoughtStreaming)
    Column {
        DisclosureRow(
            action = item.thoughtAction,
            details = item.thoughtDetails,
            expanded = expanded,
            onToggle = { toggled = !expanded },
            busy = item.isLeadingThoughtStreaming,
            expandable = expandable,
        )
        AnimatedVisibility(visible = expanded) {
            ThoughtText(text, Modifier.padding(top = 6.dp, bottom = 4.dp))
        }
    }
}

/** The tool calls behind their summary row, opening onto each step in the order it happened. */
@Composable
private fun WorkRow(item: ActivityGroup) {
    var expanded by rememberSaveable("${item.id}-work") { mutableStateOf(false) }
    val header = item.header
    Column {
        DisclosureRow(
            action = header.action,
            details = header.details,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            busy = item.isWorkBusy,
            lineStats = header.lineStats,
        )
        AnimatedVisibility(visible = expanded) {
            StepList(item.work, Modifier.padding(top = 8.dp, bottom = 4.dp))
        }
    }
}

/** The steps of a group as Cursor lists them: each thought as dimmed prose, each tool call as one line. */
@Composable
private fun StepList(steps: List<ActivityStep>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        steps.forEach { step ->
            when (step) {
                is ThinkingBlock -> ThoughtText(step.text, Modifier.padding(vertical = 4.dp))
                is ToolCall -> ToolCallLine(step)
            }
        }
    }
}

/** A thought as Cursor shows one: the prose at half strength (`.markdown-normalized { opacity: .5 }`). */
@Composable
private fun ThoughtText(text: String, modifier: Modifier = Modifier) {
    Text(text.trim(), style = CursorTheme.typography.base, color = CursorTheme.colors.textTertiary, modifier = modifier)
}

/**
 * One tool call as one line, Cursor's `ui-tool-call-line`: the verb in the secondary colour — shimmering while the call
 * runs — then its details in the tertiary colour, ellipsized to the row. An MCP call reads "Ran list_pull_requests in
 * Github", the tool's name as strong as the verb, behind the plug glyph that stands in for the server's icon. An edit
 * carries its "+12 -3". A tap opens the line onto what the call was asked and what came back: the command and its
 * output, the MCP input and result, the full path or query.
 */
@Composable
private fun ToolCallLine(call: ToolCall, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val output = remember(call) { ToolOutput.of(call) }
    val expandable = !output.isEmpty
    var expanded by rememberSaveable(call.callId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .offset(x = (-6).dp)
                .pressable({ expanded = !expanded }, CursorTheme.shapes.base, enabled = expandable)
                .heightIn(min = 24.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (call.kind == ToolKind.Mcp) {
                Icon(CursorIcons.Plug, null, tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
            }
            ShimmerText(call.action, style = type.base, color = colors.textSecondary, active = call.isRunning, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = detailsText(call)
            if (details.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    details,
                    style = type.base.copy(fontFeatureSettings = "tnum"),
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            call.lineStats?.let { stats ->
                Spacer(Modifier.width(4.dp))
                LineStats(stats)
            }
        }
        if (expandable) {
            AnimatedVisibility(visible = expanded) {
                ToolOutputView(call, output, Modifier.padding(top = 4.dp, bottom = 6.dp))
            }
        }
    }
}

/** The details of a line: for an MCP call the tool's name in the verb's colour, then "in Server" dimmed. */
@Composable
private fun detailsText(call: ToolCall): AnnotatedString {
    val colors = CursorTheme.colors
    val details = call.details
    if (call.kind != ToolKind.Mcp || call.isError || details.isEmpty()) return AnnotatedString(details)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = colors.textSecondary)) { append(details) }
        call.server?.let { append(" in $it") }
    }
}

/**
 * What a tool call opens onto, in a faint card of code: a shell call's command behind `$` and its output below, an
 * MCP call's input and result, otherwise the full path, query or URL the line abbreviated.
 */
@Composable
private fun ToolOutputView(call: ToolCall, output: ToolOutput, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorCard(modifier.fillMaxWidth(), fill = colors.fillFaint, border = Color.Transparent) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            output.input?.let { input ->
                val text = if (call.kind == ToolKind.Shell) input.trim().lines().joinToString("\n") { "\$ $it" } else input.trim()
                Text(text, style = type.code, color = colors.textPrimary)
            }
            output.output?.let { text ->
                if (output.input != null) HairlineDivider()
                Text(text, style = type.code, color = colors.textSecondary)
            }
            output.exitCode?.takeIf { it != 0 }?.let { code ->
                Text("exit code $code", style = type.small, color = colors.red)
            }
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
    val ending = when (item.status) {
        RunStatus.ERROR -> "Failed"
        RunStatus.CANCELLED -> "Cancelled"
        RunStatus.EXPIRED -> "Expired"
        else -> null
    }
    val duration = TimeFormat.duration(item.durationMs)
    // Duration and status only. The header already names the branch and owns the pull-request button; repeating
    // either as a pill under every reply is just noise. A status this build cannot read says nothing worth
    // printing either; the footer's presence is the point. The "after" belongs to the duration, so a run the
    // API returned without one is left saying just how it ended.
    when {
        duration != null -> SummaryLine(ending?.let { "$it after" } ?: "Worked", duration, modifier)
        ending != null -> SummaryLine(ending, "", modifier)
    }
}

/** Opacity of a prompt sent from here that the server has not acknowledged yet. */
private const val PendingMessageAlpha = 0.5f
