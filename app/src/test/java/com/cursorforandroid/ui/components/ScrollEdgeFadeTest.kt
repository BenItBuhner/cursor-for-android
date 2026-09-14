package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The painted edge fade — a gradient of the surface colour over the list, no offscreen layer — is the same picture as
 * the offscreen dissolve it replaces on the lists the keyboard resizes, as long as the list sits on that flat colour:
 * the content keeps the same share at every row of the fade in either.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ScrollEdgeFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** A list scrolled into its middle, so both edges have content past them and both fades are on. */
    @Composable
    private fun FadedList(surface: Color, tag: String) {
        val state = rememberLazyListState(initialFirstVisibleItemIndex = 4)
        Box(Modifier.size(160.dp, 200.dp).background(Canvas).testTag(tag)) {
            LazyColumn(Modifier.fillMaxSize().scrollEdgeFade(state, surface = surface), state = state) {
                items(30) { index -> Box(Modifier.fillMaxWidth().height(24.dp).background(if (index % 2 == 0) Blue else Orange)) }
            }
        }
    }

    @Test
    fun `the painted fade over the canvas is the offscreen dissolve pixel for pixel`() {
        compose.setContent {
            Column {
                FadedList(surface = Color.Unspecified, tag = "dissolve")
                FadedList(surface = Canvas, tag = "painted")
            }
        }
        // The fades ease in over 180 ms once the list reports content past its edges.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        // A software draw has no offscreen layer to isolate the dissolve in, so its DstIn mask reaches the pixels under
        // the list and leaves them with the mask's alpha instead of the canvas. Laid over the canvas they become what
        // the hardware pipeline shows, and what the painted fade has to match.
        val dissolve = capture("dissolve").over(Canvas)
        val painted = capture("painted")
        assertThat(painted.width).isEqualTo(dissolve.width)
        assertThat(painted.height).isEqualTo(dissolve.height)

        var largestDifference = 0
        for (y in 0 until dissolve.height) {
            for (x in 0 until dissolve.width) {
                largestDifference = maxOf(largestDifference, channelDistance(dissolve.getPixel(x, y), painted.getPixel(x, y)))
            }
        }
        // Gradient interpolation and blending round independently in the two paths; a few levels is all that may differ.
        assertThat(largestDifference).isAtMost(3)

        // And the fade is really there in both: the outermost row is the canvas, the middle is the untouched content.
        val middle = dissolve.height / 2
        assertThat(channelDistance(dissolve.getPixel(8, 0), Canvas.toArgb())).isAtMost(3)
        assertThat(channelDistance(painted.getPixel(8, 0), Canvas.toArgb())).isAtMost(3)
        assertThat(setOf(Blue.toArgb(), Orange.toArgb())).contains(painted.getPixel(8, middle))
        assertThat(painted.getPixel(8, middle)).isEqualTo(dissolve.getPixel(8, middle))
    }

    /** The node's pixels, from a software draw of the Compose view — the same draw a Roborazzi screenshot takes. */
    private fun capture(tag: String): Bitmap {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val view = composeView()
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(AndroidCanvas(whole)) }
        return Bitmap.createBitmap(whole, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
    }

    private fun Bitmap.over(background: Color): Bitmap {
        val flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        AndroidCanvas(flattened).apply {
            drawColor(background.toArgb())
            drawBitmap(this@over, 0f, 0f, null)
        }
        return flattened
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun channelDistance(a: Int, b: Int): Int = maxOf(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        abs((a and 0xFF) - (b and 0xFF)),
    )

    private companion object {
        val Canvas = Color(0xFF141414)
        val Blue = Color(0xFF3B82F6)
        val Orange = Color(0xFFF59E0B)
    }
}
