package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConnectAgentStartApi
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.DocumentedCloudAgentsServer
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.fixtures.MachineFixtures
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A new chat on one of the user's machines in Extended mode, end to end from the composer: `GET /v0/private-workers`
 * in the documented shape ([MachineFixtures]), the composer's pick, and the start the desktop makes for a machine
 * picked with its repository (`StartBackgroundComposerFromSnapshot` with `use_private_worker` and the machine's
 * labels), against a fake account ([MachineAccountServer]) that routes by those labels and a fake `api.cursor.com`
 * ([MachineFleetServer]) that lists the fleet and reads the chat back. Nothing goes to the documented create for a
 * machine with a repository — which is what refuses a repository Cursor's GitHub app cannot reach — and what the
 * account refuses reaches the composer as its compact notice: its words and the `Asked:` line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MachineStartWireTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val publicServer = MockWebServer()
    private val accountServer = MockWebServer()
    private val fleet = MachineFleetServer().apply {
        workers = MachineFixtures.workers("online")
        registered = mapOf("bennett" to "bennett/codex-poly-bot", "studio" to "acme/payments-service")
    }
    private val account = MachineAccountServer(fleet.contract)
    private lateinit var graph: AppGraph

    @Before
    fun setUp() = runBlocking<Unit> {
        publicServer.dispatcher = fleet
        publicServer.start()
        accountServer.dispatcher = account
        accountServer.start()
        val api = CursorApiFactory.retrofit(CursorApiFactory.okHttp { "test-key" }, publicServer.url("/").toString())
        val accountBase = accountServer.url("/").toString()
        val start = ConnectAgentStartApi(
            ConnectJsonClient(OkHttpClient(), accountBase),
            SessionTokenProvider(OkHttpClient(), apiKeyProvider = { "test-key" }, apiUrl = accountBase, now = { 0L }),
            noRepoEnvironment = { error("no no-repo start here") },
        )
        graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            real = CursorBackend(api, FakeRunStreamer(), isDemo = false),
            agentStartApi = start,
        )
        graph.session.signIn("key_test").getOrThrow()
        graph.prefs.setComposerDefaults(repoUrl = null, ref = null, modelId = null, params = emptyMap(), autoCreatePr = false)
        graph.drafts.clear()
        graph.extendedMode.acknowledge()
        check(graph.extendedMode.enable()) { "Extended mode could not be turned on." }
    }

    @After
    fun tearDown() {
        publicServer.shutdown()
        accountServer.shutdown()
    }

    private fun composer(): NewAgentViewModel {
        runBlocking { graph.agents.refresh() }
        val vm = NewAgentViewModel(graph, draftSaveDelayMs = 20)
        runBlocking { withTimeout(10_000) { vm.state.first { it.models.isNotEmpty() && !it.isLoadingRepos && !it.isLoadingDevices && it.repositories.isNotEmpty() } } }
        return vm
    }

    private fun NewAgentViewModel.send(prompt: String): String = runBlocking {
        setPrompt(prompt)
        var opened: String? = null
        launch(onOpen = { opened = it })
        withTimeout(10_000) { while (account.starts.isEmpty() || opened == null) delay(10) }
        opened!!
    }

    @Test
    fun `Bennett's machine starts the way the desktop starts it, and the chat lands on the machine`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("bennett"))
        assertThat(vm.state.value.selectedRepo?.slug).isEqualTo("bennett/codex-poly-bot")
        assertThat(vm.state.value.ref).isEmpty()

        val id = vm.send("Dig deep and find where accuracy is lost")

        val start = account.starts.single()
        assertThat(start["bcId"]?.jsonPrimitive?.content).isEqualTo(id)
        for ((key, expected) in MachineFixtures.start("bennett")) assertThat(start[key]).isEqualTo(expected)
        assertThat(start.containsKey("baseBranch")).isFalse()
        // Not the documented create, which is what asks Cursor's GitHub app about bennett/codex-poly-bot.
        assertThat(fleet.creates).isEmpty()
        runBlocking { withTimeout(10_000) { while (graph.agents.agent(id)?.latestRunId == null) delay(10) } }
        val row = graph.agents.agent(id)!!
        assertThat(row.envType).isEqualTo(EnvType.MACHINE)
        assertThat(row.envName).isEqualTo("bennett")
        assertThat(row.repoUrl).isEqualTo("https://github.com/bennett/codex-poly-bot")
    }

    /**
     * The machine was listed, then went offline: the next listing leaves it out. It is still asked for by the worker it
     * was last listed as, and the account's refusal is the composer's compact notice — its words, then what was asked.
     */
    @Test
    fun `an offline machine is asked for by its last-listed worker, and the refusal is the compact notice`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("studio"))
        assertThat(vm.state.value.selectedRepo?.slug).isEqualTo("acme/payments-service")

        fleet.workers = MachineFixtures.workers("studioOffline")
        account.connected = setOf(MachineFixtures.BENNETT_WORKER)
        vm.refreshDevices()
        runBlocking { withTimeout(10_000) { vm.state.first { s -> !s.isLoadingDevices && s.devices.first { it.target == DeviceTarget.machine("studio") }.online.not() } } }

        vm.send("Rebuild the index")

        val start = account.starts.single()
        for ((key, expected) in MachineFixtures.start("studio")) assertThat(start[key]).isEqualTo(expected)
        val draft = runBlocking { withTimeout(10_000) { graph.newChatDrafts.state.first { s -> s.drafts.any { it.error != null } } }.drafts.first { it.error != null } }
        val words = MachineFixtures.offline["body"]!!.jsonObject["message"]!!.jsonPrimitive.content
        val asked = "POST /aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot → HTTP 400 failed_precondition"
        assertThat(draft.error).isEqualTo(words)
        assertThat(draft.errorAsked).isEqualTo(asked)
        assertThat(fleet.creates).isEmpty()

        // Opened again — or taken back by the composer at once — the draft brings the notice with it.
        if (vm.state.value.errorAsked == null) graph.newChatDrafts.request(NewChatDrafts.Request.Open(draft.id))
        val shown = runBlocking { withTimeout(10_000) { vm.state.first { it.errorAsked != null } } }
        assertThat(shown.error).isEqualTo(words)
        assertThat(shown.errorAsked).isEqualTo(asked)
        vm.dismissError()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.errorAsked).isNull()
    }

    @Test
    fun `a machine without a repository takes the documented request, as the desktop has no start for one`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("bennett"))
        vm.selectRepo(null)
        vm.setPrompt("Sketch a CLI")
        var opened: String? = null
        vm.launch(onOpen = { opened = it })
        runBlocking { withTimeout(10_000) { while (fleet.creates.isEmpty() || opened == null) delay(10) } }

        assertThat(account.starts).isEmpty()
        assertThat(CursorJson.parseToJsonElement(fleet.creates.single()).jsonObject["env"].toString()).isEqualTo("""{"type":"machine","name":"bennett"}""")
    }

    @Test
    fun `a chat on the cloud still goes out as the documented create`() {
        val vm = composer()
        vm.selectRepo(vm.state.value.repositories.first { it.slug == "acme/web" })
        vm.setPrompt("Add a health check")
        var opened: String? = null
        vm.launch(onOpen = { opened = it })
        runBlocking { withTimeout(10_000) { while (fleet.creates.isEmpty() || opened == null) delay(10) } }

        assertThat(account.starts).isEmpty()
        assertThat(CursorJson.parseToJsonElement(fleet.creates.single()).jsonObject["repos"].toString()).isEqualTo("""[{"url":"https://github.com/acme/web"}]""")
    }
}

