package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A coordinator's chat citing its workers by id, the whole way through (the fakes behind the demo seat of the graph:
 * repository, view model, screen): a worker the list holds opens through the screen's way to another chat at once;
 * one it does not is read by its id first and then opened, landing in the list; an id the server does not know shows
 * the server's own words and offers the agent's page; a `#desktop` link opens the page with its fragment.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentLinkNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val coordinator = "bc-bae107cb-2562-40b2-b814-4f8eca874668"
    private val listed = "bc-0cbcf799-5a79-59fd-b8b3-b855a5bba8bf"
    private val unlisted = "bc-1708c3c2-1d5c-565b-8e82-aa6e9b671cd1"
    private val deleted = "bc-deadbeef-0000-4000-8000-000000000000"
    private val reply = listOf(
        "First, [Make back navigation follow history]($listed) is on the back stack.",
        "Second, [Make agent links tappable]($unlisted) is on the renderer.",
        "Third, [the old release worker]($deleted) is gone.",
        "Fourth, [the renderer's desktop]($unlisted#desktop) is live.",
    ).joinToString("\n\n")

    private val chats = mutableListOf<String>()
    private val pages = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            pages += uri
        }
    }
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        api.addFinishedAgent(coordinator, "Cursor for Android", Triple("run-c1", "Who is on Bennett's navigation report?", reply))
        api.addFinishedAgent(listed, "Make back navigation follow history", Triple("run-l1", "Make back follow history.", "On it."))
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            demo = CursorBackend(api, FakeRunStreamer(), isDemo = true),
        )
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        // Started after the list was read: the server has it, the list does not.
        api.addFinishedAgent(unlisted, "Make agent links tappable", Triple("run-u1", "Make agent ids tappable.", "On it."))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun openChat() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalUriHandler provides uriHandler) {
                    ConversationScreen(graph, coordinator, onBack = {}, onOpenAgent = { chats += it })
                }
            }
        }
        compose.waitUntil(30_000) { shown("Fourth, ") }
        assertThat(graph.agents.agent(listed)).isNotNull()
        assertThat(graph.agents.agent(unlisted)).isNull()
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** Taps the middle of [label] in the reply's paragraph that starts with [opening]. */
    private fun tap(opening: String, label: String) {
        val node: SemanticsNodeInteraction = compose.onNode(hasText(opening, substring = true), useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
        val layout = results.single()
        val start = layout.layoutInput.text.text.indexOf(label)
        check(start >= 0) { "\"$label\" is not in the paragraph" }
        val target: Offset = layout.getBoundingBox(start + label.length / 2).center
        node.performTouchInput { click(target) }
        compose.waitForIdle()
    }

    @Test
    fun `a listed worker's chat opens at once`() {
        openChat()
        val reads = api.getAgentCalls
        tap("First, ", "Make back navigation")
        assertThat(chats).containsExactly(listed)
        assertThat(api.getAgentCalls).isEqualTo(reads)
        assertThat(pages).isEmpty()
    }

    @Test
    fun `an unlisted worker is read by its id, lands in the list, then its chat opens`() {
        openChat()
        tap("Second, ", "agent links tappable")
        compose.waitUntil(10_000) { chats.isNotEmpty() }
        assertThat(chats).containsExactly(unlisted)
        assertThat(graph.agents.agent(unlisted)?.name).isEqualTo("Make agent links tappable")
        assertThat(pages).isEmpty()
        assertThat(tagged(AgentLinkTags.FAILED)).isFalse()
    }

    @Test
    fun `an id the server does not know shows its words, and the agent's page is a tap away`() {
        openChat()
        tap("Third, ", "old release worker")
        compose.waitUntil(10_000) { tagged(AgentLinkTags.FAILED) }
        compose.onNodeWithText("Couldn't open this agent").assertExists()
        // The fake answers an unknown id as the API does: 404, "Not found.".
        compose.onNodeWithText("Not found.").assertExists()
        compose.onNodeWithText(deleted).assertExists()
        assertThat(chats).isEmpty()

        compose.onNodeWithTag(AgentLinkTags.OPEN_ON_WEB).performClick()
        compose.waitUntil(10_000) { !tagged(AgentLinkTags.FAILED) }
        assertThat(pages).containsExactly("https://cursor.com/agents/$deleted")
        assertThat(chats).isEmpty()
    }

    @Test
    fun `closing the failure leaves the chat as it was`() {
        openChat()
        tap("Third, ", "old release worker")
        compose.waitUntil(10_000) { tagged(AgentLinkTags.FAILED) }
        compose.onNodeWithTag(AgentLinkTags.CLOSE).performClick()
        compose.waitUntil(10_000) { !tagged(AgentLinkTags.FAILED) }
        assertThat(pages).isEmpty()
        assertThat(chats).isEmpty()
    }

    @Test
    fun `a desktop link opens the agent's page with the fragment, not its chat`() {
        openChat()
        tap("Fourth, ", "renderer's desktop")
        assertThat(pages).containsExactly("https://cursor.com/agents/$unlisted#desktop")
        assertThat(chats).isEmpty()
    }

    @Test
    fun `a slow read shows a spinner that can be cancelled, and a cancelled read opens nothing`() {
        openChat()
        val gate = CompletableDeferred<Unit>()
        api.getAgentGate = gate
        tap("Second, ", "agent links tappable")
        compose.waitUntil(10_000) { tagged(AgentLinkTags.OPENING) }
        compose.onNodeWithText("Opening the agent\u2026").assertExists()

        compose.onNodeWithTag(AgentLinkTags.CANCEL).performClick()
        compose.waitUntil(10_000) { !tagged(AgentLinkTags.OPENING) }
        gate.complete(Unit)
        compose.waitForIdle()
        assertThat(chats).isEmpty()
        assertThat(pages).isEmpty()
    }
}
