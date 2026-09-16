package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.DesktopProbe
import com.cursorforandroid.data.api.MachineLookupApi
import com.cursorforandroid.data.api.ProbeResult
import com.cursorforandroid.data.api.dto.ListWorkersResponseDto
import com.cursorforandroid.data.api.dto.WorkerDto
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.MachineReference
import com.cursorforandroid.domain.MachineStatus
import com.cursorforandroid.domain.MachineUnavailableReason
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The Remote section's two halves: the machine's state from the fleet endpoint, and the gated desktop with its probe. */
class RemoteRepositoryTest {

    private val server = MockWebServer()
    private var workersCalls = 0
    private var workers: () -> ListWorkersResponseDto = { ListWorkersResponseDto() }
    private var machineCalls = 0
    private var machine: () -> MachineReference = { pod() }
    private var now = 5_000_000L

    /** Which websockify paths accept the handshake; everything else answers 404. */
    private val open = mutableSetOf<String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return if (path in open) MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) = Unit
                }) else MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** A pod whose desktop URLs point at the fake server: the port sits in the path so the dispatcher can tell them apart. */
    private fun pod() = MachineReference.Pod(podId = "pod", tenantId = "t", cluster = "c", token = "s3cr3t")

    private fun repo(capabilities: Capabilities = Capabilities.EXTENDED, demo: Boolean = false): RemoteRepository = RemoteRepository(
        workers = { workersCalls++; workers() },
        machines = MachineLookupApi { machineCalls++; machine() },
        probe = RewritingProbe(server),
        capabilities = { capabilities },
        isDemo = { demo },
        now = { now },
    )

    /** Sends each candidate to the fake server as `/<port>` so the test decides which port "serves" the desktop. */
    private class RewritingProbe(private val server: MockWebServer) : DesktopProbe({ OkHttpClient() }, timeoutMs = 3_000) {
        override suspend fun probe(url: String): ProbeResult {
            val port = Regex("-(\\d+)\\.c\\.cursorvm\\.com").find(url)!!.groupValues[1]
            return super.probe(server.url("/$port").toString().replaceFirst("http://", "ws://") + "?" + url.substringAfter('?'))
        }
    }

    private fun agent(env: EnvType = EnvType.CLOUD, name: String? = null, running: Boolean = true) = Agent(
        id = "bc-1",
        name = "Chat",
        lifecycle = AgentLifecycle.ACTIVE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = env,
        envName = name,
        url = "https://cursor.com/agents/bc-1",
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        latestRunId = null,
        repoUrl = null,
        startingRef = null,
    )

    // ---- the machine (public half) -------------------------------------------------------------------------------

    @Test
    fun `a cloud chat has no machine to report on`() = runBlocking<Unit> {
        assertThat(repo(Capabilities.DOCUMENTED).machineStatus(agent())).isNull()
        assertThat(workersCalls).isEqualTo(0)
    }

    @Test
    fun `a Remote Control chat's machine is matched by the chat it is busy with, else by any of its names, in either mode`() = runBlocking<Unit> {
        workers = {
            ListWorkersResponseDto(
                workers = listOf(
                    WorkerDto(id = "w-1", name = "studio", isInUse = true, activeBcId = "bc-other"),
                    WorkerDto(id = "w-2", machineDisplayName = "Laptop", isInUse = false),
                ),
            )
        }
        val repo = repo(Capabilities.DOCUMENTED)

        val busy = repo.machineStatus(agent(EnvType.MACHINE, "studio#/home/me/app"))!!.getOrThrow()
        assertThat(busy).isEqualTo(MachineStatus("studio", connected = true, isInUse = true, activeAgentId = "bc-other"))
        assertThat(busy.label("bc-1")).isEqualTo("Connected · busy with another chat")
        val idle = repo.machineStatus(agent(EnvType.MACHINE, "laptop"))!!.getOrThrow()
        assertThat(idle.connected).isTrue()
        assertThat(idle.isInUse).isFalse()
        assertThat(idle.label("bc-1")).isEqualTo("Connected · idle")
        val gone = repo.machineStatus(agent(EnvType.MACHINE, "desktop-pc"))!!.getOrThrow()
        assertThat(gone).isEqualTo(MachineStatus("desktop-pc", connected = false))
        assertThat(gone.label("bc-1")).isEqualTo("Not connected")
        // One fleet read served all three; the mode never mattered.
        assertThat(workersCalls).isEqualTo(1)

        workers = { ListWorkersResponseDto(items = listOf(WorkerDto(id = "w-1", name = "studio", isInUse = true, activeBcId = "bc-1"))) }
        val mine = repo.machineStatus(agent(EnvType.MACHINE, "studio"), force = true)!!.getOrThrow()
        assertThat(mine.isBusyWith("bc-1")).isTrue()
        assertThat(mine.label("bc-1")).isEqualTo("Connected · working on this chat")
        assertThat(workersCalls).isEqualTo(2)
        now += RemoteRepository.WORKERS_TTL_MS + 1
        repo.machineStatus(agent(EnvType.MACHINE, "studio"))
        assertThat(workersCalls).isEqualTo(3)
    }

    @Test
    fun `a key that cannot list workers is a named failure, not an offline machine`() = runBlocking<Unit> {
        workers = { throw CursorApiException(403, "forbidden", "Fleet endpoints need a pool service account.") }
        val result = repo().machineStatus(agent(EnvType.MACHINE, "studio"))!!
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("pool service account")
    }

    @Test
    fun `the demo's machine is connected and busy with the chat while it runs`() = runBlocking<Unit> {
        val status = repo(demo = true).machineStatus(agent(EnvType.MACHINE, "demo-mac"))!!.getOrThrow()
        assertThat(status).isEqualTo(MachineStatus("demo-mac", connected = true, isInUse = true, activeAgentId = "bc-1"))
        assertThat(workersCalls).isEqualTo(0)
    }

    // ---- the desktop (Extended half) -----------------------------------------------------------------------------

    @Test
    fun `with the capability off the desktop is refused without a call, and the demo has none`() = runBlocking<Unit> {
        val off = repo(Capabilities.DOCUMENTED).openDesktop(agent()) as DesktopOpen.Failed
        assertThat(off.failure).isInstanceOf(DesktopFailure.NotAvailable::class.java)
        assertThat(off.failure.message).isEqualTo(RemoteRepository.NEEDS_EXTENDED_MODE)
        assertThat(off.failure.retryable).isFalse()
        val demo = repo(demo = true).openDesktop(agent()) as DesktopOpen.Failed
        assertThat(demo.failure.message).isEqualTo(RemoteRepository.NOT_IN_DEMO)
        assertThat(machineCalls).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the Agents Window's default port is tried first and becomes the session, view only unless asked, the steps traced`() = runBlocking<Unit> {
        open += "/26058"
        val steps = mutableListOf<String>()
        val opened = repo().openDesktop(agent(), progress = { trace -> steps += trace.steps.joinToString("|") { "${it.name}=${it.outcome ?: "…"}" } }) as DesktopOpen.Opened

        assertThat(opened.session.agentId).isEqualTo("bc-1")
        assertThat(opened.session.port).isEqualTo(26058)
        assertThat(opened.session.viewOnly).isTrue()
        assertThat(opened.session.url).isEqualTo("wss://t-pod-26058.c.cursorvm.com:443/websockify?network_token=s3cr3t&resume_lower_s=900&resume_upper_s=18000")
        assertThat(opened.session.toString()).doesNotContain("s3cr3t")
        // One handshake: the default port answered, so the fallback was never asked.
        assertThat(server.requestCount).isEqualTo(1)
        // The trace: GetMachine, then the probe of the host; the report names the host and port and never the token.
        val trace = opened.session.trace
        assertThat(trace.steps.map { it.name }).containsExactly("GetMachine", "probe t-pod-26058.c.cursorvm.com").inOrder()
        assertThat(trace.steps.all { !it.isRunning && !it.failed }).isTrue()
        assertThat(trace.host).isEqualTo("t-pod-26058.c.cursorvm.com")
        assertThat(trace.port).isEqualTo(26058)
        assertThat(trace.report()).contains("endpoint: t-pod-26058.c.cursorvm.com:26058")
        assertThat(trace.report()).doesNotContain("s3cr3t")
        assertThat(trace.report()).doesNotContain("network_token")
        assertThat(steps.first()).isEqualTo("GetMachine=…")
        assertThat(steps.last()).endsWith("probe t-pod-26058.c.cursorvm.com=handshake accepted")

        val control = repo().openDesktop(agent(), viewOnly = false) as DesktopOpen.Opened
        assertThat(control.session.viewOnly).isFalse()
    }

    @Test
    fun `when the default port refuses, the fallback port is tried before giving up, and the refusal is in the trace`() = runBlocking<Unit> {
        open += "/6080"
        val opened = repo().openDesktop(agent()) as DesktopOpen.Opened
        assertThat(opened.session.port).isEqualTo(6080)
        assertThat(server.requestCount).isEqualTo(2)
        val probes = opened.session.trace.steps.filter { it.name.startsWith("probe") }
        assertThat(probes.map { it.outcome }).containsExactly("HTTP 404", "handshake accepted").inOrder()
        assertThat(probes.first().failed).isTrue()
    }

    @Test
    fun `the probe sends the Agents Window's handshake - the page's origin and no subprotocol`() = runBlocking<Unit> {
        open += "/26058"
        repo().openDesktop(agent())
        val request = server.takeRequest()
        assertThat(request.getHeader("Upgrade")).isEqualTo("websocket")
        assertThat(request.getHeader("Sec-WebSocket-Protocol")).isNull()
    }

    @Test
    fun `no port answering is unreachable and retryable, worded by whether the chat is running`() = runBlocking<Unit> {
        val running = repo().openDesktop(agent(running = true)) as DesktopOpen.Failed
        assertThat(running.failure).isInstanceOf(DesktopFailure.Unreachable::class.java)
        assertThat(running.failure.retryable).isTrue()
        assertThat(running.failure.message).contains("would not open a session")
        assertThat(running.failure.message).contains("26058 or 6080")
        assertThat(running.failure.trace.lastFailed?.name).isEqualTo("probe t-pod-6080.c.cursorvm.com")
        val finished = repo().openDesktop(agent(running = false)) as DesktopOpen.Failed
        assertThat(finished.failure.message).contains("would not open a session")
    }

    @Test
    fun `a worker machine has no desktop reachable from here, with the worker's own reason when it gave one`() = runBlocking<Unit> {
        machine = { MachineReference.Worker("w-1", unavailableReason = "desktop sharing is off") }
        val reasoned = repo().openDesktop(agent(EnvType.MACHINE, "studio")) as DesktopOpen.Failed
        assertThat(reasoned.failure).isInstanceOf(DesktopFailure.NoDesktop::class.java)
        assertThat(reasoned.failure.message).contains("desktop sharing is off")
        assertThat(reasoned.failure.trace.steps.single().outcome).isEqualTo("worker w-1")

        machine = { MachineReference.Worker("w-1", available = true, controlAllowed = true) }
        val relay = repo().openDesktop(agent(EnvType.MACHINE, "studio")) as DesktopOpen.Failed
        assertThat(relay.failure.message).isEqualTo(RemoteRepository.WORKER_DESKTOP_RELAY)

        machine = { MachineReference.Worker("w-1") }
        val none = repo().openDesktop(agent(EnvType.MACHINE, "studio")) as DesktopOpen.Failed
        assertThat(none.failure.message).isEqualTo(RemoteRepository.WORKER_NO_DESKTOP)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `GetMachine's reasons are read the Agents Window's way - expired, on the coordinator, not provisioned, preparing`() = runBlocking<Unit> {
        machine = { throw ConnectRpcException(200, "failed_precondition", "cursorServerUrlReason=AGENT_ARCHIVED: no machine") }
        val archived = repo().openDesktop(agent()) as DesktopOpen.Failed
        assertThat(archived.failure).isInstanceOf(DesktopFailure.NoDesktop::class.java)
        assertThat((archived.failure as DesktopFailure.NoDesktop).reason).isEqualTo(MachineUnavailableReason.AGENT_ARCHIVED)
        assertThat(archived.failure.retryable).isFalse()
        assertThat(archived.failure.trace.lastFailed?.outcome).isEqualTo("AGENT_ARCHIVED")

        machine = { throw ConnectRpcException(200, "failed_precondition", "cursorServerUrlReason=WORKSPACE_ON_COORDINATOR") }
        val onCoordinator = repo().openDesktop(agent()) as DesktopOpen.Failed
        assertThat(onCoordinator.failure.message).contains("coordinator's machine")
        assertThat(onCoordinator.failure.retryable).isFalse()

        // No VM: final for a finished chat (a coordinator never has one), worth another try while the chat runs.
        machine = { throw ConnectRpcException(200, "failed_precondition", "cursorServerUrlReason=MACHINE_NOT_PROVISIONED") }
        val noVm = repo().openDesktop(agent(running = false)) as DesktopOpen.Failed
        assertThat(noVm.failure.message).contains("Project coordinator")
        assertThat(noVm.failure.retryable).isFalse()
        val starting = repo().openDesktop(agent(running = true)) as DesktopOpen.Failed
        assertThat(starting.failure.retryable).isTrue()

        machine = { throw ConnectRpcException(200, "unavailable", "cursorServerUrlReason=EXEC_DAEMON_NOT_READY") }
        val preparing = repo().openDesktop(agent()) as DesktopOpen.Failed
        assertThat(preparing.failure).isInstanceOf(DesktopFailure.Preparing::class.java)
        assertThat(preparing.failure.retryable).isTrue()
        assertThat(preparing.failure.message).contains("EXEC_DAEMON_NOT_READY")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `GetMachine's other refusals are named - an endpoint that moved, forbidden, a VM that is not running, no session`() = runBlocking<Unit> {
        machine = { throw ConnectRpcException(404, "unimplemented", "Error") }
        val moved = repo().openDesktop(agent()) as DesktopOpen.Failed
        assertThat(moved.failure).isInstanceOf(DesktopFailure.Refused::class.java)
        assertThat(moved.failure.message).isEqualTo(RemoteRepository.ENDPOINT_CHANGED)
        assertThat((moved.failure as DesktopFailure.Refused).endpointChanged).isTrue()
        assertThat(moved.failure.retryable).isFalse()
        assertThat(moved.failure.trace.lastFailed?.outcome).isEqualTo("HTTP 404 unimplemented")

        machine = { throw ConnectRpcException(403, "permission_denied", "Error") }
        val forbidden = repo().openDesktop(agent()) as DesktopOpen.Failed
        assertThat(forbidden.failure.message).contains("not authorized")
        assertThat(forbidden.failure.retryable).isFalse()

        machine = { throw ConnectRpcException(200, "failed_precondition", "pod is hibernated") }
        val asleep = repo().openDesktop(agent()) as DesktopOpen.Failed
        assertThat(asleep.failure).isInstanceOf(DesktopFailure.Unreachable::class.java)
        assertThat(asleep.failure.message).contains("hibernated")

        machine = { throw SessionUnavailableException("off", SessionUnavailableException.EXTENDED_MODE_OFF) }
        val off = repo().openDesktop(agent()) as DesktopOpen.Failed
        assertThat(off.failure).isInstanceOf(DesktopFailure.NotAvailable::class.java)
        assertThat(off.failure.message).isEqualTo(RemoteRepository.NEEDS_EXTENDED_MODE)
    }
}
