package com.cursorforandroid.ui.files

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The full-file viewer's states: the text with its marks and Copy, a file handed on, and a failure that asks again. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class FullFileScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val request = FileOpenRequest("/workspace/app/Main.kt", "c1")
    private var loads = 0
    private var closed = false

    private fun show(answer: (Int) -> FullFile) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                FullFileScreen(request, load = { answer(loads++) }, onClose = { closed = true })
            }
        }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the text is numbered, its marked lines marked, and Copy copies the whole file`() {
        show { FullFile.Text("fun a() = 1\nfun b() = 2\nfun c() = 3\n", "From the agent's workspace, as it is now", highlighted = setOf(2, 3), scrollTo = 2) }
        compose.waitUntil(5_000) { exists("full-file-lines") }
        assertThat(compose.onAllNodes(hasTestTag("full-file-marked")).fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("From the agent's workspace, as it is now \u00B7 3 lines").assertExists()
        compose.onNodeWithTag("full-file-copy").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo("fun a() = 1\nfun b() = 2\nfun c() = 3\n")
        compose.onNodeWithTag("full-file-share").assertExists()
    }

    @Test
    fun `a file that is not text is named and handed on`() {
        show { FullFile.Binary(FileFormat.PDF, 2_400_000L, "/cache/spec.pdf", "From the agent's workspace, as it is now") }
        compose.waitUntil(5_000) { exists("full-file-binary") }
        compose.onNodeWithText("PDF document \u00B7 2.3 MB").assertExists()
        compose.onNodeWithText("Open with\u2026").assertExists()
        compose.onNodeWithText("Share").assertExists()
    }

    @Test
    fun `a failed read offers Retry, which reads again`() {
        show { attempt -> if (attempt == 0) FullFile.Failed("The agent's VM isn't running right now.", retryable = true) else FullFile.Text("ok\n", "From the agent's workspace, as it is now") }
        compose.waitUntil(5_000) { exists("full-file-failed") }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) { exists("full-file-lines") }
        assertThat(loads).isEqualTo(2)
    }
}
