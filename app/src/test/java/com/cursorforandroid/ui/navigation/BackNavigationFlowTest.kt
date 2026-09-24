package com.cursorforandroid.ui.navigation

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.update.WhatsNewFixtures
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.components.BackEdgeMinWidth
import com.cursorforandroid.ui.settings.SettingsCopy
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.settings.WhatsNewTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back follows where the reader came from, through the shell on a phone and driven through the demo: a chat opened
 * from another chat — a worker's row in a coordinator's transcript, a side chat in its panel — goes back to that chat
 * as it was left (scroll, draft, open panel), with the predictive gesture previewing it; Settings and What's new,
 * reached from a chat, go back to it; a chat picked from the sidebar or handed in from outside (a notification, a
 * widget, a shortcut) sits on the New Chat pane, so back from it is the pane and back from the pane leaves the app;
 * and the trail survives the activity's state being saved and restored.
 *
 * The window is short enough that the coordinator's transcript does not fit, so where it was scrolled to is visible.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h600dp-night-420dpi")
class BackNavigationFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /** What a notification, a widget's row or a launcher shortcut handed the activity. */
    private var deepLink by mutableStateOf<String?>(null)

    private lateinit var graph: AppGraph

    private val dispatcher: OnBackPressedDispatcher get() = compose.activity.onBackPressedDispatcher

    @Composable
    private fun Shell(graph: AppGraph) {
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

    /** The demo, signed in, on the New Chat pane; with [withNotes], the installed version's notes unread. */
    private fun launch(withNotes: Boolean = false, restorer: StateRestorationTester? = null) {
        graph = if (withNotes) {
            val notes = WhatsNewFixtures.repository(PreferencesStore(context), folder.newFolder(), versionName = VERSION, notes = WhatsNewFixtures.notes(VERSION))
            AppGraph(context, releaseNotes = notes)
        } else {
            AppGraph(context)
        }
        runBlocking { graph.session.enterDemo() }
        if (restorer != null) restorer.setContent { Shell(graph) } else compose.setContent { Shell(graph) }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** Whether the chat named [name] is composed: its header's accessibility label, the header holding no title of its own. */
    private fun chatShown(name: String) = compose.onAllNodes(hasTestTag("chat-header") and hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForChat(name: String) = compose.waitUntil(30_000) { chatShown(name) }

    /** [name]'s chat alone on screen: whatever it replaced has finished leaving. */
    private fun waitForOnly(name: String, vararg gone: String) = compose.waitUntil(30_000) { chatShown(name) && gone.none(::chatShown) }

    private fun waitForHome() {
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) && compose.onAllNodes(hasTestTag("chat-header")).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    /** A row for the worker named [name]: the one that created it and any that messaged it since. */
    private fun workerRow(name: String) = hasTestTag("subagent-row") and hasContentDescription("Subagent $name,", substring = true)

    private fun firstWorkerRow() = compose.onAllNodes(workerRow(WORKER)).onFirst()

    private val followUp = hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer"))

    private val transcript = hasScrollToNodeAction() and
        hasAnyDescendant(hasText(COORDINATOR_PROMPT, substring = true) or hasText(LATEST_REPLY, substring = true) or hasTestTag("subagent-row"))

    /** The sidebar's list, told from the recent list by the section headers only it has. */
    private val sidebarList = hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    /** A gesture from the left edge, reported at each of [progress] on its way to the commit threshold. */
    private fun swipe(vararg progress: Float) {
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
        compose.waitForIdle()
        progress.forEach {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(it * 400f, 400f, it, BackEventCompat.EDGE_LEFT))
            compose.waitForIdle()
        }
    }

    private fun cancel() {
        dispatcher.dispatchOnBackCancelled()
        compose.waitForIdle()
    }

    private fun back() {
        dispatcher.onBackPressed()
        compose.waitForIdle()
    }

    /** The coordinator's chat, entered the way a notification enters it: on the New Chat pane. */
    private fun openCoordinator() {
        deepLink = DemoData.PROJECT_ID
        waitForChat(COORDINATOR)
        // The whole transcript, both turns, rather than the first rows of it: the chat opens on its latest reply.
        compose.waitUntil(30_000) { onScreen(LATEST_REPLY) }
        compose.waitUntil(30_000) { graph.conversations.state(DemoData.PROJECT_ID).value.items.count { it is ActivityGroup } >= 2 }
        compose.waitForIdle()
    }

    private fun openWorkerFromTranscript() {
        compose.onAllNodes(transcript).onFirst().performScrollToNode(workerRow(WORKER))
        firstWorkerRow().performClick()
        waitForOnly(WORKER, COORDINATOR)
    }

    /** The drawer, dragged in over a screen whose header has a back button rather than the sidebar's. */
    private fun dragDrawerIn() {
        compose.onRoot().performTouchInput { swipeRight(startX = BackEdgeMinWidth.toPx() + 8.dp.toPx(), endX = width * 0.9f) }
        compose.waitUntil(20_000) { onScreen("Demo User") }
        compose.waitForIdle()
    }

    @Test
    fun `a worker opened from the coordinator's transcript goes back to the coordinator, where it was scrolled to and with its draft`() {
        launch()
        openCoordinator()
        compose.onNode(followUp).performTextInput(DRAFT)
        // Up to where the coordinator started its workers, away from the latest reply the chat opened on.
        compose.onAllNodes(transcript).onFirst().performScrollToNode(hasText(COORDINATOR_PROMPT, substring = true))
        compose.onAllNodes(transcript).onFirst().performScrollToNode(workerRow(WORKER))
        compose.waitForIdle()
        assertThat(onScreen(LATEST_REPLY)).isFalse()
        val rowTop = firstWorkerRow().fetchSemanticsNode().boundsInRoot.top

        firstWorkerRow().performClick()
        waitForOnly(WORKER, COORDINATOR)
        assertThat(dispatcher.hasEnabledCallbacks()).isTrue()

        // The gesture previews the chat it will go back to: the coordinator, not the New Chat pane.
        swipe(0.25f, 0.5f)
        assertThat(chatShown(WORKER)).isTrue()
        assertThat(chatShown(COORDINATOR)).isTrue()
        assertThat(onScreen(HOME_PLACEHOLDER)).isFalse()
        cancel()
        waitForOnly(WORKER, COORDINATOR)

        swipe(0.7f)
        back()
        waitForOnly(COORDINATOR, WORKER)
        // As it was left: the same stretch of the transcript on screen, the draft still in the composer.
        compose.waitUntil(20_000) { compose.onAllNodes(workerRow(WORKER)).fetchSemanticsNodes().isNotEmpty() }
        assertThat(firstWorkerRow().fetchSemanticsNode().boundsInRoot.top).isWithin(1f).of(rowTop)
        assertThat(onScreen(LATEST_REPLY)).isFalse()
        compose.onNode(followUp and hasText(DRAFT, substring = true)).assertExists()

        // And back from the coordinator, entered from outside, is the New Chat pane, and from there the app's home.
        back()
        waitForHome()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test
    fun `a side chat opened from the panel goes back to the coordinator with its panel open, which back closes first`() {
        launch()
        openCoordinator()
        // A coordinator's panel opens on its Project surface; the chat's own sections — the side chats among them —
        // are the panel's other surface, which the Agents pill above the composer opens.
        compose.onNodeWithTag("pill-agents").performClick()
        compose.waitUntil(20_000) { shown("section-SideChats") || shown("section-Project") }
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-SideChats"))
        if (!shown("side-chat")) compose.onNodeWithTag("section-SideChats").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("side-chat") and hasText(SIDE_CHAT, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("side-chat"))
        // The row itself opens the side chat as a tab beside the coordinator; its trailing button opens it as a chat
        // of its own, over the coordinator — the destination this trail is about.
        compose.onNodeWithContentDescription("Open $SIDE_CHAT as a chat").performClick()
        waitForOnly(SIDE_CHAT, COORDINATOR)
        assertThat(shown("conversation-panel")).isFalse()

        back()
        waitForOnly(COORDINATOR, SIDE_CHAT)
        compose.waitUntil(20_000) { shown("side-chats-section") }
        back()
        compose.waitUntil(20_000) { !shown("conversation-panel") }
        assertThat(chatShown(COORDINATOR)).isTrue()
        back()
        waitForHome()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test
    fun `a chat picked from the sidebar over a trail of chats sits on the New Chat pane`() {
        launch()
        openCoordinator()
        openWorkerFromTranscript()
        dragDrawerIn()
        compose.onAllNodes(sidebarList).onFirst().performScrollToNode(hasText(IDLE_CHAT))
        compose.onNode(hasText(IDLE_CHAT) and hasAnyAncestor(sidebarList)).performClick()
        waitForOnly(IDLE_CHAT, WORKER)

        // The preview is the pane: the trail was left for the sidebar, and the pick has nothing to do with it.
        swipe(0.5f)
        assertThat(onScreen(HOME_PLACEHOLDER)).isTrue()
        assertThat(chatShown(WORKER) || chatShown(COORDINATOR)).isFalse()
        back()
        waitForHome()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test
    fun `a chat handed in from outside over a trail gets the New Chat pane under it, not the trail`() {
        launch()
        openCoordinator()
        openWorkerFromTranscript()
        // A notification's tap, a widget's row, a launcher shortcut: all arrive as a link to the chat.
        deepLink = IDLE_CHAT_ID
        waitForOnly(IDLE_CHAT, WORKER)
        swipe(0.5f)
        assertThat(onScreen(HOME_PLACEHOLDER)).isTrue()
        assertThat(chatShown(WORKER) || chatShown(COORDINATOR)).isFalse()
        back()
        waitForHome()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test
    fun `Settings reached from a chat goes back to the chat, and What's new opened from Settings back to Settings`() {
        launch(withNotes = true)
        openCoordinator()
        dragDrawerIn()
        compose.onNodeWithText("Demo User").performClick()
        compose.waitUntil(20_000) { onScreen(SETTINGS_PAGE) && !chatShown(COORDINATOR) }
        compose.waitForIdle()

        // What the gesture reveals under Settings is the chat it was opened over.
        swipe(0.5f)
        assertThat(chatShown(COORDINATOR)).isTrue()
        assertThat(onScreen(HOME_PLACEHOLDER)).isFalse()
        cancel()
        compose.waitUntil(20_000) { !chatShown(COORDINATOR) }

        compose.waitUntil(20_000) { shown(SettingsTags.WHATS_NEW_ROW) }
        compose.onNodeWithTag(SettingsTags.WHATS_NEW_ROW).performScrollTo().performClick()
        compose.waitUntil(20_000) { shown(WhatsNewTags.PAGE) }
        back()
        compose.waitUntil(20_000) { !shown(WhatsNewTags.PAGE) && onScreen(SettingsCopy.GROUP_UPDATES) }
        back()
        waitForOnly(COORDINATOR)
        assertThat(onScreen(SETTINGS_PAGE)).isFalse()
        back()
        waitForHome()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test
    fun `the trail survives the activity's state being saved and restored`() {
        val restorer = StateRestorationTester(compose)
        launch(restorer = restorer)
        openCoordinator()
        openWorkerFromTranscript()

        restorer.emulateSavedInstanceStateRestore()
        waitForChat(WORKER)
        back()
        waitForOnly(COORDINATOR, WORKER)
        back()
        waitForHome()
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    }

    private companion object {
        const val VERSION = "0.3.37"
        const val COORDINATOR = "Cesium billing launch"
        const val COORDINATOR_PROMPT = "Take the Cesium billing launch"
        const val LATEST_REPLY = "is open and waiting on one decision"
        const val WORKER = "Stripe webhook handler"
        const val SIDE_CHAT = "Pricing page copy"
        const val IDLE_CHAT = "Cli exploration"
        const val IDLE_CHAT_ID = "bc-demo-0004"
        const val DRAFT = "Prorate the upgrade"
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val SETTINGS_PAGE = "Appearance"
    }
}
