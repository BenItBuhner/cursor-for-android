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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.EventLine
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * A turn Cursor injected — a subagent's report, a subscribed pull request's change, a timer, a goal continued — as
 * one line: the kind's glyph, the subject, what happened to it, who did it, how long ago ("#64 · opened ·
 * BenItBuhner   2h"; "Hand & Arm Renders · completed   3h"). [count] above one says the same notice came that many
 * times in a row ("#12 · synchronize · cursor[bot] ×2"). Tapping opens the report, or the reply the agent folded
 * under the row; a worker's or subagent's row also opens that agent's chat.
 */
@Composable
internal fun EventRow(item: SystemNotification, count: Int = 1, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val line = remember(item) { EventLine.of(item) }
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    // A one-line notification is said in full by the row unless the row had to cut its subject, or the detail beside it, short.
    var subjectCut by remember(item.summary) { mutableStateOf(false) }
    var tailCut by remember(item.summary) { mutableStateOf(false) }
    val report = item.body?.takeIf { it != item.summary || subjectCut || tailCut }
    // The agent's brief remark on the notice, folded under the row in a coordinator's chat (see CoordinatorTranscript).
    val narration = item.narration?.trim()?.takeIf { it.isNotEmpty() }
    val body = report ?: narration
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "chevron")
    val tint = when (item.tone) {
        NoticeTone.Neutral -> colors.iconTertiary
        NoticeTone.Success -> colors.green.copy(alpha = 0.8f)
        NoticeTone.Warning -> colors.orange
        NoticeTone.Error -> colors.red
    }
    // A worker's (or a cloud subagent's) report names the agent it came from: the row can take the reader there.
    val controls = LocalTranscriptControls.current
    val openAgent = item.agentId?.let { id -> controls.onOpenAgent?.let { handler -> { handler(id) } } }
    val onClick: (() -> Unit)? = when {
        body != null -> ({ expanded = !expanded })
        openAgent != null -> openAgent
        else -> null
    }
    Column(modifier.fillMaxWidth().testTag("event-row")) {
        MessageActions(
            text = item.raw,
            onClick = onClick,
            modifier = Modifier.offset(x = (-6).dp).clip(CursorTheme.shapes.base),
        ) {
            Row(Modifier.heightIn(min = 28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(glyph(line.source), null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                // The subject and what follows it share the line: a long title yields to its verb, a long objective to
                // its event, and neither pushes the count, the time or the chevron off the row.
                SubjectAndTail(
                    modifier = Modifier.weight(1f, fill = false),
                    subject = {
                        Text(
                            line.subject,
                            style = type.base,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { subjectCut = it.hasVisualOverflow },
                        )
                    },
                    tail = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            line.verb?.let { verb ->
                                Text(" \u00B7 $verb", style = type.base, color = colors.textTertiary, maxLines = 1)
                            }
                            line.actor?.let { actor ->
                                Text(
                                    " \u00B7 $actor",
                                    style = type.base,
                                    color = colors.textQuaternary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { tailCut = it.hasVisualOverflow },
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    },
                )
                if (count > 1) {
                    Spacer(Modifier.width(5.dp))
                    Text("\u00D7$count", style = type.small.copy(fontFeatureSettings = "tnum"), color = colors.textTertiary, modifier = Modifier.testTag("event-count"))
                }
                item.timestampMillis?.let { at ->
                    Spacer(Modifier.width(8.dp))
                    Text(TimeFormat.relativeShort(at), style = type.small.copy(fontFeatureSettings = "tnum"), color = colors.textQuaternary, maxLines = 1, modifier = Modifier.testTag("event-time"))
                }
                if (body != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
                }
                if (openAgent != null) {
                    Spacer(Modifier.width(if (body != null) 2.dp else 4.dp))
                    // Its own pressable, so the report can be opened here and the agent's chat there.
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .pressable(openAgent, CircleShape)
                            .semantics { contentDescription = "Open agent" }
                            .testTag("open-worker"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(CursorIcons.ChevronRight, null, tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
        if (body != null) {
            AnimatedVisibility(visible = expanded) {
                CursorCard(Modifier.fillMaxWidth().padding(top = 6.dp), fill = colors.fillFaint, border = Color.Transparent) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report?.let { MarkdownText(it, style = type.base, color = colors.textSecondary) }
                        // The remark reads as the coordinator's notes read elsewhere in its chat: dimmer than a report.
                        if (narration != null) {
                            if (report != null) HairlineDivider()
                            MarkdownText(narration, Modifier.testTag("notification-narration"), style = type.base, color = colors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A run the server's status says failed, as one line in the event row's shape: the warning glyph, "Run failed", the
 * server's reason (or, without one, how long the run had worked), and when it ended. Where it stands says how much
 * it matters: after its stretch as a row of its own while the conversation has not moved past it, inside the
 * stretch once it has (see [TranscriptRow.Failure], [TranscriptRow.Entry.Failure]). Press and hold copies the
 * reason; tapping opens the whole of a reason the line had to cut short.
 */
@Composable
internal fun RunFailureRow(footer: RunFooter, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val reason = footer.reason?.trim()?.takeIf { it.isNotEmpty() }
    val duration = TimeFormat.duration(footer.durationMs)
    val tail = reason ?: duration?.let { "after $it" }
    var expanded by rememberSaveable(footer.id) { mutableStateOf(false) }
    var tailCut by remember(reason) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "chevron")
    val opens = reason != null && (tailCut || reason.contains('\n'))
    Column(modifier.fillMaxWidth().testTag("run-failure")) {
        MessageActions(
            text = listOfNotNull(RUN_FAILED, reason).joinToString(": "),
            onClick = if (opens) ({ expanded = !expanded }) else null,
            modifier = Modifier.offset(x = (-6).dp).clip(CursorTheme.shapes.base),
        ) {
            Row(Modifier.heightIn(min = 28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                SubjectAndTail(
                    modifier = Modifier.weight(1f, fill = false),
                    subject = { Text(RUN_FAILED, style = type.base, color = colors.textSecondary, maxLines = 1) },
                    tail = {
                        if (tail != null) {
                            Text(
                                " \u00B7 ${tail.lineSequence().first()}",
                                style = type.base,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { tailCut = it.hasVisualOverflow },
                                modifier = Modifier.testTag("run-failure-reason"),
                            )
                        }
                    },
                )
                footer.endedAtMillis?.let { at ->
                    Spacer(Modifier.width(8.dp))
                    Text(TimeFormat.relativeShort(at), style = type.small.copy(fontFeatureSettings = "tnum"), color = colors.textQuaternary, maxLines = 1, modifier = Modifier.testTag("run-failure-time"))
                }
                if (opens) {
                    Spacer(Modifier.width(4.dp))
                    Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
                }
            }
        }
        if (opens && reason != null) {
            AnimatedVisibility(visible = expanded) {
                CursorCard(Modifier.fillMaxWidth().padding(top = 6.dp), fill = colors.fillFaint, border = Color.Transparent) {
                    Text(reason, style = type.base, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("run-failure-reason-full"))
                }
            }
        }
    }
}

/** The failure line's subject. */
internal const val RUN_FAILED = "Run failed"

/**
 * The subject of a line and the tail after it, sharing the width the row gives them: the tail is measured for what
 * it needs, the subject gets the rest — never less than [SUBJECT_SHARE] of the width, so a long tail (a goal's
 * objective) cannot squeeze the subject to nothing — and the tail takes what remains, cut at its end.
 */
@Composable
private fun SubjectAndTail(modifier: Modifier, subject: @Composable () -> Unit, tail: @Composable () -> Unit) {
    Layout(content = { subject(); tail() }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val tailNeed = measurables[1].maxIntrinsicWidth(constraints.maxHeight)
        val subjectMax = maxOf(width - tailNeed, (width * SUBJECT_SHARE).toInt()).coerceIn(0, width)
        val subjectPlaced = measurables[0].measure(constraints.copy(minWidth = 0, maxWidth = subjectMax))
        val tailPlaced = measurables[1].measure(constraints.copy(minWidth = 0, maxWidth = (width - subjectPlaced.width).coerceAtLeast(0)))
        val height = maxOf(subjectPlaced.height, tailPlaced.height)
        layout(subjectPlaced.width + tailPlaced.width, height) {
            subjectPlaced.placeRelative(0, (height - subjectPlaced.height) / 2)
            tailPlaced.placeRelative(subjectPlaced.width, (height - tailPlaced.height) / 2)
        }
    }
}

private const val SUBJECT_SHARE = 0.55f

/** The glyph a kind of event is drawn with. */
private fun glyph(source: EventLine.Source): ImageVector = when (source) {
    EventLine.Source.GitHub -> CursorIcons.GitPullRequest
    EventLine.Source.Subagent -> CursorIcons.Sparkle
    EventLine.Source.Worker -> CursorIcons.Multitask
    EventLine.Source.Goal -> CursorIcons.Target
    EventLine.Source.Task -> CursorIcons.Terminal
    EventLine.Source.Timer -> CursorIcons.Clock
    EventLine.Source.Other -> CursorIcons.Bell
}

/**
 * A run of silent injected turns behind one line — "14 events · 9 GitHub · 5 subagents · 2h span" — that opens onto
 * every event in order, each as the [EventRow] it would be alone with its own detail, and the stretches of work done
 * between them where there were any. Closed unless the group is the newest and small (see [TranscriptRow.Events]).
 */
@Composable
internal fun EventGroupView(group: TranscriptRow.Events, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var expanded by rememberSaveable(group.key) { mutableStateOf(group.startsOpen) }
    val summary = group.summary
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "chevron")
    Column(modifier.fillMaxWidth().testTag("event-group")) {
        Row(
            Modifier
                .offset(x = (-6).dp)
                .pressable({ expanded = !expanded }, CursorTheme.shapes.base)
                .heightIn(min = 28.dp)
                .padding(horizontal = 6.dp)
                .semantics { contentDescription = if (expanded) "Hide events" else "Show events" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CursorIcons.Bell, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(summary.count, style = type.base.copy(fontFeatureSettings = "tnum"), color = colors.textSecondary, maxLines = 1)
            listOfNotNull(summary.kinds, summary.span).joinToString(" \u00B7 ").takeIf { it.isNotEmpty() }?.let { rest ->
                Spacer(Modifier.width(4.dp))
                Text(
                    "\u00B7 $rest",
                    style = type.base.copy(fontFeatureSettings = "tnum"),
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(CursorIcons.ChevronDown, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).testTag("event-group-rows"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Each row owns its slot, so an opened report stays with its event as the group grows.
                group.rows.forEach { row -> key(row.key) { TranscriptRowView(row) } }
            }
        }
    }
}
