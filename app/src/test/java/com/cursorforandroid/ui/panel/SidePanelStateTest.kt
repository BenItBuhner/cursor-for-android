package com.cursorforandroid.ui.panel

import androidx.compose.foundation.MutatePriority
import com.cursorforandroid.ui.components.VirtualFrames
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ctrl+Shift+B and Esc slide the chat's panel as its buttons do, but set off a frame ahead: the first frame drawn after
 * the key already has the panel on its way, where a tap's first frame shows it where it starts. A slide under way is
 * turned back from where it has got to; a finger holding the sheet keeps it.
 */
class SidePanelStateTest {

    @Test
    fun `a key's slide has the sheet moving in its first frame, where a tap's first frame is where it starts`() = runTest {
        val keyed = SidePanelState(SidePanelValue.Closed)
        val tapped = SidePanelState(SidePanelValue.Closed)
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
    fun `a key turns a slide under way back from where it has got to, without a jump`() = runTest {
        val state = SidePanelState(SidePanelValue.Closed)
        val slide = launch(VirtualFrames(this)) { state.slideTo(SidePanelValue.Open) }
        advanceTimeBy(100)
        val midway = state.fraction
        assertThat(midway).isGreaterThan(0f)
        assertThat(midway).isLessThan(1f)

        launch(VirtualFrames(this)) { state.close(keyed = true) }
        advanceTimeBy(17)

        assertThat(state.fraction).isLessThan(midway)
        assertThat(state.fraction).isGreaterThan(0f)
        assertThat(state.targetValue).isEqualTo(SidePanelValue.Closed)
        advanceUntilIdle()
        assertThat(slide.isCancelled).isTrue()
        assertThat(state.fraction).isEqualTo(0f)
        assertThat(state.isVisible).isFalse()
    }

    @Test
    fun `a key during a drag leaves the sheet to the finger`() = runTest {
        val state = SidePanelState(SidePanelValue.Closed)
        state.widthPx = 1000f
        val drag = launch(start = CoroutineStart.UNDISPATCHED) {
            state.draggableState.drag(MutatePriority.UserInput) {
                dragBy(400f)
                awaitCancellation()
            }
        }
        val key = launch(VirtualFrames(this)) { state.open(keyed = true) }

        advanceTimeBy(100)

        assertThat(key.isCancelled).isTrue()
        assertThat(state.fraction).isWithin(0.001f).of(0.4f)
        drag.cancel()
    }
}
