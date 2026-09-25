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
 * or back handler, the moment the window folds back. The keyboard's slide (Ctrl+B) sets off a frame ahead, so the
 * first frame drawn after the key already has the sheet on its way, and turns a slide under way back from where it is.
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
    fun `Ctrl+B's slide has the sheet moving in its first frame, where a tap's first frame is where it starts`() = runTest {
        val keyed = CursorDrawerState(DrawerValue.Closed)
        val tapped = CursorDrawerState(DrawerValue.Closed)
        launch(VirtualFrames(this)) { keyed.open(keyed = true) }
        launch(VirtualFrames(this)) { tapped.open() }

        advanceTimeBy(17)

        assertThat(keyed.fraction).isGreaterThan(0f)
        assertThat(keyed.fraction).isLessThan(0.1f)
        assertThat(tapped.fraction).isEqualTo(0f)
        advanceUntilIdle()
        assertThat(keyed.fraction).isEqualTo(1f)
        assertThat(keyed.isOpen).isTrue()
        assertThat(keyed.isAnimating).isFalse()
    }

    @Test
    fun `Ctrl+B turns a slide under way back from where it has got to, without a jump`() = runTest {
        val state = CursorDrawerState(DrawerValue.Closed)
        val slide = launch(VirtualFrames(this)) { state.slideTo(DrawerValue.Open) }
        advanceTimeBy(100)
        val midway = state.fraction
        assertThat(midway).isGreaterThan(0f)
        assertThat(midway).isLessThan(1f)

        launch(VirtualFrames(this)) { state.close(keyed = true) }
        advanceTimeBy(17)

        assertThat(state.fraction).isLessThan(midway)
        assertThat(state.fraction).isGreaterThan(0f)
        assertThat(state.isOpen).isFalse()
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
