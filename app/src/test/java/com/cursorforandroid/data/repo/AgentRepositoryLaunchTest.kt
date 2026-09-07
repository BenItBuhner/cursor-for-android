package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.local.AttachmentStore
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException

/**
 * Launching a chat against a scriptable backend: the client-minted id goes out, a retry after a lost reply adopts the
 * agent the first attempt created, and a launch that lands while the list is being refreshed is not dropped.
 * Robolectric only because [SessionManager] needs a Context for its stores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentRepositoryLaunchTest {

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
        val agent = agents.launch(request.copy(agentId = id), "Auto").getOrThrow()

        assertThat(api.createRequests.single().agentId).isEqualTo(id)
        assertThat(api.createRequests.single().repos?.single()?.startingRef).isEqualTo("main")
        assertThat(agent.id).isEqualTo(id)
        assertThat(agent.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(agent.modelDisplayName).isEqualTo("Auto")
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)
        assertThat(prefs.localAgentState.first().launchedHereIds).contains(id)
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

        val retried = agents.launch(request.copy(agentId = id), "Auto").getOrThrow()

        assertThat(api.createRequests).hasSize(2)
        assertThat(retried.id).isEqualTo(id)
        assertThat(retried.name).isEqualTo("Sync merge and chat state")
        assertThat(retried.latestRunId).isEqualTo("run-server-1")
        assertThat(retried.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(retried.modelDisplayName).isEqualTo("Auto")
        // One chat, not two.
        assertThat(api.agents.keys).containsExactly(id)
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)
    }

    @Test
    fun `sending the same id twice never creates a duplicate`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        val first = agents.launch(request.copy(agentId = id), null).getOrThrow()
        val second = agents.launch(request.copy(agentId = id), null).getOrThrow()
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
