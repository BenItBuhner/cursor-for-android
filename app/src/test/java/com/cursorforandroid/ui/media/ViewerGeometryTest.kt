package com.cursorforandroid.ui.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The arithmetic behind the viewer: where the picture may be dragged, how a pinch keeps its point, and the transform's frames. */
class ViewerGeometryTest {

    private val phone = Size(1080f, 2400f)
    private val square = ViewerGeometry.fitted(phone, 1080, 1080).size

    private fun Rect.assertCloseTo(expected: Rect) {
        assertThat(left).isWithin(0.5f).of(expected.left)
        assertThat(top).isWithin(0.5f).of(expected.top)
        assertThat(right).isWithin(0.5f).of(expected.right)
        assertThat(bottom).isWithin(0.5f).of(expected.bottom)
    }

    // -- pan limits ---------------------------------------------------------------------------------------------------

    @Test
    fun `an unzoomed picture has nowhere to be dragged`() {
        // Fit leaves nothing hanging off either edge, so every drag comes back to centre.
        assertThat(ViewerGeometry.panLimit(square, phone, scale = 1f)).isEqualTo(Offset.Zero)
        assertThat(ViewerGeometry.clampPan(Offset(400f, -900f), Offset.Zero)).isEqualTo(Offset.Zero)
    }

    @Test
    fun `panning stops at the edge of the zoomed picture`() {
        // A square drawn Fit in a 1080x2400 viewport is 1080 wide; at 3x it is 3240, so 1080 of it hangs off each
        // side horizontally, and vertically the 3240-tall picture overhangs by 420.
        val limit = ViewerGeometry.panLimit(square, phone, scale = 3f)
        assertThat(limit.x).isWithin(0.5f).of(1080f)
        assertThat(limit.y).isWithin(0.5f).of(420f)
        val far = ViewerGeometry.clampPan(Offset(5000f, 5000f), limit)
        assertThat(far.x).isWithin(0.5f).of(1080f)
        assertThat(far.y).isWithin(0.5f).of(420f)
        assertThat(ViewerGeometry.clampPan(Offset(-5000f, -5000f), limit).x).isWithin(0.5f).of(-1080f)
    }

    @Test
    fun `a letterboxed picture cannot be dragged into its own margins`() {
        // A wide panorama in a tall viewport: at 2x it still does not fill the height, so vertical pan stays locked
        // even though the horizontal overhang is large.
        val panorama = ViewerGeometry.fitted(phone, 4000, 1000).size
        val limit = ViewerGeometry.panLimit(panorama, phone, scale = 2f)
        assertThat(limit.x).isWithin(0.5f).of(540f)
        assertThat(limit.y).isEqualTo(0f)
    }

    @Test
    fun `a rotation pulls a dragged picture back inside the bounds the new viewport allows`() {
        // Dragged to the right edge of a square picture zoomed 3x in portrait: 1080 of overhang each side. The same
        // picture in a landscape viewport is drawn 1080 tall and wide, so at 3x it overhangs by 420 each side rather
        // than 1080; the old offset is off the screen until the clamp brings it back.
        val landscape = Size(2400f, 1080f)
        val rotated = ViewerGeometry.fitted(landscape, 1080, 1080).size
        val clamped = ViewerGeometry.clampPan(Offset(1080f, 0f), ViewerGeometry.panLimit(rotated, landscape, scale = 3f))
        assertThat(clamped.x).isWithin(0.5f).of(420f)
        assertThat(clamped.y).isEqualTo(0f)
    }

    // -- rubber band --------------------------------------------------------------------------------------------------

    @Test
    fun `a drag inside the edges is taken whole, up to the edge, and the rest is overflow`() {
        val limit = 1080f
        assertThat(ViewerGeometry.panWithin(200f, 50f, limit)).isEqualTo(ViewerGeometry.AxisPan(250f, 0f))
        // 900 of room to the right: 900 taken, 100 left over for the pager or the band.
        assertThat(ViewerGeometry.panWithin(180f, 1000f, limit)).isEqualTo(ViewerGeometry.AxisPan(1080f, 100f))
        // At the edge already: nothing taken.
        assertThat(ViewerGeometry.panWithin(1080f, 40f, limit)).isEqualTo(ViewerGeometry.AxisPan(1080f, 40f))
        // From the edge, back inside: taken whole.
        assertThat(ViewerGeometry.panWithin(1080f, -40f, limit)).isEqualTo(ViewerGeometry.AxisPan(1040f, 0f))
    }

    @Test
    fun `an axis with no overhang has no edge to move within`() {
        assertThat(ViewerGeometry.panWithin(0f, 80f, limit = 0f)).isEqualTo(ViewerGeometry.AxisPan(0f, 80f))
        // And the band does not stretch it either: a fitted picture does not slide off a horizontal pan.
        assertThat(ViewerGeometry.stretch(0f, 80f, limit = 0f)).isEqualTo(0f)
    }

