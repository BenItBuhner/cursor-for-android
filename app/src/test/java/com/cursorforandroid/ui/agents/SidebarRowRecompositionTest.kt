package com.cursorforandroid.ui.agents

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.RecomposeCounter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A list emission that changes nothing a row shows leaves the sidebar's rows alone: the rows are rebuilt on every
 * emission, and skip only because their domain types are declared stable (app/compose-stability.conf) and so are
 * compared by equality rather than identity. Driven through the wide shell on the demo, whose sidebar stays on screen,
 * with the clock held still so that the minute tick, which does change every row's age, cannot land mid-test.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarRowRecompositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: AppGraph

    private val vm: AgentsViewModel get() = ViewModelProvider(compose.activity, AgentsViewModel.Factory(graph))[AgentsViewModel::class.java]

    @Before
    fun setUp() {
        val now = System.currentTimeMillis()
        AppClock.nowMillis = { now }
        RecomposeCounter.install()
        graph = AppGraph(context)
        runBlocking { graph.session.enterDemo() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    wide = true,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(HOME_PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        compose.waitForIdle()
        assertThat(RecomposeCounter.count(ROW)).isAtLeast(MIN_ROWS)
    }

    @After
    fun tearDown() {
        RecomposeCounter.uninstall()
        AppClock.nowMillis = System::currentTimeMillis
        runBlocking { graph.prefs.setSidebarSectionCollapsed(UNKNOWN_SECTION, false) }
    }

    @Test
    fun `a list emission that changes no row recomposes no row`() {
        val before = vm.uiState.value
        RecomposeCounter.reset()
        vm.setSectionCollapsed(UNKNOWN_SECTION, true)
        compose.waitUntil(10_000) { vm.uiState.value !== before }
        compose.waitForIdle()

        assertRowsSkipped()
    }

    @Test
    fun `a refresh that brings back the same chats recomposes no row`() {
        val refreshes = graph.agents.refreshCompleted.value
        RecomposeCounter.reset()
        vm.refresh()
        compose.waitUntil(30_000) { graph.agents.refreshCompleted.value > refreshes && !graph.agents.state.value.isRefreshing }
        compose.waitForIdle()

        assertRowsSkipped()
    }

    /** The emission reached the sidebar, which ran again, and at most one of its rows did. */
    private fun assertRowsSkipped() {
        val ran = RecomposeCounter.top()
        assertWithMessage(ran).that(RecomposeCounter.count(SIDEBAR)).isAtLeast(1)
        assertWithMessage(ran).that(RecomposeCounter.count(ROW)).isAtMost(1)
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val UNKNOWN_SECTION = "no-such-section"
        /** Rows the demo's sidebar draws at least once on opening, so that "no row recomposed" means something. */
        const val MIN_ROWS = 10
        const val SIDEBAR = "ui.agents.Sidebar"
        const val ROW = "ui.agents.AgentRowItem"
    }
}
