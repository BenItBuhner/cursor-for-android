package com.cursorforandroid.ui.agents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.NewChatPageSnapshot
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.NewChatHomeChoice
import com.cursorforandroid.ui.home.HomeBlock
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.home.homeBlocks
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The New Chat page's Projects before the list's first load: the page as the last launch left it, then — the moment
 * the list is in — the list's own, whatever changed meanwhile (a Project renamed, one deleted, its chats moved on).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentsViewModelPageSeedTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule { Dispatchers.Unconfined }

    private val listGate = CompletableDeferred<Unit>()

    private fun graph(): AppGraph {
        val (demoApi, demoStreamer) = DemoBackendFactory.create()
        val api = object : CursorApi by demoApi {
            override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean): ListAgentsResponseDto {
                listGate.await()
                return demoApi.listAgents(limit, cursor, includeArchived)
            }
        }
        return AppGraph(ApplicationProvider.getApplicationContext<Context>(), demo = CursorBackend(api, demoStreamer, isDemo = true))
            .also { runBlocking { it.session.enterDemo() } }
    }

    private val AppGraph.user: CursorUser get() = (session.state.value as SessionState.SignedIn).user

    /** The page as a launch some while ago drew it: the demo's Project under its old name, idle, and one since deleted. */
    private fun stalePage(): List<AgentRow> {
        val fixture = NewChatHomeFixtures.list().projectRows
        val project = fixture.first().let { it.copy(agent = it.agent.copy(id = DemoData.PROJECT_ID, name = "Billing (old name)"), indicator = AgentIndicator.Read) }
        val deleted = fixture[1].let { it.copy(agent = it.agent.copy(id = "bc-deleted-project")) }
        return listOf(project, deleted)
    }

    private suspend fun AppGraph.savePage(user: CursorUser, rows: List<AgentRow>) {
        caches.newChatPage.save(NewChatPageSnapshot.of(user, NewChatHomeChoice(null), extendedMode = false, projects = rows))
    }

    @Test
    fun `the last page's Projects are listed until the list's first load, which then replaces them`() = runBlocking<Unit> {
        val graph = graph()
        graph.savePage(graph.user, stalePage())

        val vm = AgentsViewModel(graph)
        val initial = vm.uiState.value
        assertThat(initial.projectRows.map { it.agent.id }).containsExactly(DemoData.PROJECT_ID, "bc-deleted-project").inOrder()
        // A pass of the list's own, not the initial value.
        val beforeLoad = withTimeout(10_000) { vm.uiState.first { it !== initial } }
        assertThat(beforeLoad.hasLoaded).isFalse()
        assertThat(beforeLoad.projectRows.map { it.agent.name }).isEqualTo(stalePage().map { it.agent.name })
        assertThat(beforeLoad.recentRows).isEmpty()

        listGate.complete(Unit)
        val loaded = withTimeout(30_000) { vm.uiState.first { it.hasLoaded && it.projectRows.isNotEmpty() } }
        val project = loaded.projectRows.single()
        assertThat(project.agent.id).isEqualTo(DemoData.PROJECT_ID)
        assertThat(project.agent.name).isEqualTo("Cesium billing launch")
        assertThat(project.children.map { it.agent.id }).containsExactly("bc-demo-0019", "bc-demo-0020", "bc-demo-0021").inOrder()
        assertThat(loaded.projectRows.map { it.agent.id }).doesNotContain("bc-deleted-project")
    }

    @Test
    fun `once the list has loaded the seed is never listed again, even by a view model built later`() = runBlocking<Unit> {
        val graph = graph()
        listGate.complete(Unit)
        val first = AgentsViewModel(graph)
        val loaded = withTimeout(30_000) { first.uiState.first { it.hasLoaded && it.projectRows.isNotEmpty() } }
        graph.savePage(graph.user, loaded.projectRows)

        val rebuilt = AgentsViewModel(graph)
        assertThat(rebuilt.uiState.value.projectRows.map { it.agent.id }).isEqualTo(loaded.projectRows.map { it.agent.id })
        val settled = withTimeout(30_000) { rebuilt.uiState.first { it.hasLoaded } }
        assertThat(settled.projectRows.map { it.agent.id }).isEqualTo(loaded.projectRows.map { it.agent.id })
    }

    @Test
    fun `a Project hidden from the page is seeded with the page's hidden set, which leaves it off`() = runBlocking<Unit> {
        val graph = graph()
        val rows = NewChatHomeFixtures.list().projectRows
        val hidden = setOf(rows.first().agent.id)
        graph.caches.newChatPage.save(NewChatPageSnapshot.of(graph.user, NewChatHomeChoice(null), extendedMode = false, projects = rows, hidden = hidden))

        val seeded = AgentsViewModel(graph).uiState.value
        assertThat(seeded.projectRows.map { it.agent.id }).isEqualTo(rows.map { it.agent.id })
        assertThat(seeded.local.hiddenProjectIds).isEqualTo(hidden)
        assertThat(homeBlocks(NewChatHome.PROJECTS, seeded, projectsAvailable = true)).containsExactly(HomeBlock.Projects(seeded.projectRows, hidden))
        listGate.complete(Unit)
    }

    @Test
    fun `another account's page is not listed`() = runBlocking<Unit> {
        val graph = graph()
        graph.savePage(CursorUser("Other", "someone-else@example.com", null, null, null), stalePage())

        val vm = AgentsViewModel(graph)
        assertThat(vm.uiState.value.projectRows).isEmpty()
        listGate.complete(Unit)
    }
}