    @Test
    fun `the band takes overflow at a fraction, and a stretched picture comes back the way it went out`() {
        val limit = 1080f
        // 100 of overflow past the right edge: the picture gives 35.
        val stretched = ViewerGeometry.stretch(1080f, 100f, limit)
        assertThat(stretched).isWithin(0.01f).of(1115f)
        // Further out: all of it is still overflow (the caller feeds it to the band again).
        assertThat(ViewerGeometry.panWithin(stretched, 20f, limit)).isEqualTo(ViewerGeometry.AxisPan(stretched, 20f))
        // Back toward the inside: 100 of finger undoes the 35 of stretch exactly, and nothing is left over.
        val back = ViewerGeometry.panWithin(stretched, -100f, limit)
        assertThat(back.value).isWithin(0.01f).of(1080f)
        assertThat(back.overflow).isEqualTo(0f)
        // Back further than the stretch: the finger past the edge continues inside, one for one.
        val through = ViewerGeometry.panWithin(stretched, -150f, limit)
        assertThat(through.value).isWithin(0.01f).of(1030f)
        assertThat(through.overflow).isEqualTo(0f)
    }

    @Test
    fun `a pinch past the range keeps going at a fraction of its rate`() {
        assertThat(ViewerGeometry.rubberBandScale(0.8f, 1f, 5f)).isWithin(0.001f).of(1f - 0.2f * 0.35f)
        assertThat(ViewerGeometry.rubberBandScale(6f, 1f, 5f)).isWithin(0.001f).of(5f + 0.35f)
        assertThat(ViewerGeometry.rubberBandScale(2.5f, 1f, 5f)).isEqualTo(2.5f)
    }

    // -- zoom about a point -------------------------------------------------------------------------------------------

    @Test
    fun `zooming keeps the point under the fingers where it is`() {
        // A point 300 px right of the centre at 1x, pan zero: doubling the scale must leave that point where it was,
        // so the pan shifts left by 300 (the picture grows outward from the centre and is dragged back).
        val pan = ViewerGeometry.panForZoom(Offset.Zero, scale = 1f, newScale = 2f, centroid = Offset(300f, 0f))
        assertThat(pan.x).isWithin(0.01f).of(-300f)
        // The picture point that was under the centroid: (c - pan) / scale, before and after.
        val before = (Offset(300f, 0f) - Offset.Zero) / 1f
        val after = (Offset(300f, 0f) - pan) / 2f
        assertThat(after.x).isWithin(0.01f).of(before.x)
    }

    @Test
    fun `a pinch at the centre with no pan leaves the pan alone`() {
        assertThat(ViewerGeometry.panForZoom(Offset.Zero, 1f, 3f, Offset.Zero)).isEqualTo(Offset.Zero)
    }

    @Test
    fun `a double tap fills the viewport when that is a real zoom and steps otherwise`() {
        // A square in a tall viewport fills it at 2400 / 1080 = 2.22x.
        assertThat(ViewerGeometry.doubleTapScale(square, phone, current = 1f, maxScale = 5f)).isWithin(0.01f).of(2400f / 1080f)
        // A picture already shaped like the viewport would only "fill" at 1x; the step applies.
        val fitting = ViewerGeometry.fitted(phone, 1080, 2400).size
        assertThat(ViewerGeometry.doubleTapScale(fitting, phone, current = 1f, maxScale = 5f)).isEqualTo(ViewerGeometry.DoubleTapStep)
        // And from anywhere zoomed, back to the fit.
        assertThat(ViewerGeometry.doubleTapScale(square, phone, current = 2.2f, maxScale = 5f)).isEqualTo(1f)
    }

    // -- the pager's share of the swipe -------------------------------------------------------------------------------

    @Test
    fun `a sideways drag is the picture's until it reaches that edge, then the pager's`() {
        val limit = Offset(1080f, 420f)
        assertThat(ViewerGeometry.canPanHorizontally(Offset.Zero, limit, dx = 40f)).isTrue()
        assertThat(ViewerGeometry.canPanHorizontally(Offset(1080f, 0f), limit, dx = 40f)).isFalse()
        // At the right edge a drag back to the left is still the picture's.
        assertThat(ViewerGeometry.canPanHorizontally(Offset(1080f, 0f), limit, dx = -40f)).isTrue()
        // Nothing hangs off: never the picture's.
        assertThat(ViewerGeometry.canPanHorizontally(Offset.Zero, Offset.Zero, dx = 40f)).isFalse()
    }

    // -- dismiss ------------------------------------------------------------------------------------------------------

