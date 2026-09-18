package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.ApiThrottle
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.PinnedIds
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.RootScan
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.V0ListAgentsResponseDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RefreshStats
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Bennett's account in the shape of `AccountSimulationTest` — eight Projects of 2 to 115 members, a hundred and
 * twenty chats of the account's own, a record window of two hundred, Extended mode on — run through a pull-to-refresh
 * after a cold start, with every network call recorded and given the same latency, so what a refresh costs can be
 * counted: which calls, in what order, how long each stage holds the line, when the spinner is let go and when the
 * last call lands. The report is printed and written to the build directory for the PR body; the bounds asserted
 * below are what the refresh is held to from here on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RefreshBenchmarkTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** One recorded network call: what was asked, and when it went out and came back, in millis since the log began. */
    data class Call(val kind: String, val detail: String, val startMs: Long, val endMs: Long)

    /**
     * The network as a phone sees it. [latencyMs] draws one round trip; [failureRate] of the calls fail transiently
     * (a dropped connection, a 503); and each host refuses with a 429 and a `Retry-After` while more than its
     * [Limiter.cap] calls are in flight — the account service (api2) and the public API each with their own.
     */
    class CallLog(
        private val latencyMs: () -> Long,
        private val failureRate: Double = 0.0,
        private val api2Cap: Int = Int.MAX_VALUE,
        private val publicCap: Int = Int.MAX_VALUE,
        private val retryAfterMs: Long = 2_000L,
        seed: Int = 11,
    ) {
        constructor(latencyMs: Long) : this({ latencyMs })

        private val t0 = System.nanoTime()
        private val random = Random(seed)
        val calls = CopyOnWriteArrayList<Call>()
        val refused = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val peakApi2 = java.util.concurrent.atomic.AtomicInteger()
        val peakPublic = java.util.concurrent.atomic.AtomicInteger()
        private val api2 = Limiter(api2Cap, peakApi2)
        private val public = Limiter(publicCap, peakPublic)
        fun nowMs(): Long = (System.nanoTime() - t0) / 1_000_000

        class Limiter(val cap: Int, private val peak: java.util.concurrent.atomic.AtomicInteger) {
            val inFlight = java.util.concurrent.atomic.AtomicInteger()
            fun enter(): Boolean {
                val n = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, n) }
                return n <= cap
            }
            fun leave() { inFlight.decrementAndGet() }
        }

        /** A public API call (api.cursor.com): the same weather; a refusal is a [CursorApiException] 429. */
        suspend fun <T> record(kind: String, detail: String = "", block: suspend () -> T): T = call(kind, detail, public, block) { CursorApiException(429, "rate_limited", "Too many requests.") }

        /** An account service call (api2): a refusal is a [ConnectRpcException] 429 naming when to come back. */
        suspend fun <T> api2(kind: String, detail: String = "", block: suspend () -> T): T = call(kind, detail, api2, block) { ConnectRpcException(429, "resource_exhausted", "Rate limited.", retryAfterMillis = retryAfterMs) }

        private suspend fun <T> call(kind: String, detail: String, limiter: Limiter, block: suspend () -> T, refusal: () -> Throwable): T {
            val start = nowMs()
            val admitted = limiter.enter()
            try {
                // A refused call is answered fast, as a 429 is; an admitted one takes its round trip.
                if (!admitted) {
                    delay(latencyMs() / 4)
                    refused.incrementAndGet()
                    throw refusal()
                }
                delay(latencyMs())
                if (failureRate > 0 && synchronized(random) { random.nextDouble() } < failureRate) {
                    failed.incrementAndGet()
                    throw java.io.IOException("connection reset")
                }
                return block()
            } finally {
                limiter.leave()
                calls += Call(kind + if (!admitted) " [429]" else "", detail, start, nowMs())
            }
        }
        fun counts(): Map<String, Int> = calls.groupingBy { it.kind }.eachCount().toSortedMap()
        fun lastEndMs(): Long = calls.maxOfOrNull { it.endMs } ?: 0L
        /** Each kind's first departure and last arrival: the stage's span. */
        fun stages(of: List<Call> = calls.toList()): List<Triple<String, Long, Long>> = of.groupBy { it.kind }.map { (kind, list) -> Triple(kind, list.minOf { it.startMs }, list.maxOf { it.endMs }) }.sortedBy { it.second }
    }

    /** The public API, every call recorded and delayed. */
    private class LoggingApi(private val delegate: CursorApi, private val log: CallLog) : CursorApi by delegate {
        override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean): ListAgentsResponseDto =
            log.record("v1 /agents page", cursor ?: "first") { delegate.listAgents(limit, cursor, includeArchived) }
        override suspend fun listAgentsV0(limit: Int, cursor: String?): V0ListAgentsResponseDto =
            log.record("v0 /agents page (status scan)", cursor ?: "first") { delegate.listAgentsV0(limit, cursor) }
        override suspend fun getAgent(id: String): AgentDto = log.record("v1 /agents/{id} (by id)", id) { delegate.getAgent(id) }
        override suspend fun getRun(id: String, runId: String): RunDto = log.record("v1 run record", runId) { delegate.getRun(id, runId) }
    }

    private class Chat(
        val id: String,
        val name: String,
        val activityMillis: Long,
        val running: Boolean = false,
        val archived: Boolean = false,
        val source: AgentSource? = null,
        val isProject: Boolean = false,
        val appearance: ProjectAppearance? = null,
        val manager: String? = null,
        val adoptedBy: String? = null,
        val sideChatOf: String? = null,
        val subagentOf: String? = null,
        val prUrl: String? = null,
    ) {
        fun snapshot(): ComposerSnapshot = ComposerSnapshot(
            id = id,
            name = name,
            archived = archived,
            isProject = isProject,
            projectAppearance = appearance?.takeIf { isProject },
            record = RecordFields(
                projectMetadata = if (isProject) appearance?.let { """{"appearance":{"icon":"${it.icon}","colorId":"${it.colorId}"}}""" } ?: "{}" else null,
                managerAgentId = manager,
                subagentParentId = subagentOf,
                sideChatParentId = sideChatOf,
                source = source?.name,
            ),
            parent = manager?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) }
                ?: sideChatOf?.let { AgentParent(it, AgentParentKind.SIDE_CHAT) }
                ?: subagentOf?.let { AgentParent(it, AgentParentKind.SUBAGENT) },
            source = source,
            status = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
            activityAtMillis = activityMillis,
            createdAtMillis = activityMillis,
        )
    }

    private class Project(val id: String, val name: String, val members: Int, val appearance: ProjectAppearance?, val archived: Boolean = false, val ageHours: Int)

    private val projects = listOf(
        Project("bc-shipyard", "Shipyard", 31, ProjectAppearance("logo-github", "blue"), ageHours = 2),
        Project("bc-jobs", "Job & Bounty Research", 8, ProjectAppearance("briefcase", "orange"), ageHours = 5),
        Project("bc-revenue", "Revenue Scaling Pipeline", 115, ProjectAppearance("lightning", "green"), ageHours = 1),
        Project("bc-noetic", "Noetic", 28, null, ageHours = 30),
        Project("bc-murmur", "Murmur", 6, ProjectAppearance("target", "purple"), ageHours = 60),
        Project("bc-mobile", "Cursor for Android", 17, ProjectAppearance("lightning", "default"), ageHours = 3),
        Project("bc-meter", "Codex Meter", 2, ProjectAppearance("gauge", "cyan"), ageHours = 400),
        Project("bc-retro", "Q2 launch retro", 4, ProjectAppearance("flag", "yellow"), archived = true, ageHours = 900),
    )

    private lateinit var chats: List<Chat>
    private val byId = HashMap<String, Chat>()
    private val memberships = HashMap<String, MutableList<WorkerMembership>>()

    /** The account service in miniature, every call recorded and delayed like the public API's, and through the process's throttle as every Connect call is. */
    private inner class Account(private val log: CallLog, private val throttle: ApiThrottle) : PinsApi, ProjectLineageApi {
        private suspend fun <T> api2(kind: String, detail: String = "", block: suspend () -> T): T = throttle.call { log.api2(kind, detail, block) }
        private val ordered get() = chats.sortedByDescending { it.activityMillis }

        private fun page(offset: Long?, size: Int): Pair<List<Chat>, Long?> {
            val rows = ordered.filter { offset == null || it.activityMillis < offset }.take(size)
            val next = rows.lastOrNull()?.activityMillis?.takeIf { rows.size == size }
            return rows to next
        }

        private fun list(rows: List<Chat>, next: Long?, pins: Boolean) = AccountList(
            pinned = PinnedIds(if (pins) setOf("bc-codex", "bc-own-3") else emptySet(), loaded = pins),
            pullRequests = rows.mapNotNull { c -> c.prUrl?.let { it to PullRequestState.Open } }.toMap(),
            sources = rows.mapNotNull { c -> c.source?.let { c.id to it } }.toMap(),
            composers = rows.map { it.snapshot() },
            nextCursor = next?.toString(),
        )

        override suspend fun list(): AccountList = api2("ListBackgroundComposers (account round)", "first") {
            val (rows, next) = page(null, 200)
            list(rows, next, pins = true)
        }

        override suspend fun listMore(cursor: String): AccountList = api2("ListBackgroundComposers (account round)", cursor) {
            val (rows, next) = page(cursor.toLong(), 200)
            list(rows, next, pins = false)
        }

        override suspend fun pin(ids: Collection<String>) = Unit
        override suspend fun unpin(ids: Collection<String>) = Unit

        override suspend fun workersForManager(managerId: String): List<WorkerMembership> =
            api2("ListWorkersForManager (per root)", managerId) { memberships[managerId].orEmpty() }

        override suspend fun children(parentId: String): List<ComposerSnapshot> =
            api2("ListBackgroundComposerChildren (per root)", parentId) { chats.filter { it.sideChatOf == parentId || it.subagentOf == parentId }.map { it.snapshot() } }

        override suspend fun record(id: String): ComposerSnapshot? = api2("account record by id", id) { byId[id]?.snapshot() }

        override suspend fun scanRoots(maxPages: Int): RootScan = scanRoots(maxPages, null)

        /** The real API's pass (see `BackgroundComposerApi.scanRoots`): newest first, stopped at the page older than the floor. */
        override suspend fun scanRoots(maxPages: Int, stopBelowActivityMillis: Long?): RootScan {
            val roots = ArrayList<ComposerSnapshot>()
            val children = ArrayList<ComposerSnapshot>()
            var offset: Long? = null
            var pages = 0
            var records = 0
            var complete = false
            var stoppedEarly = false
            var failure: String? = null
            val all = ArrayList<ComposerSnapshot>()
            do {
                val (rows, next) = try {
                    api2("ListBackgroundComposers (discovery scan page)", "page ${pages + 1}") { page(offset, 200) }
                } catch (t: java.io.IOException) {
                    if (t is ConnectRpcException && t.httpCode < 500) { failure = "page ${pages + 1}: ${t.message}"; break }
                    delay(750)
                    try {
                        api2("ListBackgroundComposers (discovery scan page)", "page ${pages + 1} (retry)") { page(offset, 200) }
                    } catch (again: java.io.IOException) {
                        failure = "page ${pages + 1}: ${again.message}"
                        break
                    }
                }
                pages++
                records += rows.size
                rows.map { it.snapshot() }.forEach { snap ->
                    all += snap
                    if (snap.scope == AgentScope.PROJECT_ROOT) roots += snap
                    if (snap.parent != null) children += snap
                }
                val oldest = rows.minOfOrNull { it.activityMillis }
                if (stopBelowActivityMillis != null && oldest != null && oldest < stopBelowActivityMillis && next != null) {
                    stoppedEarly = true
                    break
                }
                offset = next
                if (offset == null) complete = true
            } while (offset != null && pages < maxPages)
            return RootScan(roots, children, pages, complete, records, failure, truncated = failure == null && !complete && !stoppedEarly, stoppedEarly = stoppedEarly, snapshots = all)
        }
    }

    private val fake = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var cache: AgentListCache
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(true) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        cache = AgentListCache(JsonDiskCache(folder.newFolder("agents"), dispatcher = Dispatchers.Unconfined))
        buildAccount()
        fake.pageSize = 100
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun buildAccount() {
        val random = Random(7)
        val now = System.currentTimeMillis()
        val all = ArrayList<Chat>()
        projects.forEach { p ->
            val rootActivity = now - p.ageHours * 3_600_000L
            all += Chat(p.id, p.name, rootActivity, running = p.id == "bc-revenue" || p.id == "bc-shipyard", archived = p.archived, source = AgentSource.CLOUD_META_AGENT, isProject = true, appearance = p.appearance)
            val members = memberships.getOrPut(p.id) { ArrayList() }
            repeat(p.members) { index ->
                val id = "${p.id}-m${index + 1}"
                val activity = rootActivity - (index + 1) * 600_000L - random.nextLong(0, 300_000L)
                val running = !p.archived && random.nextInt(10) == 0
                when {
                    index % 6 == 5 -> all += Chat(id, "${p.name} side chat ${index + 1}", activity, running = running, source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD, sideChatOf = p.id)
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
        // The account's own chats — a thousand over three weeks, an eighth archived, most with a pull request, one
        // in nine of the last two days' running (a run ends within hours; the server marks the ones that do not
        // expired, so nothing weeks old runs) — so the public window (500) and the account window (200) each hold a
        // part of the account, as they do on a phone that has been in use for a while.
        repeat(OWN_CHATS) { index ->
            val id = "bc-own-${index + 1}"
            all += Chat(id, "Own chat ${index + 1}", now - index * 1_900_000L - 60_000L, running = index % 9 == 0 && index < RUNNING_OWN_CHATS_WITHIN, archived = index % 8 == 7, source = if (index % 3 == 0) AgentSource.EDITOR else null, prUrl = if (index % 3 != 1) "https://github.com/acme/app/pull/${100 + index}" else null)
        }
        all += Chat("bc-codex", "Codex-Poly-Bot Scaling", now - 500 * 3_600_000L, running = true, source = AgentSource.EDITOR)
        all += Chat("bc-spawner-1", "Refactor with subagents", now - 50 * 60_000L, running = true, source = AgentSource.EDITOR)
        all += Chat("bc-spawner-1-sub1", "Subagent: tests", now - 45 * 60_000L, running = true, source = AgentSource.AS_SUBAGENT_FROM_CLOUD, subagentOf = "bc-spawner-1")
        chats = all
        chats.forEach { byId[it.id] = it }
        chats.forEach { c ->
            val created = Instant.ofEpochMilli(c.activityMillis).toString()
            if (c.running) fake.addRunningAgent(c.id, c.name, "run-${c.id}", createdAt = created) else fake.addIdleAgent(c.id, c.name, "run-${c.id}", createdAt = created)
            if (c.archived) fake.agents[c.id] = fake.agents.getValue(c.id).copy(status = "ARCHIVED")
            if (c.prUrl != null) fake.runs["run-${c.id}"] = fake.runs.getValue("run-${c.id}").copy(git = RunGitDto(listOf(RunGitBranchDto("https://github.com/acme/app", "cursor/${c.id}", c.prUrl))))
        }
    }

    /** The graph's wiring, on one process: the list, the account round with its hooks, the Projects, the pull requests. */
    private inner class Process(val log: CallLog) {
        /** The process's own scope: a process that has ended runs nothing on, and its collectors do not touch the disk the next one reads. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun end() = scope.cancel()
        val api = LoggingApi(fake, log)
        val throttle = ApiThrottle()
        val account = Account(log, throttle)
        val stats = RefreshStats()
        val session = SessionManager(SecureKeyStore(context), prefs, CursorBackend(api, FakeRunStreamer(), isDemo = false), CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        val agents = AgentRepository(session, prefs, AttachmentStore(context), cache, scope, persistDelayMs = 1, capabilities = capabilities, recordOf = { id -> account.record(id) }, stats = stats)
        val projects = ProjectRepository(session, agents, account, scope = scope, pollIntervalMs = 60_000, capabilities = capabilities, retryDelaysMs = listOf(50L, 50L, 50L), stats = stats).also { it.watchList() }
        val pullRequests = PullRequestRepository(
            account = { url -> throttle.call { log.api2("GetPullRequestMergeStatus (PR badge)", url) { PullRequestLookup.Found(PullRequestState.Open) } } },
            demo = { PullRequestLookup.Unreadable },
            isDemo = { false },
            scope = scope,
            stats = stats,
        )
        val pins = PinRepository(
            session, prefs, agents, account, scope = scope, capabilities = capabilities, retryDelaysMs = listOf(50L, 50L, 50L), stats = stats,
            onList = { list, token ->
                agents.applySources(list.sources, token)
                pullRequests.seed(list.pullRequests)
                projects.scheduleRootDiscovery(list.composers.filter { it.scope == AgentScope.PROJECT_ROOT }.map { it.id })
            },
        ).also { pins -> agents.accountPrime = { pins.primeForFetch() } }

        /**
         * The sidebar's pull (see `AgentsViewModel.refresh`): the window re-read, then the pull requests of the rows on
         * screen — a screenful, as the sidebar reports them — re-read eagerly.
         */
        suspend fun pull() {
            agents.refresh(depth = RefreshDepth.Full)
            pullRequests.refresh(agents.state.value.agents.take(24).mapNotNull { it.prUrl }, eager = true)
        }

        /**
         * The cold start as `AgentsViewModel` runs it: the disk copy first, then a silent refresh (there was something
         * to show), and — the user pulling a moment later — a pull that joins the fetch in flight. Returns when the
         * pull returns; the marks are on [log].
         */
        suspend fun coldStartThenPull(pullAfterMs: Long): ColdStartMarks {
            val marks = ColdStartMarks()
            val watcher = scope.launch {
                var shown = false
                agents.state.collect { s ->
                    if (s.hasLoaded && marks.firstPaintMs < 0) marks.firstPaintMs = log.nowMs()
                    if (s.isRefreshing) { shown = true; marks.spinnerShown = true }
                    if (shown && !s.isRefreshing && marks.spinnerReleasedMs < 0) marks.spinnerReleasedMs = log.nowMs()
                    if (marks.pulledAtMs >= 0 && !s.isRefreshing && !s.isSettling && s.agents.isNotEmpty() && marks.listSettledMs < 0 && log.nowMs() > marks.pulledAtMs + 50) marks.listSettledMs = log.nowMs()
                }
            }
            agents.restoreFromCache()
            // In program order, not by the clock: the disk copy is published when the restore returns, before the
            // first fetch is even launched — so before any call could have gone out.
            marks.diskShownBeforeNetwork = agents.state.value.hasLoaded && agents.state.value.agents.isNotEmpty() && log.calls.isEmpty()
            pullRequests.restoreFromCache()
            val silent = scope.launch { agents.refresh(silent = agents.state.value.hasLoaded) }
            delay(pullAfterMs)
            marks.pulledAtMs = log.nowMs()
            pull()
            marks.pullReturnedMs = log.nowMs()
            silent.join()
            settle()
            marks.settledMs = log.lastEndMs()
            marks.firstNetworkCallMs = log.calls.minOfOrNull { it.startMs } ?: -1
            watcher.cancel()
            return marks
        }

        /** Waits until nothing has been on the wire for a while and the account round is over. */
        suspend fun settle(quietMs: Long = 1_500, timeoutMs: Long = 600_000) = withTimeout(timeoutMs) {
            while (true) {
                delay(100)
                val quiet = log.nowMs() - log.lastEndMs() >= quietMs && log.calls.isNotEmpty()
                if (quiet && !pins.state.value.isSyncing && !agents.state.value.isRefreshing && !agents.state.value.isSettling && !projects.syncingLineage.value && projects.lastRootScan.value?.status != RootScanRecord.Status.Running) return@withTimeout
            }
        }
    }

    class ColdStartMarks {
        var diskShownBeforeNetwork = false
        var spinnerShown = false
        var listSettledMs = -1L
        var firstPaintMs = -1L
        var firstNetworkCallMs = -1L
        var pulledAtMs = -1L
        var spinnerReleasedMs = -1L
        var pullReturnedMs = -1L
        var settledMs = -1L
    }

    /** The disk as 0.3.38 left it: the same rows and registry, but no root dated and no word that the registry is complete. */
    private suspend fun ageDiskToPreviousBuild() {
        val entry = cache.read() ?: error("nothing on disk")
        val lineage = cache.readLineage() ?: error("no lineage on disk")
        cache.write(entry.value, cache.token(), lineage.copy(roots = lineage.roots.map { it.copy(activityAtMillis = null) }, registryCompleteAtMillis = null))
    }

    private fun reportOf(title: String, marks: ColdStartMarks, log: CallLog, calls: List<Call>, process: Process): String = buildString {
        appendLine(title)
        appendLine("first paint (disk copy) at +${marks.firstPaintMs} ms; first network call at +${marks.firstNetworkCallMs} ms; pulled at +${marks.pulledAtMs} ms")
        val spinner = when {
            !marks.spinnerShown -> "not shown (the pull joined the cold start's fetch past its first page; the footer stood instead)"
            marks.spinnerReleasedMs < 0 -> "never released"
            else -> "released at +${marks.spinnerReleasedMs} ms (${marks.spinnerReleasedMs - marks.pulledAtMs} ms after the pull)"
        }
        appendLine("spinner $spinner; list's own tail settled (footer) at +${marks.listSettledMs} ms; refresh() returned at +${marks.pullReturnedMs} ms; last call landed at +${marks.settledMs} ms")
        appendLine("calls: ${calls.size} (${calls.count { it.kind.endsWith("[429]") }} refused with 429, ${log.failed.get()} failed transiently); peak in flight: api2=${log.peakApi2.get()} public=${log.peakPublic.get()}")
        calls.groupingBy { it.kind }.eachCount().toSortedMap().forEach { (kind, n) -> appendLine("  $n × $kind") }
        val scan = process.projects.lastRootScan.value
        appendLine("discovery scan: ${scan?.status} pages=${scan?.pagesRead} notice=${scan?.notice}")
        appendLine("rows: ${process.agents.state.value.agents.size}; roots shown: ${process.agents.state.value.agents.count { it.isProjectRoot }}; registry: ${process.agents.knownRoots.value.size}; unresolved roots: ${process.agents.unresolvedRoots().size}; unresolved records: ${process.agents.unresolvedRecords().size}")
    }

    /**
     * The same account, the network as a phone has it: 300–900 ms per round trip, one call in ten failing, and each
     * host refusing with a 429 past a few calls in flight. Two cold starts: the first from the disk 0.3.38 left (no
     * root dated, the registry not known complete), the second from what the first wrote.
     */
    @Test
    fun `a cold start at a phone's conditions - slow, lossy, rate-limited`() = runBlocking<Unit> {
        val warm = Process(CallLog(latencyMs = 0))
        warm.pull()
        warm.settle(quietMs = 400)
        chats.filter { it.prUrl != null }.forEach { c -> warm.agents.patch(c.id) { it.copy(branches = listOf(GitBranch("github.com/acme/app", "cursor/${c.id}", c.prUrl))) } }
        delay(300)
        warm.agents.state.first { !it.isFromCache }
        delay(300)
        warm.end()
        ageDiskToPreviousBuild()

        fun weather(seed: Int) = CallLog(latencyMs = { 300L + Random(seed + System.nanoTime().toInt()).nextLong(0, 600) }, failureRate = 0.10, api2Cap = 4, publicCap = 6, retryAfterMs = 2_000L, seed = seed)

        val log1 = weather(1)
        val first = Process(log1)
        val marks1 = first.coldStartThenPull(pullAfterMs = 1_000)
        val calls1 = log1.calls.toList()
        delay(400)
        first.agents.state.first { !it.isFromCache }
        delay(300)
        first.end()

        val log2 = weather(2)
        val second = Process(log2)
        val marks2 = second.coldStartThenPull(pullAfterMs = 1_000)
        val calls2 = log2.calls.toList()

        val report = buildString {
            appendLine(reportOf("Cold start 1 — upgraded install's disk (0.3.38): 300–900 ms RTT, 10 % transient failures, 429 past 4 api2 / 6 public calls in flight", marks1, log1, calls1, first))
            appendLine()
            appendLine(reportOf("Cold start 2 — the disk the first start wrote", marks2, log2, calls2, second))
            appendLine()
            appendLine("refresh block (second start):")
            appendLine(com.cursorforandroid.domain.ProjectDiagnostics.render(
                com.cursorforandroid.domain.ProjectDiagnostics.Input(
                    appVersion = "bench", nowIso = "-", extendedMode = true, projectsCapability = true, accountSession = true, listFromCache = false,
                    lastRefreshedIso = null, agents = emptyList(), placementOf = { null }, rootSyncs = emptyMap(), refresh = second.stats.snapshot.value,
                ),
            ).substringAfter("refresh:").substringBefore("rows (").trimEnd())
        }
        println(report)
        File(System.getProperty("user.dir"), "build/reports/refresh-benchmark-realistic.txt").apply { parentFile?.mkdirs() }.writeText(report)

        // The disk copy is on screen before anything goes on the wire, on both starts.
        assertThat(marks1.diskShownBeforeNetwork).isTrue()
        assertThat(marks2.diskShownBeforeNetwork).isTrue()
        // Every Project is a root, and its members its own, on both starts, whatever the weather.
        for (process in listOf(first, second)) {
            val rows = process.agents.state.value.agents
            assertThat(rows.filter { it.isProjectRoot }.map { it.id }).containsAtLeastElementsIn(projects.filter { !it.archived }.map { it.id })
            val members = chats.filter { it.manager != null || it.adoptedBy != null || it.sideChatOf != null || it.subagentOf != null }.mapTo(HashSet()) { it.id }
            assertThat(rows.filter { it.scope == AgentScope.PRIMARY }.map { it.id }.filter { it in members }).isEmpty()
        }
        // The second start's discovery scan stops at the registry's oldest Project: the first start's full pass is the last.
        assertWithMessage("first=${first.projects.lastRootScan.value} second=${second.projects.lastRootScan.value} complete=${second.agents.registryCompleteAtMillis} roots=${second.agents.knownRoots.value.map { it.id to it.activityAtMillis }}")
            .that(second.projects.lastRootScan.value?.pagesRead ?: 0).isLessThan(first.projects.lastRootScan.value?.pagesRead ?: 0)
        second.end()
    }

    @Test
    fun `a pull to refresh after a cold start, counted call by call`() = runBlocking<Unit> {
        // A previous session: the list, the registry and the account's records reach the disk, as they would have
        // by the time Bennett pulls the next morning.
        val warm = Process(CallLog(latencyMs = 0))
        warm.pull()
        warm.settle(quietMs = 400)
        // Rows learned their pull requests over earlier sessions (the run records name them): the disk copy carries them.
        chats.filter { it.prUrl != null }.forEach { c -> warm.agents.patch(c.id) { it.copy(branches = listOf(GitBranch("github.com/acme/app", "cursor/${c.id}", c.prUrl))) } }
        delay(300)
        // Written out before the "process" ends.
        warm.agents.state.first { !it.isFromCache }
        delay(300)

        // The next morning: a fresh process, the disk copy, and a pull.
        warm.end()
        val log = CallLog(latencyMs = LATENCY_MS)
        val process = Process(log)
        val spinner = AtomicLong(-1)
        val firstRows = AtomicLong(-1)
        val watcher: Job = scope.launch {
            var wasRefreshing = false
            process.agents.state.collect { s ->
                if (s.agents.isNotEmpty() && firstRows.get() < 0) firstRows.set(log.nowMs())
                if (s.isRefreshing) wasRefreshing = true
                if (wasRefreshing && !s.isRefreshing && spinner.get() < 0) spinner.set(log.nowMs())
            }
        }
        val pullStarted = log.nowMs()
        process.pull()
        val pullReturned = log.nowMs()
        process.settle()
        watcher.cancel()
        val coldCalls = log.calls.toList()
        val coldSettled = log.lastEndMs()
        val coldSnapshot = process.stats.snapshot.value

        // A second pull a moment later, in the same process: what a refresh costs once the account has been read.
        val warmFrom = log.calls.size
        val warmStarted = log.nowMs()
        process.pull()
        process.settle()
        val warmCalls = log.calls.drop(warmFrom)

        val report = buildString {
            appendLine("Cold pull-to-refresh on Bennett's account shape, ${LATENCY_MS} ms per network call")
            appendLine("rows: ${chats.size} chats (${projects.size} Projects, ${chats.count { it.manager != null || it.adoptedBy != null || it.sideChatOf != null }} members, ${chats.count { it.prUrl != null }} with a PR); public pages of 100, account pages of 200")
            appendLine("first rows shown at +${firstRows.get()} ms (disk copy); spinner released at +${spinner.get()} ms; refresh() returned at +${pullReturned - pullStarted} ms; last call landed at +$coldSettled ms")
            appendLine("calls: ${coldCalls.size} in total")
            coldCalls.groupingBy { it.kind }.eachCount().toSortedMap().forEach { (kind, n) -> appendLine("  $n × $kind") }
            appendLine("stages (first departure → last arrival):")
            log.stages(coldCalls).forEach { (kind, start, end) -> appendLine("  +${start.toString().padStart(6)} → +${end.toString().padStart(6)} ms  $kind") }
            appendLine("order of the first thirty calls:")
            coldCalls.sortedBy { it.startMs }.take(30).forEach { appendLine("  +${it.startMs.toString().padStart(6)} ms  ${it.kind}  ${it.detail}") }
            appendLine()
            appendLine("Second pull in the same process: ${warmCalls.size} calls, settled in ${log.lastEndMs() - warmStarted} ms")
            warmCalls.groupingBy { it.kind }.eachCount().toSortedMap().forEach { (kind, n) -> appendLine("  $n × $kind") }
            appendLine()
            appendLine("refresh block as the diagnostics export prints it (last pull):")
            appendLine(com.cursorforandroid.domain.ProjectDiagnostics.render(
                com.cursorforandroid.domain.ProjectDiagnostics.Input(
                    appVersion = "bench", nowIso = "-", extendedMode = true, projectsCapability = true, accountSession = true, listFromCache = false,
                    lastRefreshedIso = null, agents = emptyList(), placementOf = { null }, rootSyncs = emptyMap(), refresh = process.stats.snapshot.value,
                ),
            ).substringAfter("refresh:").substringBefore("rows (").trimEnd())
        }
        println(report)
        File(System.getProperty("user.dir"), "build/reports/refresh-benchmark.txt").apply { parentFile?.mkdirs() }.writeText(report)

        // What the refresh is held to from here on — by counts and by the order the code records itself, never by
        // how the wall clock happened to fall between two threads (the overlap-of-round-trips assertion this had
        // flaked on about a third of runs).
        // The indicator is let go before any pass of the tail starts: the release is recorded in the fetch before it
        // launches the by-id passes, the memberships follow the completed fetch, the badges follow the pull.
        val coldStats = coldSnapshot ?: error("no refresh recorded")
        val tailStages = coldStats.stages.filter { it.name.contains("fetched by id") || it.name.contains("memberships") || it.name.contains("badges") || it.name.contains("records by id") }
        assertThat(tailStages).isNotEmpty()
        assertThat(coldStats.spinnerReleasedAtMillis).isNotNull()
        tailStages.forEach { stage -> assertThat(stage.startedAtMillis).isAtLeast(coldStats.spinnerReleasedAtMillis!!) }
        // The discovery scan stops at the registry's oldest live Project rather than reading the whole account.
        assertThat(coldCalls.count { it.kind == "ListBackgroundComposers (discovery scan page)" }).isLessThan((chats.size + 199) / 200)
        // Never more on the wire at once than the account service and the public API are given: the throttle's
        // three and the by-id pool's two plus the two lists' pages — and so nothing refused.
        assertThat(log.peakApi2.get()).isAtMost(ApiThrottle.DEFAULT_MAX_IN_FLIGHT)
        assertThat(log.peakPublic.get()).isAtMost(4)
        assertThat(log.refused.get()).isEqualTo(0)
        // A second pull re-reads no memberships (no root's record moved), no discovery pages and no badge already fresh.
        assertThat(warmCalls.count { it.kind.contains("per root") }).isEqualTo(0)
        assertThat(warmCalls.count { it.kind.contains("discovery scan") }).isEqualTo(0)
        assertThat(warmCalls.size).isLessThan(coldCalls.size / 2)

        // The invariants a pull must keep: every Project a root, every member placed, no leak among the account's own.
        val rows = process.agents.state.value.agents
        val roots = rows.filter { it.isProjectRoot }.map { it.id }
        assertThat(roots).containsAtLeastElementsIn(projects.filter { !it.archived }.map { it.id })
        val members = chats.filter { it.manager != null || it.adoptedBy != null || it.sideChatOf != null || it.subagentOf != null }.mapTo(HashSet()) { it.id }
        assertThat(rows.filter { it.scope == AgentScope.PRIMARY }.map { it.id }.filter { it in members }).isEmpty()
    }

    private companion object {
        const val LATENCY_MS = 60L
        const val OWN_CHATS = 1_000
        /** Own chats newer than this many (about two days at one every half hour) may be running; nothing older does. */
        const val RUNNING_OWN_CHATS_WITHIN = 90
    }
}
