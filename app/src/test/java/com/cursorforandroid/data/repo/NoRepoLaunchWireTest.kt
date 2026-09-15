package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A new chat without a repository, end to end on the wire: the composer's [LaunchRequest] goes through the
 * [ChatLauncher], the conversation and agent repositories, Retrofit and the app's own OkHttp client to a fake
 * `api.cursor.com` that implements the documented Create An Agent contract ([DocumentedCloudAgentsServer]), and what
 * comes back reaches the composer the way it does in the app — as a chat on screen, or as a [FailedLaunch] with its
 * reason. Robolectric for the same reason as [ChatLauncherTest]: the session's stores need a Context.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NoRepoLaunchWireTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val contract = DocumentedCloudAgentsServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var agents: AgentRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var launcher: ChatLauncher
    private val failures = CopyOnWriteArrayList<FailedLaunch>()

    /** The composer's draft for "Start from scratch" on the ordinary cloud: a prompt, a model with a variant, nothing else. */
    private val fromScratch = LaunchRequest(
        prompt = "Build a small calculator",
        repoUrl = null,
        // A branch remembered from the repository picked before: nothing for it to refer to now.
        ref = "main",
        modelId = "claude-fable-5-1",
        modelParams = listOf(ModelParam("thinking", "max")),
        autoCreatePr = true,
        planMode = false,
    )

    @Before
    fun setUp() {
        server.dispatcher = contract
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferencesStore(context)
        // The app's client, with its interceptors, at a read timeout short enough for a silent server to be waited
        // out here in a second rather than a minute.
        val client = CursorApiFactory.okHttp { "test-key" }.newBuilder().readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val api = CursorApiFactory.retrofit(client, server.url("/").toString())
        val streamer = FakeRunStreamer()
        val backend = CursorBackend(api, streamer, isDemo = false)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val attachments = AttachmentStore(context)
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10, lostReplyProbeDelayMs = 50)
        val hub = LiveRunHub(session, agents, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
        conversations = ConversationRepository(session, agents, prefs, hub, attachments, isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope)
        launcher = ChatLauncher(conversations, scope)
        scope.launch { launcher.failures.collect { failures += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun idOf(request: LaunchRequest) = LaunchIdempotency.agentId(request, NONCE)

    private fun prompts(id: String) = conversations.state(id).value.items.filterIsInstance<UserMessage>().map { it.text }

    /** Launches [request] the way the composer does and waits until the chat has landed or the launch has failed. */
    private suspend fun launchAndSettle(request: LaunchRequest): String {
        val id = idOf(request)
        launcher.launch(request.copy(agentId = id), "Claude Fable 5.1", NONCE)
        awaitUntil { conversations.state(id).value.activeRunId != null || failures.isNotEmpty() }
        return id
    }

    @Test
    fun `start from scratch on the cloud goes out in the reference's explicit no-repo form and lands`() = runBlocking<Unit> {
        val id = launchAndSettle(fromScratch)

        val create = contract.creates.single()
        // Exactly the documented body: the prompt, the client-minted id, the model — and `repos: []` with no `env`,
        // "Omit both repos and env (or pass repos: []) to start a no-repo agent". No branch, no autoCreatePR: neither
        // has a repository to refer to.
        assertThat(create.path).isEqualTo("/v1/agents")
        assertThat(create.body).isEqualTo(
            """{"prompt":{"text":"Build a small calculator"},"agentId":"$id",""" +
                """"model":{"id":"claude-fable-5-1","params":[{"id":"thinking","value":"max"}]},"repos":[]}""",
        )
        assertThat(create.headers["Authorization"]).isEqualTo("Bearer test-key")
        assertThat(create.headers["Content-Type"]).startsWith("application/json")
        // The server's record — `repos` empty, `env` the cloud — is what the chat and its row are made of.
        assertThat(failures).isEmpty()
        val row = agents.agent(id)!!
        assertThat(row.repoUrl).isNull()
        assertThat(row.envType).isEqualTo(EnvType.CLOUD)
        assertThat(row.latestRunId).isEqualTo(contract.agents.getValue(id).runId)
        assertThat(conversations.state(id).value.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(prompts(id)).containsExactly("Build a small calculator")
    }

    @Test
    fun `a repository launch is unchanged on the wire`() = runBlocking<Unit> {
        val withRepo = fromScratch.copy(repoUrl = "https://github.com/acme/app", ref = "main", autoCreatePr = true)
        val id = launchAndSettle(withRepo)

        assertThat(contract.creates.single().body).isEqualTo(
            """{"prompt":{"text":"Build a small calculator"},"agentId":"$id",""" +
                """"model":{"id":"claude-fable-5-1","params":[{"id":"thinking","value":"max"}]},""" +
                """"repos":[{"url":"https://github.com/acme/app","startingRef":"main"}],"autoCreatePR":true}""",
        )
        assertThat(failures).isEmpty()
        assertThat(agents.agent(id)!!.repoUrl).isEqualTo("https://github.com/acme/app")
    }

    @Test
    fun `a repo-less pool sends env alone, as the reference allows`() = runBlocking<Unit> {
        val onPool = fromScratch.copy(env = DeviceTarget.pool("sandbox"))
        val id = launchAndSettle(onPool)

        // "Omit repos with type: pool to target a repo-less pool."
        assertThat(contract.creates.single().body).isEqualTo(
            """{"prompt":{"text":"Build a small calculator"},"agentId":"$id",""" +
                """"model":{"id":"claude-fable-5-1","params":[{"id":"thinking","value":"max"}]},""" +
                """"env":{"type":"pool","name":"sandbox"}}""",
        )
        assertThat(failures).isEmpty()
        val row = agents.agent(id)!!
        assertThat(row.envType).isEqualTo(EnvType.POOL)
        assertThat(row.envName).isEqualTo("sandbox")
        assertThat(row.repoUrl).isNull()
    }

    @Test
    fun `a named cloud environment sends env alone, with no repos beside it`() = runBlocking<Unit> {
        val named = fromScratch.copy(env = DeviceTarget.of(EnvType.CLOUD, "Release workspace"))
        val id = launchAndSettle(named)

        assertThat(contract.creates.single().body).isEqualTo(
            """{"prompt":{"text":"Build a small calculator"},"agentId":"$id",""" +
                """"model":{"id":"claude-fable-5-1","params":[{"id":"thinking","value":"max"}]},""" +
                """"env":{"type":"cloud","name":"Release workspace"}}""",
        )
        assertThat(failures).isEmpty()
        assertThat(agents.agent(id)!!.envName).isEqualTo("Release workspace")
    }

    @Test
    fun `a machine without a repository hears the server's refusal at once, in its words`() = runBlocking<Unit> {
        val onMachine = fromScratch.copy(env = DeviceTarget.machine("bennett#/home/bennett/app"))
        val startedAt = System.nanoTime()
        val id = launchAndSettle(onMachine)

        // `env` alone went out; the server answered 400 repository_required (its documented refusal for a machine
        // without a repository) and the composer has the message, verbatim, well within the read timeout: an answer
        // the server gave is never waited on or looked up again.
        assertThat(contract.creates.single().body).contains(""""env":{"type":"machine","name":"bennett"}""")
        assertThat(contract.creates.single().body).doesNotContain("repos")
        val failed = failures.single()
        assertThat(failed.agentId).isEqualTo(id)
        assertThat(failed.reason).isEqualTo(DocumentedCloudAgentsServer.REPOSITORY_REQUIRED)
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(READ_TIMEOUT_MS)
        assertThat(contract.reads).isEmpty()
        // Nothing of the chat is left behind, on screen or in the list.
        assertThat(prompts(id)).isEmpty()
        assertThat(agents.agent(id)).isNull()
    }

    @Test
    fun `a validation error reaches the composer as the server worded it`() = runBlocking<Unit> {
        contract.refuseCreatesWith = 400 to ("validation_error" to "Failed to determine repository default branch.")
        val id = launchAndSettle(fromScratch)

        assertThat(failures.single().reason).isEqualTo("Failed to determine repository default branch.")
        assertThat(agents.agent(id)).isNull()
    }

    @Test
    fun `a launch whose reply the server never sends adopts the chat the server created`() = runBlocking<Unit> {
        // The server acts on the request — the agent exists under the client's id — but never answers: the
        // connection sits silent past the read timeout. The launch then finds the chat by id and carries on with it.
        contract.swallowCreates = true
        contract.createSilently = true
        val id = launchAndSettle(fromScratch)

        assertThat(failures).isEmpty()
        assertThat(contract.creates).hasSize(1)
        assertThat(contract.reads).containsExactly("/v1/agents/$id", "/v1/agents/$id/runs/${contract.agents.getValue(id).runId}").inOrder()
        val row = agents.agent(id)!!
        assertThat(row.latestRunId).isEqualTo(contract.agents.getValue(id).runId)
        assertThat(row.repoUrl).isNull()
        assertThat(conversations.state(id).value.activeRunId).isEqualTo(row.latestRunId)
        assertThat(prompts(id)).containsExactly("Build a small calculator")
    }

    @Test
    fun `a launch whose reply the server never sends, and that created nothing, fails saying so`() = runBlocking<Unit> {
        contract.swallowCreates = true
        val id = launchAndSettle(fromScratch)

        val failed = failures.single()
        assertThat(failed.agentId).isEqualTo(id)
        // Not a bare "took too long": the chat was looked for by id, is nowhere, and a retry is safe.
        assertThat(failed.reason).isEqualTo(
            "Cursor didn't answer the request to start this chat, and no chat appeared on your account. " +
                "Send it again to retry: a chat Cursor did create after all is picked up rather than started twice.",
        )
        assertThat(contract.reads).hasSize(AgentRepository.LOST_REPLY_PROBES)
        assertThat(contract.reads.toSet()).containsExactly("/v1/agents/$id")
        assertThat(prompts(id)).isEmpty()
        assertThat(agents.agent(id)).isNull()
        // The draft that comes back goes out under the same id, so a server that did create the chat late is met
        // with 409 agent_id_conflict and the chat adopted rather than started twice.
        assertThat(failed.request.agentId).isEqualTo(id)
        assertThat(failed.nonce).isEqualTo(NONCE)
    }

    @Test
    fun `a gateway's answer without the API's body still fails at once with its status`() = runBlocking<Unit> {
        contract.gatewayFailure = 502 to "<html><body>Bad Gateway</body></html>"
        val startedAt = System.nanoTime()
        val id = launchAndSettle(fromScratch)

        val failed = failures.single()
        assertThat(failed.reason).isNotEmpty()
        assertThat(failed.reason).doesNotContain("took too long")
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(READ_TIMEOUT_MS)
        assertThat(contract.reads).isEmpty()
        assertThat(agents.agent(id)).isNull()
    }

    private companion object {
        const val NONCE = "nonce-1"
        const val READ_TIMEOUT_MS = 1_000L
    }
}

/**
 * `api.cursor.com` as the Cloud Agents OpenAPI (`cloud-agents-openapi.yaml`, fetched 2026-09-15) and the endpoint
 * reference describe Create An Agent, Get An Agent and Get A Run, plus the one refusal the reference does not spell
 * out and the server does: a `machine` without a repository is answered `400 repository_required`. Every other route
 * answers `404 not_found` in the API's error shape. Test hooks make the server refuse, swallow or misbehave on demand.
 */
class DocumentedCloudAgentsServer : Dispatcher() {

    class Create(val path: String, val body: String, val headers: Map<String, String>)
    class Agent(val id: String, val runId: String, val body: JsonObject, val run: JsonObject)

    val creates = CopyOnWriteArrayList<Create>()
    /** Every `GET` path asked of the server, in order. */
    val reads = CopyOnWriteArrayList<String>()
    val agents = java.util.concurrent.ConcurrentHashMap<String, Agent>()

    /** Answers every well-formed create with this status and API error instead of creating anything. */
    @Volatile var refuseCreatesWith: Pair<Int, Pair<String, String>>? = null
    /** Answers every create with this status and a non-JSON body, the way a gateway in front of the API does. */
    @Volatile var gatewayFailure: Pair<Int, String>? = null
    /** Never answers a create: the connection stays open and silent until the client gives up. */
    @Volatile var swallowCreates = false
    /** With [swallowCreates]: the agent is created all the same, so a later read by id finds it. */
    @Volatile var createSilently = false

    private val ids = java.util.concurrent.atomic.AtomicInteger()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path?.substringBefore('?') ?: return error(404, "not_found", "No such route.")
        return when {
            request.method == "POST" && path == "/v1/agents" -> create(request)
            request.method == "GET" && AGENT_PATH.matches(path) -> {
                reads += path
                val id = AGENT_PATH.matchEntire(path)!!.groupValues[1]
                agents[id]?.let { json(200, it.body) } ?: error(404, "agent_not_found", "Agent not found.")
            }
            request.method == "GET" && RUN_PATH.matches(path) -> {
                reads += path
                val (id, runId) = RUN_PATH.matchEntire(path)!!.destructured
                agents[id]?.takeIf { it.runId == runId }?.let { json(200, it.run) } ?: error(404, "run_not_found", "Run not found.")
            }
            else -> {
                if (request.method == "GET") reads += path
                error(404, "not_found", "No such route.")
            }
        }
    }

    private fun create(request: RecordedRequest): MockResponse {
        val body = request.body.readUtf8()
        creates += Create(request.path!!, body, request.headers.names().associateWith { request.headers[it]!! })
        val json = runCatching { CursorJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return error(400, "missing_body", "Request body must be a JSON object.")
        // CreateAgentRequest: prompt.text required, minLength 1.
        val text = (json["prompt"] as? JsonObject)?.get("text")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        if (text.isNullOrEmpty()) return error(400, "validation_error", "prompt.text is required.")
        // AgentEnv: type required whenever env is given, one of cloud / pool / machine.
        val env = json["env"]?.let { it as? JsonObject ?: return error(400, "validation_error", "env must be an object.") }
        val envType = env?.get("type")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        if (env != null && envType !in ENV_TYPES) return error(400, "validation_error", "env.type is required when env is provided.")
        val envName = env?.get("name")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        // RepoConfig: url required on every entry, minLength 1; at most 20 entries.
        val repos = json["repos"]?.let { it as? JsonArray ?: return error(400, "validation_error", "repos must be an array.") }
        if (repos != null && repos.size > 20) return error(400, "validation_error", "repos holds at most 20 entries.")
        repos?.forEach { entry ->
            val url = (entry as? JsonObject)?.get("url")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
            if (url.isNullOrEmpty()) return error(400, "validation_error", "repos[].url is required.")
        }
        // "Mutually exclusive with a named cloud environment."
        if (envType == "cloud" && envName != null && !repos.isNullOrEmpty()) return error(400, "validation_error", "repos cannot be combined with a named cloud environment.")
        // The server's word for a machine with nothing to check out (not in the reference; Cursor's forum, May 2026).
        if (envType == "machine" && repos.isNullOrEmpty()) return error(400, "repository_required", REPOSITORY_REQUIRED)
        // agentId: bc-<uuid>, and never twice.
        val agentId = json["agentId"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        if (agentId != null && !AGENT_ID.matches(agentId)) return error(400, "validation_error", "agentId must be a bc-<uuid>.")
        if (agentId != null && agents.containsKey(agentId)) return error(409, "agent_id_conflict", "An agent with this id already exists.")
        refuseCreatesWith?.let { (status, codeAndMessage) -> return error(status, codeAndMessage.first, codeAndMessage.second) }
        gatewayFailure?.let { (status, html) -> return MockResponse().setResponseCode(status).setHeader("Content-Type", "text/html").setBody(html) }
        if (swallowCreates) {
            if (createSilently) register(agentId, text, json, env, repos)
            return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        val created = register(agentId, text, json, env, repos)
        // CreateAgentResponse: the durable agent and the enqueued run.
        return json(201, buildJsonObject { put("agent", created.body); put("run", created.run) })
    }

    private fun register(agentId: String?, text: String, json: JsonObject, env: JsonObject?, repos: JsonArray?): Agent {
        val id = agentId ?: "bc-00000000-0000-0000-0000-%012d".format(ids.incrementAndGet())
        val runId = "run-00000000-0000-0000-0000-%012d".format(ids.incrementAndGet())
        val agent = buildJsonObject {
            put("id", id)
            put("name", (json["name"] as? JsonPrimitive)?.content ?: text.take(60))
            put("status", "ACTIVE")
            // The env as it was asked for; the ordinary cloud when none was.
            putJsonObject("env") {
                put("type", (env?.get("type") as? JsonPrimitive)?.content ?: "cloud")
                (env?.get("name") as? JsonPrimitive)?.content?.let { put("name", it) }
            }
            // Agent.repos: "Empty for no-repo agents."
            putJsonArray("repos") { repos?.forEach { add(it) } }
            put("workOnCurrentBranch", false)
            put("autoCreatePR", (json["autoCreatePR"] as? JsonPrimitive)?.content == "true")
            put("url", "https://cursor.com/agents/$id")
            put("createdAt", NOW)
            put("updatedAt", NOW)
            put("latestRunId", runId)
        }
        val run = buildJsonObject {
            put("id", runId)
            put("agentId", id)
            put("status", "CREATING")
            put("createdAt", NOW)
            put("updatedAt", NOW)
        }
        return Agent(id, runId, agent, run).also { agents[id] = it }
    }

    private fun json(status: Int, body: JsonObject): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body.toString())

    /** The API's error shape: `{ error: { code, message } }`. */
    private fun error(status: Int, code: String, message: String): MockResponse =
        json(status, buildJsonObject { putJsonObject("error") { put("code", code); put("message", message) } })

    companion object {
        const val REPOSITORY_REQUIRED = "Repository is required. Either provide a repository URL in the repos[0].url field, or configure a default repository at https://cursor.com/settings."
        private const val NOW = "2026-09-15T13:30:00.000Z"
        private val ENV_TYPES = setOf("cloud", "pool", "machine")
        private val AGENT_ID = Regex("^bc-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val AGENT_PATH = Regex("^/v1/agents/([^/]+)$")
        private val RUN_PATH = Regex("^/v1/agents/([^/]+)/runs/([^/]+)$")
    }
}
