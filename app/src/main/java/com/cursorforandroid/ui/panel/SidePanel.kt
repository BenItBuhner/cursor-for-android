package com.cursorforandroid.ui.panel

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The conversation's right-side panel: a sheet that rests off the end edge and slides in over a scrim, the mirror
 * image of the sidebar drawer ([com.cursorforandroid.ui.components.CursorDrawer]) and driven the same way — one value,
 * how far open it is, whether the sheet is dragged, animated or scrubbed by the predictive back gesture.
 *
 * Closed, only a strip along the end edge takes the drag, so the transcript's own horizontal gestures (and the
 * sidebar drawer's, which owns the whole screen) are untouched; open, the scrim and the sheet take it, so a swipe
 * anywhere puts it away. Back closes the sheet before anything behind it goes back.
 */
@Composable
fun SidePanelHost(
    state: SidePanelState,
    panelWidth: Dp,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    edgeWidth: Dp = EdgeWidth,
    containerColor: Color = CursorTheme.colors.sidebar,
    contentColor: Color = CursorTheme.colors.textPrimary,
    scrimColor: Color = Color.Black.copy(alpha = 0.45f),
    panelContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val widthPx = with(density) { panelWidth.toPx() }
    val flingThreshold = with(density) { FlingThreshold.toPx() } / widthPx
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    SideEffect { state.widthPx = widthPx }

    // The sheet lives at the end edge, so dragging toward the start opens it: the drawer's direction, reversed.
    val drag = Modifier.draggable(
        state = state.draggableState,
        orientation = Orientation.Horizontal,
        enabled = gesturesEnabled,
        reverseDirection = !rtl,
        startDragImmediately = state.isAnimating,
        onDragStopped = { velocity -> state.settle(-velocity / widthPx, flingThreshold) },
    )

    Box(
        modifier
            .fillMaxSize()
            // Closed, only a touch that lands within the end edge's strip and then moves sideways is the panel's;
            // everything else — taps, the transcript's scroll, the sidebar drawer's own swipe — passes untouched.
            .endEdgeDrag(state, scope, enabled = gesturesEnabled && !state.isOpen, edgeWidthPx = with(density) { edgeWidth.toPx() }, rtl = rtl, flingThreshold = flingThreshold),
    ) {
        Box { content() }

        PredictiveBackHandler(enabled = state.isOpen) { events ->
            val start = state.fraction
            try {
                events.collect { state.seek(start * (1f - it.progress)) }
            } catch (_: CancellationException) {
                scope.launch { state.slideTo(SidePanelValue.Open) }
                return@PredictiveBackHandler
            }
            scope.launch { state.slideTo(SidePanelValue.Closed) }
        }

        // Open, or on its way: the scrim covers the content and takes the drag, so the sheet can be swiped shut from anywhere.
        if (state.isOpen || state.fraction > 0f) {
            Scrim(
                onClose = { if (gesturesEnabled) scope.launch { state.close() } },
                fraction = { state.fraction },
                color = scrimColor,
                modifier = drag,
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(panelWidth)
                .offset { IntOffset(((1f - state.fraction) * widthPx).roundToInt(), 0) }
                .then(if (state.isOpen) drag else Modifier)
                .semantics {
                    paneTitle = PanelTitle
                    if (state.isOpen) {
                        dismiss {
                            scope.launch { state.close() }
                            true
                        }
                    }
                },
        ) {
            // Composed only while there is something to see: a closed panel's sections — with their figures and lists —
            // would otherwise be measured and kept up to date off screen for the whole of every chat.
            if (state.isOpen || state.fraction > 0f) {
                Surface(color = containerColor, contentColor = contentColor, shape = RectangleShape, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.End))) { panelContent() }
                }
            }
        }
    }
}

/** The width the panel takes on a screen of [maxWidth]: most of a phone, a column on anything wider. */
fun panelWidthFor(maxWidth: Dp): Dp = minOf(maxWidth - PanelMargin, PanelMaxWidth)

/** A host that sizes the panel to its window; see [SidePanelHost]. */
@Composable
fun SidePanel(
    state: SidePanelState,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    panelContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        SidePanelHost(
            state = state,
            panelWidth = panelWidthFor(maxWidth),
            gesturesEnabled = gesturesEnabled,
            panelContent = panelContent,
            content = content,
        )
    }
}

enum class SidePanelValue { Closed, Open }

/** Where the panel is and where it is heading. Survives configuration changes through [Saver]. */
@Stable
class SidePanelState(initialValue: SidePanelValue) {
    /** 0: the sheet rests off the end edge. 1: fully open. Anything between is a drag, an animation or a gesture. */
    var fraction by mutableFloatStateOf(if (initialValue == SidePanelValue.Open) 1f else 0f)
        private set

    var targetValue by mutableStateOf(initialValue)
        private set

    val isOpen: Boolean get() = targetValue == SidePanelValue.Open

    var isAnimating by mutableStateOf(false)
        private set

    internal var widthPx = 0f

    private val mutex = MutatorMutex()

    suspend fun open() = slideTo(SidePanelValue.Open)

    suspend fun close() = slideTo(SidePanelValue.Closed)

    suspend fun toggle() = if (isOpen) close() else open()

