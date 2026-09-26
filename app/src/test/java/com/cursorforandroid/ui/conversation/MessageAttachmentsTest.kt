package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.media.ConversationMedia
import com.cursorforandroid.ui.media.FakeVideoPlayer
import com.cursorforandroid.ui.media.LocalVideoPlayerFactory
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaViewerHost
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Renders a user bubble with images through Robolectric's native graphics so the files are really decoded. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MessageAttachmentsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun shot(name: String, width: Int, height: Int): MessageAttachment {
        val file = File(folder.root, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        return MessageAttachment(file.path, width, height)
    }

    private fun show(item: UserMessage) {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { TimelineItemView(item) } }
    }

    private fun countOf(description: String) = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    /**
     * Waits for [count] nodes described as [description] — the images once their files have been decoded, on
     * `Dispatchers.IO`, through Robolectric's native graphics. The wait ends the moment they land; the ceiling is a
     * failure threshold, set where the viewer waits below set theirs: with the whole suite in one JVM the IO pool and
     * the runner are shared, and the ten seconds this used to allow was seen spent on a loaded release gate
     * (the v0.3.47 gate, 3/3 in isolation).
     */
    private fun awaitDecoded(count: Int, description: String = "Attached image") = compose.waitUntil(DECODE_CEILING_MS) { countOf(description) == count }

    /**
     * Up to two attachments lay out as before; more than two go into the one scrolling row the composer uses, images
     * and file cards together, rather than stacking into a block as tall as the bubble.
     */
    @Test
    fun `a prompt carrying more than two attachments puts them in one scrolling row`() {
        val har = File(folder.root, "trace.har").apply { writeBytes(ByteArray(48 * 1024)) }
        val card = MessageAttachment.file(har.path, "network-trace.har", "application/json", har.length())
        var item by mutableStateOf(UserMessage("m3", "Compare these against the trace", attachments = listOf(shot("a.jpg", 720, 1600), shot("b.jpg", 1600, 900), card)))
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { TimelineItemView(item) } }
        awaitDecoded(2)
        // The bubble's press-and-hold merges what is under it, so the row is looked for in the unmerged tree.
        compose.onAllNodesWithTag("attachment-row", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("attachment-file-card").assertIsDisplayed()
        compose.onNodeWithText("network-trace.har").assertIsDisplayed()

        item = UserMessage("m4", "Just these two", attachments = listOf(shot("c.jpg", 720, 1600), card))
        awaitDecoded(1)
        compose.onAllNodesWithTag("attachment-row", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("attachment-file-card").assertIsDisplayed()
    }

    @Test
    fun `a prompt with images shows every one of them above its text`() {
        show(UserMessage("m1", "Fix the layout in these screenshots", attachments = listOf(shot("a.jpg", 720, 1600), shot("b.jpg", 1600, 900))))
        awaitDecoded(2)
        compose.onNodeWithText("Fix the layout in these screenshots").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Attached image")[0].assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Attached image")[1].assertIsDisplayed()
    }

    @Test
    fun `a prompt without images renders only its text`() {
        show(UserMessage("m1", "Add a README"))
        compose.onNodeWithText("Add a README").assertIsDisplayed()
        compose.waitForIdle()
        assertThat(countOf("Attached image")).isEqualTo(0)
        assertThat(countOf("Attached image unavailable")).isEqualTo(0)
    }

    @Test
    fun `an image that is gone from the device shows a placeholder instead of nothing`() {
        show(UserMessage("m1", "See the attached image.", attachments = listOf(MessageAttachment(File(folder.root, "gone.jpg").path, 100, 100))))
        awaitDecoded(1, "Attached image unavailable")
        compose.onNodeWithText("See the attached image.").assertIsDisplayed()
    }

    /**
     * A New Chat's launch can settle — its copies moved from staging under the run — before its bubble, drawn from the
     * staged paths, has decoded them; the state naming the new paths comes after. The thumbnails follow the files to
     * where they went instead of showing them as gone, and stay when the new paths arrive.
     */
    @Test
    fun `a bubble still holding its staged paths shows the pictures its send has just moved`() {
        val store = AttachmentStore(ApplicationProvider.getApplicationContext())
        val pictures = listOf(Color.RED, Color.GREEN).map { color ->
            val out = ByteArrayOutputStream()
            Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.PNG, 100, out)
            PromptImage(out.toByteArray(), "image/png")
        }
        try {
            val staged = runBlocking { store.stage(pictures) }
            val kept = runBlocking { store.commit("bc-launch", "run-1", staged) }
            staged.attachments.forEach { assertThat(File(it.path).exists()).isFalse() }
            var item by mutableStateOf(UserMessage("local-1", "Match these colours", attachments = staged.attachments, isPending = true))
            compose.setContent { CursorTheme(mode = ThemeMode.Dark) { TimelineItemView(item) } }
            awaitDecoded(2)
            assertThat(countOf("Attached image unavailable")).isEqualTo(0)

            item = item.copy(attachments = kept, isPending = false)
            awaitDecoded(2)
            assertThat(countOf("Attached image unavailable")).isEqualTo(0)
        } finally {
            runBlocking { store.clear() }
        }
    }

    /** The thumbnail is one of the media viewer's: a tap opens the viewer on the picture, out of the strip's crop. */
    @Test
    fun `tapping a thumbnail opens the media viewer on the picture`() {
        val viewer = MediaViewerState(null)
        val loader = ViewerFixtures.loader()
        val message = UserMessage("m1", "Look", attachments = listOf(shot("a.jpg", 400, 400)))
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                MediaViewerHost(viewer, loader) {
                    CompositionLocalProvider(LocalMarkdownMedia provides MarkdownMediaContext("bc-1", loader, entries = { ConversationMedia.of(listOf(message)) })) {
                        TimelineItemView(message)
                    }
                }
            }
        }
        awaitDecoded(1)
        compose.onNodeWithContentDescription("Attached image").performClick()
        compose.waitUntil(30_000) { compose.waitForIdle(); viewer.phase == MediaViewerState.Phase.Open && countOf("Close") == 1 }
        assertThat(viewer.current?.src).isEqualTo("file://${message.attachments.single().path}")
        assertThat(viewer.current?.kind).isEqualTo(MediaEntry.Kind.Image)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(30_000) { compose.waitForIdle(); !viewer.isOpen }
        assertThat(countOf("Close")).isEqualTo(0)
    }

    /**
     * A recording the prompt carried is a tile, as the composer showed it — a play glyph, no name and size card — and
     * a tap opens the viewer on it, playing; a PDF beside it keeps its card. The by-content rule, kept past the send.
     */
    @Test
    fun `a recording is a tile that opens playing, a file of another kind keeps its card`() {
        val viewer = MediaViewerState(null)
        val loader = ViewerFixtures.loader()
        val clip = File(folder.root, "clip.mp4").apply { writeBytes(ByteArray(4_096)) }
        val spec = File(folder.root, "spec.pdf").apply { writeBytes(ByteArray(2_048)) }
        val message = UserMessage(
            "m1", "Look",
            attachments = listOf(
                MessageAttachment.file(clip.path, "clip.mp4", "video/mp4", 4_096),
                MessageAttachment.file(spec.path, "spec.pdf", "application/pdf", 2_048),
            ),
        )
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                // A stand-in player, as every viewer test hands in: a real ExoPlayer's threads would outlive the test.
                CompositionLocalProvider(LocalVideoPlayerFactory provides { FakeVideoPlayer() }) {
                    MediaViewerHost(viewer, loader) {
                        CompositionLocalProvider(LocalMarkdownMedia provides MarkdownMediaContext("bc-1", loader, entries = { ConversationMedia.of(listOf(message)) })) {
                            TimelineItemView(message)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("attachment-video").assertIsDisplayed()
        compose.onAllNodesWithText("clip.mp4").assertCountEquals(0)
        compose.onAllNodesWithText("Video · 4 KB").assertCountEquals(0)
        compose.onNodeWithTag("attachment-file-card").assertIsDisplayed()
        compose.onNodeWithText("spec.pdf").assertIsDisplayed()
        compose.onNodeWithTag("attachment-video").performClick()
        compose.waitUntil(30_000) { compose.waitForIdle(); viewer.isOpen }
        assertThat(viewer.current?.src).isEqualTo("file://${clip.path}")
        assertThat(viewer.current?.kind).isEqualTo(MediaEntry.Kind.Video)
        assertThat(viewer.session?.autoplay).isTrue()
        assertThat(viewer.session?.origin).isNotNull()
        viewer.close()
        compose.waitUntil(30_000) { compose.waitForIdle(); !viewer.isOpen }
    }

    /** Without a viewer hosted (a preview, a test of the row alone) the thumbnail is inert rather than a crash. */
    @Test
    fun `a thumbnail with no viewer to open does nothing`() {
        show(UserMessage("m1", "Look", attachments = listOf(shot("a.jpg", 400, 400))))
        awaitDecoded(1)
        compose.onNodeWithContentDescription("Attached image").performClick()
        compose.waitForIdle()
        assertThat(countOf("Close")).isEqualTo(0)
    }

    @Test
    fun `a decode never spends more memory on one image than the ceiling allows`() {
        // Asking for every pixel of an image far past the ceiling: the sampling has to step in on its own.
        val huge = shot("huge.jpg", 5000, 4000)
        val decoded = requireNotNull(decodeSampled(huge.path, targetEdgePx = 0))
        assertThat(decoded.width.toLong() * decoded.height * 4).isAtMost(32L * 1024 * 1024)
        // A request the file already fits is left alone.
        val small = shot("small.jpg", 400, 300)
        val exact = requireNotNull(decodeSampled(small.path, targetEdgePx = 800))
        assertThat(exact.width).isEqualTo(400)
    }

    @Test
    fun `a file too broken to decode is missing rather than fatal`() {
        val broken = File(folder.root, "broken.jpg").apply { writeText("not an image") }
        assertThat(decodeSampled(broken.path, targetEdgePx = 256)).isNull()
    }

    @Test
    fun `signing out drops the decoded attachment cache`() {
        val shot = shot("cached.jpg", 200, 200)
        val decoded = requireNotNull(decodeSampled(shot.path, targetEdgePx = 128))
        AttachmentImages.put("${shot.path}@128", decoded)
        assertThat(AttachmentImages.get("${shot.path}@128")).isNotNull()
        AttachmentImages.clear()
        assertThat(AttachmentImages.get("${shot.path}@128")).isNull()
    }

    private companion object {
        /** How long a decode may take before the test gives up on it (see [awaitDecoded]). */
        const val DECODE_CEILING_MS = 30_000L
    }
}
