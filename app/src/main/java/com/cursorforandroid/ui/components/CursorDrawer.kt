package com.cursorforandroid.ui.components

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import kotlinx.coroutines.launch

/**
 * The phone layout's sidebar drawer: a sheet the width of the sidebar that rests off the start edge and slides in over
 * a scrim. One value — how far open the sheet is — drives everything, whether the sheet is being dragged, animated
 * open or closed, or scrubbed by the Android 14+ predictive back gesture. The gesture moves the sheet back toward its
 * edge in step with the finger and nothing else: no shrinking, and the same motion whichever edge the swipe came from.
 * Letting go commits or rewinds the slide from wherever the finger left it, over a duration in proportion to the
 * distance still to go, so there is no jump in speed at the hand-over.
 *
 * Material's `ModalNavigationDrawer` answers the gesture by scaling the sheet down and nudging it toward the swipe's
 * edge, and keeps the drawer's offset to itself, so it cannot be made to simply follow the finger.
 */
@Composable
fun CursorDrawer(
    state: CursorDrawerState,
    drawerWidth: Dp,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    containerColor: Color = CursorTheme.colors.sidebar,
    contentColor: Color = CursorTheme.colors.textPrimary,
    scrimColor: Color = Color.Black.copy(alpha = 0.45f),
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val widthPx = with(density) { drawerWidth.toPx() }
    val flingThreshold = with(density) { FlingThreshold.toPx() } / widthPx
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    SideEffect { state.widthPx = widthPx }

    Box(
        modifier
            .fillMaxSize()
            .draggable(
                state = state.draggableState,
                orientation = Orientation.Horizontal,
                enabled = gesturesEnabled,
                reverseDirection = rtl,
                startDragImmediately = state.isAnimating,
                onDragStopped = { velocity -> state.settle(velocity / widthPx, flingThreshold) },
            ),
    ) {
        Box { content() }

        // With the drawer open over a screen that can itself go back, back closes the drawer before it pops anything.
        // The content is expected to stand its own handler down while the drawer is open, rather than this relying on
        // being the later-registered of the two.
        PredictiveBackHandler(enabled = state.isOpen) { events ->
            val start = state.fraction
            try {
                events.collect { state.seek(start * (1f - it.progress)) }
            } catch (_: CancellationException) {
                // The handler's own coroutine is the one cancelled; the rewind has to run somewhere that outlives it.
                scope.launch { state.slideTo(DrawerValue.Open) }
                return@PredictiveBackHandler
            }
            scope.launch { state.slideTo(DrawerValue.Closed) }
        }

        Scrim(
            open = state.isOpen,
            onClose = { if (gesturesEnabled) scope.launch { state.close() } },
            fraction = { state.fraction },
            color = scrimColor,
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(drawerWidth)
                .offset { IntOffset((-(1f - state.fraction) * widthPx).roundToInt(), 0) }
                .semantics {
                    paneTitle = NavigationMenu
                    if (state.isOpen) {
                        dismiss {
                            scope.launch { state.close() }
                            true
                        }
                    }
                },
        ) {
            Surface(color = containerColor, contentColor = contentColor, shape = RectangleShape, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start))) { drawerContent() }
            }
        }
    }
}

/** Where the drawer is and where it is heading. Survives configuration changes through [Saver]. */
@Stable
class CursorDrawerState(initialValue: DrawerValue) {
    /** 0: the sheet rests off screen. 1: it is fully open. Anything between is a drag, an animation or a gesture. */
    var fraction by mutableFloatStateOf(if (initialValue == DrawerValue.Open) 1f else 0f)
        private set

    /** Where the sheet will come to rest: unchanged by a drag or a gesture until the finger lets go. */
    var targetValue by mutableStateOf(initialValue)
        private set

    val isOpen: Boolean get() = targetValue == DrawerValue.Open

    var isAnimating by mutableStateOf(false)
        private set

