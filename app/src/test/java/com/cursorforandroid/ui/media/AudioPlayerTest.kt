package com.cursorforandroid.ui.media

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A sound opens in the media viewer the way a figure does: out of the chip it was tapped on, through the same
 * transform, onto a card with the player's controls — play and pause, the scrubber, elapsed and total, the speed —
 * and it is dismissed with the same gestures.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AudioPlayerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = MediaViewerState(null)
    private val loader by lazy { ViewerFixtures.loader() }
    private var player: FakeVideoPlayer? = null
    private val factory: (Context) -> androidx.media3.common.Player = { FakeVideoPlayer(durationMs = 83_000L).also { player = it } }

    private fun sound(name: String): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "audio-test/$name").apply { parentFile?.mkdirs(); writeBytes("ID3".toByteArray() + ByteArray(64)) }
        return "file://${file.absolutePath}"
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }
    private fun text(tag: String): String? = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString()

    @Test
    fun `a sound opens out of its chip, playing, with its controls`() {
        val src = sound("standup.mp3")
        val entries = listOf(MediaEntry(src, MediaEntry.Kind.Audio, fileName = "standup.mp3"))
        compose.setContent { ViewerScene(state, loader, entries, playerFactory = factory) }
        settle { exists("audio-chip") }
        val chip = compose.onNodeWithTag("audio-chip").fetchSemanticsNode().boundsInRoot

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("audio-chip").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertThat(state.phase).isEqualTo(MediaViewerState.Phase.Opening)
        // The transform starts on the chip's own box and carries the card's picture from the first frame.
        assertThat(state.session?.origin).isNotNull()
        compose.mainClock.advanceTimeBy(140)
        assertThat(exists("viewer-transform")).isTrue()
        compose.mainClock.autoAdvance = true
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-audio-card") }
        assertThat(chip.width).isGreaterThan(0f)

        // Tapped open, so it plays; the chrome carries its controls and its length.
        settle { player?.prepared == true }
        assertThat(player!!.playWhenReadyFlag).isTrue()
        settle { exists("viewer-play-pause") && text("viewer-duration") == "1:23" }
        assertThat(text("viewer-position")).isEqualTo("0:00")
        assertThat(text("viewer-caption")).isEqualTo("standup.mp3")

        // Pause, then play again.
        compose.onNodeWithTag("viewer-play-pause").performClick()
        settle { player!!.playWhenReadyFlag == false }
        compose.onNodeWithTag("viewer-play-pause").performClick()
        settle { player!!.playWhenReadyFlag }

        // The speed steps 1× → 1.5× → 2×, and the player plays at it.
        assertThat(text("viewer-speed")).isEqualTo("1\u00D7")
        compose.onNodeWithTag("viewer-speed").performClick()
        settle { player!!.speed == 1.5f && text("viewer-speed") == "1.5\u00D7" }
        compose.onNodeWithTag("viewer-speed").performClick()
        settle { player!!.speed == 2f }

        // A tap on the scrubber seeks there.
        compose.onNodeWithTag("viewer-scrubber").performTouchInput { click(center) }
        settle { player!!.positionMs in 38_000L..45_000L }
    }

    @Test
    fun `a sound's page is dismissed by a drag down like any page, and its player goes with it`() {
        val src = sound("note.wav")
        val entries = listOf(MediaEntry(src, MediaEntry.Kind.Audio, fileName = "note.wav"))
        compose.setContent { ViewerScene(state, loader, entries, playerFactory = factory) }
        settle { exists("audio-chip") }
        compose.onNodeWithTag("audio-chip").performClick()
        settle { state.phase == MediaViewerState.Phase.Open && player?.prepared == true }

        compose.onNodeWithTag("viewer-page-0").performTouchInput { swipeDown(startY = centerY, endY = bottom, durationMillis = 200) }
        settle { !state.isOpen }
        assertThat(player!!.released).isTrue()
    }

    @Test
    fun `speeds step round and read as the button shows them`() {
        assertThat(VideoPlayback.SPEEDS.map(VideoPlayback::speedLabel)).containsExactly("1\u00D7", "1.5\u00D7", "2\u00D7", "0.75\u00D7").inOrder()
    }
}
