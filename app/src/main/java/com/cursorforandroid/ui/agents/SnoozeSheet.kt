package com.cursorforandroid.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.SnoozeDuration
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock

/**
 * Duration picker for snoozing a chat. Same [CursorSheet] chrome as the model picker: elevated surface, sheet
 * radius, 20dp inset. Durations are grouped chips — Soon / Later / Longer — and a tap snoozes immediately.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SnoozeChatDialog(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    nowMillis: Long = AppClock.now(),
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        fun pick(duration: SnoozeDuration) {
            onPick(duration.untilMillis(nowMillis))
            dismiss()
        }
        SheetHeader("Snooze")
        Text(
            "No notifications until then. The chat stays on the list.",
            style = type.small,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            SnoozeDuration.groups.forEach { (label, durations) ->
                Text(
                    label,
                    style = type.small,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    durations.forEach { duration ->
                        SnoozeChip(duration) { pick(duration) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnoozeChip(duration: SnoozeDuration, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.base
    Box(
        Modifier
            .background(colors.fillFaint, shape)
            .pressable(onClick, shape)
            .height(36.dp)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = "Snooze ${duration.title}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            duration.title,
            style = CursorTheme.typography.base,
            color = if (duration == SnoozeDuration.Forever) colors.textSecondary else colors.textPrimary,
        )
    }
}
