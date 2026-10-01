package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.google.common.truth.Truth.assertWithMessage
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
 * The pull as drawn: the transcript lifted off the composer ([catchUpLift]) and the indicator in the gap, both read
 * off the pixels of one line down the screen. Nothing at rest. With the finger the newest row rises and the indicator
 * rises half as far, centred between the row and the composer — as far from each — and grows into the gap rather than
 * covering either side. Released short of the threshold, both spring home together, decelerating, with nothing asked;
 * released armed, the transcript settles at the held gap with the indicator spinning in the middle of it while the
 * pull is answered, then both go home together, and only then is the answer told; a pause is told the same way. A word
 * already up gives way as the indicator starts to rise.
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

    /**
     * The transcript's area over a composer's stack that paints nothing: its newest row resting [EdgeGap] over the
     * area's bottom edge, lifted by the pull, and the indicator over it. The clock is the test's (the spinner never idles).
     */
    private fun screen() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                screenDensity = LocalDensity.current
                container = CursorTheme.colors.elevated
                pull = remember { catchUpPullFor(screenDensity) }
                Column(Modifier.fillMaxSize().background(Backdrop)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxSize().catchUpLift(pull).padding(bottom = EdgeGap),
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                        ) {
                            repeat(3) { Box(Modifier.fillMaxWidth().height(64.dp).background(Row)) }
                        }
                        CatchUpIndicator(pull, status, onSettled = { settled += it }, modifier = Modifier.align(Alignment.BottomCenter), edgeGap = EdgeGap, onRise = { rises++ })
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

    private fun px(dp: Float): Float = with(screenDensity) { dp.dp.toPx() }

    private val edge: Float get() = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.top

    /** The screen as a software draw of the Compose view, the same draw a Roborazzi screenshot takes. */
    private fun shot(): Bitmap {
        val view = composeView()
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(AndroidCanvas(whole)) }
        return whole
    }

    /**
     * What one line down the screen shows, 14 dp off the indicator's centre (clear of its arrow, which reaches some
     * 10 dp out from it): where the newest row ends, and where the indicator's disc starts and ends, if drawn; and
     * whether any of the disc is drawn over the composer's stack.
     */
    private data class Line(val rowBottom: Float, val discTop: Float?, val discBottom: Float?, val discUnderEdge: Boolean) {
        val discCentre: Float? get() = if (discTop != null && discBottom != null) (discTop + discBottom) / 2f else null
    }

    private fun Bitmap.line(): Line {
        val x = (width / 2f + px(14f)).roundToInt()
        val edgeY = edge.roundToInt()
        var rowBottom = -1
        var discTop = -1
        var discBottom = -1
        var under = false
        for (y in 0 until height) {
            val pixel = getPixel(x, y)
            if (y < edgeY && close(pixel, Row.toArgb())) rowBottom = y + 1
            if (close(pixel, container.toArgb())) {
                if (y >= edgeY) under = true
                if (discTop < 0) discTop = y
                discBottom = y + 1
            }
        }
        return Line(rowBottom.toFloat(), discTop.takeIf { it >= 0 }?.toFloat(), discBottom.takeIf { it >= 0 }?.toFloat(), under)
    }

    @Test
    fun `at rest nothing shows, and the newest row rests the edge gap over the composer`() {
        screen()
        val line = shot().line()
        assertThat(line.discTop).isNull()
        assertThat(line.rowBottom).isWithin(1.5f).of(edge - px(EdgeGap.value))
    }

    @Test
    fun `the indicator travels half as far as the transcript, centred in the gap with even room above and below`() {
        screen()
        val rest = shot().line().rowBottom
        val readings = listOf(0.55f, 0.75f, 1f, 1.5f, 2.5f).map { share ->
            pull.stretch(pull.thresholdPx * share - pull.distance)
            frame()
            val line = shot().line()
            val lifted = rest - line.rowBottom
            assertWithMessage("lift at $share of the threshold").that(lifted).isWithin(1.5f).of(pull.lift)
            val top = checkNotNull(line.discTop) { "no indicator at $share of the threshold" }
            val bottom = checkNotNull(line.discBottom)
            // As far from the newest row as from the composer.
            assertWithMessage("room above and below at $share").that(top - line.rowBottom).isWithin(2f).of(edge - bottom)
            assertThat(line.discUnderEdge).isFalse()
            lifted to (edge - checkNotNull(line.discCentre))
        }
        // Between any two readings the transcript moved twice as far as the indicator.
        readings.zipWithNext { (liftA, centreA), (liftB, centreB) ->
            assertThat(liftB - liftA).isGreaterThan(px(2f))
            assertWithMessage("transcript ${liftB - liftA} px, indicator ${centreB - centreA} px").that((liftB - liftA) / (centreB - centreA)).isWithin(0.12f).of(2f)
        }
        // And its centre is half the whole gap up: the room at rest plus the lift.
        readings.forEach { (lift, centre) -> assertThat(centre).isWithin(1.5f).of((px(EdgeGap.value) + lift) / 2f) }
    }

    @Test
    fun `it grows into the gap, never drawn over the newest row or the composer while the gap is too small for it`() {
        screen()
        listOf(4f, 10f, 20f, 32f, 44f).forEach { lift ->
            pull.stretch(catchUpFingerFor(px(lift), pull.reachPx) - pull.distance)
            frame()
            val line = shot().line()
            assertThat(line.discUnderEdge).isFalse()
            line.discTop?.let { assertWithMessage("disc top at a $lift dp lift").that(it).isAtLeast(line.rowBottom) }
        }
        assertThat(catchUpIndicatorScale(px(EdgeGap.value), px(40f), px(8f))).isEqualTo(0f)
        assertThat(catchUpIndicatorScale(px(56f), px(40f), px(8f))).isEqualTo(1f)
    }

    @Test
    fun `a release short of the threshold springs both home, decelerating, with nothing asked or told`() {
        screen()
        val rest = shot().line().rowBottom
        pull.stretch(pull.thresholdPx * 0.9f)
        frame()
        assertThat(pull.armed).isFalse()
        val lifts = mutableListOf(pull.lift)
        assertThat(pull.release()).isFalse()
        repeat(40) {
            frame()
            lifts += pull.lift
        }
        // Every frame lower than the last until home, and never below it: no bounce past the composer.
        lifts.zipWithNext { a, b -> assertThat(b).isAtMost(a) }
        assertThat(lifts.min()).isAtLeast(0f)
        // Not a constant speed: the spring is quicker leaving the finger than settling home.
        val steps = lifts.zipWithNext { a, b -> a - b }.filter { it > 0.01f }
        assertThat(steps.size).isAtLeast(6)
        assertThat(steps.takeLast(3).average()).isLessThan(steps.max() / 3.0)
        settle()
        assertThat(pull.lift).isEqualTo(0f)
        val line = shot().line()
        assertThat(line.discTop).isNull()
        assertThat(line.rowBottom).isWithin(1.5f).of(rest)
        assertThat(settled).isEmpty()
        assertThat(pull.pulls).isEqualTo(0)
    }

    @Test
    fun `an armed release settles the transcript at the held gap, spinning in the middle of it, then both go home together`() {
        screen()
        pull.stretch(pull.thresholdPx * 1.6f)
        frame()
        assertThat(pull.release()).isTrue()
        status.value = CatchUpStatus.Checking
        settle()
        assertThat(pull.lift).isWithin(0.5f).of(pull.heldPx)
        val held = shot().line()
        assertThat(edge - checkNotNull(held.discCentre)).isWithin(1.5f).of((px(EdgeGap.value) + pull.heldPx) / 2f)
        settle()
        assertThat(pull.lift).isWithin(0.5f).of(pull.heldPx)
        assertThat(settled).isEmpty()

        status.value = CatchUpStatus.Done(newMessages = 2, changed = true)
        // On the way home the indicator stays in the middle of the closing gap.
        repeat(6) { frame() }
        val going = shot().line()
        assertThat(pull.lift).isLessThan(pull.heldPx - px(4f))
        going.discCentre?.let { centre -> assertThat(edge - centre).isWithin(1.5f).of((px(EdgeGap.value) + pull.lift) / 2f) }
        settle()
        assertThat(pull.lift).isEqualTo(0f)
        assertThat(shot().line().discTop).isNull()
        assertThat(settled).isEmpty()
    }

    @Test
    fun `a failure is told only once the indicator is home`() {
        screen()
        pull.stretch(pull.thresholdPx * 1.6f)
        frame()
        assertThat(pull.release()).isTrue()
        status.value = CatchUpStatus.Checking
        settle()
        status.value = CatchUpStatus.Failed("Cursor couldn't be reached. Check your connection.")
        frame()
        assertThat(settled).isEmpty()
        settle()
        assertThat(pull.lift).isEqualTo(0f)
        assertThat(settled).containsExactly(CatchUpStatus.Failed("Cursor couldn't be reached. Check your connection."))
    }

    @Test
    fun `a pause is spun through at the held gap with nothing told, and the catch-up after it carries on there`() {
        AppClock.nowMillis = { 1_000_000L }
        try {
            screen()
            pull.stretch(pull.thresholdPx * 1.2f)
            frame()
            assertThat(pull.release()).isTrue()
            status.value = CatchUpStatus.Waiting(untilMillis = 1_007_000L)
            settle()
            assertThat(pull.lift).isWithin(0.5f).of(pull.heldPx)
            assertThat(settled).isEmpty()
            assertThat(CatchUpStatus.Waiting(untilMillis = 1_007_000L).word()).isNull()

            status.value = CatchUpStatus.Checking
            settle()
            assertThat(pull.lift).isWithin(0.5f).of(pull.heldPx)
            assertThat(shot().line().discTop).isNotNull()
        } finally {
            AppClock.nowMillis = System::currentTimeMillis
        }
    }

    @Test
    fun `a catch-up asked without a pull lifts the transcript to the held gap by itself`() {
        screen()
        status.value = CatchUpStatus.Checking
        settle()
        assertThat(pull.lift).isWithin(0.5f).of(pull.heldPx)
        assertThat(shot().line().discTop).isNotNull()
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
    fun `a failure already there when the screen comes back is told once it is home, and an answer is not`() {
        status.value = CatchUpStatus.Failed("stale")
        screen()
        assertThat(settled).containsExactly(CatchUpStatus.Failed("stale"))
        settled.clear()
        status.value = CatchUpStatus.Done(newMessages = 0, changed = false)
        settle()
        assertThat(settled).isEmpty()
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun close(a: Int, b: Int): Boolean = maxOf(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        abs((a and 0xFF) - (b and 0xFF)),
    ) <= 3

    private companion object {
        /** Unlike anything the indicator draws. */
        val Backdrop = Color(0xFF00C853)
        val Row = Color(0xFF2962FF)
        val EdgeGap = 12.dp
    }
}
