package com.cursorforandroid.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
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

/** The composer's file chips and the "+" menu's Attach file row, on and off with the Extended mode. */
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
                    plusMenu = ComposerMenuActions(onPickFiles = {}, onAttachFile = {}),
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

    @Test
    fun `an upload under way fills the chip and hides its cross, and a failed one offers a retry`() {
        val retried = ArrayList<PendingFile>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickFiles = {}, onAttachFile = {}),
                    files = listOf(pdf, zip),
                    onRemoveFile = {},
                    fileUploads = mapOf("f1" to FileUploadState(progress = 0.4f), "f2" to FileUploadState(progress = 0.1f, failed = true)),
                    onRetryFile = { retried += it },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Uploading · 40%").assertIsDisplayed()
        compose.onNodeWithText("Upload failed · tap to retry").assertIsDisplayed()
        // The chip going up cannot be taken off mid-flight; the failed one can be retried or removed.
        compose.onAllNodesWithContentDescription("Remove attachment").assertCountEquals(1)
        compose.onNodeWithContentDescription("Retry upload").performClick()
        compose.waitForIdle()
        assertThat(retried.map { it.id }).containsExactly("f2")
    }

    @Test
    fun `the plus menu offers Attach file beside Images in Extended mode, and the one Files row without it`() {
        var attached = 0
        var pickedImages = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickFiles = { pickedImages++ }, onAttachFile = { attached++ }),
                )
            }
        }
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Images").assertIsDisplayed()
        compose.onNodeWithText("Attach file").assertIsDisplayed()
        compose.onNodeWithText("Any type, up to 15 MB each").assertIsDisplayed()
        compose.onNodeWithText("Attach file").performClick()
        compose.waitForIdle()
        assertThat(attached).isEqualTo(1)
        assertThat(pickedImages).isEqualTo(0)
    }

    @Test
    fun `the default mode's menu is unchanged - one Files row with the image picker behind it`() {
        var pickedImages = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickFiles = { pickedImages++ }),
                )
            }
        }
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onAllNodesWithText("Attach file").assertCountEquals(0)
        compose.onAllNodesWithText("Images").assertCountEquals(0)
        compose.onNodeWithText("Files").performClick()
        compose.waitForIdle()
        assertThat(pickedImages).isEqualTo(1)
    }
}
