package com.cursorforandroid.ui.agents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.dto.IdResponseDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.SortOrder
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import com.cursorforandroid.util.MainDispatcherRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The list state both the sidebar and the New Chat pane render from, against the demo backend (seventeen agents, three
 * of them running, three pinned). Robolectric only because [AppGraph] needs a Context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule { Dispatchers.Unconfined }

    private lateinit var graph: AppGraph
    private var now = 1_800_000_000_000L
    /** When set, the demo's delete endpoint throws it, which the demo backend itself never does. */
    @Volatile private var failDelete: Throwable? = null
    /** When set, the demo's v1 list endpoint throws it, for the polling cadence under a server that will not answer. */
    @Volatile private var failList: Throwable? = null
    @Volatile private var listCalls = 0
    private val listCallReached = mutableListOf<CompletableDeferred<Unit>>()
    @Volatile private var blockListCall: Int? = null
    private var listCallGate = CompletableDeferred<Unit>()

    @Before
    fun setUp() = runBlocking {
        AppClock.nowMillis = { now }
        buildGraph()
    }

    private suspend fun buildGraph() {
        listCalls = 0
        listCallReached.clear()
        blockListCall = null
        listCallGate = CompletableDeferred()
        val (demoApi, demoStreamer) = DemoBackendFactory.create()
        val api = object : CursorApi by demoApi {
            override suspend fun delete(id: String): IdResponseDto {
                failDelete?.let { throw it }
                return demoApi.delete(id)
            }

            override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean): ListAgentsResponseDto {
                listCalls++
                listCallReached.getOrNull(listCalls - 1)?.complete(Unit)
                if (listCalls == blockListCall) listCallGate.await()
                failList?.let { throw it }
                return demoApi.listAgents(limit, cursor, includeArchived)
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
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun expectListCall(index: Int): CompletableDeferred<Unit> {
        while (listCallReached.size <= index) listCallReached += CompletableDeferred()
        return listCallReached[index]
    }

    /** The list once a fetch has settled: the indicator let go, and nothing still syncing underneath it. */
    private suspend fun AgentsViewModel.loaded(): AgentListUiState =
        uiState.first { it.hasLoaded && !it.isRefreshing && !it.isSyncingOlder && it.recentRows.isNotEmpty() }

    private suspend fun awaitRefresh(after: Long = graph.agents.refreshCompleted.value) {
        graph.agents.refreshCompleted.first { it > after }
    }

    @Test
    fun `the recent list is the sidebar's rows newest first and follows its filters but not its search`() = runBlocking<Unit> {
        mainDispatcher.set(Dispatchers.Unconfined)
        val vm = AgentsViewModel(graph)
        val loaded = vm.loaded()
        assertThat(loaded.recentRows.map { it.agent.id }).isEqualTo(AgentListOrganizer.recentRows(loaded.sections).map { it.agent.id })
        assertThat(loaded.recentRows.map { it.agent.updatedAtMillis }).isInOrder(reverseOrder<Long>())
        // The demo's Project leads, its workers and side chat folded under it; the demo pins its three showcase
        // chats, which the sidebar lifts out into a Pinned group. The recent list leaves the pinned chats in their
        // place by recency; the Project and everything folded under it belong to the Project's surface, not here.
        assertThat(loaded.sections.map { it.title }.take(2)).containsExactly("Projects", "Pinned").inOrder()
        val project = loaded.sections.first().rows.single()
        assertThat(project.agent.id).isEqualTo(DemoData.PROJECT_ID)
        assertThat(project.children.map { it.agent.id }).containsExactly("bc-demo-0019", "bc-demo-0020", "bc-demo-0021").inOrder()
        assertThat(loaded.sections[1].rows.map { it.agent.id }).containsExactly("bc-demo-0001", "bc-demo-0002", "bc-demo-0003")
        assertThat(loaded.recentRows.map { it.agent.id }).containsNoneOf(DemoData.PROJECT_ID, "bc-demo-0019", "bc-demo-0020", "bc-demo-0021")
        assertThat(loaded.recentRows.first().agent.id).isEqualTo("bc-demo-0004")
        assertThat(loaded.recentRows.count { it.indicator == AgentIndicator.Running }).isEqualTo(3)
        assertThat(loaded.runningCount).isEqualTo(3)
        assertThat(loaded.allAgents.none { it.isRunning && it.runStatus?.isActive != true }).isTrue()

        vm.toggleStatus(StatusFilter.Running)
        val noRunning = vm.uiState.first { StatusFilter.Running !in it.prefs.statuses }
        // The filter takes the running chats off both surfaces — a pinned one excepted, which the user's word keeps.
        assertThat(noRunning.recentRows.none { it.indicator == AgentIndicator.Running && !it.isPinned }).isTrue()
        val pinnedRunning = loaded.recentRows.count { it.indicator == AgentIndicator.Running && it.isPinned }
        assertThat(noRunning.recentRows).hasSize(loaded.recentRows.size - 3 + pinnedRunning)
        assertThat(noRunning.recentRows.map { it.agent.id }.toSet()).isEqualTo(noRunning.sections.flatMap { it.rows }.filterNot { it.agent.isProjectScoped }.map { it.agent.id }.toSet())

        vm.toggleStatus(StatusFilter.Running)
        vm.setSortOrder(SortOrder.Name)
        val byName = vm.uiState.first { it.prefs.sortOrder == SortOrder.Name && StatusFilter.Running in it.prefs.statuses }
        byName.sections.forEach { section -> assertThat(section.rows.map { it.agent.name.lowercase() }).isInOrder() }
        assertThat(byName.recentRows.map { it.agent.id }).isEqualTo(loaded.recentRows.map { it.agent.id })

        vm.setQuery("cesium")
        val searched = vm.uiState.first { it.query == "cesium" }
        val sidebar = searched.sections.flatMap { it.rows }
        assertThat(sidebar).isNotEmpty()
        assertThat(sidebar.all { AgentListOrganizer.matchesQuery(it.agent, "cesium") }).isTrue()
        assertThat(searched.recentRows.map { it.agent.id }).isEqualTo(loaded.recentRows.map { it.agent.id })
    }

    @Test
    fun `read all marks every loaded conversation read`() = runBlocking<Unit> {
        mainDispatcher.set(Dispatchers.Unconfined)
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
    fun `relative ages are computed against a clock that keeps ticking while the data stands still`() = runTest {
        mainDispatcher.set(StandardTestDispatcher(testScheduler))
        val vm = AgentsViewModel(graph, clockTickMs = 20)
        advanceUntilIdle()
        val initial = vm.loaded()
        assertThat(initial.nowMillis).isEqualTo(now)

        now += 3 * 60_000
        advanceTimeBy(20)
        runCurrent()
        val ticked = vm.uiState.first { it.nowMillis == now }
        assertThat(ticked.recentRows.map { it.agent.id }).isEqualTo(initial.recentRows.map { it.agent.id })
        assertThat(ticked.recentRows.map { it.agent.updatedAtMillis }).isEqualTo(initial.recentRows.map { it.agent.updatedAtMillis })
    }

    @Test
    fun `while on screen the list refreshes itself quietly once the last fetch is old enough`() = runTest {
        mainDispatcher.set(StandardTestDispatcher(testScheduler))
        val vm = AgentsViewModel(graph, pollIntervalMs = 100)
        advanceUntilIdle()
        vm.loaded()
        assertThat(graph.agents.lastRefreshedAt).isEqualTo(now)
        val before = graph.agents.refreshCompleted.value
        val polling = vm.pollWhileVisible()
        try {
            assertThat(graph.agents.refreshCompleted.value).isEqualTo(before)
            now += 60_000
            advanceTimeBy(100)
            runCurrent()
            awaitRefresh(before)
            assertThat(graph.agents.lastRefreshedAt).isEqualTo(now)
            assertThat(vm.uiState.value.isRefreshing).isFalse()
        } finally {
            polling.cancel()
        }
    }

    @Test
    fun `polling backs off while the list cannot be fetched and picks its cadence up once it can`() = runTest {
        mainDispatcher.set(StandardTestDispatcher(testScheduler))
        listCallGate = CompletableDeferred()
        val vm = AgentsViewModel(graph, pollIntervalMs = 100)
        advanceUntilIdle()
        vm.loaded()
        now += 60_000
        failList = IOException("offline")
        val polling = vm.pollWhileVisible()
        try {
            expectListCall(3).await()
            assertThat(listCalls).isEqualTo(4)
            blockListCall = 5
            advanceTimeBy(2_000)
            runCurrent()
            assertThat(listCalls).isEqualTo(4)

            failList = null
            now += 60_000
            blockListCall = null
            listCallGate.complete(Unit)
            awaitRefresh()
            failList = IOException("offline")
            now += 60_000
            val start = listCalls
            expectListCall(start).await()
            expectListCall(start + 1).await()
        } finally {
            blockListCall = null
            if (!listCallGate.isCompleted) listCallGate.complete(Unit)
            polling.cancel()
            // What the cancel and the gate's release scheduled on the test's own dispatcher runs to its end here,
            // rather than being reported as coroutines the test left behind.
            advanceUntilIdle()
        }
    }

    @Test
    fun `each completed refresh re-reads the pull requests once their own interval has passed`() = runTest {
        mainDispatcher.set(StandardTestDispatcher(testScheduler))
        val vm = AgentsViewModel(graph, pollIntervalMs = 100)
        advanceUntilIdle()
        vm.loaded()
        // The pass publishes each answer as it lands (the pills fill in one by one), and it runs on the repository's
        // own threads, not the test scheduler: the snapshot is taken only once every pull request the list carries
        // has been answered. Taking it at the first non-empty map compared a partial pass against the finished one
        // later on, and flaked (#111, #119, #149).
        val urls = graph.agents.state.value.agents.mapNotNullTo(HashSet()) { it.prUrl }
        assertThat(urls).isNotEmpty()
        graph.pullRequests.statuses.first { it.keys.containsAll(urls) }
        val first = graph.pullRequests.statuses.value
        assertThat(first.keys).containsExactlyElementsIn(urls)
        assertThat(first.values.map { it.checkedAtMillis }.toSet()).containsExactly(now)

        val polling = vm.pollWhileVisible()
        try {
            val before = graph.agents.refreshCompleted.value
            now += 60_000
            advanceTimeBy(100)
            runCurrent()
            awaitRefresh(before)
            assertThat(graph.pullRequests.statuses.value).isEqualTo(first)

            now += 10 * 60_000
            graph.pullRequests.statuses.first { it.values.any { state -> state.checkedAtMillis == now } }
            assertThat(graph.pullRequests.statuses.value.keys).isEqualTo(first.keys)
        } finally {
            polling.cancel()
        }
    }

    /**
     * The pull request badges are read for the rows on screen, as the sidebar reports them — not for every row the
     * list holds: a row the reader has not scrolled to costs nothing until they do. (Before the sidebar has said
     * what is on screen, a screenful of the newest rows is read; the demo fits in one, so this drives the sidebar's
     * report by hand.)
     */
    @Test
    fun `pull request badges are read for the rows on screen, not for every row`() = runTest {
        mainDispatcher.set(StandardTestDispatcher(testScheduler))
        val vm = AgentsViewModel(graph)
        // The sidebar's first report lands before the list does: one row with a PR is on screen.
        val all = graph.agents.state.first { it.hasLoaded }.agents
        val withPr = all.filter { it.prUrl != null }
        assertThat(withPr.size).isAtLeast(3)
        val onScreen = withPr.first()
        vm.rowsVisible(listOf(onScreen.id))
        advanceUntilIdle()
        vm.loaded()
        graph.pullRequests.statuses.first { onScreen.prUrl in it.keys }
        // Only that row's badge was read: the rows off screen keep theirs unread.
        assertThat(graph.pullRequests.statuses.value.keys).containsExactly(onScreen.prUrl)

        // The reader scrolls: another row with a PR comes on screen, and its badge is read then.
        val scrolledTo = withPr[1]
        vm.rowsVisible(listOf(onScreen.id, scrolledTo.id))
        advanceTimeBy(AgentsViewModel.VISIBLE_DEBOUNCE_MS + 1)
        runCurrent()
        graph.pullRequests.statuses.first { scrolledTo.prUrl in it.keys }
        assertThat(graph.pullRequests.statuses.value.keys).containsExactly(onScreen.prUrl, scrolledTo.prUrl)
    }

    @Test
    fun `a delete the server refuses keeps the chat and its transcript, and says so`() = runBlocking<Unit> {
        mainDispatcher.set(Dispatchers.Unconfined)
        val vm = AgentsViewModel(graph)
        val id = vm.loaded().recentRows.first().agent.id
        graph.caches.conversations.write(CachedConversation(id, emptyList(), emptyList()))

        failDelete = CursorApiException(503, "unavailable", "Try again later.")
        vm.delete(id).join()

        val refused = vm.uiState.first { it.error != null }
        assertThat(refused.error).isNotEmpty()
        assertThat(graph.agents.agent(id)).isNotNull()
        assertThat(graph.caches.conversations.read(id)).isNotNull()

        failDelete = null
        vm.delete(id).join()

        graph.agents.state.first { it.agents.none { agent -> agent.id == id } }
        withTimeout(10_000) {
            while (graph.caches.conversations.read(id) != null) yield()
        }
        assertThat(vm.uiState.first { it.error == null }.error).isNull()
    }
}
