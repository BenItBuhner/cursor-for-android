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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.LocalScrollFadeSurface
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The conversation's right-side panel: a sheet that rests off the end edge and slides in over a scrim, the mirror
 * image of the sidebar drawer ([com.cursorforandroid.ui.components.CursorDrawer]) and driven the same way — one value,
 * how far open it is, whether the sheet is dragged, animated or scrubbed by the predictive back gesture. A drag moves
 * the sheet with the finger; letting go commits on a fling past the threshold or on having passed half-way, and
 * otherwise settles back, on the drawer's spring.
 *
 * Closed, a drag toward the start edge anywhere on the content pulls the sheet in, once nothing inside has claimed it:
 * a horizontal scroller (a code block, a table, a row of attachments) scrolls until it reaches its end and only then
 * hands the rest of the drag over, a finger held before it moves is a selection or a message's menu, and a drag toward
 * the end edge is left to the sidebar drawer. Open, the scrim and the sheet take the drag, so a swipe anywhere puts it
 * away, the sheet's own scrollers handing over at their ends the same way. Back closes the sheet before anything behind
 * it goes back.
 */
@Composable
fun SidePanelHost(
    state: SidePanelState,
    panelWidth: Dp,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
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
    val opening = remember(state, scope) { ScrollerHandoff(state, scope, from = SidePanelValue.Closed) }
    val closing = remember(state, scope) { ScrollerHandoff(state, scope, from = SidePanelValue.Open) }
    SideEffect {
        state.widthPx = widthPx
        opening.update(gesturesEnabled, rtl, flingThreshold)
        closing.update(gesturesEnabled, rtl, flingThreshold)
    }

    // The sheet lives at the end edge, so dragging toward the start opens it: the drawer's direction, reversed. The
    // release velocity arrives in the same reversed frame, positive toward open, which is what `settle` takes. It
    // settles on the host's scope, not the one `draggable` calls back on: that belongs to the node that took the drag,
    // and the slide has to outlive it — the scrim leaves the composition as the sheet shuts.
    val drag = Modifier.draggable(
        state = state.draggableState,
        orientation = Orientation.Horizontal,
        enabled = gesturesEnabled,
        reverseDirection = !rtl,
        startDragImmediately = state.isAnimating,
        onDragStopped = { velocity -> scope.launch { state.settle(velocity / widthPx, flingThreshold) } },
    )

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .nestedScroll(opening)
                .openDrag(state, scope, enabled = gesturesEnabled && !state.isOpen, rtl = rtl, flingThreshold = flingThreshold),
        ) { content() }

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
        if (state.isVisible) {
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
                .nestedScroll(closing)
                // Open or not, as the drawer's: a sheet caught mid-slide either way follows the finger.
                .then(drag)
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
            // would otherwise be measured and kept up to date off screen for the whole of every chat. The surface runs
            // edge to edge, under the status bar and the navigation bar alike; the content insets itself
            // (`panelInsetPadding` at the panel's root), so the one consumption is the content's whichever host it is in.
            if (state.isVisible) {
                Surface(color = containerColor, contentColor = contentColor, shape = RectangleShape, modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalScrollFadeSurface provides containerColor, content = panelContent)
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

    /**
     * Open, or anywhere on its way in or out: whether there is a sheet to compose. It changes as a slide begins and as
     * it ends, not with every frame of it, so the host reads it without recomposing all the way through a drag.
     */
    val isVisible: Boolean by derivedStateOf { isOpen || fraction > 0f }

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
 * The closed panel's gesture on the content: a finger that travels toward the start edge past touch slop pulls the
 * sheet in, and its velocity decides where it settles. Watched on the main pass, after the content's own handlers, and
 * only on a pointer none of them has consumed: a horizontal scroller that took the drag keeps it (what it cannot scroll
 * comes through [ScrollerHandoff]), and so does the transcript's vertical scroll. A drag toward the end edge is left
 * unconsumed for the sidebar drawer around the chat, and so is one that only starts once the finger has been held past
 * the long-press timeout — a text selection or a message's menu, never the panel.
 *
 * The velocity is the finger's from every sample it made, each event's history included, as `draggable` reads it. A
 * frame the app is slow to finish — the sheet's first, while its contents are composed — holds up the samples that
 * came in meanwhile, and Android hands them over at once as the history of one move: the event alone would say the
 * finger covered the whole way in an instant after standing still, and the fling that should open the sheet would
 * read as none at all, leaving it to settle back shut from where the lump of travel put it.
 */
private fun Modifier.openDrag(state: SidePanelState, scope: CoroutineScope, enabled: Boolean, rtl: Boolean, flingThreshold: Float): Modifier =
    if (!enabled) this else pointerInput(state, rtl, flingThreshold) {
        val tracker = VelocityTracker()
        // Toward the start edge opens: the sheet's travel is the finger's, sign-flipped in LTR.
        val sign = if (rtl) 1f else -1f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            tracker.addPointerInputChange(down)
            val slop = viewConfiguration.touchSlop
            var travel = 0f
            var start: PointerInputChange? = null
            while (start == null) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                tracker.addPointerInputChange(change)
                travel += change.positionChange().x
                if (abs(travel) < slop) continue
                val held = change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                if (sign * travel <= 0f || held || state.isAnimating || state.fraction > 0f) return@awaitEachGesture
                change.consume()
                start = change
            }
            // Raw deltas rather than a held drag: the gesture scope cannot suspend on the state's mutex, and a sheet at
            // rest closed has nothing animating to contend with.
            state.draggableState.dispatchRawDelta(sign * (travel - slop * travel.sign))
            var velocity = 0f
            try {
                val lifted = horizontalDrag(start.id) { change ->
                    tracker.addPointerInputChange(change)
                    state.draggableState.dispatchRawDelta(sign * change.positionChange().x)
                    change.consume()
                }
                // The lift itself: a finger that stopped before it let go flings nothing.
                if (lifted) currentEvent.changes.firstOrNull { it.changedToUpIgnoreConsumed() }?.let(tracker::addPointerInputChange)
                val maxVelocity = viewConfiguration.maximumFlingVelocity
                velocity = sign * tracker.calculateVelocity().x.coerceIn(-maxVelocity, maxVelocity) / state.widthPx.coerceAtLeast(1f)
            } finally {
                // Settled on the composition's scope: the animation must not hold up the next gesture's detection.
                scope.launch { state.settle(velocity, flingThreshold) }
            }
        }
    }

