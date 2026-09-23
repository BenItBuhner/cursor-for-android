package com.cursorforandroid.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.DocumentedCloudAgentsServer
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.util.AppClock
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
 * A new chat on one of the user's machines, end to end: `GET /v0/private-workers` answers in the documented shape, the
 * New Chat composer picks the machine and the launch goes through the launcher, the repositories and the app's own
 * OkHttp client to a fake `api.cursor.com` ([MachineFleetServer]). What goes out is the documented Create An Agent body
 * for a machine — `env {type: machine, name}` and `repos[0].url`, the repository the worker registered, with no
 * `startingRef` unless a branch was picked on it — and what the server says about a machine it cannot use reaches the
 * composer in its own words.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MachineLaunchWireTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val server = MockWebServer()
    private val fleet = MachineFleetServer()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() = runBlocking<Unit> {
        server.dispatcher = fleet
        server.start()
        val api = CursorApiFactory.retrofit(CursorApiFactory.okHttp { "test-key" }, server.url("/").toString())
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>(), real = CursorBackend(api, FakeRunStreamer(), isDemo = false))
        graph.session.signIn("key_test").getOrThrow()
        // Robolectric's preferences and files outlive a test: nothing of the one before is this one's last launch or draft.
        graph.prefs.setComposerDefaults(repoUrl = null, ref = null, modelId = null, params = emptyMap(), autoCreatePr = false)
        graph.drafts.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun composer(resume: String? = null): NewAgentViewModel {
        runBlocking { graph.agents.refresh() }
        val vm = NewAgentViewModel(graph, draftSaveDelayMs = 20, resume = resume)
        runBlocking { withTimeout(10_000) { vm.state.first { it.models.isNotEmpty() && !it.isLoadingRepos && !it.isLoadingDevices && it.repositories.isNotEmpty() } } }
        return vm
    }

    private fun NewAgentViewModel.repo(slug: String) = state.value.repositories.first { it.slug == slug }

    /** Sends what is written and waits for the request to have reached the server. */
    private fun NewAgentViewModel.send(prompt: String): String = runBlocking {
        setPrompt(prompt)
        var opened: String? = null
        launch(onOpen = { opened = it })
        withTimeout(10_000) { while (fleet.creates.isEmpty() || opened == null) delay(10) }
        opened!!
    }

    /** The body as the server received it, without the model the composer's default adds. */
    private fun sent(): JsonObject = CursorJson.parseToJsonElement(fleet.creates.last()).jsonObject.let { body ->
        JsonObject(body.filterKeys { it != "model" })
    }

    private fun json(text: String): JsonObject = CursorJson.parseToJsonElement(text).jsonObject

    /** Why the launch did not go through, as the draft it gave back carries it to the composer or the sidebar. */
    private fun failure(): String = runBlocking {
        withTimeout(10_000) { graph.newChatDrafts.state.first { s -> s.drafts.any { it.error != null } } }.drafts.first { it.error != null }.error!!
    }

    @Test
    fun `a machine and its repository go out as env and the repository the worker registered, with no branch`() {
        val vm = composer()
        // Picked on Cloud: a repository and a branch of it.
        vm.selectRepo(vm.repo("acme/web"))
        vm.setRef("release/2.1")

        vm.selectDevice(DeviceTarget.machine("devbox"))

        val s = vm.state.value
        assertThat(s.selectedRepo?.slug).isEqualTo("acme/payments-service")
        assertThat(s.repoFollowsDevice).isTrue()
        // The checkout's own branch: blank, which the chip shows as "default" and which is never sent as a name.
        assertThat(s.ref).isEmpty()

        val id = vm.send("Fix the flaky test")

        assertThat(sent()).isEqualTo(
            json(
                """{"prompt":{"text":"Fix the flaky test"},"agentId":"$id",""" +
                    """"env":{"type":"machine","name":"devbox"},"repos":[{"url":"https://github.com/acme/payments-service"}]}""",
            ),
        )
        assertThat(sent()["repos"].toString()).doesNotContain("startingRef")
        assertThat(sent()["repos"].toString()).doesNotContain("default")
        runBlocking { withTimeout(10_000) { while (graph.agents.agent(id)?.latestRunId == null) delay(10) } }
        assertThat(graph.agents.agent(id)!!.envName).isEqualTo("devbox")
    }

    @Test
    fun `a branch picked on the machine goes out as its starting ref`() {
        val vm = composer()
        // The same repository on Cloud, at a branch: the machine's checkout starts where it is, not there.
        vm.selectRepo(vm.repo("acme/payments-service"))
        vm.setRef("main")
        vm.selectDevice(DeviceTarget.machine("devbox"))
        assertThat(vm.state.value.ref).isEmpty()
        vm.setRef("feature/retry")

        vm.send("Carry on")

        assertThat((sent()["repos"] as JsonArray).single()).isEqualTo(
            json("""{"url":"https://github.com/acme/payments-service","startingRef":"feature/retry"}"""),
        )
    }

    @Test
    fun `a worker that reports its remote in git's own spelling goes out as the https url`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("laptop"))
        assertThat(vm.state.value.deviceRepoUrl).isEqualTo("https://github.com/acme/web")

        vm.send("Tidy the README")

        assertThat((sent()["repos"] as JsonArray).single()).isEqualTo(json("""{"url":"https://github.com/acme/web"}"""))
        assertThat(sent()["env"]).isEqualTo(json("""{"type":"machine","name":"laptop"}"""))
    }

    /**
     * Bennett's machine as the fleet endpoint describes it: registered as `bennett/codex-poly-bot` — the owner and
     * name of its checkout's origin, the host dropped — which is not a repository Cursor's GitHub app can reach. The
     * request is the documented one all the same, and Cursor's refusal is what the composer shows.
     */
    @Test
    fun `a machine whose repository Cursor cannot reach is refused in the server's words`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("bennett"))
        assertThat(vm.state.value.selectedRepo?.shortName).isEqualTo("codex-poly-bot")
        assertThat(vm.state.value.ref).isEmpty()

        vm.send("Dig deep")

        assertThat(sent()["env"]).isEqualTo(json("""{"type":"machine","name":"bennett"}"""))
        assertThat(sent()["repos"]).isEqualTo(CursorJson.parseToJsonElement("""[{"url":"https://github.com/bennett/codex-poly-bot"}]"""))
        assertThat(failure()).isEqualTo(
            "The source control provider for repository bennett/codex-poly-bot is not connected to Cursor. Connect it and try again.",
        )
    }

    @Test
    fun `a machine that is not connected is refused in the server's words`() {
        runBlocking {
            graph.prefs.setComposerDefaults(repoUrl = "https://github.com/acme/web", ref = "", modelId = null, params = emptyMap(), autoCreatePr = false, env = DeviceTarget.machine("studio"))
        }
        val vm = composer()
        assertThat(vm.state.value.selectedDevice).isEqualTo(DeviceTarget.machine("studio"))
        assertThat(vm.state.value.devices.first { it.target == DeviceTarget.machine("studio") }.online).isFalse()

        vm.send("Rebuild the index")

        assertThat(sent()["env"]).isEqualTo(json("""{"type":"machine","name":"studio"}"""))
        assertThat(failure()).isEqualTo(MachineFleetServer.NOT_CONNECTED)
    }

    @Test
    fun `a repository the machine is not checked out at is refused in the server's words`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("devbox"))
        vm.selectRepo(vm.repo("acme/web"))

        vm.send("Work on the web app")

        assertThat(sent()["repos"]).isEqualTo(CursorJson.parseToJsonElement("""[{"url":"https://github.com/acme/web"}]"""))
        assertThat(failure()).isEqualTo(MachineFleetServer.OTHER_REPOSITORY)
    }

    @Test
    fun `a machine without a repository sends env alone and hears the server's refusal`() {
        val vm = composer()
        vm.selectDevice(DeviceTarget.machine("scratch-box"))
        // An any-repo worker names no repository; starting from scratch leaves none to send.
        assertThat(vm.state.value.deviceRepoUrl).isNull()
        vm.selectRepo(null)

        val id = vm.send("Sketch a CLI")

        assertThat(sent()).isEqualTo(json("""{"prompt":{"text":"Sketch a CLI"},"agentId":"$id","env":{"type":"machine","name":"scratch-box"}}"""))
        assertThat(failure()).isEqualTo(DocumentedCloudAgentsServer.REPOSITORY_REQUIRED)
    }

    @Test
    fun `a repository on the cloud goes out as before, with its branch`() {
        val vm = composer()
        vm.selectRepo(vm.repo("acme/web"))
        vm.setRef("main")

        val id = vm.send("Add a health check")

        assertThat(sent()).isEqualTo(
            json("""{"prompt":{"text":"Add a health check"},"agentId":"$id","repos":[{"url":"https://github.com/acme/web","startingRef":"main"}]}"""),
        )
        runBlocking { withTimeout(10_000) { while (graph.agents.agent(id)?.latestRunId == null) delay(10) } }
    }

    /**
     * A draft kept on the machine with the repository and branch it had then: the machine is checked out elsewhere
     * now, and the draft opens on the checkout it reports — the branch of the old repository left behind.
     */
    @Test
    fun `a restored draft on a machine follows the machine's checkout, not the repository saved with it`() {
        val draft = draft(id = "draft-stale", repoUrl = "https://github.com/acme/old-service", ref = "cursor/old-branch", repoPicked = false)
        runBlocking { graph.newChatDrafts.save(draft) }

        val vm = composer(resume = draft.id)
        runBlocking { withTimeout(10_000) { vm.state.first { it.prompt == draft.prompt && it.selectedRepo?.slug == "acme/payments-service" } } }
        assertThat(vm.state.value.selectedDevice).isEqualTo(DeviceTarget.machine("devbox"))
        assertThat(vm.state.value.ref).isEmpty()

        vm.send(draft.prompt)

        assertThat(sent()["env"]).isEqualTo(json("""{"type":"machine","name":"devbox"}"""))
        assertThat(sent()["repos"]).isEqualTo(CursorJson.parseToJsonElement("""[{"url":"https://github.com/acme/payments-service"}]"""))
    }

    @Test
    fun `a restored draft keeps a repository picked over the machine's, with its branch`() {
        val draft = draft(id = "draft-picked", repoUrl = "https://github.com/acme/web", ref = "main", repoPicked = true)
        runBlocking { graph.newChatDrafts.save(draft) }

        val vm = composer(resume = draft.id)
        runBlocking { withTimeout(10_000) { vm.state.first { it.prompt == draft.prompt && it.selectedRepo != null } } }
        assertThat(vm.state.value.selectedRepo?.slug).isEqualTo("acme/web")
        assertThat(vm.state.value.repoFollowsDevice).isFalse()
        assertThat(vm.state.value.ref).isEqualTo("main")
    }

    private fun draft(id: String, repoUrl: String, ref: String, repoPicked: Boolean) = DraftStore.Record(
        id = id,
        schema = DraftStore.SCHEMA,
        createdAtMillis = AppClock.now(),
        updatedAtMillis = AppClock.now(),
        prompt = "Scale the fleet",
        repoUrl = repoUrl,
        ref = ref,
        device = DeviceTarget.machine("devbox"),
        repoPicked = repoPicked,
        nonce = "nonce-$id",
    )
}

