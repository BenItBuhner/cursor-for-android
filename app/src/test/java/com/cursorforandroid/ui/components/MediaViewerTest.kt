package com.cursorforandroid.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The arithmetic behind the full-screen image viewer: what it asks the loader for, and where it may be dragged. */
class MediaViewerTest {

    private val phone = IntSize(1080, 2400)

    @Test
    fun `a decode request is capped by area, not left to grow with the screen`() {
        // The viewer used to ask for twice the screen in each direction — four times its area, ~41 MB of
        // ARGB_8888 on a 420dpi phone, with nothing between it and OutOfMemoryError.
        val huge = boundedPixels(2158, 4798)
        assertThat(huge.width.toLong() * huge.height).isAtMost(3_000_000L)
        // Proportions are kept, so Scale.FIT still fits the same box.
        assertThat(huge.width.toFloat() / huge.height).isWithin(0.01f).of(2158f / 4798f)
    }

    @Test
    fun `a request that already fits is passed through untouched`() {
        assertThat(boundedPixels(1080, 2400)).isEqualTo(IntSize(1080, 2400))
        assertThat(boundedPixels(0, 0)).isEqualTo(IntSize(1, 1))
    }

    @Test
    fun `an unzoomed figure has nowhere to be dragged`() {
        // Fit leaves nothing hanging off either edge, so every drag comes back to centre.
        assertThat(clampedPan(Offset(400f, -900f), scale = 1f, 1080, 1080, phone)).isEqualTo(Offset.Zero)
    }

    @Test
    fun `panning stops at the edge of the zoomed image`() {
        // A square drawn Fit in a 1080x2400 viewport is 1080 wide; at 3x it is 3240, so 1080 of it hangs off each
        // side horizontally, and vertically the 3240-tall image overhangs by 420.
        val far = clampedPan(Offset(5000f, 5000f), scale = 3f, 1080, 1080, phone)
        assertThat(far.x).isWithin(0.5f).of(1080f)
        assertThat(far.y).isWithin(0.5f).of(420f)
        assertThat(clampedPan(Offset(-5000f, -5000f), scale = 3f, 1080, 1080, phone).x).isWithin(0.5f).of(-1080f)
    }

    @Test
    fun `a drag inside the overhang is left alone`() {
        assertThat(clampedPan(Offset(200f, -100f), scale = 3f, 1080, 1080, phone)).isEqualTo(Offset(200f, -100f))
    }

    @Test
    fun `a letterboxed image cannot be dragged into its own margins`() {
        // A wide panorama in a tall viewport: at 2x it still does not fill the height, so vertical pan stays locked
        // even though the horizontal overhang is large.
        val panned = clampedPan(Offset(5000f, 5000f), scale = 2f, 4000, 1000, phone)
        assertThat(panned.x).isWithin(0.5f).of(540f)
        assertThat(panned.y).isEqualTo(0f)
    }

    @Test
    fun `pan is centred before the viewer has been measured`() {
        assertThat(clampedPan(Offset(120f, 120f), scale = 4f, 1080, 1080, IntSize.Zero)).isEqualTo(Offset.Zero)
    }
}
