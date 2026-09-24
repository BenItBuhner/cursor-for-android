package com.cursorforandroid.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.roundToInt

/**
 * The controls of the widget configuration screen, in the app's own chrome and without a line drawn anywhere: a
 * group is a 12sp label over a card one step up from the canvas (`--cursor-editor` over `--cursor-chrome`), the
 * rows inside it are set apart by air rather than hairlines, and every nested shape keeps its corner concentric
 * with the one around it — the card's 12dp, the controls' 8dp four points inside it, a segmented control's 6dp thumb
 * two points inside its track. Shared by every widget kind that registers with [WidgetKinds].
 */
object WidgetOptionRadii {
    val card: Dp = 12.dp
    val cardInset: Dp = 4.dp
    val control: Dp = card - cardInset
    val trackInset: Dp = 2.dp
    val thumb: Dp = control - trackInset
}

/** A group: its label in the group-label voice, then the card. */
@Composable
fun OptionGroup(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = CursorTheme.colors
    Column(modifier.fillMaxWidth().widthIn(max = 640.dp)) {
        Text(label, style = CursorTheme.typography.small, color = colors.textTertiary, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 2.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WidgetOptionRadii.card))
                .background(colors.elevated)
                .padding(WidgetOptionRadii.cardInset),
            content = content,
        )
    }
}

/** A labelled row inside a group: the label leads, the control trails; 44dp tall, inset so its shape sits in the card's. */
@Composable
fun OptionRow(label: String, modifier: Modifier = Modifier, detail: String? = null, control: @Composable () -> Unit) {
    val colors = CursorTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = CursorDimens.listRow).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = CursorTheme.typography.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

/** A label over a control that fills the card's width (a chip row, a slider): the row voice, with the air of a row above it. */
@Composable
fun OptionLabel(label: String, modifier: Modifier = Modifier) {
    Text(label, style = CursorTheme.typography.base, color = CursorTheme.colors.textPrimary, modifier = modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp))
}

