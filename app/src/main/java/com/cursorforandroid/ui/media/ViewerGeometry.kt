package com.cursorforandroid.ui.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Where a thumbnail draws its picture: the box it occupies (in the viewer's coordinates), how its corners are
 * rounded, and whether the picture is fitted inside the box or cropped to fill it. The viewer grows the same
 * picture out of this box when it opens and shrinks it back into it when it closes.
 */
data class ThumbnailFrame(val bounds: Rect, val cornerRadius: Float, val crop: Boolean)

/**
 * One frame of the container transform between a thumbnail and the open page: the rect the whole picture is drawn
 * into (which may reach past [clip] while a cropped thumbnail is still showing its middle), the rect the drawing is
 * clipped to, and the clip's corner radius.
 */
data class TransitionFrame(val image: Rect, val clip: Rect, val cornerRadius: Float)

/** The arithmetic of the viewer's transform, pinch and pan, kept free of Compose so it can be checked on the JVM. */
object ViewerGeometry {

    /** The rect a [imageWidth] x [imageHeight] picture occupies when drawn centred in [bounds], fitted inside it or cropped to fill it. */
    fun imageRect(bounds: Rect, imageWidth: Int, imageHeight: Int, crop: Boolean): Rect {
        val w = imageWidth.coerceAtLeast(1).toFloat()
        val h = imageHeight.coerceAtLeast(1).toFloat()
        if (bounds.width <= 0f || bounds.height <= 0f) return bounds
        val scale = if (crop) max(bounds.width / w, bounds.height / h) else min(bounds.width / w, bounds.height / h)
        return rectAround(bounds.center, w * scale, h * scale)
    }

    /** The page's resting rect: the picture fitted inside [viewport] and centred, a small one enlarged to it as galleries do. */
    fun fitted(viewport: Size, imageWidth: Int, imageHeight: Int): Rect {
        if (viewport.width <= 0f || viewport.height <= 0f) return Rect.Zero
        return imageRect(Rect(Offset.Zero, viewport), imageWidth, imageHeight, crop = false)
    }

    /**
     * Where the page's picture is on screen once the reader's zoom (about the viewport's centre), pan and any
     * drag-to-dismiss displacement are applied to [fitted]: the transform `graphicsLayer` draws with, as a rect.
     */
    fun displayed(fitted: Rect, scale: Float, pan: Offset, drag: Offset = Offset.Zero, dragScale: Float = 1f): Rect {
        val s = scale * dragScale
        return rectAround(fitted.center + pan + drag, fitted.width * s, fitted.height * s)
    }

    /** The [width] x [height] rect centred on [center]. */
    fun rectAround(center: Offset, width: Float, height: Float): Rect =
        Rect(center.x - width / 2f, center.y - height / 2f, center.x + width / 2f, center.y + height / 2f)

    fun lerp(from: Rect, to: Rect, t: Float): Rect = Rect(
        lerp(from.left, to.left, t),
        lerp(from.top, to.top, t),
        lerp(from.right, to.right, t),
        lerp(from.bottom, to.bottom, t),
    )

    /**
     * The transform at [progress] (0 at the thumbnail, 1 at the open page). The picture's rect is interpolated
     * between where the thumbnail draws it and where the page does, and the clip between the thumbnail's box and
     * the part of the page's picture that is on screen, so a cropped strip thumbnail widens into the whole picture
     * and a zoomed page shrinks into its thumbnail without ever drawing outside the viewport.
     */
    fun transition(thumbnail: ThumbnailFrame, imageWidth: Int, imageHeight: Int, page: Rect, viewport: Rect, progress: Float): TransitionFrame {
        val t = progress.coerceIn(0f, 1f)
        val thumbImage = imageRect(thumbnail.bounds, imageWidth, imageHeight, thumbnail.crop)
        val pageClip = page.intersect(viewport).let { if (it.width < 0f || it.height < 0f) viewport else it }
        return TransitionFrame(
            image = lerp(thumbImage, page, t),
            clip = lerp(thumbnail.bounds, pageClip, t),
            cornerRadius = lerp(thumbnail.cornerRadius, 0f, t),
        )
    }

    // -- zoom and pan -------------------------------------------------------------------------------------------------

    /** The farthest the picture may be panned on each axis: half of what hangs past the viewport once scaled; 0 where it fits. */
    fun panLimit(fitted: Size, viewport: Size, scale: Float): Offset = Offset(
        max(0f, (fitted.width * scale - viewport.width) / 2f),
        max(0f, (fitted.height * scale - viewport.height) / 2f),
    )

    fun clampPan(pan: Offset, limit: Offset): Offset = Offset(clamp(pan.x, limit.x), clamp(pan.y, limit.y))

    /** Within ±[limit]; exactly 0 (never -0) where there is no room, so a rested pan compares equal to none. */
    private fun clamp(value: Float, limit: Float): Float = if (limit <= 0f) 0f else value.coerceIn(-limit, limit)

    /**
     * [pan] moved by [delta] with the rubber band on: a move that stays within [limit] is taken whole, one that goes
     * past it (or starts past it) is taken at [factor] of its length, so the picture resists at the edge instead of
     * either stopping dead or leaving the screen. Where nothing hangs past the viewport the axis does not move at all.
     */
    fun rubberBandPan(pan: Offset, delta: Offset, limit: Offset, factor: Float = RubberBandFactor): Offset =
        Offset(rubberBand(pan.x, delta.x, limit.x, factor), rubberBand(pan.y, delta.y, limit.y, factor))

    private fun rubberBand(value: Float, delta: Float, limit: Float, factor: Float): Float {
        if (limit <= 0f) return 0f
        val proposed = value + delta
        return if (abs(proposed) <= limit) proposed else value + delta * factor
    }

    /** A pinch past the zoom range keeps going at [factor] of its rate; [settle] brings it back. */
    fun rubberBandScale(scale: Float, min: Float, max: Float, factor: Float = RubberBandFactor): Float = when {
        scale < min -> min - (min - scale) * factor
        scale > max -> max + (scale - max) * factor
        else -> scale
    }

    /**
     * The pan that keeps the picture under [centroid] (relative to the viewport's centre) still while the scale goes
     * from [scale] to [newScale]: the point was at `(centroid - pan) / scale` in the picture, and stays there.
     */
    fun panForZoom(pan: Offset, scale: Float, newScale: Float, centroid: Offset): Offset {
        if (scale <= 0f) return pan
        val ratio = newScale / scale
        return centroid - (centroid - pan) * ratio
    }

    /** Whether a horizontal drag of [dx] moves the picture rather than being the pager's: the picture is zoomed and not yet at that edge. */
    fun canPanHorizontally(pan: Offset, limit: Offset, dx: Float): Boolean {
        if (limit.x <= 0f || dx == 0f) return false
        return if (dx > 0f) pan.x < limit.x - EdgeEpsilon else pan.x > -limit.x + EdgeEpsilon
    }

    /** The scale a double tap takes the picture to, and back from: fills the viewport when that is a real zoom, else a fixed step. */
    fun doubleTapScale(fitted: Size, viewport: Size, current: Float, maxScale: Float): Float {
        if (current > 1f + EdgeEpsilon) return 1f
        val fill = if (fitted.width > 0f && fitted.height > 0f) max(viewport.width / fitted.width, viewport.height / fitted.height) else 1f
        val target = if (fill > 1.2f) fill else DoubleTapStep
        return target.coerceIn(1f, maxScale)
    }

    /** How far along the dismiss a drag of [drag] is: the vertical travel against a share of the viewport's height, 1 at the threshold. */
    fun dismissFraction(drag: Offset, viewport: Size): Float {
        if (viewport.height <= 0f) return 0f
        return (abs(drag.y) / (viewport.height * DismissDistanceShare)).coerceIn(0f, 1f)
    }

    /** Whether the finger lifting with [velocityY] (px/s) after [drag] dismisses the page rather than letting it spring back. */
    fun shouldDismiss(drag: Offset, velocityY: Float, viewport: Size): Boolean =
        dismissFraction(drag, viewport) >= 1f || (abs(velocityY) > DismissVelocity && sign(velocityY) == sign(drag.y) && abs(drag.y) > 0f)

    const val RubberBandFactor = 0.35f
    const val DoubleTapStep = 2.5f
    const val MinScale = 1f
    const val MaxScale = 5f
    private const val EdgeEpsilon = 0.5f
    /** A drag over this much of the viewport's height dismisses whatever the speed. */
    private const val DismissDistanceShare = 0.28f
    /** A fling faster than this (px/s) dismisses however short the drag. */
    private const val DismissVelocity = 1800f
}
