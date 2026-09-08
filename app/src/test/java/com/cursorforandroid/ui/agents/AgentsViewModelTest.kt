package com.cursorforandroid.ui.agents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.dto.IdResponseDto
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.repo.CursorBackend
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
    /** When set, the demo's delete endpoint throws it, which the demo backend itself never does. */
    @Volatile private var failDelete: Throwable? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        // Real dispatch, real delays: the clock and the poller are timed against the wall, shortened per test.
        Dispatchers.setMain(Dispatchers.Default)
        AppClock.nowMillis = { now }
        // The demo's own data (seventeen agents, three running, three pinned) with a delete that can be refused.
        val (demoApi, demoStreamer) = DemoBackendFactory.create()
        val api = object : CursorApi by demoApi {
            override suspend fun delete(id: String): IdResponseDto {
                failDelete?.let { throw it }
                return demoApi.delete(id)
            }
        }
        graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            demo = CursorBackend(api, demoStreamer, isDemo = true),
        )
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
        // The demo pins its three showcase chats: the sidebar lifts them out into a Pinned group, the recent list
        // leaves them in their place by recency.
        assertThat(loaded.sections.first().title).isEqualTo("Pinned")
        assertThat(loaded.sections.first().rows.map { it.agent.id }).containsExactly("bc-demo-0001", "bc-demo-0002", "bc-demo-0003")
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
        assertThat(noRunning.recentRows.map { it.agent.id }.toSet()).isEqualTo(noRunning.sections.flatMap { it.rows }.map { it.agent.id }.toSet())

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

    @Test
    fun `a delete the server refuses keeps the chat and its transcript, and says so`() = runBlocking<Unit> {
        val vm = AgentsViewModel(graph)
        val id = vm.loaded().recentRows.first().agent.id
        // The retained transcript is the only copy of a run whose server-side event log has expired.
        graph.caches.conversations.write(CachedConversation(id, emptyList(), emptyList()))

        failDelete = CursorApiException(503, "unavailable", "Try again later.")
        vm.delete(id).join()

        val refused = withTimeout(10_000) { vm.uiState.first { it.error != null } }
        assertThat(refused.error).isNotEmpty()
        assertThat(graph.agents.agent(id)).isNotNull()
        assertThat(graph.caches.conversations.read(id)).isNotNull()

        failDelete = null
        vm.delete(id).join()

        awaitUntil { graph.agents.agent(id) == null }
        awaitUntil { graph.caches.conversations.read(id) == null }
        assertThat(withTimeout(10_000) { vm.uiState.first { it.error == null } }.error).isNull()
    }
}
