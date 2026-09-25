package com.cursorforandroid.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.setOffAhead
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Steps the clock through the rail's slide and reads where the detail pane's left edge is at each point: at rest it
 * sits past the rail, whose hairline is drawn inside its width, and while the rail is on the move it must be somewhere
 * in between — the cut the rail used to make would take it from one to the other in a single frame.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
class SidebarRailTest {

    @get:Rule
    val compose = createComposeRule()

    private var expanded by mutableStateOf(true)
    private var animate by mutableStateOf(true)

    private val railWidth: Dp = CursorDimens.sidebarWidth

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Row(Modifier.fillMaxSize()) {
                    SidebarRail(expanded = expanded, animate = animate) { Box(Modifier.fillMaxSize().testTag("rail")) }
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
    private fun toggle(to: Boolean, slides: Boolean = true) {
        expanded = to
        animate = slides
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
        compose.mainClock.advanceTimeBy(2_000)
        assertAtRest(railWidth)
    }

    @Test
    fun `a change that does not slide is in place in the frame it lands, a slide under way dropped where it was`() {
        show()
        // The window folded or turned under the rail: gone, and back, each within the one frame the change runs.
        toggle(false, slides = false)
        assertAtRest(Dp(0f))
        compose.onNodeWithTag("rail").assertDoesNotExist()
        toggle(true, slides = false)
        assertAtRest(railWidth)
        compose.onNodeWithTag("rail").assertExists()

        // A tap starts the slide out; the window changing half-way through puts the rail back at once.
        toggle(false)
        assertMidway()
        toggle(true, slides = false)
        assertAtRest(railWidth)
        compose.mainClock.advanceTimeBy(SidebarRailMillis.toLong())
        assertAtRest(railWidth)

        // And a tap after it slides again.
        toggle(false)
        assertMidway()
        finishSlide()
        assertAtRest(Dp(0f))
    }

    @Test
    fun `a slide set off by whoever moved the rail, a frame ahead, moves it in its first frame and is left to go on`() {
        val shown = Animatable(1f)
        lateinit var scope: CoroutineScope
        compose.mainClock.autoAdvance = false
        compose.setContent {
            scope = rememberCoroutineScope()
            CursorTheme(mode = ThemeMode.Dark) {
                Row(Modifier.fillMaxSize()) {
                    SidebarRail(expanded = expanded, animate = animate, shown = shown) { Box(Modifier.fillMaxSize().testTag("rail")) }
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("detail"))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        assertAtRest(railWidth)

        // Ctrl+B as the shell sets it off: before the next frame and a frame ahead, as it collapses the rail.
        compose.runOnUiThread {
            expanded = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) { shown.animateTo(0f, sidebarRailSlide<Float>(1f).setOffAhead()) }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val first = detailLeft()
        assertThat(first).isLessThan(railWidth.value - 0.5f)
        assertThat(first).isGreaterThan(railWidth.value - 20f)
        // The rail hears of the collapse as it is composed and leaves the slide under way be, rather than starting
        // one of its own from where it has got to, which would hold it still for a frame.
        compose.mainClock.advanceTimeByFrame()
        assertThat(detailLeft()).isLessThan(first - 0.5f)
        finishSlide()
        assertAtRest(Dp(0f))
        compose.onNodeWithTag("rail").assertDoesNotExist()
    }
}
