package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.ProjectActionsApi
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.WorkerLaunch
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.SideChatAvailability
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    private class RecordingLineage : ProjectLineageApi, ProjectActionsApi, AgentStoreApi {
        val calls = CopyOnWriteArrayList<String>()
        var workers: Map<String, List<WorkerMembership>> = emptyMap()
        var children: Map<String, List<ComposerSnapshot>> = emptyMap()
        @Volatile var failing: Throwable? = null
        @Volatile var sideChatFailure: Throwable? = null
        var storeId: String? = "st-1"
        var entries: Map<String, List<ContextEntry>> = mapOf("" to listOf(ContextEntry("notes.md", isDirectory = false, sizeBytes = 12L), ContextEntry("docs", isDirectory = true)))

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

        override suspend fun createWorker(managerId: String, launch: WorkerLaunch): ComposerSnapshot {
            calls += "create:$managerId:${launch.prompt}"
            return ComposerSnapshot(launch.workerId, name = launch.name, parent = AgentParent(managerId, AgentParentKind.PROJECT_WORKER))
        }

        override suspend fun setWorkerManager(workerId: String, managerId: String, spawnKind: WorkerSpawnKind) { calls += "adopt:$workerId:$managerId:${spawnKind.name}" }
        override suspend fun clearWorkerManager(workerId: String) { calls += "release:$workerId" }
        override suspend fun reparent(agentId: String, parentId: String, subagentType: String?) { calls += "reparent:$agentId:$parentId" }
        override suspend fun updateAppearance(projectId: String, appearance: ProjectAppearance): ProjectAppearance? {
            calls += "appearance:$projectId:${appearance.icon}:${appearance.colorId}"
            return appearance
        }

        override suspend fun startSideChat(parentId: String, name: String?): ComposerSnapshot {
            calls += "side:$parentId:$name"
            sideChatFailure?.let { throw it }
            return ComposerSnapshot("bc-side", name = name, parent = AgentParent(parentId, AgentParentKind.SIDE_CHAT))
        }

        override suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome {
            calls += "steer:$agentId:$text:$expectedRunId"
            return SteerOutcome.QUEUED
        }

        override suspend fun pause(agentId: String, runId: String?) { calls += "pause:$agentId:$runId" }
        override suspend fun resume(agentId: String) { calls += "resume:$agentId" }
        override suspend fun storeFor(sourceId: String): String? { calls += "store:$sourceId"; return storeId }
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> { calls += "entries:$storeId:$relativePath"; return entries[relativePath].orEmpty() }
        override suspend fun readFile(storeId: String, relativePath: String): String { calls += "read:$storeId:$relativePath"; return "# $relativePath" }
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

    private fun projects(agents: AgentRepository) = ProjectRepository(session, agents, lineage, actions = lineage, store = lineage, scope = scope, pollIntervalMs = 60_000, capabilities = capabilities)

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

    // ---- the coordinator's actions ------------------------------------------------------------------------------------

    @Test
    fun `off, every action is refused by name and nothing is called, and the view says why`() = runBlocking<Unit> {
        api.addIdleAgent("bc-p", "Billing launch", "run-p")
        api.addIdleAgent("bc-w", "Worker", "run-w")
        val agents = agents()
        agents.refresh()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-p", isProject = true), ComposerSnapshot("bc-w", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        val projects = projects(agents)

        val refusals = listOf(
            projects.createWorker("bc-p", WorkerLaunch(prompt = "x")).exceptionOrNull(),
            projects.adopt("bc-p", "bc-x").exceptionOrNull(),
            projects.release("bc-p", "bc-w").exceptionOrNull(),
            projects.reparent("bc-w", "bc-q").exceptionOrNull(),
            projects.updateAppearance("bc-p", ProjectAppearance("flag", "green")).exceptionOrNull(),
            projects.startSideChat("bc-p", null).exceptionOrNull(),
            projects.steer("bc-w", "go").exceptionOrNull(),
            projects.pause("bc-w").exceptionOrNull(),
            projects.resume("bc-w").exceptionOrNull(),
            projects.readContextFile("bc-p", ContextEntry("notes.md", false)).exceptionOrNull(),
        )
        assertThat(refusals.map { it?.message }).doesNotContain(null)
        assertThat(refusals.map { it?.message }.distinct()).containsExactly(ProjectRepository.NEEDS_EXTENDED_MODE, "The Project's context has not been opened.")
        projects.loadContext("bc-p")
        projects.refreshView("bc-p")
        val view = projects.view("bc-p").first { it.hasSynced }
        assertThat(view.context).isEqualTo(ContextState.Unavailable(ProjectRepository.NEEDS_EXTENDED_MODE))
        assertThat(view.lineageNotice).isEqualTo(ProjectRepository.NEEDS_EXTENDED_MODE)
        assertThat(view.actionsAvailable).isFalse()
        assertThat(view.workers.map { it.id }).containsExactly("bc-w")
        assertThat(view.root?.id).isEqualTo("bc-p")
        assertThat(lineage.calls).isEmpty()
        // The worker is still the Project's: the refused release changed nothing.
        assertThat(agents.agent("bc-w")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
    }

    @Test
    fun `on, the actions reach the account and the rows follow them`() = runBlocking<Unit> {
        extended = true
        api.addIdleAgent("bc-p", "Billing launch", "run-p")
        api.addIdleAgent("bc-w", "Worker", "run-w")
        api.addIdleAgent("bc-x", "Plain chat", "run-x")
        api.addIdleAgent("bc-q", "Other Project", "run-q")
        val agents = agents()
        agents.refresh()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-p", isProject = true), ComposerSnapshot("bc-q", isProject = true), ComposerSnapshot("bc-w", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        val projects = projects(agents)

        assertThat(projects.adopt("bc-p", "bc-x").isSuccess).isTrue()
        assertThat(agents.agent("bc-x")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        assertThat(projects.release("bc-p", "bc-x").isSuccess).isTrue()
        assertThat(agents.agent("bc-x")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.agent("bc-x")?.parent).isNull()
        assertThat(projects.reparent("bc-w", "bc-q").isSuccess).isTrue()
        assertThat(agents.agent("bc-w")?.parent).isEqualTo(AgentParent("bc-q", AgentParentKind.SUBAGENT))
        assertThat(projects.updateAppearance("bc-p", ProjectAppearance("flag", "green")).isSuccess).isTrue()
        assertThat(agents.agent("bc-p")?.projectAppearance).isEqualTo(ProjectAppearance("flag", "green"))
        assertThat(projects.steer("bc-w", "Use v2").getOrNull()).isEqualTo(SteerOutcome.QUEUED)
        assertThat(projects.pause("bc-w").isSuccess).isTrue()
        assertThat(projects.resume("bc-w").isSuccess).isTrue()
        // A new primary: the account's record joins the list as the Project's, fetched by id.
        api.addIdleAgent("bc-new", "New worker", "run-new")
        val created = projects.createWorker("bc-p", WorkerLaunch(prompt = "Build it", name = "New worker", workerId = "bc-new"))
        assertThat(created.getOrNull()).isEqualTo("bc-new")
        assertThat(agents.agent("bc-new")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-new")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)

        assertThat(lineage.calls).containsAtLeast(
            "adopt:bc-x:bc-p:ADOPTED", "release:bc-x", "reparent:bc-w:bc-q", "appearance:bc-p:flag:green",
            "steer:bc-w:Use v2:run-w", "pause:bc-w:run-w", "resume:bc-w", "create:bc-p:Build it",
        ).inOrder()
    }

    @Test
    fun `a side chat is probed by the attempt, and a refusal that reads as not offered becomes the named state`() = runBlocking<Unit> {
        extended = true
        api.addIdleAgent("bc-p", "Billing launch", "run-p")
        val agents = agents()
        agents.refresh()
        val projects = projects(agents)
        lineage.sideChatFailure = ConnectRpcException(400, "failed_precondition", "Side chats are not enabled for cloud agents")

        val refused = projects.startSideChat("bc-p", "Pricing")
        assertThat(refused.isFailure).isTrue()
        assertThat(projects.view("bc-p").first().sideChatAvailability).isEqualTo(SideChatAvailability.COMING_TO_CURSOR)

        // A different failure is an error, not the named state.
        lineage.sideChatFailure = ConnectRpcException(400, "invalid_argument", "name too long")
        projects.startSideChat("bc-p", "x".repeat(200))
        assertThat(projects.view("bc-p").first().sideChatAvailability).isEqualTo(SideChatAvailability.COMING_TO_CURSOR)

        // The day it answers, the side chat is the Project's and the state says so.
        lineage.sideChatFailure = null
        api.addIdleAgent("bc-side", "Pricing", "run-side")
        assertThat(projects.startSideChat("bc-p", "Pricing").getOrNull()).isEqualTo("bc-side")
        assertThat(projects.view("bc-p").first().sideChatAvailability).isEqualTo(SideChatAvailability.AVAILABLE)
        assertThat(agents.agent("bc-side")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.SIDE_CHAT))
    }

    @Test
    fun `on, the context is the coordinator's store, opened folder by folder, and its absence is a named state`() = runBlocking<Unit> {
        extended = true
        api.addIdleAgent("bc-p", "Billing launch", "run-p")
        val agents = agents()
        agents.refresh()
        val projects = projects(agents)
        lineage.entries = lineage.entries + ("docs" to listOf(ContextEntry("docs/plan.md", isDirectory = false, sizeBytes = 3L)))

        projects.loadContext("bc-p")
        val root = projects.view("bc-p").first().context as ContextState.Loaded
        assertThat(root.context.storeId).isEqualTo("st-1")
        assertThat(root.context.entries.map { it.relativePath }).containsExactly("notes.md", "docs")
        projects.loadContext("bc-p", "docs")
        val docs = projects.view("bc-p").first().context as ContextState.Loaded
        assertThat(docs.context.relativePath).isEqualTo("docs")
        assertThat(docs.context.entries.single().name).isEqualTo("plan.md")
        assertThat(projects.readContextFile("bc-p", docs.context.entries.single()).getOrNull()).isEqualTo("# docs/plan.md")
        // The store is looked up once; the folders are read as they are opened.
        assertThat(lineage.calls.count { it.startsWith("store:") }).isEqualTo(1)

        lineage.storeId = null
        projects.reset()
        projects.loadContext("bc-p")
        assertThat(projects.view("bc-p").first().context).isEqualTo(ContextState.NoStore)
    }

    @Test
    fun `the demo shows its Project from memory and says its actions are not available`() = runBlocking<Unit> {
        val demo = CursorBackend(isDemo = true, parts = lazy { DemoBackendFactory.create() })
        session = SessionManager(SecureKeyStore(context), prefs, CursorBackend(api, FakeRunStreamer(), isDemo = false), demo, capabilities = capabilities)
        session.enterDemo()
        val agents = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, demoSources = DemoData.sources, demoComposers = DemoData.composers, capabilities = capabilities)
        agents.refresh()
        val projects = projects(agents)

        projects.refreshView(DemoData.PROJECT_ID)
        val view = projects.view(DemoData.PROJECT_ID).first { it.hasSynced }
        assertThat(view.root?.name).isEqualTo("Cesium billing launch")
        assertThat(view.workers.map { it.agent.name }).containsExactly("Usage events aggregation", "Stripe webhook handler").inOrder()
        assertThat(view.sideChats.map { it.name }).containsExactly("Pricing page copy")
        assertThat(view.actionsAvailable).isTrue()
        assertThat(view.context).isEqualTo(ContextState.NoStore)
        assertThat(projects.createWorker(view.projectId, WorkerLaunch(prompt = "x")).exceptionOrNull()).hasMessageThat().isEqualTo(ProjectRepository.NOT_IN_DEMO)
        assertThat(lineage.calls).isEmpty()
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
