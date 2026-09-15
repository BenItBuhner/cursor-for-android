package com.cursorforandroid.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.ShimmerText
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * A task the agent handed to a subagent (`task`, `task_v2`), drawn as cursor.com draws its Task card: a dot in the
 * task's state colour, the task's title, a cloud glyph when the subagent ran as a cloud agent, and the state word
 * under it — `Completed`, `Working…` while it runs, `Failed` — in the tertiary colour. A tap opens the cloud agent
 * when it is one and the screen can navigate; otherwise it opens the row onto the task's details (how long it ran,
 * where its transcript was written).
 */
@Composable
internal fun TaskRow(call: ToolCall, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val controls = LocalTranscriptControls.current
    val payload = call.payload as? ToolPayload.Subagent
    val title = payload?.description?.trim()?.takeIf { it.isNotEmpty() } ?: call.summary.trim().takeIf { it.isNotEmpty() } ?: "Task"
    val state = when {
        call.isRunning -> "Working…"
        call.isError -> "Failed"
        else -> "Completed"
    }
    val dot = when {
        call.isRunning -> colors.accent
        call.isError -> colors.red
        else -> colors.iconTertiary
    }
    val detail = listOfNotNull(state, TimeFormat.duration(payload?.durationMs)?.takeIf { !call.isRunning }).joinToString(" · ")
    val agentId = payload?.agentId
    val open = controls.onOpenAgent?.takeIf { agentId != null && payload.isCloudAgent }?.let { handler -> { handler(agentId!!) } }
    val expandable = open == null && payload != null && (payload.transcriptPath != null || payload.durationMs != null || payload.subagentType != null)
    var expanded by rememberSaveable("task-${call.callId}") { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .pressable({ open?.invoke() ?: run { expanded = !expanded } }, CursorTheme.shapes.base, enabled = open != null || expandable)
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "Task $title, $state" }
            .testTag("task-row"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(11.dp))
            ShimmerText(title, style = type.base, color = colors.textPrimary, active = call.isRunning, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (payload?.isCloudAgent == true) {
                Spacer(Modifier.width(6.dp))
                Icon(CursorIcons.Cloud, "Cloud agent", tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
            }
        }
        Text(detail, style = type.base, color = colors.textTertiary, maxLines = 1, modifier = Modifier.padding(start = 18.dp, top = 2.dp).testTag("task-state"))
        if (expandable) {
            AnimatedVisibility(visible = expanded) {
                SubagentCard(payload!!, Modifier.padding(start = 18.dp, top = 6.dp))
            }
        }
    }
}
