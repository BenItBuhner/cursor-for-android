package com.cursorforandroid.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentLink
import com.cursorforandroid.ui.conversation.MessageActions
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A link to an agent under the finger, as a message draws it: a tap hands the link to the chat (the media context's
 * [MarkdownMediaContext.onOpenAgentLink]), or opens the agent's page where no chat is there to take it; a press and
 * hold copies the link's address, ahead of the message's own hold menu. The prose around it and ordinary links behave
 * as they did.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentLinkTapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val worker = "bc-0cbcf799-5a79-59fd-b8b3-b855a5bba8bf"
    private val message = "Handing the history work to [Make back navigation follow history]($worker) now; the docs are at [the guide](https://example.com/guide)."

    private val handed = mutableListOf<AgentLink>()
    private val opened = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun clipboard(): String? {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        return manager.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun show(markdown: String = message, inChat: Boolean = true) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val media = if (inChat) MarkdownMediaContext("bc-coordinator", ViewerFixtures.loader(), onOpenAgentLink = { handed += it }) else null
                CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalMarkdownMedia provides media) {
                    MessageActions(text = markdown) { MarkdownText(markdown) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun paragraph(): SemanticsNodeInteraction = compose.onNode(hasText("Handing the history work", substring = true), useUnmergedTree = true)

    /** The centre of the middle character of [shown] in the paragraph. */
    private fun centreOf(shown: String): Offset {
        val results = mutableListOf<TextLayoutResult>()
        paragraph().fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
        val layout = results.single()
        val start = layout.layoutInput.text.text.indexOf(shown)
        check(start >= 0) { "\"$shown\" is not in the paragraph" }
        return layout.getBoundingBox(start + shown.length / 2).center
    }

    @Test
    fun `the link reads as its label, not its id`() {
        show()
        compose.onNodeWithText("Make back navigation follow history", substring = true, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText(worker, substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a tap on the link hands it to the chat, and nothing else happens`() {
        show()
        val target = centreOf("follow history")
        paragraph().performTouchInput { click(target) }
        compose.waitForIdle()

        assertThat(handed).containsExactly(AgentLink(worker))
        assertThat(opened).isEmpty()
        assertThat(clipboard()).isNull()
        compose.onAllNodesWithText("Copy message").assertCountEquals(0)
    }

    @Test
    fun `holding the link copies its address, ahead of the message menu, and opens nothing`() {
        show()
        val target = centreOf("follow history")
        paragraph().performTouchInput { longClick(target) }
        compose.waitForIdle()

        assertThat(clipboard()).isEqualTo("https://cursor.com/agents/$worker")
        assertThat(handed).isEmpty()
        assertThat(opened).isEmpty()
        compose.onAllNodesWithText("Copy message").assertCountEquals(0)
    }

    @Test
    fun `a desktop link held copies the address with its fragment`() {
        show("Watch it on [its desktop]($worker#desktop), Handing the history work over.")
        val target = centreOf("desktop")
        paragraph().performTouchInput { longClick(target) }
        compose.waitForIdle()
        assertThat(clipboard()).isEqualTo("https://cursor.com/agents/$worker#desktop")
    }

    @Test
    fun `a drag that starts on the link is not a tap`() {
        show()
        val target = centreOf("follow history")
        paragraph().performTouchInput { swipeUp(startY = target.y, endY = target.y - 300f) }
        compose.waitForIdle()
        assertThat(handed).isEmpty()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `outside a chat the link opens the agent's page`() {
        show(inChat = false)
        val target = centreOf("follow history")
        paragraph().performTouchInput { click(target) }
        compose.waitForIdle()
        assertThat(opened).containsExactly("https://cursor.com/agents/$worker")
    }

    @Test
    fun `a cursor com agents link in prose goes to the chat too`() {
        show("Handing the history work to https://cursor.com/agents/$worker today.")
        val target = centreOf("cursor.com/agents")
        paragraph().performTouchInput { click(target) }
        compose.waitForIdle()
        assertThat(handed).containsExactly(AgentLink(worker))
        assertThat(opened).isEmpty()
    }

    @Test
    fun `an ordinary link and the prose beside the links behave as they did`() {
        show()
        paragraph().performTouchInput { click(centreOf("the guide")) }
        compose.waitForIdle()
        assertThat(opened).containsExactly("https://example.com/guide")
        assertThat(handed).isEmpty()

        // Holding the prose opens the message's own menu, as before.
        paragraph().performTouchInput { longClick(centreOf("history work")) }
        compose.waitForIdle()
        compose.onNodeWithText("Copy message").assertExists()
        assertThat(clipboard()).isNull()
    }
}
