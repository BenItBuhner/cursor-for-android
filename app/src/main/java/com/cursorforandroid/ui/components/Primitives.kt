package com.cursorforandroid.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/** Hairline border + fill that share one shape so the edge stays crisp. */
fun Modifier.cursorSurface(fill: Color, border: Color, shape: Shape): Modifier =
    this.clip(shape).background(fill, shape).border(CursorDimens.borderWidth, border, shape)

@Composable
fun Modifier.pressable(onClick: () -> Unit, shape: Shape, enabled: Boolean = true, role: Role? = Role.Button): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clip(shape).clickable(
        interactionSource = interaction,
        indication = ripple(color = CursorTheme.colors.base),
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

/** Circular 40dp icon button: 15% base fill, hairline border — the filter / sidebar / more / send affordance. */
@Composable
fun CursorIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = CursorDimens.iconButton,
    iconSize: Dp = CursorDimens.iconSize,
    filled: Boolean = true,
    tint: Color = CursorTheme.colors.textPrimary,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    val colors = CursorTheme.colors
    val fill = when {
        prominent -> colors.base
        filled -> colors.borderFocus
        else -> Color.Transparent
    }
    val border = if (filled && !prominent) colors.borderSubtle else Color.Transparent
    val iconTint = if (prominent) colors.canvas else tint.copy(alpha = if (enabled) tint.alpha else tint.alpha * 0.4f)
    Box(
        modifier = modifier
            .size(size)
            .cursorSurface(fill, border, CircleShape)
            .pressable(onClick = onClick, shape = CircleShape, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

/** Level-1 card: surface fill, hairline border, radius 10. */
@Composable
fun CursorCard(
    modifier: Modifier = Modifier,
    shape: Shape = CursorTheme.shapes.lg,
    fill: Color = CursorTheme.colors.surface,
    border: Color = CursorTheme.colors.borderSubtle,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.cursorSurface(fill, border, shape).padding(contentPadding), content = content)
}

/** Sentence-case group label ("Pinned", "Today", "Grouping"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = CursorTheme.typography.sectionLabel, color = CursorTheme.colors.textPlaceholder)
        trailing?.invoke(this)
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier, color: Color = CursorTheme.colors.borderSubtle) {
    Box(modifier.fillMaxWidth().height(CursorDimens.borderWidth).background(color))
}

/** Neutral chip: 12% base fill, radius 6, secondary text. */
@Composable
fun CursorChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    tint: Color = CursorTheme.colors.textSecondary,
    fill: Color = CursorTheme.colors.selected,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = CursorTheme.shapes.sm
    Row(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(if (onClick != null) Modifier.pressable(onClick, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(
            text,
            style = if (mono) CursorTheme.typography.code.copy(fontSize = CursorTheme.typography.caption.fontSize) else CursorTheme.typography.caption,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingIcon != null) Icon(trailingIcon, null, tint = tint, modifier = Modifier.size(13.dp))
    }
}

/** Cursor-styled switch: green track when on (desktop toggle colour), 12% base when off, white knob. */
@Composable
fun CursorSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = CursorTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.green,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White.copy(alpha = 0.9f),
            uncheckedTrackColor = colors.selected,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Leading list indicator: 8dp dot, or the spinning sparkle for a running agent. */
@Composable
fun StatusIndicator(indicator: AgentIndicator, modifier: Modifier = Modifier, size: Dp = CursorDimens.statusDot) {
    val colors = CursorTheme.colors
    when (indicator) {
        AgentIndicator.Running -> RunningGlyph(modifier, color = colors.statusRunning)
        AgentIndicator.Unread -> Dot(colors.statusUnread, modifier, size)
        AgentIndicator.Error -> Dot(colors.statusError, modifier, size)
        AgentIndicator.Read -> Dot(colors.statusIdle, modifier, size)
        AgentIndicator.Archived -> Box(modifier.size(size).border(1.dp, colors.statusIdle, CircleShape))
    }
}

@Composable
private fun Dot(color: Color, modifier: Modifier, size: Dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Slowly rotating four-point sparkle — Cursor's "agent is working" glyph. */
@Composable
fun RunningGlyph(modifier: Modifier = Modifier, color: Color = CursorTheme.colors.statusRunning, size: Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "running")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Icon(CursorIcons.Sparkle, null, tint = color, modifier = modifier.size(size).rotate(angle))
}

/** Thin indeterminate ring used inside cards and the composer while a run is in flight. */
@Composable
fun SpinnerRing(modifier: Modifier = Modifier, color: Color = CursorTheme.colors.textSecondary, size: Dp = 14.dp, strokeWidth: Dp = 1.5.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    Box(
        modifier.size(size).drawBehind {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = stroke)
            drawArc(color, angle, 90f, false, style = stroke)
        },
    )
}
