package com.cursorforandroid.ui.conversation

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.SystemNotifications
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
    fun `a reply with a markdown PR link shows the label, not the raw markup`() {
        show(AssistantMessage("a1", "Opened [PR #66](https://github.com/BenItBuhner/cursor-for-android/pull/66) against `main`."))
        compose.onNodeWithText("PR #66", substring = true).assertIsDisplayed()
        compose.onNodeWithText("main", substring = true).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("](", substring = true).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("github.com", substring = true).fetchSemanticsNodes()).isEmpty()
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

    @Test
    fun `a reply is not announced as something to activate, but still offers message actions on a hold`() {
        show(AssistantMessage("a1", "Plain reply."))
        val node = compose.onNodeWithText("Plain reply.").fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsActions.OnClick)).isNull()
        assertThat(node.config.getOrNull(SemanticsActions.OnLongClick)).isNotNull()
    }

    private val injected = "<system_notification>\nThe following task has finished.\n\n<task>\nkind: subagent\nstatus: success\ntitle: Contacts and clipping\ndetail: This is the last output of the subagent:\n\nThe clipping is gone. Four commits, not pushed.\n</task>\n</system_notification>"

    @Test
    fun `an injected notification reads as one row, opens onto its report on a tap, and copies as injected`() {
        show(SystemNotifications.parse("n1", injected)!!.items.single())
        compose.onNodeWithText("Subagent completed").assertIsDisplayed()
        compose.onNodeWithText("Contacts and clipping").assertIsDisplayed()
        // The markup is nowhere on screen, and neither is the report until asked for.
        assertThat(compose.onAllNodesWithText("<system_notification>", substring = true).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("The clipping is gone", substring = true).fetchSemanticsNodes()).isEmpty()

        compose.onNodeWithText("Subagent completed").performClick()
        compose.onNodeWithText("The clipping is gone. Four commits, not pushed.").assertIsDisplayed()
        assertThat(menuIsOpen()).isFalse()
        compose.onNodeWithText("Subagent completed").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("The clipping is gone", substring = true).fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithText("Subagent completed").performTouchInput { longClick() }
        compose.onNodeWithText("Copy message").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !menuIsOpen() }
        assertThat(clipboard.getText()?.text).isEqualTo(injected)
        val node = compose.onNodeWithText("Subagent completed").fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsActions.OnClick)).isNotNull()
        assertThat(node.config.getOrNull(SemanticsActions.OnLongClick)).isNotNull()
    }

    @Test
    fun `a one-line notification the row shows in full has nothing to open`() {
        show(SystemNotifications.parse("n2", "<system_notification>The user paused the goal.</system_notification>")!!.items.single())
        compose.onNodeWithText("System notification").assertIsDisplayed()
        compose.onNodeWithText("The user paused the goal.").assertIsDisplayed()
        compose.onNodeWithText("System notification").performClick()
        compose.waitForIdle()
        // Still exactly one copy of the line: nothing unfolded.
        assertThat(compose.onAllNodesWithText("The user paused the goal.").fetchSemanticsNodes()).hasSize(1)
    }
}
