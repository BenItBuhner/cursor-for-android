package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
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
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The leak as it showed on a real account in 0.2.0, Extended mode: seven Projects in the Projects group, their
 * workers nested under them — and other workers of the same Projects sitting in Today as primary rows, notifying.
 *
 * What did it: the account list is read in a window of 200 records, archived ones included, and every record it
 * returned replaced the row's lineage wholesale — a worker's record without a `managerAgentId` (an adopted chat's
 * never carries one; the field is the record's, the membership is the Project's) put the worker back among the
 * primary rows over the membership that had placed it, on every list round. Roots outside the window were never
 * asked for their memberships at all, and one failing read lost the other's answer. Each of those is a case here,
 * on the repository the list is built in, so the rule cannot regress quietly again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectLeakRegressionTest {

    private class RecordingLineage : ProjectLineageApi {
        val calls = CopyOnWriteArrayList<String>()
        var workers: Map<String, List<WorkerMembership>> = emptyMap()
        var children: Map<String, List<ComposerSnapshot>> = emptyMap()
        var childrenFailure: Throwable? = null

        override suspend fun workersForManager(managerId: String): List<WorkerMembership> {
            calls += "workers:$managerId"
            return workers[managerId].orEmpty()
        }

        override suspend fun children(parentId: String): List<ComposerSnapshot> {
            calls += "children:$parentId"
            childrenFailure?.let { throw it }
            return children[parentId].orEmpty()
        }
    }

    private val api = FakeCursorApi()
    private val lineage = RecordingLineage()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private val capabilities: suspend () -> Capabilities = { Capabilities.EXTENDED }

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

    private fun projects(agents: AgentRepository) = ProjectRepository(session, agents, lineage, scope = scope, pollIntervalMs = 60_000, capabilities = capabilities)

    private fun primaryIds(agents: AgentRepository): List<String> =
        AgentListOrganizer.organize(agents.state.value.agents, ListPreferences(), LocalAgentState(), nowMillis = 1_800_000_000_000L, zone = ZoneOffset.UTC)
            .flatMap { it.rows }.filterNot { it.agent.isProjectRoot }.map { it.agent.id }

    /** The account as it showed: a Project, two workers the account created, one it adopted, a side chat, a chat of its own. */
    private suspend fun seed(): AgentRepository {
        api.addIdleAgent("bc-p", "Cursor for Android", "run-p")
        api.addRunningAgent("bc-w1", "New chat creation issue", "run-w1")
        api.addRunningAgent("bc-w2", "User interface polish", "run-w2")
        api.addRunningAgent("bc-a", "Model picker stability", "run-a")
        api.addRunningAgent("bc-s", "Pricing copy", "run-s")
        api.addRunningAgent("bc-x", "Cesium", "run-x")
        return agents().also { it.refresh() }
    }

    @Test
    fun `a record without lineage never puts a placed worker back among the primary rows`() = runBlocking<Unit> {
        val agents = seed()
        // The memberships placed every worker, the adopted one included.
        agents.applyLineage("bc-p", mapOf("bc-w1" to AgentParentKind.PROJECT_WORKER, "bc-w2" to AgentParentKind.PROJECT_WORKER, "bc-a" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        assertThat(primaryIds(agents)).containsExactly("bc-s", "bc-x")

        // The next list round: the window holds the root's record, the created workers' records with their manager,
        // and the adopted chat's record — which never carried one. In 0.2.0 this read made bc-a a primary row again.
        agents.applyAccountSnapshots(
            listOf(
                ComposerSnapshot("bc-p", isProject = true),
                ComposerSnapshot("bc-w1", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)),
                ComposerSnapshot("bc-a"),
                ComposerSnapshot("bc-x"),
            ),
        )
        assertThat(agents.agent("bc-a")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(agents.agent("bc-a")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-a")?.scopeSignal).isEqualTo(LineageSignal.MEMBERSHIP)
        assertThat(agents.agent("bc-w1")?.scopeSignal).isEqualTo(LineageSignal.ACCOUNT_RECORD)
        assertThat(agents.agent("bc-w2")?.scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(agents.agent("bc-x")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(primaryIds(agents)).containsExactly("bc-s", "bc-x")
        // And again, and again: the rounds repeat every minute.
        repeat(3) { agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-a"), ComposerSnapshot("bc-w2"))) }
        assertThat(primaryIds(agents)).containsExactly("bc-s", "bc-x")
    }

    @Test
    fun `the record's own word, withdrawn, releases the chat - a membership's word does not go with it`() = runBlocking<Unit> {
        val agents = seed()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w1", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        assertThat(agents.agent("bc-w1")?.isProjectChild).isTrue()
        // The record no longer names a manager: the chat was released on another client.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w1")))
        assertThat(agents.agent("bc-w1")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.agent("bc-w1")?.parent).isNull()
        // A membership placed bc-w2; its record staying silent says nothing about that.
        agents.applyLineage("bc-p", mapOf("bc-w2" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w2")))
        assertThat(agents.agent("bc-w2")?.isProjectChild).isTrue()
    }

    @Test
    fun `a complete membership answer releases the workers it no longer names, and only those`() = runBlocking<Unit> {
        val agents = seed()
        agents.applyLineage("bc-p", mapOf("bc-w1" to AgentParentKind.PROJECT_WORKER, "bc-w2" to AgentParentKind.PROJECT_WORKER, "bc-s" to AgentParentKind.SIDE_CHAT), LineageSignal.MEMBERSHIP)
        // bc-a's own record names the root; a membership answer that omits it does not override the record.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-a", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        // The next `ListWorkersForManager` names bc-w1 alone: bc-w2 was released; the side chat is not a worker and stays.
        agents.applyLineage("bc-p", mapOf("bc-w1" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP, retract = setOf(AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-w1")?.isProjectChild).isTrue()
        assertThat(agents.agent("bc-w2")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.agent("bc-w2")?.parent).isNull()
        assertThat(agents.agent("bc-s")?.isProjectChild).isTrue()
        assertThat(agents.agent("bc-a")?.isProjectChild).isTrue()
        // Without the retraction (a read that did not cover the kind) nothing is released.
        agents.applyLineage("bc-p", emptyMap(), LineageSignal.MEMBERSHIP)
        assertThat(agents.agent("bc-w1")?.isProjectChild).isTrue()
    }

    @Test
    fun `a coordinator's transcript places what nothing authoritative has, and yields to what has`() = runBlocking<Unit> {
        val agents = seed()
        agents.applyLineage("bc-p", mapOf("bc-w1" to AgentParentKind.PROJECT_WORKER, "bc-x" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-p")?.scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(agents.agent("bc-w1")?.scopeSignal).isEqualTo(LineageSignal.COORDINATOR_TRANSCRIPT)
        assertThat(agents.agent("bc-x")?.isProjectChild).isTrue()
        // The account then says bc-x is a Project of its own under a different parent: its word stands over the hint.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-x", parent = AgentParent("bc-w2", AgentParentKind.SUBAGENT))))
        assertThat(agents.agent("bc-x")?.parent).isEqualTo(AgentParent("bc-w2", AgentParentKind.SUBAGENT))
        // A hint never lifts what the account placed.
        agents.applyLineage("bc-p", mapOf("bc-x" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-x")?.parent).isEqualTo(AgentParent("bc-w2", AgentParentKind.SUBAGENT))
        // Nor does a root's word lift a Project that is itself somebody's child (the Agents Window's rule).
        agents.applyLineage("bc-w2", mapOf("bc-x" to AgentParentKind.SUBAGENT), LineageSignal.MEMBERSHIP)
        agents.applyLineage("bc-x", emptyMap(), LineageSignal.MEMBERSHIP)
        assertThat(agents.agent("bc-x")?.isProjectChild).isTrue()
    }

    @Test
    fun `a row the public list brings after the word about it, or brings again, is placed on arrival`() = runBlocking<Unit> {
        api.addIdleAgent("bc-p", "Cursor for Android", "run-p")
        api.addRunningAgent("bc-x", "Cesium", "run-x")
        val agents = agents()
        agents.refresh()
        // The memberships name a worker the public list has not shown yet (created moments ago, or beyond its window).
        agents.applyLineage("bc-p", mapOf("bc-w1" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        api.addRunningAgent("bc-w1", "New chat creation issue", "run-w1")
        agents.refresh()
        assertThat(agents.agent("bc-w1")?.isProjectChild).isTrue()
        assertThat(agents.agent("bc-w1")?.scopeSignal).isEqualTo(LineageSignal.MEMBERSHIP)
        assertThat(primaryIds(agents)).containsExactly("bc-x")
        // A row deleted from the list and listed again keeps its place: the registry remembers, not just the row.
        agents.upsert(agents.agent("bc-w1")!!.copy(parent = null, knownScope = null, scopeSignal = null))
        assertThat(agents.agent("bc-w1")?.isProjectChild).isTrue()
    }

    @Test
    fun `every root the list knows is asked for its memberships, not only the ones inside the account window`() = runBlocking<Unit> {
        val agents = seed()
        // bc-p's own record is outside the account window (its last activity is older than two hundred other chats'):
        // the list learned it is a root only from a worker's record naming it, or from an earlier session.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w1", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        agents.applyLineage("bc-p", emptyMap(), LineageSignal.MEMBERSHIP)
        lineage.workers = mapOf("bc-p" to listOf(WorkerMembership("bc-w1", "bc-p", WorkerSpawnKind.CREATED), WorkerMembership("bc-w2", "bc-p", WorkerSpawnKind.CREATED), WorkerMembership("bc-a", "bc-p", WorkerSpawnKind.ADOPTED)))
        lineage.children = mapOf("bc-p" to listOf(ComposerSnapshot("bc-s", parent = AgentParent("bc-p", AgentParentKind.SIDE_CHAT), source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD)))

        // The pin sync's round found no root inside the window; the pass still reads the root the list knows.
        projects(agents).syncLineage(emptyList())

        assertThat(lineage.calls).containsExactly("workers:bc-p", "children:bc-p").inOrder()
        assertThat(primaryIds(agents)).containsExactly("bc-x")
        assertThat(agents.agent("bc-a")?.scopeSignal).isEqualTo(LineageSignal.MEMBERSHIP)
        assertThat(agents.agent("bc-s")?.scopeSignal).isEqualTo(LineageSignal.CHILDREN_LIST)
    }

    @Test
    fun `one read refused does not lose the other's answer`() = runBlocking<Unit> {
        val agents = seed()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-p", isProject = true)))
        lineage.workers = mapOf("bc-p" to listOf(WorkerMembership("bc-w1", "bc-p"), WorkerMembership("bc-w2", "bc-p"), WorkerMembership("bc-a", "bc-p")))
        lineage.childrenFailure = ConnectRpcException(501, "unimplemented", "not offered")
        val projects = projects(agents)

        projects.syncLineage(listOf("bc-p"))

        assertThat(primaryIds(agents)).containsExactly("bc-s", "bc-x")
        val record = projects.syncRecords().getValue("bc-p")
        assertThat(record.workersRead).isTrue()
        assertThat(record.childrenRead).isFalse()
        assertThat(record.workerCount).isEqualTo(3)
        assertThat(record.notice).isNotNull()
    }

    /**
     * The list is paged on demand now, so a Project's workers and its coordinator can arrive pages apart. A worker on
     * the first page whose record names a coordinator the list does not hold yet is placed under it at once, the
     * coordinator is fetched by id to head the tree, and when the page that carries the coordinator (and more workers)
     * lands, the placement pass runs over it too: nothing lifts the worker out, and the newcomers take their places.
     */
    @Test
    fun `a Project's chats stay placed as the list pages, whichever page each arrives on`() = runBlocking<Unit> {
        // Newest first: the workers on the first page of two, the coordinator and a third worker on the second.
        api.addRunningAgent("bc-w1", "Worker one", "run-w1", createdAt = "2026-04-14T10:00:00.000Z")
        api.addRunningAgent("bc-x", "A chat of its own", "run-x", createdAt = "2026-04-14T09:00:00.000Z")
        api.addRunningAgent("bc-w2", "Worker two", "run-w2", createdAt = "2026-04-13T10:00:00.000Z")
        api.addIdleAgent("bc-p", "Cursor for Android", "run-p", createdAt = "2026-04-12T10:00:00.000Z")
        api.pageSize = 2
        val agents = agents()
        val projects = projects(agents)
        projects.watchList()

        agents.refresh()
        // One page of two; the second worker, running beyond it, is fetched by the running scan all the same.
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-w1", "bc-x", "bc-w2")
        assertThat(agents.state.value.hasMore).isTrue()
        // The account's first page says whose the worker is; the coordinator is beyond the public list's first page.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w1", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        assertThat(primaryIds(agents)).containsExactly("bc-x", "bc-w2")
        assertThat(AgentListOrganizer.missingParentIds(agents.state.value.agents)).containsExactly("bc-p")
        // Fetched by id so it can head its tree, and marked a coordinator by the worker that names it.
        withTimeout(5_000) { while (agents.agent("bc-p") == null) delay(10) }
        assertThat(agents.agent("bc-p")!!.isProjectRoot).isTrue()
        assertThat(primaryIds(agents)).containsExactly("bc-x", "bc-w2")

        // The reader pages on: the coordinator's own row lands with the second worker's, and the account's next page
        // says whose the second worker is.
        assertThat(agents.loadMore()).isEqualTo(RefreshOutcome.Refreshed)
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-w1", "bc-x", "bc-w2", "bc-p")
        assertThat(agents.state.value.hasMore).isFalse()
        assertThat(agents.agent("bc-p")!!.isProjectRoot).isTrue()
        assertThat(agents.agent("bc-w1")!!.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-p", isProject = true), ComposerSnapshot("bc-w2", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        assertThat(primaryIds(agents)).containsExactly("bc-x")
        assertThat(agents.agent("bc-w2")!!.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))

        // A refresh re-reads both pages; the rows come back placed as before.
        agents.refresh()
        assertThat(primaryIds(agents)).containsExactly("bc-x")
        assertThat(agents.agent("bc-w1")!!.parent?.id).isEqualTo("bc-p")
        assertThat(agents.agent("bc-w2")!!.parent?.id).isEqualTo("bc-p")
        assertThat(agents.agent("bc-p")!!.isProjectRoot).isTrue()
    }

    @Test
    fun `a released membership places nothing, and takes a placed worker back among the account's chats`() = runBlocking<Unit> {
        val agents = seed()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-p", isProject = true)))
        // The account keeps a row for a worker it released, marked so: that row is evidence the chat is its own.
        lineage.workers = mapOf(
            "bc-p" to listOf(
                WorkerMembership("bc-w1", "bc-p", WorkerSpawnKind.CREATED, status = "MANAGER_WORKER_MEMBERSHIP_STATUS_ACTIVE"),
                WorkerMembership("bc-a", "bc-p", WorkerSpawnKind.ADOPTED, status = "MANAGER_WORKER_MEMBERSHIP_STATUS_RELEASED"),
                WorkerMembership("bc-w2", "bc-p", WorkerSpawnKind.CREATED, status = "removed"),
            ),
        )
        val projects = projects(agents)
        projects.syncLineage(listOf("bc-p"))
        assertThat(agents.agent("bc-w1")?.isProjectChild).isTrue()
        assertThat(agents.agent("bc-a")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.agent("bc-w2")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(WorkerMembership("x", "y", status = null).isActive).isTrue()
        assertThat(WorkerMembership("x", "y", status = "MANAGER_WORKER_MEMBERSHIP_STATUS_UNSPECIFIED").isActive).isTrue()
        // A worker placed earlier whose membership the account now marks released is released here too.
        agents.applyLineage("bc-p", mapOf("bc-a" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        assertThat(agents.agent("bc-a")?.isProjectChild).isTrue()
        projects.syncLineage(listOf("bc-p"))
        assertThat(agents.agent("bc-a")?.scope).isEqualTo(AgentScope.PRIMARY)
    }

    @Test
    fun `a coordinator's transcript never places a chat on the user's machine, and its record puts back a chat it did place`() = runBlocking<Unit> {
        api.addIdleAgent("bc-p", "Cursor for Android", "run-p")
        api.addRunningAgent("bc-x", "Cesium", "run-x")
        val at = "2026-04-13T18:30:00.000Z"
        api.agents["bc-m"] = com.cursorforandroid.data.api.dto.AgentDto(id = "bc-m", name = "Codex-Poly-Bot Scaling", status = "ACTIVE", env = com.cursorforandroid.data.api.dto.AgentEnvDto(type = "machine", name = "poly"), createdAt = at, updatedAt = at, latestRunId = "run-m")
        api.runs["run-m"] = com.cursorforandroid.data.api.dto.RunDto(id = "run-m", agentId = "bc-m", status = "RUNNING", createdAt = at, updatedAt = at)
        val agents = agents()
        agents.refresh()
        // The coordinator messaged both: the transcript names them as if they were its workers.
        agents.applyLineage("bc-p", mapOf("bc-m" to AgentParentKind.PROJECT_WORKER, "bc-x" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-m")?.isProjectScoped).isFalse()
        assertThat(agents.agent("bc-x")?.isProjectScoped).isTrue()
        assertThat(agents.agent("bc-x")?.isProjectScopedByEvidence).isFalse()
        assertThat(primaryIds(agents)).containsExactly("bc-m")
        // The account's record of bc-x names nothing: the hint is withdrawn and refused; positive evidence still places.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-x"), ComposerSnapshot("bc-m")))
        assertThat(agents.agent("bc-x")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(primaryIds(agents)).containsExactly("bc-m", "bc-x")
        agents.applyLineage("bc-p", mapOf("bc-x" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-x")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.hintRefusedIds()).containsExactly("bc-x")
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-x", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        assertThat(agents.agent("bc-x")?.isProjectScopedByEvidence).isTrue()
        // Even the account's membership does not make the machine chat a worker on a hint; its own membership would.
        agents.applyLineage("bc-p", mapOf("bc-m" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        assertThat(agents.agent("bc-m")?.isProjectChild).isTrue()
    }

    @Test
    fun `the account's word about a chat no page holds yet places the row the page brings later`() = runBlocking<Unit> {
        api.addIdleAgent("bc-p", "Cursor for Android", "run-p")
        api.addRunningAgent("bc-w1", "New chat creation issue", "run-w1", createdAt = "2026-04-12T18:30:00.000Z")
        api.addRunningAgent("bc-s", "Pricing copy", "run-s", createdAt = "2026-04-11T18:30:00.000Z")
        api.pageSize = 1
        val agents = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, capabilities = capabilities, runningScanPages = 1)
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-p")
        // The account list's window is wider than the page: it names the worker and the side chat the page has not brought.
        agents.applyAccountSnapshots(
            listOf(
                ComposerSnapshot("bc-p", isProject = true),
                ComposerSnapshot("bc-w1", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)),
                ComposerSnapshot("bc-s", source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD),
            ),
        )
        agents.loadMore()
        agents.loadMore()
        assertThat(agents.agent("bc-w1")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-w1")?.scopeSignal).isEqualTo(LineageSignal.ACCOUNT_RECORD)
        assertThat(agents.agent("bc-s")?.source).isEqualTo(AgentSource.AS_SIDE_CHAT_FROM_CLOUD)
        assertThat(agents.agent("bc-s")?.isProjectChild).isTrue()
        assertThat(primaryIds(agents)).isEmpty()
    }

    @Test
    fun `the scope reads the row's facts first - a parent link or a child's source is never outvoted`() {
        val base = com.cursorforandroid.domain.Agent(
            id = "bc-w", name = "Worker", lifecycle = com.cursorforandroid.domain.AgentLifecycle.ACTIVE, runStatus = null,
            envType = com.cursorforandroid.domain.EnvType.CLOUD, envName = null, url = "", createdAtMillis = 0, updatedAtMillis = 0, latestRunId = null, repoUrl = null, startingRef = null,
        )
        assertThat(base.copy(parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER), knownScope = AgentScope.PRIMARY).scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(base.copy(source = AgentSource.AS_SUBAGENT_FROM_CLOUD, knownScope = AgentScope.PRIMARY).scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(base.copy(isProject = true, knownScope = AgentScope.PROJECT_CHILD).scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(base.copy(isProject = true).scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(base.copy(knownScope = AgentScope.PROJECT_ROOT).scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(base.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(base.copy(knownScope = AgentScope.PROJECT_CHILD).isProjectScoped).isTrue()
    }
}
