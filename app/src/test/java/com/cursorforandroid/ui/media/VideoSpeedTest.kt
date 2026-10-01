package com.cursorforandroid.ui.media

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A recording's speed: a finger held on the playing page plays it at 2× with the "2×" pill over the picture, and
 * lifting it goes back to the rate the reader picked — without the hold toggling the chrome, dismissing the page or
 * turning it. The pill at the end of the controls opens a compact menu of 0.5×, 1×, 2× and 4×, and every rate keeps
 * the sound's pitch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class VideoSpeedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val loader by lazy { ViewerFixtures.loader() }
    private val state = MediaViewerState(null)
    private lateinit var player: FakeVideoPlayer
    private lateinit var media: List<MediaEntry>

    @Before
    fun recording() {
        player = FakeVideoPlayer(durationMs = 30_000L)
        media = listOf(
            MediaEntry(ViewerFixtures.mp4("demo.mp4"), MediaEntry.Kind.Video, durationMs = 30_000L),
            MediaEntry(ViewerFixtures.png("after.png", 640, 360, 0xFF1E2A3A.toInt()), MediaEntry.Kind.Image, "After"),
        )
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }
    private fun text(tag: String): String? = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString()

    private fun open() {
        compose.setContent { ViewerScene(state, loader, media, playerFactory = { player }) }
        settle { compose.onAllNodes(hasContentDescription("Video: demo.mp4")).fetchSemanticsNodes().size == 1 && compose.onAllNodes(hasContentDescription("After")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("Video: demo.mp4").performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-play-pause") && player.playWhenReadyFlag }
    }

    /** Puts a finger down on the page's centre and keeps it there past the long-press timeout. */
    private fun holdDown() {
        compose.onNodeWithTag("viewer-page-0").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun lift() {
        compose.onNodeWithTag("viewer-page-0").performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun `holding a playing recording plays it at 2x with the pill, and letting go goes back`() {
        open()
        assertThat(player.speed).isEqualTo(1f)
        assertThat(exists("viewer-hold-speed")).isFalse()

        holdDown()
        assertThat(player.speed).isEqualTo(2f)
        assertThat(player.pitch).isEqualTo(1f)
        compose.onNodeWithContentDescription("Playing at 2\u00D7").assertExists()
        // The pill at the end of the controls still names the reader's pick; the hold is the page's to show.
        assertThat(text("viewer-speed")).isEqualTo("1\u00D7")

        lift()
        settle { !exists("viewer-hold-speed") }
        assertThat(player.speed).isEqualTo(1f)
        // A hold is not a tap: the chrome stayed where it was.
        assertThat(state.controlsVisible).isTrue()
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `a tap on the page still toggles the chrome and never changes the speed`() {
        open()
        compose.onNodeWithTag("viewer-page-0").performTouchInput { click(center) }
        settle { !state.controlsVisible }
        compose.onNodeWithTag("viewer-page-0").performTouchInput { click(center) }
        settle { state.controlsVisible }
        assertThat(player.speedsSet).isEmpty()
    }

    @Test
    fun `a held finger dragged down and let go does not dismiss the page`() {
        open()
        holdDown()
        compose.onNodeWithTag("viewer-page-0").performTouchInput {
            repeat(8) { moveBy(Offset(0f, 90f)) }
        }
        compose.waitForIdle()
        assertThat(player.speed).isEqualTo(2f)
        lift()
        compose.mainClock.advanceTimeBy(1_000)
        settle { !exists("viewer-hold-speed") }
        assertThat(state.isOpen).isTrue()
        assertThat(state.phase).isEqualTo(MediaViewerState.Phase.Open)
        assertThat(player.speed).isEqualTo(1f)
    }

    @Test
    fun `a held finger dragged sideways and let go does not turn the page`() {
        open()
        holdDown()
        compose.onNodeWithTag("viewer-page-0").performTouchInput {
            repeat(8) { moveBy(Offset(-100f, 0f)) }
        }
        lift()
        compose.mainClock.advanceTimeBy(1_000)
        settle { !exists("viewer-hold-speed") }
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(state.isOpen).isTrue()
        assertThat(player.speed).isEqualTo(1f)
    }

    @Test
    fun `a paused recording is not sped up by a hold`() {
        open()
        compose.onNodeWithTag("viewer-play-pause").performClick()
        settle { !player.playWhenReadyFlag }
        holdDown()
        assertThat(exists("viewer-hold-speed")).isFalse()
        lift()
        assertThat(player.speedsSet).isEmpty()
    }

    @Test
    fun `the speed pill opens a compact menu of four rates, and a hold goes back to the one picked`() {
        open()
        assertThat(text("viewer-speed")).isEqualTo("1\u00D7")
        compose.onNodeWithTag("viewer-speed").performClick()
        settle { exists("viewer-speed-menu") }
        for (label in listOf("0.5\u00D7", "1\u00D7", "2\u00D7", "4\u00D7")) assertThat(exists("viewer-speed-$label")).isTrue()

        compose.onNodeWithTag("viewer-speed-0.5\u00D7").performClick()
        settle { !exists("viewer-speed-menu") }
        assertThat(player.speed).isEqualTo(0.5f)
        assertThat(player.pitch).isEqualTo(1f)
        assertThat(text("viewer-speed")).isEqualTo("0.5\u00D7")

        holdDown()
        assertThat(player.speed).isEqualTo(2f)
        lift()
        assertThat(player.speed).isEqualTo(0.5f)

        compose.onNodeWithTag("viewer-speed").performClick()
        settle { exists("viewer-speed-menu") }
        compose.onNodeWithTag("viewer-speed-4\u00D7").performClick()
        settle { !exists("viewer-speed-menu") && player.speed == 4f }
        assertThat(text("viewer-speed")).isEqualTo("4\u00D7")
    }

    @Test
    fun `the chrome waits while the speed menu is open`() {
        compose.setContent { ViewerScene(state, loader, media, autoHide = 2_000L, playerFactory = { player }) }
        settle { compose.onAllNodes(hasContentDescription("Video: demo.mp4")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("Video: demo.mp4").performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-speed") }
        compose.onNodeWithTag("viewer-speed").performClick()
        settle { exists("viewer-speed-menu") }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertThat(state.controlsVisible).isTrue()
        assertThat(exists("viewer-speed-menu")).isTrue()
    }

    @Test
    fun `a rate picked during a hold is the one it goes back to`() {
        val playback = VideoPlayback(player)
        playback.load("file:///clip.mp4", playWhenReady = true, muted = false)
        compose.waitForIdle()
        assertThat(playback.beginHold()).isTrue()
        assertThat(player.speed).isEqualTo(2f)
        playback.selectSpeed(4f)
        compose.waitForIdle()
        assertThat(player.speed).isEqualTo(2f)
        assertThat(playback.selectedSpeed).isEqualTo(4f)
        playback.endHold()
        compose.waitForIdle()
        assertThat(player.speed).isEqualTo(4f)
        assertThat(playback.holding).isFalse()
    }

    @Test
    fun `the menu offers the four rates slowest first, as the pill names them`() {
        assertThat(VideoPlayback.SPEEDS.map(VideoPlayback::speedLabel)).containsExactly("0.5\u00D7", "1\u00D7", "2\u00D7", "4\u00D7").inOrder()
        assertThat(VideoPlayback.speedLabel(VideoPlayback.HOLD_SPEED)).isEqualTo("2\u00D7")
    }
}
