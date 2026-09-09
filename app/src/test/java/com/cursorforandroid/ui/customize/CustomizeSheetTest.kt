package com.cursorforandroid.ui.customize

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        )
        graph.session.enterDemo()
    }

    /**
     * The rule's own `waitUntil` runs on the frame clock, which the disk-backed list preferences do not; this waits
     * on the wall while keeping the main looper and the composition moving.
     */
    private fun awaitOnScreen(condition: () -> Boolean) {
        repeat(500) {
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Condition was still not satisfied after 10s")
    }

    private fun composed(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun openRepoPage(): AgentsViewModel {
        val viewModel = AgentsViewModel(graph)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { CustomizeSheet(viewModel, onDismiss = {}) }
        }
        // Wait for the list itself: the Repo page of an account whose agents have not landed yet has no rows at all.
        awaitOnScreen { composed("Repo") && viewModel.uiState.value.repoSlugs.size == repoCount }
        compose.onNodeWithText("Repo").performClick()
        awaitOnScreen { composed("All repositories") }
        return viewModel
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
        awaitOnScreen { viewModel.uiState.value.prefs.repos != null }
        val repos = viewModel.uiState.value.prefs.repos
        assertThat(repos).doesNotContain("acme/repo-00")
        assertThat(repos).hasSize(repoCount - 1)
    }
}
