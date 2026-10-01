package com.cursorforandroid.ui.conversation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The arrow of Material's pull-to-refresh indicator, drawn as Material 1.3's `PullToRefreshDefaults.Indicator` draws
 * it, which keeps it private: its arc fills from 40% of the way to the threshold, whole at [progress] 1, where it turns
 * from faint to solid — the pull armed — and winds on past it under Material's own tension. Kept stroke for stroke
 * with Material's, so the chat's indicator stays the sidebar's.
 */
@Composable
internal fun CatchUpArrow(progress: () -> Float, color: Color) {
    val path = remember { Path().apply { fillType = PathFillType.EvenOdd } }
    val targetAlpha by remember { derivedStateOf { if (progress() >= 1f) ArrowArmedAlpha else ArrowFaintAlpha } }
    val alpha = animateFloatAsState(targetAlpha, tween(ArrowAlphaMillis, easing = FastOutSlowInEasing), label = "catch-up-arrow-alpha")
    Canvas(
        Modifier
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(progress(), 0f..1f, 0) }
            .size(ArrowCanvasSize),
    ) {
        val values = ArrowValues(progress())
        rotate(degrees = values.rotation) {
            val arcRadius = ArrowArcRadius.toPx() + CatchUpStrokeWidth.toPx() / 2f
            val arcBounds = Rect(center = size.center, radius = arcRadius)
            drawArc(
                color = color,
                alpha = alpha.value,
                startAngle = values.startAngle,
                sweepAngle = values.endAngle - values.startAngle,
                useCenter = false,
                topLeft = arcBounds.topLeft,
                size = arcBounds.size,
                style = Stroke(width = CatchUpStrokeWidth.toPx(), cap = StrokeCap.Butt),
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
    arrow.translate(Offset(x = radius + bounds.center.x - width / 2f, y = bounds.center.y - CatchUpStrokeWidth.toPx()))
    rotate(degrees = values.endAngle - CatchUpStrokeWidth.toPx()) {
        drawPath(path = arrow, color = color, alpha = alpha, style = Stroke(CatchUpStrokeWidth.toPx()))
    }
}

private const val ArrowMaxArc = 0.8f
private val ArrowArcRadius = 5.5.dp
private val ArrowCanvasSize = 16.dp
private val ArrowHeadWidth = 10.dp
private val ArrowHeadHeight = 5.dp
private const val ArrowFaintAlpha = 0.3f
private const val ArrowArmedAlpha = 1f
private const val ArrowAlphaMillis = 300