/** A row with one of the app's toggles at its end; the whole row flips it. */
@Composable
fun OptionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .pressable({ haptics.toggle(!checked); onChange(!checked) }, RoundedCornerShape(WidgetOptionRadii.control), role = Role.Switch)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = CursorTheme.colors
        Column(Modifier.weight(1f)) {
            Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = CursorTheme.typography.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        CursorToggle(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Equal segments in a faint track; the chosen one carries a raised thumb that slides to it. The whole control is
 * one row of the card, so each option is a comfortable tap wide however many there are.
 */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val trackShape = RoundedCornerShape(WidgetOptionRadii.control)
    val thumbShape = RoundedCornerShape(WidgetOptionRadii.thumb)
    var widthPx by remember { mutableIntStateOf(0) }
    val fraction by animateFloatAsState(selected.toFloat(), tween(220), label = "segment")
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .height(36.dp)
            .clip(trackShape)
            .background(colors.fillFaint)
            .padding(WidgetOptionRadii.trackInset)
            .onSizeChanged { widthPx = it.width },
    ) {
        val segmentPx = if (options.isEmpty()) 0f else widthPx.toFloat() / options.size
        Box(
            Modifier
                .offset { IntOffset((fraction * segmentPx).roundToInt(), 0) }
                .width(with(LocalDensity.current) { segmentPx.toDp() })
                .height(32.dp)
                .clip(thumbShape)
                .background(colors.fillMedium),
        )
        Row(Modifier.fillMaxWidth().height(32.dp)) {
            options.forEachIndexed { index, option ->
                val chosen = index == selected
                val tint by animateColorAsState(if (chosen) colors.textPrimary else colors.textTertiary, tween(160), label = "segment-text")
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(thumbShape)
                        .pressable({ onSelect(index) }, thumbShape, role = Role.RadioButton)
                        .semantics { this.selected = chosen },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option, style = if (chosen) CursorTheme.typography.baseMedium else CursorTheme.typography.base, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/** Wrapping pills, for a set too wide for a segmented row: the chosen one filled, the rest on the faint fill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { index, option ->
            val chosen = index == selected
            val fill by animateColorAsState(if (chosen) colors.fillMedium else colors.fillFaint, tween(160), label = "chip-fill")
            val tint by animateColorAsState(if (chosen) colors.textPrimary else colors.textTertiary, tween(160), label = "chip-text")
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(fill)
                    .pressable({ onSelect(index) }, CircleShape, role = Role.RadioButton)
                    .semantics { this.selected = chosen }
                    .height(32.dp)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = if (chosen) CursorTheme.typography.baseMedium else CursorTheme.typography.base, color = tint, maxLines = 1)
            }
        }
    }
}

/**
 * The app's flat slider: a 4dp track in the medium fill, the travelled part in the secondary text tone, a white knob
 * like the toggle's. Snaps to [step]s of [range]; the value is read out at the end.
 */
@Composable
fun OptionSlider(value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, format: (Int) -> String = { "$it" }) {
    val colors = CursorTheme.colors
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction by animateFloatAsState(((value - range.first).toFloat() / span).coerceIn(0f, 1f), tween(120), label = "slider")
    var widthPx by remember { mutableIntStateOf(0) }
    val knob = 16.dp
    fun valueAt(x: Float): Int {
        if (widthPx == 0) return value
        val raw = range.first + (x / widthPx).coerceIn(0f, 1f) * span
        return ((raw / step).roundToInt() * step).coerceIn(range.first, range.last)
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).heightIn(min = CursorDimens.listRow), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(32.dp)
                .onSizeChanged { widthPx = it.width }
                .semantics { contentDescription = format(value) }
                .pointerInput(range, step) {
                    detectTapGestures { onChange(valueAt(it.x)) }
                }
                .pointerInput(range, step) {
                    detectHorizontalDragGestures { change, _ -> onChange(valueAt(change.position.x)) }
                }
                .drawBehind {
                    val trackHeight = 4.dp.toPx()
                    val y = (size.height - trackHeight) / 2
                    val radius = CornerRadius(trackHeight / 2)
                    drawRoundRect(colors.fillMedium, Offset(0f, y), Size(size.width, trackHeight), radius)
                    drawRoundRect(colors.textSecondary, Offset(0f, y), Size(size.width * fraction, trackHeight), radius)
                    drawCircle(Color.White, knob.toPx() / 2, Offset(size.width * fraction, size.height / 2))
                },
        )
        Spacer(Modifier.width(12.dp))
        Text(format(value), style = CursorTheme.typography.base, color = colors.textTertiary, modifier = Modifier.widthIn(min = 40.dp))
    }
}

/**
 * A set of drawn choices — the corner button's styles — each in a 56dp cell; the chosen cell sits on the medium fill,
 * the rest on nothing. [swatch] draws one choice at rest in its cell.
 */
@Composable
fun <T> SwatchRow(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String, modifier: Modifier = Modifier, swatch: @Composable (T) -> Unit) {
    val colors = CursorTheme.colors
    val cell = RoundedCornerShape(WidgetOptionRadii.control)
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val chosen = option == selected
            val fill by animateColorAsState(if (chosen) colors.fillMedium else Color.Transparent, tween(160), label = "swatch-fill")
            val tint by animateColorAsState(if (chosen) colors.textPrimary else colors.textTertiary, tween(160), label = "swatch-text")
            Column(
                Modifier
                    .weight(1f)
                    .clip(cell)
                    .background(fill)
                    .pressable({ onSelect(option) }, cell, role = Role.RadioButton)
                    .semantics { this.selected = chosen; this.contentDescription = label(option) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                swatch(option)
                Text(label(option), style = CursorTheme.typography.small, color = tint, maxLines = 1)
            }
        }
    }
}

/** A round button as the widget draws it, for [SwatchRow] and the like. */
@Composable
fun RoundButtonSwatch(fill: Color, glyph: ImageVector, glyphTint: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Icon(glyph, null, tint = glyphTint, modifier = Modifier.size(size / 2))
    }
}
