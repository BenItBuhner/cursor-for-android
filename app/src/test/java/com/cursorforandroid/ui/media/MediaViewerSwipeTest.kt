package com.cursorforandroid.ui.media

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The pager's share of a finger on a page, at every speed: a drag that crosses the pager's touch slop is the
 * pager's whether it crawls or flies, the page commits on how far it was pulled (past 40 % of the width) or on how
 * fast it was let go, a short slow one settles back, and the first direction of a diagonal start decides between
 * paging and dismissing once and for all. Zoomed, the picture pans to its edge and the same drag then pulls the next
 * page in; pulled back, the pager returns to rest before the picture pans again.
 *
 * Every drag here is stepped at the test clock's 16 ms frame, so a 200 px/s drag moves 3.2 px a frame — well under
 * the 21 px touch slop for the first six frames, which is exactly where a fast swipe never goes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerSwipeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var wide: String
    private lateinit var square: String
    private lateinit var tall: String
    private lateinit var entries: List<MediaEntry>
    private val loader by lazy { ViewerFixtures.loader() }
    private val state = MediaViewerState(null)

    @Before
    fun pictures() {
        wide = ViewerFixtures.png("swipe-wide.png", 640, 360, 0xFF1E2A3A.toInt())
        square = ViewerFixtures.png("swipe-square.png", 400, 400, 0xFF3A1E2A.toInt())
        tall = ViewerFixtures.png("swipe-tall.png", 360, 640, 0xFF2A3A1E.toInt())
        entries = listOf(MediaEntry(wide, MediaEntry.Kind.Image, "Wide"), MediaEntry(square, MediaEntry.Kind.Image, "Square"), MediaEntry(tall, MediaEntry.Kind.Image, "Tall"))
    }

    private fun nodes(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    /** Where the page's left edge is: its position, not its bounds — the pager clips a page it has scrolled, so the bounds of one pulled left still start at 0. */
    private fun pageLeft(index: Int): Float = compose.onNodeWithTag("viewer-page-$index").fetchSemanticsNode().positionInRoot.x
    private val width: Float get() = compose.onNodeWithTag("viewer-pager").fetchSemanticsNode().boundsInRoot.width

    private fun open(description: String, page: Int) {
        compose.setContent { ViewerScene(state, loader, entries) }
        settle { nodes(description) == 1 }
        compose.onAllNodes(hasContentDescription(description))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-$page") }
    }

    /**
     * A finger down at the page's centre that travels [dx] x [dy] at [pxPerSecond] along its path, one move a frame,
     * and lifts unless [lift] is off (for a look at the state mid-drag). The speed is the path's, so a slow diagonal
     * is slow on both axes.
     */
    private fun TouchInjectionScope.drag(dx: Float, dy: Float, pxPerSecond: Float, lift: Boolean = true, from: Offset = center) {
        val distance = Offset(dx, dy).getDistance()
        val frames = (distance / (pxPerSecond * FrameMillis / 1000f)).roundToInt().coerceAtLeast(1)
        down(from)
        repeat(frames) { moveBy(Offset(dx / frames, dy / frames), delayMillis = FrameMillis) }
        if (lift) up()
    }

    private fun dragPager(dx: Float, dy: Float = 0f, pxPerSecond: Float, lift: Boolean = true) =
        compose.onNodeWithTag("viewer-pager").performTouchInput { drag(dx, dy, pxPerSecond, lift) }

    @Test
    fun `a slow drag past the threshold turns the page`() {
        open("Wide", page = 0)
        // 200 px/s: 3.2 px a frame, the slop crossed a dozen frames in.
        dragPager(dx = -width * 0.5f, pxPerSecond = 200f)
        settle { state.currentIndex == 1 }
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `a slow drag short of the threshold settles back`() {
        open("Wide", page = 0)
        dragPager(dx = -width * 0.3f, pxPerSecond = 200f)
        compose.waitForIdle()
        // The page came back to rest where it was, and nothing else happened to the viewer.
        settle { pageLeft(0).roundToInt() == 0 }
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `a moderate drag past the threshold turns the page`() {
        open("Wide", page = 0)
        dragPager(dx = -width * 0.5f, pxPerSecond = 800f)
        settle { state.currentIndex == 1 }
    }

    @Test
    fun `a short fast flick turns the page on speed alone`() {
        open("Wide", page = 0)
        // 150 px in three frames: 3100 px/s, a sixth of the width.
        dragPager(dx = -150f, pxPerSecond = 3_100f)
        settle { state.currentIndex == 1 }
    }

    @Test
    fun `a full-speed swipe still turns the page, and back`() {
        open("Wide", page = 0)
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeLeft() }
        settle { state.currentIndex == 1 }
        dragPager(dx = width * 0.5f, pxPerSecond = 200f)
        settle { state.currentIndex == 0 }
    }

    @Test
    fun `the page follows a slow finger while it is down`() {
        open("Wide", page = 0)
        dragPager(dx = -width * 0.35f, pxPerSecond = 300f, lift = false)
        compose.waitForIdle()
        // Mid-drag: the page has moved with the finger by its travel, less the slop the pager keeps.
        val shifted = pageLeft(0)
        assertThat(shifted).isLessThan(-width * 0.25f)
        assertThat(shifted).isGreaterThan(-width * 0.4f)
        assertThat(state.currentIndex).isEqualTo(0)
        compose.onNodeWithTag("viewer-pager").performTouchInput { up() }
        settle { pageLeft(0).roundToInt() == 0 }
    }

    @Test
    fun `a diagonal start that leans sideways is the pager's, whatever it does afterwards`() {
        open("Wide", page = 0)
        // 30 degrees off horizontal: the pager's from the slop, and the vertical drift is not a dismiss.
        dragPager(dx = -width * 0.5f, dy = width * 0.5f * 0.58f, pxPerSecond = 600f)
        settle { state.currentIndex == 1 }
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `a diagonal start that leans downward is a dismiss, whatever it does afterwards`() {
        open("Wide", page = 0)
        // 60 degrees off horizontal, far enough down to dismiss; the sideways travel would have turned the page
        // had the pager been given it, but the first direction was down.
        val down = compose.onNodeWithTag("viewer-pager").fetchSemanticsNode().boundsInRoot.height * 0.3f
        dragPager(dx = -down * 0.58f, dy = down, pxPerSecond = 600f)
        settle { !state.isOpen }
    }

    @Test
    fun `zoomed, the same drag pans the picture to its edge and then pulls the next page in`() {
        open("Square", page = 1)
        compose.onNodeWithTag("viewer-page-1").performTouchInput { doubleClick(center) }
        settle { state.zoomScale > 2f }
        val scale = state.zoomScale
        // A square fitted to the width and zoomed: half of what hangs past the viewport is the pan's room.
        val room = (width * scale - width) / 2f
        val pull = width * 0.45f

        // Mid-drag, past the room: the picture is at its right edge and the pager has begun to move.
        compose.onNodeWithTag("viewer-page-1").performTouchInput { drag(dx = -(room + pull * 0.4f), dy = 0f, pxPerSecond = 1_500f, lift = false) }
        compose.waitForIdle()
        assertThat(state.zoomPan.x).isWithin(1f).of(-room)
        assertThat(state.zoomScale).isEqualTo(scale)
        assertThat(pageLeft(1)).isLessThan(-pull * 0.3f)
        assertThat(state.currentIndex).isEqualTo(1)

        // The rest of the pull, then the lift: past 40 % of the width, the pager commits to the next page.
        compose.onNodeWithTag("viewer-page-1").performTouchInput {
            repeat(10) { moveBy(Offset(-pull * 0.06f, 0f), delayMillis = FrameMillis) }
            up()
        }
        settle { state.currentIndex == 2 && pageLeft(2).roundToInt() == 0 }
        // The page left behind rests at the fit again once it is out of view.
        compose.onNodeWithTag("viewer-pager").performTouchInput { drag(dx = width * 0.45f, dy = 0f, pxPerSecond = 800f) }
        settle { state.currentIndex == 1 }
        settle { state.zoomScale == 1f }
    }

    @Test
    fun `zoomed, a drag that engaged the pager and comes back brings the pager to rest before the picture pans again`() {
        open("Square", page = 1)
        compose.onNodeWithTag("viewer-page-1").performTouchInput { doubleClick(center) }
        settle { state.zoomScale > 2f }
        val room = (width * state.zoomScale - width) / 2f
        // Out to the edge and 200 px into the pager, then 300 px back the other way, all in one drag.
        compose.onNodeWithTag("viewer-page-1").performTouchInput {
            drag(dx = -(room + 200f), dy = 0f, pxPerSecond = 1_500f, lift = false)
            repeat(15) { moveBy(Offset(20f, 0f), delayMillis = FrameMillis) }
        }
        compose.waitForIdle()
        // The pager is back at rest and the last 100 px panned the picture off its edge.
        assertThat(pageLeft(1)).isWithin(1f).of(0f)
        assertThat(state.zoomPan.x).isWithin(2f).of(-room + 100f)
        compose.onNodeWithTag("viewer-page-1").performTouchInput { up() }
        compose.waitForIdle()
        assertThat(state.currentIndex).isEqualTo(1)
        assertThat(state.zoomScale).isGreaterThan(2f)
    }

    @Test
    fun `zoomed, a drag that reaches the last page's edge rubber-bands the picture instead`() {
        open("Tall", page = 2)
        compose.onNodeWithTag("viewer-page-2").performTouchInput { doubleClick(center) }
        settle { state.zoomScale > 1f }
        val room = ((width * state.zoomScale - width) / 2f).coerceAtLeast(0f)
        // Past the picture's right edge, with no page after this one: the picture gives a little and springs back.
        compose.onNodeWithTag("viewer-page-2").performTouchInput { drag(dx = -(room + 200f), dy = 0f, pxPerSecond = 1_500f, lift = false) }
        compose.waitForIdle()
        assertThat(pageLeft(2)).isWithin(1f).of(0f)
        assertThat(state.zoomPan.x).isLessThan(-room - 10f)
        assertThat(state.zoomPan.x).isGreaterThan(-room - 200f)
        compose.onNodeWithTag("viewer-page-2").performTouchInput { up() }
        settle { state.zoomPan.x.roundToInt() == (-room).roundToInt() }
        assertThat(state.currentIndex).isEqualTo(2)
    }

    private companion object {
        const val FrameMillis = 16L
    }
}
