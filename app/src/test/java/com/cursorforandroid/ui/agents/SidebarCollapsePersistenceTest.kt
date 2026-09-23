package com.cursorforandroid.ui.agents

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A group folded in the sidebar stays folded across a restart: the fold goes through the view model to the device's
 * preferences, and a new view model on the same device — the app started again — reads it back before anything is
 * tapped. Against the demo backend, whose chats fall into Today and Yesterday by the clock.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarCollapsePersistenceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        val (demoApi, demoStreamer) = DemoBackendFactory.create()
        graph = AppGraph(context, demo = CursorBackend(demoApi, demoStreamer, isDemo = true))
        graph.session.enterDemo()
    }

    /**
     * The rule's own `waitUntil` runs on the frame clock, which the disk-backed preferences do not; this waits on the
     * wall while keeping the main looper and the composition moving.
     */
    private fun awaitOnScreen(condition: () -> Boolean) {
        repeat(1500) {
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Condition was still not satisfied after 30s")
    }

    private fun composed(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a fold made in the sidebar is written to the device and read back by the next view model`() = runBlocking<Unit> {
        // Which view model the sidebar reads from: swapping it is the restart, the composition standing in for the app's.
        var viewModel by mutableStateOf(AgentsViewModel(graph))
        compose.setContent {
            val vm = viewModel
            val state by vm.uiState.collectAsState(context = Dispatchers.Main.immediate)
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = state,
                    user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                    isDemo = true,
                    selectedAgentId = null,
                    selectedDestination = null,
                    onQueryChange = vm::setQuery,
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = {},
                        onCustomize = {},
                        onToggleSidebar = null,
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                        onSectionCollapsed = vm::setSectionCollapsed,
                    ),
                )
            }
        }
        awaitOnScreen { composed("Today") && viewModel.uiState.value.hasLoaded }
        val todayRows = viewModel.uiState.value.sections.first { it.key == "date:Today" }.rows
        assertThat(todayRows).isNotEmpty()
        val firstToday = todayRows.first().agent.name
        compose.onNodeWithText(firstToday).assertIsDisplayed()

        // Folded from the header: the rows go, the count comes, and the device has the fold.
        compose.onNodeWithText("Today").performClick()
        awaitOnScreen { viewModel.uiState.value.collapsedSections.contains("date:Today") }
        compose.onNodeWithText(firstToday).assertDoesNotExist()
        compose.onNodeWithTag("section-count-date:Today", useUnmergedTree = true).assertIsDisplayed()
        assertThat(PreferencesStore(context).collapsedSidebarSections.first()).containsExactly("date:Today")

        // Restart: a new view model, a fresh read of the same device. Today comes back folded, nothing tapped.
        viewModel = AgentsViewModel(graph)
        awaitOnScreen { viewModel.uiState.value.hasLoaded && viewModel.uiState.value.collapsedSections.contains("date:Today") }
        compose.onNodeWithText(firstToday).assertDoesNotExist()
        compose.onNodeWithTag("section-count-date:Today", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand Today").assertIsDisplayed()
        // The other groups were never folded.
        assertThat(viewModel.uiState.value.collapsedSections).containsExactly("date:Today")

        // Opened again, the device forgets the fold.
        compose.onNodeWithContentDescription("Expand Today").performClick()
        awaitOnScreen { viewModel.uiState.value.collapsedSections.isEmpty() }
        compose.onNodeWithText(firstToday).assertIsDisplayed()
        assertThat(PreferencesStore(context).collapsedSidebarSections.first()).isEmpty()
    }
}