/**
 * `api2.cursor.sh` for a machine start: the key exchange, and `StartBackgroundComposerFromSnapshot` routed the way the
 * account routes a private-worker request — to the connected worker the request selects, or failing an id the one its
 * `name=` label names — answered with the chat's record ([MachineFixtures.response]) and the chat registered on the
 * documented API with `env {type: machine}`; a worker that is not connected is refused with
 * [MachineFixtures.offline]'s Connect error.
 */
class MachineAccountServer(private val publicApi: DocumentedCloudAgentsServer) : Dispatcher() {

    val starts = CopyOnWriteArrayList<JsonObject>()
    @Volatile var connected: Set<String> = setOf(MachineFixtures.BENNETT_WORKER, MachineFixtures.STUDIO_WORKER)
    private val workerNames = mapOf("bennett" to MachineFixtures.BENNETT_WORKER, "studio" to MachineFixtures.STUDIO_WORKER)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        if (path == "/auth/exchange_user_api_key") return json(200, """{"accessToken":"session-token","refreshToken":"rt"}""")
        if (!path.endsWith("/StartBackgroundComposerFromSnapshot")) return json(404, """{"code":"unimplemented","message":"No such method"}""")
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        starts += body
        val labels = (body["labels"] as? JsonArray).orEmpty().associate { (it.jsonObject["key"] as JsonPrimitive).content to (it.jsonObject["value"] as JsonPrimitive).content }
        val worker = body["selectedPrivateWorkerId"]?.jsonPrimitive?.content ?: labels["name"]?.let(workerNames::get)
        if (worker == null || worker !in connected) {
            val offline = MachineFixtures.offline
            return json(offline["status"]!!.jsonPrimitive.content.toInt(), offline["body"].toString())
        }
        val bcId = body["bcId"]!!.jsonPrimitive.content
        val name = workerNames.entries.first { it.value == worker }.key
        val repoUrl = body["repoUrl"]!!.jsonPrimitive.content
        publicApi.registerStarted(
            bcId,
            "Codex-Poly-Bot accuracy pass",
            env = buildJsonObject { put("type", "machine"); put("name", name) },
            repos = buildJsonArray { add(buildJsonObject { put("url", repoUrl) }) },
        )
        return json(200, MachineFixtures.response(bcId))
    }

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
}
