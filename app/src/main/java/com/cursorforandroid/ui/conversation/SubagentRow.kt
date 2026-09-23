package com.cursorforandroid.ui.conversation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.domain.SubagentCall
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.SubagentLook
import com.cursorforandroid.domain.SubagentModel
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.Dot
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.RunningGlyph
import com.cursorforandroid.ui.components.ShimmerText
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf

/**
 * A subagent as Cursor's desktop draws it (`SubagentTaskCard`, Cursor 3.21.18): the indicator in a slot of its own
 * — the dot grid while the child works, a dot once it is done — then the title, the model muted after it (with the
 * brain and "Fast" when Fast is on) and the glyph for where it runs; under them one line with where it stands —
 * the step it announced, the action it is on, or how it ended — rolling from one to the next.
 *
 * Tapping opens the child's conversation when it is a cloud agent the app can open; any other row opens onto what
 * the call said (the task's card, the message sent).
 */
@Composable
internal fun SubagentRowView(call: ToolCall, subagent: SubagentCall, modifier: Modifier = Modifier) {
    val controls = LocalTranscriptControls.current
    val agentId = subagent.agentId
    val latest = controls.subagents.isLatest(call, subagent)
    val worker = agentId?.let { controls.subagents.workers[it] }
    val agent = agentId?.takeIf { subagent.isCloudAgent }?.let(controls.agentById)
    val activity = remember(agentId, latest, controls.subagentActivity) {
        if (latest && agentId != null && subagent.isCloudAgent) controls.subagentActivity(agentId) else flowOf(null)
    }
    val live by activity.collectAsState(null)
    val child = live ?: controls.subagentRuns[call.callId] ?: agent?.let { SubagentRows.childOf(it, controls.models) }
    val look = SubagentRows.look(call, subagent, child, latest)
    val title = SubagentRows.title(subagent, look, worker?.name, child?.name ?: agent?.name)
    val model = SubagentRows.modelLabel(subagent, controls.models, worker?.modelId, agent, child?.model)
    val placement = SubagentRows.placement(subagent, controls.placement, agent)
    val open = controls.onOpenAgent?.takeIf { agentId != null && subagent.isCloudAgent && look.status != SubagentRows.STARTING }?.let { handler -> { handler(agentId!!) } }
    val details = if (open == null) subagentDetails(call, subagent) else null
    var expanded by rememberSaveable(call.callId) { mutableStateOf(false) }
    val taps = LocalDisclosureTaps.current
    Column(modifier.fillMaxWidth()) {
        SubagentRowContent(
            title = title,
            look = look,
            model = model,
            placement = placement,
            modifier = Modifier
                .offset(x = -(ROW_PADDING + ROW_OUTSET))
                .pressable(
                    {
                        if (open != null) open() else { taps.toggling(opening = !expanded); expanded = !expanded }
                    },
                    CursorTheme.shapes.base,
                    enabled = open != null || details != null,
                )
                .padding(horizontal = ROW_PADDING, vertical = 3.dp)
                .testTag("subagent-row")
                .semantics { contentDescription = "Subagent $title, ${look.status}" },
        )
        if (details != null) {
            AnimatedVisibility(visible = expanded) {
                Box(Modifier.padding(start = SLOT_WIDTH + SLOT_GAP - ROW_OUTSET, top = 2.dp, bottom = 6.dp)) { details() }
            }
        }
    }
}

