package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

    @Test
    fun `a prompt with images shows every one of them above its text`() {
        show(UserMessage("m1", "Fix the layout in these screenshots", attachments = listOf(shot("a.jpg", 720, 1600), shot("b.jpg", 1600, 900))))
        compose.waitUntil(10_000) { countOf("Attached image") == 2 }
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
        compose.waitUntil(10_000) { countOf("Attached image unavailable") == 1 }
        compose.onNodeWithText("See the attached image.").assertIsDisplayed()
    }

    @Test
    fun `tapping a thumbnail opens the full-screen viewer`() {
        show(UserMessage("m1", "Look", attachments = listOf(shot("a.jpg", 400, 400))))
        compose.waitUntil(10_000) { countOf("Attached image") == 1 }
        compose.onNodeWithContentDescription("Attached image").performClick()
        compose.waitUntil(10_000) { countOf("Close") == 1 }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(10_000) { countOf("Close") == 0 }
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
}
