package com.cursorforandroid.ui.media

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A page's picture replaced by a sharper decode: the one it replaces is kept under it and the new one fades in over
 * [UpgradeFadeMillis], rather than swapping in one frame; a smaller decode never replaces a larger one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PagePresentationUpgradeTest {

    /** Frames 16 ms apart, as fast as the fade asks for them, counted. */
    private class SteppingClock : MonotonicFrameClock {
        var frames = 0
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            frames++
            nanos += 16_000_000L
            return onFrame(nanos)
        }
    }

    @Test
    fun `a sharper decode fades in over the picture it replaces, then lets it go`() {
        val tile = ImageBitmap(54, 120)
        val sharp = ImageBitmap(720, 1600)
        val presentation = PagePresentation(tile)
        assertThat(presentation.previous).isNull()
        assertThat(presentation.upgrade).isEqualTo(1f)

        presentation.offer(sharp)
        assertThat(presentation.bitmap).isSameInstanceAs(sharp)
        assertThat(presentation.previous).isSameInstanceAs(tile)
        assertThat(presentation.upgrade).isEqualTo(0f)

        val clock = SteppingClock()
        runBlocking(clock) { presentation.playUpgrade() }
        assertThat(presentation.upgrade).isEqualTo(1f)
        assertThat(presentation.previous).isNull()
        assertThat(presentation.bitmap).isSameInstanceAs(sharp)
        // About ten frames of blend, not one.
        assertThat(clock.frames).isAtLeast(UpgradeFadeMillis / 16)
    }

    @Test
    fun `a decode landing mid-fade takes the fading one's place without starting over`() {
        val tile = ImageBitmap(54, 120)
        val presentation = PagePresentation(tile)
        presentation.offer(ImageBitmap(240, 533))
        val sharper = ImageBitmap(720, 1600)
        presentation.offer(sharper)
        assertThat(presentation.bitmap).isSameInstanceAs(sharper)
        assertThat(presentation.previous).isSameInstanceAs(tile)
        assertThat(presentation.upgrade).isEqualTo(0f)
    }

    @Test
    fun `a smaller or equal decode never fades, and a smaller one never replaces`() {
        val sharp = ImageBitmap(720, 1600)
        val presentation = PagePresentation(sharp)
        presentation.offer(ImageBitmap(240, 533))
        assertThat(presentation.bitmap).isSameInstanceAs(sharp)
        assertThat(presentation.previous).isNull()

        val same = ImageBitmap(720, 1600)
        presentation.offer(same)
        assertThat(presentation.bitmap).isSameInstanceAs(same)
        assertThat(presentation.previous).isNull()
        assertThat(presentation.upgrade).isEqualTo(1f)
        runBlocking(SteppingClock()) { presentation.playUpgrade() }
        assertThat(presentation.bitmap).isSameInstanceAs(same)
    }

    @Test
    fun `a first picture on an empty page is shown at once`() {
        val presentation = PagePresentation(null)
        val first = ImageBitmap(360, 800)
        presentation.offer(first)
        assertThat(presentation.bitmap).isSameInstanceAs(first)
        assertThat(presentation.previous).isNull()
        assertThat(presentation.upgrade).isEqualTo(1f)
    }
}
