package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConnectAgentStartApi
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.ConnectProjectCreationApi
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.PresignedPromptUpload
import com.cursorforandroid.data.api.PromptUploadApi
import com.cursorforandroid.data.api.PromptUploadCompletion
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ProjectDiagnostics
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SendDiagnostics
import com.cursorforandroid.domain.UploadRef
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * "Start from scratch" with a file attached (Extended mode), end to end on the wire — the launch Bennett's 0.3.41
 * screenshot shows failing: an image and a video on the prompt, no repository, the ordinary cloud. A prompt with a
 * file cannot go through the documented create, so it goes the desktop's way: the account's `ListEnvironments` for
 * the personal no-repo environment (created with `SetPersonalEnvironmentJson` when there is none), then
 * `StartBackgroundComposerFromSnapshot` against it, then the chat read back through `GET /v1/agents/{id}`. Two fake
 * servers stand in: `api2.cursor.sh` ([AccountServer]) with the environment list in the shape the account really
 * sends — proto3 JSON, where an `EnvironmentRepoConfig` with no repositories is `"repoConfig": {}` — and
 * `api.cursor.com` ([DocumentedCloudAgentsServer]) for the read-back. Robolectric for the session's stores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NoRepoFilesLaunchWireTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val publicServer = MockWebServer()
    private val accountServer = MockWebServer()
    private val contract = DocumentedCloudAgentsServer()
    private val account = AccountServer(contract)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var agents: AgentRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var launcher: ChatLauncher
    private val failures = CopyOnWriteArrayList<FailedLaunch>()

    /** The screenshot's draft: a pasted image, a video already uploaded when it was attached, no text, no repository, Grok. */
    private val fromScratchWithVideo = LaunchRequest(
        prompt = "Take this image and stitch it into the green screen on the monitor in this video and return the video to me, please.",
        images = listOf(PromptImage(byteArrayOf(1, 2, 3, 4), "image/png")),
        files = listOf(PromptFile(ByteArray(64) { it.toByte() }, "65747.mp4", "video/mp4", UploadRef("upl_65747", "s3-65747", "uuid-65747"))),
        repoUrl = null,
        ref = "main",
        modelId = "cursor-grok-4.6",
        modelParams = listOf(ModelParam("thinking", "high")),
        autoCreatePr = false,
        planMode = false,
    )

    @Before
    fun setUp() {
        publicServer.dispatcher = contract
        publicServer.start()
        accountServer.dispatcher = account
        accountServer.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferencesStore(context)
        val client = CursorApiFactory.okHttp { "test-key" }.newBuilder().readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val api = CursorApiFactory.retrofit(client, publicServer.url("/").toString())
        val streamer = FakeRunStreamer()
        val backend = CursorBackend(api, streamer, isDemo = false)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val attachments = AttachmentStore(context)
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        // The account side as the graph wires it: one Connect client, the session minted from the key, the start
        // API borrowing the Project creation API's no-repo environment lookup.
        val accountBase = accountServer.url("/").toString()
        val rpc = ConnectJsonClient(OkHttpClient(), accountBase)
        val tokens = SessionTokenProvider(OkHttpClient(), apiKeyProvider = { "test-key" }, apiUrl = accountBase, now = { 0L })
        val creation = ConnectProjectCreationApi(rpc, tokens)
        val start = ConnectAgentStartApi(rpc, tokens, noRepoEnvironment = { creation.noRepoEnvironmentPublicId() })
        agents = AgentRepository(
            session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10, lostReplyProbeDelayMs = 50,
            capabilities = { Capabilities.EXTENDED },
            start = { start },
            uploads = { PromptUploader(NoUploads, OkHttpClient(), partTimeoutMs = 0L) },
        )
        val hub = LiveRunHub(session, agents, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
        conversations = ConversationRepository(session, agents, prefs, hub, attachments, isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope)
        launcher = ChatLauncher(conversations, scope)
        scope.launch { launcher.failures.collect { failures += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        publicServer.shutdown()
        accountServer.shutdown()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun prompts(id: String) = conversations.state(id).value.items.filterIsInstance<UserMessage>().map { it.text }

    /** Launches [request] the way the composer does and waits until the chat has landed or the launch has failed. */
    private suspend fun launchAndSettle(request: LaunchRequest): String {
        val id = LaunchIdempotency.agentId(request, NONCE)
        launcher.launch(request.copy(agentId = id), "Cursor Grok 4.6", NONCE)
        awaitUntil { conversations.state(id).value.activeRunId != null || failures.isNotEmpty() }
        return id
    }

    @Test
    fun `a no-repo chat with a video starts in the account's no-repo environment, whose repo config the account sends empty`() = runBlocking<Unit> {
        // Bennett's account list: twenty-eight environments, the personal no-repo one last, its `EnvironmentRepoConfig`
        // with no repositories — which proto3 JSON writes as an empty object, not as `{"repos": []}`.
        account.environments = (1..27).map { n -> repoEnvironment("env-$n", "https://github.com/acme/app-$n") } + noRepoEnvironmentAsSent("env-personal-scratch")

        val id = launchAndSettle(fromScratchWithVideo)

        // The launch went the account's way: nothing to the documented create, the list read, no environment written.
        assertThat(contract.creates).isEmpty()
        assertThat(account.calls.map { it.method }).containsExactly("ListEnvironments", "StartBackgroundComposerFromSnapshot").inOrder()
        val start = account.calls.last().body
        assertThat(start["bcId"]?.jsonPrimitive?.content).isEqualTo(id)
        assertThat(start["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("env|env-personal-scratch")
        assertThat(start["devcontainerStartingPoint"]!!.jsonObject["environmentPublicId"]?.jsonPrimitive?.content).isEqualTo("env-personal-scratch")
        assertThat(start["repoUrl"]).isNull()
        assertThat(start["requestedModels"]!!.jsonArray.single().jsonObject["modelId"]?.jsonPrimitive?.content).isEqualTo("cursor-grok-4.6")
        val context = start["conversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject["userMessage"]!!.jsonObject["selectedContext"]!!.jsonObject
        assertThat(context["selectedImages"]!!.jsonArray).hasSize(1)
        assertThat(context["selectedDocuments"]!!.jsonArray.single().jsonObject["promptUploadRef"]!!.jsonObject["uploadId"]?.jsonPrimitive?.content).isEqualTo("upl_65747")
        // Then read back through the documented API and on screen, with its run.
        assertThat(failures).isEmpty()
        val row = agents.agent(id)!!
        assertThat(row.repoUrl).isNull()
        assertThat(row.envType).isEqualTo(EnvType.CLOUD)
        assertThat(row.latestRunId).isEqualTo(contract.agents.getValue(id).runId)
        assertThat(conversations.state(id).value.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(prompts(id)).containsExactly(fromScratchWithVideo.prompt)
    }

    @Test
    fun `without a no-repo environment one is written the desktop's way and used`() = runBlocking<Unit> {
        account.environments = listOf(repoEnvironment("env-1", "https://github.com/acme/app"))

        val id = launchAndSettle(fromScratchWithVideo)

        assertThat(failures).isEmpty()
        assertThat(account.calls.map { it.method }).containsExactly("ListEnvironments", "SetPersonalEnvironmentJson", "StartBackgroundComposerFromSnapshot").inOrder()
        val written = account.calls[1].body
        assertThat(written["environmentJson"]?.jsonPrimitive?.content).isEqualTo("{}")
        assertThat(written["repoUrl"]?.jsonPrimitive?.content).isEqualTo("")
        assertThat(written["repoConfig"]!!.jsonObject["repos"]!!.jsonArray).isEmpty()
        assertThat(account.calls.last().body["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("env|${AccountServer.CREATED_ENVIRONMENT}")
        assertThat(agents.agent(id)).isNotNull()
    }

    @Test
    fun `the account's refusal reaches the composer in its words, at once`() = runBlocking<Unit> {
        account.environments = listOf(noRepoEnvironmentAsSent("env-personal-scratch"))
        account.refuseStartWith = 400 to "At least one model details is required"
        val startedAt = System.nanoTime()

        val id = launchAndSettle(fromScratchWithVideo)

        val failed = failures.single()
        assertThat(failed.agentId).isEqualTo(id)
        assertThat(failed.reason).isEqualTo("At least one model details is required")
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(READ_TIMEOUT_MS)
        assertThat(contract.reads).isEmpty()
        assertThat(prompts(id)).isEmpty()
        assertThat(agents.agent(id)).isNull()
    }

    @Test
    fun `an answer of the account's that cannot be read fails the launch naming the call, not the field`() = runBlocking<Unit> {
        // The list comes back in a shape no version of this app can read at all.
        account.environmentsBody = """{"environments":"none of your business"}"""

        val id = launchAndSettle(fromScratchWithVideo)

        val failed = failures.single()
        assertThat(failed.reason).startsWith("Cursor's answer to ListEnvironments could not be read")
        assertThat(agents.agent(id)).isNull()
    }

    @Test
    fun `a chat the account started but the API has not listed yet stays, stood in from the account's record`() = runBlocking<Unit> {
        account.environments = listOf(noRepoEnvironmentAsSent("env-personal-scratch"))
        // The account starts the chat and answers with its record; the documented API knows nothing of it for now.
        account.registerOnPublicApi = false

        val id = LaunchIdempotency.agentId(fromScratchWithVideo, NONCE)
        launcher.launch(fromScratchWithVideo.copy(agentId = id), "Cursor Grok 4.6", NONCE)
        awaitUntil { agents.launchDiagnostics(id)?.outcome?.startsWith("stood in") == true || failures.isNotEmpty() }

        // No failure, no rollback: the chat exists on the account. The row carries the record's name and the request's
        // facts until the API lists the chat; the API was asked the usual few times first.
        assertThat(failures).isEmpty()
        assertThat(contract.reads).hasSize(AgentRepository.LOST_REPLY_PROBES)
        val row = agents.agent(id)!!
        assertThat(row.name).isEqualTo(AccountServer.RECORD_NAME)
        assertThat(row.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(row.latestRunId).isNull()
        assertThat(row.repoUrl).isNull()
        assertThat(row.envType).isEqualTo(EnvType.CLOUD)
        // The diagnostics say which way the launch went and how it ended.
        val line = agents.launchDiagnostics(id)!!
        assertThat(line.via).isEqualTo("account")
        assertThat(line.target).isEqualTo("no-repo(personal environment)")
        assertThat(line.files).isEqualTo(1)
        assertThat(line.images).isEqualTo(1)
        assertThat(line.outcome).isEqualTo("stood in from the account's record, not listed by the API after ${AgentRepository.LOST_REPLY_PROBES} reads")
    }

    @Test
    fun `the diagnostics carry the launch's way and outcome for the composer's sends too`() = runBlocking<Unit> {
        account.environments = listOf(noRepoEnvironmentAsSent("env-personal-scratch"))
        val id = launchAndSettle(fromScratchWithVideo)

        val line = agents.launchDiagnostics(id)!!
        assertThat(line.via).isEqualTo("account")
        assertThat(line.target).isEqualTo("no-repo(personal environment)")
        assertThat(line.outcome).isEqualTo("accepted run=${ProjectDiagnostics.tail(contract.agents.getValue(id).runId)}")
        assertThat(line.detail).isNull()
        val rendered = SendDiagnostics(decision = null, decidedAtIso = null, queue = emptyList(), attempts = emptyList(), accepted = emptyList(), launch = line).render()
        assertThat(rendered).contains("launch: at=")
        assertThat(rendered).contains(" via=account target=no-repo(personal environment) files=1 images=1 outcome=accepted run=")
    }

    private fun repoEnvironment(publicId: String, repoUrl: String): JsonObject = buildJsonObject {
        put("id", "1$publicId".hashCode().toString())
        put("publicId", publicId)
        put("name", publicId)
        put("scope", "LOGICAL_ENVIRONMENT_SCOPE_PERSONAL")
        putJsonObject("repoConfig") { putJsonArray("repos") { add(buildJsonObject { put("repoUrl", repoUrl) }) } }
        put("environmentJson", """{"snapshot":"default"}""")
    }

    /** The personal no-repo environment as protobuf-es writes it: an empty repo config is `{}`, and there is no environment json to speak of. */
    private fun noRepoEnvironmentAsSent(publicId: String): JsonObject = buildJsonObject {
        put("id", "42")
        put("publicId", publicId)
        put("name", "")
        put("scope", "LOGICAL_ENVIRONMENT_SCOPE_PERSONAL")
        putJsonObject("repoConfig") {}
        put("environmentJson", "{}")
    }

    /** No presign is ever needed: the video carries the reference its upload settled on when it was attached. */
    private object NoUploads : PromptUploadApi {
        override suspend fun presign(filename: String, mimeType: String, contentLengthBytes: Long, teamId: Int?): PresignedPromptUpload = throw ConnectRpcException(500, null, "no presign in this test")
        override suspend fun complete(uploadId: String, s3UploadId: String) = PromptUploadCompletion.COMPLETED
        override suspend fun abort(uploadId: String, s3UploadId: String) = Unit
    }

    private companion object {
        const val NONCE = "nonce-1"
        const val READ_TIMEOUT_MS = 1_000L
    }
}

/**
 * `api2.cursor.sh` for a no-repo start: the key exchange, `ListEnvironments` with the account's environments as
 * protobuf-es writes them, `SetPersonalEnvironmentJson` creating the personal no-repo environment, and
 * `StartBackgroundComposerFromSnapshot` answering with the chat's record — and, unless told otherwise, registering
 * the chat on the documented API, which lists what the account created.
 */
class AccountServer(private val publicApi: DocumentedCloudAgentsServer) : Dispatcher() {

    class Call(val method: String, val body: JsonObject)

    val calls = CopyOnWriteArrayList<Call>()
    /** The environments the account lists, as JSON objects; see the test's builders for the wire shapes. */
    @Volatile var environments: List<JsonObject> = emptyList()
    /** A raw body for `ListEnvironments`, over [environments], for answers of a shape this app cannot read. */
    @Volatile var environmentsBody: String? = null
    /** Refuses the start with this status and Connect error message. */
    @Volatile var refuseStartWith: Pair<Int, String>? = null
    /** Whether a started chat appears on the documented API (it does on the real account, a moment later). */
    @Volatile var registerOnPublicApi = true

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: return connectError(404, "not_found", "No such route.")
        if (path == "/auth/exchange_user_api_key") return json(200, """{"accessToken":"session-token","refreshToken":"rt"}""")
        val method = path.substringAfterLast('/')
        val body = runCatching { CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        calls += Call(method, body)
        return when (method) {
            "ListEnvironments" -> environmentsBody?.let { json(200, it) } ?: json(200, buildJsonObject { put("environments", buildJsonArray { environments.forEach { add(it) } }) }.toString())
            "SetPersonalEnvironmentJson" -> json(
                200,
                buildJsonObject {
                    putJsonObject("environment") {
                        put("id", "77")
                        put("publicId", CREATED_ENVIRONMENT)
                        put("scope", "LOGICAL_ENVIRONMENT_SCOPE_PERSONAL")
                        putJsonObject("repoConfig") {}
                        put("environmentJson", "{}")
                    }
                    put("created", true)
                }.toString(),
            )
            "StartBackgroundComposerFromSnapshot" -> {
                refuseStartWith?.let { (status, message) -> return connectError(status, "invalid_argument", message) }
                val bcId = body["bcId"]?.jsonPrimitive?.content ?: return connectError(400, "invalid_argument", "bc_id is required")
                if (registerOnPublicApi) publicApi.registerStarted(bcId, RECORD_NAME)
                json(200, buildJsonObject { putJsonObject("composer") { put("bcId", bcId); put("name", RECORD_NAME); put("status", "BACKGROUND_COMPOSER_STATUS_CREATING") } }.toString())
            }
            else -> connectError(404, "unimplemented", "No such method: $method")
        }
    }

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    /** A Connect error: `{code, message}`; Cursor's readable text usually rides in `details[].debug.details.detail`. */
    private fun connectError(status: Int, code: String, message: String) = json(status, buildJsonObject { put("code", code); put("message", message) }.toString())

    companion object {
        const val CREATED_ENVIRONMENT = "env-created-now"
        const val RECORD_NAME = "Green screen stitch"
    }
}
