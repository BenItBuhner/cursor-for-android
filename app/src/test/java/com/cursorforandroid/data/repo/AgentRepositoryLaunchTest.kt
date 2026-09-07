package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.net.SocketTimeoutException

/**
 * Launching a chat against a scriptable backend: the client-minted id goes out, the chat is in the list before the
 * server has answered and stays or goes with the answer, a retry after a lost reply adopts the agent the first attempt
 * created, and a launch that lands while the list is being refreshed is not dropped. Robolectric only because
 * [SessionManager] needs a Context for its stores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentRepositoryLaunchTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: PreferencesStore
    private lateinit var agents: AgentRepository

    private val request = LaunchRequest(
        prompt = "merge and chat states often fail to sync between these two for some reason",
        repoUrl = "https://github.com/acme/cursor-for-android",
        ref = "main",
        modelId = "auto-smart",
        modelParams = emptyList(),
        autoCreatePr = false,
        planMode = false,
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = true)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        agents = AgentRepository(session, prefs, AttachmentStore(context))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    @Test
    fun `launch sends the client id and puts the new chat at the top of the list`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        val (agent, run) = agents.launch(request.copy(agentId = id), "Auto").getOrThrow()

        assertThat(api.createRequests.single().agentId).isEqualTo(id)
        assertThat(api.createRequests.single().repos?.single()?.startingRef).isEqualTo("main")
        assertThat(agent.id).isEqualTo(id)
        assertThat(agent.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(agent.modelDisplayName).isEqualTo("Auto")
        assertThat(run?.id).isEqualTo(agent.latestRunId)
        assertThat(agent.modelId).isEqualTo("auto-smart")
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)
        assertThat(prefs.localAgentState.first().launchedHereIds).contains(id)
    }

    @Test
    fun `a chat begun before the server answers is in the list at once and takes the server's record when it does`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        val provisional = agents.beginLaunch(request.copy(agentId = id), "Auto")!!

        // Named after the prompt, cut at a word, and running (creating) with what the request already knows.
        assertThat(provisional.name).isEqualTo("merge and chat states often fail to sync between these two…")
        assertThat(provisional.isRunning).isTrue()
        assertThat(provisional.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(provisional.latestRunId).isNull()
        assertThat(provisional.repoShortName).isEqualTo("cursor-for-android")
        assertThat(provisional.startingRef).isEqualTo("main")
        assertThat(provisional.modelDisplayName).isEqualTo("Auto")
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)

        val (agent, run) = agents.launch(request.copy(agentId = id), "Auto").getOrThrow()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)
        assertThat(agent.latestRunId).isEqualTo(run!!.id)
        assertThat(agent.modelDisplayName).isEqualTo("Auto")
        assertThat(agent.name).isEqualTo(api.agents.getValue(id).name)
    }

    @Test
    fun `a chat begun before the server answers keeps its provisional name while the server has none`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        agents.beginLaunch(request.copy(agentId = id), null)
        // The server has not generated a title yet: the record comes back nameless.
        api.blankCreatedNames = true

        val (agent, _) = agents.launch(request.copy(agentId = id), null).getOrThrow()
        assertThat(agent.name).isEqualTo("merge and chat states often fail to sync between these two…")
    }

    @Test
    fun `a chat begun before the server answers leaves the list when the launch fails`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        agents.beginLaunch(request.copy(agentId = id), "Auto")
        api.failNextCreate = CursorApiException(429, "rate_limited", "Slow down.")

        val failed = agents.launch(request.copy(agentId = id), "Auto")
        assertThat(failed.isFailure).isTrue()
        assertThat(agents.state.value.agents).isEmpty()
        // Discarding again, or a row that was never begun, changes nothing.
        agents.discardLaunch(id)
        agents.discardLaunch("bc-other")
        assertThat(agents.state.value.agents).isEmpty()
    }

    @Test
    fun `a chat begun before the server answers is never written to disk`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = AgentListCache(JsonDiskCache(folder.newFolder("agents"), dispatcher = Dispatchers.Unconfined))
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true))
        val persisted = AgentRepository(session, prefs, AttachmentStore(context), cache, scope, persistDelayMs = 10)
        api.addRunningAgent("bc-old", "Older chat", "run-old")
        persisted.refresh()
        awaitUntil { cache.read()?.value?.map { it.id } == listOf("bc-old") }

        val id = LaunchIdempotency.agentId(request, "nonce")
        api.createGate = CompletableDeferred()
        persisted.beginLaunch(request.copy(agentId = id), "Auto")
        val launch = scope.launch { persisted.launch(request.copy(agentId = id), "Auto") }
        assertThat(persisted.state.value.agents.map { it.id }).containsExactly(id, "bc-old").inOrder()
        delay(100)
        assertThat(cache.read()!!.value.map { it.id }).containsExactly("bc-old")

        api.createGate!!.complete(Unit)
        launch.join()
        awaitUntil { cache.read()?.value?.map { it.id } == listOf(id, "bc-old") }
    }

    @Test
    fun `a retry after a lost reply adopts the agent the first attempt created`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        // The request reaches the server, which creates the agent, but the reply never makes it back.
        api.failNextCreate = SocketTimeoutException("timeout")
        val first = agents.launch(request.copy(agentId = id), "Auto")
        assertThat(first.exceptionOrNull()).isInstanceOf(SocketTimeoutException::class.java)
        assertThat(agents.state.value.agents).isEmpty()
        api.addRunningAgent(id, "Sync merge and chat state", "run-server-1")

        val (retried, run) = agents.launch(request.copy(agentId = id), "Auto").getOrThrow()

        assertThat(api.createRequests).hasSize(2)
        assertThat(retried.id).isEqualTo(id)
        assertThat(retried.name).isEqualTo("Sync merge and chat state")
        assertThat(retried.latestRunId).isEqualTo("run-server-1")
        assertThat(run?.id).isEqualTo("run-server-1")
        assertThat(retried.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(retried.modelDisplayName).isEqualTo("Auto")
        // One chat, not two.
        assertThat(api.agents.keys).containsExactly(id)
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)
    }

    @Test
    fun `sending the same id twice never creates a duplicate`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        val first = agents.launch(request.copy(agentId = id), null).getOrThrow().agent
        val second = agents.launch(request.copy(agentId = id), null).getOrThrow().agent
        assertThat(second.id).isEqualTo(first.id)
        assertThat(api.agents).hasSize(1)
        assertThat(agents.state.value.agents).hasSize(1)
    }

    @Test
    fun `other conflicts and launches without an id still fail`() = runBlocking<Unit> {
        api.failNextCreate = CursorApiException(409, "agent_busy", "Agent is busy.")
        val busy = agents.launch(request.copy(agentId = LaunchIdempotency.agentId(request, "n")), null)
        assertThat((busy.exceptionOrNull() as CursorApiException).code).isEqualTo("agent_busy")

        api.failNextCreate = CursorApiException(409, "agent_id_conflict", "Exists.")
        val anonymous = agents.launch(request, null)
        assertThat((anonymous.exceptionOrNull() as CursorApiException).code).isEqualTo("agent_id_conflict")
        assertThat(agents.state.value.agents).isEmpty()
    }

    @Test
    fun `a chat launched while the list is being refreshed stays in the list`() = runBlocking<Unit> {
        api.addRunningAgent("bc-old", "Older chat", "run-old")
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-old")

        // The refresh has asked the server for the list (which does not include the new chat yet) and is waiting.
        val gate = CompletableDeferred<Unit>()
        api.listGate = gate
        val refresh = scope.launch { agents.refresh() }
        awaitUntil { agents.state.value.isRefreshing }

        val id = LaunchIdempotency.agentId(request, "nonce")
        agents.launch(request.copy(agentId = id), "Auto").getOrThrow()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id, "bc-old").inOrder()

        gate.complete(Unit)
        refresh.join()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id, "bc-old").inOrder()
        assertThat(agents.state.value.agents.first().modelDisplayName).isEqualTo("Auto")

        // The next refresh sees it server-side and there is still exactly one copy.
        api.listGate = null
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id, "bc-old")
    }
}
