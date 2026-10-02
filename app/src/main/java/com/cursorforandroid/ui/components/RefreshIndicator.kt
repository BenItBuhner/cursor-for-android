package com.cursorforandroid.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefreshIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The pull-to-refresh indicator every pull in the app shows — the sidebar's pull down for the list, the transcript's
 * pull up to catch up (`CatchUpIndicator`): Material 1.3's `PullToRefreshDefaults.Indicator`, its 40dp disc in
 * Material's container colour, Material's arrow filling with the pull and Material's spinner once it is answered, the
 * hundred-millisecond fade between them — drawn as the app's cards are ([dockedCard]: a queued follow-up, a goal), on
 * a hairline of `strokeSubtle` and with no shadow. Material tells its disc from the surface under it by the shadow
 * alone, and the container colour is the theme's `elevated`: the sidebar's own colour, and a shade off the chat
 * canvas, where the shadow hardly read in the dark. The hairline is what shows the disc now, in dark as in light.
 *
 * This is the one for a [androidx.compose.material3.pulltorefresh.PullToRefreshBox]'s `indicator` slot: the box and
 * its motion are Material's own ([pullToRefreshIndicator], with its elevation at nought). Material keeps the arrow
 * private, so it is drawn here stroke for stroke ([RefreshArrow]); another indicator stands in the same disc with
 * [refreshDiscOutline] and [RefreshIndicatorContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshIndicator(state: PullToRefreshState, isRefreshing: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .pullToRefreshIndicator(state, isRefreshing, containerColor = PullToRefreshDefaults.containerColor, elevation = 0.dp)
            .refreshDiscOutline()
            // Inside Material's layer, so the tag's bounds are where the disc is drawn, not where it is laid out.
            .testTag(REFRESH_INDICATOR_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        RefreshIndicatorContent(isRefreshing, progress = { state.distanceFraction })
    }
}

/** The outline that tells the disc from the surface it is pulled over: the app's cards' own hairline ([dockedCard]). */
@Composable
fun Modifier.refreshDiscOutline(): Modifier = border(CursorDimens.hairline, CursorTheme.colors.strokeSubtle, CircleShape)

/**
 * What stands in the disc: Material's spinner while [refreshing], its arrow filling with [progress] (1 at the
 * threshold) otherwise, faded from one to the other over Material's hundred milliseconds, both in [color].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshIndicatorContent(refreshing: Boolean, progress: () -> Float, color: Color = PullToRefreshDefaults.indicatorColor) {
    Crossfade(targetState = refreshing, animationSpec = tween(RefreshCrossfadeMillis, easing = FastOutSlowInEasing), label = "refresh-indicator") { spinning ->
        if (spinning) {
            CircularProgressIndicator(strokeWidth = RefreshStrokeWidth, color = color, modifier = Modifier.size(RefreshSpinnerSize))
        } else {
            RefreshArrow(progress, color)
        }
    }
}

/**
 * The arrow of Material's pull-to-refresh indicator, drawn as Material 1.3's `PullToRefreshDefaults.Indicator` draws
 * it, which keeps it private: its arc fills from 40% of the way to the threshold, whole at [progress] 1, where it turns
 * from faint to solid — the pull armed — and winds on past it under Material's own tension. Kept stroke for stroke
 * with Material's.
 */
@Composable
internal fun RefreshArrow(progress: () -> Float, color: Color) {
    val path = remember { Path().apply { fillType = PathFillType.EvenOdd } }
    val targetAlpha by remember { derivedStateOf { if (progress() >= 1f) ArrowArmedAlpha else ArrowFaintAlpha } }
    val alpha = animateFloatAsState(targetAlpha, tween(ArrowAlphaMillis, easing = FastOutSlowInEasing), label = "refresh-arrow-alpha")
    Canvas(
        Modifier
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(progress(), 0f..1f, 0) }
            .size(RefreshSpinnerSize),
    ) {
        val values = ArrowValues(progress())
        rotate(degrees = values.rotation) {
            val arcRadius = ArrowArcRadius.toPx() + RefreshStrokeWidth.toPx() / 2f
            val arcBounds = Rect(center = size.center, radius = arcRadius)
            drawArc(
                color = color,
                alpha = alpha.value,
                startAngle = values.startAngle,
                sweepAngle = values.endAngle - values.startAngle,
                useCenter = false,
                topLeft = arcBounds.topLeft,
                size = arcBounds.size,
                style = Stroke(width = RefreshStrokeWidth.toPx(), cap = StrokeCap.Butt),
            )
            drawArrowHead(path, arcBounds, color, alpha.value, values)
        }
    }
}

private class ArrowValues(val rotation: Float, val startAngle: Float, val endAngle: Float, val scale: Float)

private fun ArrowValues(progress: Float): ArrowValues {
    val adjustedPercent = max(min(1f, progress) - 0.4f, 0f) * 5 / 3
    val overshootPercent = abs(progress) - 1.0f
    val linearTension = overshootPercent.coerceIn(0f, 2f)
    val tensionPercent = linearTension - linearTension.pow(2) / 4
    val endTrim = adjustedPercent * ArrowMaxArc
    val rotation = (-0.25f + 0.4f * adjustedPercent + tensionPercent) * 0.5f
    val startAngle = rotation * 360
    val endAngle = (rotation + endTrim) * 360
    return ArrowValues(rotation, startAngle, endAngle, min(1f, adjustedPercent))
}

private fun DrawScope.drawArrowHead(arrow: Path, bounds: Rect, color: Color, alpha: Float, values: ArrowValues) {
    val width = ArrowHeadWidth.toPx() * values.scale
    arrow.reset()
    arrow.moveTo(0f, 0f)
    arrow.lineTo(x = width / 2, y = ArrowHeadHeight.toPx() * values.scale)
    arrow.lineTo(x = width, y = 0f)
    val radius = min(bounds.width, bounds.height) / 2f
    arrow.translate(Offset(x = radius + bounds.center.x - width / 2f, y = bounds.center.y - RefreshStrokeWidth.toPx()))
    rotate(degrees = values.endAngle - RefreshStrokeWidth.toPx()) {
        drawPath(path = arrow, color = color, alpha = alpha, style = Stroke(RefreshStrokeWidth.toPx()))
    }
}

/** Material's pull-to-refresh indicator's own measures (`PullToRefreshDefaults.Indicator`). */
val RefreshIndicatorSize = 40.dp
internal val RefreshSpinnerSize = 16.dp
internal val RefreshStrokeWidth = 2.5.dp
private const val RefreshCrossfadeMillis = 100

private const val ArrowMaxArc = 0.8f
private val ArrowArcRadius = 5.5.dp
private val ArrowHeadWidth = 10.dp
private val ArrowHeadHeight = 5.dp
private const val ArrowFaintAlpha = 0.3f
private const val ArrowArmedAlpha = 1f
private const val ArrowAlphaMillis = 300

const val REFRESH_INDICATOR_TEST_TAG = "refresh-indicator"
