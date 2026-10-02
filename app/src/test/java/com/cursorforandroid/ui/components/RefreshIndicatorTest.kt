package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.conversation.CATCH_UP_TEST_TAG
import com.cursorforandroid.ui.conversation.CatchUpIndicator
import com.cursorforandroid.ui.conversation.CatchUpPull
import com.cursorforandroid.ui.conversation.CatchUpStatus
import com.cursorforandroid.ui.conversation.catchUpFingerFor
import com.cursorforandroid.ui.conversation.catchUpPullFor
import com.cursorforandroid.ui.theme.CursorColors
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
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
 * The one refresh indicator, as the two pulls draw it: the sidebar's ([RefreshIndicator], Material's own box) and
 * the chat's ([CatchUpIndicator], turned a half turn for a pull that comes up from below) are the same picture at the
 * same point of the pull, pixel for pixel, in the dark theme and the light; and the disc stands on the cards' hairline
 * rather than a shadow, so it reads on the sidebar's own colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RefreshIndicatorTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var colors: CursorColors
    private lateinit var density: Density
    private lateinit var pull: CatchUpPull
    private var sidebarFraction by mutableStateOf(0f)
    private var sidebarRefreshing by mutableStateOf(false)
    private var edgeGapPx by mutableStateOf(PullsEdgeGapPx)
    private val status = MutableStateFlow<CatchUpStatus>(CatchUpStatus.Idle)

    /** A pull that stands where it is told, for the sidebar's indicator. */
    private inner class StoodPull : PullToRefreshState {
        override val distanceFraction: Float get() = sidebarFraction
        override val isAnimating: Boolean get() = false
        override suspend fun animateToThreshold() = Unit
        override suspend fun animateToHidden() = Unit
        override suspend fun snapTo(targetValue: Float) = Unit
    }

    /** Both indicators over one backdrop: the sidebar's in the top half, the chat's at the bottom of the lower half. */
    private fun screen(mode: ThemeMode) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                colors = CursorTheme.colors
                density = LocalDensity.current
                pull = remember { catchUpPullFor(density) }
                Column(Modifier.fillMaxWidth().background(colors.sidebar)) {
                    Box(Modifier.fillMaxWidth().height(Half)) {
                        RefreshIndicator(remember { StoodPull() }, sidebarRefreshing, Modifier.align(Alignment.TopCenter))
                    }
                    Box(Modifier.fillMaxWidth().height(Half)) {
                        CatchUpIndicator(pull, status, onSettled = {}, modifier = Modifier.align(Alignment.BottomCenter), edgeGap = with(density) { edgeGapPx.toDp() })
                    }
                }
            }
        }
        settle()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** A second of frames: every tween and spring in either indicator has settled. */
    private fun settle() = repeat(60) { frame() }

    /**
     * Both pulls at [fraction] of their threshold: the sidebar's stood there, the chat's lifted exactly there — by the
     * finger's travel that lifts the transcript that share of its lift, since the rubber band makes the two unalike.
     */
    private fun pullBothTo(fraction: Float) {
        sidebarFraction = fraction
        pull.stretch(catchUpFingerFor(fraction * pull.liftPx, pull.reachPx) - pull.distance)
        settle()
    }

    private fun px(dp: Float): Int = with(density) { dp.dp.toPx() }.roundToInt()

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun shot(): Bitmap {
        val view = composeView()
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(AndroidCanvas(whole)) }
        return whole
    }

    /** The disc with a dp of backdrop round it, so its edge is compared too. */
    private fun Bitmap.disc(tag: String): Bitmap {
        val margin = px(1f)
        val b = bounds(tag)
        return Bitmap.createBitmap(this, b.left.roundToInt() - margin, b.top.roundToInt() - margin, b.width.roundToInt() + 2 * margin, b.height.roundToInt() + 2 * margin)
    }

    /**
     * [a] is [b] turned a half turn: all but a handful of pixels within a few shades of the other's, and none far off.
     * The handful is the arrow's anti-aliased edge, rasterised a touch differently under the turn (under a hundred
     * pixels of twelve thousand, a couple of dozen shades at most where the arrow is solid); the hairline left out
     * would put eight hundred pixels off, another arrow hundreds, by the whole span. [holePx] round the middle is
     * left out of it, for the spinners, which turn on their own clocks.
     */
    private fun assertHalfTurnOf(a: Bitmap, b: Bitmap, what: String, holePx: Int = 0) {
        assertThat(a.width to a.height).isEqualTo(b.width to b.height)
        var worst = 0
        var off = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val dx = x - a.width / 2f
            val dy = y - a.height / 2f
            if (dx * dx + dy * dy < holePx * holePx) continue
            val d = distance(a.getPixel(x, y), b.getPixel(a.width - 1 - x, a.height - 1 - y))
            if (d > 4) off++
            worst = maxOf(worst, d)
        }
        assertWithMessage("$what: the chat's disc, turned round, is the sidebar's; the furthest pixel is $worst shades off").that(worst).isAtMost(40)
        assertWithMessage("$what: $off of ${a.width * a.height} pixels differ by more than a few shades").that(off).isAtMost(a.width * a.height / 100)
    }

    private fun distance(a: Int, b: Int): Int = maxOf(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        abs((a and 0xFF) - (b and 0xFF)),
    )

    private fun samePictureInBothPulls(mode: ThemeMode) {
        screen(mode)
        // Filling, armed, wound on past the threshold: the arrow at three points of the pull. Twenty-firsts, so that
        // at this density both discs stand on whole pixels (Material's 80dp threshold is 210px, the chat's 104dp lift
        // 273px, odd, over an even gap) and neither edge is drawn across a pixel the other's is not.
        listOf(15f / 21f, 1f, 33f / 21f).forEach { fraction ->
            pullBothTo(fraction)
            val whole = shot()
            assertThat(bounds(REFRESH_INDICATOR_TEST_TAG).width).isWithin(1f).of(px(40f).toFloat())
            assertThat(bounds(CATCH_UP_TEST_TAG).width).isWithin(1f).of(px(40f).toFloat())
            assertHalfTurnOf(whole.disc(REFRESH_INDICATOR_TEST_TAG), whole.disc(CATCH_UP_TEST_TAG), "$mode at $fraction of the threshold")
        }
        // Let go armed and answered: both discs, the spinner's ring left out of it (the two spin on their own clocks).
        // The chat is held at a whole 210px, so the gap takes a pixel for its disc to stand on whole pixels again.
        edgeGapPx = HeldEdgeGapPx
        sidebarFraction = 1f
        sidebarRefreshing = true
        pull.release()
        status.value = CatchUpStatus.Checking
        settle()
        val whole = shot()
        assertHalfTurnOf(whole.disc(REFRESH_INDICATOR_TEST_TAG), whole.disc(CATCH_UP_TEST_TAG), "$mode refreshing, round the spinner", holePx = px(11f))
    }

    @Test
    fun `the chat's indicator is the sidebar's turned round, pulling, armed, past the threshold and refreshing, in the dark`() {
        samePictureInBothPulls(ThemeMode.Dark)
    }

    @Test
    fun `the chat's indicator is the sidebar's turned round, pulling, armed, past the threshold and refreshing, in the light`() {
        samePictureInBothPulls(ThemeMode.Light)
    }

    private fun outlinedOnTheCardsHairline(mode: ThemeMode) {
        screen(mode)
        pullBothTo(1f)
        val whole = shot()
        val container = colors.elevated
        val outline = colors.strokeSubtle.compositeOver(container)
        // The sidebar's disc is the sidebar's own colour: the outline is all that tells it from the pane.
        assertThat(container).isEqualTo(colors.sidebar)
        listOf(REFRESH_INDICATOR_TEST_TAG, CATCH_UP_TEST_TAG).forEach { tag ->
            val b = bounds(tag)
            val y = b.center.y.roundToInt()
            // On the disc's own row its edge runs straight up and down: a whole pixel into the disc is the hairline,
            // whole; a dp further in is the container; a dp out is the backdrop, with nothing cast on it.
            val edge = whole.getPixel(b.left.roundToInt() + 1, y)
            assertWithMessage("$tag $mode: the hairline at the disc's edge").that(distance(edge, outline.toArgb())).isAtMost(4)
            assertWithMessage("$tag $mode: the hairline is not the disc's own colour").that(distance(edge, container.toArgb())).isGreaterThan(4)
            val inside = whole.getPixel(b.left.roundToInt() + px(2f), y)
            assertWithMessage("$tag $mode: the disc inside the hairline").that(distance(inside, container.toArgb())).isAtMost(4)
            val outside = whole.getPixel(b.left.roundToInt() - px(1f), y)
            assertWithMessage("$tag $mode: the backdrop beside the disc").that(distance(outside, colors.sidebar.toArgb())).isAtMost(2)
        }
    }

    @Test
    fun `both discs stand on the cards' hairline, and nothing is cast on the surface round them, in the dark`() {
        outlinedOnTheCardsHairline(ThemeMode.Dark)
    }

    @Test
    fun `both discs stand on the cards' hairline, and nothing is cast on the surface round them, in the light`() {
        outlinedOnTheCardsHairline(ThemeMode.Light)
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private companion object {
        val Half = 240.dp
        /**
         * The room at rest between the chat's newest row and its edge, in pixels: the disc stands in the middle of this
         * and the lift together, and is an odd 105px across, so the two must come to an even number — the pulls' lifts
         * (195, 273 and 429px) take an even gap (16dp), the held lift (210px) an odd one.
         */
        const val PullsEdgeGapPx = 42
        const val HeldEdgeGapPx = 43
    }
}
