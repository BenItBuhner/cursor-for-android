package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerLifecycleApi
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0TargetDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.HeldDispatcher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * The agent list against a paging, gate-able backend: what is on screen before each network answer arrives, what
 * survives failures, and what reaches the disk. Robolectric only because [SessionManager] needs a Context.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var cache: AgentListCache
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        // The default backend is the real one (isDemo = false), which is what exercises the disk cache.
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true))
        cache = AgentListCache(JsonDiskCache(folder.newFolder("agents"), dispatcher = Dispatchers.Unconfined))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun repository(persistDelayMs: Long = 10, account: ComposerLifecycleApi? = null, scope: CoroutineScope = this.scope) =
        AgentRepository(session, prefs, AttachmentStore(ApplicationProvider.getApplicationContext()), cache, scope, persistDelayMs, account)

    private class FakeLifecycleApi : ComposerLifecycleApi {
        val archives = CopyOnWriteArrayList<String>()
        val unarchives = CopyOnWriteArrayList<String>()
        val renames = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var failing: Throwable? = null
        override suspend fun archive(id: String) { failing?.let { throw it }; archives += id }
        override suspend fun unarchive(id: String) { failing?.let { throw it }; unarchives += id }
        override suspend fun rename(id: String, name: String) { failing?.let { throw it }; renames += id to name }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun cachedAgent(id: String, name: String, runStatus: RunStatus? = RunStatus.FINISHED, lifecycle: AgentLifecycle = AgentLifecycle.IDLE, runId: String? = "run-$id") = Agent(
        id = id,
        name = name,
        lifecycle = lifecycle,
        runStatus = runStatus,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = 1_700_000_000_000L,
        updatedAtMillis = 1_700_000_000_000L,
        latestRunId = runId,
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        summary = "Cached summary",
        modelDisplayName = "Claude",
    )

    /**
     * Two passes asking for the same row at once — the running scan's and the pin's, a root's and a membership's —
     * share one read of `GET /v1/agents/{id}` rather than each making its own; a later ask reads again.
     */
    @Test
    fun `concurrent fetches by id of one chat share one read`() = runBlocking<Unit> {
        api.addIdleAgent("bc-shared", "Shared", "run-shared")
        val repo = repository()
        api.getAgentGate = kotlinx.coroutines.CompletableDeferred()
        val before = api.getAgentCalls
        val first = async { repo.loadDetail("bc-shared") }
        val second = async { repo.loadDetail("bc-shared") }
        awaitUntil { api.getAgentCalls == before + 1 }
        api.getAgentGate!!.complete(Unit)
        assertThat(first.await().isSuccess).isTrue()
        assertThat(second.await().isSuccess).isTrue()
        assertThat(api.getAgentCalls).isEqualTo(before + 1)
        api.getAgentGate = null
        // The shared read is over: the next ask is its own.
        repo.loadDetail("bc-shared")
        assertThat(api.getAgentCalls).isEqualTo(before + 2)
    }

    @Test
    fun `the list restored from disk is on screen before the network answers, then revalidated`() = runBlocking<Unit> {
        cache.write(listOf(cachedAgent("bc-old", "From last time")))
        api.addIdleAgent("bc-old", "From last time (renamed)", "run-bc-old", summary = "Fresh summary")
        api.addIdleAgent("bc-new", "Started elsewhere", "run-2", createdAt = "2026-04-14T10:00:00.000Z")
        api.v0Gate = CompletableDeferred()
        api.laterPagesGate = CompletableDeferred()
        val repo = repository()

        repo.restoreFromCache()
        val restored = repo.state.value
        assertThat(restored.hasLoaded).isTrue()
        assertThat(restored.isFromCache).isTrue()
        assertThat(restored.agents.map { it.name }).containsExactly("From last time")
        assertThat(api.listAgentsCalls).isEqualTo(0)

        val refresh = scope.launch { repo.refresh(silent = true) }
        // The v1 page lands while the legacy list is still pending: rows are refreshed in place and new ones appear.
        awaitUntil { repo.state.value.agents.size == 2 }
        val midway = repo.state.value
        assertThat(midway.agents.map { it.name }).containsExactly("From last time (renamed)", "Started elsewhere")
        // Enrichment from the previous row is kept until v0 says otherwise; a brand-new row has none yet.
        assertThat(midway.agents.first { it.id == "bc-old" }.summary).isEqualTo("Cached summary")
        assertThat(midway.agents.first { it.id == "bc-old" }.modelDisplayName).isEqualTo("Claude")
        assertThat(midway.agents.first { it.id == "bc-new" }.repoUrl).isNull()
        assertThat(midway.isFromCache).isTrue()

        api.v0Gate!!.complete(Unit)
        api.laterPagesGate!!.complete(Unit)
        refresh.join()
        val done = repo.state.value
        assertThat(done.isFromCache).isFalse()
        assertThat(done.isRefreshing).isFalse()
        assertThat(done.error).isNull()
        assertThat(done.agents.first { it.id == "bc-old" }.summary).isEqualTo("Fresh summary")
        assertThat(done.agents.first { it.id == "bc-new" }.repoUrl).isEqualTo("https://github.com/acme/app")
        assertThat(done.agents.first { it.id == "bc-new" }.runStatus).isEqualTo(RunStatus.FINISHED)

        // ...and the disk now holds the fresh list for the next start.
        awaitUntil { cache.read()?.value?.map { it.name }?.toSet() == setOf("From last time (renamed)", "Started elsewhere") }
    }

    @Test
    fun `where a chat was started is folded in from the account's list, kept across refreshes and written to disk`() = runBlocking<Unit> {
        api.addIdleAgent("bc-slack", "From Slack", "run-1")
        api.addIdleAgent("bc-web", "From the web", "run-2", createdAt = "2026-04-14T10:00:00.000Z")
        val repo = repository()
        repo.refresh()
        assertThat(repo.state.value.agents.map { it.source }).containsExactly(null, null)

        // The account list says; a row it does not name keeps what it had.
        repo.applySources(mapOf("bc-slack" to AgentSource.SLACK, "bc-elsewhere" to AgentSource.API))
        assertThat(repo.state.value.agents.first { it.id == "bc-slack" }.source).isEqualTo(AgentSource.SLACK)
        assertThat(repo.state.value.agents.first { it.id == "bc-web" }.source).isNull()

        // The public list never carries the source, so the next refresh must not forget it.
        repo.refresh()
        assertThat(repo.state.value.agents.first { it.id == "bc-slack" }.source).isEqualTo(AgentSource.SLACK)
        awaitUntil { cache.read()?.value?.firstOrNull { it.id == "bc-slack" }?.source == AgentSource.SLACK }

        // Nothing to say changes nothing (and publishes nothing).
        val before = repo.state.value
        repo.applySources(emptyMap())
        repo.applySources(mapOf("bc-slack" to AgentSource.SLACK))
        assertThat(repo.state.value).isSameInstanceAs(before)

        // Read for one account and applied to the next: ids collide across a team, so this must not land.
        val stale = repo.token()
        repo.reset()
        repo.refresh()
        repo.applySources(mapOf("bc-web" to AgentSource.SLACK), stale)
        assertThat(repo.state.value.agents.first { it.id == "bc-web" }.source).isNull()
    }

    @Test
    fun `the first page is published on its own, the legacy list never gates it, and the pages behind it come on demand`() = runBlocking<Unit> {
        repeat(250) { i -> api.addIdleAgent("bc-%03d".format(i), "Agent $i", "run-$i", createdAt = "2026-04-13T%02d:%02d:00.000Z".format(i / 60, i % 60)) }
        api.pageSize = 100
        api.v0Gate = CompletableDeferred()
        val repo = repository()

        val refresh = scope.launch { repo.refresh() }
        awaitUntil { repo.state.value.agents.size == 100 }
        assertThat(repo.state.value.hasLoaded).isTrue()
        assertThat(repo.state.value.isRefreshing).isTrue()
        // Newest first: the first page holds the most recently created agents, and the rest wait for the reader.
        assertThat(repo.state.value.agents.map { it.name }).contains("Agent 249")
        assertThat(repo.state.value.agents.map { it.name }).doesNotContain("Agent 0")
        assertThat(repo.state.value.agents.all { it.repoUrl == null }).isTrue()

        api.v0Gate!!.complete(Unit)
        refresh.join()
        assertThat(repo.state.value.isRefreshing).isFalse()
        assertThat(repo.state.value.hasMore).isTrue()
        assertThat(repo.state.value.agents).hasSize(100)
        assertThat(repo.state.value.agents.all { it.repoUrl == "https://github.com/acme/app" }).isTrue()
        assertThat(api.listAgentsCalls).isEqualTo(1)
        // The legacy list is read further than the window: its statuses are the running scan (five pages, or the end).
        assertThat(api.listAgentsV0Calls).isEqualTo(3)

        // The reader reaches the end of the list: the next page, on both endpoints, and the page after that.
        api.laterPagesGate = CompletableDeferred()
        val more = scope.launch { repo.loadMore() }
        awaitUntil { repo.state.value.isLoadingMore }
        api.laterPagesGate!!.complete(Unit)
        more.join()
        assertThat(repo.state.value.agents).hasSize(200)
        assertThat(repo.state.value.isLoadingMore).isFalse()
        assertThat(repo.state.value.hasMore).isTrue()
        assertThat(repo.state.value.agents.all { it.repoUrl == "https://github.com/acme/app" }).isTrue()
        assertThat(repo.loadMore()).isEqualTo(RefreshOutcome.Refreshed)
        assertThat(repo.state.value.agents).hasSize(250)
        assertThat(repo.state.value.agents.map { it.name }).contains("Agent 0")
        assertThat(repo.state.value.hasMore).isFalse()
        assertThat(api.listAgentsCalls).isEqualTo(3)
        assertThat(api.listAgentsV0Calls).isEqualTo(5)
        // Past the last page there is nothing to ask for.
        assertThat(repo.loadMore()).isEqualTo(RefreshOutcome.Skipped)
        assertThat(api.listAgentsCalls).isEqualTo(3)

        // A refresh re-reads the pages the reader has been to, so what is on screen is what it refreshes.
        repo.refresh()
        assertThat(api.listAgentsCalls).isEqualTo(6)
        assertThat(repo.state.value.agents).hasSize(250)
    }

    @Test
    fun `a failed refresh keeps what is shown, and a silent one keeps quiet about it`() = runBlocking<Unit> {
        cache.write(listOf(cachedAgent("bc-1", "Kept")))
        api.failListAgents = IOException("boom")
        val repo = repository()

        repo.refresh(silent = true)
        assertThat(repo.state.value.agents.map { it.name }).containsExactly("Kept")
        assertThat(repo.state.value.error).isNull()
        assertThat(repo.state.value.isRefreshing).isFalse()

        repo.refresh()
        assertThat(repo.state.value.agents.map { it.name }).containsExactly("Kept")
        assertThat(repo.state.value.error).isNotNull()

        // The failing v0 list alone is never an error either.
        api.failListAgents = null
        api.failListAgentsV0 = IOException("legacy down")
        api.addIdleAgent("bc-1", "Kept", "run-1")
        repo.refresh()
        assertThat(repo.state.value.error).isNull()
        assertThat(repo.state.value.agents.single().repoUrl).isEqualTo("https://github.com/acme/app")
    }

    @Test
    fun `a complete refresh drops agents deleted elsewhere but keeps ones just launched here`() = runBlocking<Unit> {
        cache.write(listOf(cachedAgent("bc-gone", "Deleted on the web"), cachedAgent("bc-stays", "Still there")))
        api.addIdleAgent("bc-stays", "Still there", "run-1")
        val repo = repository()
        repo.restoreFromCache()
        repo.upsert(cachedAgent("bc-local", "Launched here").copy(createdAtMillis = now - 1_000))

        repo.refresh(depth = RefreshDepth.Quick)
        assertThat(repo.state.value.agents.map { it.id }).containsExactly("bc-gone", "bc-stays", "bc-local")

        repo.refresh()
        assertThat(repo.state.value.agents.map { it.id }).containsExactly("bc-stays", "bc-local")
    }

    @Test
    fun `a complete refresh keeps pinned agents the listing no longer includes`() = runBlocking<Unit> {
        cache.write(listOf(cachedAgent("bc-pinned", "Pinned, past the window"), cachedAgent("bc-unpinned", "Past the window")))
        api.addIdleAgent("bc-stays", "Still there", "run-1")
        prefs.setPinnedIds(setOf("bc-pinned"))
        val repo = repository()
        repo.restoreFromCache()

        repo.refresh()

        assertThat(repo.state.value.agents.map { it.id }).containsExactly("bc-pinned", "bc-stays")
        assertThat(repo.refreshCompleted.value).isEqualTo(1L)

        repo.refresh()
        assertThat(repo.refreshCompleted.value).isEqualTo(2L)
    }

    @Test
    fun `a quick refresh reads only the newest page of each list, and a full one only the pages the list has been paged to`() = runBlocking<Unit> {
        repeat(150) { i -> api.addIdleAgent("bc-%03d".format(i), "Agent $i", "run-$i", createdAt = "2026-04-13T%02d:%02d:00.000Z".format(i / 60, i % 60)) }
        api.pageSize = 100
        val repo = repository()
        repo.refresh(depth = RefreshDepth.Quick)
        assertThat(api.listAgentsCalls).isEqualTo(1)
        // Two legacy pages: the whole list, for the running scan.
        assertThat(api.listAgentsV0Calls).isEqualTo(2)
        assertThat(repo.state.value.agents).hasSize(100)
        assertThat(repo.state.value.hasMore).isTrue()
        repo.refresh()
        assertThat(api.listAgentsCalls).isEqualTo(2)
        assertThat(repo.state.value.agents).hasSize(100)
        repo.loadMore()
        assertThat(repo.state.value.agents).hasSize(150)
        assertThat(repo.state.value.hasMore).isFalse()
        // Two pages have been read, so a full refresh re-reads two.
        repo.refresh()
        assertThat(api.listAgentsCalls).isEqualTo(5)
    }

    @Test
    fun `overlapping refreshes share one fetch`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        api.v0Gate = CompletableDeferred()
        val repo = repository()
        val first = scope.async { repo.refresh() }
        awaitUntil { api.listAgentsCalls == 1 }
        val second = scope.async { repo.refresh() }
        val quick = scope.async { repo.refresh(silent = true, depth = RefreshDepth.Quick) }
        delay(100)
        api.v0Gate!!.complete(Unit)
        first.await(); second.await(); quick.await()
        assertThat(api.listAgentsCalls).isEqualTo(1)
        assertThat(api.listAgentsV0Calls).isEqualTo(1)
    }

    @Test
    fun `a row cached as running stops spinning once v1 reports the agent idle, and a new run is settled by its record`() = runBlocking<Unit> {
        cache.write(listOf(cachedAgent("bc-1", "Was running", runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE, runId = "run-1")))
        api.addIdleAgent("bc-1", "Was running", "run-1")
        api.failListAgentsV0 = IOException("legacy down")
        val repo = repository()
        repo.restoreFromCache()
        assertThat(repo.state.value.agents.single().isRunning).isTrue()

        repo.refresh()
        val row = repo.state.value.agents.single()
        assertThat(row.lifecycle).isEqualTo(AgentLifecycle.IDLE)
        assertThat(row.runStatus).isNull()
        assertThat(row.isRunning).isFalse()
        // A quiet row without a status is not worth a record read.
        assertThat(api.getRunCalls).isEqualTo(0)

        // A new run started elsewhere: the lifecycle says nothing about running (it reads ACTIVE for finished agents
        // too) and the legacy list is down, so the new run's record is read — and it says running.
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "ACTIVE", latestRunId = "run-2", updatedAt = iso(now - 60_000))
        api.runs["run-2"] = RunDto(id = "run-2", agentId = "bc-1", status = "RUNNING", createdAt = iso(now - 60_000), updatedAt = iso(now - 60_000))
        repo.refresh()
        assertThat(api.getRunCalls).isEqualTo(1)
        assertThat(repo.state.value.agents.single().isRunning).isTrue()
        assertThat(repo.state.value.agents.single().latestRunId).isEqualTo("run-2")
    }

    @Test
    fun `a finished agent the server still calls active is shown at rest and left where the server has it`() = runBlocking<Unit> {
        // The everyday shape of a finished agent on v1: lifecycle ACTIVE, an updatedAt that went quiet weeks ago, a
        // latest run that is over. This is the row that used to spin, get streamed, and end up "updated just now".
        api.addIdleAgent("bc-old", "Weeks ago", "run-old", createdAt = "2026-04-13T18:30:00.000Z")
        api.agents["bc-old"] = api.agents.getValue("bc-old").copy(status = "ACTIVE")
        val streamer = FakeRunStreamer()
        session = SessionManager(SecureKeyStore(ApplicationProvider.getApplicationContext()), prefs, CursorBackend(api, streamer, isDemo = false), CursorBackend(api, streamer, isDemo = true))
        val repo = repository()
        val hub = LiveRunHub(session, repo, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, scope = scope)
        val monitor = RunMonitor(repo, hub, runRecord = { a, r -> api.getRun(a, r) }, refreshIntervalMs = 600_000, nowProvider = { now })

        repo.refresh()
        val row = repo.state.value.agents.single()
        assertThat(row.lifecycle).isEqualTo(AgentLifecycle.ACTIVE)
        assertThat(row.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(row.isRunning).isFalse()
        assertThat(row.updatedAtMillis).isEqualTo(parseIsoMillis("2026-04-13T18:30:00.000Z"))
        assertThat(api.getRunCalls).isEqualTo(0)

        monitor.start()
        delay(200)
        assertThat(monitor.state.value.running).isEmpty()
        assertThat(streamer.connections).isEmpty()
        assertThat(repo.state.value.agents.single()).isEqualTo(row)
        monitor.stop()

        // Even without the legacy list — no run status at all — an ACTIVE lifecycle is not taken for running, and a
        // row quiet for that long is not polled for it either.
        cache.clear()
        api.failListAgentsV0 = IOException("legacy down")
        val bare = repository()
        bare.refresh()
        val unknown = bare.state.value.agents.single()
        assertThat(unknown.runStatus).isNull()
        assertThat(unknown.isRunning).isFalse()
        assertThat(unknown.updatedAtMillis).isEqualTo(row.updatedAtMillis)
        assertThat(api.getRunCalls).isEqualTo(0)
    }

    @Test
    fun `a row cached as running for a run that is long over is settled from its record without a stream`() = runBlocking<Unit> {
        cache.write(listOf(cachedAgent("bc-1", "Stale", runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE, runId = "run-1")))
        api.addIdleAgent("bc-1", "Stale", "run-1", createdAt = "2026-04-13T18:30:00.000Z")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "ACTIVE")
        api.failListAgentsV0 = IOException("legacy down")
        val repo = repository()
        repo.restoreFromCache()
        assertThat(repo.state.value.agents.single().isRunning).isTrue()

        // The lifecycle cannot end the remembered status (it reads ACTIVE) and the legacy list is down: the row says
        // running, so its record is read, and the record says the run finished long ago.
        repo.refresh()
        val row = repo.state.value.agents.single()
        assertThat(api.getRunCalls).isEqualTo(1)
        assertThat(row.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(row.isRunning).isFalse()
        assertThat(row.summary).isEqualTo("Done.")
        // The server's updatedAt is the row's activity time: the cache's value is superseded, and nothing says "now".
        assertThat(row.updatedAtMillis).isEqualTo(parseIsoMillis("2026-04-13T18:30:00.000Z"))
    }

    @Test
    fun `a running agent the legacy list still calls finished is settled by its run record`() = runBlocking<Unit> {
        // The legacy list is one status per agent and may lag a follow-up: here it says the agent is done while its
        // latest run — active a minute ago by the agent's updatedAt — is still going.
        api.addRunningAgent("bc-1", "Follow-up in progress", "run-1", createdAt = iso(now - 60_000))
        api.v0["bc-1"] = V0AgentDto(id = "bc-1", name = "Follow-up in progress", status = "FINISHED", target = V0TargetDto(branchName = "cursor/x"))
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = repository(scope = firstScope)
        repo.refresh()
        val cold = repo.state.value.agents.single()
        assertThat(api.getRunCalls).isEqualTo(1)
        assertThat(cold.isRunning).isTrue()
        assertThat(cold.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(cold.branchName).isEqualTo("cursor/x")

        // The disk remembers the previous run as finished; the server has moved on to a new one since, and the legacy
        // list still has not: the new run's record decides.
        awaitUntil { cache.read()?.value?.single()?.isRunning == true }
        // The first repository persists the current list on every conflated turn of its collector (the legacy page,
        // then the record's settle), so a turn can still be pending after the write just read; it is stopped and
        // waited for before the disk is rewritten by hand, or the running row lands on top of the finished one.
        firstScope.coroutineContext.job.cancelAndJoin()
        cache.write(listOf(cachedAgent("bc-1", "Follow-up in progress", runStatus = RunStatus.FINISHED, lifecycle = AgentLifecycle.IDLE, runId = "run-0")))
        val next = repository()
        next.restoreFromCache()
        assertThat(next.state.value.agents.single().isRunning).isFalse()
        next.refresh()
        assertThat(next.state.value.agents.single().isRunning).isTrue()
        assertThat(next.state.value.agents.single().latestRunId).isEqualTo("run-1")
    }

    @Test
    fun `an activity time stamped here long after the server's is put back on the next refresh`() = runBlocking<Unit> {
        // A row the old build had marked "updated just now" for a run that finished weeks earlier, persisted as such.
        cache.write(listOf(cachedAgent("bc-1", "Inflated", runId = "run-1").copy(updatedAtMillis = now)))
        api.addIdleAgent("bc-1", "Inflated", "run-1", createdAt = "2026-04-13T18:30:00.000Z")
        val repo = repository()
        repo.restoreFromCache()
        assertThat(repo.state.value.agents.single().updatedAtMillis).isEqualTo(now)

        repo.refresh()
        assertThat(repo.state.value.agents.single().updatedAtMillis).isEqualTo(parseIsoMillis("2026-04-13T18:30:00.000Z"))

        // Whereas a follow-up sent here moments ago keeps its place while the list catches up with it.
        val sent = repo.followUp("bc-1", "Again")
        assertThat(sent.isSuccess).isTrue()
        assertThat(repo.state.value.agents.single().updatedAtMillis).isEqualTo(now)
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(updatedAt = iso(now - 30_000))
        repo.refresh()
        assertThat(repo.state.value.agents.single().updatedAtMillis).isEqualTo(now)
    }

    @Test
    fun `an error the run reported survives a legacy list that only knows finished`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Broke", "run-1")
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "ERROR", result = "Build failed")
        val repo = repository()
        repo.refresh()
        // The list alone cannot tell: idle it is, finished says the legacy record.
        assertThat(repo.state.value.agents.single().runStatus).isEqualTo(RunStatus.FINISHED)

        repo.loadDetail("bc-1")
        assertThat(repo.state.value.agents.single().isError).isTrue()
        repo.refresh()
        assertThat(repo.state.value.agents.single().isError).isTrue()
        assertThat(repo.state.value.agents.single().summary).isEqualTo("Build failed")
    }

    /**
     * The run this device last saw end outranks every later word that calls it active — not only the run records
     * read here (`known`), but anything that reaches the list: a patch from elsewhere, a legacy `/v0` status filling
     * in a status this build could not read, a refresh's page. Applied at publication, so the row can never be seen
     * running on a turn that is over. A newer run the server names is followed as it is.
     */
    @Test
    fun `a row cannot be put back to running for the run the list saw end, whoever says so`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        val repo = repository()
        repo.refresh()
        assertThat(repo.agent("bc-1")!!.isRunning).isTrue()

        // The hub saw the run end in a status this build cannot read; the row is settled as the hub does it.
        repo.noteRunEnded("bc-1", "run-1", RunStatus.UNKNOWN)
        repo.patch("bc-1") { it.copy(runStatus = RunStatus.UNKNOWN) }
        assertThat(repo.agent("bc-1")!!.isRunning).isFalse()
        assertThat(repo.endedStatus("bc-1", "run-1")).isEqualTo(RunStatus.UNKNOWN)
        assertThat(repo.endedStatus("bc-1", "run-2")).isNull()

        // A patch that calls the run active again lands as the run ended.
        repo.patch("bc-1") { it.copy(runStatus = RunStatus.RUNNING) }
        assertThat(repo.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.UNKNOWN)
        // So does a refresh whose legacy list, a poll behind, still calls the agent running — the one source that
        // fills in a status this build could not read — and whose run record still says so.
        repo.refresh()
        assertThat(repo.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.UNKNOWN)
        assertThat(repo.agent("bc-1")!!.isRunning).isFalse()
        // And a run record read by id.
        assertThat(repo.loadDetail("bc-1").getOrThrow().isRunning).isFalse()

        // A newer run is the server's word on a new turn, taken as it comes.
        api.runs["run-2"] = api.runs.getValue("run-1").copy(id = "run-2", status = "RUNNING", createdAt = "2026-04-13T19:30:00.000Z", updatedAt = "2026-04-13T19:30:00.000Z")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(latestRunId = "run-2", updatedAt = "2026-04-13T19:30:00.000Z")
        repo.refresh()
        assertThat(repo.agent("bc-1")!!.let { it.isRunning && it.latestRunId == "run-2" }).isTrue()
    }

    /**
     * An agent runs one turn at a time, so a run begun before the one this device saw end had ended before it began:
     * a record that still calls the older run active — the run page a chat's load read before the Stop, landing after
     * it — is over by that alone, whatever it says. Placed on the server's clock, from the records the list has read;
     * a run the list never read the record of cannot be placed, and only its own end is remembered.
     */
    @Test
    fun `a run begun before the one the list saw end is over too, whatever a record read before says`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        val repo = repository()
        repo.refresh()
        val older = api.runs.getValue("run-1")
        // The turn moved on: run-2 began, and the list read its record — a settle of the row by the queue, a detail.
        api.runs["run-2"] = older.copy(id = "run-2", createdAt = "2026-04-13T19:30:00.000Z", updatedAt = "2026-04-13T19:30:00.000Z")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(latestRunId = "run-2", updatedAt = "2026-04-13T19:30:00.000Z")
        assertThat(repo.loadDetail("bc-1").getOrThrow().latestRunId).isEqualTo("run-2")
        // Nothing has ended yet: run-1's record from before stands for what it says.
        assertThat(repo.endedBefore("bc-1", older)).isFalse()

        // Run-2 is stopped here. Run-1 began an hour before it, so it is over too; run-2 itself, and a run begun after it, are not "before".
        assertThat(repo.cancelRun("bc-1", "run-2").isSuccess).isTrue()
        assertThat(repo.endedStatus("bc-1", "run-2")).isEqualTo(RunStatus.CANCELLED)
        assertThat(repo.endedStatus("bc-1", "run-1")).isNull()
        assertThat(repo.endedBefore("bc-1", older)).isTrue()
        assertThat(repo.endedBefore("bc-1", api.runs.getValue("run-2"))).isFalse()
        assertThat(repo.endedBefore("bc-1", older.copy(id = "run-3", createdAt = "2026-04-13T20:30:00.000Z", updatedAt = "2026-04-13T20:30:00.000Z"))).isFalse()
        // A record whose start cannot be read is not placed.
        assertThat(repo.endedBefore("bc-1", older.copy(createdAt = ""))).isFalse()

        // A detail read before the Stop lands after it, naming run-1 as the agent's latest and running: the row keeps
        // its own word — the run it saw end, ended — and takes from the record only what is not about that.
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(latestRunId = "run-1", updatedAt = older.updatedAt, name = "Renamed meanwhile")
        api.runs["run-1"] = older
        val settled = repo.loadDetail("bc-1").getOrThrow()
        assertThat(settled.name).isEqualTo("Renamed meanwhile")
        assertThat(settled.latestRunId).isEqualTo("run-2")
        assertThat(settled.runStatus).isEqualTo(RunStatus.CANCELLED)
        assertThat(settled.isRunning).isFalse()
        assertThat(settled.updatedAtMillis).isEqualTo(parseIsoMillis("2026-04-13T19:30:00.000Z"))

        // Run-1's own end, told late — its replay finishing, followed for its trace — is not the agent's last end:
        // the memory keeps run-2's, whose stale records are the ones still on their way.
        repo.noteRunEnded("bc-1", "run-1", RunStatus.FINISHED)
        assertThat(repo.endedStatus("bc-1", "run-2")).isEqualTo(RunStatus.CANCELLED)
        assertThat(repo.endedStatus("bc-1", "run-1")).isNull()
        assertThat(repo.endedBefore("bc-1", older)).isTrue()

        // A run the list never read the record of ends without a place in time: only its own end is remembered.
        repo.noteRunEnded("bc-1", "run-9", RunStatus.FINISHED)
        assertThat(repo.endedStatus("bc-1", "run-9")).isEqualTo(RunStatus.FINISHED)
        assertThat(repo.endedBefore("bc-1", older)).isFalse()
    }

    /**
     * The fetch's own publications, its bookkeeping and its write to the disk are all guarded, but by two different
     * things: the repository's generation guards what is in memory, and the cache's generation guards the file — a
     * write samples the list and the cache's generation under the publish lock and writes outside it, so a reset
     * that lands in between cannot be seen by the write itself. The sign-out (see `AppGraph`) invalidates the caches
     * before it resets the list and wipes them after, which is what makes the file safe; this test does the same,
     * with the write held in flight across the reset so the order is the test's and not the scheduler's. Run with
     * the reset alone it flaked on CI (35523594515): the held write landed after the reset, and the disk had the
     * previous account's list for the next one to restore.
     */
    @Test
    fun `a fetch that outlives a reset publishes nothing into the list that replaced it`() = runBlocking<Unit> {
        val disk = HeldDispatcher()
        val diskCache = JsonDiskCache(folder.newFolder("held-agents"), dispatcher = disk.dispatcher)
        val cache = AgentListCache(diskCache)
        try {
            api.addIdleAgent("bc-1", "Previous account", "run-1")
            api.v0Gate = CompletableDeferred()
            // The write follows the first page by half a second here, so the hold below is armed before it starts.
            val repo = AgentRepository(session, prefs, AttachmentStore(ApplicationProvider.getApplicationContext()), cache, scope, persistDelayMs = 500)
            val refresh = scope.launch { repo.refresh() }
            awaitUntil { repo.state.value.agents.size == 1 }
            // The first page is on its way to the disk: the write has sampled the list and waits for the disk.
            disk.hold = true
            awaitUntil { disk.heldCount > 0 }

            // The sign-out's order: the caches invalidated, then the list reset.
            diskCache.invalidate()
            repo.reset()
            assertThat(repo.state.value).isEqualTo(AgentListState())

            // Everything the fetch had left to do runs from here: its remaining pages, the legacy enrichment, the
            // run-status pass and the bookkeeping that says a fetch completed — and the write it had in flight. None
            // of it belongs to this session.
            api.v0Gate!!.complete(Unit)
            disk.release()
            refresh.join()
            assertThat(repo.state.value).isEqualTo(AgentListState())
            assertThat(repo.lastRefreshedAt).isEqualTo(0L)
            // The cue the account's pins are synced on: the previous account's fetch must not be the one that gives it.
            assertThat(repo.refreshCompleted.value).isEqualTo(0L)
            // Nor may the old list have reached the disk for the next account to restore: the held write met a wipe
            // under way and was refused. (Without the invalidate above it lands here, the reset notwithstanding —
            // the flake — and only the wipe below would take it off the disk.)
            assertThat(cache.read()).isNull()
            diskCache.clear()
            assertThat(cache.read()).isNull()

            // The next refresh belongs to the new session and lands normally, on disk too.
            repo.refresh()
            assertThat(repo.state.value.agents.map { it.name }).containsExactly("Previous account")
            assertThat(repo.state.value.isRefreshing).isFalse()
            assertThat(repo.refreshCompleted.value).isEqualTo(1L)
            awaitUntil { cache.read()?.value?.map { it.name } == listOf("Previous account") }
        } finally {
            disk.close()
        }
    }

    @Test
    fun `a detail load that outlives a reset does not put its row back`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Previous account", "run-1")
        val repo = repository()
        repo.refresh()
        assertThat(repo.state.value.agents).hasSize(1)

        api.getAgentGate = CompletableDeferred()
        val detail = scope.async { repo.loadDetail("bc-1") }
        awaitUntil { api.getAgentCalls == 1 }
        repo.reset()
        api.getAgentGate!!.complete(Unit)
        detail.await()
        assertThat(repo.state.value).isEqualTo(AgentListState())
    }

    @Test
    fun `a reset that arrives while a row is being rewritten still empties the list`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Previous account", "run-1")
        val repo = repository()
        repo.refresh()

        // The reset is released while the patch is between reading the row and writing it back: whichever order the
        // two settle in, the row must not be in the list the next account starts from.
        val inTransform = CountDownLatch(1)
        val resetting = CountDownLatch(1)
        val resetter = Thread {
            inTransform.await()
            resetting.countDown()
            repo.reset()
        }
        resetter.start()
        repo.patch("bc-1") { agent ->
            inTransform.countDown()
            resetting.await()
            agent.copy(name = "Renamed")
        }
        resetter.join(5_000)
        assertThat(repo.state.value).isEqualTo(AgentListState())
    }

    @Test
    fun `a row a caller collected before a reset is refused afterwards`() = runBlocking<Unit> {
        val repo = repository()
        val startedIn = repo.token()
        repo.reset()
        repo.upsert(cachedAgent("bc-1", "Previous account"), startedIn)
        assertThat(repo.state.value.agents).isEmpty()

        repo.upsert(cachedAgent("bc-2", "This account"))
        assertThat(repo.state.value.agents.map { it.id }).containsExactly("bc-2")
    }

    @Test
    fun `the run monitor ignores rows restored from disk until a fetch confirms them`() = runBlocking<Unit> {
        cache.write(
            listOf(
                cachedAgent("bc-stale", "Finished hours ago", runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE, runId = "run-stale"),
                cachedAgent("bc-live", "Still going", runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE, runId = "run-live"),
            ),
        )
        api.addIdleAgent("bc-stale", "Finished hours ago", "run-stale")
        api.addRunningAgent("bc-live", "Still going", "run-live")
        val streamer = FakeRunStreamer()
        session = SessionManager(SecureKeyStore(ApplicationProvider.getApplicationContext()), prefs, CursorBackend(api, streamer, isDemo = false), CursorBackend(api, streamer, isDemo = true))
        val repo = repository()
        val hub = LiveRunHub(session, repo, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, scope = scope)
        val monitor = RunMonitor(repo, hub, runRecord = { a, r -> api.getRun(a, r) }, refreshIntervalMs = 600_000, nowProvider = { now })

        repo.restoreFromCache()
        assertThat(repo.state.value.agents.count { it.isRunning }).isEqualTo(2)
        monitor.start()
        delay(150)
        // Nothing is tracked and no stream is opened for the stale run while the list is unconfirmed.
        assertThat(monitor.state.value.running).isEmpty()
        assertThat(streamer.connections).isEmpty()

        repo.refresh()
        awaitUntil { monitor.state.value.running.map { it.agentId } == listOf("bc-live") }
        // The tracker is registered before its stream connects.
        awaitUntil { streamer.connections.isNotEmpty() }
        assertThat(streamer.connections.toList()).containsExactly("run-live")
        assertThat(repo.state.value.agents.first { it.id == "bc-stale" }.isRunning).isFalse()
        monitor.stop()
    }

    @Test
    fun `archive writes the account flag and the public lifecycle`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        val account = FakeLifecycleApi()
        val repo = repository(account = account)
        repo.refresh()
        assertThat(repo.archive("bc-1").isSuccess).isTrue()
        assertThat(account.archives).containsExactly("bc-1")
        assertThat(repo.state.value.agents.single().lifecycle).isEqualTo(AgentLifecycle.ARCHIVED)
        assertThat(api.agents.getValue("bc-1").status).isEqualTo("ARCHIVED")
    }

    @Test
    fun `an account archive that fails still lands when the public write succeeds`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        val account = FakeLifecycleApi().apply { failing = IOException("account down") }
        val repo = repository(account = account)
        repo.refresh()
        assertThat(repo.archive("bc-1").isSuccess).isTrue()
        assertThat(repo.state.value.agents.single().lifecycle).isEqualTo(AgentLifecycle.ARCHIVED)
    }

    @Test
    fun `rename goes through the account service and updates the row`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Old title", "run-1")
        val account = FakeLifecycleApi()
        val repo = repository(account = account)
        repo.refresh()
        assertThat(repo.rename("bc-1", "  Billing fix  ").isSuccess).isTrue()
        assertThat(account.renames).containsExactly("bc-1" to "Billing fix")
        assertThat(repo.state.value.agents.single().name).isEqualTo("Billing fix")
    }

    @Test
    fun `a blank rename is rejected without a network call`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Old title", "run-1")
        val account = FakeLifecycleApi()
        val repo = repository(account = account)
        repo.refresh()
        assertThat(repo.rename("bc-1", "   ").isFailure).isTrue()
        assertThat(account.renames).isEmpty()
        assertThat(repo.state.value.agents.single().name).isEqualTo("Old title")
    }

    @Test
    fun `account snapshots overlay a rename and an archive the public list missed`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Old title", "run-1")
        api.addIdleAgent("bc-2", "Still open", "run-2")
        val repo = repository()
        repo.refresh()
        repo.applyAccountSnapshots(
            listOf(
                ComposerSnapshot("bc-1", name = "Renamed elsewhere", archived = true),
                ComposerSnapshot("bc-2", name = "Still open", archived = false),
            ),
        )
        val byId = repo.state.value.agents.associateBy { it.id }
        assertThat(byId.getValue("bc-1").name).isEqualTo("Renamed elsewhere")
        assertThat(byId.getValue("bc-1").lifecycle).isEqualTo(AgentLifecycle.ARCHIVED)
        assertThat(byId.getValue("bc-2").lifecycle).isEqualTo(AgentLifecycle.IDLE)
    }

    @Test
    fun `account snapshots say which chats are Projects and whose children, and a refresh keeps the answer`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Billing launch", "run-1")
        api.addIdleAgent("bc-2", "Webhook worker", "run-2")
        api.addIdleAgent("bc-3", "Plain chat", "run-3")
        val repo = repository()
        repo.refresh()
        val appearance = ProjectAppearance("rocket", "purple")
        repo.applyAccountSnapshots(
            listOf(
                ComposerSnapshot("bc-1", isProject = true, projectAppearance = appearance),
                ComposerSnapshot("bc-2", parent = AgentParent("bc-1", AgentParentKind.PROJECT_WORKER)),
                ComposerSnapshot("bc-3"),
            ),
        )
        fun agents() = repo.state.value.agents.associateBy { it.id }
        assertThat(agents().getValue("bc-1").isProject).isTrue()
        assertThat(agents().getValue("bc-1").isProjectRoot).isTrue()
        assertThat(agents().getValue("bc-1").projectAppearance).isEqualTo(appearance)
        assertThat(agents().getValue("bc-2").parent).isEqualTo(AgentParent("bc-1", AgentParentKind.PROJECT_WORKER))
        assertThat(agents().getValue("bc-2").isProject).isFalse()
        assertThat(agents().getValue("bc-3").isProject).isFalse()
        assertThat(agents().getValue("bc-3").parent).isNull()

        // The public list knows nothing of Projects; a page refresh must not make the rows forget.
        repo.refresh()
        assertThat(agents().getValue("bc-1").isProject).isTrue()
        assertThat(agents().getValue("bc-1").projectAppearance).isEqualTo(appearance)
        assertThat(agents().getValue("bc-2").parent).isEqualTo(AgentParent("bc-1", AgentParentKind.PROJECT_WORKER))
        awaitUntil { cache.read()?.value?.firstOrNull { it.id == "bc-1" }?.isProject == true }
        assertThat(cache.read()?.value?.first { it.id == "bc-2" }?.parent).isEqualTo(AgentParent("bc-1", AgentParentKind.PROJECT_WORKER))

        // The account's later word replaces the earlier one: a Project unmade, a worker released.
        repo.applyAccountSnapshots(listOf(ComposerSnapshot("bc-1"), ComposerSnapshot("bc-2")))
        assertThat(agents().getValue("bc-1").isProject).isFalse()
        assertThat(agents().getValue("bc-1").projectAppearance).isNull()
        assertThat(agents().getValue("bc-2").parent).isNull()
    }

    @Test
    fun `lineage from a transcript fills in what nothing has placed, lineage from the account replaces it, and both wait for rows still to come`() = runBlocking<Unit> {
        api.addIdleAgent("bc-c", "Coordinator", "run-c")
        api.addIdleAgent("bc-w", "Worker", "run-w")
        api.addIdleAgent("bc-x", "Placed elsewhere", "run-x")
        val repo = repository()
        repo.refresh()
        repo.applyAccountSnapshots(listOf(ComposerSnapshot("bc-x", parent = AgentParent("bc-other", AgentParentKind.SIDE_CHAT))))
        fun agent(id: String) = repo.state.value.agents.first { it.id == id }

        // The coordinator's own transcript: a hint. It places the unplaced and leaves the account's word alone —
        // and makes no Project of the chat that named them: a root takes the record's word.
        repo.applyLineage("bc-c", mapOf("bc-w" to AgentParentKind.PROJECT_WORKER, "bc-x" to AgentParentKind.PROJECT_WORKER, "bc-late" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agent("bc-c").scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agent("bc-c").isProject).isFalse()
        assertThat(agent("bc-c").looksLikeProject).isFalse()
        assertThat(agent("bc-w").parent).isEqualTo(AgentParent("bc-c", AgentParentKind.PROJECT_WORKER))
        assertThat(agent("bc-w").scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(agent("bc-x").parent).isEqualTo(AgentParent("bc-other", AgentParentKind.SIDE_CHAT))

        // A worker the coordinator just created reaches the list a refresh later, already placed.
        api.addIdleAgent("bc-late", "Late worker", "run-late")
        repo.refresh()
        assertThat(agent("bc-late").parent).isEqualTo(AgentParent("bc-c", AgentParentKind.PROJECT_WORKER))
        assertThat(agent("bc-late").scope).isEqualTo(AgentScope.PROJECT_CHILD)

        // The account's list speaks for what it names: a record that names a parent or a Project places the row; a
        // record silent on lineage is the account's word against a transcript's mention, and takes the hint back —
        // while a membership's or a record's own placement stands (it is how workers without a `managerAgentId` leaked).
        repo.applyAccountSnapshots(listOf(ComposerSnapshot("bc-c", isProject = true), ComposerSnapshot("bc-w"), ComposerSnapshot("bc-late", parent = AgentParent("bc-c", AgentParentKind.PROJECT_WORKER))))
        assertThat(agent("bc-w").scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agent("bc-w").parent).isNull()
        assertThat(agent("bc-late").scopeSignal).isEqualTo(LineageSignal.ACCOUNT_RECORD)
        assertThat(agent("bc-c").isProject).isTrue()
        // The hint is refused from then on; a record naming a parent places the row, and no hint takes it elsewhere.
        repo.applyLineage("bc-c", mapOf("bc-w" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agent("bc-w").scope).isEqualTo(AgentScope.PRIMARY)
        repo.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w", parent = AgentParent("bc-other", AgentParentKind.SUBAGENT))))
        assertThat(agent("bc-w").parent).isEqualTo(AgentParent("bc-other", AgentParentKind.SUBAGENT))
        repo.applyLineage("bc-c", mapOf("bc-w" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agent("bc-w").parent).isEqualTo(AgentParent("bc-other", AgentParentKind.SUBAGENT))
        // The record's own word withdrawn releases the row; the account's membership places it again, and the
        // classification rides refreshes and the disk.
        repo.applyAccountSnapshots(listOf(ComposerSnapshot("bc-w")))
        assertThat(agent("bc-w").scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agent("bc-w").parent).isNull()
        repo.applyLineage("bc-c", mapOf("bc-w" to AgentParentKind.PROJECT_WORKER), authoritative = true)
        assertThat(agent("bc-w").scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(agent("bc-w").scopeSignal).isEqualTo(LineageSignal.MEMBERSHIP)
        repo.refresh()
        assertThat(agent("bc-w").scope).isEqualTo(AgentScope.PROJECT_CHILD)
        awaitUntil { cache.read()?.value?.firstOrNull { it.id == "bc-w" }?.scope == AgentScope.PROJECT_CHILD }

        // A Project that is itself somebody's child stays where its parent is, whoever names it as a root.
        repo.applyAccountSnapshots(listOf(ComposerSnapshot("bc-c", isProject = true, parent = AgentParent("bc-up", AgentParentKind.SUBAGENT))))
        repo.applyLineage("bc-c", emptyMap(), authoritative = true)
        assertThat(agent("bc-c").scope).isEqualTo(AgentScope.PROJECT_CHILD)
    }

    @Test
    fun `local changes reach the disk without a refresh`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        val repo = repository(persistDelayMs = 10)
        repo.refresh()
        awaitUntil { cache.read()?.value?.single()?.lifecycle == AgentLifecycle.IDLE }
        repo.archive("bc-1")
        awaitUntil { cache.read()?.value?.single()?.lifecycle == AgentLifecycle.ARCHIVED }
    }

    @Test
    fun `the demo backend never touches the disk cache`() = runBlocking<Unit> {
        cache.write(listOf(cachedAgent("bc-real", "Real account")))
        session.enterDemo()
        api.addIdleAgent("bc-demo", "Demo", "run-1")
        val repo = repository(persistDelayMs = 10)
        repo.refresh()
        assertThat(repo.state.value.agents.map { it.id }).containsExactly("bc-demo")
        delay(100)
        assertThat(cache.read()!!.value.map { it.id }).containsExactly("bc-real")
    }

    // -- session switches (from main's suite) --------------------------------------------------------------------------

    private val demoApi = FakeCursorApi()
    private val realApi = FakeCursorApi()

    /** A session with a distinct fake per backend, signed into the demo one, with one running agent on each side. */
    private suspend fun demoSession(): AgentRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val real = CursorBackend(realApi, FakeRunStreamer(), isDemo = false)
        val demo = CursorBackend(demoApi, FakeRunStreamer(), isDemo = true)
        session = SessionManager(SecureKeyStore(context), prefs, real, demo)
        session.enterDemo()
        demoApi.addRunningAgent("bc-demo", "Demo agent", "run-demo")
        realApi.addRunningAgent("bc-real", "Real agent", "run-real")
        return repository()
    }

    @Test
    fun `a refresh re-reads the window and reconciles inside it, and a page loaded on demand reconciles the stretch it covers`() = runBlocking<Unit> {
        val agents = repository()
        // Twelve agents, two per page: the refresh reads one page, the reader pages to the rest.
        api.pageSize = 2
        val start = now
        repeat(12) { i -> api.addIdleAgent("bc-%02d".format(i), "Agent $i", "run-$i", createdAt = iso(start - i * 60_000L)) }

        agents.refresh(depth = RefreshDepth.Full)
        assertThat(agents.state.value.agents).hasSize(2)
        assertThat(api.listAgentsCalls).isEqualTo(1)
        assertThat(agents.state.value.hasMore).isTrue()
        repeat(5) { assertThat(agents.loadMore()).isEqualTo(RefreshOutcome.Refreshed) }
        assertThat(agents.state.value.agents).hasSize(12)
        assertThat(agents.state.value.hasMore).isFalse()
        assertThat(api.listAgentsCalls).isEqualTo(6)

        // Deleted and renamed elsewhere, beyond the five pages a refresh re-reads at most. The refresh neither sees
        // nor reconciles them: a row the pass never reached is not a row the server no longer has.
        api.agents.remove("bc-11")
        api.v0.remove("bc-11")
        api.addIdleAgent("bc-10", "Renamed elsewhere", "run-10", createdAt = iso(start - 10 * 60_000L))
        now += 60_000
        agents.refresh(depth = RefreshDepth.Full)
        assertThat(api.listAgentsCalls).isEqualTo(11)
        assertThat(agents.agent("bc-11")).isNotNull()
        assertThat(agents.agent("bc-10")?.name).isEqualTo("Agent 10")

        // Paging to the end again covers the stretch beyond the window: the rename lands and the deleted row goes.
        assertThat(agents.state.value.hasMore).isTrue()
        agents.loadMore()
        assertThat(agents.agent("bc-10")?.name).isEqualTo("Renamed elsewhere")
        assertThat(agents.agent("bc-11")).isNull()
        assertThat(agents.state.value.agents).hasSize(11)
        assertThat(agents.state.value.hasMore).isFalse()

        // A row deleted inside the window a refresh re-reads goes with that refresh (one old enough not to be a
        // creation the listing is merely lagging).
        api.agents.remove("bc-07")
        api.v0.remove("bc-07")
        agents.refresh(depth = RefreshDepth.Full)
        assertThat(agents.agent("bc-07")).isNull()
        assertThat(agents.state.value.agents).hasSize(10)
    }

    @Test
    fun `refreshIfStale skips a list fetched recently and fetches again after a reset`() = runBlocking<Unit> {
        val agents = demoSession()
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-demo")
        assertThat(agents.lastRefreshedAt).isGreaterThan(0L)
        assertThat(demoApi.listAgentsCalls).isEqualTo(1)

        agents.refreshIfStale(maxAgeMs = 60_000)
        assertThat(demoApi.listAgentsCalls).isEqualTo(1)

        agents.reset()
        assertThat(agents.state.value.agents).isEmpty()
        assertThat(agents.state.value.hasLoaded).isFalse()
        assertThat(agents.lastRefreshedAt).isEqualTo(0L)

        agents.refreshIfStale(maxAgeMs = 60_000)
        assertThat(demoApi.listAgentsCalls).isEqualTo(2)
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-demo")
    }

    @Test
    fun `signing out of one backend and into another never shows the previous list`() = runBlocking<Unit> {
        val agents = demoSession()
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-demo")

        // What AppGraph's sign-out hook does, then a sign-in with a key.
        agents.reset()
        session.signOut()
        assertThat(agents.state.value.agents).isEmpty()
        assertThat(session.signIn("key_test").isSuccess).isTrue()

        // The list is stale for the new backend no matter how recently the old one was fetched.
        agents.refreshIfStale(maxAgeMs = Long.MAX_VALUE)
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-real")
        assertThat(realApi.listAgentsCalls).isEqualTo(1)
    }

    @Test
    fun `a cached list from another backend is dropped before the new fetch publishes`() = runBlocking<Unit> {
        val agents = demoSession()
        agents.refresh()
        session.signOut()
        assertThat(session.signIn("key_test").isSuccess).isTrue()
        // Without the explicit reset the demo rows linger until the next refresh, which must replace, not merge.
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-real")
    }

    @Test
    fun `duplicate summaries across pages collapse to one row`() = runBlocking<Unit> {
        val agents = demoSession()
        demoApi.agents["bc-dup"] = demoApi.agents.getValue("bc-demo")
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-demo")
    }
}
