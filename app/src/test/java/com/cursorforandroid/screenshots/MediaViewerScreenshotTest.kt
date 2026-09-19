package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.VideoBlock
import com.cursorforandroid.ui.media.FakeVideoPlayer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.media.ViewerScene
import com.cursorforandroid.ui.theme.CursorTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The media viewer's states over a chat's figures: a screenshot open with its chrome, the same one zoomed with the
 * chrome tapped away, a recording with its controls, and a frame of the transform on its way out of the thumbnail.
 * Written to `screenshots/` beside the walkthrough; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val state = MediaViewerState(null)
    private lateinit var landscape: String
    private lateinit var swatch: String
    private lateinit var clip: String
    private lateinit var poster: String
    private lateinit var entries: List<MediaEntry>

    @Before
    fun pictures() {
        // A real screen of the app (the coordinator fixture's landscape tab layout), a generated swatch, and a recording.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.filesDir, "viewer-shots/tab-landscape.png").apply { parentFile?.mkdirs() }
        CoordinatorFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/coordinator/tab-landscape-icon-only-project.png")!!.use { input -> fixture.outputStream().use { input.copyTo(it) } }
        landscape = "file://${fixture.absolutePath}"
        swatch = ViewerFixtures.png("swatch.png", 720, 480, 0xFF1F2A44.toInt())
        poster = ViewerFixtures.png("poster.png", 1280, 720, 0xFF262626.toInt())
        clip = ViewerFixtures.mp4("walkthrough.mp4")
        entries = listOf(
            MediaEntry(swatch, MediaEntry.Kind.Image, "Settings row with the new theme toggle", fileName = "theme-toggle.png"),
            MediaEntry(landscape, MediaEntry.Kind.Image, "Tab layout, landscape, icon-only rail, Project view", fileName = "tab-landscape-icon-only-project.png"),
            MediaEntry(clip, MediaEntry.Kind.Video, fileName = "walkthrough.mp4", durationMs = 12_000L),
        )
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Composable
    private fun Transcript() {
        val type = CursorTheme.typography
        val colors = CursorTheme.colors
        Column(Modifier.fillMaxSize().background(colors.canvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The toggle is in. Here is the settings row, the landscape tab layout, and a walkthrough of the drawer:", style = type.message, color = colors.textPrimary)
            ImageBlock(swatch, "Settings row with the new theme toggle", heightCap = 160.dp)
            ImageBlock(landscape, "Tab layout, landscape, icon-only rail, Project view", heightCap = 160.dp)
            VideoBlock(clip, poster = poster, heightCap = 160.dp)
        }
    }

    /** Every figure of the transcript decoded, the recording's poster included, so each frame captured is the settled one. */
    private fun waitForTranscript() = settle {
        compose.onAllNodes(hasContentDescription("Settings row with the new theme toggle")).fetchSemanticsNodes().size == 1 &&
            compose.onAllNodes(hasContentDescription("Tab layout, landscape, icon-only rail, Project view")).fetchSemanticsNodes().size == 1 &&
            exists("video-poster")
    }

    private fun openLandscape(player: FakeVideoPlayer) {
        compose.setContent { ViewerScene(state, ViewerFixtures.loader(), entries, playerFactory = { player }) { Transcript() } }
        waitForTranscript()
        compose.onAllNodes(hasContentDescription("Tab layout, landscape, icon-only rail, Project view"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-1") && exists("viewer-top-bar") }
    }

    @Test
    fun mediaViewerOpen() {
        openLandscape(FakeVideoPlayer())
        capture("83_media_viewer_open")
    }

    @Test
    fun mediaViewerZoomed() {
        openLandscape(FakeVideoPlayer())
        // Double tap fills the width... a landscape picture in a portrait viewport fills by its width already, so the
        // step applies: 2.5x, about the tapped point, a little right of centre. Then the chrome tapped away.
        compose.onNodeWithTag("viewer-page-1").performTouchInput { doubleClick(center.copy(x = center.x + 120f)) }
        compose.waitForIdle()
        settle { state.zoomScale > 2f }
        compose.onNodeWithTag("viewer-page-1").performTouchInput { click(center) }
        settle { !exists("viewer-top-bar") }
        capture("84_media_viewer_zoomed")
    }

    @Test
    fun mediaViewerVideo() {
        val player = FakeVideoPlayer(durationMs = 12_000L)
        compose.setContent { ViewerScene(state, ViewerFixtures.loader(), entries, playerFactory = { player }) { Transcript() } }
        // The card's poster is what the page opens on; wait for it, as a reader would see it before tapping.
        waitForTranscript()
        compose.onAllNodes(hasContentDescription("Video: walkthrough.mp4"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-play-pause") }
        // Three seconds in, paused, so the frame holds still: the scrubber a quarter along, the play disc on the poster.
        compose.runOnUiThread {
            player.seekTo(3_000L)
            player.pause()
        }
        settle { compose.onAllNodes(hasContentDescription("Play video")).fetchSemanticsNodes().isNotEmpty() }
        capture("85_media_viewer_video")
    }

    /**
     * A slow drag held a third of the way: the page follows the finger and the next picture is pulled in beside it,
     * with the gap between pages, under the chrome of the page that is still current.
     */
    @Test
    fun mediaViewerSwipe() {
        compose.setContent { ViewerScene(state, ViewerFixtures.loader(), entries, playerFactory = { FakeVideoPlayer() }) { Transcript() } }
        waitForTranscript()
        compose.onAllNodes(hasContentDescription("Settings row with the new theme toggle"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") && exists("viewer-top-bar") }
        // The neighbour is composed ahead; its picture has to have landed for the frame to show it being pulled in.
        settle { exists("viewer-image-1") }
        val width = compose.onNodeWithTag("viewer-pager").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("viewer-pager").performTouchInput {
            down(center)
            // 300 px/s: a crawl, a fifth of the touch slop a frame, held rather than let go.
            repeat(75) { moveBy(Offset(-width * 0.35f / 75, 0f), delayMillis = 16) }
        }
        compose.waitForIdle()
        check(compose.onNodeWithTag("viewer-page-0").fetchSemanticsNode().positionInRoot.x < -width * 0.25f) { "the page should be following the finger" }
        capture("96_media_viewer_swipe")
        compose.onNodeWithTag("viewer-pager").performTouchInput { up() }
    }

    @Test
    fun mediaViewerTransform() {
        compose.setContent { ViewerScene(state, ViewerFixtures.loader(), entries, playerFactory = { FakeVideoPlayer() }) { Transcript() } }
        waitForTranscript()
        // Halfway through the open: the picture is between its thumbnail and its place, the scrim half in, no chrome yet.
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasContentDescription("Tab layout, landscape, icon-only rail, Project view"))[0].performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(70)
        compose.waitForIdle()
        check(state.phase == MediaViewerState.Phase.Opening) { "the transform should still be running, was ${state.phase}" }
        compose.onRoot().captureRoboImage(File(outDir, "86_media_viewer_transform.png").path, RoborazziOptions())
        compose.mainClock.autoAdvance = true
    }
}
