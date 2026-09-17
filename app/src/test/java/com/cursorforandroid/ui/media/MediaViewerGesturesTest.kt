package com.cursorforandroid.ui.media

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gestures of an open page and how they share the screen with the pager: a pinch and a double tap zoom, a drag
 * pans a zoomed picture up to its edge and the next swipe turns the page, a swipe on a fitted picture turns it at
 * once, a page left behind rests at the fit again, a tap toggles the chrome, and a recording's controls drive its
 * player.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerGesturesTest {

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
        wide = ViewerFixtures.png("wide.png", 640, 360, 0xFF1E2A3A.toInt())
        square = ViewerFixtures.png("square.png", 400, 400, 0xFF3A1E2A.toInt())
        tall = ViewerFixtures.png("tall.png", 360, 640, 0xFF2A3A1E.toInt())
        entries = listOf(MediaEntry(wide, MediaEntry.Kind.Image, "Wide"), MediaEntry(square, MediaEntry.Kind.Image, "Square"), MediaEntry(tall, MediaEntry.Kind.Image, "Tall"))
    }

    private fun nodes(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits for [condition], idling the looper on every check: the loader answers on the main looper, and a
     * condition that only reads state would otherwise never see the frame that moves it.
     */
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }
    /** The rect the page's picture rests in: the viewport with the picture fitted into it (the semantics bounds follow the zoom once drawn, so they are not it). */
    private fun fitted(page: Int, width: Int, height: Int): Rect {
        val viewport = compose.onNodeWithTag("viewer-page-$page").fetchSemanticsNode().boundsInRoot
        return ViewerGeometry.fitted(viewport.size, width, height)
    }
    private fun root(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot
    /** Where the page's picture is drawn: its resting rect under the zoom the viewer reports. */
    private fun displayed(page: Int, width: Int, height: Int): Rect = ViewerGeometry.displayed(fitted(page, width, height), state.zoomScale, state.zoomPan)
    private fun index(): String? = compose.onNodeWithTag("viewer-index").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString()

    private fun open(description: String, page: Int) {
        compose.setContent { ViewerScene(state, loader, entries) }
        settle { nodes(description) == 1 }
        compose.onAllNodes(hasContentDescription(description))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-$page") }
        compose.waitForIdle()
    }

    @Test
    fun `a pinch zooms about the fingers and springs back into range when let go past it`() {
        open("Wide", page = 0)
        val root = root()
        val resting = fitted(0, 640, 360)
        assertThat(resting.width).isWithin(2f).of(root.width)
        assertThat(state.zoomScale).isEqualTo(1f)

        // Fingers 200 px apart spread to 600: three times the size, kept, since it is within the range.
        compose.onNodeWithTag("viewer-page-0").performTouchInput {
            pinch(center - Offset(100f, 0f), center - Offset(300f, 0f), center + Offset(100f, 0f), center + Offset(300f, 0f), durationMillis = 200)
        }
        compose.waitForIdle()
        assertThat(state.zoomScale).isWithin(0.05f).of(3f)
        // Zoomed about the centre: the picture is still centred.
        assertThat(state.zoomPan.x).isWithin(3f).of(0f)
        val zoomed = displayed(0, 640, 360)
        assertThat(zoomed.width / resting.width).isWithin(0.05f).of(3f)
        assertThat(zoomed.center.x).isWithin(3f).of(resting.center.x)

        // Pinched down past the fit: the rubber band gives a little, and the release springs back to exactly the fit.
        compose.onNodeWithTag("viewer-page-0").performTouchInput {
            pinch(center - Offset(300f, 0f), center - Offset(40f, 0f), center + Offset(300f, 0f), center + Offset(40f, 0f), durationMillis = 200)
        }
        compose.waitForIdle()
        assertThat(state.zoomScale).isEqualTo(1f)
        assertThat(state.zoomPan).isEqualTo(Offset.Zero)
    }

    @Test
    fun `a double tap zooms in to fill and a second one back to the fit`() {
        open("Wide", page = 0)
        val resting = fitted(0, 640, 360)
        compose.onNodeWithTag("viewer-page-0").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        // A wide picture fills a tall viewport by its height: 2399 / 606.
        assertThat(state.zoomScale).isWithin(0.02f).of(root().height / resting.height)
        val filled = displayed(0, 640, 360)
        assertThat(filled.height).isWithin(4f).of(root().height)
        assertThat(filled.width).isGreaterThan(resting.width * 3f)

        compose.onNodeWithTag("viewer-page-0").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertThat(state.zoomScale).isEqualTo(1f)
        assertThat(state.zoomPan).isEqualTo(Offset.Zero)
    }

    @Test
    fun `a swipe on a fitted picture turns the page, and the index says where the reader is`() {
        open("Wide", page = 0)
        assertThat(index()).isEqualTo("1 of 3")
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeLeft() }
        settle { state.currentIndex == 1 }
        compose.waitForIdle()
        assertThat(index()).isEqualTo("2 of 3")
        settle { exists("viewer-image-1") }
        // The square page rests fitted to the width too.
        val square = compose.onNodeWithTag("viewer-image-1").fetchSemanticsNode().boundsInRoot
        assertThat(square.width).isWithin(2f).of(root().width)
        assertThat(square.height).isWithin(2f).of(root().width)
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeRight() }
        settle { state.currentIndex == 0 }
        assertThat(index()).isEqualTo("1 of 3")
    }

    @Test
    fun `while zoomed a sideways drag pans the picture up to its edge, and only the next swipe turns the page`() {
        open("Wide", page = 0)
        compose.onNodeWithTag("viewer-page-0").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        val scale = state.zoomScale
        assertThat(scale).isGreaterThan(3f)
        val zoomed = displayed(0, 640, 360)

        // A swipe the width of the screen: absorbed as a pan, the picture moves left, the page stays.
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(state.zoomScale).isEqualTo(scale)
        val panned = displayed(0, 640, 360)
        assertThat(panned.left).isLessThan(zoomed.left - 200f)

        // Dragged on until the picture's right edge meets the viewport's: it resists past that, and settles on it.
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertThat(state.currentIndex).isEqualTo(0)
        val atEdge = displayed(0, 640, 360)
        assertThat(atEdge.right).isWithin(2f).of(root().right)

        // At the edge, the next swipe is the pager's.
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeLeft() }
        settle { state.currentIndex == 1 }
        compose.waitForIdle()
        assertThat(index()).isEqualTo("2 of 3")
        assertThat(state.zoomScale).isEqualTo(1f)

        // Back on the first page: the zoom has reset with the page change.
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeRight() }
        settle { state.currentIndex == 0 }
        compose.waitForIdle()
        assertThat(state.zoomScale).isEqualTo(1f)
        assertThat(state.zoomPan).isEqualTo(Offset.Zero)
    }

    @Test
    fun `a tap toggles the chrome`() {
        open("Wide", page = 0)
        compose.onNodeWithTag("viewer-top-bar").assertIsDisplayed()
        compose.onNodeWithTag("viewer-bottom-bar").assertIsDisplayed()
        compose.onNodeWithTag("viewer-page-0").performTouchInput { click(center) }
        settle { !exists("viewer-top-bar") }
        assertThat(state.controlsVisible).isFalse()
        assertThat(exists("viewer-bottom-bar")).isFalse()
        compose.onNodeWithTag("viewer-page-0").performTouchInput { click(center) }
        settle { exists("viewer-top-bar") }
        compose.onNodeWithTag("viewer-caption").assertIsDisplayed()
    }

    @Test
    fun `the chrome goes on its own after a while and a tap brings it back`() {
        compose.setContent { ViewerScene(state, loader, entries, autoHide = 2_000L) }
        settle { nodes("Wide") == 1 }
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasContentDescription("Wide"))[0].performClick()
        compose.mainClock.advanceTimeBy(600)
        settle { state.phase == MediaViewerState.Phase.Open }
        compose.mainClock.advanceTimeBy(400)
        assertThat(state.controlsVisible).isTrue()
        compose.mainClock.advanceTimeBy(2_400)
        assertThat(state.controlsVisible).isFalse()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("viewer-page-0").performTouchInput { click(center) }
        settle { state.controlsVisible }
    }

    @Test
    fun `a tapped recording opens playing, and the controls drive its player`() {
        val clip = ViewerFixtures.mp4("clip.mp4")
        val player = FakeVideoPlayer(durationMs = 12_000L)
        val media = listOf(MediaEntry(wide, MediaEntry.Kind.Image, "Wide"), MediaEntry(clip, MediaEntry.Kind.Video, durationMs = 12_000L))
        compose.setContent { ViewerScene(state, loader, media, playerFactory = { player }) }
        settle { nodes("Video: clip.mp4") == 1 }
        compose.onNodeWithContentDescription("Video: clip.mp4").performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-play-pause") }
        compose.waitForIdle()

        // Tapped, so playing; the length is known from the player; a recording has a way out to another app.
        assertThat(player.prepared).isTrue()
        assertThat(player.playWhenReadyFlag).isTrue()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        assertThat(compose.onNodeWithTag("viewer-duration").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString()).isEqualTo("0:12")
        compose.onNodeWithTag("viewer-open-with").assertIsDisplayed()
        assertThat(exists("viewer-video-surface")).isTrue()

        compose.onNodeWithTag("viewer-play-pause").performClick()
        compose.waitForIdle()
        assertThat(player.playWhenReadyFlag).isFalse()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play video").assertIsDisplayed()

        compose.onNodeWithTag("viewer-mute").performClick()
        compose.waitForIdle()
        assertThat(state.muted).isTrue()
        assertThat(player.volumeSet).isEqualTo(0f)
        compose.onNodeWithContentDescription("Unmute").assertIsDisplayed()

        // A tap three quarters along the scrubber seeks there.
        compose.onNodeWithTag("viewer-scrubber").performTouchInput { click(Offset(width * 0.75f, centerY)) }
        compose.waitForIdle()
        assertThat(player.positionMs).isGreaterThan(8_000L)
        assertThat(player.positionMs).isLessThan(10_000L)

        // Swiping to the picture releases the player; the picture page has no video controls.
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeRight() }
        settle { state.currentIndex == 0 }
        compose.waitForIdle()
        assertThat(player.released).isTrue()
        assertThat(exists("viewer-play-pause")).isFalse()
        assertThat(exists("viewer-open-with")).isFalse()
    }
}