/**
 * `api.cursor.com` for a user with three machines and a GitHub app that reaches two repositories. The fleet endpoints
 * answer in the shape the reference documents (`GET /v0/private-workers`, "List Workers"): `repoOwner`/`repoName` from
 * the `repo=owner/name` label a worker registers for its checkout's origin, `repoUrl` beside them when there is one.
 * Creates go to [DocumentedCloudAgentsServer], after the two checks Cursor makes of a machine before it routes to it:
 * the repository has to be one its GitHub app reaches (the Cloud Agents API validates a machine's repository through
 * the app, forum.cursor.com/t/160136; `integration_not_connected` names the refusal), and the machine has to be
 * connected and registered for it ("a request for repo A should never run on a machine checkout for repo B").
 */
class MachineFleetServer : Dispatcher() {

    private val contract = DocumentedCloudAgentsServer()
    val creates = CopyOnWriteArrayList<String>()

    /** What `GET /v1/repositories` lists: the repositories reachable through Cursor's GitHub app. */
    private val reachable = listOf("acme/payments-service", "acme/web")

    /** Each connected machine's registered repository, as its `repo` label has it; blank for an any-repo worker. */
    private val registered = mapOf("devbox" to "acme/payments-service", "laptop" to "acme/web", "bennett" to "bennett/codex-poly-bot", "scratch-box" to "")

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path?.substringBefore('?').orEmpty()
        val query = request.requestUrl
        return when {
            request.method == "GET" && path == "/v1/me" -> json("""{"apiKeyName":"Test key","userId":42,"userEmail":"dev@example.com","userFirstName":"Dev"}""")
            request.method == "GET" && path == "/v1/models" -> json("""{"items":[{"id":"default","displayName":"Auto"},{"id":"claude-fable-5-1","displayName":"Claude Fable 5.1"}]}""")
            request.method == "GET" && path == "/v1/repositories" -> json("""{"items":[{"url":"https://github.com/acme/payments-service"},{"url":"https://github.com/acme/web"}]}""")
            request.method == "GET" && path == "/v0/private-workers" -> json(if (query?.queryParameter("scope") == "team_pool") """{"workers":[],"totalCount":0}""" else WORKERS)
            request.method == "GET" && path == "/v0/private-workers/pools" -> json("""{"pools":[]}""")
            request.method == "GET" && path == "/v1/agents" -> json("""{"items":[]}""")
            request.method == "GET" && path == "/v0/agents" -> json("""{"agents":[]}""")
            request.method == "POST" && path == "/v1/agents" -> create(request)
            else -> contract.dispatch(request)
        }
    }

    private fun create(request: RecordedRequest): MockResponse {
        val body = request.body.clone().readUtf8()
        creates += body
        val json = runCatching { CursorJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return contract.dispatch(request)
        val env = json["env"] as? JsonObject
        if ((env?.get("type") as? JsonPrimitive)?.content == "machine") {
            val name = (env["name"] as? JsonPrimitive)?.content
            val label = registered[name] ?: return error(400, "validation_error", NOT_CONNECTED)
            val url = ((json["repos"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("url")?.let { (it as JsonPrimitive).content }
            if (url != null) {
                val slug = Agent.repoSlugOf(url).orEmpty()
                if (!url.startsWith("https://github.com/") || reachable.none { it.equals(slug, ignoreCase = true) }) {
                    return error(400, "integration_not_connected", "The source control provider for repository $slug is not connected to Cursor. Connect it and try again.")
                }
                if (!label.equals(slug, ignoreCase = true)) return error(400, "validation_error", OTHER_REPOSITORY)
            }
        }
        return contract.dispatch(request)
    }

    private fun json(body: String): MockResponse = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun error(status: Int, code: String, message: String): MockResponse = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject { putJsonObject("error") { put("code", code); put("message", message) } }.toString())

    companion object {
        const val NOT_CONNECTED = "The requested self-hosted worker is not connected."
        const val OTHER_REPOSITORY = "This machine is registered for a different repository. Start the worker in a checkout of the target repo first."

        /**
         * `scope=personal`, as the reference's example has it: `devbox` with a GitHub remote, `laptop` reporting the
         * remote the way git spells it, `bennett` registered as `bennett/codex-poly-bot` with no `repoUrl`, and
         * `scratch-box`, an any-repo worker ("Empty strings for any-repo workers", `repoUrl` omitted).
         */
        val WORKERS = """
            {
              "workers": [
                {
                  "workerId": "a8574fe8-248e-424a-a078-7584a2b93724",
                  "repoOwner": "acme",
                  "repoName": "payments-service",
                  "repoUrl": "https://github.com/acme/payments-service",
                  "workspaceRootPath": "/home/dev/payments-service",
                  "connectedAtMs": 1737306880000,
                  "userId": 42,
                  "isInUse": false,
                  "name": "devbox"
                },
                {
                  "workerId": "0d6f2b1e-7c7a-4d8e-9a57-1b2c3d4e5f60",
                  "repoOwner": "acme",
                  "repoName": "web",
                  "repoUrl": "git@github.com:acme/web.git",
                  "workspaceRootPath": "/Users/dev/src/web",
                  "connectedAtMs": 1737306890000,
                  "userId": 42,
                  "isInUse": false,
                  "name": "laptop"
                },
                {
                  "workerId": "5f1c9d2a-3b4e-4f60-8a71-92b3c4d5e6f7",
                  "repoOwner": "bennett",
                  "repoName": "codex-poly-bot",
                  "workspaceRootPath": "/home/bennett/projects/codex-poly-bot",
                  "connectedAtMs": 1737306900000,
                  "userId": 42,
                  "isInUse": false,
                  "name": "bennett"
                },
                {
                  "workerId": "9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4",
                  "repoOwner": "",
                  "repoName": "",
                  "workspaceRootPath": "/home/dev/scratch",
                  "connectedAtMs": 1737306910000,
                  "userId": 42,
                  "isInUse": false,
                  "name": "scratch-box"
                }
              ],
              "totalCount": 4
            }
        """.trimIndent()
    }
}
