package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.AgentsWindowList
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
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
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Bennett's sidebar, a very large account: every now and then the list dropped every recent chat — the running ones
 * too — and showed months-old chats under "Today", with a Project's workers loose among them at the top level.
 *
 * The account here is 2,500 chats, newest first by creation, with a Project whose workers are placed by their record
 * (`managerAgentId`) or by the Project's membership answer alone, running chats, and old chats whose public
 * `updatedAt` a server sweep bumped to minutes ago while their record's message activity stayed where it was (the
 * desktop dates a chat by the record; the public `updatedAt` moves with anything the service does to the row).
 *
 * The list endpoint misbehaves the ways a big account's list does under load: a page with no cursor where there are
 * more ([Glitch.EMPTY_END], [Glitch.SHORT_END]), a page ordered by activity rather than creation after a sweep
 * ([Glitch.BY_ACTIVITY]), slow answers overtaken by later ones, and failures. Through all of it:
 *
 * 1. the list is monotonic — a chat the list held is held after any refresh or page unless the server confirmed it
 *    gone (a 404 for that chat), however partial, stale or out of order the answer that omitted it was;
 * 2. every running chat stays on the list;
 * 3. a chat is dated by its real activity: nothing shown under Today is a chat whose activity was not today;
 * 4. Project membership is sticky: a worker known to belong to its Project is never a top-level row, and a new
 *    worker the list has not placed yet is held back rather than shown loose until its record says where it goes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SidebarListMonotonicTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var cache: AgentListCache
    private val api = ChaosApi()

    private val now = Instant.parse("2026-09-25T20:00:00Z").toEpochMilli()
    private val zone = ZoneOffset.UTC

    /** The account's own record of each chat, as the account service would give it by id. */
    private val records = ConcurrentHashMap<String, ComposerSnapshot>()
    /** When set, a record read by id waits on it: an account service slower than the list. */
    @Volatile private var recordGate: CompletableDeferred<Unit>? = null
    /** The record reads by id that failed in passing, as a flaky account service would. */
    @Volatile private var recordFailureRate = 0.0
    private val random = Random(20260925)

    /** The chats' real last activity (the record's `lastMessageActivityAtMs`), by id: what "Today" must be judged by. */
    private val activity = ConcurrentHashMap<String, Long>()
    /** Chats the server deleted: the only ones the list may lose. */
    private val deleted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val root = "bc-project"
    /** Workers whose own record names the Project (`managerAgentId`). */
    private val recordWorkers = listOf(5, 9, 13, 21, 140, 333).map(::id).toMutableList()
    /** Workers only the Project's membership answer names; their records carry no parent link. */
    private val membershipWorkers = listOf(6, 10, 260).map(::id)
    private val running = mutableSetOf(id(0), id(1), id(7), id(410))

    private enum class Glitch {
        /** 200 with no rows and no cursor: the list's end, as far as the answer says. */
        EMPTY_END,
        /** The first rows of the page, and no cursor. */
        SHORT_END,
        /** The page ordered by the public `updatedAt`, the sweep's bumped old chats first, with a cursor. */
        BY_ACTIVITY,
        /** Ordered by activity and cut short with no cursor: both at once. */
        BY_ACTIVITY_END,
        /** The request fails outright. */
        FAIL,
    }

    private inner class ChaosApi : FakeCursorApi() {
        @Volatile var glitch: Glitch? = null
        /** How many list pages the glitch applies to before the server behaves again (-1: until cleared). */
        @Volatile var glitchPages = -1
        @Volatile var maxLatencyMs = 0L
        @Volatile var getAgentFailure: Throwable? = null

        private fun latency(): Long = if (maxLatencyMs <= 0) 0 else synchronized(random) { random.nextLong(maxLatencyMs + 1) }

        override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean): ListAgentsResponseDto {
            val answer = super.listAgents(limit, cursor, includeArchived)
            delay(latency())
            val glitch = synchronized(this) {
                val g = glitch ?: return@synchronized null
                if (glitchPages == 0) return@synchronized null
                if (glitchPages > 0) glitchPages--
                g
            } ?: return answer
            fun byActivity() = agents.values.sortedByDescending { it.updatedAt }.take(limit).map(::summary)
            return when (glitch) {
                Glitch.EMPTY_END -> ListAgentsResponseDto(emptyList(), null)
                Glitch.SHORT_END -> ListAgentsResponseDto(answer.items.take(7), null)
                Glitch.BY_ACTIVITY -> ListAgentsResponseDto(byActivity(), answer.nextCursor)
                Glitch.BY_ACTIVITY_END -> ListAgentsResponseDto(byActivity().take(40), null)
                Glitch.FAIL -> throw IOException("connection reset")
            }
        }

        override suspend fun getAgent(id: String): AgentDto {
            delay(latency())
            getAgentFailure?.let { throw it }
            return super.getAgent(id)
        }
    }

    private fun summary(it: AgentDto) = AgentSummaryDto(it.id, it.name, it.status, it.env, it.url, it.createdAt, it.updatedAt, it.latestRunId)

    private fun id(i: Int) = "bc-%04d".format(i)
    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true))
        cache = AgentListCache(JsonDiskCache(folder.newFolder("agents"), dispatcher = Dispatchers.Unconfined))
        AppClock.nowMillis = { now }
        api.pageSize = 100
        seed(2_500)
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    /**
     * Chat i was created 2h·(i+1) ago, so the four newest are today's. A running chat's activity is minutes ago; an
     * idle one's half an hour after its creation. Every seventh chat past the 200th had its public `updatedAt`
     * bumped by a sweep twenty minutes ago; its record still says when it was last active.
     */
    private fun seed(count: Int) {
        for (i in 0 until count) addChat(id(i), createdAt = now - (i + 1) * 2 * HOUR, running = id(i) in running, swept = i >= 200 && i % 7 == 0)
        addChat(root, createdAt = now - 25 * HOUR, name = "Sidebar Project")
        records[root] = records.getValue(root).copy(isProject = true, record = RecordFields(projectMetadata = "{}"))
        recordWorkers.forEach { w -> records[w] = records.getValue(w).copy(parent = AgentParent(root, AgentParentKind.PROJECT_WORKER), record = RecordFields(managerAgentId = root)) }
    }

    private fun addChat(id: String, createdAt: Long, running: Boolean = false, swept: Boolean = false, name: String = "Chat $id") {
        val active = if (running) now - 5 * MINUTE else createdAt + 30 * MINUTE
        val publicUpdated = if (swept) now - 20 * MINUTE else active
        val runId = "run-$id"
        api.agents[id] = AgentDto(id = id, name = name, status = if (running) "ACTIVE" else "IDLE", createdAt = iso(createdAt), updatedAt = iso(publicUpdated), latestRunId = runId)
        api.v0[id] = V0AgentDto(id = id, name = name, status = if (running) "RUNNING" else "FINISHED")
        api.runs[runId] = RunDto(id = runId, agentId = id, status = if (running) "RUNNING" else "FINISHED", createdAt = iso(createdAt), updatedAt = iso(active), durationMs = if (running) null else 60_000)
        records[id] = ComposerSnapshot(id = id, name = name, archived = false, record = RecordFields(), activityAtMillis = active, createdAtMillis = createdAt)
        activity[id] = active
    }

    /** One chat's account record by id, as `AgentRepository.recordOf` asks for it. */
    private suspend fun recordOf(id: String): ComposerSnapshot? {
        recordGate?.await()
        if (recordFailureRate > 0 && synchronized(random) { random.nextDouble() } < recordFailureRate) throw IOException("account service hiccup")
        return records[id]
    }

    private fun repository(repoScope: CoroutineScope = scope) = AgentRepository(
        session, prefs, AttachmentStore(context), cache, repoScope, persistDelayMs = 1,
        recordOf = ::recordOf, maxMaterializedRunning = 50,
    ).also { repo ->
        // The account's list paged alongside the public one, as the pin sync pages it by its own cursor: the records
        // of the next stretch of chats by creation.
        val accountPages = java.util.concurrent.atomic.AtomicInteger(0)
        repo.accountPage = {
            val page = accountPages.incrementAndGet()
            repo.applyAccountSnapshots(records.values.filter { it.id !in deleted }.sortedByDescending { it.createdAtMillis ?: 0L }.drop(page * api.pageSize).take(api.pageSize))
        }
    }

    /**
     * The account list's word, as the pin sync gives it after each fetch: the records of the newest chats by
     * activity, and every membership the Project's answer names — each applied the way the graph applies them.
     */
    private fun accountRound(repo: AgentRepository, window: Int = 200) {
        val newest = records.values.filter { it.id !in deleted }.sortedByDescending { it.activityAtMillis ?: 0L }.take(window)
        repo.applyAccountSnapshots(newest)
        membershipAnswer(repo, (recordWorkers + membershipWorkers).toSet())
    }

    private fun membershipAnswer(repo: AgentRepository, workers: Set<String>) =
        repo.applyLineage(root, workers.associateWith { AgentParentKind.PROJECT_WORKER }, LineageSignal.MEMBERSHIP, retract = setOf(AgentParentKind.PROJECT_WORKER))

    /** The window Bennett scrolls to: the first page and four more. */
    private suspend fun loadWindow(repo: AgentRepository, pages: Int = 5) {
        repo.refresh()
        repeat(pages - 1) { repo.loadMore() }
        accountRound(repo)
        awaitSettled(repo)
    }

    private suspend fun awaitSettled(repo: AgentRepository) {
        AgentRepositoryTestHelper.awaitFetchIdle(repo)
        withTimeout(20_000) { while (repo.pending.state.value.items.isNotEmpty()) delay(10) }
    }

    /** The rows the sidebar draws from. */
    private fun visible(repo: AgentRepository): List<Agent> = repo.state.value.shownAgents

    private fun sections(repo: AgentRepository): List<AgentSection> =
        AgentListOrganizer.organize(visible(repo), ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone, knownRoots = repo.knownRoots.value)

    private fun topLevel(sections: List<AgentSection>): List<AgentRow> = sections.flatMap { it.rows }

    private fun descendants(rows: List<AgentRow>): List<AgentRow> = rows.flatMap { listOf(it) + descendants(it.children) }

    private fun held(repo: AgentRepository): Set<String> = repo.state.value.agents.mapTo(HashSet()) { it.id }

    /**
     * The four invariants, against what the list held before ([before]) and what the server deleted since. Every
     * running chat must be held, or with [newRunningMayLag] every running chat the list held before — a chat started
     * while every listing failed has had no answer to arrive in.
     */
    private fun assertInvariants(label: String, repo: AgentRepository, before: Set<String>, newRunningMayLag: Boolean = false) {
        val held = held(repo)
        val lost = before - held - deleted
        assertWithMessage("$label: chats the list held and lost without the server deleting them (${lost.size})").that(lost.sorted().take(20)).isEmpty()

        val runningNow = (if (newRunningMayLag) running.intersect(before) else running) - deleted
        assertWithMessage("$label: running chats missing from the list").that((runningNow - held).sorted()).isEmpty()

        val sections = sections(repo)
        val today = sections.firstOrNull { it.title == AgentsWindowList.TimeBucket.TODAY.label }?.rows.orEmpty()
        val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val misdated = today.filter { row -> activity[row.agent.id]?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() != todayDate } ?: false }
            .map { "${it.agent.id} active ${iso(activity.getValue(it.agent.id))}" }
        assertWithMessage("$label: chats shown under Today whose activity was not today").that(misdated).isEmpty()

        val top = topLevel(sections).mapTo(HashSet()) { it.agent.id }
        val workers = (recordWorkers + membershipWorkers).filter { it in held && it !in deleted }
        assertWithMessage("$label: Project workers shown as top-level rows").that(workers.filter { it in top }).isEmpty()
        val projectRow = topLevel(sections).firstOrNull { it.agent.id == root }
        if (projectRow != null) {
            val under = descendants(projectRow.children).mapTo(HashSet()) { it.agent.id }
            assertWithMessage("$label: workers held but not under their Project").that(workers.filter { it !in under && it in visible(repo).map { a -> a.id } }).isEmpty()
        }
    }

    @Test
    fun `a page with no cursor where there are more never empties the window - the screenshot's empty Recent`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo)
        val window = held(repo)
        assertWithMessage("window loaded").that(window.size).isAtLeast(500)
        assertInvariants("loaded", repo, window)

        for (glitch in listOf(Glitch.EMPTY_END, Glitch.SHORT_END)) {
            api.glitch = glitch
            api.glitchPages = 1
            repo.refresh()
            awaitSettled(repo)
            assertInvariants("after a refresh answered with $glitch", repo, window)
        }
        api.glitch = null
        repo.refresh()
        awaitSettled(repo)
        assertInvariants("after the server recovered", repo, window)
    }

    @Test
    fun `a page ordered by activity after a sweep never replaces the recent chats with old ones dated Today`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo)
        val window = held(repo)

        for (glitch in listOf(Glitch.BY_ACTIVITY, Glitch.BY_ACTIVITY_END)) {
            api.glitch = glitch
            api.glitchPages = -1
            repo.refresh()
            awaitSettled(repo)
            assertInvariants("after a refresh answered with $glitch", repo, window)
            repo.refresh(depth = RefreshDepth.Deep)
            awaitSettled(repo)
            assertInvariants("after a deep refresh answered with $glitch", repo, window)
        }
        api.glitch = null
        repo.refresh()
        awaitSettled(repo)
        accountRound(repo)
        assertInvariants("after the server recovered", repo, window)
    }

    @Test
    fun `an older page that lands with no cursor does not take the rows above it`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo, pages = 3)
        val window = held(repo)
        api.glitch = Glitch.EMPTY_END
        api.glitchPages = 1
        repo.loadMore()
        awaitSettled(repo)
        assertInvariants("after an empty older page", repo, window)
        api.glitch = Glitch.BY_ACTIVITY_END
        api.glitchPages = 1
        repo.loadMore()
        awaitSettled(repo)
        assertInvariants("after an older page ordered by activity", repo, window)
    }

    @Test
    fun `a chat deleted elsewhere still leaves the list, once the server confirms it by id`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo, pages = 2)
        val gone = id(42)
        api.agents.remove(gone)
        api.v0.remove(gone)
        deleted += gone

        // A server that cannot be reached confirms nothing: the row stays.
        api.getAgentFailure = IOException("offline")
        repo.refresh()
        awaitSettled(repo)
        assertWithMessage("a row the server could not be asked about").that(held(repo)).contains(gone)

        api.getAgentFailure = null
        repo.refresh()
        awaitSettled(repo)
        assertWithMessage("a row the server says is gone").that(held(repo)).doesNotContain(gone)
        assertInvariants("after the delete", repo, held(repo) + gone)
    }

    @Test
    fun `a membership answer that omits workers never demotes them to the top level`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo)
        val window = held(repo)
        membershipAnswer(repo, emptySet())
        assertInvariants("after an empty membership answer", repo, window)
        AppClock.nowMillis = { now + 3 * MINUTE }
        membershipAnswer(repo, emptySet())
        assertInvariants("after empty membership answers three minutes apart", repo, window)
    }

    @Test
    fun `a Project whose answers stay empty lets its membership-placed workers go, after a while`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo)
        membershipAnswer(repo, emptySet())
        assertWithMessage("one empty answer").that(membershipWorkers.filter { repo.agent(it)?.parent == null }).isEmpty()
        AppClock.nowMillis = { now + 11 * MINUTE }
        membershipAnswer(repo, emptySet())
        assertWithMessage("empty answers eleven minutes apart").that(membershipWorkers.filter { repo.agent(it)?.parent != null }).isEmpty()
        assertWithMessage("workers their own record places").that(recordWorkers.filter { repo.agent(it)?.parent?.id != root }).isEmpty()
    }

    @Test
    fun `an answer that names the Project's other workers releases the one it leaves out at once`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo)
        val released = id(6)
        membershipAnswer(repo, (recordWorkers + membershipWorkers).toSet() - released)
        assertWithMessage("a worker the account's current answer no longer names").that(repo.agent(released)?.parent).isNull()
    }

    @Test
    fun `a poll's quick look never shrinks the window paged to`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo)
        val pages = repo.pagesLoadedCount()
        assertWithMessage("pages loaded").that(pages).isEqualTo(5)
        repo.refresh(depth = RefreshDepth.Quick)
        awaitSettled(repo)
        assertWithMessage("pages loaded after a quick look").that(repo.pagesLoadedCount()).isEqualTo(pages)
        repo.loadMore()
        awaitSettled(repo)
        assertWithMessage("the next page after a quick look is the sixth").that(held(repo)).contains(id(550))
    }

    @Test
    fun `a new worker is held back until its record places it, and never shown loose`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo)
        val window = held(repo)

        // The coordinator creates a worker; the list's page brings it before the account can say whose it is.
        val worker = "bc-new-worker"
        addChat(worker, createdAt = now - MINUTE, running = true, name = "Fix sidebar losing recent chats")
        running += worker
        records[worker] = records.getValue(worker).copy(parent = AgentParent(root, AgentParentKind.PROJECT_WORKER), record = RecordFields(managerAgentId = root))
        val gate = CompletableDeferred<Unit>()
        recordGate = gate
        val refresh = scope.launch { repo.refresh() }
        withTimeout(10_000) { while (worker !in held(repo)) delay(10) }
        val loose = topLevel(sections(repo)).map { it.agent.id }
        assertWithMessage("a worker the list cannot place yet, shown as a top-level row").that(loose).doesNotContain(worker)

        // A membership answer read before the account had registered the worker: the coordinator's word stands.
        repo.applyLineage(root, mapOf(worker to AgentParentKind.PROJECT_WORKER), LineageSignal.COORDINATOR_CREATED)
        membershipAnswer(repo, (recordWorkers + membershipWorkers).toSet())
        assertWithMessage("a just-created worker a membership answer did not know yet").that(topLevel(sections(repo)).map { it.agent.id }).doesNotContain(worker)

        gate.complete(Unit)
        recordGate = null
        refresh.join()
        awaitSettled(repo)
        withTimeout(10_000) { while (repo.agent(worker)?.record == null) { repo.materializeRecords(); delay(20) } }
        val project = topLevel(sections(repo)).first { it.agent.id == root }
        assertWithMessage("the worker, once placed, under its Project").that(descendants(project.children).map { it.agent.id }).contains(worker)
        assertInvariants("after the worker was placed", repo, window)
    }

    @Test
    fun `a new chat of the account's own shows once its record says it has no parent`() = runBlocking<Unit> {
        val repo = repository()
        loadWindow(repo)
        val chat = "bc-new-chat"
        addChat(chat, createdAt = now - MINUTE, running = true, name = "Started on the web")
        running += chat
        repo.refresh()
        awaitSettled(repo)
        withTimeout(10_000) { while (chat !in visible(repo).map { it.id }) { repo.materializeRecords(); delay(20) } }
        assertWithMessage("the new chat, top level under Today").that(sections(repo).first { it.title == "Today" }.rows.map { it.agent.id }).contains(chat)
    }

    @Test
    fun `restored from disk, a glitching first refresh keeps the saved list`() = runBlocking<Unit> {
        val first = repository()
        loadWindow(first)
        val window = held(first)
        withTimeout(10_000) { while ((cache.readWithLineage()?.first?.value?.size ?: 0) < window.size) delay(20) }

        val second = repository(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        second.restoreFromCache()
        assertWithMessage("the saved list, restored").that(held(second)).containsAtLeastElementsIn(window)
        api.glitch = Glitch.BY_ACTIVITY_END
        api.glitchPages = -1
        second.refresh()
        awaitSettled(second)
        assertInvariants("restarted into a glitching server", second, window)
    }

    /**
     * Random interleavings of everything that moves the list at once — pulls, polls, deep refreshes, the next page,
     * the account's word, memberships, new chats and workers, runs starting and finishing, deletes elsewhere — over a
     * server that answers slowly, out of order, with glitched pages and failures. The invariants after every round.
     */
    @Test
    fun `stress - concurrent refreshes, pages, glitches and failures never regress the list`() = runBlocking<Unit> {
        api.maxLatencyMs = 25
        recordFailureRate = 0.1
        val repo = repository()
        loadWindow(repo)
        var before = held(repo)
        var created = 0
        repeat(ROUNDS) { round ->
            api.glitch = listOf(null, null, Glitch.EMPTY_END, Glitch.SHORT_END, Glitch.BY_ACTIVITY, Glitch.BY_ACTIVITY_END, Glitch.FAIL).random(random)
            api.glitchPages = if (random.nextBoolean()) 1 else -1
            // The server moves meanwhile.
            when (random.nextInt(5)) {
                0 -> { val chat = "bc-new-${created++}"; addChat(chat, createdAt = now - random.nextLong(1, 30) * MINUTE, running = random.nextBoolean()); if (api.v0.getValue(chat).status == "RUNNING") running += chat }
                1 -> {
                    val worker = "bc-worker-${created++}"
                    addChat(worker, createdAt = now - random.nextLong(1, 30) * MINUTE, running = true)
                    running += worker
                    records[worker] = records.getValue(worker).copy(parent = AgentParent(root, AgentParentKind.PROJECT_WORKER), record = RecordFields(managerAgentId = root))
                    recordWorkers += worker
                }
                2 -> {
                    val victim = before.filter { it.startsWith("bc-0") && it !in running && it != root && it !in recordWorkers && it !in membershipWorkers }.randomOrNull(random)
                    if (victim != null) { api.agents.remove(victim); api.v0.remove(victim); deleted += victim }
                }
                else -> Unit
            }
            val ops = List(random.nextInt(2, 6)) {
                scope.launch {
                    when (random.nextInt(7)) {
                        0 -> repo.refresh(depth = RefreshDepth.Quick)
                        1 -> repo.refresh()
                        2 -> repo.refresh(depth = RefreshDepth.Deep)
                        3 -> repo.loadMore()
                        4 -> repo.refreshIfStale(0, RefreshDepth.Quick)
                        5 -> accountRound(repo)
                        else -> repo.materializeRecords()
                    }
                }
            }
            ops.joinAll()
            awaitSettled(repo)
            // New workers are placed by their record; the list holds a worker back until then, never loose.
            assertInvariants("round $round (${api.glitch})", repo, before, newRunningMayLag = true)
            before = held(repo)
        }
        api.glitch = null
        recordFailureRate = 0.0
        repo.refresh()
        awaitSettled(repo)
        accountRound(repo)
        assertInvariants("after the storm", repo, before)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val ROUNDS = 60
    }
}
