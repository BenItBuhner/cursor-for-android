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
import com.cursorforandroid.domain.Agent
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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

    /** The account's records by id, for the rows fetched by id (see `AgentRepository.loadDetail`): the desktop's list comes with them. */
    private val records = java.util.concurrent.ConcurrentHashMap<String, ComposerSnapshot>()

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, capabilities = capabilities, recordOf = { records[it] })

    private fun projects(agents: AgentRepository) = ProjectRepository(session, agents, lineage, scope = scope, pollIntervalMs = 60_000, capabilities = capabilities)

    private fun primaryIds(agents: AgentRepository): List<String> = primaryIds(agents.state.value.agents)

    private fun primaryIds(rows: List<Agent>): List<String> =
        AgentListOrganizer.organize(rows, ListPreferences(), LocalAgentState(), nowMillis = 1_800_000_000_000L, zone = ZoneOffset.UTC)
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
        // The memberships placed every worker, the adopted one included; bc-p, not flagged yet, is a top-level row
        // with the three nested under it (the desktop's row-with-children), the side chat and the plain chat beside it.
        agents.applyLineage("bc-p", mapOf("bc-w1" to AgentParentKind.PROJECT_WORKER, "bc-w2" to AgentParentKind.PROJECT_WORKER, "bc-a" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        assertThat(primaryIds(agents)).containsExactly("bc-p", "bc-s", "bc-x")

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
    fun `a coordinator's create_agent places what no record has, and yields to a record`() = runBlocking<Unit> {
        val agents = seed()
        agents.applyLineage("bc-p", mapOf("bc-w1" to AgentParentKind.PROJECT_WORKER, "bc-x" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        // The transcript nests the workers it created; it makes no Project of the chat that created them (that takes
        // the record's flag), so bc-p stays a chat of the account's own with two chats under it.
        assertThat(agents.agent("bc-p")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.knownRoots.value).isEmpty()
        assertThat(agents.agent("bc-w1")?.scopeSignal).isEqualTo(LineageSignal.COORDINATOR_CREATED)
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
        // bc-p is no Project by its record: a top-level row with the worker nested under it.
        assertThat(primaryIds(agents)).containsExactly("bc-p", "bc-x")
        // A row deleted from the list and listed again keeps its place: the registry remembers, not just the row.
        agents.upsert(agents.agent("bc-w1")!!.copy(parent = null, scopeSignal = null))
        assertThat(agents.agent("bc-w1")?.isProjectChild).isTrue()
    }

    @Test
    fun `every root the list knows is asked for its memberships, not only the ones inside the account window`() = runBlocking<Unit> {
        val agents = seed()
        // bc-p's own record is outside the account window (its last activity is older than two hundred other chats'):
        // the discovery pass over the whole list read it, and a worker's record in the window names it.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w1", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)), ComposerSnapshot("bc-p", isProject = true, record = com.cursorforandroid.data.api.RecordFields(projectMetadata = "{}"))))
        lineage.workers = mapOf("bc-p" to listOf(WorkerMembership("bc-w1", "bc-p", WorkerSpawnKind.CREATED), WorkerMembership("bc-w2", "bc-p", WorkerSpawnKind.CREATED), WorkerMembership("bc-a", "bc-p", WorkerSpawnKind.ADOPTED)))
        lineage.children = mapOf("bc-p" to listOf(ComposerSnapshot("bc-s", parent = AgentParent("bc-p", AgentParentKind.SIDE_CHAT), source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD)))

        // The pin sync's round found no root inside the window; the pass still reads the root the list knows.
        projects(agents).syncLineage(emptyList())

        assertThat(lineage.calls).containsExactly("workers:bc-p", "children:bc-p").inOrder()
        assertThat(primaryIds(agents)).containsExactly("bc-x")
        assertThat(agents.agent("bc-a")?.scopeSignal).isEqualTo(LineageSignal.MEMBERSHIP)
        // The children answer carries the side chat's own record, which names its parent: the record's word.
        assertThat(agents.agent("bc-s")?.scopeSignal).isEqualTo(LineageSignal.ACCOUNT_RECORD)
        assertThat(agents.agent("bc-s")?.record?.sideChatParentId).isEqualTo("bc-p")
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
        // The account's first page says whose the worker is; the coordinator is beyond the public list's first page,
        // and the watcher fetches it by id the moment a row names it — so its absence from the list is momentary,
        // and no assertion here depends on catching it. What holds at every publication, before and after the
        // fetch lands, is the ordering: the worker is never a primary row, and the coordinator's row is placed as a
        // root in the publication that brings it, never landing as a chat of its own first.
        val published = java.util.concurrent.CopyOnWriteArrayList<List<Agent>>()
        // Started undispatched, so the subscription is made before anything else runs: the state flow's replay of the
        // current list — the one before the record below, the worker still a chat of its own — is what is dropped,
        // and every publication after it reaches the collector in order. A collector racing the record on another
        // thread could see that earlier list, or miss the one that brings the coordinator.
        val watching = launch(start = CoroutineStart.UNDISPATCHED) { agents.state.drop(1).collect { published += it.agents } }
        // The worker's record names bc-p; the watcher fetches bc-p by id, and the row lands with its account record
        // — the flag that makes it a root — in the one publication, as a row of the desktop's list would.
        records["bc-p"] = ComposerSnapshot("bc-p", isProject = true, record = com.cursorforandroid.data.api.RecordFields(projectMetadata = "{}"))
        lineage.workers = mapOf("bc-p" to listOf(WorkerMembership("bc-w1", "bc-p", WorkerSpawnKind.CREATED)))
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w1", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        assertThat(primaryIds(agents)).containsExactly("bc-x", "bc-w2")
        // The coordinator is fetched by id on the repository's own scope; wait until the collector has seen it land.
        withTimeout(5_000) { while (published.none { rows -> rows.any { it.id == "bc-p" } }) delay(10) }
        watching.cancel()
        assertThat(agents.agent("bc-p")!!.isProjectRoot).isTrue()
        assertThat(primaryIds(agents)).containsExactly("bc-x", "bc-w2")
        published.forEach { rows ->
            assertThat(primaryIds(rows)).doesNotContain("bc-w1")
            rows.firstOrNull { it.id == "bc-p" }?.let { root -> assertThat(root.isProjectRoot).isTrue() }
        }
        assertThat(published.any { rows -> rows.any { it.id == "bc-p" } }).isTrue()

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
    fun `a membership listed stands, one absent from a complete answer is released, and its status is the worker's run status`() = runBlocking<Unit> {
        val agents = seed()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-p", isProject = true)))
        // A worker placed earlier by a membership the answer no longer lists was released on another client.
        agents.applyLineage("bc-p", mapOf("bc-w2" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        assertThat(agents.agent("bc-w2")?.isProjectChild).isTrue()
        // `WorkerManagerMembership.status` is the worker's `BackgroundComposerStatus`, nothing about the membership:
        // a finished worker is as much a member as a running one (the desktop seeds the header's status from it).
        lineage.workers = mapOf(
            "bc-p" to listOf(
                WorkerMembership("bc-w1", "bc-p", WorkerSpawnKind.CREATED, status = "BACKGROUND_COMPOSER_STATUS_RUNNING"),
                WorkerMembership("bc-a", "bc-p", WorkerSpawnKind.ADOPTED, status = "BACKGROUND_COMPOSER_STATUS_FINISHED"),
            ),
        )
        val projects = projects(agents)
        projects.syncLineage(listOf("bc-p"))
        assertThat(agents.agent("bc-w1")?.isProjectChild).isTrue()
        assertThat(agents.agent("bc-a")?.isProjectChild).isTrue()
        assertThat(agents.agent("bc-w2")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(WorkerMembership("x", "y", status = "BACKGROUND_COMPOSER_STATUS_RUNNING").runStatus).isEqualTo(com.cursorforandroid.domain.RunStatus.RUNNING)
        assertThat(WorkerMembership("x", "y", status = "FINISHED").runStatus).isEqualTo(com.cursorforandroid.domain.RunStatus.FINISHED)
        assertThat(WorkerMembership("x", "y", status = null).runStatus).isNull()
        // A worker whose own record names the root keeps the record's link whatever the answer lists.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w2", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        projects.syncLineage(listOf("bc-p"))
        assertThat(agents.agent("bc-w2")?.isProjectChild).isTrue()
    }

    @Test
    fun `a record read is the last word over a coordinator's create_agent, for a machine chat as for any`() = runBlocking<Unit> {
        api.addIdleAgent("bc-p", "Cursor for Android", "run-p")
        api.addRunningAgent("bc-x", "Cesium", "run-x")
        val at = "2026-04-13T18:30:00.000Z"
        api.agents["bc-m"] = com.cursorforandroid.data.api.dto.AgentDto(id = "bc-m", name = "Codex-Poly-Bot Scaling", status = "ACTIVE", env = com.cursorforandroid.data.api.dto.AgentEnvDto(type = "machine", name = "poly"), createdAt = at, updatedAt = at, latestRunId = "run-m")
        api.runs["run-m"] = com.cursorforandroid.data.api.dto.RunDto(id = "run-m", agentId = "bc-m", status = "RUNNING", createdAt = at, updatedAt = at)
        val agents = agents()
        agents.refresh()
        // Default mode's one word: the coordinator's create_agent named both as its workers.
        agents.applyLineage("bc-p", mapOf("bc-m" to AgentParentKind.PROJECT_WORKER, "bc-x" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-m")?.isProjectScoped).isTrue()
        assertThat(agents.agent("bc-x")?.isProjectScoped).isTrue()
        // bc-p is no Project on its transcript's word: a chat of the account's own with the two nested under it.
        assertThat(primaryIds(agents)).containsExactly("bc-p")
        // The account's records name no parent for either: the record is the word, and the transcript's stamp goes
        // — and is not made again over a record that has been read.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-x"), ComposerSnapshot("bc-m")))
        assertThat(agents.agent("bc-x")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agents.agent("bc-m")?.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(primaryIds(agents)).containsExactly("bc-m", "bc-p", "bc-x")
        agents.applyLineage("bc-p", mapOf("bc-x" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-x")?.scope).isEqualTo(AgentScope.PRIMARY)
        // The record naming the manager places it; so does a membership answer, for the machine chat as for any.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-x", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))))
        assertThat(agents.agent("bc-x")?.isProjectScopedByEvidence).isTrue()
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
                ComposerSnapshot("bc-s", parent = AgentParent("bc-p", AgentParentKind.SIDE_CHAT), source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD),
            ),
        )
        agents.loadMore()
        agents.loadMore()
        assertThat(agents.agent("bc-w1")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-w1")?.scopeSignal).isEqualTo(LineageSignal.ACCOUNT_RECORD)
        assertThat(agents.agent("bc-w1")?.record?.managerAgentId).isEqualTo("bc-p")
        assertThat(agents.agent("bc-s")?.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.SIDE_CHAT))
        assertThat(agents.agent("bc-s")?.isProjectChild).isTrue()
        assertThat(primaryIds(agents)).isEmpty()
    }

    @Test
    fun `the scope is the desktop's two predicates - the parent link, then the Project flag - and never the source`() {
        val base = com.cursorforandroid.domain.Agent(
            id = "bc-w", name = "Worker", lifecycle = com.cursorforandroid.domain.AgentLifecycle.ACTIVE, runStatus = null,
            envType = com.cursorforandroid.domain.EnvType.CLOUD, envName = null, url = "", createdAtMillis = 0, updatedAtMillis = 0, latestRunId = null, repoUrl = null, startingRef = null,
        )
        assertThat(base.copy(parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)).scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(base.copy(isProject = true, parent = AgentParent("bc-p", AgentParentKind.SUBAGENT)).scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(base.copy(isProject = true).scope).isEqualTo(AgentScope.PROJECT_ROOT)
        // A source says how a chat was started, never where it belongs.
        assertThat(base.copy(source = AgentSource.AS_SUBAGENT_FROM_CLOUD).scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(base.copy(isProject = true, source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD).scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(base.copy(source = AgentSource.CLOUD_META_AGENT).scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(base.scope).isEqualTo(AgentScope.PRIMARY)
        // The record's raw fields decide the same way, in the desktop's precedence.
        val fields = com.cursorforandroid.data.api.RecordFields(managerAgentId = "bc-p", sideChatParentId = "bc-s", subagentParentId = "bc-sub")
        assertThat(fields.desktopParent).isEqualTo(AgentParent("bc-sub", AgentParentKind.SUBAGENT))
        assertThat(fields.copy(subagentParentId = null).desktopParent).isEqualTo(AgentParent("bc-s", AgentParentKind.SIDE_CHAT))
        assertThat(fields.copy(subagentParentId = null, sideChatParentId = null).desktopParent).isEqualTo(AgentParent("bc-p", AgentParentKind.PROJECT_WORKER))
        assertThat(com.cursorforandroid.data.api.RecordFields(projectMetadata = "{}").isProject).isTrue()
        assertThat(com.cursorforandroid.data.api.RecordFields(startedAsNewProject = true).isProject).isFalse()
    }
}
