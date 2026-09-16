package com.cursorforandroid.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.conversation.MessageActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Copying code out of a reply: the block's copy button puts the raw code on the clipboard and shows a tick, and a
 * press-and-hold on an inline code span copies that span — ahead of the message's own hold menu, and without taking
 * the paragraph's links.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CodeBlockCopyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun clipboard(): String? {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        return manager.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun show(markdown: String, streaming: Boolean = false, withMessageActions: Boolean = false) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                if (withMessageActions) {
                    MessageActions(text = markdown) { MarkdownText(markdown, streaming = streaming) }
                } else {
                    MarkdownText(markdown, streaming = streaming)
                }
            }
        }
    }

    /** The laid-out text of a paragraph node, so a press can be aimed at one of its characters. */
    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
        return results.single()
    }

    /** The paragraph node itself (not the message it is merged into), so its layout and bounds are its own. */
    private fun paragraph(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text, substring = true), useUnmergedTree = true)

    /** The centre of the character at [offset] of the paragraph shown as [text]. */
    private fun centreOf(text: String, offset: Int): Pair<SemanticsNodeInteraction, Offset> {
        val node = paragraph(text)
        val box = node.layout().getBoundingBox(offset)
        return node to box.center
    }

    @Test
    fun `the block's button copies the raw code, not the fence, and ticks for a moment`() {
        show("Run this:\n\n```bash\ncd ~/.cesium/source && bun pm cache rm\necho \"done\"\n```\n\nThen retry.")

        compose.onNodeWithText("bash").assertExists()
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.waitForIdle()

        assertThat(clipboard()).isEqualTo("cd ~/.cesium/source && bun pm cache rm\necho \"done\"")
        compose.onNodeWithContentDescription("Copied").assertExists()
        compose.onAllNodes(hasContentDescription("Copy code")).assertCountEquals(0)
    }

    @Test
    fun `a block without a language still has its strip and button`() {
        show("```\nplain text\n```")
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.waitForIdle()
        assertThat(clipboard()).isEqualTo("plain text")
    }

    @Test
    fun `while a block is still arriving the button copies what has arrived so far`() {
        var markdown by mutableStateOf("```kotlin\nval a = 1\n")
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { MarkdownText(markdown, streaming = true) } }

        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.waitForIdle()
        assertThat(clipboard()).isEqualTo("val a = 1")

        markdown += "val b = 2\n```"
        compose.waitForIdle()
        // The same button, still there once the fence has closed, copies the whole block.
        compose.waitUntil { compose.onAllNodes(hasContentDescription("Copy code")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.waitForIdle()
        assertThat(clipboard()).isEqualTo("val a = 1\nval b = 2")
    }

    @Test
    fun `holding an inline code span copies the span alone, ahead of the message menu`() {
        val markdown = "Then run `bun install` in the source directory."
        show(markdown, withMessageActions = true)

        // The rendered span is " bun install " with its padding; aim at the middle of the code itself.
        val shown = " bun install "
        val rendered = paragraph("Then run").layout().layoutInput.text.text
        val (node, centre) = centreOf("Then run", rendered.indexOf(shown) + shown.length / 2)
        node.performTouchInput { longClick(centre) }
        compose.waitForIdle()

        assertThat(clipboard()).isEqualTo("bun install")
        compose.onAllNodesWithText("Copy message").assertCountEquals(0)
    }

    @Test
    fun `holding the prose beside the code still opens the message menu`() {
        val markdown = "Then run `bun install` in the source directory."
        show(markdown, withMessageActions = true)

        val (node, centre) = centreOf("Then run", 2)
        node.performTouchInput { longClick(centre) }
        compose.waitForIdle()

        compose.onNodeWithText("Copy message").assertExists()
        assertThat(clipboard()).isNull()
    }
}
