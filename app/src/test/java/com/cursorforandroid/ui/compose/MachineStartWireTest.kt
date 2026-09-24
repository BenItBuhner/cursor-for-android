package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConnectAgentStartApi
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.ConnectRepositoryBranchesApi
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.DocumentedCloudAgentsServer
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.TranscriptEngine
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
 * labels, naming the account's own project for the repository), against a fake account ([MachineAccountServer]) that
 * reaches only the repositories it has and routes by those labels, and a fake `api.cursor.com` ([MachineFleetServer])
 * that lists the fleet and reads the chat back. Nothing goes to the documented create for a machine with a
 * repository, and what the account refuses reaches the composer as its compact notice: its words and the `Asked:` line.
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
        val rpc = ConnectJsonClient(OkHttpClient(), accountBase)
        val tokens = SessionTokenProvider(OkHttpClient(), apiKeyProvider = { "test-key" }, apiUrl = accountBase, now = { 0L })
        graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            real = CursorBackend(api, FakeRunStreamer(), isDemo = false),
            agentStartApi = ConnectAgentStartApi(rpc, tokens, noRepoEnvironment = { error("no no-repo start here") }),
            repositoryBranchesApi = ConnectRepositoryBranchesApi(rpc, tokens),
        )
        graph.session.signIn("key_test").getOrThrow()
        graph.prefs.setComposerDefaults(repoUrl = null, ref = null, modelId = null, params = emptyMap(), autoCreatePr = false)
        graph.drafts.clear()
        graph.extendedMode.acknowledge()
        check(graph.extendedMode.enable()) { "Extended mode could not be turned on." }
        // A launched chat's load under Beta would read the account's record over the graph's own api2 client, which
        // no fake here stands in for; the launch wire under test does not depend on the engine.
        check(graph.extendedMode.setEngine(TranscriptEngine.STABLE)) { "The transcript engine could not be set." }
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

    private fun NewAgentViewModel.send(prompt: String): String? = runBlocking {
        setPrompt(prompt)
        var opened: String? = null
        launch(onOpen = { opened = it })
        withTimeout(10_000) { while (account.starts.isEmpty()) delay(10) }
        opened
    }

    private fun NewAgentViewModel.sendAndOpen(prompt: String): String {
        send(prompt)
        val id = account.starts.single()["bcId"]!!.jsonPrimitive.content
        runBlocking { withTimeout(10_000) { while (graph.agents.agent(id)?.latestRunId == null) delay(10) } }
        return id
    }

    private fun refusedDraft() = runBlocking { withTimeout(10_000) { graph.newChatDrafts.state.first { s -> s.drafts.any { it.error != null } } }.drafts.first { it.error != null } }

    private fun landedOnBennett(id: String, repoUrl: String) {
        val row = graph.agents.agent(id)!!
        assertThat(row.envType).isEqualTo(EnvType.MACHINE)
        assertThat(row.envName).isEqualTo("bennett")
        assertThat(row.repoUrl).isEqualTo(repoUrl)
        assertThat(fleet.creates).isEmpty()
    }

    /**
     * Bennett's pick as he made it, with nothing on the account for `bennett/codex-poly-bot`: the desktop too falls back
     * to `https://github.com/bennett/codex-poly-bot` (`VXS`), and an account that cannot reach it refuses with his words.
     */
    @Test
    fun `with no account project for the machine's repository the start is the desktop's fallback, and its refusal the compact notice`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("bennett"))
        assertThat(vm.state.value.selectedRepo?.url).isEqualTo(MachineFixtures.CODEX_GUESS)
        assertThat(vm.state.value.branchLabel).isEqualTo("current")

        vm.send("Test!")

        val start = account.starts.single()
        for ((key, expected) in MachineFixtures.start("bennett")) assertThat(start[key]).isEqualTo(expected)
        assertThat(start.containsKey("baseBranch")).isFalse()
        val draft = refusedDraft()
        assertThat(draft.error).isEqualTo(MachineFixtures.noAccess["body"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertThat(draft.errorAsked).isEqualTo("POST /aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot → HTTP 400 invalid_argument")
        assertThat(fleet.creates).isEmpty()
    }

    @Test
    fun `the account's environment for codex-poly-bot is what the start names, and the chat lands on the machine`() {
        account.environments = MachineFixtures.environments("withCodexPolyBot")
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("bennett"))
        assertThat(vm.state.value.ref).isEmpty()

        val id = vm.sendAndOpen("Dig deep and find where accuracy is lost")

        val start = account.starts.single()
        for ((key, expected) in MachineFixtures.start("bennettEnvironment")) assertThat(start[key]).isEqualTo(expected)
        assertThat(start.containsKey("baseBranch")).isFalse()
        landedOnBennett(id, MachineFixtures.CODEX_ORIGIN)
    }

    /**
     * The account's copy of the repository is known from a chat that ran on it — here only once the list answers after
     * the pick: that copy becomes the machine's repository (`nZS`), its branches are the account's real refs, and one
     * picked goes out as the base branch and the ref.
     */
    @Test
    fun `an earlier chat on the account's copy makes it the machine's repository, with its real branches`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("bennett"))
        assertThat(vm.state.value.selectedRepo?.url).isEqualTo(MachineFixtures.CODEX_GUESS)

        fleet.contract.registerStarted(
            "bc-earlier",
            "Polymarket US infrastructure",
            repos = buildJsonArray { add(buildJsonObject { put("url", MachineFixtures.CODEX_ORIGIN) }) },
        )
        fleet.history = listOf(fleet.contract.agents["bc-earlier"]!!.body)
        runBlocking { graph.agents.refresh() }

        val listed = runBlocking { withTimeout(10_000) { vm.state.first { it.selectedRepo?.url == MachineFixtures.CODEX_ORIGIN && it.branchesListedByAccount } } }
        assertThat(listed.branches.map { it.name }).containsExactly("main", "feature/accuracy", "fix/slippage").inOrder()
        assertThat(listed.branches.first().isDefault).isTrue()
        assertThat(listed.branchLabel).isEqualTo("current")
        assertThat(account.branchAsks).contains(MachineFixtures.CODEX_ORIGIN)

        vm.setRef("feature/accuracy")
        assertThat(vm.state.value.branchLabel).isEqualTo("feature/accuracy")
        val id = vm.sendAndOpen("Dig deep")

        val start = account.starts.single()
        assertThat(start["repoUrl"]?.jsonPrimitive?.content).isEqualTo(MachineFixtures.CODEX_ORIGIN)
        assertThat(start["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("origin.cursor.com/bennett/codex-poly-bot")
        assertThat(start["devcontainerStartingPoint"].toString())
            .isEqualTo("""{"url":"${MachineFixtures.CODEX_ORIGIN}","ref":"feature/accuracy","environmentName":"bennett/codex-poly-bot"}""")
        assertThat(start["baseBranch"]?.jsonPrimitive?.content).isEqualTo("feature/accuracy")
        assertThat(start["labels"]).isEqualTo(MachineFixtures.start("bennett")["labels"])
        landedOnBennett(id, MachineFixtures.CODEX_ORIGIN)
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
        val draft = refusedDraft()
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
        assertThat(vm.state.value.branchLabel).isEqualTo("default")
        vm.setPrompt("Add a health check")
        var opened: String? = null
        vm.launch(onOpen = { opened = it })
        runBlocking { withTimeout(10_000) { while (fleet.creates.isEmpty() || opened == null) delay(10) } }

        assertThat(account.starts).isEmpty()
        assertThat(CursorJson.parseToJsonElement(fleet.creates.single()).jsonObject["repos"].toString()).isEqualTo("""[{"url":"https://github.com/acme/web"}]""")
    }
}

