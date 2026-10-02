package com.cursorforandroid.ui.media

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.VideoBlock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The open viewer's keys (see [MediaKeys]) from a hardware keyboard: the viewer takes the focus as it opens, off a
 * composer under it, and answers Space and K, J and L, the arrows, < and >, M, Shift+N and Shift+P, Page Up and Page
 * Down — with the speed or the seek read out on the player — and a field over it (the palette's) keeps every key typed
 * into it, so a Space there is a space and the recording is left alone.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerKeysTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val loader by lazy { ViewerFixtures.loader() }
    private val state = MediaViewerState(null)
    private lateinit var player: FakeVideoPlayer
    private lateinit var media: List<MediaEntry>

    @Before
    fun gallery() {
        player = FakeVideoPlayer(durationMs = 30_000L)
        media = listOf(
            MediaEntry(ViewerFixtures.mp4("keys.mp4"), MediaEntry.Kind.Video, durationMs = 30_000L),
            MediaEntry(ViewerFixtures.png("keys-a.png", 640, 360, 0xFF1E2A3A.toInt()), MediaEntry.Kind.Image, "Before"),
            MediaEntry(ViewerFixtures.png("keys-b.png", 640, 360, 0xFF3A2A1E.toInt()), MediaEntry.Kind.Image, "After"),
        )
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }
    private fun text(tag: String): String? = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString()
    private val viewer get() = compose.onNodeWithTag("media-viewer")

    private fun keys(block: KeyInjectionScope.() -> Unit) {
        viewer.performKeyInput(block)
        compose.waitForIdle()
    }

    private fun shifted(key: Key) = keys { withKeyDown(Key.ShiftLeft) { pressKey(key) } }

    private fun openVideo() {
        compose.onNodeWithContentDescription("Video: keys.mp4").performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-play-pause") && player.playWhenReadyFlag }
    }

    private fun show(field: TextFieldState? = null, over: TextFieldState? = null) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ViewerScene(state, loader, media, playerFactory = { player }, content = {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (field != null) BasicTextField(field, Modifier.fillMaxWidth().testTag("under-field"))
                        VideoBlock(media[0].src, poster = null, heightCap = 150.dp)
                    }
                })
                if (over != null) {
                    BasicTextField(over, Modifier.align(Alignment.Center).fillMaxWidth().background(Color.White).padding(12.dp).testTag("over-field"))
                }
            }
        }
        settle { compose.onAllNodes(hasContentDescription("Video: keys.mp4")).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun `the viewer takes the keys as it opens, and Space and K play and pause`() {
        show()
        openVideo()
        viewer.assertIsFocused()
        keys { pressKey(Key.Spacebar) }
        assertThat(player.playWhenReadyFlag).isFalse()
        keys { pressKey(Key.Spacebar) }
        assertThat(player.playWhenReadyFlag).isTrue()
        keys { pressKey(Key.K) }
        assertThat(player.playWhenReadyFlag).isFalse()
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `J and L seek 10 s, the arrows 5 s, inside the recording, each read out on the player`() {
        show()
        openVideo()
        // Paused, so the position is only what the keys make it.
        keys { pressKey(Key.K) }
        assertThat(player.playWhenReadyFlag).isFalse()
        keys { pressKey(Key.L) }
        assertThat(player.positionMs).isEqualTo(10_000L)
        assertThat(text("viewer-key-readout")).isEqualTo("+10 s")
        keys { pressKey(Key.DirectionRight) }
        assertThat(player.positionMs).isEqualTo(15_000L)
        assertThat(text("viewer-key-readout")).isEqualTo("+5 s")
        keys { pressKey(Key.DirectionLeft) }
        assertThat(player.positionMs).isEqualTo(10_000L)
        keys { pressKey(Key.J) }
        keys { pressKey(Key.J) }
        assertThat(player.positionMs).isEqualTo(0L)
        assertThat(text("viewer-key-readout")).isEqualTo("\u221210 s")
        repeat(4) { keys { pressKey(Key.L) } }
        assertThat(player.positionMs).isEqualTo(30_000L)
        // The readout goes on its own, and nothing else was touched: still the recording, still open.
        compose.mainClock.advanceTimeBy(2_000)
        settle { !exists("viewer-key-readout") }
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `greater-than and less-than step the speed a quarter at a time between 0_25x and 2x, the pill following`() {
        show()
        openVideo()
        shifted(Key.Period)
        assertThat(player.speed).isEqualTo(1.25f)
        assertThat(player.pitch).isEqualTo(1f)
        assertThat(text("viewer-key-readout")).isEqualTo("1.25\u00D7")
        assertThat(text("viewer-speed")).isEqualTo("1.25\u00D7")
        repeat(5) { shifted(Key.Period) }
        assertThat(player.speed).isEqualTo(2f)
        assertThat(text("viewer-key-readout")).isEqualTo("2\u00D7")
        repeat(10) { shifted(Key.Comma) }
        assertThat(player.speed).isEqualTo(0.25f)
        assertThat(text("viewer-speed")).isEqualTo("0.25\u00D7")
        assertThat(player.speedsSet).containsExactly(1.25f, 1.5f, 1.75f, 2f, 1.75f, 1.5f, 1.25f, 1f, 0.75f, 0.5f, 0.25f).inOrder()
    }

    @Test
    fun `M mutes and unmutes the viewer's sound`() {
        show()
        openVideo()
        keys { pressKey(Key.M) }
        assertThat(state.muted).isTrue()
        settle { player.volumeSet == 0f }
        assertThat(text("viewer-key-readout")).isEqualTo("Muted")
        keys { pressKey(Key.M) }
        assertThat(state.muted).isFalse()
        settle { player.volumeSet == 1f }
        assertThat(text("viewer-key-readout")).isEqualTo("Sound on")
    }

    @Test
    fun `Shift+N and Shift+P, Page Down and Page Up turn the pages, and on a picture so do the arrows`() {
        show()
        openVideo()
        shifted(Key.N)
        settle { state.currentIndex == 1 }
        // On a picture there is nothing to seek: the arrows turn the page.
        keys { pressKey(Key.DirectionRight) }
        settle { state.currentIndex == 2 }
        keys { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertThat(state.currentIndex).isEqualTo(2)
        keys { pressKey(Key.DirectionLeft) }
        settle { state.currentIndex == 1 }
        keys { pressKey(Key.PageDown) }
        settle { state.currentIndex == 2 }
        keys { pressKey(Key.PageUp) }
        settle { state.currentIndex == 1 }
        shifted(Key.P)
        settle { state.currentIndex == 0 }
        // A picture's Space is nothing: the keys still belong to the viewer, which is still open.
        assertThat(state.isOpen).isTrue()
        viewer.assertIsFocused()
    }

    @Test
    fun `a composer focused under the viewer gives up the focus as it opens, so Space plays and types nothing there`() {
        val under = TextFieldState("draft")
        show(field = under)
        compose.onNodeWithTag("under-field").performClick()
        compose.onNodeWithTag("under-field").assertIsFocused()
        openVideo()
        compose.onNodeWithTag("under-field").assertIsNotFocused()
        viewer.assertIsFocused()
        keys { pressKey(Key.Spacebar) }
        keys { pressKey(Key.K) }
        keys { pressKey(Key.M) }
        assertThat(under.text.toString()).isEqualTo("draft")
        assertThat(player.playWhenReadyFlag).isTrue()
        assertThat(state.muted).isTrue()
    }

    @Test
    fun `a field over the viewer keeps what is typed into it, Space, K, J, L and M among it, and the recording is left alone`() {
        val over = TextFieldState()
        show(over = over)
        openVideo()
        val field = compose.onNodeWithTag("over-field")
        field.performClick()
        field.assertIsFocused()
        field.performKeyInput {
            pressKey(Key.K)
            pressKey(Key.Spacebar)
            pressKey(Key.J)
            pressKey(Key.L)
            pressKey(Key.M)
            withKeyDown(Key.ShiftLeft) { pressKey(Key.N) }
            pressKey(Key.DirectionLeft)
        }
        compose.waitForIdle()
        assertThat(over.text.toString()).isEqualTo("k jlmN")
        assertThat(player.playWhenReadyFlag).isTrue()
        assertThat(player.positionMs).isEqualTo(0L)
        assertThat(player.speedsSet).isEmpty()
        assertThat(state.muted).isFalse()
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(exists("viewer-key-readout")).isFalse()
    }

    @Test
    fun `a speed off the steps, the menu's 4x, steps down to 2x and goes no faster`() {
        val playback = VideoPlayback(player)
        playback.load("file:///clip.mp4", playWhenReady = true, muted = false)
        compose.waitForIdle()
        playback.selectSpeed(4f)
        assertThat(playback.stepSpeed(faster = true)).isEqualTo(4f)
        assertThat(playback.stepSpeed(faster = false)).isEqualTo(2f)
        assertThat(playback.stepSpeed(faster = true)).isEqualTo(2f)
        playback.selectSpeed(0.25f)
        assertThat(playback.stepSpeed(faster = false)).isEqualTo(0.25f)
        playback.release()
    }
}
