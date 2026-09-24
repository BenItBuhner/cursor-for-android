package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The composer's attachments as one row that scrolls sideways (Bennett's frame `attachment-chips-wrap.png`: a
 * picture over a video chip over the text, a block as tall as the message). Its ends fade where there is more past
 * them — the fade is driven by the row's `canScrollBackward` / `canScrollForward`, read here off the hoisted state —
 * and the chips' upload ring, retry and cross go on working wherever the row stands.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AttachmentCarouselTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val image = PendingAttachment("img", PromptImage(ByteArray(64), "image/png"), thumbnail = ImageBitmap(4, 4))
    private val spec = PendingFile("f1", PromptFile(ByteArray(2_400 * 1024), "Q3-billing-spec.pdf", "application/pdf"))
    private val recording = PendingFile("f2", PromptFile(ByteArray(11_600 * 1024), "checkout-flow.mp4", "video/mp4"))
    private val trace = PendingFile("f3", PromptFile(ByteArray(48 * 1024), "network-trace.har", "application/json"))
    private val notes = PendingFile("f4", PromptFile(ByteArray(900), "release-notes.md", "text/markdown"))

    @Composable
    private fun Narrow(width: Int = 320, content: @Composable () -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) { Box(Modifier.width(width.dp)) { content() } }
    }

    /**
     * Five attachments in 320dp: the row overflows to the right, so only its end fades; scrolled, the start fades
     * too; at the end, the end's fade eases off and the start's stays. The affordances are live at every position:
     * the ring on the chip going up, the retry on the failed one, the cross on the last.
     */
    @Test
    fun `an overflowing row fades at the end that has more, the fade follows the scroll, and the chips keep working`() {
        val state = LazyListState()
        val removed = ArrayList<PendingFile>()
        val retried = ArrayList<PendingFile>()
        compose.setContent {
            Narrow {
                ComposerAttachments(
                    images = listOf(image),
                    onRemoveImage = {},
                    files = listOf(spec, recording, trace, notes),
                    onRemoveFile = { removed += it },
                    surface = CursorTheme.colors.elevated,
                    uploads = mapOf("f1" to FileUploadState.DONE, "f2" to FileUploadState(progress = 0.5f), "f3" to FileUploadState(failed = true)),
                    onRetryFile = { retried += it },
                    state = state,
                )
            }
        }
        compose.waitForIdle()

        // More past the end, nothing before the start: the end fades, the start is a hard edge.
        assertThat(state.canScrollForward).isTrue()
        assertThat(state.canScrollBackward).isFalse()
        compose.onNodeWithContentDescription("Attached image").assertIsDisplayed()
        // The last chip is off the end: a lazy row has not even composed it.
        compose.onAllNodesWithText("release-notes.md").assertCountEquals(0)

        compose.onNodeWithTag("attachment-row").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertThat(state.canScrollBackward).isTrue()

        // The recording going up (a media tile: the ring over it, the progress in its description), and the failed
        // file's retry, wherever the row has been scrolled to. Media come first in the row: the picture, the recording.
        compose.onNodeWithTag("attachment-row").performScrollToIndex(1)
        compose.waitForIdle()
        compose.onNode(hasContentDescription("uploading 50%", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("attachment-row").performScrollToIndex(3)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Retry upload").assertIsDisplayed().performClick()
        assertThat(retried.map { it.id }).containsExactly("f3")

        // At the end: the start fades, the end no longer; the last chip's cross takes it off.
        compose.onNodeWithTag("attachment-row").performScrollToIndex(4)
        compose.waitForIdle()
        assertThat(state.canScrollForward).isFalse()
        assertThat(state.canScrollBackward).isTrue()
        compose.onNodeWithText("release-notes.md").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Remove attachment").onLast().performClick()
        assertThat(removed.map { it.id }).containsExactly("f4")
    }

    /**
     * The fade as painted: with more past the end, the row's last column of pixels is the composer's own colour —
     * the chip under it fully covered — while its first column is chip; scrolled to the end, the two swap. Read off
     * the rendered row, not the state, so the paint itself is what is asserted.
     */
    @Test
    fun `the painted fade covers the cut edge in the composer's colour, and only that edge`() {
        val state = LazyListState()
        var surface = Color.Unspecified
        compose.setContent {
            Narrow {
                surface = CursorTheme.colors.elevated
                Box(Modifier.background(surface)) {
                    ComposerAttachments(
                        images = listOf(image),
                        onRemoveImage = {},
                        files = listOf(spec, recording, trace, notes),
                        onRemoveFile = {},
                        surface = surface,
                        uploads = mapOf("f1" to FileUploadState.DONE, "f2" to FileUploadState.DONE, "f3" to FileUploadState.DONE, "f4" to FileUploadState.DONE),
                        state = state,
                    )
                }
            }
        }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("attachment-row")

        // The content view drawn into a bitmap, as Roborazzi draws it under native graphics, cropped to the row.
        fun rendered(): Bitmap {
            val content = compose.activity.findViewById<View>(android.R.id.content)
            val whole = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { content.draw(Canvas(whole)) }
            val bounds = row.fetchSemanticsNode().boundsInRoot
            return Bitmap.createBitmap(whole, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        }
        fun Bitmap.column(x: Int): Color = Color(getPixel(x, height / 2))
        fun Color.isSurface() = abs(red - surface.red) < 0.02f && abs(green - surface.green) < 0.02f && abs(blue - surface.blue) < 0.02f

        var shot = rendered()
        val width = shot.width
        // At the start: the last column is covered — the composer's colour — and the first is the thumbnail's slot, not.
        assertThat(shot.column(width - 1).isSurface()).isTrue()
        assertThat(shot.column(0).isSurface()).isFalse()

        row.performScrollToIndex(4)
        compose.waitForIdle()
        shot = rendered()
        // At the end: the first column is covered, the last — the last chip's own edge — is not.
        assertThat(shot.column(0).isSurface()).isTrue()
        assertThat(shot.column(width - 1).isSurface()).isFalse()
    }

    /** A row that fits has nothing past either end: neither fades, and nothing scrolls. */
    @Test
    fun `a row that fits fades at neither end`() {
        val state = LazyListState()
        compose.setContent {
            Narrow(width = 400) {
                ComposerAttachments(images = listOf(image), onRemoveImage = {}, files = listOf(notes), onRemoveFile = {}, surface = CursorTheme.colors.elevated, state = state)
            }
        }
        compose.waitForIdle()
        assertThat(state.canScrollForward).isFalse()
        assertThat(state.canScrollBackward).isFalse()
        compose.onNodeWithContentDescription("Attached image").assertIsDisplayed()
        compose.onNodeWithText("release-notes.md").assertIsDisplayed()
    }

    /** In the composer, images and files share the one row — and there is no row at all when nothing is attached. */
    @Test
    fun `the composer puts images and files in one row, and has no row when nothing is attached`() {
        val removedImages = ArrayList<PendingAttachment>()
        var attached by mutableStateOf(true)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    attachments = if (attached) listOf(image) else emptyList(),
                    onRemoveAttachment = { removedImages += it },
                    files = if (attached) listOf(spec) else emptyList(),
                    onRemoveFile = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("attachment-row").assertCountEquals(1)
        compose.onNodeWithTag("media-tile").assertIsDisplayed()
        compose.onNodeWithTag("file-chip").assertIsDisplayed()
        // The image's badge, first in the row.
        compose.onAllNodesWithContentDescription("Remove attachment")[0].performClick()
        assertThat(removedImages.map { it.id }).containsExactly("img")

        // Everything taken off: the row goes with it, leaving no height behind.
        attached = false
        compose.waitForIdle()
        compose.onAllNodesWithTag("attachment-row").assertCountEquals(0)
        compose.onAllNodesWithTag("media-chip").assertCountEquals(0)
        compose.onAllNodesWithTag("file-chip").assertCountEquals(0)
    }
}