    @Test
    fun `a drag over a share of the viewport or a fast fling dismisses`() {
        assertThat(ViewerGeometry.dismissFraction(Offset(0f, 336f), phone)).isWithin(0.01f).of(0.5f)
        assertThat(ViewerGeometry.shouldDismiss(Offset(0f, 336f), velocityY = 0f, phone)).isFalse()
        assertThat(ViewerGeometry.shouldDismiss(Offset(0f, 700f), velocityY = 0f, phone)).isTrue()
        assertThat(ViewerGeometry.shouldDismiss(Offset(0f, 120f), velocityY = 2500f, phone)).isTrue()
        // A fling back the way the drag came does not dismiss.
        assertThat(ViewerGeometry.shouldDismiss(Offset(0f, 120f), velocityY = -2500f, phone)).isFalse()
    }

    // -- the transform ------------------------------------------------------------------------------------------------

    @Test
    fun `the transform starts in the thumbnail's box and ends at the page`() {
        val thumbnail = ThumbnailFrame(Rect(100f, 900f, 460f, 1260f), cornerRadius = 24f, crop = false)
        val page = ViewerGeometry.fitted(phone, 1080, 1080)
        val viewport = Rect(Offset.Zero, phone)
        val start = ViewerGeometry.transition(thumbnail, 1080, 1080, page, viewport, progress = 0f)
        start.image.assertCloseTo(thumbnail.bounds)
        start.clip.assertCloseTo(thumbnail.bounds)
        assertThat(start.cornerRadius).isEqualTo(24f)
        val end = ViewerGeometry.transition(thumbnail, 1080, 1080, page, viewport, progress = 1f)
        end.image.assertCloseTo(page)
        end.clip.assertCloseTo(page)
        assertThat(end.cornerRadius).isEqualTo(0f)
        // Halfway: halfway.
        val mid = ViewerGeometry.transition(thumbnail, 1080, 1080, page, viewport, progress = 0.5f)
        assertThat(mid.image.left).isWithin(0.5f).of((thumbnail.bounds.left + page.left) / 2f)
        assertThat(mid.cornerRadius).isEqualTo(12f)
    }

    @Test
    fun `a cropped thumbnail draws the whole picture past its box and clips to the box`() {
        // An 88 x 160 strip crop of a 1080 x 1080 picture: the picture is drawn 160 x 160, centred, and only the
        // middle 88 of it shows. As the transform starts the clip is the strip and the picture already reaches past it.
        val thumbnail = ThumbnailFrame(Rect(0f, 0f, 88f, 160f), cornerRadius = 8f, crop = true)
        val start = ViewerGeometry.transition(thumbnail, 1080, 1080, ViewerGeometry.fitted(phone, 1080, 1080), Rect(Offset.Zero, phone), 0f)
        start.clip.assertCloseTo(thumbnail.bounds)
        start.image.assertCloseTo(Rect(-36f, 0f, 124f, 160f))
    }

    @Test
    fun `a zoomed page shrinks into its thumbnail from what is on screen of it`() {
        // Closing at 3x: the picture rect reaches far past the viewport; the clip at the page end is what the
        // viewport shows of it, never more.
        val page = ViewerGeometry.displayed(ViewerGeometry.fitted(phone, 1080, 1080), scale = 3f, pan = Offset(200f, 0f))
        val thumbnail = ThumbnailFrame(Rect(100f, 900f, 460f, 1260f), cornerRadius = 24f, crop = false)
        val end = ViewerGeometry.transition(thumbnail, 1080, 1080, page, Rect(Offset.Zero, phone), 1f)
        end.image.assertCloseTo(page)
        end.clip.assertCloseTo(Rect(0f, 0f, 1080f, 2400f).intersect(page))
    }

    @Test
    fun `displayed applies zoom about the centre, then the pan and the dismiss drag`() {
        val fitted = ViewerGeometry.fitted(phone, 1080, 1080)
        val plain = ViewerGeometry.displayed(fitted, scale = 1f, pan = Offset.Zero)
        plain.assertCloseTo(fitted)
        val zoomed = ViewerGeometry.displayed(fitted, scale = 2f, pan = Offset(100f, -50f))
        assertThat(zoomed.width).isWithin(0.5f).of(2160f)
        assertThat(zoomed.center.x).isWithin(0.5f).of(fitted.center.x + 100f)
        assertThat(zoomed.center.y).isWithin(0.5f).of(fitted.center.y - 50f)
        val dragged = ViewerGeometry.displayed(fitted, 1f, Offset.Zero, drag = Offset(0f, 300f), dragScale = 0.9f)
        assertThat(dragged.width).isWithin(0.5f).of(972f)
        assertThat(dragged.center.y).isWithin(0.5f).of(fitted.center.y + 300f)
    }
}
