package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Who belongs to whom, under both settings: with Extended mode off the account is never asked and the rows keep the
 * classification they have; with it on, each Project's memberships are read and folded onto the rows. In either mode
 * a parent the list names but lacks is fetched through the public API.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectRepositoryTest {

    private class RecordingLineage : ProjectLineageApi {
        val calls = CopyOnWriteArrayList<String>()
        var workers: Map<String, List<WorkerMembership>> = emptyMap()
        var children: Map<String, List<ComposerSnapshot>> = emptyMap()
        @Volatile var failing: Throwable? = null

        override suspend fun workersForManager(managerId: String): List<WorkerMembership> {
            calls += "workers:$managerId"
            failing?.let { throw it }
            return workers[managerId].orEmpty()
        }

        override suspend fun children(parentId: String): List<ComposerSnapshot> {
            calls += "children:$parentId"
            failing?.let { throw it }
            return children[parentId].orEmpty()
        }
    }

    private val api = FakeCursorApi()
    private val lineage = RecordingLineage()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private var extended = false
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, capabilities = capabilities)

    private fun projects(agents: AgentRepository) = ProjectRepository(session, agents, lineage, scope, capabilities = capabilities)

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    @Test
    fun `off, the account is never asked and the rows keep the scope they already have`() = runBlocking<Unit> {
        api.addIdleAgent("bc-p", "Billing launch", "run-p")
        api.addIdleAgent("bc-w", "Webhook worker", "run-w")
        api.addIdleAgent("bc-x", "Plain chat", "run-x")
        val agents = agents()
        agents.refresh()
        // Classified during an earlier Extended session, and kept on the rows since.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-p", isProject = true), ComposerSnapshot("bc-w", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER), source = AgentSource.AS_SUBAGENT_FROM_CLOUD)))
        lineage.workers = mapOf("bc-p" to listOf(WorkerMembership("bc-x", "bc-p", WorkerSpawnKind.ADOPTED)))

        val projects = projects(agents)
        projects.syncLineage(listOf("bc-p"))
        projects.scheduleLineageSync(listOf("bc-p"))
        delay(100)

        assertThat(lineage.calls).isEmpty()
        assertThat(agents.agent("bc-x")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.agent("bc-w")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(agents.agent("bc-p")?.scope).isEqualTo(AgentScope.PROJECT_ROOT)
        // Turning the mode off wipes the sources the account gave, not where the rows belong.
        agents.forgetAccountSources(emptySet())
        assertThat(agents.agent("bc-w")?.source).isNull()
        assertThat(agents.agent("bc-w")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(agents.agent("bc-w")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
    }

    @Test
    fun `on, each Project's memberships are read and its workers and children become its own`() = runBlocking<Unit> {
        extended = true
        api.addIdleAgent("bc-p", "Billing launch", "run-p")
        api.addIdleAgent("bc-w", "Webhook worker", "run-w")
        api.addIdleAgent("bc-a", "Adopted chat", "run-a")
        api.addIdleAgent("bc-s", "Pricing side chat", "run-s")
        api.addIdleAgent("bc-x", "Plain chat", "run-x")
        val agents = agents()
        agents.refresh()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-p", isProject = true)))
        lineage.workers = mapOf("bc-p" to listOf(WorkerMembership("bc-w", "bc-p", WorkerSpawnKind.CREATED), WorkerMembership("bc-a", "bc-p", WorkerSpawnKind.ADOPTED)))
        lineage.children = mapOf("bc-p" to listOf(ComposerSnapshot("bc-s", parent = AgentParent("bc-p", AgentParentKind.SIDE_CHAT))))

        projects(agents).syncLineage(listOf("bc-p", "bc-p"))

        assertThat(lineage.calls).containsExactly("workers:bc-p", "children:bc-p").inOrder()
        assertThat(agents.agent("bc-w")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-a")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-s")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.SIDE_CHAT))
        assertThat(agents.agent("bc-s")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(agents.agent("bc-x")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.agent("bc-p")?.scope).isEqualTo(AgentScope.PROJECT_ROOT)
        // A refresh brings the same rows back with the same place.
        agents.refresh()
        assertThat(agents.agent("bc-a")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
    }

    @Test
    fun `on, a root whose reads fail is skipped and the others still land`() = runBlocking<Unit> {
        extended = true
        api.addIdleAgent("bc-p", "One", "run-p")
        api.addIdleAgent("bc-q", "Two", "run-q")
        api.addIdleAgent("bc-w", "Worker", "run-w")
        val agents = agents()
        agents.refresh()
        lineage.workers = mapOf("bc-q" to listOf(WorkerMembership("bc-w", "bc-q")))
        lineage.failing = IllegalStateException("down")

        val projects = projects(agents)
        projects.syncLineage(listOf("bc-p", "bc-q"))
        assertThat(agents.agent("bc-w")?.scope).isEqualTo(AgentScope.PRIMARY)

        lineage.failing = null
        projects.syncLineage(listOf("bc-p", "bc-q"))
        assertThat(agents.agent("bc-w")?.parent).isEqualTo(AgentParent("bc-q", AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-q")?.scope).isEqualTo(AgentScope.PROJECT_ROOT)
    }

    @Test
    fun `a parent the list names but lacks is fetched by id in either mode, and a manager of workers is a Project`() = runBlocking<Unit> {
        // The Project sits beyond the listing window: only its worker made it into the list.
        api.addIdleAgent("bc-w", "Webhook worker", "run-w")
        val agents = agents()
        agents.refresh()
        agents.applyLineage("bc-far", mapOf("bc-w" to AgentParentKind.PROJECT_WORKER), authoritative = true)
        assertThat(agents.agent("bc-far")).isNull()
        api.addIdleAgent("bc-far", "Far Project", "run-far")

        val projects = projects(agents)
        projects.watchList()
        awaitUntil { agents.agent("bc-far") != null }

        assertThat(lineage.calls).isEmpty()
        assertThat(agents.agent("bc-far")?.name).isEqualTo("Far Project")
        assertThat(agents.agent("bc-far")?.scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(agents.agent("bc-w")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
    }

    @Test
    fun `a parent that cannot be fetched is asked for once per pass, not on every list change`() = runBlocking<Unit> {
        api.addIdleAgent("bc-w", "Worker", "run-w")
        val agents = agents()
        agents.refresh()
        agents.applyLineage("bc-gone", mapOf("bc-w" to AgentParentKind.PROJECT_WORKER), authoritative = true)
        api.failGetAgent = IllegalStateException("gone")
        val projects = projects(agents)
        val before = api.getAgentCalls

        projects.materializeParents(listOf("bc-gone"))
        projects.materializeParents(listOf("bc-gone"))

        assertThat(api.getAgentCalls).isEqualTo(before + 1)
        assertThat(agents.agent("bc-w")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
    }
}
