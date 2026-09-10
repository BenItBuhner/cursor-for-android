package com.cursorforandroid.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock

/** How the snooze picker lays out its nine durations. The live picker uses [GroupedList]. */
enum class SnoozeSheetLayout {
    /** Sectioned rows — Soon / Later / Longer — matching the model picker's list. */
    GroupedList,
    /** One stacked list, no section labels. */
    FlatList,
    /** Short chips in padded groups, still a sheet rather than a dialog pad. */
    ChipSheet,
}

/**
 * Duration picker for snoozing a chat. Same [CursorSheet] chrome as the model picker: side inset, sheet radius,
 * no floating dialog. A tap snoozes immediately and dismisses the sheet.
 */
@Composable
fun SnoozeChatDialog(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    nowMillis: Long = AppClock.now(),
    layout: SnoozeSheetLayout = SnoozeSheetLayout.GroupedList,
) {
    SnoozeSheet(onPick = onPick, onDismiss = onDismiss, nowMillis = nowMillis, layout = layout)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSheet(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    nowMillis: Long = AppClock.now(),
    layout: SnoozeSheetLayout = SnoozeSheetLayout.GroupedList,
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
            "Hide this chat until then. It comes back on its own.",
            style = type.small,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        when (layout) {
            SnoozeSheetLayout.GroupedList -> GroupedListBody(::pick)
            SnoozeSheetLayout.FlatList -> FlatListBody(::pick)
            SnoozeSheetLayout.ChipSheet -> ChipSheetBody(::pick)
        }
    }
}

@Composable
private fun GroupedListBody(onPick: (SnoozeDuration) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        SnoozeDuration.groups.forEach { (label, durations) ->
            SnoozeSectionLabel(label)
            durations.forEach { duration ->
                SnoozeDurationRow(duration, onPick)
            }
        }
    }
}

@Composable
private fun FlatListBody(onPick: (SnoozeDuration) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        SnoozeDuration.entries.forEach { duration ->
            SnoozeDurationRow(duration, onPick)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSheetBody(onPick: (SnoozeDuration) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        SnoozeDuration.groups.forEach { (label, durations) ->
            Text(
                label,
                style = CursorTheme.typography.small,
                color = CursorTheme.colors.textTertiary,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                durations.forEach { duration ->
                    SnoozeChip(duration, onPick)
                }
            }
        }
    }
}

@Composable
private fun SnoozeSectionLabel(text: String) {
    Text(
        text,
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.textTertiary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SnoozeDurationRow(duration: SnoozeDuration, onPick: (SnoozeDuration) -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .pressable({ onPick(duration) }, CursorTheme.shapes.base)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "Snooze ${duration.title}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            duration.title,
            style = CursorTheme.typography.base,
            color = if (duration == SnoozeDuration.Forever) colors.textSecondary else colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (duration != SnoozeDuration.Forever) {
            Text(duration.label, style = CursorTheme.typography.small, color = colors.textQuaternary)
        }
    }
}

@Composable
private fun SnoozeChip(duration: SnoozeDuration, onPick: (SnoozeDuration) -> Unit) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.base
    Box(
        Modifier
            .background(colors.fillFaint, shape)
            .pressable({ onPick(duration) }, shape)
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
