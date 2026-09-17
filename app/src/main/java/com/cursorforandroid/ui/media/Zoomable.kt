package com.cursorforandroid.ui.media

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A page's zoom: the scale about the viewport's centre and the pan, as the gestures leave them and as the springs
 * settle them. [fitted] and [viewport] come from the layout; the picture is drawn at [fitted] and the transform
 * `scale` / `pan` is applied over it (see [ViewerGeometry.displayed]).
 */
@Stable
class ZoomState(private val maxScale: Float = ViewerGeometry.MaxScale) {
    var scale by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set
    var fitted by mutableStateOf(Size.Zero)
        private set
    var viewport by mutableStateOf(Size.Zero)
        private set

    private var settling: Job? = null

    val isZoomed: Boolean get() = scale > 1f + ZoomedEpsilon

    val panLimit: Offset get() = ViewerGeometry.panLimit(fitted, viewport, scale)

    /** The layout changed under the picture (a rotation, the sharper decode): the pan is pulled back inside what the new bounds allow. */
    fun onLayout(viewport: Size, fitted: Size) {
        if (viewport == this.viewport && fitted == this.fitted) return
        this.viewport = viewport
        this.fitted = fitted
        pan = if (isZoomed) ViewerGeometry.clampPan(pan, panLimit) else Offset.Zero
    }

    /** Back to the resting fit at once: the page is no longer the one on screen. */
    fun reset() {
        settling?.cancel()
        scale = 1f
        pan = Offset.Zero
    }

    /** The fingers' own scale through the gesture, before the rubber band: what the band is applied to, so it gives evenly rather than compounding. */
    private var pinchScale = 1f

    /** A pinch's step: [zoomChange] about [centroid] (relative to the viewport's centre) plus the fingers' [panChange], rubber-banded past the range. */
    fun pinch(zoomChange: Float, centroid: Offset, panChange: Offset) {
        pinchScale *= zoomChange
        val next = ViewerGeometry.rubberBandScale(pinchScale, ViewerGeometry.MinScale, maxScale)
        val zoomed = ViewerGeometry.panForZoom(pan, scale, next, centroid)
        scale = next
        pan = ViewerGeometry.rubberBandPan(zoomed, panChange, ViewerGeometry.panLimit(fitted, viewport, next))
    }

    /** A one-finger drag while zoomed: the picture follows, resisting past its edges. */
    fun dragBy(delta: Offset) {
        pan = ViewerGeometry.rubberBandPan(pan, delta, panLimit)
    }

    /** Whether a horizontal drag of [dx] is this picture's to take, rather than the pager's. */
    fun canPanHorizontally(dx: Float): Boolean = isZoomed && ViewerGeometry.canPanHorizontally(pan, panLimit, dx)

    /** A gesture is starting: whatever spring was still running yields to the finger, and a pinch starts from where the picture is. */
    fun interrupt() {
        settling?.cancel()
        settling = null
        pinchScale = scale
    }

    /** The fingers lifted: the scale springs back into range and the pan inside the bounds the settled scale allows. */
    fun settle(scope: CoroutineScope) {
        val targetScale = scale.coerceIn(ViewerGeometry.MinScale, maxScale)
        val limit = ViewerGeometry.panLimit(fitted, viewport, targetScale)
        val targetPan = if (targetScale <= ViewerGeometry.MinScale + ZoomedEpsilon) Offset.Zero else ViewerGeometry.clampPan(pan, limit)
        if (targetScale == scale && targetPan == pan) return
        animateTo(scope, targetScale, targetPan)
    }

    /** A double tap at [position] (relative to the viewport's centre): in to fill, or back out to the fit. */
    fun doubleTap(scope: CoroutineScope, position: Offset) {
        val targetScale = ViewerGeometry.doubleTapScale(fitted, viewport, scale, maxScale)
        val targetPan = if (targetScale <= 1f) Offset.Zero
        else ViewerGeometry.clampPan(ViewerGeometry.panForZoom(pan, scale, targetScale, position), ViewerGeometry.panLimit(fitted, viewport, targetScale))
        animateTo(scope, targetScale, targetPan)
    }

    private fun animateTo(scope: CoroutineScope, targetScale: Float, targetPan: Offset) {
        settling?.cancel()
        val fromScale = scale
        val fromPan = pan
        settling = scope.launch {
            animate(0f, 1f, animationSpec = SettleSpec) { t, _ ->
                scale = lerp(fromScale, targetScale, t)
                pan = lerp(fromPan, targetPan, t)
            }
        }
    }

    private companion object {
        const val ZoomedEpsilon = 0.001f
        val SettleSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)
    }
}

/**
 * The drag that dismisses a page: the finger's displacement from where it started, the share of the way to the
 * threshold that is ([fraction]), and the shrink the picture takes on as it goes.
 */
@Stable
class DismissState {
    var drag by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(Size.Zero)
        internal set
    /** True from the first movement to the release, while the scrim follows the finger. */
    var dragging by mutableStateOf(false)
        private set

    val fraction: Float get() = ViewerGeometry.dismissFraction(drag, viewport)

    /** The picture shrinks to three quarters at the threshold. */
    val scale: Float get() = 1f - fraction * ShrinkAtThreshold

