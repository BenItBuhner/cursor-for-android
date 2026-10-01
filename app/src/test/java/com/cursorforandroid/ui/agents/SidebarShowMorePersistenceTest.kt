package com.cursorforandroid.ui.agents

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.awaitSynced
import com.cursorforandroid.util.holdFrameClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Show N more" is the device's to remember, like a fold: the tap goes through the view model to the device's
 * preferences, a sidebar composed again from nothing (the activity recreated) reads it back from the same view model,
 * and a new view model on the same device — the app started again — reads it back before anything is tapped. "Show
 * less" is kept the same way. Against the demo backend, whose Pinned group is made long by pinning seven of its chats
 * (the demo has one Project; Projects and Pinned are cut, listed and remembered by the one mechanism, under their own
 * section keys — see `SidebarShowMoreTest` for the Projects group at the sidebar's level).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarShowMorePersistenceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private lateinit var context: Context

    private val pinned = mapOf(
        "bc-demo-0001" to "Codex-Poly-Bot Scaling",
        "bc-demo-0002" to "Revenue Scaling Pipeline Research",
        "bc-demo-0003" to "Cesium Revenue Strategy",
        "bc-demo-0004" to "Cli exploration",
        "bc-demo-0005" to "House environment overhaul",
        "bc-demo-0006" to "Market replay engine",
        "bc-demo-0007" to "Latest release process",
    )

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        val (demoApi, demoStreamer) = DemoBackendFactory.create()
        graph = AppGraph(context, demo = CursorBackend(demoApi, demoStreamer, isDemo = true))
        graph.session.enterDemo()
        val now = graph.prefs.localAgentState.first().pinnedIds
        pinned.keys.filter { it !in now }.forEach { graph.pins.toggle(it) }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val now = graph.prefs.localAgentState.first().pinnedIds
        pinned.keys.filter { it in now }.forEach { graph.pins.toggle(it) }
        graph.prefs.setSidebarSectionListedInFull(AgentListOrganizer.PINNED_KEY, false)
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The pinned chats the sidebar lists (pinned chats are drawn in the Pinned group alone). */
    private fun listedPinned(): Set<String> = pinned.values.filterTo(HashSet(), ::exists)

    private val moreRow get() = compose.onNodeWithTag("section-more-${AgentListOrganizer.PINNED_KEY}")

    private fun moreText(): String? = compose.onAllNodes(hasTestTag("section-more-${AgentListOrganizer.PINNED_KEY}")).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun savedOnDevice(): Set<String> = runBlocking { PreferencesStore(context).listedInFullSidebarSections.first() }

    @Test
    fun `Show more and Show less are written to the device, survive the composition being rebuilt, and are read back by the next view model`() {
        // Which view model the sidebar reads from: swapping it is the restart, the composition standing in for the app's.
        var viewModel by mutableStateOf(AgentsViewModel(graph))
        val restorer = StateRestorationTester(compose)
        compose.holdFrameClock()
        restorer.setContent {
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
                        onSectionListedInFull = vm::setSectionListedInFull,
                    ),
                )
            }
        }
        compose.awaitSynced { viewModel.uiState.value.hasLoaded && listedPinned().size == 5 }
        assertThat(moreText()).isEqualTo("Show 2 more")
        assertThat(savedOnDevice()).isEmpty()

        // "Show 2 more", tapped: every pinned chat listed, and the device has it.
        moreRow.performClick()
        compose.awaitSynced { viewModel.uiState.value.listedInFullSections.contains(AgentListOrganizer.PINNED_KEY) && listedPinned().size == 7 }
        assertThat(moreText()).isEqualTo("Show less")
        assertThat(savedOnDevice()).containsExactly(AgentListOrganizer.PINNED_KEY)

        // The activity recreated: the composition is built again from its saved state, on the same view model.
        restorer.emulateSavedInstanceStateRestore()
        compose.awaitSynced { listedPinned().size == 7 }
        assertThat(moreText()).isEqualTo("Show less")

        // Restart: a new view model, a fresh read of the same device. Pinned comes back listed in full, nothing tapped.
        viewModel = AgentsViewModel(graph)
        compose.awaitSynced { viewModel.uiState.value.hasLoaded && viewModel.uiState.value.listedInFullSections.contains(AgentListOrganizer.PINNED_KEY) }
        compose.awaitSynced { listedPinned().size == 7 }
        assertThat(moreText()).isEqualTo("Show less")
        // The other long group was never listed in full.
        assertThat(viewModel.uiState.value.listedInFullSections).containsExactly(AgentListOrganizer.PINNED_KEY)

        // "Show less": cut back, and the device forgets — so the next start opens on the short list again.
        moreRow.performClick()
        compose.awaitSynced { viewModel.uiState.value.listedInFullSections.isEmpty() && listedPinned().size == 5 }
        assertThat(moreText()).isEqualTo("Show 2 more")
        assertThat(savedOnDevice()).isEmpty()

        viewModel = AgentsViewModel(graph)
        compose.awaitSynced { viewModel.uiState.value.hasLoaded && listedPinned().size == 5 }
        assertThat(moreText()).isEqualTo("Show 2 more")
    }
}
