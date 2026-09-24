package com.cursorforandroid.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.MonotonicFrameClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unfolding a foldable mid-drag hands the sidebar from the drawer to the rail, and the drawer is snapped shut on the
 * way out. The snap has to win: the sheet it would otherwise leave half open is drawn again, without a working scrim
 * or back handler, the moment the window folds back. The keyboard's jump (Ctrl+B) wins the same way, and in the call
 * itself, since the frame the key lands in is the one that has to show it.
 */
class CursorDrawerStateTest {

    @Test
    fun `a snap during a drag lands the sheet instead of leaving the target ahead of it`() = runTest {
        val state = CursorDrawerState(DrawerValue.Closed)
        state.widthPx = 1000f
        val drag = launch(start = CoroutineStart.UNDISPATCHED) {
            state.draggableState.drag(MutatePriority.UserInput) {
                dragBy(600f)
                awaitCancellation()
            }
        }
        assertThat(state.fraction).isWithin(0.001f).of(0.6f)

        state.snapTo(DrawerValue.Closed)

        assertThat(state.fraction).isEqualTo(0f)
        assertThat(state.targetValue).isEqualTo(DrawerValue.Closed)
        assertThat(state.isOpen).isFalse()
        drag.join()
    }

    @Test
    fun `a snap the other way opens the sheet fully`() = runTest {
        val state = CursorDrawerState(DrawerValue.Closed)

        state.snapTo(DrawerValue.Open)

        assertThat(state.fraction).isEqualTo(1f)
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `Ctrl+B's jump opens the sheet fully in the call itself`() = runTest {
        val state = CursorDrawerState(DrawerValue.Closed)

        state.jumpTo(DrawerValue.Open, this)

        assertThat(state.fraction).isEqualTo(1f)
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `a jump lands the sheet at once, and a slide under way moves it no further and is let go of`() = runTest {
        val state = CursorDrawerState(DrawerValue.Closed)
        val slide = launch(VirtualFrames(this)) { state.slideTo(DrawerValue.Open) }
        advanceTimeBy(100)
        assertThat(state.fraction).isGreaterThan(0f)
        assertThat(state.fraction).isLessThan(1f)

        state.jumpTo(DrawerValue.Closed, this)

        assertThat(state.fraction).isEqualTo(0f)
        assertThat(state.isOpen).isFalse()
        advanceTimeBy(48)
        assertThat(state.fraction).isEqualTo(0f)
        advanceUntilIdle()
        assertThat(slide.isCancelled).isTrue()
        assertThat(state.isAnimating).isFalse()
        assertThat(state.fraction).isEqualTo(0f)
    }
}

/** A frame every 16ms of the test's virtual time, for the slides, which are timed by a frame clock. */
internal class VirtualFrames(private val scope: TestScope) : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        delay(16)
        return onFrame(scope.testScheduler.currentTime * 1_000_000)
    }
}
