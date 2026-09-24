package com.cursorforandroid.ui.media

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerAttachments
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Bennett's screen recording on his Galaxy Tab S8 (2560x1600): 1728x1080, H.264, as Samsung's recorder wrote it and
 * Cursor stored it. Opened in the viewer from the composer's tile and from the sent message's tile, the player is
 * handed the file byte for byte — never the tile's small poster — and the surface it decodes into is laid out over the
 * whole screen, so every decoded pixel reaches it: 1728x1080 enlarged to 2560x1600, not a smaller picture scaled up.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-night-320dpi")
class VideoFullResolutionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val state = MediaViewerState(null)
    private var player: FakeVideoPlayer? = null
    private val playerFactory: (android.content.Context) -> androidx.media3.common.Player = {
        FakeVideoPlayer(durationMs = 10_804, width = RECORDING_WIDTH, height = RECORDING_HEIGHT).also { player = it }
    }

    /** Stands for the recording's bytes: the fake player never decodes them, the test only checks they arrive unchanged. */
    private val recordingBytes = ByteArray(256 * 1024) { (it * 31 + it / 7).toByte() }

    /** The tile's poster, as `VideoPreview` reads it off the picker's copy: far smaller than the recording. */
    private val tilePoster = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(40, 44, 52)) }.asImageBitmap()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun textureViews(view: View): List<TextureView> = when (view) {
        is TextureView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textureViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** The file the player was handed, read back from the URI it was given. */
    private fun playedFile(): File {
        settle { player?.currentMediaItem != null }
        val uri = player!!.currentMediaItem!!.localConfiguration!!.uri
        assertThat(uri.scheme).isEqualTo("file")
        return File(Uri.decode(uri.path!!))
    }

    /**
     * The player has drawn its first frame; the poster is gone, the transform layer too, and the surface is what is on
     * screen. Its laid-out box and the TextureView's own size are the viewer's, and at least the decoded frame's.
     */
    private fun assertPlaysAtFullResolution() {
        player!!.advanceTo(0, firstFrame = true)
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-video-surface") && !exists("viewer-video-poster") }
        assertThat(exists("viewer-transform")).isFalse()

        val viewer = bounds("media-viewer")
        val surface = bounds("viewer-video-surface")
        assertThat(viewer.width).isEqualTo(2560f)
        assertThat(viewer.height).isEqualTo(1600f)
        // 1728x1080 is the screen's own 16:10: fitted, it fills the viewer edge to edge.
        assertThat(surface.width).isEqualTo(viewer.width)
        assertThat(surface.height).isEqualTo(viewer.height)
        assertThat(surface.width).isAtLeast(RECORDING_WIDTH.toFloat())
        assertThat(surface.height).isAtLeast(RECORDING_HEIGHT.toFloat())

        val texture = textureViews(compose.activity.window.decorView).single()
        assertThat(texture.width).isEqualTo(surface.width.toInt())
        assertThat(texture.height).isEqualTo(surface.height.toInt())
        assertThat(texture.scaleX).isEqualTo(1f)
        assertThat(texture.scaleY).isEqualTo(1f)
    }

    @Test
    fun `a recording opened from the composer's tile plays its own bytes over the whole tablet screen`() {
        val recording = PendingFile(
            "f-screen-recording",
            PromptFile(recordingBytes, "Screen_Recording_20260924_110403_Cursor.mp4", "video/mp4"),
            thumbnail = tilePoster,
            durationMs = 10_804,
        )
        compose.setContent {
            ViewerScene(state, ViewerFixtures.loader(), entries = emptyList(), playerFactory = playerFactory) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                    ComposerAttachments(images = emptyList(), onRemoveImage = {}, files = listOf(recording), onRemoveFile = {}, surface = Color.Black, agentId = "bc-composer")
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("media-tile")[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open }

        val played = playedFile()
        assertThat(played.readBytes()).isEqualTo(recordingBytes)
        assertThat(played.length()).isEqualTo(recordingBytes.size.toLong())
        assertPlaysAtFullResolution()
    }

    @Test
    fun `a sent recording opened from the message plays the kept copy over the whole tablet screen`() {
        val clip = File(folder.root, "Screen_Recording_20260924_110403_Cursor.mp4").apply { writeBytes(recordingBytes) }
        val message = UserMessage("m1", "The shortcut animations", attachments = listOf(MessageAttachment.file(clip.path, clip.name, "video/mp4", clip.length())))
        compose.setContent {
            ViewerScene(state, ViewerFixtures.loader(), entries = ConversationMedia.of(listOf(message)), playerFactory = playerFactory) {
                TimelineItemView(message)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("attachment-video").performClick()
        settle { state.phase == MediaViewerState.Phase.Open }

        val played = playedFile()
        assertThat(played.canonicalPath).isEqualTo(clip.canonicalPath)
        assertThat(played.readBytes()).isEqualTo(recordingBytes)
        assertPlaysAtFullResolution()
    }

    private companion object {
        const val RECORDING_WIDTH = 1728
        const val RECORDING_HEIGHT = 1080
    }
}
