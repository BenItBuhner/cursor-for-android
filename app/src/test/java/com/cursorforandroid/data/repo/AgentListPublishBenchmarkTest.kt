package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.PinnedIds
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.RootScan
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ListAgentsResponseDto
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
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * How many times one refresh publishes the agent list, at the scale of an account with hundreds of agents working:
 * 200 and 500 agents, eight Projects — one of them with 130 children — and 60 to 120 of the agents running, Extended
 * mode on, the account round and the Projects' memberships wired as the graph wires them. Every publication that
 * changes the list is a pass of every collector of `AgentRepository.state` — the sidebar's organizer, an open
 * Project's view, the chat screens' lookups — so it is counted here, per Quick refresh (the sidebar's poll), per Full
 * refresh (a pull) and per Project poll (the list's quick look and the open Project's memberships), with the
 * classification passes behind them, the organizer passes and Project view emissions they cause, and the CPU they
 * took. The counts are the budget; the times are printed for the record.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentListPublishBenchmarkTest {

    @get:Rule val folder = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val fake = FakeCursorApi()
    private lateinit var context: Context
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(true) }

    private class Chat(
        val id: String,
        var activityMillis: Long,
        var running: Boolean,
        val isProject: Boolean = false,
        val manager: String? = null,
        val sideChatOf: String? = null,
    ) {
        fun snapshot(): ComposerSnapshot = ComposerSnapshot(
            id = id,
            name = id,
            archived = false,
            isProject = isProject,
            projectAppearance = ProjectAppearance("lightning", "blue").takeIf { isProject },
            record = RecordFields(projectMetadata = if (isProject) "{}" else null, managerAgentId = manager, sideChatParentId = sideChatOf),
            parent = manager?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) } ?: sideChatOf?.let { AgentParent(it, AgentParentKind.SIDE_CHAT) },
            status = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
            activityAtMillis = activityMillis,
            createdAtMillis = activityMillis,
        )
    }

    private val chats = ArrayList<Chat>()
    private val byId = ConcurrentHashMap<String, Chat>()
    private val memberships = HashMap<String, List<WorkerMembership>>()
    private val runSeq = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppClock.nowMillis = { now }
        fake.pageSize = 100
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /**
     * [total] agents: eight Project roots, the big one with 110 workers and 20 side chats, the other seven sharing
     * [otherMembers] between them, and the account's own chats making up the rest; [running] of them running, 40
     * of those inside the big Project. Newest first by activity, the running ones the newest.
     */
    private fun buildFleet(total: Int, otherMembers: Int, running: Int) {
        val roots = (0 until PROJECTS).map { "bc-project-$it" }
        val all = ArrayList<Chat>()
        var t = now - 60_000L
        fun next(): Long { t -= 90_000L; return t }
        roots.forEach { all += Chat(it, next(), running = false, isProject = true) }
        repeat(110) { all += Chat("bc-big-w$it", next(), running = false, manager = roots[0]) }
        repeat(20) { all += Chat("bc-big-s$it", next(), running = false, sideChatOf = roots[0]) }
        repeat(otherMembers) { all += Chat("bc-p${1 + it % (PROJECTS - 1)}-w$it", next(), running = false, manager = roots[1 + it % (PROJECTS - 1)]) }
        var own = 0
        while (all.size < total) all += Chat("bc-own-${own++}", next(), running = false)
        // Forty inside the big Project, the rest spread over the others and the account's own chats.
        all.filter { it.manager == roots[0] }.take(40).forEach { it.running = true }
        all.filter { !it.isProject && it.manager != roots[0] && it.sideChatOf == null }.take(running - 40).forEach { it.running = true }
        chats.clear()
        chats += all
        all.forEach { c ->
            byId[c.id] = c
            val runId = "run-${c.id}-${runSeq.incrementAndGet()}"
            if (c.running) fake.addRunningAgent(c.id, c.id, runId, createdAt = iso(c.activityMillis)) else fake.addIdleAgent(c.id, c.id, runId, createdAt = iso(c.activityMillis))
        }
        roots.forEach { root -> memberships[root] = all.filter { it.manager == root }.map { WorkerMembership(it.id, root, WorkerSpawnKind.CREATED) } }
        check(chats.size == total && chats.count { it.running } == running) { "fleet ${chats.size} / ${chats.count { it.running }}" }
    }

    /** Chats just launched elsewhere: the public list and the account's record by id have them, the account's list not yet. */
    private val arriving = ArrayList<Chat>()
    private var arrived = 0

    /**
     * What happens on the account between two refreshes: [finishing] running agents end their turn, [starting] idle
     * ones start a new one, and each moves up the list as its activity does; the chats launched last time reach the
     * account's list, and [launching] more are launched — one of them a worker of the big Project — which the list
     * holds back until their records are read by id.
     */
    private fun churn(seed: Int, finishing: Int = 8, starting: Int = 4, launching: Int = 3) {
        val random = Random(seed)
        val at = now + seed * 60_000L
        chats += arriving
        arriving.clear()
        repeat(launching) { i ->
            val id = "bc-new-${arrived++}"
            val worker = i == 0
            val c = Chat(id, at, running = true, manager = if (worker) "bc-project-0" else null)
            arriving += c
            byId[id] = c
            if (worker) memberships["bc-project-0"] = memberships.getValue("bc-project-0") + WorkerMembership(id, "bc-project-0", WorkerSpawnKind.CREATED)
            fake.addRunningAgent(id, id, "run-$id-${runSeq.incrementAndGet()}", createdAt = iso(at))
        }
        val ending = chats.filter { it.running }.shuffled(random).take(finishing)
        val beginning = chats.filter { !it.running && !it.isProject }.shuffled(random).take(starting)
        ending.forEach { c ->
            val agent = fake.agents.getValue(c.id)
            fake.runs[agent.latestRunId!!] = fake.runs.getValue(agent.latestRunId!!).copy(status = "FINISHED", updatedAt = iso(at), durationMs = 60_000L, result = "Done.")
            fake.agents[c.id] = agent.copy(status = "IDLE", updatedAt = iso(at))
            fake.v0[c.id] = fake.v0.getValue(c.id).copy(status = "FINISHED")
            c.running = false
            c.activityMillis = at
        }
        beginning.forEach { c ->
            val runId = "run-${c.id}-${runSeq.incrementAndGet()}"
            fake.runs[runId] = RunDto(id = runId, agentId = c.id, status = "RUNNING", createdAt = iso(at), updatedAt = iso(at))
            fake.agents[c.id] = fake.agents.getValue(c.id).copy(status = "ACTIVE", updatedAt = iso(at), latestRunId = runId)
            fake.v0[c.id] = fake.v0.getValue(c.id).copy(status = "RUNNING")
            c.running = true
            c.activityMillis = at
        }
    }

    /** Every call takes its round trip; [lastEndNanos] is when the last one came back, for [Process.settle]. */
    private class Wire(private val latencyMs: Long) {
        val calls = AtomicInteger()
        val inFlight = AtomicInteger()
        val lastEndNanos = AtomicLong(System.nanoTime())
        suspend fun <T> call(block: suspend () -> T): T {
            calls.incrementAndGet()
            inFlight.incrementAndGet()
            try {
                delay(latencyMs)
                return block()
            } finally {
                inFlight.decrementAndGet()
                lastEndNanos.set(System.nanoTime())
            }
        }
    }

    private class WiredApi(private val delegate: CursorApi, private val wire: Wire) : CursorApi by delegate {
        override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean): ListAgentsResponseDto = wire.call { delegate.listAgents(limit, cursor, includeArchived) }
        override suspend fun listAgentsV0(limit: Int, cursor: String?): V0ListAgentsResponseDto = wire.call { delegate.listAgentsV0(limit, cursor) }
        override suspend fun getAgent(id: String): AgentDto = wire.call { delegate.getAgent(id) }
        override suspend fun getRun(id: String, runId: String): RunDto = wire.call { delegate.getRun(id, runId) }
    }

    /** The account service in miniature: its list 200 at a time, newest first, the memberships and children per root, a record by id. */
    private inner class Account(private val wire: Wire) : PinsApi, ProjectLineageApi {
        private fun ordered() = chats.sortedByDescending { it.activityMillis }
        private fun page(offset: Int): Pair<List<Chat>, Int?> {
            val all = ordered()
            val rows = all.drop(offset).take(ACCOUNT_PAGE)
            return rows to (offset + ACCOUNT_PAGE).takeIf { it < all.size }
        }
        private fun list(offset: Int): AccountList {
            val (rows, next) = page(offset)
            return AccountList(pinned = PinnedIds(emptySet(), loaded = offset == 0), pullRequests = emptyMap(), composers = rows.map { it.snapshot() }, nextCursor = next?.toString())
        }
        override suspend fun list(): AccountList = wire.call { list(0) }
        override suspend fun listMore(cursor: String): AccountList = wire.call { list(cursor.toInt()) }
        override suspend fun pin(ids: Collection<String>) = Unit
        override suspend fun unpin(ids: Collection<String>) = Unit
        override suspend fun workersForManager(managerId: String): List<WorkerMembership> = wire.call { memberships[managerId].orEmpty() }
        override suspend fun children(parentId: String): List<ComposerSnapshot> = wire.call { chats.filter { it.sideChatOf == parentId }.map { it.snapshot() } }
        override suspend fun record(id: String): ComposerSnapshot? = wire.call { byId[id]?.snapshot() }
        override suspend fun scanRoots(maxPages: Int): RootScan = scanRoots(maxPages, null)
        override suspend fun scanRoots(maxPages: Int, stopBelowActivityMillis: Long?): RootScan {
            val all = ArrayList<ComposerSnapshot>()
            var offset: Int? = 0
            var pages = 0
            while (offset != null && pages < maxPages) {
                val (rows, next) = wire.call { page(offset!!) }
                pages++
                all += rows.map { it.snapshot() }
                offset = next
            }
            return RootScan(all.filter { it.scope == AgentScope.PROJECT_ROOT }, all.filter { it.parent != null }, pages, offset == null, all.size, null, truncated = offset != null, snapshots = all)
        }
    }

    /** The graph's wiring on one process: the list, the account round and its hooks, the Projects. */
    private inner class Process(latencyMs: Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wire = Wire(latencyMs)
        val api = WiredApi(fake, wire)
        val account = Account(wire)
        val prefs = PreferencesStore(context)
        val session = SessionManager(SecureKeyStore(context), prefs, CursorBackend(api, FakeRunStreamer(), isDemo = false), CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        val agents = AgentRepository(
            session, prefs, AttachmentStore(context), AgentListCache(JsonDiskCache(folder.newFolder(), dispatcher = Dispatchers.Unconfined)), scope,
            persistDelayMs = 1, capabilities = capabilities, recordOf = { id -> account.record(id) },
        )
        val projects = ProjectRepository(session, agents, account, scope = scope, pollIntervalMs = 600_000, capabilities = capabilities, retryDelaysMs = listOf(50L, 50L, 50L)).also { it.watchList() }
        val pins = PinRepository(
            session, prefs, agents, account, scope = scope, capabilities = capabilities, retryDelaysMs = listOf(50L, 50L, 50L),
            onList = { list, token ->
                agents.applySources(list.sources, token)
                projects.scheduleRootDiscovery(list.composers.filter { it.scope == AgentScope.PROJECT_ROOT }.map { it.id })
            },
        ).also { pins -> agents.accountPrime = { pins.primeForFetch() } }

        fun end() = scope.cancel()

        /** Until nothing has been on the wire for a while and neither the list nor the account layer has work in flight. */
        suspend fun settle(quietMs: Long = 600) = withTimeout(120_000) {
            while (true) {
                delay(50)
                val quiet = (System.nanoTime() - wire.lastEndNanos.get()) / 1_000_000 >= quietMs && wire.inFlight.get() == 0
                if (quiet && !pins.state.value.isSyncing && !agents.state.value.isRefreshing && agents.pending.items.isEmpty() && !projects.syncingLineage.value &&
                    projects.lastRootScan.value?.status != RootScanRecord.Status.Running) return@withTimeout
            }
        }
    }

    /** What the collectors of the list were asked to do: the sidebar's organizer passes and an open Project's view emissions, with the CPU they took. */
    private class Watchers {
        val organizer = AtomicInteger()
        val organizerCpuNanos = AtomicLong()
        val projectView = AtomicInteger()
    }

    // Through reflection: the unit tests compile against android.jar, which has no java.lang.management.
    private val management = Class.forName("java.lang.management.ManagementFactory")
    private val threads: Any = management.getMethod("getThreadMXBean").invoke(null)!!
    private val threadCpu = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
    private val os: Any = management.getMethod("getOperatingSystemMXBean").invoke(null)!!
    private val processCpu = Class.forName("com.sun.management.OperatingSystemMXBean").getMethod("getProcessCpuTime")
    private fun threadCpuNanos(): Long = threadCpu.invoke(threads) as Long
    private fun processCpuNanos(): Long = processCpu.invoke(os) as Long

    /**
     * The collectors run unconfined: each sees every publication, in the publisher's own call, so what they count is
     * what the list asks of them rather than what one machine's scheduler happened to let them skip.
     */
    private fun watch(process: Process, projectId: String): Watchers {
        val w = Watchers()
        process.scope.launch(Dispatchers.Unconfined) {
            process.agents.state.collect { s ->
                if (!s.hasLoaded) return@collect
                val start = threadCpuNanos()
                val sections = AgentListOrganizer.organize(s.shownAgents, ListPreferences(), LocalAgentState(), nowMillis = now, knownRoots = process.agents.knownRoots.value)
                AgentListOrganizer.recentRows(sections)
                AgentListOrganizer.projectRows(sections)
                w.organizerCpuNanos.addAndGet(threadCpuNanos() - start)
                w.organizer.incrementAndGet()
            }
        }
        process.scope.launch(Dispatchers.Unconfined) { process.projects.view(projectId).collect { w.projectView.incrementAndGet() } }
        return w
    }

    private data class Phase(
        val fleet: String,
        val kind: String,
        val publishes: Int,
        val passes: Int,
        val organizer: Int,
        val projectView: Int,
        val organizerCpuMs: Long,
        val processCpuMs: Long,
        val calls: Int,
        val fingerprint: Int,
    ) {
        override fun toString() =
            "SCALE refresh fleet=$fleet publishes=$publishes organizer=$organizer projectView=$projectView kind=$kind classified=$passes " +
                "organizerCpu=${organizerCpuMs}ms processCpu=${processCpuMs}ms calls=$calls final=${Integer.toHexString(fingerprint)}"
    }

    /**
     * At most so many publications that changed the list, and classification passes behind them, per refresh of each
     * kind — the batched passes' numbers with room to spare. Before the batching a Quick refresh took 22 passes, a
     * Full one 24 to 27 and a Project poll 39, on either fleet: a pass per run record read, per record read by id and
     * per membership answer, changed or not.
     */
    private data class Budget(val publishes: Int, val passes: Int)

    private val budgets = mapOf(
        "Quick" to Budget(publishes = 6, passes = 8),
        "Full" to Budget(publishes = 10, passes = 14),
        "ProjectPoll" to Budget(publishes = 6, passes = 22),
    )

    private fun assertBudgets(phases: List<Phase>) {
        phases.forEach { phase ->
            val budget = budgets[phase.kind] ?: return@forEach
            assertWithMessage("${phase.fleet} ${phase.kind}: publications that changed the list").that(phase.publishes).isAtMost(budget.publishes)
            assertWithMessage("${phase.fleet} ${phase.kind}: classification passes").that(phase.passes).isAtMost(budget.passes)
            // Each publication that changed the list is one pass of the sidebar's organizer, and no more.
            assertWithMessage("${phase.fleet} ${phase.kind}: organizer passes").that(phase.organizer).isAtMost(phase.publishes)
        }
    }

    /** The list as the screens draw it, reduced to what a refresh settles: each row's run, place and look. */
    private fun fingerprint(process: Process): Int = process.agents.state.value.agents
        .sortedBy { it.id }
        .joinToString("\n") { "${it.id}|${it.runStatus}|${it.lifecycle}|${it.latestRunId}|${it.parent?.id}|${it.parent?.kind}|${it.isProject}|${it.record != null}|${it.activityAtMillis}|${it.updatedAtMillis}" }
        .hashCode()

    private suspend fun measure(process: Process, watchers: Watchers, fleet: String, kind: String, block: suspend () -> Unit): Phase {
        process.settle()
        val counts = process.agents.publishCounts
        val publishes = counts.changes.get()
        val passes = counts.passes.get()
        val organizer = watchers.organizer.get()
        val organizerCpu = watchers.organizerCpuNanos.get()
        val projectView = watchers.projectView.get()
        val calls = process.wire.calls.get()
        val cpu = processCpuNanos()
        block()
        process.settle()
        return Phase(
            fleet = fleet,
            kind = kind,
            publishes = counts.changes.get() - publishes,
            passes = counts.passes.get() - passes,
            organizer = watchers.organizer.get() - organizer,
            projectView = watchers.projectView.get() - projectView,
            organizerCpuMs = (watchers.organizerCpuNanos.get() - organizerCpu) / 1_000_000,
            processCpuMs = (processCpuNanos() - cpu) / 1_000_000,
            calls = process.wire.calls.get() - calls,
            fingerprint = fingerprint(process),
        ).also { println(it) }
    }

    private fun scenario(fleet: String, total: Int, otherMembers: Int, running: Int): List<Phase> = runBlocking {
        buildFleet(total, otherMembers, running)
        val process = Process(latencyMs = LATENCY_MS)
        try {
            val watchers = watch(process, "bc-project-0")
            val phases = ArrayList<Phase>()
            // The cold start: nothing on disk, a refresh the user sees, then the account round and the Projects behind it.
            phases += measure(process, watchers, fleet, "Cold") {
                process.agents.refresh(silent = false, depth = RefreshDepth.Full)
                // The reader scrolls to the end: every page loaded, so a Full refresh re-reads the whole fleet.
                repeat(total / 100 + 1) { if (process.agents.state.value.hasMore) process.agents.loadMore() }
            }
            repeat(ROUNDS) { round ->
                phases += measure(process, watchers, fleet, "Quick") {
                    churn(seed = 10 + round)
                    process.agents.refresh(silent = true, depth = RefreshDepth.Quick)
                }
                phases += measure(process, watchers, fleet, "Full") {
                    churn(seed = 20 + round)
                    process.agents.refresh(silent = true, depth = RefreshDepth.Full)
                }
                // An open Project's poll: the list's quick look, and the Project's memberships read again.
                phases += measure(process, watchers, fleet, "ProjectPoll") {
                    churn(seed = 30 + round)
                    process.agents.refresh(silent = true, depth = RefreshDepth.Quick)
                    process.projects.syncLineage(listOf("bc-project-0"), force = true)
                }
            }
            // A Quick refresh reads the first page only, so a turn that started deep in the list waits for a Full one;
            // and the launches have pushed the oldest rows past the pages the reader had loaded, so it scrolls again.
            phases += measure(process, watchers, fleet, "Settle") {
                process.agents.refresh(silent = true, depth = RefreshDepth.Full)
                repeat(3) { if (process.agents.state.value.hasMore) process.agents.loadMore() }
            }
            // What a refresh must still settle: every row's run as the server has it, every member under its Project.
            val rows = process.agents.state.value.agents.associateBy { it.id }
            val expected = chats + arriving
            val wrongRun = expected.filter { c -> rows[c.id]?.isRunning != c.running }.map { "${it.id} server=${it.running} row=${rows[it.id]?.runStatus}" }
            val loose = expected.filter { it.manager != null || it.sideChatOf != null }.filter { c -> rows[c.id]?.parent?.id != (c.manager ?: c.sideChatOf) }.map { it.id }
            assertWithMessage("rows whose run the refreshes left wrong").that(wrongRun.take(10)).isEmpty()
            assertWithMessage("members not under their Project").that(loose.take(10)).isEmpty()
            assertWithMessage("rows").that(rows.size).isEqualTo(expected.size)
            assertWithMessage("rows still held back").that(process.agents.state.value.awaitingPlacement).isEmpty()
            phases
        } finally {
            process.end()
        }
    }

    private fun report(phases: List<Phase>) {
        val text = phases.joinToString("\n")
        File(System.getProperty("user.dir"), "build/reports/agent-list-publish-benchmark.txt").apply { parentFile?.mkdirs() }.appendText(text + "\n")
    }

    @Test
    fun `two hundred agents, eight Projects, sixty running`() {
        val phases = scenario("S200", total = 200, otherMembers = 42, running = 60)
        report(phases)
        assertBudgets(phases)
    }

    @Test
    fun `five hundred agents, eight Projects, a hundred and twenty running`() {
        val phases = scenario("S500", total = 500, otherMembers = 210, running = 120)
        report(phases)
        assertBudgets(phases)
    }

    private companion object {
        const val PROJECTS = 8
        const val ACCOUNT_PAGE = 200
        const val LATENCY_MS = 10L
        const val ROUNDS = 2
    }
}
