package com.cursorforandroid.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A link to an agent in a chat opens that agent's chat from the one on top, through the shell: back returns to the
 * chat the link was in, a chain of links retraces one chat at a time, and a worker's link to the coordinator it was
 * opened from goes back to it instead of stacking the coordinator a second time. The coordinator is entered the way a
 * notification enters it, on the New Chat pane, so the end of each trail is the pane.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentLinkBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private var deepLink by mutableStateOf<String?>(null)
    private lateinit var graph: AppGraph

    private val dispatcher: OnBackPressedDispatcher get() = compose.activity.onBackPressedDispatcher

    @Composable
    private fun Shell() {
        CursorTheme(mode = ThemeMode.Dark) {
            AppShell(
                graph = graph,
                user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                isDemo = true,
                wide = false,
                deepLinkAgentId = deepLink,
                onDeepLinkConsumed = { deepLink = null },
            )
        }
    }

    /** The coordinator's chat on the New Chat pane; [RENDERER] started after the list was read, so only the server has it. */
    private fun openCoordinator() {
        api.addFinishedAgent(COORDINATOR_ID, COORDINATOR, Triple("run-c1", "Who is on Bennett's navigation report?", COORDINATOR_REPLY))
        api.addFinishedAgent(BACK_STACK_ID, BACK_STACK, Triple("run-b1", "Make back follow history.", WORKER_REPLY))
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            demo = CursorBackend(api, FakeRunStreamer(), isDemo = true),
        )
        runBlocking { graph.session.enterDemo() }
        compose.setContent { Shell() }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        api.addFinishedAgent(RENDERER_ID, RENDERER, Triple("run-r1", "Make agent ids tappable.", "On it."))

        deepLink = COORDINATOR_ID
        waitForOnly(COORDINATOR)
        compose.waitUntil(30_000) { onScreen("First, ") }
        compose.waitForIdle()
    }

    private fun onScreen(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun chatShown(name: String) =
        compose.onAllNodes(hasTestTag("chat-header") and hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty()

    /** [name]'s chat alone on screen: whatever it replaced has finished leaving. */
    private fun waitForOnly(name: String) {
        val others = listOf(COORDINATOR, BACK_STACK, RENDERER) - name
        compose.waitUntil(30_000) { chatShown(name) && others.none(::chatShown) }
        compose.waitForIdle()
    }

    private fun waitForHome() {
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) && compose.onAllNodes(hasTestTag("chat-header")).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    private fun back() {
        dispatcher.onBackPressed()
        compose.waitForIdle()
    }

    /** Taps the middle of [label] in the paragraph that starts with [opening]. */
    private fun tap(opening: String, label: String) {
        compose.waitUntil(30_000) { onScreen(opening) }
        val node = compose.onNode(hasText(opening, substring = true), useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
        val layout = results.single()
        val start = layout.layoutInput.text.text.indexOf(label)
        check(start >= 0) { "\"$label\" is not in the paragraph" }
        val target = layout.getBoundingBox(start + label.length / 2).center
        node.performTouchInput { click(target) }
        compose.waitForIdle()
    }

    @Test
    fun `a worker opened from a link goes back to the coordinator, and back from that is the New Chat pane`() {
        openCoordinator()
        tap("First, ", "Make back navigation")
        waitForOnly(BACK_STACK)
        assertThat(dispatcher.hasEnabledCallbacks()).isTrue()

        back()
        waitForOnly(COORDINATOR)
        assertThat(onScreen("First, ")).isTrue()
        back()
        waitForHome()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test
    fun `an agent the list does not hold is read, pushed like any other, and back retraces a chain of links`() {
        openCoordinator()
        assertThat(graph.agents.agent(RENDERER_ID)).isNull()
        tap("First, ", "Make back navigation")
        waitForOnly(BACK_STACK)
        tap("Fifth, ", "agent links tappable")
        waitForOnly(RENDERER)
        assertThat(graph.agents.agent(RENDERER_ID)?.name).isEqualTo(RENDERER)

        back()
        waitForOnly(BACK_STACK)
        back()
        waitForOnly(COORDINATOR)
        back()
        waitForHome()
    }

    @Test
    fun `a worker's link to the coordinator it was opened from goes back to it rather than stacking it again`() {
        openCoordinator()
        tap("First, ", "Make back navigation")
        waitForOnly(BACK_STACK)
        tap("Sixth, ", "the coordinator")
        waitForOnly(COORDINATOR)

        back()
        waitForHome()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    private companion object {
        const val COORDINATOR = "Cursor for Android"
        const val COORDINATOR_ID = "bc-bae107cb-2562-40b2-b814-4f8eca874668"
        const val BACK_STACK = "Make back navigation follow history"
        const val BACK_STACK_ID = "bc-0cbcf799-5a79-59fd-b8b3-b855a5bba8bf"
        const val RENDERER = "Make agent links tappable"
        const val RENDERER_ID = "bc-1708c3c2-1d5c-565b-8e82-aa6e9b671cd1"
        const val COORDINATOR_REPLY = "First, [Make back navigation follow history]($BACK_STACK_ID) is on the back stack."
        const val WORKER_REPLY = "Fifth, [Make agent links tappable]($RENDERER_ID) has the renderer.\n\n" +
            "Sixth, [the coordinator]($COORDINATOR_ID) has the plan."
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
    }
}
