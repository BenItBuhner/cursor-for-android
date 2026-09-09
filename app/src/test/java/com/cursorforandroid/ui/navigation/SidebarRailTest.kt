package com.cursorforandroid.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Steps the clock through the rail's slide and reads where the detail pane's left edge is at each point: at rest it
 * sits past the rail and its hairline, and while the rail is on the move it must be somewhere in between — the cut the
 * rail used to make would take it from one to the other in a single frame.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
class SidebarRailTest {

    @get:Rule
    val compose = createComposeRule()

    private var expanded by mutableStateOf(true)

    private val railWidth: Dp = CursorDimens.sidebarWidth + CursorDimens.hairline

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Row(Modifier.fillMaxSize()) {
                    SidebarRail(expanded = expanded) { Box(Modifier.fillMaxSize().testTag("rail")) }
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("detail"))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * Flips the rail and runs the frame that starts its slide. The clock is paused, so the write has to be delivered
     * to the composition by hand (`waitForIdle` drains the main looper, which is where snapshot changes are announced
     * from) before a frame is pumped.
     */
    private fun toggle(to: Boolean) {
        expanded = to
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun detailLeft(): Float = compose.onNodeWithTag("detail").getBoundsInRoot().left.value

    private fun assertAtRest(expectedLeft: Dp) {
        assertThat(detailLeft()).isWithin(0.5f).of(expectedLeft.value)
    }

    /** Advances into the slide and checks the rail is neither where it was nor where it is going. */
    private fun assertMidway() {
        compose.mainClock.advanceTimeBy(SidebarRailMillis / 2L)
        val left = detailLeft()
        assertThat(left).isGreaterThan(1f)
        assertThat(left).isLessThan(railWidth.value - 1f)
    }

    private fun finishSlide() {
        compose.mainClock.advanceTimeBy(SidebarRailMillis.toLong())
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun railShownAtRestOnFirstFrame() {
        // A layout that comes up with the rail open (unfolding, rotating) shows it in place, not sliding in.
        show()
        assertAtRest(railWidth)
        compose.onNodeWithTag("rail").assertExists()
    }

    @Test
    fun collapsingAndExpandingSlide() {
        show()

        toggle(false)
        assertMidway()
        finishSlide()
        assertAtRest(Dp(0f))
        compose.onNodeWithTag("rail").assertDoesNotExist()

        toggle(true)
        assertMidway()
        finishSlide()
        assertAtRest(railWidth)
        compose.onNodeWithTag("rail").assertExists()
    }

    @Test
    fun reversingMidSlideTurnsBack() {
        show()
        toggle(false)
        compose.mainClock.advanceTimeBy(SidebarRailMillis / 2L)
        val partWayOut = detailLeft()

        // Tapping the toggle again before the rail has gone brings it back from where it is, not from nothing.
        toggle(true)
        compose.mainClock.advanceTimeBy(SidebarRailMillis / 4L)
        assertThat(detailLeft()).isGreaterThan(partWayOut)
        // An interrupted slide is finished on a spring rather than the tween's fixed clock, so it is given room to settle.
        compose.mainClock.advanceTimeBy(2_000)
        assertAtRest(railWidth)
    }
}
