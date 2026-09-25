package com.cursorforandroid.ui.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Every frame of a push shows a screen: the one being covered, the one arriving, or both. The scene moves in an effect
 * that can run before the recomposition after it, and a pane drawn by the role it was composed in would leave a frame
 * with neither — the covered screen still drawn as the top, at the pushed screen's progress.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PushFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pushFrames(push: (NavStack) -> Unit) {
        val stack = NavStack(Screen.Home)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CursorNavHost(stack) { screen ->
                    Box(Modifier.fillMaxSize().background(if (screen == Screen.Home) Color.Red else Color.Blue))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { push(stack) }
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        repeat(FRAMES) { frame ->
            compose.mainClock.advanceTimeByFrame()
            // Drawn directly: captureToImage waits for a redraw the held clock never lets happen.
            compose.runOnUiThread { root.draw(Canvas(bitmap)) }
            val pixel = bitmap.getPixel(root.width / 2, root.height / 2)
            val shown = android.graphics.Color.red(pixel) + android.graphics.Color.blue(pixel)
            assertWithMessage("frame $frame of the push shows no screen").that(shown).isGreaterThan(100)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun `no frame of a push is empty`() = pushFrames { it.push(Screen.Settings) }

    @Test
    fun `no frame of a gliding push is empty`() = pushFrames { stack -> stack.gliding { stack.push(Screen.Settings) } }

    private companion object {
        const val FRAMES = 30
    }
}
