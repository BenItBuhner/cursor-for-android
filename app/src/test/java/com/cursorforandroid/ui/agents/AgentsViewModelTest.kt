package com.cursorforandroid.ui.agents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.SortOrder
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The list state both the sidebar and the New Chat pane render from, against the demo backend (seventeen agents, three
 * of them running, three pinned). Robolectric only because [AppGraph] needs a Context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentsViewModelTest {

    private lateinit var graph: AppGraph
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() = runBlocking<Unit> {
        // Real dispatch, real delays: the clock and the poller are timed against the wall, shortened per test.
        Dispatchers.setMain(Dispatchers.Default)
        AppClock.nowMillis = { now }
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        graph.session.enterDemo()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private suspend fun AgentsViewModel.loaded(): AgentListUiState = withTimeout(10_000) {
        uiState.first { it.hasLoaded && !it.isRefreshing && it.recentRows.isNotEmpty() }
    }

    @Test
    fun `the recent list is the sidebar's rows newest first and follows its filters but not its search`() = runBlocking<Unit> {
        val vm = AgentsViewModel(graph)
        val loaded = vm.loaded()
        assertThat(loaded.recentRows.map { it.agent.id }).isEqualTo(AgentListOrganizer.recentRows(loaded.sections).map { it.agent.id })
        assertThat(loaded.recentRows.map { it.agent.updatedAtMillis }).isInOrder(reverseOrder<Long>())
        // The demo's Project leads, its workers and side chat folded under it; the demo pins its three showcase
        // chats, which the sidebar lifts out into a Pinned group. The recent list leaves all of them in their place
        // by recency, the folded chats included.
        assertThat(loaded.sections.map { it.title }.take(2)).containsExactly("Projects", "Pinned").inOrder()
        val project = loaded.sections.first().rows.single()
        assertThat(project.agent.id).isEqualTo(DemoData.PROJECT_ID)
        assertThat(project.children.map { it.agent.id }).containsExactly("bc-demo-0019", "bc-demo-0020", "bc-demo-0021").inOrder()
        assertThat(loaded.sections[1].rows.map { it.agent.id }).containsExactly("bc-demo-0001", "bc-demo-0002", "bc-demo-0003")
        assertThat(loaded.recentRows.map { it.agent.id }).containsAtLeast(DemoData.PROJECT_ID, "bc-demo-0019", "bc-demo-0020", "bc-demo-0021")
        assertThat(loaded.recentRows.first().agent.id).isEqualTo("bc-demo-0004")
        assertThat(loaded.recentRows.count { it.indicator == AgentIndicator.Running }).isEqualTo(3)
        assertThat(loaded.runningCount).isEqualTo(3)
        // Only a run status makes a row running: the finished demo chats read ACTIVE from the lifecycle like the rest.
        assertThat(loaded.allAgents.none { it.isRunning && it.runStatus?.isActive != true }).isTrue()

        // A status filter chosen in the sidebar's menu hides the same rows on both surfaces.
        vm.toggleStatus(StatusFilter.Running)
        val noRunning = withTimeout(10_000) { vm.uiState.first { StatusFilter.Running !in it.prefs.statuses } }
        assertThat(noRunning.recentRows.none { it.indicator == AgentIndicator.Running }).isTrue()
        assertThat(noRunning.recentRows).hasSize(loaded.recentRows.size - 3)
        assertThat(noRunning.recentRows.map { it.agent.id }.toSet()).isEqualTo(noRunning.sections.flatMap { it.rows }.flatMap { listOf(it) + it.descendants() }.map { it.agent.id }.toSet())

        // The sort order arranges the sidebar; the recents stay newest first. Two changes in quick succession — before
        // the first has reached the state — compose, rather than the second being computed from the stale state and
        // undoing the first.
        vm.toggleStatus(StatusFilter.Running)
        vm.setSortOrder(SortOrder.Name)
        val byName = withTimeout(10_000) { vm.uiState.first { it.prefs.sortOrder == SortOrder.Name && StatusFilter.Running in it.prefs.statuses } }
        byName.sections.forEach { section -> assertThat(section.rows.map { it.agent.name.lowercase() }).isInOrder() }
        assertThat(byName.recentRows.map { it.agent.id }).isEqualTo(loaded.recentRows.map { it.agent.id })

        // The sidebar search narrows the sidebar only.
        vm.setQuery("cesium")
        val searched = withTimeout(10_000) { vm.uiState.first { it.query == "cesium" } }
        val sidebar = searched.sections.flatMap { it.rows }
        assertThat(sidebar).isNotEmpty()
        assertThat(sidebar.all { AgentListOrganizer.matchesQuery(it.agent, "cesium") }).isTrue()
        assertThat(searched.recentRows.map { it.agent.id }).isEqualTo(loaded.recentRows.map { it.agent.id })
    }

    @Test
    fun `read all marks every loaded conversation read`() = runBlocking<Unit> {
        val vm = AgentsViewModel(graph)
        val loaded = vm.loaded()
        assertThat(loaded.unreadCount).isGreaterThan(0)
        assertThat(loaded.sections.flatMap { it.rows }.any { it.isUnread }).isTrue()

        vm.markAllRead()
        val cleared = withTimeout(10_000) { vm.uiState.first { it.unreadCount == 0 && it.hasLoaded } }
        assertThat(cleared.allAgents).isNotEmpty()
        assertThat(cleared.sections.flatMap { it.rows }.none { it.isUnread }).isTrue()
        assertThat(cleared.sections.flatMap { it.rows }.none { it.indicator == AgentIndicator.Unread }).isTrue()
        val markers = graph.prefs.localAgentState.first().readMarkers
        cleared.allAgents.forEach { agent ->
            assertThat(markers[agent.id] ?: 0L).isAtLeast(agent.updatedAtMillis)
        }
    }

    @Test
    fun `relative ages are computed against a clock that keeps ticking while the data stands still`() = runBlocking<Unit> {
        val vm = AgentsViewModel(graph, clockTickMs = 20)
        val initial = vm.loaded()
        assertThat(initial.nowMillis).isEqualTo(now)

        now += 3 * 60_000
        val ticked = withTimeout(10_000) { vm.uiState.first { it.nowMillis == now } }
        // Nothing about the data changed, only the clock the rows format their ages against.
        assertThat(ticked.recentRows.map { it.agent.id }).isEqualTo(initial.recentRows.map { it.agent.id })
        assertThat(ticked.recentRows.map { it.agent.updatedAtMillis }).isEqualTo(initial.recentRows.map { it.agent.updatedAtMillis })
    }

    @Test
    fun `while on screen the list refreshes itself quietly once the last fetch is old enough`() = runBlocking<Unit> {
        val vm = AgentsViewModel(graph, pollIntervalMs = 100)
        vm.loaded()
        awaitUntil { graph.agents.lastRefreshedAt == now }
        val polling = vm.pollWhileVisible()
        try {
            // A list fetched moments ago is left alone...
            delay(400)
            assertThat(graph.agents.lastRefreshedAt).isEqualTo(now)
            // ...and fetched again, without a spinner, once it is older than half the polling interval.
            now += 60_000
            awaitUntil { graph.agents.lastRefreshedAt == now }
            assertThat(vm.uiState.value.isRefreshing).isFalse()
        } finally {
            polling.cancel()
        }
    }
}
