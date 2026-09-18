package com.cursorforandroid.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The composer's file chips and the "+" menu's two pickers — Images and videos, Files — on and off with the Extended mode. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerFilesTest {

    @get:Rule
    val compose = createComposeRule()

    private val pdf = PendingFile("f1", PromptFile(ByteArray(2_400), "Q3 report.pdf", "application/pdf"))
    private val zip = PendingFile("f2", PromptFile(ByteArray(3 * 1024 * 1024), "bundle.zip", "application/zip"))

    @Test
    fun `chips show each file's name, kind and size, and the cross takes one off`() {
        val removed = ArrayList<PendingFile>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                    files = listOf(pdf, zip),
                    onRemoveFile = { removed += it },
                )
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithTag("file-chip").assertCountEquals(2)
        compose.onNodeWithText("Q3 report.pdf").assertIsDisplayed()
        compose.onNodeWithText("PDF · 2 KB").assertIsDisplayed()
        compose.onNodeWithText("bundle.zip").assertIsDisplayed()
        compose.onNodeWithText("Archive · 3 MB").assertIsDisplayed()

        compose.onAllNodesWithContentDescription("Remove attachment")[0].performClick()
        compose.waitForIdle()
        assertThat(removed.map { it.id }).containsExactly("f1")
    }

    /**
     * The upload starts the moment the file is attached (see `AttachmentUploads`), so the chip fills while the user
     * types: its cross stays, cancelling the upload; a failed one offers a retry of the upload alone; and the composer
     * says why send is held, the button disabled, until every file is up.
     */
    @Test
    fun `an upload under way fills the chip and holds send with a hint, and a failed one offers a retry`() {
        val retried = ArrayList<PendingFile>()
        val removed = ArrayList<PendingFile>()
        var sent = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "Read both",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = { sent++ },
                    canSend = false,
                    plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                    files = listOf(pdf, zip),
                    onRemoveFile = { removed += it },
                    fileUploads = mapOf("f1" to FileUploadState(progress = 0.4f), "f2" to FileUploadState(progress = 0.1f, failed = true)),
                    onRetryFile = { retried += it },
                    sendHint = "Uploading 1 of 2…",
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Uploading · 40%").assertIsDisplayed()
        compose.onNodeWithText("Upload failed · tap to retry").assertIsDisplayed()
        compose.onNodeWithTag("send-hint").assertIsDisplayed().assertTextEquals("Uploading 1 of 2…")
        // The disabled button takes no tap: the send waits for the files, not the other way round.
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        assertThat(sent).isEqualTo(0)
        // Both chips keep their cross: taking a file off cancels its upload, done or not.
        compose.onAllNodesWithContentDescription("Remove attachment").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Remove attachment")[0].performClick()
        compose.onNodeWithContentDescription("Retry upload").performClick()
        compose.waitForIdle()
        assertThat(removed.map { it.id }).containsExactly("f1")
        assertThat(retried.map { it.id }).containsExactly("f2")
    }

    /** Up, a file's chip is the file again — glyph, kind and size — and nothing holds the send: it goes out at once. */
    @Test
    fun `a file that is up reads as a plain chip and send is instant`() {
        var sent = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "Read both",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = { sent++ },
                    canSend = true,
                    files = listOf(pdf, zip),
                    onRemoveFile = {},
                    fileUploads = mapOf("f1" to FileUploadState.DONE, "f2" to FileUploadState.DONE),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("PDF · 2 KB").assertIsDisplayed()
        compose.onAllNodesWithText("Uploading", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag("send-hint").assertCountEquals(0)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        assertThat(sent).isEqualTo(1)
    }

    @Test
    fun `an image file's chip shows its thumbnail in place of the glyph`() {
        val bitmap = ImageBitmap(4, 4)
        val photo = PendingFile("f3", PromptFile(ByteArray(900), "IMG_20260917_074100.jpg", "image/jpeg"), thumbnail = bitmap)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = "", onValueChange = {}, placeholder = "Follow up…", onSend = {}, files = listOf(photo), onRemoveFile = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("IMG_20260917_074100.jpg").assertIsDisplayed()
        compose.onNodeWithText("Image · 900 B").assertIsDisplayed()
        assertThat(photo.isImage).isTrue()
    }

    @Test
    fun `the plus menu reads Images and videos, then Files, in Extended mode, each opening its own picker`() {
        var pickedFiles = 0
        var pickedMedia = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickMedia = { pickedMedia++ }, onPickFiles = { pickedFiles++ }),
                )
            }
        }
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitForIdle()
        val media = compose.onNodeWithText("Images and videos").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val files = compose.onNodeWithText("Files").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertThat(media.top).isLessThan(files.top)
        compose.onNodeWithText("Any type, up to 15 MB each").assertIsDisplayed()
        compose.onAllNodesWithText("Attach file").assertCountEquals(0)
        compose.onAllNodesWithText("Images").assertCountEquals(0)
        compose.onNodeWithText("Files").performClick()
        compose.waitForIdle()
        assertThat(pickedFiles).isEqualTo(1)
        assertThat(pickedMedia).isEqualTo(0)

        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Images and videos").performClick()
        compose.waitForIdle()
        assertThat(pickedMedia).isEqualTo(1)
        assertThat(pickedFiles).isEqualTo(1)
    }

    @Test
    fun `the default mode's menu has Images alone - the image-only picker - and no Files row`() {
        var pickedMedia = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickMedia = { pickedMedia++ }),
                )
            }
        }
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Images").assertIsDisplayed()
        compose.onAllNodesWithText("Files").assertCountEquals(0)
        compose.onAllNodesWithText("Images and videos").assertCountEquals(0)
        compose.onAllNodesWithText("Attach file").assertCountEquals(0)
        compose.onNodeWithText("Images").performClick()
        compose.waitForIdle()
        assertThat(pickedMedia).isEqualTo(1)
    }
}
