package com.cursorforandroid.ui.agents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
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
        uiState.first { it.hasLoaded && !it.isRefreshing && it.rows.isNotEmpty() }
    }

    @Test
    fun `the recent list shows the sidebar's rows in its sort order and follows its filters`() = runBlocking<Unit> {
        val vm = AgentsViewModel(graph)
        val loaded = vm.loaded()
        assertThat(loaded.rows.map { it.agent.id }).isEqualTo(AgentListOrganizer.flatten(loaded.sections, loaded.prefs.sortOrder).map { it.agent.id })
        assertThat(loaded.rows.map { it.agent.updatedAtMillis }).isInOrder(reverseOrder<Long>())
        // The demo pins its three showcase chats: the sidebar lifts them out into a Pinned group, the recent list
        // leaves them in their place by recency.
        assertThat(loaded.sections.first().title).isEqualTo("Pinned")
        assertThat(loaded.sections.first().rows.map { it.agent.id }).containsExactly("bc-demo-0001", "bc-demo-0002", "bc-demo-0003")
        assertThat(loaded.rows.first().agent.id).isEqualTo("bc-demo-0004")
        assertThat(loaded.rows.count { it.indicator == AgentIndicator.Running }).isEqualTo(3)
        assertThat(loaded.runningCount).isEqualTo(3)
        // Only a run status makes a row running: the finished demo chats read ACTIVE from the lifecycle like the rest.
        assertThat(loaded.allAgents.none { it.isRunning && it.runStatus?.isActive != true }).isTrue()

        // A status filter chosen in the sidebar's menu hides the same rows on both surfaces.
        vm.toggleStatus(StatusFilter.Running)
        val noRunning = withTimeout(10_000) { vm.uiState.first { StatusFilter.Running !in it.prefs.statuses } }
        assertThat(noRunning.rows.none { it.indicator == AgentIndicator.Running }).isTrue()
        assertThat(noRunning.rows).hasSize(loaded.rows.size - 3)
        assertThat(noRunning.rows.map { it.agent.id }.toSet()).isEqualTo(noRunning.sections.flatMap { it.rows }.map { it.agent.id }.toSet())

        // So does the sort order. Two changes in quick succession — before the first has reached the state — compose,
        // rather than the second being computed from the stale state and undoing the first.
        vm.toggleStatus(StatusFilter.Running)
        vm.setSortOrder(SortOrder.Name)
        val byName = withTimeout(10_000) { vm.uiState.first { it.prefs.sortOrder == SortOrder.Name && StatusFilter.Running in it.prefs.statuses } }
        assertThat(byName.rows.map { it.agent.name.lowercase() }).isInOrder()
        assertThat(byName.rows).hasSize(loaded.rows.size)

        // And the search field.
        vm.setQuery("cesium")
        val searched = withTimeout(10_000) { vm.uiState.first { it.query == "cesium" } }
        assertThat(searched.rows).isNotEmpty()
        assertThat(searched.rows.all { AgentListOrganizer.matchesQuery(it.agent, "cesium") }).isTrue()
        assertThat(searched.rows.map { it.agent.id }).containsExactlyElementsIn(searched.sections.flatMap { it.rows }.map { it.agent.id })
    }

    @Test
    fun `relative ages are computed against a clock that keeps ticking while the data stands still`() = runBlocking<Unit> {
        val vm = AgentsViewModel(graph, clockTickMs = 20)
        val initial = vm.loaded()
        assertThat(initial.nowMillis).isEqualTo(now)

        now += 3 * 60_000
        val ticked = withTimeout(10_000) { vm.uiState.first { it.nowMillis == now } }
        // Nothing about the data changed, only the clock the rows format their ages against.
        assertThat(ticked.rows.map { it.agent.id }).isEqualTo(initial.rows.map { it.agent.id })
        assertThat(ticked.rows.map { it.agent.updatedAtMillis }).isEqualTo(initial.rows.map { it.agent.updatedAtMillis })
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
