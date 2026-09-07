package com.cursorforandroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/** Fill + hairline stroke sharing one shape so the edge stays crisp. */
fun Modifier.cursorSurface(fill: Color, border: Color, shape: Shape): Modifier =
    this.clip(shape).background(fill, shape).then(if (border.alpha > 0f) Modifier.border(CursorDimens.hairline, border, shape) else Modifier)

@Composable
fun Modifier.pressable(onClick: () -> Unit, shape: Shape, enabled: Boolean = true, role: Role? = Role.Button): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clip(shape).clickable(
        interactionSource = interaction,
        indication = ripple(color = CursorTheme.colors.base, bounded = true),
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

/**
 * Cursor's icon button: no fill, no border, a 14px glyph at 66 % (`--cursor-icon-secondary`). The 28dp box is
 * only the hit area; nothing is painted until pressed.
 */
@Composable
fun FlatIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = CursorDimens.iconButton,
    iconSize: Dp = CursorDimens.headerIcon,
    tint: Color = CursorTheme.colors.iconSecondary,
    enabled: Boolean = true,
) {
    TouchTarget(size = size, touchSize = 40.dp, shape = CursorTheme.shapes.base, onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = tint.alpha * 0.4f), modifier = Modifier.size(iconSize))
    }
}

/**
 * A control that occupies [size] in the layout but accepts touches over [touchSize]: the larger hit layer uses
 * `requiredSize`, so it overflows the visual box symmetrically without changing measured bounds. The press
 * ripple stays on the visual box.
 */
@Composable
fun TouchTarget(
    size: Dp,
    touchSize: Dp,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(maxOf(size, touchSize))
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick),
        )
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .indication(interaction, ripple(color = CursorTheme.colors.base, bounded = true)),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * The round buttons of Cursor's composer footer, measured at 24px with 12px glyphs: "+" on an 8 % fill, send / stop
 * on a foreground fill with a canvas-coloured glyph.
 */
@Composable
fun ComposerRoundButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    size: Dp = CursorDimens.roundButton,
) {
    val colors = CursorTheme.colors
    val fill = if (prominent) colors.textPrimary else colors.fill
    val tint = if (prominent) colors.canvas else colors.iconSecondary
    TouchTarget(size = size, touchSize = 36.dp, shape = CircleShape, onClick = onClick, enabled = enabled, modifier = modifier) {
        Box(
            Modifier.size(size).background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = tint.alpha * 0.5f), modifier = Modifier.size(CursorDimens.roundButtonGlyph))
        }
    }
}

/** Elevated card: `--cursor-editor` fill, 8 % stroke, radius 8. */
@Composable
fun CursorCard(
    modifier: Modifier = Modifier,
    shape: Shape = CursorTheme.shapes.lg,
    fill: Color = CursorTheme.colors.elevated,
    border: Color = CursorTheme.colors.strokeSubtle,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.cursorSurface(fill, border, shape).padding(contentPadding), content = content)
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier, color: Color = CursorTheme.colors.strokeSubtle) {
    Box(modifier.fillMaxWidth().height(CursorDimens.hairline).background(color))
}

/** Group label such as "Pinned" / "Today" / "Chats": 12sp at 60 %, sentence case, no chevron. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Capsule pill measured at 20px tall with 12px text — "Branch", "Open", "Early Beta", branch names. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = CursorTheme.colors.textSecondary,
    fill: Color = CursorTheme.colors.fill,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = CursorTheme.shapes.full
    Row(
        modifier
            .height(CursorDimens.pillHeight)
            .clip(shape)
            .background(fill, shape)
            .then(if (onClick != null) Modifier.pressable(onClick, shape) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text,
            style = (if (mono) CursorTheme.typography.code else CursorTheme.typography.small).copy(lineHeight = 14.sp),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Desktop-sized toggle (30 x 16): green track when on, 14 % fill when off, white knob. */
@Composable
fun CursorToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val track by animateColorAsState(if (checked) colors.green else colors.fillMedium, label = "track")
    val knobOffset by animateDpAsState(if (checked) 16.dp else 2.dp, label = "knob")
    Box(
        modifier
            .size(width = 30.dp, height = 16.dp)
            .background(track, CircleShape)
            .pressable({ onCheckedChange(!checked) }, CircleShape, role = Role.Switch),
    ) {
        Box(
            Modifier
                .offset { IntOffset(knobOffset.roundToPx(), 2.dp.roundToPx()) }
                .size(12.dp)
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * Leading state glyph of a sidebar row (12px slot). Web semantics: running sparkle, unread blue dot, error red
 * dot, purple branch glyph for a read agent that pushed a branch, nothing for a plain read agent.
 */
@Composable
fun StateGlyph(indicator: AgentIndicator, hasBranch: Boolean, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    Box(modifier.size(CursorDimens.glyph), contentAlignment = Alignment.Center) {
        when (indicator) {
            AgentIndicator.Running -> RunningGlyph(color = colors.iconSecondary, size = 16.dp)
            AgentIndicator.Unread -> Dot(colors.unreadDot)
            AgentIndicator.Error -> Dot(colors.red)
            AgentIndicator.Read -> if (hasBranch) Icon(CursorIcons.GitBranch, null, tint = colors.branchGlyph, modifier = Modifier.size(16.dp))
            AgentIndicator.Archived -> Icon(CursorIcons.Archive, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = CursorDimens.unreadDot, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** The web sidebar's "working" glyph: a slowly rotating cluster of dots. */
@Composable
fun RunningGlyph(modifier: Modifier = Modifier, color: Color = CursorTheme.colors.iconSecondary, size: Dp = 16.dp) {
    val transition = rememberInfiniteTransition(label = "running")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Icon(CursorIcons.Working, null, tint = color, modifier = modifier.size(size).rotate(angle))
}

/** Thin indeterminate ring for in-flight tool calls. */
@Composable
fun SpinnerRing(modifier: Modifier = Modifier, color: Color = CursorTheme.colors.iconTertiary, size: Dp = 11.dp, strokeWidth: Dp = 1.5.dp) {
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

/** Desktop buttons: ghost (12 % stroke, 4 % fill) and primary (accent fill). 26dp tall, radius 6, 13sp. */
@Composable
fun CursorButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 26.dp,
) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.base
    val fill = when {
        primary -> if (enabled) colors.accent else colors.accent.copy(alpha = 0.4f)
        else -> colors.fillFaint
    }
    val border = if (primary) Color.Transparent else colors.stroke
    val label = when {
        primary -> colors.onAccent
        destructive -> colors.red
        enabled -> colors.textPrimary
        else -> colors.textQuaternary
    }
    Row(
        modifier
            .cursorSurface(fill, border, shape)
            .pressable(onClick, shape, enabled = enabled)
            .height(height)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = label, modifier = Modifier.size(16.dp))
            Box(Modifier.width(4.dp))
        }
        Text(text, style = CursorTheme.typography.base, color = label, maxLines = 1)
    }
}
