package com.cursorforandroid.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The predictive back card travels on the x-axis only: however the finger wanders up and down on its way across,
 * neither pane moves vertically, from either edge, while scrubbing, rewinding or finishing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class BackGestureAxisTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher get() = compose.activity.onBackPressedDispatcher

    private fun showSettingsOverHome() {
        val stack = NavStack(Screen.Home)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CursorNavHost(stack) { screen ->
                    Box(Modifier.fillMaxSize().background(if (screen == Screen.Home) Color.Red else Color.Blue).testTag(screen.toString()))
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(Screen.Settings) }
        compose.waitForIdle()
    }

    private fun bounds(screen: Screen): Rect = compose.onNodeWithTag(screen.toString()).fetchSemanticsNode().boundsInRoot

    private fun assertOnlyXMoved(edge: Int) {
        showSettingsOverHome()
        val width = bounds(Screen.Settings).width
        val direction = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f

        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, edge)) }
        compose.waitForIdle()
        STEPS.forEach { (progress, touchY) ->
            compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(progress * 400f, touchY, progress, edge)) }
            compose.waitForIdle()
            val top = bounds(Screen.Settings)
            assertWithMessage("top pane y at progress $progress, touchY $touchY").that(top.top).isEqualTo(0f)
            assertWithMessage("top pane x at progress $progress, touchY $touchY").that(top.left).isWithin(1f).of(direction * progress * width)
            assertWithMessage("pane underneath y at touchY $touchY").that(bounds(Screen.Home).top).isEqualTo(0f)
        }

        // Rewound frame by frame: nothing left over from the finger's height to settle back.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        repeat(REWIND_FRAMES) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertWithMessage("top pane y on rewind frame $frame").that(bounds(Screen.Settings).top).isEqualTo(0f)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertWithMessage("top pane after the rewind").that(bounds(Screen.Settings)).isEqualTo(Rect(0f, 0f, width, bounds(Screen.Settings).height))
    }

    @Test
    fun `a left-edge swipe wandering up and down moves the card on x only`() = assertOnlyXMoved(BackEventCompat.EDGE_LEFT)

    @Test
    fun `a right-edge swipe wandering up and down moves the card on x only`() = assertOnlyXMoved(BackEventCompat.EDGE_RIGHT)

    private companion object {
        /** Progress with the finger's height, which climbs, dives, and wobbles through 780–785 on the way. */
        val STEPS = listOf(
            0.05f to 600f, 0.1f to 780f, 0.15f to 781f, 0.2f to 785f, 0.25f to 782f, 0.3f to 783f,
            0.35f to 784f, 0.4f to 120f, 0.5f to 1800f, 0.6f to 0f, 0.7f to 785f,
        )
        const val REWIND_FRAMES = 24
    }
}