/** What a row that cannot open a chat opens onto: the task's own card, or what was said to the worker. */
private fun subagentDetails(call: ToolCall, subagent: SubagentCall): (@Composable () -> Unit)? {
    (call.payload as? ToolPayload.Subagent)?.let { payload -> return { SubagentCard(payload) } }
    val text = subagent.prompt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return {
        val colors = CursorTheme.colors
        CursorCard(Modifier.fillMaxWidth(), fill = colors.fillFaint, border = Color.Transparent) {
            MarkdownText(text, style = CursorTheme.typography.base, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** The row as drawn, from what it says: kept apart from where that comes from, for the previews and the goldens. */
@Composable
internal fun SubagentRowContent(title: String, look: SubagentLook, model: SubagentModel?, placement: SubagentPlacement?, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val line = type.base.copy(lineHeight = LINE_HEIGHT, lineHeightStyle = FULL_LINE)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .width(SLOT_WIDTH)
                .padding(top = (LINE_HEIGHT_DP - GLYPH_SIZE) / 2)
                .alpha(if (look.dimmed) DIMMED_ALPHA else 1f),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(Modifier.size(GLYPH_SIZE), contentAlignment = Alignment.Center) {
                when (look.indicator) {
                    SubagentLook.Indicator.Running -> RunningGlyph(color = colors.iconTertiary, size = GLYPH_SIZE)
                    SubagentLook.Indicator.Finished -> Dot(colors.iconQuaternary, DOT_SIZE)
                    SubagentLook.Indicator.Error -> Dot(colors.red, DOT_SIZE)
                    SubagentLook.Indicator.Attention -> Dot(colors.yellow, DOT_SIZE)
                }
            }
        }
        Spacer(Modifier.width(SLOT_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = line, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (model != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(model.label, style = line, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("subagent-model"))
                    if (model.fast) {
                        Spacer(Modifier.width(4.dp))
                        Icon(ProjectIcons.vector("brain"), null, tint = colors.iconTertiary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(FAST, style = type.tiny.copy(lineHeight = LINE_HEIGHT, lineHeightStyle = FULL_LINE), color = colors.textTertiary, maxLines = 1, modifier = Modifier.testTag("subagent-fast"))
                    }
                }
                placement?.let { place ->
                    Spacer(Modifier.width(6.dp))
                    Icon(placementIcon(place), placementLabel(place), tint = colors.iconTertiary, modifier = Modifier.size(13.dp).testTag("subagent-placement"))
                }
            }
            StatusLine(look, line)
        }
    }
}

/**
 * The line under the title, the desktop's `TextRoll`: each status holds for at least [HOLD_MS] before the next rolls
 * up in its place, so a child racing through its steps reads as a steady line rather than a flicker. It shimmers
 * while the child works; a child waiting on the reader reads in the link's colour.
 */
@Composable
private fun StatusLine(look: SubagentLook, style: TextStyle) {
    val colors = CursorTheme.colors
    val shown = heldText(look.status)
    val color = if (look.attention) colors.link.copy(alpha = 0.85f) else colors.textSecondary
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            (slideInVertically(tween(ROLL_MS)) { it / 2 } + fadeIn(tween(ROLL_MS))) togetherWith
                (slideOutVertically(tween(ROLL_MS)) { -it / 2 } + fadeOut(tween(ROLL_MS))) using SizeTransform(clip = true)
        },
        label = "subagent-status",
        modifier = Modifier.height(LINE_HEIGHT_DP).testTag("subagent-status"),
    ) { text ->
        ShimmerText(text, style = style, color = color, active = look.active, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** [text], but no value shown for less than [HOLD_MS]: the newest one waiting its turn is what shows next. */
@Composable
private fun heldText(text: String): String {
    val latest by rememberUpdatedState(text)
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.conflate().collect { next ->
            if (next != shown) shown = next
            delay(HOLD_MS)
        }
    }
    return shown
}

private fun placementIcon(place: SubagentPlacement): ImageVector = when (place) {
    SubagentPlacement.Cloud -> CursorIcons.Cloud
    SubagentPlacement.Machine -> CursorIcons.Desktop
    SubagentPlacement.Local -> ProjectIcons.vector("laptop")
}

private fun placementLabel(place: SubagentPlacement): String = when (place) {
    SubagentPlacement.Cloud -> "Cloud"
    SubagentPlacement.Machine -> "Self-hosted machine"
    SubagentPlacement.Local -> "Local"
}

/**
 * Measured off the desktop's row beside a reply: the dots start just left of the text column and the title
 * 18 in; each line is a whole 22 box, title and status one under the other, the glyph centred on the first.
 */
private val SLOT_WIDTH = 20.dp
private val SLOT_GAP = 2.dp
private val ROW_OUTSET = 4.dp
private val ROW_PADDING = 6.dp
private val GLYPH_SIZE = 12.dp
private val DOT_SIZE = 6.dp
private val LINE_HEIGHT = 22.sp
private val LINE_HEIGHT_DP = 22.dp
private val FULL_LINE = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
private const val DIMMED_ALPHA = 0.65f
private const val HOLD_MS = 1_200L
private const val ROLL_MS = 220
private const val FAST = "Fast"
