package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
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
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import java.io.IOException
import java.time.Instant
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

    private fun repository(persistDelayMs: Long = 10) =
        AgentRepository(session, prefs, AttachmentStore(ApplicationProvider.getApplicationContext()), cache, scope, persistDelayMs)

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
    fun `pages are published as they arrive and the legacy list never gates them`() = runBlocking<Unit> {
        repeat(250) { i -> api.addIdleAgent("bc-%03d".format(i), "Agent $i", "run-$i", createdAt = "2026-04-13T%02d:%02d:00.000Z".format(i / 60, i % 60)) }
        api.pageSize = 100
        api.v0Gate = CompletableDeferred()
        api.laterPagesGate = CompletableDeferred()
        val repo = repository()

        val refresh = scope.launch { repo.refresh() }
        awaitUntil { repo.state.value.agents.size == 100 }
        assertThat(repo.state.value.hasLoaded).isTrue()
        assertThat(repo.state.value.isRefreshing).isTrue()
        // Page 1 is on screen while page 2 is already requested (and held by the gate).
        awaitUntil { api.listAgentsCalls == 2 }
        // Newest first: the first page holds the most recently created agents.
        assertThat(repo.state.value.agents.map { it.name }).contains("Agent 249")
        assertThat(repo.state.value.agents.map { it.name }).doesNotContain("Agent 0")

        api.laterPagesGate!!.complete(Unit)
        awaitUntil { repo.state.value.agents.size == 250 }
        assertThat(repo.state.value.isRefreshing).isTrue()
        assertThat(repo.state.value.agents.all { it.repoUrl == null }).isTrue()

        api.v0Gate!!.complete(Unit)
        refresh.join()
        assertThat(repo.state.value.isRefreshing).isFalse()
        assertThat(repo.state.value.agents.all { it.repoUrl == "https://github.com/acme/app" }).isTrue()
        assertThat(api.listAgentsCalls).isEqualTo(3)
        assertThat(api.listAgentsV0Calls).isEqualTo(3)
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
    fun `a quick refresh reads only the newest page of each list`() = runBlocking<Unit> {
        repeat(150) { i -> api.addIdleAgent("bc-%03d".format(i), "Agent $i", "run-$i", createdAt = "2026-04-13T%02d:%02d:00.000Z".format(i / 60, i % 60)) }
        api.pageSize = 100
        val repo = repository()
        repo.refresh(depth = RefreshDepth.Quick)
        assertThat(api.listAgentsCalls).isEqualTo(1)
        assertThat(api.listAgentsV0Calls).isEqualTo(1)
        assertThat(repo.state.value.agents).hasSize(100)
        repo.refresh()
        assertThat(repo.state.value.agents).hasSize(150)
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
        val repo = repository()
        repo.refresh()
        val cold = repo.state.value.agents.single()
        assertThat(api.getRunCalls).isEqualTo(1)
        assertThat(cold.isRunning).isTrue()
        assertThat(cold.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(cold.branchName).isEqualTo("cursor/x")

        // The disk remembers the previous run as finished; the server has moved on to a new one since, and the legacy
        // list still has not: the new run's record decides.
        awaitUntil { cache.read()?.value?.single()?.isRunning == true }
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

    @Test
    fun `a fetch that outlives a reset publishes nothing into the list that replaced it`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Previous account", "run-1")
        api.v0Gate = CompletableDeferred()
        val repo = repository()
        val refresh = scope.launch { repo.refresh() }
        awaitUntil { repo.state.value.agents.size == 1 }

        repo.reset()
        assertThat(repo.state.value).isEqualTo(AgentListState())
        api.v0Gate!!.complete(Unit)
        refresh.join()
        assertThat(repo.state.value).isEqualTo(AgentListState())

        // The next refresh belongs to the new session and lands normally.
        repo.refresh()
        assertThat(repo.state.value.agents.map { it.name }).containsExactly("Previous account")
        assertThat(repo.state.value.isRefreshing).isFalse()
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
    fun `a windowed refresh stops at its window, and a complete listing reconciles what lies beyond it`() = runBlocking<Unit> {
        val agents = repository()
        // Twelve agents, two per page: the windowed pass reads its five pages, a complete listing reads all six.
        api.pageSize = 2
        val start = now
        repeat(12) { i -> api.addIdleAgent("bc-%02d".format(i), "Agent $i", "run-$i", createdAt = iso(start - i * 60_000L)) }

        // Nothing has ever been listed to the end, so the first pass does that rather than stopping at the window.
        agents.refresh(depth = RefreshDepth.Full)
        assertThat(agents.state.value.agents).hasSize(12)
        assertThat(api.listAgentsCalls).isEqualTo(6)

        // Deleted and renamed beyond the window. A windowed pass neither sees nor reconciles them: a row the pass
        // never reached is not a row the server no longer has.
        api.agents.remove("bc-11")
        api.v0.remove("bc-11")
        api.addIdleAgent("bc-10", "Renamed elsewhere", "run-10", createdAt = iso(start - 10 * 60_000L))
        now += 60_000
        agents.refresh(depth = RefreshDepth.Full)
        assertThat(api.listAgentsCalls).isEqualTo(11)
        assertThat(agents.agent("bc-11")).isNotNull()
        assertThat(agents.agent("bc-10")?.name).isEqualTo("Agent 10")

        // An hour on, the windowed pass is promoted to a complete listing and both land.
        now += 61 * 60_000
        agents.refresh(depth = RefreshDepth.Full)
        assertThat(agents.agent("bc-11")).isNull()
        assertThat(agents.agent("bc-10")?.name).isEqualTo("Renamed elsewhere")

        // And a refresh asked for by hand pages to the end whenever it is asked, hour or no hour.
        api.agents.remove("bc-10")
        api.v0.remove("bc-10")
        now += 60_000
        agents.refresh(depth = RefreshDepth.Deep)
        assertThat(agents.agent("bc-10")).isNull()
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
