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
import com.cursorforandroid.data.api.RecordFields
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
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.KnownRoot
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.ProjectNotificationPrefs
import com.cursorforandroid.domain.LiveRunning
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
        /** A cloud subagent of an ordinary chat: hangs off it, and makes no Project of it. */
        val subagentOf: String? = null,
        /** The record carries `projectMetadata: {}` — present and empty: a Project by the desktop's predicate, whatever else it lacks. */
        val emptyMetadata: Boolean = false,
        /** A coordinator `ListWorkersForManager` answers for, whatever its record carries. */
        val coordinator: Boolean = false,
    ) {
        val root: String? get() = manager ?: adoptedBy ?: sideChatOf
        /** The record carries `project_metadata` at all: the desktop's `isProject`. */
        val metadataPresent: Boolean get() = isProject || emptyMetadata
        /** The desktop's `subagentParentId`: the one parent link, whatever the parent is. */
        val desktopParent: String? get() = subagentOf ?: sideChatOf ?: manager ?: adoptedBy
        /** The desktop's `PJr`: a child, drawn under its parent's row and never at the top level. */
        val desktopChild: Boolean get() = desktopParent != null
        /** The desktop's `kf`: a top-level chat the record flags — the Projects section. */
        val desktopProject: Boolean get() = !desktopChild && metadataPresent
        /** A Project's coordinator or a chat inside one — what the Project's own view and the notification rule are about. */
        val projectScoped: Boolean get() = desktopProject || root != null
        /** A chat of the account's own: a top-level chat the record does not flag (a coordinator without the flag is one, as it is in the Agents Window). */
        val own: Boolean get() = !desktopChild && !metadataPresent
        /**
         * The record as the app reads it (see `BackgroundComposerApi.snapshot`), the desktop's predicate: the flag is
         * `project_metadata` present on a record with no parent; `startedAsNewProject` flags nothing.
         */
        fun snapshot(): ComposerSnapshot = ComposerSnapshot(
            id = id,
            name = name,
            archived = archived,
            isProject = metadataPresent,
            projectAppearance = appearance?.takeIf { isProject && root == null },
            record = RecordFields(
                projectMetadata = when {
                    !metadataPresent -> null
                    appearance != null && isProject -> """{"appearance":{"icon":"${appearance.icon}","colorId":"${appearance.colorId}"}}"""
                    else -> "{}"
                },
                managerAgentId = manager,
                subagentParentId = subagentOf,
                sideChatParentId = sideChatOf,
                startedAsNewProject = newProjectFlag,
                source = source?.name,
            ),
            parent = manager?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) }
                ?: sideChatOf?.let { AgentParent(it, AgentParentKind.SIDE_CHAT) }
                ?: subagentOf?.let { AgentParent(it, AgentParentKind.SUBAGENT) },
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
        override suspend fun children(parentId: String): List<ComposerSnapshot> = chats.filter { it.sideChatOf == parentId || it.subagentOf == parentId }.map { it.snapshot() }
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
                coordinator = true,
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
        // Bennett's confirmed false positive: pinned, spawned cloud subagents, carries `startedAsNewProject` and no
        // `project_metadata` — the desktop draws it as a chat; 0.3.6–0.3.7 promoted it on the flag it never reads.
        all += Chat("bc-market", "Market Opportunities", now - 26 * 3_600_000L, source = AgentSource.SLACK, newProjectFlag = true)
        // Chats that are no Projects however they look: three started as cloud meta agents (no Project flag, no
        // workers), one of them running; two ordinary chats that spawned cloud subagents, whose records name them.
        all += Chat("bc-meta-1", "Meta agent chat one", now - 40 * 60_000L, running = true, source = AgentSource.CLOUD_META_AGENT)
        all += Chat("bc-meta-2", "Meta agent chat two", now - 9 * 3_600_000L, source = AgentSource.CLOUD_META_AGENT)
        all += Chat("bc-meta-3", "Meta agent chat three", now - 300 * 3_600_000L, archived = true, source = AgentSource.CLOUD_META_AGENT)
        all += Chat("bc-spawner-1", "Refactor with subagents", now - 50 * 60_000L, running = true, source = AgentSource.EDITOR)
        all += Chat("bc-spawner-1-sub1", "Subagent: tests", now - 45 * 60_000L, running = true, source = AgentSource.AS_SUBAGENT_FROM_CLOUD, subagentOf = "bc-spawner-1")
        all += Chat("bc-spawner-1-sub2", "Subagent: docs", now - 44 * 60_000L, source = AgentSource.AS_SUBAGENT_FROM_CLOUD, subagentOf = "bc-spawner-1")
        all += Chat("bc-spawner-2", "Research with a subagent", now - 250 * 3_600_000L, source = AgentSource.EDITOR)
        all += Chat("bc-spawner-2-sub1", "Subagent: sources", now - 249 * 3_600_000L, source = AgentSource.AS_SUBAGENT_FROM_CLOUD, subagentOf = "bc-spawner-2")
        // The pinned chat's cloud subagents; another chat with the new-project flag and nothing else; and a chat
        // whose record carries `project_metadata: {}` — by the desktop's predicate a Project with no members, drawn
        // as one there and so here.
        all += Chat("bc-market-sub1", "Subagent: market sizing", now - 25 * 3_600_000L, source = AgentSource.AS_SUBAGENT_FROM_CLOUD, subagentOf = "bc-market")
        all += Chat("bc-market-sub2", "Subagent: competitors", now - 24 * 3_600_000L, running = true, source = AgentSource.AS_SUBAGENT_FROM_CLOUD, subagentOf = "bc-market")
        all += Chat("bc-newflag", "Started as new project, no look", now - 7 * 3_600_000L, source = AgentSource.EDITOR, newProjectFlag = true)
        all += Chat("bc-emptymeta", "Empty project metadata", now - 8 * 3_600_000L, source = AgentSource.EDITOR, emptyMetadata = true)
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

    /**
     * The status scan reads twenty pages of twenty: the whole account, so the running set is the server's. The rows it
     * brings bare are asked for their record by id, as the graph asks the account (Extended mode only).
     */
    private fun agents() = AgentRepository(
        session, prefs, AttachmentStore(context), cache, scope, persistDelayMs = 1, capabilities = capabilities, runningScanPages = 20,
        recordOf = { id -> if (extended) account.record(id) else null },
    )

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

    /**
     * The Projects, by the desktop's `kf` alone: the six whose records carry `project_metadata`, the archived one, and
     * the `{}`-metadata chat. Codex Meter is none — its record has only `startedAsNewProject`, which the desktop never
     * reads — so it is a chat of the account's own with its two workers nested under its row, as the Agents Window draws it.
     */
    private val expectedRoots get() = projects.filterNot { it.newProjectFlagOnly }.map { it.id }.toSet() + "bc-emptymeta"
    private val visibleRoots get() = projects.filterNot { it.archived || it.newProjectFlagOnly }.map { it.name } + "Empty project metadata"

    /** The state 0.3.4 left on disk after one account round: the roots read as children by their source, the placements, the sources. */
    @Suppress("DEPRECATION")
    private suspend fun write034State() {
        val rows = chats.sortedByDescending { it.activityMillis }.take(60).map { c ->
            Agent(
                id = c.id, name = c.name, lifecycle = if (c.archived) AgentLifecycle.ARCHIVED else if (c.running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
                runStatus = if (c.running) RunStatus.RUNNING else RunStatus.FINISHED, envType = if (c.machine) EnvType.MACHINE else EnvType.CLOUD, envName = null,
                url = "https://cursor.com/agents/${c.id}", createdAtMillis = c.activityMillis, updatedAtMillis = c.activityMillis, latestRunId = "run-${c.id}",
                repoUrl = null, startingRef = null, source = c.source, isProject = c.isProject, projectAppearance = c.appearance,
                parent = c.manager?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) } ?: c.sideChatOf?.let { AgentParent(it, AgentParentKind.SIDE_CHAT) },
                // 0.3.4 wrote no record fields with its rows; a coordinator's row was placed by its source then.
                scopeSignal = when {
                    c.root != null -> LineageSignal.ACCOUNT_RECORD
                    c.isProject -> LineageSignal.ACCOUNT_RECORD
                    c.source == AgentSource.CLOUD_META_AGENT -> LineageSignal.HIDDEN_SOURCE
                    else -> null
                },
            )
        }
        // What 0.3.6 added on top: the meta-agent chats dressed as Projects — rows flagged, and registry entries
        // admitted on the source alone (no evidence fields yet) — which the first pass must take back.
        val poisoned = rows.map { row ->
            if (byId[row.id]?.source == AgentSource.CLOUD_META_AGENT && byId[row.id]?.projectScoped == false) row.copy(isProject = true, scopeSignal = LineageSignal.ACCOUNT_RECORD) else row
        }
        val held = rows.mapTo(HashSet()) { it.id }
        val lineage = CachedLineage(
            placements = chats.filter { it.id !in held && (it.manager != null || it.sideChatOf != null) }.take(100).map { c ->
                CachedPlacement(c.id, c.manager ?: c.sideChatOf, if (c.manager != null) AgentParentKind.PROJECT_WORKER else AgentParentKind.SIDE_CHAT, LineageSignal.ACCOUNT_RECORD)
            } + projects.map { CachedPlacement(it.id, null, null, LineageSignal.ACCOUNT_RECORD) } + listOf("bc-meta-1", "bc-meta-2").map { CachedPlacement(it, null, null, LineageSignal.ACCOUNT_RECORD) },
            sources = chats.filter { it.id !in held && it.source != null && (it.source == AgentSource.CLOUD_META_AGENT || it.source == AgentSource.AS_SIDE_CHAT_FROM_CLOUD) }.associate { it.id to it.source!! },
            hintRefused = emptySet(),
            roots = listOf("bc-meta-1", "bc-meta-2").map { KnownRoot(it, byId.getValue(it).name, signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = 1L) },
        )
        cache.write(poisoned, lineage = lineage)
        prefs.setPinnedIds(setOf("bc-codex", "bc-market"))
    }

    private fun assertInvariants(label: String, agents: AgentRepository, projects: ProjectRepository?) {
        val sections = sections(agents, projects)
        val group = sections.firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()
        assertWithMessage("$label: every Project renders").that(group.map { it.agent.name }).containsExactlyElementsIn(visibleRoots)
        assertWithMessage("$label: the archived Project waits behind the filter").that(group.map { it.agent.id }).doesNotContain("bc-retro")
        val primary = sections.filterNot { it.key == AgentListOrganizer.PROJECTS_KEY }.flatMap { it.rows }
        val leaked = primary.filter { byId[it.agent.id]?.projectScoped == true }
        assertWithMessage("$label: nothing of a Project among the primary rows").that(leaked.map { it.agent.id }).isEmpty()
        assertWithMessage("$label: the pinned Remote Control chat and the pinned chat are listed")
            .that(sections.firstOrNull { it.key == AgentListOrganizer.PINNED_KEY }?.rows?.map { it.agent.id }.orEmpty()).containsExactly("bc-codex", "bc-market")
        val loadedOwn = agents.state.value.agents.filter { byId[it.id]?.own == true }
        assertWithMessage("$label: no chat of the account's own is read as a Project's").that(loadedOwn.filter { it.isProjectScoped }.map { it.id }).isEmpty()
        // A root takes the record's word alone: the registry names the Projects and nothing else — not a chat
        // started as a meta agent, not one that spawned subagents, not a coordinator without the flag, not an
        // archived chat of the account's own.
        assertWithMessage("$label: the registry holds the Projects and nothing else").that(agents.knownRoots.value.map { it.id })
            .containsNoneOf("bc-meta-1", "bc-meta-2", "bc-meta-3", "bc-spawner-1", "bc-spawner-2", "bc-market", "bc-newflag", "bc-meter")
        assertThat(agents.knownRoots.value.all { it.isEvidenced }).isTrue()
        // A pinned chat that spawned subagents and carries startedAsNewProject is a pinned chat, its subagents under it.
        agents.agent("bc-market")?.let { assertWithMessage("$label: Market Opportunities is a chat of the account's own").that(it.scope).isEqualTo(AgentScope.PRIMARY) }
        agents.agent("bc-newflag")?.let { assertWithMessage("$label: startedAsNewProject alone flags nothing").that(it.scope).isEqualTo(AgentScope.PRIMARY) }
        agents.agent("bc-meter")?.let { assertWithMessage("$label: a coordinator without project_metadata is a chat of the account's own (kf)").that(it.scope).isEqualTo(AgentScope.PRIMARY) }
        // `project_metadata: {}` is the desktop's flag: a Project with no members, drawn as the desktop draws it.
        agents.agent("bc-emptymeta")?.let { assertWithMessage("$label: an empty project_metadata is the desktop's flag").that(it.scope).isEqualTo(AgentScope.PROJECT_ROOT) }
        assertThat(primary.map { it.agent.id }).containsNoneOf("bc-market-sub1", "bc-market-sub2")
        // The evidence string names exactly what admitted each root, and the record's raw fields ride with it.
        agents.knownRoots.value.forEach { root ->
            assertWithMessage("$label: ${root.id} evidence").that(root.evidence).matches("kf: record project_metadata=\\S+, no subagentParentId( \\+ .*)?")
            assertWithMessage("$label: ${root.id} record").that(root.record?.projectMetadata).isNotNull()
        }
        agents.knownRoots.value.firstOrNull { it.id == "bc-emptymeta" }?.let { assertThat(it.record?.describe()).startsWith("project_metadata={} manager_agent_id=- cloud_subagent_parent=- side_chat_parent=- started_as_new_project=false") }
        agents.agent("bc-meta-1")?.let { assertWithMessage("$label: a meta agent chat is a chat of the account's own").that(it.scope).isEqualTo(AgentScope.PRIMARY) }
        agents.agent("bc-spawner-1")?.let { assertWithMessage("$label: a chat with subagents is a chat of the account's own").that(it.scope).isEqualTo(AgentScope.PRIMARY) }
        // Its subagents hang off it, under its own row, and never among the primary rows or the Projects.
        val spawner = primary.firstOrNull { it.agent.id == "bc-spawner-1" }
        if (spawner != null && agents.agent("bc-spawner-1-sub1") != null) {
            assertWithMessage("$label: a chat's subagents sit under it").that(spawner.children.map { it.agent.id }).contains("bc-spawner-1-sub1")
        }
        assertThat(primary.map { it.agent.id }).containsNoneOf("bc-spawner-1-sub1", "bc-spawner-1-sub2", "bc-spawner-2-sub1")
        assertDesktopTopLevel(label, agents, sections)
    }

    /**
     * The desktop Agents Window's composition of this account, row for row (see `AgentsWindowList`): the top-level
     * set is exactly the loaded chats without a parent link — `subagentParentId` empty — Projects (`kf`) in the
     * Projects section, the pinned ones in Pinned, the rest in the time sections; every loaded child is nested under
     * its parent's row when the parent's row is loaded and drawn nowhere else; a coordinator without the flag
     * (Codex Meter, a Multitask-style chat) is an ordinary top-level row with its workers under it.
     */
    private fun assertDesktopTopLevel(label: String, agents: AgentRepository, sections: List<com.cursorforandroid.domain.AgentSection>) {
        val loaded = agents.state.value.agents.associateBy { it.id }
        val topLevelRows = sections.flatMap { it.rows }
        val topLevelIds = topLevelRows.map { it.agent.id }.toSet()
        val expectedTopLevel = loaded.values.filter { row -> byId[row.id]?.let { c -> !c.desktopChild && !c.archived } ?: !row.isArchived }.map { it.id }.toSet()
        assertWithMessage("$label: the top-level set is the desktop's (mQa: every loaded row without a parent link, and no other)")
            .that(topLevelIds.filter { it in loaded }).containsExactlyElementsIn(expectedTopLevel)
        val expectedProjects = expectedTopLevel.filter { byId.getValue(it).desktopProject }.toSet()
        val projectIds = sections.firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows?.map { it.agent.id }?.filter { it in loaded }.orEmpty()
        assertWithMessage("$label: the Projects section is kf over the top level").that(projectIds).containsExactlyElementsIn(expectedProjects)
        // Every loaded child sits under its parent's row (any parent: a Project, Codex Meter, Market Opportunities,
        // a chat with subagents), never at the top level; a child whose parent's row is not loaded is not drawn.
        fun descendants(rows: List<com.cursorforandroid.domain.AgentRow>): List<com.cursorforandroid.domain.AgentRow> = rows.flatMap { listOf(it) + descendants(it.children) }
        val drawn = descendants(topLevelRows)
        loaded.values.filter { byId[it.id]?.desktopChild == true }.forEach { child ->
            val chat = byId.getValue(child.id)
            assertWithMessage("$label: ${child.id} is a child (PJr)").that(topLevelIds).doesNotContain(child.id)
            val parentRow = drawn.firstOrNull { it.agent.id == chat.desktopParent }
            if (parentRow != null && !chat.archived) {
                assertWithMessage("$label: ${child.id} is nested under ${chat.desktopParent}").that(parentRow.children.map { it.agent.id }).contains(child.id)
            } else {
                assertWithMessage("$label: ${child.id} is not drawn without its parent's row").that(drawn.map { it.agent.id }).doesNotContain(child.id)
            }
        }
        // The rule the export prints for each row is the desktop's, and names the field the parent link came from.
        loaded.values.forEach { row ->
            val rule = com.cursorforandroid.domain.AgentsWindowList.place(row, loaded.keys, setOf("bc-codex", "bc-market")).rule
            val chat = byId.getValue(row.id)
            when {
                chat.desktopChild -> assertWithMessage("$label: rule for ${row.id}").that(rule).startsWith("PJr child of")
                chat.desktopProject -> assertWithMessage("$label: rule for ${row.id}").that(rule).startsWith("kf top-level Project")
                else -> assertWithMessage("$label: rule for ${row.id}").that(rule).startsWith("mQa top-level")
            }
            if (chat.manager != null) assertThat(rule).contains("via managerAgentId")
            if (chat.subagentOf != null) assertThat(rule).contains("via cloudSubagentParent.parentAgentId")
            if (chat.sideChatOf != null) assertThat(rule).contains("via sideChatInfo.parentBcId")
            if (chat.adoptedBy != null && row.record != null) assertThat(rule).contains("via ListWorkersForManager")
        }
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
        // From the disk alone, before any network: nothing an older build flagged is trusted (0.3.6 set the flag on
        // `startedAsNewProject`), so the group waits for the account's word — which the first fetch reads before it
        // publishes its page; none of the rows is hidden by its source meanwhile.
        val fromDisk = sections(agents, projects).firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty().map { it.agent.id }
        assertWithMessage("no root an older build flagged is trusted from the disk").that(fromDisk).isEmpty()
        assertThat(agents.state.value.agents.none { byId[it.id]?.isProject == true && it.isProjectChild }).isTrue()
        // 0.3.6's false Projects do not come back from the disk: an entry admitted on no strict evidence is dropped
        // on restore, and their rows' bare flag with it.
        assertThat(agents.knownRoots.value.map { it.id }).containsNoneOf("bc-meta-1", "bc-meta-2")
        assertThat(agents.agent("bc-meta-1")?.isProject ?: false).isFalse()

        agents.refresh()
        // The account round is cued by the completed fetch and retried past the two failures; the discovery pass
        // reads its pages, loses one, keeps what it read and finishes on the retry.
        awaitUntil("the account round to go through") { account.listCalls.get() >= 3 && pins.state.value.lastSyncedAtMillis != null }
        awaitUntil("the discovery pass to finish") { projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        val scan = projects.lastRootScan.value!!
        assertThat(scan.attempts).isAtLeast(2)
        assertThat(scan.pagesRead).isEqualTo(2)
        assertThat(scan.complete).isTrue()
        // The flagged Projects are in the registry from the pass — the record's `project_metadata`, the desktop's
        // `kf`; Codex Meter, whose record has only `startedAsNewProject`, is none, its two workers or not.
        assertThat(agents.knownRoots.value.map { it.id }).containsExactlyElementsIn(expectedRoots)
        assertThat(agents.knownRoots.value.map { it.id }).doesNotContain("bc-meter")
        // The memberships place the adopted workers, and the rows of the roots the pages did not hold are fetched by id.
        awaitUntil("the memberships to be read") { projects.memberCounts.first().keys.containsAll(expectedRoots - "bc-retro") }
        // Noetic's record carries `project_metadata` without an appearance: the flag all the same, the desktop's way.
        assertThat(agents.knownRoots.value.first { it.id == "bc-noetic" }.evidence).startsWith("kf: record project_metadata={}")
        assertThat(agents.knownRoots.value.first { it.id == "bc-shipyard" }.evidence).startsWith("""kf: record project_metadata={"appearance":{"icon":"logo-github","colorId":"blue"}}""")
        awaitUntil("every root's row to be fetched") { expectedRoots.all { agents.agent(it) != null } }
        // Every row the running scan brought bare — a worker of an older Project, beyond the account's window — has
        // been asked for its record, so the desktop's predicates had the record's fields to read.
        awaitUntil("every loaded row to have its record") { agents.state.value.agents.all { it.record != null } }
        assertInvariants("after the first launch", agents, projects)
        // The counts are the account's: 31, 8, 115, 28, 6, 17 — and none for the `{}` Project.
        val group = sections(agents, projects).first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows
        assertThat(group.associate { it.agent.name to it.shownCount }).containsAtLeast("Shipyard", 31, "Revenue Scaling Pipeline", 115, "Noetic", 28, "Empty project metadata", 0)
        // Codex Meter is an ordinary row with its two workers under it — the desktop's row-with-children for a
        // coordinator the record does not flag.
        awaitUntil("Codex Meter's workers to be loaded") { agents.agent("bc-meter-m1") != null && agents.agent("bc-meter-m2") != null }
        val meter = sections(agents, projects).filterNot { it.key == AgentListOrganizer.PROJECTS_KEY }.flatMap { it.rows }.first { it.agent.id == "bc-meter" }
        assertThat(meter.children.map { it.agent.id }).containsExactly("bc-meter-m1", "bc-meter-m2")
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
            .containsExactly("Revenue Scaling Pipeline", "Shipyard", "Cursor for Android", "Job & Bounty Research", "Empty project metadata")
        assertWithMessage("the older ones stay missing").that(group.map { it.agent.id }).containsNoneOf("bc-noetic", "bc-murmur")
        assertThat(projects.lastRootScan.value!!.pagesRead).isEqualTo(1)

        // The same account with the pages reachable: the next pass names every flagged Project, and the membership
        // pass that follows admits the ones known by their workers alone.
        account.pagesServed = null
        projects.discoverRoots(force = true)
        projects.syncLineage()
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
    fun `a coordinator's row is a root by its record alone, a child's by its parent link, never by its source`() {
        val root = Agent(
            id = "bc-x", name = "X", lifecycle = AgentLifecycle.IDLE, runStatus = RunStatus.FINISHED, envType = EnvType.CLOUD, envName = null, url = "",
            createdAtMillis = 1, updatedAtMillis = 1, latestRunId = null, repoUrl = null, startingRef = null,
            source = AgentSource.CLOUD_META_AGENT, isProject = true,
        )
        assertThat(root.scope).isEqualTo(AgentScope.PROJECT_ROOT)
        // A source says how a chat was started, never what it is: a meta agent without the flag is a chat of its own,
        // and so is a side chat whose record names no parent (the desktop reads the parent link alone).
        assertThat(root.copy(isProject = false).scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(root.copy(isProject = false, source = null).scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(root.copy(isProject = false, source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD).scope).isEqualTo(AgentScope.PRIMARY)
        // A parent link makes a child of anything, a flagged Project included (kf needs no subagentParentId).
        assertThat(root.copy(parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)).scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(root.copy(parent = AgentParent("bc-p", AgentParentKind.SIDE_CHAT)).looksLikeProject).isFalse()
        assertThat(AgentScope.of(isProject = true, parent = AgentParent("bc-p", AgentParentKind.SUBAGENT))).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(AgentScope.of(isProject = false, parent = null)).isEqualTo(AgentScope.PRIMARY)
        assertThat(AgentScope.of(isProject = true, parent = null)).isEqualTo(AgentScope.PROJECT_ROOT)
    }

    /**
     * The live notification's count is every running agent — the Projects' coordinators, workers and side chats
     * included — from the status scan reconciled with the rows, while the cards for the Projects' chats stay off:
     * the same in Extended mode and, from the registry and rows kept, with it off.
     */
    @Test
    fun `the live count is every running agent while the Projects' chats keep their cards off, in both modes`() = runBlocking<Unit> {
        var agents = agents()
        var projects = projectsOf(agents)
        pinsOf(agents, projects)
        agents.refresh()
        awaitUntil("the discovery pass") { projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        awaitUntil("the memberships") { projects.memberCounts.first().keys.containsAll(expectedRoots - "bc-retro") }
        awaitUntil("the roots' rows") { expectedRoots.all { agents.agent(it) != null } }
        assertLiveRule("Extended mode", agents)

        extended = false
        awaitUntil("the registry on disk") { cache.readLineage()?.roots?.size == expectedRoots.size }
        agents.reset()
        agents = agents()
        projects = projectsOf(agents)
        pinsOf(agents, projects)
        agents.restoreFromCache()
        agents.refresh()
        projects.discoverRoots()
        awaitUntil("the roots' rows in default mode") { expectedRoots.all { agents.agent(it) != null } }
        assertLiveRule("default mode", agents)
    }

    private fun assertLiveRule(label: String, agents: AgentRepository) {
        val prefs = ProjectNotificationPrefs.DEFAULT
        val rows = agents.state.value.agents
        val scan = agents.runningScan.value
        assertWithMessage("$label: the status scan has run").that(scan.hasScanned).isTrue()
        val live = LiveRunning.ids(rows, scan, prefs)
        val serverRunningAll = chats.filter { it.running }.map { it.id }.toSet()
        val serverRunning = chats.filter { it.running && !it.archived }.map { it.id }.toSet()
        // Every agent the server calls running is in the live set — the Projects' and the account's own alike, and
        // nothing the server does not; the set is the scan's, not the loaded pages': the pages hold twenty rows,
        // the account runs many more.
        assertWithMessage("$label: the live count is the server's running set").that(live).containsAtLeastElementsIn(serverRunning)
        assertWithMessage("$label: nothing counts that the server does not call running").that(live - serverRunningAll).isEmpty()
        assertThat(live.size).isGreaterThan(rows.count { it.isRunning && it.id in live } / 2)
        val projectRunning = serverRunning.filter { byId.getValue(it).projectScoped }
        assertThat(projectRunning).isNotEmpty()
        assertWithMessage("$label: the Projects' running agents are counted").that(live).containsAtLeastElementsIn(projectRunning)
        // Their cards stay off, the account's own chats' on; the switches turn each half on.
        val loaded = rows.filter { it.id in live }
        val projectRows = loaded.filter { byId.getValue(it.id).projectScoped }
        assertThat(projectRows).isNotEmpty()
        assertWithMessage("$label: no card for a Project's chat").that(projectRows.filter { prefs.announces(it) }.map { it.id }).isEmpty()
        assertWithMessage("$label: a card for the account's own chats").that(loaded.filter { byId.getValue(it.id).own }.all { prefs.announces(it) }).isTrue()
        val coordinators = ProjectNotificationPrefs(notifyProjectCoordinators = true)
        assertThat(projectRows.filter { coordinators.announces(it) }.map { it.id }).containsExactlyElementsIn(projectRows.filter { it.isProjectRoot }.map { it.id })
        val members = ProjectNotificationPrefs(notifyProjectMembers = true)
        assertThat(projectRows.filter { members.announces(it) }.map { it.id }).containsExactlyElementsIn(projectRows.filter { it.isProjectChild }.map { it.id })
        // With the count switch off, the live set is the account's own running chats alone.
        val ownOnly = LiveRunning.ids(rows, scan, ProjectNotificationPrefs(countProjectAgentsInLive = false))
        assertThat(ownOnly.filter { byId[it]?.projectScoped == true }).isEmpty()
        assertThat(ownOnly).containsAtLeastElementsIn(loaded.filter { byId.getValue(it.id).own }.map { it.id })
    }
}
