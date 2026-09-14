package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.PinnedIds
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.RootScan
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.CachedLineage
import com.cursorforandroid.data.local.CachedPlacement
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Bennett's account in the shape of his screenshots, run through the live startup path rather than a fixture of
 * a few rows: eight Projects of 2 to 115 members (the coordinators started as cloud meta agents, one without an
 * appearance, one known only by the "New Project" flag, one archived), a fifth of the workers adopted without a
 * manager on their record, side chats, a hundred and twenty chats of the account's own with archived ones among
 * them, a Remote Control chat pinned, and roots older than the 200 newest records — installed over the state 0.3.4
 * left on disk, with the account session minting late and a page of the list failing.
 *
 * What went wrong on his phone, as this reproduces it: the account round was cued by the first touch of the pin
 * repository, which an ordinary launch never made (the sidebar's end, a pin, Settings) — so the account's list was
 * not read, and everything drawn from it came and went; a coordinator's `CLOUD_META_AGENT` source read as a child's
 * made every such Project a child of nobody, drawn nowhere; and the list's pages past the first were unreachable
 * because the first page never asked for a page token.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AccountSimulationTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** One chat of the account, as both the public list and the account list know it. */
    private class Chat(
        val id: String,
        val name: String,
        val activityMillis: Long,
        val running: Boolean = false,
        val archived: Boolean = false,
        val machine: Boolean = false,
        val source: AgentSource? = null,
        val isProject: Boolean = false,
        val appearance: ProjectAppearance? = null,
        val newProjectFlag: Boolean = false,
        val manager: String? = null,
        val adoptedBy: String? = null,
        val sideChatOf: String? = null,
    ) {
        val root: String? get() = manager ?: adoptedBy ?: sideChatOf
        val projectScoped: Boolean get() = isProject || newProjectFlag || root != null
        /** The record as the app reads it: `projectMetadata` or the "New Project" flag makes a Project's chat. */
        fun snapshot(): ComposerSnapshot = ComposerSnapshot(
            id = id,
            name = name,
            archived = archived,
            isProject = (isProject || newProjectFlag) && manager == null,
            projectAppearance = appearance,
            parent = manager?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) } ?: sideChatOf?.let { AgentParent(it, AgentParentKind.SIDE_CHAT) },
            source = source,
            status = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        )
    }

    private class Project(val id: String, val name: String, val members: Int, val appearance: ProjectAppearance?, val newProjectFlagOnly: Boolean = false, val archived: Boolean = false, val ageHours: Int)

    private val projects = listOf(
        Project("bc-shipyard", "Shipyard", 31, ProjectAppearance("logo-github", "blue"), ageHours = 2),
        Project("bc-jobs", "Job & Bounty Research", 8, ProjectAppearance("briefcase", "orange"), ageHours = 5),
        Project("bc-revenue", "Revenue Scaling Pipeline", 115, ProjectAppearance("lightning", "green"), ageHours = 1),
        Project("bc-noetic", "Noetic", 28, null, ageHours = 30),
        Project("bc-murmur", "Murmur", 6, ProjectAppearance("target", "purple"), ageHours = 60),
        Project("bc-mobile", "Cursor for Android", 17, ProjectAppearance("lightning", "default"), ageHours = 3),
        Project("bc-meter", "Codex Meter", 2, ProjectAppearance("gauge", "cyan"), newProjectFlagOnly = true, ageHours = 400),
        Project("bc-retro", "Q2 launch retro", 4, ProjectAppearance("flag", "yellow"), archived = true, ageHours = 900),
    )

    private lateinit var chats: List<Chat>
    private val byId = HashMap<String, Chat>()
    private val memberships = HashMap<String, MutableList<WorkerMembership>>()

    private val api = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var cache: AgentListCache
    private var extended = true
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }

    /** The account service in miniature: the list windowed and paged by activity, the memberships, the records by id. */
    private inner class Account : PinsApi, ProjectLineageApi {
        val listCalls = AtomicInteger()
        val scanPages = AtomicInteger()
        /** Rounds of the list that fail before one goes through: the session minting late on a cold start. */
        @Volatile var listFailuresLeft = 0
        /** Pages of the discovery pass that fail, by page number, once each. */
        val failingPages = HashSet<Int>()
        /** 0.3.5's scan as it ran: the first page never asked for a page token, so the pass ended there and called itself complete. */
        @Volatile var pagesServed: Int? = null
        private val ordered get() = chats.sortedByDescending { it.activityMillis }

        private fun page(offset: Long?, size: Int): Pair<List<Chat>, Long?> {
            val rows = ordered.filter { offset == null || it.activityMillis < offset }.take(size)
            val next = rows.lastOrNull()?.activityMillis?.takeIf { rows.size == size }
            return rows to next
        }

        override suspend fun list(): AccountList {
            listCalls.incrementAndGet()
            if (listFailuresLeft > 0) {
                listFailuresLeft--
                throw SessionUnavailableException("Couldn't reach Cursor to start a session.")
            }
            val (rows, next) = page(null, 200)
            return AccountList(
                pinned = PinnedIds(setOf("bc-codex", "bc-market"), loaded = true),
                pullRequests = emptyMap(),
                sources = rows.mapNotNull { c -> c.source?.let { c.id to it } }.toMap(),
                composers = rows.map { it.snapshot() },
                nextCursor = next?.toString(),
            )
        }

        override suspend fun listMore(cursor: String): AccountList {
            val (rows, next) = page(cursor.toLong(), 200)
            return AccountList(PinnedIds(emptySet(), false), emptyMap(), rows.mapNotNull { c -> c.source?.let { c.id to it } }.toMap(), rows.map { it.snapshot() }, nextCursor = next?.toString())
        }

        override suspend fun pin(ids: Collection<String>) = Unit
        override suspend fun unpin(ids: Collection<String>) = Unit

        override suspend fun workersForManager(managerId: String): List<WorkerMembership> = memberships[managerId].orEmpty()
        override suspend fun children(parentId: String): List<ComposerSnapshot> = chats.filter { it.sideChatOf == parentId }.map { it.snapshot() }
        override suspend fun record(id: String): ComposerSnapshot? = byId[id]?.snapshot()

        override suspend fun scanRoots(maxPages: Int): RootScan {
            val roots = ArrayList<ComposerSnapshot>()
            val children = ArrayList<ComposerSnapshot>()
            var offset: Long? = null
            var pages = 0
            var records = 0
            var complete = false
            var failure: String? = null
            do {
                val number = pages + 1
                if (failingPages.remove(number)) {
                    failure = "page $number: connection reset"
                    break
                }
                val (rows, next) = page(offset, 200)
                scanPages.incrementAndGet()
                pages++
                records += rows.size
                rows.map { it.snapshot() }.forEach { snap ->
                    if (snap.scope == AgentScope.PROJECT_ROOT) roots += snap
                    if (snap.parent != null) children += snap
                }
                offset = next
                if (offset == null) complete = true
                if (pagesServed != null && pages >= pagesServed!!) {
                    offset = null
                    complete = true
                }
            } while (offset != null && pages < maxPages)
            return RootScan(roots, children, pages, complete, records, failure)
        }
    }

    private val account = Account()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        cache = AgentListCache(JsonDiskCache(folder.newFolder("agents"), dispatcher = Dispatchers.Unconfined))
        buildAccount()
        api.pageSize = 20
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun buildAccount() {
        val random = Random(7)
        val now = 1_800_000_000_000L
        val all = ArrayList<Chat>()
        projects.forEach { p ->
            val rootActivity = now - p.ageHours * 3_600_000L
            all += Chat(
                p.id, p.name, rootActivity, running = p.id == "bc-revenue" || p.id == "bc-shipyard", archived = p.archived,
                source = AgentSource.CLOUD_META_AGENT, isProject = !p.newProjectFlagOnly, appearance = p.appearance, newProjectFlag = p.newProjectFlagOnly,
            )
            val members = memberships.getOrPut(p.id) { ArrayList() }
            repeat(p.members) { index ->
                val id = "${p.id}-m${index + 1}"
                val activity = rootActivity - (index + 1) * 600_000L - random.nextLong(0, 300_000L)
                val running = !p.archived && random.nextInt(10) == 0
                when {
                    // Every sixth member is a side chat off the coordinator.
                    index % 6 == 5 -> all += Chat(id, "${p.name} side chat ${index + 1}", activity, running = running, source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD, sideChatOf = p.id)
                    // A fifth of the workers were adopted: the membership names them, their record does not.
                    index % 5 == 4 -> {
                        all += Chat(id, "${p.name} worker ${index + 1}", activity, running = running, adoptedBy = p.id)
                        members += WorkerMembership(id, p.id, WorkerSpawnKind.ADOPTED)
                    }
                    else -> {
                        all += Chat(id, "${p.name} worker ${index + 1}", activity, running = running, manager = p.id)
                        members += WorkerMembership(id, p.id, WorkerSpawnKind.CREATED)
                    }
                }
            }
        }
        // The account's own chats: a hundred and twenty, fifteen archived, some running, two pinned — one of them
        // a Remote Control chat older than anything the first page holds.
        repeat(120) { index ->
            val id = "bc-own-${index + 1}"
            all += Chat(id, "Own chat ${index + 1}", now - index * 1_900_000L - 60_000L, running = index % 9 == 0, archived = index % 8 == 7, source = if (index % 3 == 0) AgentSource.EDITOR else null)
        }
        all += Chat("bc-codex", "Codex-Poly-Bot Scaling", now - 500 * 3_600_000L, running = true, machine = true, source = AgentSource.EDITOR)
        all += Chat("bc-market", "Market Opportunities", now - 26 * 3_600_000L, source = AgentSource.SLACK)
        chats = all
        chats.forEach { byId[it.id] = it }
        // The public API's picture of the same account.
        chats.forEach { c ->
            val created = Instant.ofEpochMilli(c.activityMillis).toString()
            if (c.running) api.addRunningAgent(c.id, c.name, "run-${c.id}", createdAt = created) else api.addIdleAgent(c.id, c.name, "run-${c.id}", createdAt = created)
            if (c.archived) api.agents[c.id] = api.agents.getValue(c.id).copy(status = "ARCHIVED")
            if (c.machine) api.agents[c.id] = api.agents.getValue(c.id).copy(env = AgentEnvDto("machine", "bennetts-mac"))
        }
    }

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache, scope, persistDelayMs = 1, capabilities = capabilities, runningScanPages = 2)

    private fun projectsOf(agents: AgentRepository) = ProjectRepository(session, agents, account, scope = scope, pollIntervalMs = 60_000, capabilities = capabilities, retryDelaysMs = listOf(50L, 50L, 50L))

    /** The graph's wiring: the account round hands its list to the sources, then to the root discovery and the memberships. */
    private fun pinsOf(agents: AgentRepository, projects: ProjectRepository, prime: Boolean = true) = PinRepository(
        session, prefs, agents, account, scope = scope, capabilities = capabilities, retryDelaysMs = listOf(50L, 50L, 50L),
        onList = { list, token ->
            agents.applySources(list.sources, token)
            projects.scheduleRootDiscovery(list.composers.filter { it.scope == AgentScope.PROJECT_ROOT }.map { it.id })
        },
    ).also { pins -> if (prime) agents.accountPrime = { pins.primeForFetch() } }

    private fun sections(agents: AgentRepository, projects: ProjectRepository?, prefs: ListPreferences = ListPreferences()) = runBlocking {
        val local = LocalAgentState(pinnedIds = this@AccountSimulationTest.prefs.localAgentState.first().pinnedIds)
        AgentListOrganizer.organize(
            agents.state.value.agents, prefs, local, nowMillis = 1_800_000_100_000L, zone = ZoneOffset.UTC,
            knownRoots = agents.knownRoots.value, memberCounts = projects?.memberCounts?.first().orEmpty(),
        )
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 20_000, condition: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    private val expectedRoots get() = projects.map { it.id }.toSet()
    private val visibleRoots get() = projects.filterNot { it.archived }.map { it.name }

    /** The state 0.3.4 left on disk after one account round: the roots read as children by their source, the placements, the sources. */
    private suspend fun write034State() {
        val rows = chats.sortedByDescending { it.activityMillis }.take(60).map { c ->
            Agent(
                id = c.id, name = c.name, lifecycle = if (c.archived) AgentLifecycle.ARCHIVED else if (c.running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
                runStatus = if (c.running) RunStatus.RUNNING else RunStatus.FINISHED, envType = if (c.machine) EnvType.MACHINE else EnvType.CLOUD, envName = null,
                url = "https://cursor.com/agents/${c.id}", createdAtMillis = c.activityMillis, updatedAtMillis = c.activityMillis, latestRunId = "run-${c.id}",
                repoUrl = null, startingRef = null, source = c.source, isProject = c.isProject, projectAppearance = c.appearance,
                parent = c.manager?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) } ?: c.sideChatOf?.let { AgentParent(it, AgentParentKind.SIDE_CHAT) },
                // 0.3.4 kept a coordinator's row as a root by its record and read it as a child by its source.
                knownScope = when {
                    c.root != null -> AgentScope.PROJECT_CHILD
                    c.isProject -> AgentScope.PROJECT_ROOT
                    c.source == AgentSource.CLOUD_META_AGENT -> AgentScope.PROJECT_CHILD
                    else -> null
                },
                scopeSignal = when {
                    c.root != null -> LineageSignal.ACCOUNT_RECORD
                    c.isProject -> LineageSignal.ACCOUNT_RECORD
                    c.source == AgentSource.CLOUD_META_AGENT -> LineageSignal.HIDDEN_SOURCE
                    else -> null
                },
            )
        }
        val held = rows.mapTo(HashSet()) { it.id }
        val lineage = CachedLineage(
            placements = chats.filter { it.id !in held && (it.manager != null || it.sideChatOf != null) }.take(100).map { c ->
                CachedPlacement(c.id, c.manager ?: c.sideChatOf, if (c.manager != null) AgentParentKind.PROJECT_WORKER else AgentParentKind.SIDE_CHAT, LineageSignal.ACCOUNT_RECORD)
            } + projects.map { CachedPlacement(it.id, null, null, LineageSignal.ACCOUNT_RECORD) },
            sources = chats.filter { it.id !in held && it.source != null && (it.source == AgentSource.CLOUD_META_AGENT || it.source == AgentSource.AS_SIDE_CHAT_FROM_CLOUD) }.associate { it.id to it.source!! },
            hintRefused = emptySet(),
            roots = emptyList(),
        )
        cache.write(rows, lineage = lineage)
        prefs.setPinnedIds(setOf("bc-codex", "bc-market"))
    }

    private fun assertInvariants(label: String, agents: AgentRepository, projects: ProjectRepository?) {
        val sections = sections(agents, projects)
        val group = sections.firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()
        assertWithMessage("$label: every Project renders").that(group.map { it.agent.name }).containsExactlyElementsIn(visibleRoots)
        assertWithMessage("$label: the archived Project waits behind the filter").that(group.map { it.agent.id }).doesNotContain("bc-retro")
        val primary = sections.filterNot { it.key == AgentListOrganizer.PROJECTS_KEY }.flatMap { it.rows }
        val leaked = primary.filter { byId[it.agent.id]?.projectScoped == true && !it.isPinned }
        assertWithMessage("$label: nothing of a Project among the primary rows").that(leaked.map { it.agent.id }).isEmpty()
        assertWithMessage("$label: the pinned Remote Control chat and the pinned chat are listed")
            .that(sections.firstOrNull { it.key == AgentListOrganizer.PINNED_KEY }?.rows?.map { it.agent.id }.orEmpty()).containsExactly("bc-codex", "bc-market")
        val loadedOwn = agents.state.value.agents.filter { byId[it.id]?.projectScoped == false }
        assertWithMessage("$label: no chat of the account's own is read as a Project's").that(loadedOwn.filter { it.isProjectScoped }.map { it.id }).isEmpty()
    }

    @Test
    fun `an install upgraded from 0_3_4 lists every Project on the first launch, with the session minting late and a page failing`() = runBlocking<Unit> {
        write034State()
        // A cold start: the first two account rounds fail (no session yet), the discovery pass loses its second page once.
        account.listFailuresLeft = 2
        account.failingPages += 2

        var agents = agents()
        var projects = projectsOf(agents)
        var pins = pinsOf(agents, projects)
        agents.restoreFromCache()
        // From the disk alone, before any network: 0.3.4's rows re-read by the record's own flag — every Project the
        // disk held is a root again, none of them hidden by its source.
        val fromDisk = sections(agents, projects).firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty().map { it.agent.id }
        assertWithMessage("the roots 0.3.4 held on disk render before any network").that(fromDisk).containsAtLeast("bc-shipyard", "bc-revenue", "bc-mobile")
        assertThat(agents.state.value.agents.none { byId[it.id]?.isProject == true && it.isProjectChild }).isTrue()

        agents.refresh()
        // The account round is cued by the completed fetch and retried past the two failures; the discovery pass
        // reads its pages, loses one, keeps what it read and finishes on the retry.
        awaitUntil("the account round to go through") { account.listCalls.get() >= 3 && pins.state.value.lastSyncedAtMillis != null }
        awaitUntil("the discovery pass to finish") { projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        val scan = projects.lastRootScan.value!!
        assertThat(scan.attempts).isAtLeast(2)
        assertThat(scan.pagesRead).isEqualTo(2)
        assertThat(scan.complete).isTrue()
        assertThat(agents.knownRoots.value.map { it.id }).containsExactlyElementsIn(expectedRoots)
        // The memberships place the adopted workers, and the rows of the roots the pages did not hold are fetched by id.
        awaitUntil("the memberships to be read") { projects.memberCounts.first().keys.containsAll(expectedRoots - "bc-retro") }
        awaitUntil("every root's row to be fetched") { expectedRoots.all { agents.agent(it) != null } }
        assertInvariants("after the first launch", agents, projects)
        // The counts are the account's: 31, 8, 115, 28, 6, 17, 2.
        val group = sections(agents, projects).first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows
        assertThat(group.associate { it.agent.name to it.shownCount }).containsAtLeast("Shipyard", 31, "Revenue Scaling Pipeline", 115, "Codex Meter", 2, "Noetic", 28)
        // The running count is the server's running set less the Projects' own: the roots and their members are
        // never counted, and never notified.
        val serverRunning = chats.filter { it.running && !it.projectScoped }.map { it.id }.toSet()
        val loadedRunning = agents.state.value.agents.filter { it.runStatus?.isActive == true && !it.isProjectScopedByEvidence }.map { it.id }.toSet()
        assertWithMessage("no Project's chat is counted as running").that(loadedRunning - serverRunning).isEmpty()
        assertThat(agents.state.value.agents.filter { it.isProjectScopedByEvidence && it.runStatus?.isActive == true }).isNotEmpty()
        assertThat(agents.runningScan.value.hasScanned).isTrue()

        // A restart onto 0.3.5's own disk state: whole from the disk, whole after the fetch, no discovery needed for it.
        awaitUntil("the registry to be on disk") { cache.readLineage()?.roots?.size == expectedRoots.size }
        agents.reset()
        agents = agents()
        projects = projectsOf(agents)
        pins = pinsOf(agents, projects)
        agents.restoreFromCache()
        assertInvariants("restored from 0.3.5's disk state", agents, projects)
        agents.refresh()
        awaitUntil("the second launch's account round") { pins.state.value.lastSyncedAtMillis != null }
        awaitUntil("the second launch's memberships") { projects.memberCounts.first().keys.containsAll(expectedRoots - "bc-retro") }
        assertInvariants("after the second launch", agents, projects)

        // Extended mode off: no account to read, the registry stands, the roots come by id from the public API.
        extended = false
        agents.reset()
        agents = agents()
        projects = projectsOf(agents)
        pinsOf(agents, projects)
        agents.restoreFromCache()
        agents.refresh()
        projects.discoverRoots()
        awaitUntil("the roots by id in default mode") { expectedRoots.all { agents.agent(it) != null } }
        val group2 = sections(agents, null).firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()
        assertWithMessage("default mode: every Project renders from the registry").that(group2.map { it.agent.name }).containsExactlyElementsIn(visibleRoots)
        assertThat(account.listCalls.get()).isEqualTo(4)
    }

    /**
     * What Bennett saw on 0.3.5, reproduced: nothing of the account read at launch — the pin repository was built
     * by the first scroll to the sidebar's end, half an hour in — and when it was, the discovery pass read one page
     * and called itself complete, so the Projects whose records sit among the newest 200 came back and the older
     * ones never did.
     */
    @Test
    fun `0_3_5 as it ran - no Projects until the pin repository is touched, then only those the newest 200 records hold`() = runBlocking<Unit> {
        account.pagesServed = 1
        val agents = agents()
        val projects = projectsOf(agents)
        agents.refresh()
        // Launch: the public list alone, which has no word on Projects.
        assertThat(sections(agents, projects).firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }).isNull()
        assertThat(account.listCalls.get()).isEqualTo(0)

        // Half an hour later, the sidebar's end: the pin repository is built, and its round follows the last fetch.
        val pins = pinsOf(agents, projects, prime = false)
        awaitUntil("the late account round") { pins.state.value.lastSyncedAtMillis != null }
        awaitUntil("the one-page pass") { projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        awaitUntil("the roots the page named to be fetched") { setOf("bc-revenue", "bc-shipyard", "bc-mobile", "bc-jobs").all { agents.agent(it) != null } }
        val group = sections(agents, projects).firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()
        assertWithMessage("the Projects among the newest 200 records reappear").that(group.map { it.agent.name })
            .containsExactly("Revenue Scaling Pipeline", "Shipyard", "Cursor for Android", "Job & Bounty Research")
        assertWithMessage("the older ones stay missing").that(group.map { it.agent.id }).containsNoneOf("bc-noetic", "bc-murmur", "bc-meter")
        assertThat(projects.lastRootScan.value!!.pagesRead).isEqualTo(1)

        // The same account with the pages reachable: the next pass names every Project.
        account.pagesServed = null
        projects.discoverRoots(force = true)
        awaitUntil("every root's row after a whole pass") { expectedRoots.all { agents.agent(it) != null } }
        assertThat(sections(agents, projects).first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows.map { it.agent.name }).containsExactlyElementsIn(visibleRoots)
    }

    @Test
    fun `a first launch with nothing on disk reads the account on its own, not on a scroll or a pin`() = runBlocking<Unit> {
        val agents = agents()
        val projects = projectsOf(agents)
        val pins = pinsOf(agents, projects)
        agents.refresh()
        awaitUntil("the account round cued by the fetch") { pins.state.value.lastSyncedAtMillis != null }
        awaitUntil("the discovery pass") { projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        awaitUntil("the memberships") { projects.memberCounts.first().keys.containsAll(expectedRoots - "bc-retro") }
        awaitUntil("the roots' rows") { expectedRoots.all { agents.agent(it) != null } }
        assertInvariants("first launch", agents, projects)
        assertThat(account.listCalls.get()).isEqualTo(1)
        // The first page of the list holds twenty rows; the Projects group holds every Project all the same.
        assertThat(agents.state.value.agents.size).isLessThan(chats.size)
    }

    @Test
    fun `a coordinator's row is a root by its record whatever its source, and a child's by its parent`() {
        val root = Agent(
            id = "bc-x", name = "X", lifecycle = AgentLifecycle.IDLE, runStatus = RunStatus.FINISHED, envType = EnvType.CLOUD, envName = null, url = "",
            createdAtMillis = 1, updatedAtMillis = 1, latestRunId = null, repoUrl = null, startingRef = null,
            source = AgentSource.CLOUD_META_AGENT, isProject = true,
        )
        assertThat(root.scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(root.copy(isProject = false).scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(root.copy(isProject = false, source = null).scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(root.copy(parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)).scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(root.copy(isProject = false, source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD).scope).isEqualTo(AgentScope.PROJECT_CHILD)
        // 0.3.4's reading of a meta agent as a child of nobody is re-read as the root it is.
        assertThat(root.copy(knownScope = AgentScope.PROJECT_CHILD, scopeSignal = LineageSignal.HIDDEN_SOURCE).scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(AgentScope.of(isProject = true, parent = null, source = AgentSource.AS_SUBAGENT_FROM_CLOUD)).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(AgentScope.of(isProject = false, parent = null, source = AgentSource.CLOUD_META_AGENT)).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(AgentScope.of(isProject = false, parent = null, source = AgentSource.AS_SUBAGENT_FROM_CLOUD)).isEqualTo(AgentScope.PROJECT_CHILD)
    }

    @Test
    fun `nothing counts as running or notifies for a Project's chats, by the indicator too`() = runBlocking<Unit> {
        val agents = agents()
        val projects = projectsOf(agents)
        pinsOf(agents, projects)
        agents.refresh()
        awaitUntil("the discovery pass") { projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        awaitUntil("the memberships") { projects.memberCounts.first().keys.containsAll(expectedRoots - "bc-retro") }
        val rows = agents.state.value.agents.map { AgentListOrganizer.toRow(it, LocalAgentState(), 1_800_000_100_000L) }
        val counted = rows.filter { it.indicator == AgentIndicator.Running && !it.agent.isProjectScopedByEvidence }.map { it.agent.id }
        assertThat(counted.filter { byId[it]?.projectScoped == true }).isEmpty()
        assertThat(counted).isNotEmpty()
    }
}