    private var recovering: Job? = null

    fun start() {
        recovering?.cancel()
        dragging = true
    }

    fun dragBy(delta: Offset) {
        drag += delta
    }

    /** The finger lifted short of the threshold: the picture springs back to rest. */
    fun recover(scope: CoroutineScope) {
        dragging = false
        val from = drag
        recovering = scope.launch {
            animate(0f, 1f, animationSpec = RecoverSpec) { t, _ -> drag = lerp(from, Offset.Zero, t) }
        }
    }

    /** The page is gone (or a new page took its place): nothing to recover from. */
    fun reset() {
        recovering?.cancel()
        dragging = false
        drag = Offset.Zero
    }

    /** Frozen where the finger left it; the close transform starts from there. */
    fun release() {
        dragging = false
    }

    private companion object {
        const val ShrinkAtThreshold = 0.25f
        val RecoverSpec = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 700f)
    }
}

/**
 * The gestures of a page, and how they are shared with the pager underneath:
 *
 * - two fingers pinch the picture, around their centroid, however the page stands ([ZoomState.pinch]);
 * - one finger on a picture zoomed past the fit pans it — unless the drag is sideways and the picture is already
 *   at that edge, in which case nothing here touches the events and the pager takes the swipe;
 * - one finger on a picture at the fit that moves mostly downward or upward drags the page toward dismissal
 *   ([DismissState]); moving mostly sideways it is the pager's;
 * - a tap toggles the chrome, a double tap zooms in and out.
 *
 * The decision is made once per gesture, when the finger has moved past touch slop, so a pan that reaches the edge
 * stops there and the *next* swipe turns the page. [enabled] false (the transform is running) lets nothing through
 * but the taps.
 */
fun Modifier.viewerGestures(
    zoom: ZoomState?,
    dismiss: DismissState,
    scope: CoroutineScope,
    enabled: Boolean,
    onTap: () -> Unit,
    onDismiss: (velocityY: Float) -> Unit,
): Modifier = this
    .pointerInput(zoom, dismiss, enabled) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { position ->
                if (enabled && zoom != null && !dismiss.dragging) {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    zoom.doubleTap(scope, position - centre)
                }
            },
        )
    }
    .pointerInput(zoom, dismiss, enabled) {
        if (!enabled) return@pointerInput
        val velocity = VelocityTracker()
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            zoom?.interrupt()
            var mode = GestureMode.Undecided
            var travelled = Offset.Zero
            val slop = viewConfiguration.touchSlop
            velocity.resetTracking()
            val centre = Offset(size.width / 2f, size.height / 2f)
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (mode == GestureMode.PassThrough) continue
                if (pressed.size >= 2 && zoom != null) {
                    if (mode == GestureMode.Dismiss) dismiss.recover(scope)
                    mode = GestureMode.Zoom
                    zoom.pinch(event.calculateZoom(), event.calculateCentroid() - centre, event.calculatePan())
                    event.changes.forEach(PointerInputChange::consume)
                    continue
                }
                val change = pressed.first()
                val delta = change.positionChange()
                when (mode) {
                    GestureMode.Zoom -> {
                        // One finger left of the pinch: it goes on as a pan of a zoomed picture, or is spent.
                        mode = if (zoom?.isZoomed == true) GestureMode.Pan else GestureMode.Spent
                        if (mode == GestureMode.Pan) zoom?.dragBy(delta)
                        change.consume()
                    }
                    GestureMode.Undecided -> {
                        travelled += delta
                        if (travelled.getDistance() < slop) continue
                        val sideways = abs(travelled.x) > abs(travelled.y)
                        mode = when {
                            zoom?.isZoomed == true -> if (sideways && !zoom.canPanHorizontally(travelled.x)) GestureMode.PassThrough else GestureMode.Pan
                            sideways -> GestureMode.PassThrough
                            else -> GestureMode.Dismiss
                        }
                        velocity.addPosition(change.uptimeMillis, change.position)
                        when (mode) {
                            GestureMode.Pan -> {
                                zoom?.dragBy(travelled)
                                change.consume()
                            }
                            GestureMode.Dismiss -> {
                                dismiss.start()
                                dismiss.dragBy(travelled)
                                change.consume()
                            }
                            else -> Unit
                        }
                    }
                    GestureMode.Pan -> {
                        zoom?.dragBy(delta)
                        velocity.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                    }
                    GestureMode.Dismiss -> {
                        dismiss.dragBy(delta)
                        velocity.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                    }
                    GestureMode.Spent -> change.consume()
                    GestureMode.PassThrough -> Unit
                }
            }
            when (mode) {
                GestureMode.Zoom, GestureMode.Pan, GestureMode.Spent -> zoom?.settle(scope)
                GestureMode.Dismiss -> {
                    val velocityY = velocity.calculateVelocity().y
                    if (ViewerGeometry.shouldDismiss(dismiss.drag, velocityY, dismiss.viewport)) {
                        dismiss.release()
                        onDismiss(velocityY)
                    } else {
                        dismiss.recover(scope)
                    }
                }
                GestureMode.Undecided, GestureMode.PassThrough -> Unit
            }
        }
    }

private enum class GestureMode { Undecided, Zoom, Pan, Dismiss, PassThrough, Spent }