    internal suspend fun slideTo(value: SidePanelValue) {
        val target = value.fraction
        mutex.mutate {
            targetValue = value
            val distance = abs(target - fraction)
            if (distance < 0.001f) {
                fraction = target
                return@mutate
            }
            val millis = (SlideMillis * distance).roundToInt().coerceIn(MinSlideMillis, SlideMillis)
            runAnimation { animate(fraction, target, animationSpec = tween(millis, easing = SlideEasing)) { v, _ -> fraction = v } }
        }
    }

    /** Puts the sheet at [value] at once, no animation, cancelling one in flight. */
    suspend fun snapTo(value: SidePanelValue) {
        mutex.mutate(MutatePriority.UserInput) {
            targetValue = value
            fraction = value.fraction
        }
    }

    internal suspend fun seek(fraction: Float) {
        mutex.mutate(MutatePriority.UserInput) { this.fraction = fraction.coerceIn(0f, 1f) }
    }

    /** The finger lifted with [velocity] (sheet widths per second, positive toward open). */
    internal suspend fun settle(velocity: Float, flingThreshold: Float) {
        val value = when {
            velocity > flingThreshold -> SidePanelValue.Open
            velocity < -flingThreshold -> SidePanelValue.Closed
            fraction >= 0.5f -> SidePanelValue.Open
            else -> SidePanelValue.Closed
        }
        mutex.mutate {
            targetValue = value
            runAnimation {
                animate(fraction, value.fraction, initialVelocity = velocity, animationSpec = FlingSpec) { v, _ -> fraction = v.coerceIn(0f, 1f) }
            }
        }
    }

    private suspend inline fun runAnimation(block: () -> Unit) {
        isAnimating = true
        try {
            block()
        } finally {
            isAnimating = false
        }
    }

    internal val draggableState: DraggableState = object : DraggableState {
        private val dragScope = object : DragScope {
            override fun dragBy(pixels: Float) {
                if (widthPx > 0f) fraction = (fraction + pixels / widthPx).coerceIn(0f, 1f)
            }
        }

        override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
            mutex.mutate(dragPriority) { dragScope.block() }
        }

        override fun dispatchRawDelta(delta: Float) = dragScope.dragBy(delta)
    }

    companion object {
        val Saver: Saver<SidePanelState, SidePanelValue> = Saver(save = { it.targetValue }, restore = { SidePanelState(it) })
    }
}

@Composable
fun rememberSidePanelState(initialValue: SidePanelValue = SidePanelValue.Closed): SidePanelState =
    rememberSaveable(saver = SidePanelState.Saver) { SidePanelState(initialValue) }

private val SidePanelValue.fraction: Float get() = if (this == SidePanelValue.Open) 1f else 0f

/**
 * The closed panel's gesture: a pointer that comes down within [edgeWidthPx] of the end edge and then travels
 * sideways past touch slop drags the sheet in; the finger's velocity decides where it settles. A pointer anywhere
 * else, or one that moves vertically first (the transcript's scroll), is never touched. The down is seen on the
 * initial pass so no child can hide it, but nothing is consumed until the drag is unmistakably the panel's.
 */
private fun Modifier.endEdgeDrag(state: SidePanelState, scope: CoroutineScope, enabled: Boolean, edgeWidthPx: Float, rtl: Boolean, flingThreshold: Float): Modifier =
    if (!enabled) this else pointerInput(state, edgeWidthPx, rtl, flingThreshold) {
        val tracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val nearEdge = if (rtl) down.position.x <= edgeWidthPx else down.position.x >= size.width - edgeWidthPx
            if (!nearEdge) return@awaitEachGesture
            var overslop = 0f
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                overslop = over
            } ?: return@awaitEachGesture
            tracker.resetTracking()
            tracker.addPosition(start.uptimeMillis, start.position)
            // Toward the start edge opens: the sheet's travel is the finger's, sign-flipped in LTR.
            // Raw deltas rather than a held drag: the gesture scope cannot suspend on the state's mutex, and a sheet
            // that was closed and still has nothing animating to contend with.
            val sign = if (rtl) 1f else -1f
            state.draggableState.dispatchRawDelta(sign * overslop)
            horizontalDrag(start.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                state.draggableState.dispatchRawDelta(sign * change.positionChange().x)
                change.consume()
            }
            val velocity = sign * tracker.calculateVelocity().x / state.widthPx.coerceAtLeast(1f)
            // Settled on the composition's scope: the animation must not hold up the next gesture's detection.
            scope.launch { state.settle(velocity, flingThreshold) }
        }
    }

@Composable
private fun Scrim(onClose: () -> Unit, fraction: () -> Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(onClose) { detectTapGestures { onClose() } }
            .semantics(mergeDescendants = true) {
                contentDescription = ClosePanel
                onClick {
                    onClose()
                    true
                }
            },
    ) { drawRect(color, alpha = fraction()) }
}

private const val PanelTitle = "Conversation panel"
private const val ClosePanel = "Dismiss panel"

/** How wide the strip along the end edge that opens the panel is. */
private val EdgeWidth = 20.dp
/** What the panel leaves of the screen beside it, so the transcript stays visible as context. */
private val PanelMargin = 48.dp
private val PanelMaxWidth = 400.dp

private const val SlideMillis = 300
private const val MinSlideMillis = 100
private val SlideEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val FlingThreshold = 400.dp
private val FlingSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1000f, visibilityThreshold = 0.0005f)
