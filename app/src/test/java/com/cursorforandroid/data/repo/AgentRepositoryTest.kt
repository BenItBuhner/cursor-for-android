package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.JsonDiskCache
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

    private fun repository(persistDelayMs: Long = 10) = AgentRepository(session, prefs, cache, scope, persistDelayMs)

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

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
    fun `a row cached as running stops spinning once v1 reports the agent idle`() = runBlocking<Unit> {
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

        // Conversely a new run started elsewhere shows as running through the lifecycle alone.
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "ACTIVE", latestRunId = "run-2")
        repo.refresh()
        assertThat(repo.state.value.agents.single().isRunning).isTrue()
        assertThat(repo.state.value.agents.single().latestRunId).isEqualTo("run-2")
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
        val monitor = RunMonitor(repo, hub, runStartedAt = { _, _ -> 1_000L }, refreshIntervalMs = 600_000, nowProvider = { now })

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
