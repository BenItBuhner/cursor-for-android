package com.cursorforandroid.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * Text that shimmers while [active]: Cursor's treatment for the verb of a tool call in progress
 * (`ui-tool-call-line-shimmer` in the desktop build). The glyphs themselves are painted with a band of the brighter
 * [highlight] sweeping left to right through the [color] every 1.4s; nothing spins beside the word. When not active
 * it is an ordinary single line of text in [color].
 */
@Composable
fun ShimmerText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    highlight: Color = CursorTheme.colors.textPrimary,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    if (!active) {
        Text(text, style = style, color = color, maxLines = maxLines, overflow = overflow, modifier = modifier)
        return
    }
    var width by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    // The band is as wide as the text and travels from entirely left of it to entirely right of it.
    val w = width.coerceAtLeast(1).toFloat()
    val start = -w + progress * 2f * w
    val brush = Brush.linearGradient(
        colors = listOf(color, highlight, color),
        start = Offset(start, 0f),
        end = Offset(start + w, 0f),
        tileMode = TileMode.Clamp,
    )
    Text(
        text,
        style = style.merge(TextStyle(brush = brush)),
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { width = it.size.width },
        modifier = modifier,
    )
}

private const val SHIMMER_MS = 1400
