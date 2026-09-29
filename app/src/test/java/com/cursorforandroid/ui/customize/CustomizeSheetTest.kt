package com.cursorforandroid.ui.customize

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filter sheet against an account with more repositories than fit a screen. The Repo page is the only one that
 * can grow without bound, so it is the one that must compose lazily.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CustomizeSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private val repoCount = 60
    private val lastSlug = "repo-%02d".format(repoCount - 1)
    private val readAllTag = "sheet-header-action-$READ_ALL"

    @Before
    fun setUp() = runBlocking<Unit> {
        val api = FakeCursorApi()
        repeat(repoCount) { i ->
            api.addIdleAgent(
                id = "bc-%02d".format(i),
                name = "Chat %02d".format(i),
                runId = "run-%02d".format(i),
                repo = "https://github.com/acme/repo-%02d".format(i),
            )
        }
        graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            demo = CursorBackend(api, FakeRunStreamer(), isDemo = true),
            agentListDispatcher = Dispatchers.Main,
        )
        graph.session.enterDemo()
    }

    /**
     * Waits for [condition] on the semantics tree, whose every read idles the rule first: for what the sheet has drawn,
     * which is what the assertions after it read. The list is organized on the main thread (see
     * [AppGraph.agentListDispatcher]); from a background thread the sheet could miss a state and never draw it.
     */
    private fun awaitOnScreen(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000, condition = condition)

    private fun composed(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The header's Read all as drawn, saying [state]: "60 unread chats" once every chat is in the frame. */
    private fun readAllSays(state: String) =
        compose.onAllNodes(hasTestTag(readAllTag) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)).fetchSemanticsNodes().isNotEmpty()

    private fun openRepoPage(): AgentsViewModel {
        val viewModel = AgentsViewModel(graph)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { CustomizeSheet(viewModel, onDismiss = {}) }
        }
        // Wait for the list itself: the Repo page of an account whose agents have not landed yet has no rows at all.
        awaitOnScreen { composed("Repo") && readAllSays("$repoCount unread chats") }
        compose.onNodeWithText("Repo").performClick()
        awaitOnScreen { composed("All repositories") }
        return viewModel
    }

    @Test
    fun `Read all sits in the header left of Reset, marks every chat read, and then goes off`() {
        val viewModel = AgentsViewModel(graph)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { CustomizeSheet(viewModel, onDismiss = {}) }
        }
        awaitOnScreen { composed("Grouping") && readAllSays("$repoCount unread chats") }
        val readAll = compose.onNodeWithTag(readAllTag)
        readAll.assertIsDisplayed().assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$repoCount unread chats"))
        // In the header row, beside the title, and above the first section: the sheet opens on Grouping.
        val readAllBounds = readAll.fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Chats").fetchSemanticsNode().boundsInRoot
        assertThat(readAllBounds.left).isAtLeast(title.right)
        assertThat(readAllBounds.center.y).isWithin(1f).of(title.center.y)
        assertThat(readAllBounds.bottom).isLessThan(compose.onNodeWithText("Grouping").fetchSemanticsNode().boundsInRoot.top)
        // The Actions section is gone: no header of its own, no card.
        assertThat(composed("Actions")).isFalse()
        assertThat(compose.onAllNodesWithTag("sheet-actions").fetchSemanticsNodes()).isEmpty()
        // Default preferences: nothing to reset, so Read all stands alone.
        assertThat(composed("Reset")).isFalse()

        viewModel.setShowRuntime(true)
        awaitOnScreen { composed("Reset") }
        val reset = compose.onNodeWithTag("sheet-header-action-Reset").fetchSemanticsNode().boundsInRoot
        val readAllBeside = readAll.fetchSemanticsNode().boundsInRoot
        // Directly left of Reset, the two touch areas side by side without overlapping.
        assertThat(readAllBeside.right).isAtMost(reset.left)
        assertThat(readAllBeside.center.y).isWithin(1f).of(reset.center.y)

        readAll.performClick()
        awaitOnScreen { readAllSays("Nothing unread") }
        readAll.assertIsNotEnabled()
        assertThat(viewModel.uiState.value.unreadCount).isEqualTo(0)
        assertThat(compose.onAllNodesWithText(READ_ALL).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun `the repo page composes only the rows on screen and scrolls to the rest`() {
        openRepoPage()
        assertThat(composed("repo-00")).isTrue()
        assertThat(composed(lastSlug)).isFalse()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(lastSlug))
        compose.waitForIdle()
        assertThat(composed(lastSlug)).isTrue()
    }

    @Test
    fun `a repository tapped on the repo page drops it from the filter`() {
        val viewModel = openRepoPage()
        compose.onNodeWithText("repo-00").performClick()
        // A row's check mark carries no semantics, so this waits on the view model, idling the rule before each check.
        compose.waitUntil(timeoutMillis = 10_000) { compose.runOnIdle { viewModel.uiState.value.prefs.repos != null } }
        val repos = viewModel.uiState.value.prefs.repos
        assertThat(repos).doesNotContain("acme/repo-00")
        assertThat(repos).hasSize(repoCount - 1)
    }
}
