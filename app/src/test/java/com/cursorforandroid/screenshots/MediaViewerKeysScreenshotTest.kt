package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * A recording open in the viewer, paused, as its keys are read out on the player: L's "+10 s" with the scrubber a third
 * along, and two presses of > with "1.5×" over the speed pill that has followed it. Written to `screenshots/`; CI
 * compares them pixel for pixel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerKeysScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val state = MediaViewerState(null)
    private val player = FakeVideoPlayer(durationMs = 30_000L)
    private lateinit var clip: String
    private lateinit var poster: String

    @Before
    fun recording() {
        clip = ViewerFixtures.mp4("walkthrough.mp4")
        poster = ViewerFixtures.png("keys-poster.png", 1280, 720, 0xFF262626.toInt())
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    private fun keys(block: KeyInjectionScope.() -> Unit) {
        compose.onNodeWithTag("media-viewer").performKeyInput(block)
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** The recording opened from its card and paused with K, so the frame holds still for the keys that follow. */
    private fun openPaused() {
        val entries = listOf(MediaEntry(clip, MediaEntry.Kind.Video, fileName = "walkthrough.mp4", durationMs = 30_000L))
        compose.setContent {
            ViewerScene(state, ViewerFixtures.loader(), entries, playerFactory = { player }) {
                val colors = CursorTheme.colors
                Column(Modifier.fillMaxSize().background(colors.canvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Here is the walkthrough of the drawer:", style = CursorTheme.typography.message, color = colors.textPrimary)
                    VideoBlock(clip, poster = poster, heightCap = 160.dp)
                }
            }
        }
        settle { exists("video-poster") }
        compose.onAllNodes(hasContentDescription("Video: walkthrough.mp4"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-play-pause") && player.playWhenReadyFlag }
        keys { pressKey(Key.K) }
        settle { compose.onAllNodes(hasContentDescription("Play video")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun mediaViewerSeekReadout() {
        openPaused()
        keys { pressKey(Key.L) }
        check(player.positionMs == 10_000L) { "L should have seeked to 10 s, was ${player.positionMs}" }
        settle { exists("viewer-key-readout") }
        capture("996_media_viewer_seek_readout")
    }

    @Test
    fun mediaViewerSpeedReadout() {
        openPaused()
        repeat(2) { keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.Period) } } }
        check(player.speed == 1.5f) { "two presses of > should be 1.5x, was ${player.speed}" }
        settle { exists("viewer-key-readout") }
        capture("997_media_viewer_speed_readout")
    }
}
