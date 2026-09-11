package com.cursorforandroid.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.cursorforandroid.ui.theme.CursorDimens

/**
 * Dissolves a list's content into the surface behind it at the top and bottom of its viewport, instead of cutting
 * it off flat where the container clips. Each edge fades only while the list has more content past it and eases
 * back to a hard edge as the reader reaches the end, so the first and last rows are never dimmed when they are
 * fully in view.
 *
 * The list is composited offscreen and its alpha multiplied with a vertical gradient (`DstIn`), so the fade is a
 * true dissolve rather than a painted-over strip: it reveals whatever the container draws behind it, and overlays
 * placed on top of the list (pull-to-refresh, scroll-to-bottom) are unaffected.
 *
 * @param reverseLayout mirror of the list's own flag: in a bottom-anchored transcript item 0 sits at the bottom, so
 *   "more items ahead" means content clipped at the top.
 */
@Composable
fun Modifier.scrollEdgeFade(
    state: LazyListState,
    reverseLayout: Boolean = false,
    fadeHeight: Dp = CursorDimens.scrollFade,
): Modifier {
    val clippedAtTop = if (reverseLayout) state.canScrollForward else state.canScrollBackward
    val clippedAtBottom = if (reverseLayout) state.canScrollBackward else state.canScrollForward
    return scrollEdgeFade(clippedAtTop = clippedAtTop, clippedAtBottom = clippedAtBottom, fadeHeight = fadeHeight)
}

@Composable
fun Modifier.scrollEdgeFade(clippedAtTop: Boolean, clippedAtBottom: Boolean, fadeHeight: Dp = CursorDimens.scrollFade): Modifier {
    val top by animateFloatAsState(if (clippedAtTop) 1f else 0f, tween(FADE_DURATION_MS), label = "topFade")
    val bottom by animateFloatAsState(if (clippedAtBottom) 1f else 0f, tween(FADE_DURATION_MS), label = "bottomFade")
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // Both fades must fit without overlapping, or a short viewport would dim its middle.
            val depth = fadeHeight.toPx().coerceAtMost(size.height / 2f)
            if (depth <= 0f) return@drawWithContent
            if (top > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(*edgeStops(top, outerFirst = true), startY = 0f, endY = depth),
                    size = Size(size.width, depth),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottom > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(*edgeStops(bottom, outerFirst = false), startY = size.height - depth, endY = size.height),
                    topLeft = Offset(0f, size.height - depth),
                    size = Size(size.width, depth),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/**
 * [scrollEdgeFade] for content that scrolls sideways — a table wider than the message: each side dissolves while
 * there is more table past it, so a flat cut at the card edge never passes for the last column.
 */
@Composable
fun Modifier.horizontalScrollEdgeFade(clippedAtStart: Boolean, clippedAtEnd: Boolean, fadeWidth: Dp = CursorDimens.scrollFade): Modifier {
    val start by animateFloatAsState(if (clippedAtStart) 1f else 0f, tween(FADE_DURATION_MS), label = "startFade")
    val end by animateFloatAsState(if (clippedAtEnd) 1f else 0f, tween(FADE_DURATION_MS), label = "endFade")
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val depth = fadeWidth.toPx().coerceAtMost(size.width / 2f)
            if (depth <= 0f) return@drawWithContent
            val rtl = layoutDirection == LayoutDirection.Rtl
            val left = if (rtl) end else start
            val right = if (rtl) start else end
            if (left > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(*edgeStops(left, outerFirst = true), startX = 0f, endX = depth),
                    size = Size(depth, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (right > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(*edgeStops(right, outerFirst = false), startX = size.width - depth, endX = size.width),
                    topLeft = Offset(size.width - depth, 0f),
                    size = Size(depth, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/**
 * Alpha mask stops for one edge at [strength] (0 = no fade, 1 = fully transparent at the edge). The curve eases out
 * from the edge so the dissolve starts fast and settles gently into fully opaque content, which reads smoother than
 * a straight linear ramp of the same depth.
 */
private fun edgeStops(strength: Float, outerFirst: Boolean): Array<Pair<Float, Color>> {
    val mask = { keep: Float -> Color.Black.copy(alpha = 1f - strength * (1f - keep)) }
    val stops = arrayOf(0f to mask(0f), 0.45f to mask(0.6f), 0.75f to mask(0.9f), 1f to mask(1f))
    return if (outerFirst) stops else Array(stops.size) { i -> (1f - stops[stops.lastIndex - i].first) to stops[stops.lastIndex - i].second }
}

private const val FADE_DURATION_MS = 180
