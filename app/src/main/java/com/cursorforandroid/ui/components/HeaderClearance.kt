package com.cursorforandroid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.cursorforandroid.ui.theme.CursorDimens
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/**
 * Whether a chat's header needs a band of its own above the transcript.
 *
 * The header's controls stand at the two ends of the pane; the transcript's rows and the composer are one column of
 * at most [CursorDimens.composerMaxWidth], centred. Where the column reaches under the controls — a phone, a narrow
 * pane, a rail open far enough to bring the column to the buttons — the header keeps its band
 * ([CursorDimens.chatHeaderHeight] under the status bar) and the transcript starts under it, its top edge fading
 * there. Where the margins beside the column are wide enough to hold the controls, the band would only cost height
 * and dim the transcript's top for nothing: the transcript runs up to the status bar's edge and the controls float
 * over the empty margin ([headerBand]).
 *
 * The answer comes from where things were laid out, never from a width breakpoint: each group of controls, reaching
 * [ControlReach] past its buttons on either side (as far as their touch targets do), against the transcript's column
 * and the composer, all measured in the window. So it follows the rail while it slides, a rotation, a pane of any
 * width, and the buttons the chat has (a pull request adds one).
 */
@Stable
class HeaderClearance {
    private var start by mutableStateOf<Span?>(null)
    private var end by mutableStateOf<Span?>(null)
    private var transcript by mutableStateOf<Span?>(null)
    private var composer by mutableStateOf<Span?>(null)

    /** Null until the controls and the column have all been laid out; then whether every control stands clear of the column. */
    val isClear: Boolean? by derivedStateOf {
        val controls = listOf(start ?: return@derivedStateOf null, end ?: return@derivedStateOf null)
        val column = listOf(transcript ?: return@derivedStateOf null, composer ?: return@derivedStateOf null)
        !overlaps(controls, column)
    }

    /** On the row holding one end's controls; [reachPx] is [ControlReach] in pixels. */
    fun controls(side: Side, reachPx: Float): Modifier = Modifier.onGloballyPositioned { coordinates ->
        val span = coordinates.span().reaching(reachPx)
        when (side) {
            Side.Start -> start = span
            Side.End -> end = span
        }
    }

    /** On a box laid out across the transcript's rows as they are: same parent, same gutters, same width cap. */
    val transcriptColumn: Modifier = Modifier.onGloballyPositioned { transcript = it.span() }

    /** On the follow-up composer, the widest box docked under the transcript. */
    val composerColumn: Modifier = Modifier.onGloballyPositioned { composer = it.span() }

    enum class Side { Start, End }

    /** A horizontal extent in the window's pixels. */
    internal data class Span(val left: Float, val right: Float) {
        val isEmpty: Boolean get() = right <= left

        fun reaching(px: Float): Span = if (isEmpty) this else Span(left - px, right + px)
    }

    companion object {
        /** How far a header control takes touches past its button on each side: its target is wider than the glyph's box. */
        val ControlReach: Dp = (CursorDimens.touchTarget - CursorDimens.iconButton) / 2

        /** Whether any control (an empty group has none) overlaps any part of the column horizontally. */
        internal fun overlaps(controls: List<Span>, column: List<Span>): Boolean =
            controls.any { control -> !control.isEmpty && column.any { part -> !part.isEmpty && control.left < part.right && part.left < control.right } }

        private fun LayoutCoordinates.span(): Span {
            val left = positionInRoot().x
            return Span(left, left + size.width)
        }
    }
}

/**
 * How far the header has let its band go: 0 with the band, 1 without. The first answer is taken at once, so a chat
 * opening beside wide margins never shows the band leaving; every later change — the rail sliding, a rotation — eases
 * over [HeaderBandMillis], and a change of mind mid-way turns back from where it is.
 */
@Composable
internal fun rememberHeaderBandRelease(clearance: HeaderClearance): State<Float> {
    val release = remember(clearance) { Animatable(0f) }
    LaunchedEffect(clearance, release) {
        var answered = false
        snapshotFlow { clearance.isClear }.filterNotNull().collectLatest { clear ->
            val target = if (clear) 1f else 0f
            if (answered) {
                release.animateTo(target, tween(HeaderBandMillis, easing = FastOutSlowInEasing))
            } else {
                answered = true
                release.snapTo(target)
            }
        }
    }
    return release.asState()
}

/**
 * The header laid out and drawn whole, claiming all of its height from the column with [release] at 0 and, at 1, only
 * what lies above its [CursorDimens.chatHeaderHeight] row — the status bar — so the transcript that follows starts at
 * the bar's edge and the controls stand over its margin. Read while laying out, so the band easing re-lays the column
 * out on each frame without recomposing anything.
 */
internal fun Modifier.headerBand(release: State<Float>): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val released = lerp(0.dp, CursorDimens.chatHeaderHeight, release.value).roundToPx().coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - released) { placeable.place(0, 0) }
}

/** Exposed for tests that step the clock through the band's easing. */
internal const val HeaderBandMillis = 220
