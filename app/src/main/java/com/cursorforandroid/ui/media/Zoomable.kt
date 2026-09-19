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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
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
import kotlin.math.min
import kotlin.math.sign

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
        pan = ViewerGeometry.panForZoom(pan, scale, next, centroid)
        scale = next
        // Two fingers own the picture outright: whatever they move past an edge is the band's, not the pager's.
        stretchBy(panWithin(panChange))
    }

    /**
     * Pans by [delta] as far as the picture's edges allow, and answers what did not fit — the overflow past an edge
     * on each axis, in finger pixels — for the caller to hand to the pager or give back as a stretch. See
     * [ViewerGeometry.panWithin] for the edge and the band's behaviour.
     */
    fun panWithin(delta: Offset): Offset {
        val limit = panLimit
        val x = ViewerGeometry.panWithin(pan.x, delta.x, limit.x)
        val y = ViewerGeometry.panWithin(pan.y, delta.y, limit.y)
        pan = Offset(x.value, y.value)
        return Offset(x.overflow, y.overflow)
    }

    /** Takes [overflow] as a rubber band: the picture gives a fraction of it past its edge, on the axes that have one, and springs back on release. */
    fun stretchBy(overflow: Offset) {
        if (overflow == Offset.Zero) return
        val limit = panLimit
        pan = Offset(ViewerGeometry.stretch(pan.x, overflow.x, limit.x), ViewerGeometry.stretch(pan.y, overflow.y, limit.y))
    }

    /** Whether a horizontal drag of [dx] starts on this picture rather than on the pager: zoomed, and not already at that edge. */
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
 * The pager's share of a zoomed picture's drag. The pager cannot be handed a gesture part-way — its own detector
 * gave the gesture up when the picture took it and waits for the next finger — so the page drives it instead: once
 * the picture has been panned to its edge, what the finger moves past that edge pulls the next page in
 * ([dragBy], raw, with the finger), a finger that turns back brings the pager to rest before the picture pans
 * again ([returnBy]), and the lift settles the pager on the page it was pulled past [ViewerGeometry.PageCommitShare]
 * of the way toward, or flung toward faster than [minFlingVelocityPx], else back where it was ([settle]).
 */
@Stable
class PagerHandover internal constructor(
    private val pagerState: PagerState,
    /** In a left-to-right layout the finger and the pager's offset run opposite ways: a finger moving left scrolls forward. */
    private val reverse: Boolean,
    private val minFlingVelocityPx: Float,
) {
    /** How far the pager has been pulled by the gesture under way, in its own scroll pixels (positive toward the next page); 0 at rest. */
    var travel by mutableFloatStateOf(0f)
        private set
    private var startPage = 0
    private var settling: Job? = null

    val engaged: Boolean get() = travel != 0f

    private fun toScroll(finger: Float) = if (reverse) -finger else finger
    private fun toFinger(scroll: Float) = if (reverse) -scroll else scroll

    /** A gesture is starting: a settle still running yields to the finger. */
    fun interrupt() {
        settling?.cancel()
        settling = null
        travel = 0f
    }

    /** Brings the pager back toward rest with as much of [dx] (finger pixels) as heads that way; answers what is left of [dx]. */
    fun returnBy(dx: Float): Float {
        if (travel == 0f || dx == 0f) return dx
        val scroll = toScroll(dx)
        if (sign(scroll) == sign(travel)) return dx
        val back = sign(scroll) * min(abs(scroll), abs(travel))
        val consumed = pagerState.dispatchRawDelta(back)
        travel += consumed
        if (abs(travel) < RestEpsilon) travel = 0f
        return dx - toFinger(consumed)
    }

    /** Pulls the pager by [dx] (finger pixels), from rest or further out; answers what it would not take — there is no page that way. */
    fun dragBy(dx: Float): Float {
        if (dx == 0f) return 0f
        if (travel == 0f) startPage = pagerState.currentPage
        val consumed = pagerState.dispatchRawDelta(toScroll(dx))
        travel += consumed
        return dx - toFinger(consumed)
    }

    /** The finger lifted at [velocityX] (finger px/s): forward to the page pulled in when pulled or flung far enough, else back to rest. */
    fun settle(scope: CoroutineScope, velocityX: Float) {
        if (travel == 0f) return
        val extent = (pagerState.layoutInfo.pageSize + pagerState.layoutInfo.pageSpacing).toFloat().coerceAtLeast(1f)
        val velocity = toScroll(velocityX)
        val committed = abs(travel) / extent >= ViewerGeometry.PageCommitShare ||
            (abs(velocity) >= minFlingVelocityPx && sign(velocity) == sign(travel))
        val target = (startPage + if (committed) sign(travel).toInt() else 0).coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
        travel = 0f
        settling = scope.launch { pagerState.animateScrollToPage(target) }
    }

    private companion object {
        const val RestEpsilon = 0.5f
    }
}

