package com.cursorforandroid.ui.media

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The viewer as a layer of the window: it opens out of the figure that was tapped and closes back into it, over a
 * transform that back and a downward drag scrub or finish; it outlives the row it came from and a saved-state
 * restoration; and it can be closed however it was opened.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var first: String
    private lateinit var second: String
    private lateinit var third: String
    private lateinit var entries: List<MediaEntry>
    private val loader by lazy { ViewerFixtures.loader() }
    private val state = MediaViewerState(null)

    @Before
    fun pictures() {
        first = ViewerFixtures.png("first.png", 640, 360, 0xFF1E2A3A.toInt())
        second = ViewerFixtures.png("second.png", 400, 400, 0xFF3A1E2A.toInt())
        third = ViewerFixtures.png("third.png", 360, 640, 0xFF2A3A1E.toInt())
        entries = listOf(MediaEntry(first, MediaEntry.Kind.Image, "First figure"), MediaEntry(second, MediaEntry.Kind.Image, "Second figure"), MediaEntry(third, MediaEntry.Kind.Image, "Third figure"))
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
    private fun imageBounds(): Rect = compose.onNodeWithTag("viewer-image-0").fetchSemanticsNode().boundsInRoot
    private fun rootBounds(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    private fun waitForThumbnails() {
        settle { nodes("First figure") == 1 && nodes("Second figure") == 1 && nodes("Third figure") == 1 }
    }

    private fun openFirst() {
        compose.onAllNodes(hasContentDescription("First figure"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
    }

    @Test
    fun `opening runs a transform out of the thumbnail and lands the picture where the page rests`() {
        compose.setContent { ViewerScene(state, loader, entries) }
        waitForThumbnails()
        val thumbnail = compose.onAllNodes(hasContentDescription("First figure"))[0].fetchSemanticsNode().boundsInRoot

        // Drive the clock by hand through the open: the figure must be on its way, not jump.
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasContentDescription("First figure"))[0].performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertThat(state.phase).isEqualTo(MediaViewerState.Phase.Opening)
        compose.mainClock.advanceTimeBy(140)
        val midway = state.progress.value
        assertThat(midway).isGreaterThan(0.05f)
        assertThat(midway).isLessThan(0.95f)
        // Mid-flight the transform layer draws the picture and the page's own copy is standing aside.
        assertThat(exists("viewer-transform")).isTrue()
        assertThat(state.isOpen).isTrue()
        compose.mainClock.autoAdvance = true
        settle { state.phase == MediaViewerState.Phase.Open }
        compose.waitForIdle()

        // Landed: the layer is gone, the page shows the picture fitted to the width, centred, with the chrome on.
        assertThat(exists("viewer-transform")).isFalse()
        assertThat(state.progress.value).isEqualTo(1f)
        val root = rootBounds()
        val image = imageBounds()
        assertThat(image.width).isWithin(2f).of(root.width)
        assertThat(image.height).isWithin(2f).of(root.width * 360f / 640f)
        assertThat(image.center.y).isWithin(2f).of(root.center.y)
        assertThat(image.width).isGreaterThan(thumbnail.width)
        compose.onNodeWithTag("viewer-caption").assertIsDisplayed()
        compose.onNodeWithTag("viewer-index").assertIsDisplayed()
        assertThat(compose.onNodeWithTag("viewer-index").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString()).isEqualTo("1 of 3")
    }

    @Test
    fun `closing plays the transform back and takes the viewer down`() {
        compose.setContent { ViewerScene(state, loader, entries) }
        waitForThumbnails()
        openFirst()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("viewer-close").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(120)
        assertThat(state.phase).isEqualTo(MediaViewerState.Phase.Closing)
        assertThat(state.progress.value).isLessThan(0.9f)
        assertThat(state.progress.value).isGreaterThan(0.05f)
        assertThat(exists("viewer-transform")).isTrue()
        compose.mainClock.autoAdvance = true
        settle { !state.isOpen }
        compose.waitForIdle()
        assertThat(state.phase).isEqualTo(MediaViewerState.Phase.Closed)
        assertThat(exists("media-viewer")).isFalse()
        // The thumbnail draws itself again.
        assertThat(nodes("First figure")).isEqualTo(1)
    }

    @Test
    fun `an open viewer outlives the row it was opened from`() {
        var rowOnScreen by mutableStateOf(true)
        compose.setContent {
            ViewerScene(state, loader, entries) {
                if (rowOnScreen) com.cursorforandroid.ui.components.ImageBlock(first, "First figure")
            }
        }
        settle { nodes("First figure") == 1 }
        openFirst()
        assertThat(nodes("First figure")).isEqualTo(2)

        rowOnScreen = false
        compose.waitForIdle()

        // Only the viewer's copy is left, and it still has its own bitmap to draw.
        assertThat(state.isOpen).isTrue()
        assertThat(nodes("First figure")).isEqualTo(1)
        compose.onNodeWithTag("viewer-image-0").assertIsDisplayed()
        // With no thumbnail left to shrink into, the close still completes: the page fades where it is.
        compose.runOnUiThread { state.close() }
        settle { !state.isOpen }
        assertThat(exists("media-viewer")).isFalse()
    }

    @Test
    fun `an open viewer survives a saved-state restoration on the same page`() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            val restored = rememberMediaViewerState()
            ViewerScene(restored, loader, entries)
        }
        waitForThumbnails()
        compose.onAllNodes(hasContentDescription("Second figure"))[0].performClick()
        settle { exists("viewer-image-1") && exists("viewer-index") }
        compose.waitForIdle()

        // The rotation the activity does not absorb, or a process death: the composition is rebuilt from saved state.
        restorer.emulateSavedInstanceStateRestore()

        settle { exists("viewer-index") }
        assertThat(compose.onNodeWithTag("viewer-index").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString()).isEqualTo("2 of 3")
        settle { exists("viewer-image-1") }
        compose.onNodeWithContentDescription("Close").performClick()
        settle { !exists("media-viewer") }
    }

    @Test
    fun `predictive back scrubs the close and a cancelled gesture brings the page back`() {
        compose.setContent { ViewerScene(state, loader, entries) }
        waitForThumbnails()
        openFirst()
        val dispatcher = compose.activity.onBackPressedDispatcher

        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(240f, 600f, 0.6f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        // Scrubbing: the picture is on its way back toward the thumbnail, part of the way.
        assertThat(state.phase).isEqualTo(MediaViewerState.Phase.Closing)
        assertThat(state.progress.value).isLessThan(0.95f)
        assertThat(state.progress.value).isGreaterThan(0.4f)
        assertThat(exists("viewer-transform")).isTrue()

        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        settle { state.phase == MediaViewerState.Phase.Open }
        compose.waitForIdle()
        assertThat(state.progress.value).isEqualTo(1f)
        assertThat(exists("viewer-transform")).isFalse()
        compose.onNodeWithTag("viewer-image-0").assertIsDisplayed()

        // Committed: the trip finishes and the viewer is gone.
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(240f, 600f, 0.5f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.onBackPressed() }
        settle { !state.isOpen }
        assertThat(exists("media-viewer")).isFalse()
    }

    @Test
    fun `a downward drag dismisses, a short one springs back`() {
        compose.setContent { ViewerScene(state, loader, entries) }
        waitForThumbnails()
        openFirst()
        val resting = imageBounds()

        // Short: not past the threshold, released without speed — the page comes back to rest.
        compose.onNodeWithTag("viewer-page-0").performTouchInput {
            down(center)
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, 20f), delayMillis = 60) }
            up()
        }
        compose.waitForIdle()
        assertThat(state.isOpen).isTrue()
        val recovered = imageBounds()
        assertThat(recovered.center.y).isWithin(2f).of(resting.center.y)

        // Long: past the threshold — the viewer goes, back into the thumbnail.
        compose.onNodeWithTag("viewer-page-0").performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 250) }
        settle { !state.isOpen }
        assertThat(exists("media-viewer")).isFalse()
        assertThat(nodes("First figure")).isEqualTo(1)
    }
}
