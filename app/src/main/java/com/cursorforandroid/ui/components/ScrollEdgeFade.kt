package com.cursorforandroid.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens

/**
 * The flat colour of the container a vertically scrolling viewport fills — what [scrollEdgeFade] and
 * [fadingVerticalScroll] paint their edges in when the call site names none. Provided by the containers that paint
 * one flat colour behind everything they hold: every [CursorSheet] (the elevated surface) and the conversation's side
 * panel (the sidebar surface). Unprovided it is unspecified, and a fade dissolves offscreen into whatever is behind,
 * which is right over any background: a popup, a dialog, a card. A screen on the canvas names the canvas itself.
 */
val LocalScrollFadeSurface = staticCompositionLocalOf { Color.Unspecified }

/**
 * Dissolves a list's content into the surface behind it at the top and bottom of its viewport, instead of cutting
 * it off flat where the container clips. Each edge fades only while the list has more content past it and eases
 * back to a hard edge as the reader reaches the end, so the first and last rows are never dimmed when they are
 * fully in view. Every vertically scrolling surface of the app wears it — a lazy list or grid through this modifier
 * or [FadingLazyColumn], a scrolling column through [fadingVerticalScroll] in place of `verticalScroll` — and
 * `ScrollEdgeFadeCoverageTest` fails the build on one that does not.
 *
 * Place it after the modifiers that size the viewport (and after any inset padding, so the bottom edge fades above
 * the navigation bar rather than behind it) and before the scroll itself, so it draws over the viewport rather than
 * over the scrolled content.
 *
 * There are two ways to paint it, and which one a list gets is decided by [surface]:
 *
 * - Unspecified (the default): the list is composited offscreen and its alpha multiplied with a vertical gradient
 *   (`DstIn`), a true dissolve that reveals whatever the container draws behind it, whatever that is. The offscreen
 *   layer is a texture the size of the whole list, re-rendered whenever the list redraws and re-allocated whenever
 *   the list changes size, which is fine for a list that only ever scrolls.
 * - A colour: the same gradient is painted over the list's edges in that colour (`SrcOver`), with no offscreen layer
 *   at all. Over a container that is flat [surface] the two are pixel for pixel the same picture — the content's
 *   share at each stop is identical — but this one costs a strip of gradient per frame instead of a full-list
 *   texture. It is the one for a list whose viewport is resized every frame: the transcript and the New Chat pane
 *   shrink and grow with the keyboard over ~150 ms at up to 120 Hz, and a dissolve there means a fresh full-screen
 *   layer allocation and a complete re-render of the list on each of those frames, which is what dropped them.
 *
 * Either way overlays placed on top of the list (pull-to-refresh, scroll-to-bottom) are drawn after it and are
 * unaffected.
 *
 * @param state the list's, grid's or column's scroll state: whether it can scroll either way is what turns each edge on.
 * @param reverseLayout mirror of the list's own flag: in a bottom-anchored transcript item 0 sits at the bottom, so
 *   "more items ahead" means content clipped at the top.
 * @param surface the flat colour the container paints behind the list, for the painted fade; unspecified for the
 *   offscreen dissolve. [LocalScrollFadeSurface] when not given.
 */
@Composable
fun Modifier.scrollEdgeFade(
    state: ScrollableState,
    reverseLayout: Boolean = false,
    fadeHeight: Dp = CursorDimens.scrollFade,
    surface: Color = LocalScrollFadeSurface.current,
): Modifier {
    val clippedAtTop = if (reverseLayout) state.canScrollForward else state.canScrollBackward
    val clippedAtBottom = if (reverseLayout) state.canScrollBackward else state.canScrollForward
    return scrollEdgeFade(clippedAtTop = clippedAtTop, clippedAtBottom = clippedAtBottom, fadeHeight = fadeHeight, surface = surface)
}

/**
 * `verticalScroll` with the edge fade: the one way a column scrolls in this app, so none of them cuts its content off
 * flat under a header or above the navigation bar. Chain it where `verticalScroll` would go — after the viewport's
 * size and insets, before the content's own padding.
 */
@Composable
fun Modifier.fadingVerticalScroll(
    state: ScrollState = rememberScrollState(),
    enabled: Boolean = true,
    surface: Color = LocalScrollFadeSurface.current,
): Modifier = scrollEdgeFade(state, surface = surface).verticalScroll(state, enabled)

/**
 * A [LazyColumn] that wears the edge fade, for the lists whose scroll state nothing else needs: the fade goes last on
 * [modifier], over the list's viewport. A list whose modifier scrolls sideways as well puts [scrollEdgeFade] ahead of
 * that scroll itself instead, so the fade stays on the viewport rather than riding along with the content.
 */
@Composable
fun FadingLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    surface: Color = LocalScrollFadeSurface.current,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.scrollEdgeFade(state, surface = surface),
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

