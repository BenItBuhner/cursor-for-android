package com.cursorforandroid.ui.conversation

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Press-and-hold on a prompt or a reply: the context menu, and what "Copy message" leaves on the clipboard. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MessageActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var clipboard: ClipboardManager

    /** Reads the clipboard through the same manager the composition writes to, whatever Robolectric does per context. */
    private fun show(item: TimelineItem) {
        compose.setContent {
            clipboard = LocalClipboardManager.current
            CursorTheme(mode = ThemeMode.Dark) { TimelineItemView(item) }
        }
    }

    private fun menuIsOpen() = compose.onAllNodesWithText("Copy message").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `holding a prompt offers to copy it and copying puts its text on the clipboard`() {
        show(UserMessage("m1", "Add a README"))
        compose.onNodeWithText("Add a README").performTouchInput { longClick() }
        compose.onNodeWithText("Copy message").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !menuIsOpen() }
        assertThat(clipboard.getText()?.text).isEqualTo("Add a README")
    }

    @Test
    fun `holding a reply copies its markdown as written, not as rendered`() {
        show(AssistantMessage("a1", "Done, **README** added."))
        compose.onNodeWithText("Done, README added.").performTouchInput { longClick() }
        compose.onNodeWithText("Copy message").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !menuIsOpen() }
        assertThat(clipboard.getText()?.text).isEqualTo("Done, **README** added.")
    }

    @Test
    fun `a tap on a message opens nothing`() {
        show(AssistantMessage("a1", "Nothing to see here."))
        compose.onNodeWithText("Nothing to see here.").performClick()
        compose.waitForIdle()
        assertThat(menuIsOpen()).isFalse()
    }
}