/**
 * `api2.cursor.sh` for a machine start: the key exchange; `ListEnvironments` ([environments]); `GetRepositoryBranches`
 * for a repository it reaches ([branches]); and `StartBackgroundComposerFromSnapshot` routed the way the account routes
 * a private-worker request — to the connected worker the request selects, or failing an id the one its `name=` label
 * names — answered with the chat's record ([MachineFixtures.response]) and the chat registered on the documented API
 * with `env {type: machine}`. A worker that is not connected is refused with [MachineFixtures.offline]'s Connect error,
 * and a repository URL the account does not reach ([reachable]) with [MachineFixtures.noAccess]'s, as Bennett's was.
 */
class MachineAccountServer(private val publicApi: DocumentedCloudAgentsServer) : Dispatcher() {

    val starts = CopyOnWriteArrayList<JsonObject>()
    val branchAsks = CopyOnWriteArrayList<String>()
    @Volatile var connected: Set<String> = setOf(MachineFixtures.BENNETT_WORKER, MachineFixtures.STUDIO_WORKER)
    @Volatile var environments: String = MachineFixtures.environments("none")
    @Volatile var reachable: Set<String> = setOf(MachineFixtures.CODEX_ORIGIN, "https://github.com/acme/payments-service", "https://github.com/acme/web")
    @Volatile var branches: Map<String, String> = mapOf(MachineFixtures.CODEX_ORIGIN to MachineFixtures.branches("codexPolyBot"))
    private val workerNames = mapOf("bennett" to MachineFixtures.BENNETT_WORKER, "studio" to MachineFixtures.STUDIO_WORKER)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            path == "/auth/exchange_user_api_key" -> json(200, """{"accessToken":"session-token","refreshToken":"rt"}""")
            path.endsWith("/ListEnvironments") -> json(200, environments)
            path.endsWith("/GetRepositoryBranches") -> {
                val repoUrl = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject["repoUrl"]!!.jsonPrimitive.content
                branchAsks += repoUrl
                if (repoUrl in reachable) json(200, branches[repoUrl] ?: """{"branches":[]}""") else refusal(MachineFixtures.noAccess)
            }
            path.endsWith("/StartBackgroundComposerFromSnapshot") -> start(CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject)
            else -> json(404, """{"code":"unimplemented","message":"No such method"}""")
        }
    }

    private fun start(body: JsonObject): MockResponse {
        starts += body
        val labels = (body["labels"] as? JsonArray).orEmpty().associate { (it.jsonObject["key"] as JsonPrimitive).content to (it.jsonObject["value"] as JsonPrimitive).content }
        val worker = body["selectedPrivateWorkerId"]?.jsonPrimitive?.content ?: labels["name"]?.let(workerNames::get)
        if (worker == null || worker !in connected) return refusal(MachineFixtures.offline)
        val repoUrl = body["repoUrl"]!!.jsonPrimitive.content
        if (repoUrl !in reachable) return refusal(MachineFixtures.noAccess)
        val bcId = body["bcId"]!!.jsonPrimitive.content
        val name = workerNames.entries.first { it.value == worker }.key
        publicApi.registerStarted(
            bcId,
            "Codex-Poly-Bot accuracy pass",
            env = buildJsonObject { put("type", "machine"); put("name", name) },
            repos = buildJsonArray { add(buildJsonObject { put("url", repoUrl) }) },
        )
        return json(200, MachineFixtures.response(bcId, repoUrl))
    }

    private fun refusal(shape: JsonObject) = json(shape["status"]!!.jsonPrimitive.content.toInt(), shape["body"].toString())

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
}
