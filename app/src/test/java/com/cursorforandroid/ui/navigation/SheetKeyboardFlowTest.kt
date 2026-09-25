package com.cursorforandroid.ui.navigation

import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sheets and the keyboard in the running shell, on the demo: opening the phone's sidebar drawer or a chat's side
 * panel by its button lets the composer go and puts the keyboard away with its draft kept, and the sidebar's search
 * takes the keyboard as usual once the drawer is in. On a wide window the rail comes back beside the chat rather than
 * over it, and the side panel is pinned beside the chat as well, so a composer holding the keyboard keeps both through
 * either. Shutting the drawer, or collapsing the rail, takes the keyboard from the sidebar's search, while a composer
 * beside the collapsing rail keeps it. The swipes themselves, and the ones that must leave the keyboard be, are
 * [com.cursorforandroid.ui.panel.SheetKeyboardTest]'s.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SheetKeyboardFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var wide by mutableStateOf(false)

    @Before
    fun enterDemo() {
        graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking { graph.session.enterDemo() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    wide = wide,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun described(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun softInputVisible(): Boolean =
        shadowOf(compose.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    /** The composer whose empty field shows [placeholder], and then the same field once it holds [Draft]. */
    private fun composer(placeholder: String) = compose.onNode(hasSetTextAction() and hasText(placeholder, substring = true))

    private val written: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasText(Draft))

    private fun fieldText(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun startWriting(placeholder: String) {
        composer(placeholder).performClick()
        composer(placeholder).performTextInput(Draft)
        written.assertIsFocused()
        compose.waitUntil(5_000) { softInputVisible() }
    }

    private fun assertComposerReleased() {
        compose.waitForIdle()
        written.assertIsNotFocused()
        assertThat(softInputVisible()).isFalse()
        assertThat(fieldText(written)).isEqualTo(Draft)
    }

    private fun openChat(name: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(name, substring = true)) }.isSuccess
        }
        compose.onAllNodesWithText(name).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(30_000) { onScreen(CHAT_PLACEHOLDER) }
        compose.waitForIdle()
    }

    @Test
    fun `a chat's panel button puts the keyboard away and keeps the follow-up`() {
        openChat(IDLE_CHAT)
        startWriting(CHAT_PLACEHOLDER)

        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(10_000) { described(DISMISS_PANEL) }
        assertComposerReleased()
    }

    @Test
    fun `the sidebar button puts the New Chat composer's keyboard away, and the sidebar's search takes it after`() {
        startWriting(HOME_PLACEHOLDER)

        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(10_000) { described(CLOSE_DRAWER) }
        assertComposerReleased()

        compose.onNodeWithContentDescription("Search chats").performClick()
        val search = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sidebar-search")))
        compose.waitUntil(5_000) { runCatching { search.assertIsFocused() }.isSuccess }
        compose.waitUntil(5_000) { softInputVisible() }
        assertThat(fieldText(written)).isEqualTo(Draft)
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp-night-mdpi")
    fun `on a wide window the rail and the panel both come back beside the chat and leave the keyboard be`() {
        compose.runOnIdle { wide = true }
        openChat(IDLE_CHAT)
        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitUntil(10_000) { described("Open sidebar") }
        startWriting(CHAT_PLACEHOLDER)

        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(10_000) { described("Toggle sidebar") && !described("Open sidebar") }
        compose.waitForIdle()
        written.assertIsFocused()
        assertThat(softInputVisible()).isTrue()
        assertThat(fieldText(written)).isEqualTo(Draft)

        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(10_000) { described(HIDE_PANEL) }
        compose.waitForIdle()
        assertThat(described(DISMISS_PANEL)).isFalse()
        written.assertIsFocused()
        assertThat(softInputVisible()).isTrue()
        assertThat(fieldText(written)).isEqualTo(Draft)
    }

    private val search: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sidebar-search")))

    private fun searchShown() = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("sidebar-search"))).fetchSemanticsNodes().isNotEmpty()

    /** The sidebar's search holding focus with the keyboard up for it, opened by its button unless it is open already. */
    private fun startSearching() {
        if (searchShown()) search.performClick() else compose.onNodeWithContentDescription("Search chats").performClick()
        compose.waitUntil(5_000) { runCatching { search.assertIsFocused() }.isSuccess }
        compose.waitUntil(5_000) { softInputVisible() }
    }

    @Test
    fun `shutting the phone's drawer takes the keyboard from the sidebar's search, by the scrim or by the sidebar's own button`() {
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(10_000) { described(CLOSE_DRAWER) }
        startSearching()

        compose.onNodeWithContentDescription(CLOSE_DRAWER).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { !described(CLOSE_DRAWER) }
        compose.waitForIdle()
        search.assertIsNotFocused()
        assertThat(softInputVisible()).isFalse()

        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(10_000) { described(CLOSE_DRAWER) }
        startSearching()

        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitUntil(10_000) { !described(CLOSE_DRAWER) }
        compose.waitForIdle()
        search.assertIsNotFocused()
        assertThat(softInputVisible()).isFalse()
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp-night-mdpi")
    fun `on a wide window collapsing the rail takes the keyboard from its search as the slide begins, and leaves a composer beside it be`() {
        compose.runOnIdle { wide = true }
        openChat(IDLE_CHAT)
        startWriting(CHAT_PLACEHOLDER)

        // The composer beside the rail is not the rail's to take.
        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitUntil(10_000) { described("Open sidebar") }
        compose.waitForIdle()
        written.assertIsFocused()
        assertThat(softInputVisible()).isTrue()
        assertThat(fieldText(written)).isEqualTo(Draft)

        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(10_000) { described("Toggle sidebar") && !described("Open sidebar") }
        startSearching()

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Toggle sidebar").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            // Still sliding out, the search still composed: it has let go already, and the keyboard is down.
            assertThat(searchShown()).isTrue()
            search.assertIsNotFocused()
            assertThat(softInputVisible()).isFalse()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { described("Open sidebar") && !searchShown() }
        compose.waitForIdle()
        written.assertIsNotFocused()
        assertThat(softInputVisible()).isFalse()
        assertThat(fieldText(written)).isEqualTo(Draft)
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val IDLE_CHAT = "Cli exploration"
        const val DISMISS_PANEL = "Dismiss panel"
        const val HIDE_PANEL = "Hide panel"
        const val CLOSE_DRAWER = "Close navigation menu"
        const val Draft = "Half a thought about the"
    }
}
