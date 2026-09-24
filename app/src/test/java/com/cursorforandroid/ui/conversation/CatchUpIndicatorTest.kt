package com.cursorforandroid.ui.conversation

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas

/**
 * Where the pull's indicator stands — the sidebar's own, turned to rise from the transcript's bottom edge — read off
 * the pixels on its centre line: nothing at rest; drawn up out of the edge with the finger and clipped there, so what
 * is still under it never shows over the composer's see-through stack; at the threshold its own height clear of the
 * edge, as the sidebar's stands under its top. Let go armed it springs to the threshold and stays while the pull is
 * answered, then goes home, and only then is the answer told; a pause is told the same way, and the catch-up after
 * it rises by itself. A word already up gives way as it starts to rise.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CatchUpIndicatorTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val status = MutableStateFlow<CatchUpStatus>(CatchUpStatus.Idle)
    private val settled = mutableListOf<CatchUpStatus>()
    private var rises = 0
    private lateinit var pull: CatchUpPull
    private lateinit var screenDensity: Density
    private var container = Color.Unspecified

    /** The indicator at the bottom of the transcript's area, over a composer's stack that paints nothing; the clock is the test's (the spinner never idles). */
    private fun screen() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                screenDensity = LocalDensity.current
                container = CursorTheme.colors.elevated
                pull = remember { with(screenDensity) { CatchUpPull(CatchUpPullThreshold.toPx()) } }
                Column(Modifier.fillMaxSize().background(Backdrop)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CatchUpIndicator(pull, status, onSettled = { settled += it }, modifier = Modifier.align(Alignment.BottomCenter), onRise = { rises++ })
                    }
                    Box(Modifier.fillMaxWidth().height(96.dp).testTag("composer"))
                }
            }
        }
        settle()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** A second of frames, each taken up before the next as a device's are. */
    private fun settle() = repeat(60) { frame() }

    private val edge: Float get() = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.top

    /** The screen as a software draw of the Compose view, the same draw a Roborazzi screenshot takes. */
    private fun shot(): Bitmap {
        val view = composeView()
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(AndroidCanvas(whole)) }
        return whole
    }

    /**
     * Whether the indicator's disc is drawn [above] dp over the transcript's bottom edge (under it when negative), on
     * its centre line. Read 14 dp off the disc's centre: clear of its arrow, which reaches some 10 dp out from it.
     */
    private fun Bitmap.indicatorAt(above: Float): Boolean {
        val y = (edge - with(screenDensity) { above.dp.toPx() }).roundToInt()
        return channelDistance(getPixel(width / 2, y), container.toArgb()) <= 3
    }

    private fun Bitmap.drawn(vararg above: Float): List<Boolean> = above.map { indicatorAt(it) }

    @Test
    fun `at rest nothing shows, the pull draws it up out of the bottom edge clipped there, and at the threshold it stands clear of the edge`() {
        screen()
        assertThat(shot().drawn(-14f, 14f, 46f, 74f)).containsExactly(false, false, false, false).inOrder()

        // A quarter of the way the disc is half out, centred on the edge: the half still under it is not drawn.
        pull.stretch(pull.thresholdPx * 0.25f)
        frame()
        assertThat(shot().drawn(-14f, 14f, 30f)).containsExactly(false, true, false).inOrder()

        // At the threshold: all of it out and its own height (40 dp) clear of the edge, 40 to 80 dp above it.
        pull.stretch(pull.thresholdPx * 0.75f)
        frame()
        assertThat(pull.distanceFraction).isWithin(0.001f).of(1f)
        assertThat(shot().drawn(30f, 46f, 74f, 90f)).containsExactly(false, true, true, false).inOrder()
    }

    @Test
    fun `an armed release springs it to the threshold and holds it there while the pull is answered, then home, and only then is the answer told`() {
        screen()
        pull.stretch(pull.thresholdPx * 1.6f)
        frame()
        assertThat(pull.release()).isTrue()
        settle()
        assertThat(pull.distanceFraction).isWithin(0.01f).of(1f)
        assertThat(shot().drawn(30f, 46f, 74f)).containsExactly(false, true, true).inOrder()

        status.value = CatchUpStatus.Checking
        settle()
        assertThat(pull.distanceFraction).isWithin(0.01f).of(1f)
        assertThat(settled).isEmpty()

        status.value = CatchUpStatus.Done(newMessages = 2, changed = true)
        frame()
        assertThat(settled).isEmpty()
        settle()
        assertThat(pull.distanceFraction).isEqualTo(0f)
        assertThat(shot().drawn(14f, 46f, 74f)).containsExactly(false, false, false).inOrder()
        assertThat(settled).containsExactly(CatchUpStatus.Done(newMessages = 2, changed = true))
    }

    @Test
    fun `a release short of the threshold goes home with nothing asked or told`() {
        screen()
        pull.stretch(pull.thresholdPx * 0.6f)
        frame()
        assertThat(pull.release()).isFalse()
        settle()
        assertThat(pull.distanceFraction).isEqualTo(0f)
        assertThat(shot().drawn(14f, 46f)).containsExactly(false, false).inOrder()
        assertThat(settled).isEmpty()
        assertThat(pull.pulls).isEqualTo(0)
    }

    @Test
    fun `a pause is told once the indicator is home, and the catch-up after it rises by itself`() {
        AppClock.nowMillis = { 1_000_000L }
        try {
            screen()
            pull.stretch(pull.thresholdPx * 1.2f)
            frame()
            assertThat(pull.release()).isTrue()
            status.value = CatchUpStatus.Waiting(untilMillis = 1_007_000L)
            settle()
            assertThat(pull.distanceFraction).isEqualTo(0f)
            assertThat(settled).containsExactly(CatchUpStatus.Waiting(untilMillis = 1_007_000L))
            assertThat(settled.single().word()).isEqualTo("Cursor asked for a pause · catching up in 7 s")

            status.value = CatchUpStatus.Checking
            settle()
            assertThat(pull.distanceFraction).isWithin(0.01f).of(1f)
            assertThat(shot().drawn(46f, 74f)).containsExactly(true, true).inOrder()
        } finally {
            AppClock.nowMillis = System::currentTimeMillis
        }
    }

    @Test
    fun `a word up gives way once as the indicator starts to rise, whether pulled or caught up by itself`() {
        screen()
        assertThat(rises).isEqualTo(0)
        pull.stretch(4f)
        frame()
        pull.stretch(pull.thresholdPx * 0.4f)
        frame()
        assertThat(rises).isEqualTo(1)
        pull.release()
        settle()
        status.value = CatchUpStatus.Checking
        settle()
        assertThat(rises).isEqualTo(2)
    }

    @Test
    fun `an answer already there when the screen comes back is told once it is home`() {
        status.value = CatchUpStatus.Done(newMessages = 0, changed = false)
        screen()
        assertThat(settled).containsExactly(CatchUpStatus.Done(newMessages = 0, changed = false))
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
        /** Unlike anything the indicator draws. */
        val Backdrop = Color(0xFF00C853)
    }
}
