package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.RootScan
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * Every Project renders, wherever its row is. On 0.3.2–0.3.4 the Projects group was built from the loaded rows, a
 * refresh loaded one page, and a root beyond it was known only when a loaded worker named it — so Projects went
 * missing by the page. Now the root registry names every Project any source has named (the account list scanned in
 * full in Extended mode, the workers' records, the memberships, the coordinators' transcripts, the disk), the group
 * is drawn from the registry with stand-ins for the rows not loaded, and the rows are fetched by id. Seven Projects
 * spread across pages, a restart, Extended mode off: all seven, every time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectRootsInvariantTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeLineage : ProjectLineageApi {
        var roots: List<ComposerSnapshot> = emptyList()
        var children: List<ComposerSnapshot> = emptyList()
        var memberships: Map<String, List<WorkerMembership>> = emptyMap()
        var scans = 0

        override suspend fun workersForManager(managerId: String): List<WorkerMembership> = memberships[managerId].orEmpty()
        override suspend fun children(parentId: String): List<ComposerSnapshot> = children.filter { it.parent?.id == parentId && it.parent.kind != AgentParentKind.PROJECT_WORKER }
        override suspend fun scanRoots(maxPages: Int): RootScan {
            scans++
            return RootScan(roots, children, pagesRead = 3, complete = true)
        }
    }

    private val api = FakeCursorApi()
    private val lineage = FakeLineage()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var cache: AgentListCache
    private var extended = true
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }
    private val rootIds = (1..7).map { "bc-root-$it" }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        cache = AgentListCache(JsonDiskCache(folder.newFolder("agents"), dispatcher = Dispatchers.Unconfined))
        // Seven Projects, each with two workers, and plain chats between them: newest first, three rows to a page,
        // so the roots land on every page and most of them far beyond the first.
        var created = 1_800_000_000_000L
        fun next(): String = Instant.ofEpochMilli(created).also { created -= 3_600_000L }.toString()
        var plain = 0
        val roots = ArrayList<ComposerSnapshot>()
        val children = ArrayList<ComposerSnapshot>()
        val memberships = HashMap<String, List<WorkerMembership>>()
        rootIds.forEachIndexed { index, root ->
            api.addIdleAgent("bc-plain-${plain++}", "Plain $plain", "run-plain-$plain", createdAt = next())
            api.addIdleAgent(root, "Project ${index + 1}", "run-$root", createdAt = next())
            roots += ComposerSnapshot(root, name = "Project ${index + 1}", isProject = true, projectAppearance = ProjectAppearance("rocket", "purple"))
            val workers = (1..2).map { w ->
                val id = "$root-w$w"
                api.addRunningAgent(id, "Worker $w of ${index + 1}", "run-$id", createdAt = next())
                // Half the workers' records name their manager; the rest are known by membership alone.
                if (w == 1) children += ComposerSnapshot(id, parent = AgentParent(root, AgentParentKind.PROJECT_WORKER))
                WorkerMembership(id, root, WorkerSpawnKind.CREATED)
            }
            memberships[root] = workers
        }
        lineage.roots = roots
        lineage.children = children
        lineage.memberships = memberships
        api.pageSize = 3
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache, scope, persistDelayMs = 1, capabilities = capabilities, runningScanPages = 1)

    private fun projects(agents: AgentRepository) = ProjectRepository(session, agents, lineage, scope = scope, pollIntervalMs = 60_000, capabilities = capabilities)

    private fun projectsGroup(agents: AgentRepository, prefs: ListPreferences = ListPreferences()) =
        AgentListOrganizer.organize(agents.state.value.agents, prefs, LocalAgentState(), nowMillis = 1_800_000_100_000L, zone = ZoneOffset.UTC, knownRoots = agents.knownRoots.value)
            .firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    @Test
    fun `every Project renders whichever page its row is on, across a restart and with Extended mode off`() = runBlocking<Unit> {
        var agents = agents()
        var projects = projects(agents)
        agents.refresh()
        // One page of three loaded. The public API has no word on Projects, so nothing is a root yet: this is the
        // 0.3.2 picture, the Projects group empty or short until something names the roots.
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-plain-0", "bc-root-1", "bc-root-1-w1")
        assertThat(projectsGroup(agents)).isEmpty()

        // The root discovery pass over the whole account list: every Project's record, every worker's, into the
        // registry; the group lists all seven at once — stand-ins with their record's name and look for the rows
        // the page does not hold — and their rows are then fetched by id.
        projects.discoverRoots()
        assertThat(lineage.scans).isEqualTo(1)
        assertThat(agents.knownRoots.value.map { it.id }).containsExactlyElementsIn(rootIds)
        val group = projectsGroup(agents)
        assertWithMessage("all seven Projects render").that(group.map { it.agent.id }).containsExactlyElementsIn(rootIds)
        assertThat(group.all { it.agent.isProjectRoot && it.agent.looksLikeProject }).isTrue()
        assertThat(group.first { it.agent.id == "bc-root-7" }.agent.name).isEqualTo("Project 7")
        assertThat(group.first { it.agent.id == "bc-root-7" }.agent.projectAppearance).isEqualTo(ProjectAppearance("rocket", "purple"))
        // Fetched by id: the rows themselves head their trees now, not stand-ins.
        assertThat(agents.state.value.agents.filter { it.id in rootIds }.map { it.id }).containsExactlyElementsIn(rootIds)
        assertThat(group.none { it.isPlaceholder }).isTrue()

        // The memberships name the workers whose records did not; the account's count shows on every root.
        projects.syncLineage()
        val counts = projects.memberCounts.first()
        assertThat(counts.keys).containsExactlyElementsIn(rootIds)
        assertThat(counts.values.toSet()).containsExactly(2)
        val counted = AgentListOrganizer.organize(agents.state.value.agents, ListPreferences(), LocalAgentState(), nowMillis = 1_800_000_100_000L, zone = ZoneOffset.UTC, knownRoots = agents.knownRoots.value, memberCounts = counts)
            .first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows
        assertThat(counted.all { it.shownCount == 2 }).isTrue()
        // A second pass within the interval does not read the list again.
        projects.discoverRoots()
        assertThat(lineage.scans).isEqualTo(1)

        // A restart: the registry comes back from the disk with the list, and the group is whole before any fetch.
        awaitUntil { cache.readLineage()?.roots?.size == 7 }
        agents.reset()
        agents = agents()
        projects = projects(agents)
        agents.restoreFromCache()
        assertThat(agents.knownRoots.value.map { it.id }).containsExactlyElementsIn(rootIds)
        assertThat(projectsGroup(agents).map { it.agent.id }).containsExactlyElementsIn(rootIds)
        agents.refresh()
        assertThat(projectsGroup(agents).map { it.agent.id }).containsExactlyElementsIn(rootIds)

        // Extended mode off: no account to scan, but the registry stands and the public API gives the rows by id.
        extended = false
        agents.forgetAccountSources(emptySet())
        awaitUntil { cache.readLineage()?.roots?.size == 7 }
        agents.reset()
        agents = agents()
        projects = projects(agents)
        agents.refresh()
        projects.discoverRoots()
        assertThat(lineage.scans).isEqualTo(1)
        assertThat(agents.knownRoots.value.map { it.id }).containsExactlyElementsIn(rootIds)
        assertThat(projectsGroup(agents).map { it.agent.id }).containsExactlyElementsIn(rootIds)
        assertThat(agents.state.value.agents.filter { it.id in rootIds }.map { it.id }).containsExactlyElementsIn(rootIds)
    }

    @Test
    fun `a root leaves the registry only on the record's own word - gone, or no longer a Project - never on silence`() = runBlocking<Unit> {
        val agents = agents()
        val projects = projects(agents)
        agents.refresh()
        projects.discoverRoots()
        assertThat(agents.knownRoots.value).hasSize(7)
        // The account list's next window names none of them: nothing changes.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-plain-0"), ComposerSnapshot("bc-plain-1")))
        assertThat(agents.knownRoots.value).hasSize(7)
        // A membership answer naming a worker holds bc-root-2 beside its flag; the record withdrawing the flag then
        // leaves it standing on the membership — a worker's record naming it is no evidence of its own, only a
        // candidate — and an archived one stays, archived.
        agents.applyLineage("bc-root-2", mapOf("bc-root-2-w1" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP, retract = setOf(AgentParentKind.PROJECT_WORKER))
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-root-2"), ComposerSnapshot("bc-root-3", isProject = true, archived = true)))
        assertThat(agents.knownRoots.value.first { it.id == "bc-root-2" }.flagged).isFalse()
        assertThat(agents.knownRoots.value.first { it.id == "bc-root-2" }.evidence).startsWith("membership 1")
        assertThat(agents.knownRoots.value.first { it.id == "bc-root-3" }.archived).isTrue()
        // The membership answer then names nobody: no evidence is left, and the root leaves — its row a chat of the
        // account's own, whatever worker records still say (they make a candidate, not a root).
        agents.applyLineage("bc-root-2", emptyMap(), LineageSignal.MEMBERSHIP, retract = setOf(AgentParentKind.PROJECT_WORKER))
        assertThat(agents.knownRoots.value.map { it.id }).doesNotContain("bc-root-2")
        assertThat(agents.agent("bc-root-2")?.scope).isEqualTo(AgentScope.PRIMARY)
        // A membership answer with nobody in it does not take out a root whose record still flags it.
        agents.applyLineage("bc-root-3", emptyMap(), LineageSignal.MEMBERSHIP, retract = setOf(AgentParentKind.PROJECT_WORKER))
        assertThat(agents.knownRoots.value.map { it.id }).contains("bc-root-3")
        // A record with `startedAsNewProject` and no `project_metadata` (the desktop never reads the former), and a
        // worker's record naming a manager, admit nothing: the latter a candidate only, until the membership answers.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-plain-3", record = RecordFields(startedAsNewProject = true)), ComposerSnapshot("bc-plain-4-w", parent = AgentParent("bc-plain-4", AgentParentKind.PROJECT_WORKER))))
        assertThat(agents.knownRoots.value.map { it.id }).containsNoneOf("bc-plain-3", "bc-plain-4")
        assertThat(agents.managerCandidates()).contains("bc-plain-4")
        // A Project deleted on the server: the public API answers 404 for it. Nothing but the registry knows it
        // (a membership answer named its worker; its own row never loaded): the fetch by id is what lets it go —
        // and the Projects it stands beside are all still there.
        api.agents.remove("bc-root-7")
        agents.upsert(agents.agent("bc-root-7")!!.copy(name = "gone"))
        val fresh = AgentRepository(session, prefs, AttachmentStore(context), AgentListCache(JsonDiskCache(folder.newFolder("fresh"), dispatcher = Dispatchers.Unconfined)), scope, persistDelayMs = 1, capabilities = capabilities, runningScanPages = 1)
        fresh.refresh()
        fresh.applyLineage("bc-root-7", mapOf("bc-root-7-w2" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        assertThat(fresh.knownRoots.value.map { it.id }).containsExactly("bc-root-7")
        fresh.materializeRoots()
        assertThat(fresh.knownRoots.value).isEmpty()
        assertThat(fresh.unresolvedRoots()).isEmpty()
        assertThat(agents.knownRoots.value.map { it.id }).containsExactly("bc-root-1", "bc-root-3", "bc-root-4", "bc-root-5", "bc-root-6", "bc-root-7")
    }

    @Test
    fun `a Project's chats are placed by the discovery pass before their rows arrive, and the archived filter hides an archived Project alone`() = runBlocking<Unit> {
        val agents = agents()
        val projects = projects(agents)
        agents.refresh()
        projects.discoverRoots()
        // The workers whose records named their manager are placed the moment their page lands; the group holds them.
        agents.loadMore()
        agents.loadMore()
        val loadedWorkers = agents.state.value.agents.filter { it.id.contains("-w1") }
        assertThat(loadedWorkers).isNotEmpty()
        assertThat(loadedWorkers.all { it.parent?.id?.startsWith("bc-root-") == true }).isTrue()
        assertThat(loadedWorkers.all { it.scopeSignal == LineageSignal.ACCOUNT_RECORD }).isTrue()
        // An archived Project is listed only while the Archived filter is on, like any archived chat.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-root-5", name = "Project 5", isProject = true, archived = true)))
        assertThat(projectsGroup(agents).map { it.agent.id }).doesNotContain("bc-root-5")
        assertThat(projectsGroup(agents, ListPreferences(statuses = StatusFilter.entries.toSet())).map { it.agent.id }).contains("bc-root-5")
    }
}
