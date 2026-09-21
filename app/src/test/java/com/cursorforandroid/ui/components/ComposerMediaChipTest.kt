package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's pictures and recordings as tiles, whichever way they arrived (Bennett's two frames: the same picture
 * pasted was a bare thumbnail, picked through "Images and videos" a file chip with a name and a size), files of any
 * other kind as chips; a tap on a tile opening the viewer out of it; and the remove badge's geometry, chosen so a
 * tap meant to open cannot take the picture off.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerMediaChipTest {

    @get:Rule
    val compose = createComposeRule()

    private val png = ByteArray(582) { 7 }
    private val pasted = PendingAttachment("img-pasted", PromptImage(png, "image/png"), thumbnail = ImageBitmap(4, 4))
    private val picked = PendingFile("f-picked", PromptFile(png, "65868.png", "image/png"), thumbnail = ImageBitmap(4, 4))
    private val clip = PendingFile("f-clip", PromptFile(ByteArray(4_096) { 1 }, "clip.mp4", "video/mp4"), thumbnail = null, durationMs = 12_000)
    private val pdf = PendingFile("f-pdf", PromptFile(ByteArray(2_400), "Q3 report.pdf", "application/pdf"))

    @Composable
    private fun Row(viewer: MediaViewerState? = null, onRemoveImage: (PendingAttachment) -> Unit = {}, onRemoveFile: (PendingFile) -> Unit = {}, onRetry: ((PendingFile) -> Unit)? = null, uploads: Map<String, FileUploadState> = emptyMap(), files: List<PendingFile> = listOf(picked, clip, pdf), images: List<PendingAttachment> = listOf(pasted)) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalMediaViewer provides viewer) {
                Box(Modifier.width(400.dp)) {
                    ComposerAttachments(images = images, onRemoveImage = onRemoveImage, files = files, onRemoveFile = onRemoveFile, surface = CursorTheme.colors.elevated, uploads = uploads, onRetryFile = onRetry, agentId = "bc-1")
                }
            }
        }
    }

    /** One rule, by content: a pasted picture, a picked picture and a recording are tiles — no name, no size; the PDF keeps its chip. */
    @Test
    fun `pictures and recordings are tiles whatever way they arrived, other files keep the chip`() {
        compose.setContent { Row() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("media-chip").assertCountEquals(3)
        compose.onAllNodesWithTag("file-chip").assertCountEquals(1)
        // Nothing of a file chip on a picture: not the picked one's name, not its kind and size.
        compose.onAllNodesWithText("65868.png").assertCountEquals(0)
        compose.onAllNodesWithText("Image · 582 B").assertCountEquals(0)
        compose.onAllNodesWithText("clip.mp4").assertCountEquals(0)
        // The recording: a play glyph and its length.
        compose.onAllNodesWithTag("media-play", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("media-duration", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("0:12", useUnmergedTree = true).assertIsDisplayed()
        // The file keeps its chip, name, kind and size.
        compose.onNodeWithText("Q3 report.pdf").assertIsDisplayed()
        compose.onNodeWithText("PDF · 2 KB").assertIsDisplayed()
        assertThat(clip.isMedia).isTrue()
        assertThat(clip.isImage).isFalse()
        assertThat(pdf.isMedia).isFalse()
    }

    /**
     * The geometry: the tile is 48dp square at the bottom-start of a 60dp slot (its top-end corner at 48, 12); the
     * badge is 18dp across centred 3dp outside that corner (51, 9); its hit target is the 20dp square in the slot's
     * top-end corner (40..60 × 0..20), reaching 8dp into the tile. A tap on the picture — its middle, or a point near
     * the corner past the target's reach — opens the viewer out of the tile, with the picture's own thumbnail as the
     * first frame; only the target removes. A recording opens playing.
     */
    @Test
    fun `a tap on the picture opens the viewer out of the tile, and only the badge's corner removes`() {
        val viewer = MediaViewerState(null)
        val removedImages = ArrayList<PendingAttachment>()
        val removedFiles = ArrayList<PendingFile>()
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            Row(viewer = viewer, onRemoveImage = { removedImages += it }, onRemoveFile = { removedFiles += it })
        }
        compose.waitForIdle()
        fun dp(x: Float, y: Float) = Offset(x * density, y * density)
        val chips = compose.onAllNodesWithTag("media-chip")

        // The middle of the pasted picture: opens, out of the tile, its thumbnail the first frame.
        chips[0].performTouchInput { click(dp(24f, 36f)) }
        compose.waitUntil(10_000) { viewer.isOpen }
        assertThat(viewer.current!!.kind).isEqualTo(MediaEntry.Kind.Image)
        assertThat(viewer.current!!.src).startsWith("file:")
        assertThat(viewer.current!!.src).contains("composer-media")
        assertThat(viewer.session!!.origin).isNotNull()
        assertThat(viewer.session!!.seen).isNotNull()
        assertThat(viewer.session!!.autoplay).isFalse()
        // The row's media are the pages, in order: the pasted picture, the picked one, the recording — not the PDF.
        assertThat(viewer.session!!.entries.map { it.kind }).containsExactly(MediaEntry.Kind.Image, MediaEntry.Kind.Image, MediaEntry.Kind.Video).inOrder()
        assertThat(viewer.session!!.entries[1].fileName).isEqualTo("65868.png")
        assertThat(removedImages).isEmpty()
        viewer.finishClose()

        // Near the top-end corner but past the target's reach (34dp in, 26dp down: inside the tile, below the 20dp target): opens.
        chips[0].performTouchInput { click(dp(34f, 26f)) }
        compose.waitUntil(10_000) { viewer.isOpen }
        assertThat(removedImages).isEmpty()
        viewer.finishClose()

        // The badge: removes, and nothing opens — from the badge's centre (51, 9 from the slot's origin), and from
        // the tile's own corner under the target's reach (46, 14: inside the picture, inside the 40..60 × 0..20 target).
        chips[0].performTouchInput { click(dp(51f, 9f)) }
        compose.waitForIdle()
        assertThat(removedImages.map { it.id }).containsExactly("img-pasted")
        assertThat(viewer.isOpen).isFalse()
        chips[0].performTouchInput { click(dp(46f, 14f)) }
        compose.waitForIdle()
        assertThat(removedImages.map { it.id }).containsExactly("img-pasted", "img-pasted")
        assertThat(viewer.isOpen).isFalse()
        // Just past the target's reach into the tile (2dp outside it on both axes): opens.
        chips[0].performTouchInput { click(dp(38f, 22f)) }
        compose.waitUntil(10_000) { viewer.isOpen }
        assertThat(removedImages).hasSize(2)
        viewer.finishClose()

        // The recording's tile opens the viewer on the recording, playing.
        chips[2].performTouchInput { click(dp(24f, 36f)) }
        compose.waitUntil(10_000) { viewer.isOpen }
        assertThat(viewer.current!!.kind).isEqualTo(MediaEntry.Kind.Video)
        assertThat(viewer.current!!.durationMs).isEqualTo(12_000L)
        assertThat(viewer.session!!.autoplay).isTrue()
        assertThat(removedFiles).isEmpty()
        viewer.finishClose()
    }

    /** A hold on a tile offers Open and Remove — and Retry upload when the upload failed — as the transcript's media offer their actions on a hold. */
    @Test
    fun `a long press offers Open and Remove, and Retry upload when the upload failed`() {
        val viewer = MediaViewerState(null)
        val removed = ArrayList<PendingFile>()
        val retried = ArrayList<PendingFile>()
        compose.setContent {
            Row(viewer = viewer, onRemoveFile = { removed += it }, onRetry = { retried += it }, uploads = mapOf("f-picked" to FileUploadState(failed = true)), images = emptyList(), files = listOf(picked))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("media-tile").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("Retry upload").assertIsDisplayed().performClick()
        assertThat(retried.map { it.id }).containsExactly("f-picked")

        compose.onNodeWithTag("media-tile").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Remove").assertIsDisplayed().performClick()
        assertThat(removed.map { it.id }).containsExactly("f-picked")
        assertThat(viewer.isOpen).isFalse()

        // The badge's remove is still there, described as such, for those who know it.
        compose.onAllNodesWithContentDescription("Remove attachment").assertCountEquals(1)
    }
}