/**
 * The gestures of a page, and how they are shared with the pager underneath:
 *
 * - two fingers pinch the picture, around their centroid, however the page stands ([ZoomState.pinch]);
 * - one finger on a picture at the fit: the first direction it moves in decides, once, whether the drag is the
 *   pager's (sideways: nothing here touches the events, and the pager takes it from its own touch slop, at any
 *   speed) or a dismiss (up or down: [DismissState]);
 * - one finger on a picture zoomed past the fit pans it; a pan that reaches the picture's edge hands what the finger
 *   moves past it to the pager, in the same drag ([PagerHandover]), and takes the finger back when it turns; a
 *   sideways start on a picture already at that edge is the pager's outright;
 * - a tap toggles the chrome, a double tap zooms in and out.
 *
 * [enabled] is read when a finger comes down (the transform running, the page not the one on screen): a gesture
 * under way is never cut short by the page it is on ceasing to be the current one, which is exactly what happens
 * when it pulls the next page past halfway.
 */
fun Modifier.viewerGestures(
    zoom: ZoomState?,
    dismiss: DismissState,
    handover: PagerHandover?,
    scope: CoroutineScope,
    enabled: State<Boolean>,
    onTap: () -> Unit,
    onDismiss: (velocityY: Float) -> Unit,
): Modifier = this
    .pointerInput(zoom, dismiss, enabled) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { position ->
                if (enabled.value && zoom != null && !dismiss.dragging) {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    zoom.doubleTap(scope, position - centre)
                }
            },
        )
    }
    .pointerInput(zoom, dismiss, handover, enabled) {
        val velocity = VelocityTracker()
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            if (!enabled.value) return@awaitEachGesture
            zoom?.interrupt()
            handover?.interrupt()
            var mode = GestureMode.Undecided
            var travelled = Offset.Zero
            val slop = viewConfiguration.touchSlop
            velocity.resetTracking()
            val centre = Offset(size.width / 2f, size.height / 2f)

            /** One finger's move on a zoomed picture: the pager back to rest first, then the picture to its edges, then the pager, then the band. */
            fun pan(delta: Offset) {
                val picture = zoom ?: return
                val toPicture = Offset(handover?.returnBy(delta.x) ?: delta.x, delta.y)
                val overflow = picture.panWithin(toPicture)
                val refused = Offset(handover?.takeIf { overflow.x != 0f }?.dragBy(overflow.x) ?: overflow.x, overflow.y)
                picture.stretchBy(refused)
            }

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
                        if (mode == GestureMode.Pan) pan(delta)
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
                                pan(travelled)
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
                        pan(delta)
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
                GestureMode.Pan -> {
                    handover?.settle(scope, velocity.calculateVelocity().x)
                    zoom?.settle(scope)
                }
                GestureMode.Zoom, GestureMode.Spent -> zoom?.settle(scope)
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