/**
 * What a horizontal scroller inside cannot use of a drag moves the sheet: a code block, a table or a row of
 * attachments scrolls first, and once it is at its end the rest of the same drag pulls the sheet — open when it rests
 * [from] closed, shut when it rests open. From then until the finger lifts the sheet has the drag first, either way,
 * until it is back where it rested, when the scroller has it again; the release settles it like any other drag. Only
 * the finger's own scrolling hands over: a scroller's fling running into its end never moves the sheet.
 */
private class ScrollerHandoff(private val state: SidePanelState, private val scope: CoroutineScope, private val from: SidePanelValue) : NestedScrollConnection {
    private var enabled = false
    /** The finger's travel that opens the sheet: toward the start edge. */
    private var openSign = -1f
    private var flingThreshold = 0f
    private var active = false

    fun update(enabled: Boolean, rtl: Boolean, flingThreshold: Float) {
        this.enabled = enabled
        this.openSign = if (rtl) 1f else -1f
        this.flingThreshold = flingThreshold
    }

    private val rest: Float get() = from.fraction
    private val atRest: Boolean get() = state.fraction == rest

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (active && source == NestedScrollSource.UserInput && !atRest) move(available.x) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
        // At rest the hand-over is decided afresh, so a drag whose fling never came (its scroller left the composition
        // under it) cannot carry over into the next one.
        if (atRest) {
            val awayFromRest = if (from == SidePanelValue.Closed) openSign * available.x > 0f else openSign * available.x < 0f
            active = enabled && state.targetValue == from && !state.isAnimating && awayFromRest
        }
        return if (active) move(available.x) else Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!active) return Velocity.Zero
        active = false
        if (atRest) return Velocity.Zero
        val velocity = openSign * available.x / state.widthPx.coerceAtLeast(1f)
        scope.launch { state.settle(velocity, flingThreshold) }
        return Velocity(available.x, 0f)
    }

    /** Moves the sheet by as much of the finger's [dx] as it has room for, and answers how much of [dx] that was. */
    private fun move(dx: Float): Offset {
        val before = state.fraction
        state.draggableState.dispatchRawDelta(openSign * dx)
        return Offset(openSign * (state.fraction - before) * state.widthPx, 0f)
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

/** What the panel leaves of the screen beside it, so the transcript stays visible as context. */
private val PanelMargin = 48.dp
private val PanelMaxWidth = 400.dp

private const val SlideMillis = 300
private const val MinSlideMillis = 100
private val SlideEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val FlingThreshold = 400.dp
private val FlingSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1000f, visibilityThreshold = 0.0005f)
