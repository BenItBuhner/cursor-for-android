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
 * Ctrl+Shift+B and Esc put the chat's panel where they say in the call itself, since the frame the key lands in is the
 * one that has to show it, whatever the sheet was doing: sliding for a tap, or following a finger.
 */
class SidePanelStateTest {

    @Test
    fun `a jump opens the sheet fully in the call itself`() = runTest {
        val state = SidePanelState(SidePanelValue.Closed)

        state.jumpTo(SidePanelValue.Open, this)

        assertThat(state.fraction).isEqualTo(1f)
        assertThat(state.isOpen).isTrue()
        assertThat(state.isVisible).isTrue()
    }

    @Test
    fun `a jump shuts the sheet at once, and the slide under way moves it no further and is let go of`() = runTest {
        val state = SidePanelState(SidePanelValue.Closed)
        val slide = launch(VirtualFrames(this)) { state.slideTo(SidePanelValue.Open) }
        advanceTimeBy(100)
        assertThat(state.fraction).isGreaterThan(0f)
        assertThat(state.fraction).isLessThan(1f)

        state.jumpTo(SidePanelValue.Closed, this)

        assertThat(state.fraction).isEqualTo(0f)
        assertThat(state.isVisible).isFalse()
        advanceTimeBy(48)
        assertThat(state.fraction).isEqualTo(0f)
        advanceUntilIdle()
        assertThat(slide.isCancelled).isTrue()
        assertThat(state.isAnimating).isFalse()
    }

    @Test
    fun `a jump during a drag lands the sheet and ends the drag`() = runTest {
        val state = SidePanelState(SidePanelValue.Closed)
        state.widthPx = 1000f
        val drag = launch(start = CoroutineStart.UNDISPATCHED) {
            state.draggableState.drag(MutatePriority.UserInput) {
                dragBy(400f)
                awaitCancellation()
            }
        }
        assertThat(state.fraction).isWithin(0.001f).of(0.4f)

        state.jumpTo(SidePanelValue.Open, this)

        assertThat(state.fraction).isEqualTo(1f)
        assertThat(state.isOpen).isTrue()
        advanceUntilIdle()
        assertThat(drag.isCancelled).isTrue()
        assertThat(state.fraction).isEqualTo(1f)
    }
}