    internal var widthPx = 0f

    private val mutex = MutatorMutex()

    suspend fun open() = slideTo(DrawerValue.Open)

    suspend fun close() = slideTo(DrawerValue.Closed)

    /** Animates to [value] from wherever the sheet is now, taking longer the further it has to travel. */
    internal suspend fun slideTo(value: DrawerValue) {
        targetValue = value
        val target = value.fraction
        mutex.mutate {
            val distance = abs(target - fraction)
            if (distance < 0.001f) {
                fraction = target
                return@mutate
            }
            val millis = (SlideMillis * distance).roundToInt().coerceIn(MinSlideMillis, SlideMillis)
            runAnimation { animate(fraction, target, animationSpec = tween(millis, easing = SlideEasing)) { v, _ -> fraction = v } }
        }
    }

    /**
     * Puts the sheet at [value] at once, no animation, cancelling one in flight: for a layout change that replaces the
     * drawer with something else (the rail, when the window turns wide), where a slide would play under the new layout.
     */
    suspend fun snapTo(value: DrawerValue) {
        targetValue = value
        mutex.mutate { fraction = value.fraction }
    }

    /** Puts the sheet at [fraction] for this frame of a back gesture, taking over from any animation in flight. */
    internal suspend fun seek(fraction: Float) {
        mutex.mutate(MutatePriority.UserInput) { this.fraction = fraction.coerceIn(0f, 1f) }
    }

    /**
     * The finger lifted after a drag with [velocity] (in sheet widths per second). A fling past [flingThreshold]
     * decides the direction; otherwise the nearer resting place does. The velocity carries into the animation.
     */
    internal suspend fun settle(velocity: Float, flingThreshold: Float) {
        val value = when {
            velocity > flingThreshold -> DrawerValue.Open
            velocity < -flingThreshold -> DrawerValue.Closed
            fraction >= 0.5f -> DrawerValue.Open
            else -> DrawerValue.Closed
        }
        targetValue = value
        mutex.mutate {
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

    /** Dragging holds the mutex for the whole gesture, so an animation cannot fight the finger. */
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
        val Saver: Saver<CursorDrawerState, DrawerValue> = Saver(save = { it.targetValue }, restore = { CursorDrawerState(it) })
    }
}

@Composable
fun rememberCursorDrawerState(initialValue: DrawerValue = DrawerValue.Closed): CursorDrawerState =
    rememberSaveable(saver = CursorDrawerState.Saver) { CursorDrawerState(initialValue) }

private val DrawerValue.fraction: Float get() = if (this == DrawerValue.Open) 1f else 0f

/** Darkens the content behind the sheet in step with how far open it is; a tap on it closes the drawer. */
@Composable
private fun Scrim(open: Boolean, onClose: () -> Unit, fraction: () -> Float, color: Color) {
    val dismissDrawer = if (open) {
        Modifier
            .pointerInput(onClose) { detectTapGestures { onClose() } }
            .semantics(mergeDescendants = true) {
                contentDescription = CloseNavigationMenu
                onClick {
                    onClose()
                    true
                }
            }
    } else {
        Modifier
    }
    Canvas(Modifier.fillMaxSize().then(dismissDrawer)) { drawRect(color, alpha = fraction()) }
}

private const val NavigationMenu = "Navigation menu"
private const val CloseNavigationMenu = "Close navigation menu"

/** A full-width slide; shorter ones scale down with the distance, never below [MinSlideMillis]. */
private const val SlideMillis = 300
private const val MinSlideMillis = 100

/** Fast out of the gate, settling gently: the same curve the navigation host settles its screens with. */
private val SlideEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** A drag lets go with a fling faster than this (per second, in dp of travel) decides the direction on its own. */
private val FlingThreshold = 400.dp

/** Carries a released drag's velocity into the rest of the slide without overshooting. */
private val FlingSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1000f, visibilityThreshold = 0.0005f)
