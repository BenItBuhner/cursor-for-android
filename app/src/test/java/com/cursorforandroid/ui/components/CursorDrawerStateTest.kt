package com.cursorforandroid.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.DrawerValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unfolding a foldable mid-drag hands the sidebar from the drawer to the rail, and the drawer is snapped shut on the
 * way out. The snap has to win: the sheet it would otherwise leave half open is drawn again, without a working scrim
 * or back handler, the moment the window folds back.
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
}
