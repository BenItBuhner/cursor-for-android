package com.cursorforandroid.ui.components

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
}