@Composable
fun Modifier.scrollEdgeFade(
    clippedAtTop: Boolean,
    clippedAtBottom: Boolean,
    fadeHeight: Dp = CursorDimens.scrollFade,
    surface: Color = LocalScrollFadeSurface.current,
): Modifier {
    val top by animateFloatAsState(if (clippedAtTop) 1f else 0f, tween(FADE_DURATION_MS), label = "topFade")
    val bottom by animateFloatAsState(if (clippedAtBottom) 1f else 0f, tween(FADE_DURATION_MS), label = "bottomFade")
    val painted = surface.isSpecified
    val blend = if (painted) BlendMode.SrcOver else BlendMode.DstIn
    return this
        .then(if (painted) Modifier else Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen })
        .drawWithContent {
            drawContent()
            // Both fades must fit without overlapping, or a short viewport would dim its middle.
            val depth = fadeHeight.toPx().coerceAtMost(size.height / 2f)
            if (depth <= 0f) return@drawWithContent
            if (top > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(*edgeStops(top, outerFirst = true, surface), startY = 0f, endY = depth),
                    size = Size(size.width, depth),
                    blendMode = blend,
                )
            }
            if (bottom > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(*edgeStops(bottom, outerFirst = false, surface), startY = size.height - depth, endY = size.height),
                    topLeft = Offset(0f, size.height - depth),
                    size = Size(size.width, depth),
                    blendMode = blend,
                )
            }
        }
}

/**
 * [scrollEdgeFade] for a lazy row — the attachments of a prompt: each end dissolves while the row has more past it,
 * so a chip cut by the edge reads as more to come rather than the last one. [surface] as for the vertical fade.
 */
@Composable
fun Modifier.horizontalScrollEdgeFade(state: LazyListState, fadeWidth: Dp = CursorDimens.scrollFade, surface: Color = Color.Unspecified): Modifier =
    horizontalScrollEdgeFade(clippedAtStart = state.canScrollBackward, clippedAtEnd = state.canScrollForward, fadeWidth = fadeWidth, surface = surface)

/**
 * [scrollEdgeFade] for content that scrolls sideways — a table wider than the message, a row of attachments wider
 * than the composer: each side dissolves while there is more past it, so a flat cut at the edge never passes for
 * the last column. Painted over in [surface] when one is given, as the vertical fade is (see there for the two ways
 * and their cost); dissolved offscreen into whatever is behind otherwise.
 */
@Composable
fun Modifier.horizontalScrollEdgeFade(clippedAtStart: Boolean, clippedAtEnd: Boolean, fadeWidth: Dp = CursorDimens.scrollFade, surface: Color = Color.Unspecified): Modifier {
    val start by animateFloatAsState(if (clippedAtStart) 1f else 0f, tween(FADE_DURATION_MS), label = "startFade")
    val end by animateFloatAsState(if (clippedAtEnd) 1f else 0f, tween(FADE_DURATION_MS), label = "endFade")
    val painted = surface.isSpecified
    val blend = if (painted) BlendMode.SrcOver else BlendMode.DstIn
    return this
        .then(if (painted) Modifier else Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen })
        .drawWithContent {
            drawContent()
            val depth = fadeWidth.toPx().coerceAtMost(size.width / 2f)
            if (depth <= 0f) return@drawWithContent
            val rtl = layoutDirection == LayoutDirection.Rtl
            val left = if (rtl) end else start
            val right = if (rtl) start else end
            if (left > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(*edgeStops(left, outerFirst = true, surface), startX = 0f, endX = depth),
                    size = Size(depth, size.height),
                    blendMode = blend,
                )
            }
            if (right > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(*edgeStops(right, outerFirst = false, surface), startX = size.width - depth, endX = size.width),
                    topLeft = Offset(size.width - depth, 0f),
                    size = Size(depth, size.height),
                    blendMode = blend,
                )
            }
        }
}

/**
 * Gradient stops for one edge at [strength] (0 = no fade, 1 = fully faded at the edge). The curve eases out from the
 * edge so the dissolve starts fast and settles gently into fully opaque content, which reads smoother than a straight
 * linear ramp of the same depth.
 *
 * With [surface] unspecified the stops are an alpha mask for `DstIn`: how much of the content is kept. With a colour
 * they are that colour at the complementary alpha, for painting over the content with `SrcOver`: how much of it is
 * covered. Composited over a flat [surface], the two leave the content with the same share at every stop.
 */
private fun edgeStops(strength: Float, outerFirst: Boolean, surface: Color = Color.Unspecified): Array<Pair<Float, Color>> {
    val mask = { keep: Float ->
        val faded = strength * (1f - keep)
        if (surface.isSpecified) surface.copy(alpha = faded) else Color.Black.copy(alpha = 1f - faded)
    }
    val stops = arrayOf(0f to mask(0f), 0.45f to mask(0.6f), 0.75f to mask(0.9f), 1f to mask(1f))
    return if (outerFirst) stops else Array(stops.size) { i -> (1f - stops[stops.lastIndex - i].first) to stops[stops.lastIndex - i].second }
}

private const val FADE_DURATION_MS = 180
