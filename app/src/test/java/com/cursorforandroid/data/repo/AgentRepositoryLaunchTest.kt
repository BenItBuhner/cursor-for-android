package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AgentStartApi
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.PresignedPromptUpload
import com.cursorforandroid.data.api.PromptUploadApi
import com.cursorforandroid.data.api.PromptUploadCompletion
import com.cursorforandroid.data.api.StartRequest
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UploadRef
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
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
        // The reads a lost reply is followed by are spaced seconds apart in the app; here they only need to happen.
        agents = AgentRepository(session, prefs, AttachmentStore(context), lostReplyProbeDelayMs = 10)
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
        assertThat(api.createRequests.single().env).isNull()
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

    /** The account's start and uploads, scriptable: what a launch with files hands them, and the record they answer with. */
    private class FakeStart : AgentStartApi {
        val requests = ArrayList<StartRequest>()
        var onStart: (StartRequest) -> Unit = {}
        override suspend fun start(request: StartRequest): ComposerSnapshot {
            requests += request
            onStart(request)
            return ComposerSnapshot(request.agentId, name = "From the account")
        }
    }

    private class FakeUploads : PromptUploadApi {
        val presigned = ArrayList<String>()
        override suspend fun presign(filename: String, mimeType: String, contentLengthBytes: Long, teamId: Int?): PresignedPromptUpload {
            presigned += filename
            // No upload path: the uploader carries the bytes inline, so no storage is needed here.
            throw ConnectRpcException(404, "unimplemented", "no presign in this fake")
        }
        override suspend fun complete(uploadId: String, s3UploadId: String) = PromptUploadCompletion.COMPLETED
        override suspend fun abort(uploadId: String, s3UploadId: String) = Unit
    }

    private fun withAccountStart(start: FakeStart, uploads: FakeUploads, capabilities: Capabilities = Capabilities.EXTENDED): AgentRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = true)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        runBlocking { session.enterDemo() }
        return AgentRepository(
            session, prefs, AttachmentStore(context), lostReplyProbeDelayMs = 10,
            capabilities = { capabilities },
            start = { start },
            uploads = { PromptUploader(uploads, OkHttpClient(), partTimeoutMs = 0L) },
        )
    }

    @Test
    fun `a launch with files goes through the account's start with the files uploaded, then adopts the chat from the API`() = runBlocking<Unit> {
        val start = FakeStart()
        val uploads = FakeUploads()
        val agents = withAccountStart(start, uploads)
        val withFiles = request.copy(
            files = listOf(PromptFile(byteArrayOf(1, 2, 3), "spec.pdf", "application/pdf")),
            images = listOf(PromptImage(byteArrayOf(9), "image/png")),
            planMode = true,
            autoCreatePr = true,
        )
        val id = LaunchIdempotency.agentId(withFiles, "nonce")
        // The account creates the chat; the API lists it under the minted id a moment later.
        start.onStart = { api.addRunningAgent(it.agentId, "Sync merge and chat state", "run-server-1") }

        val (agent, run) = agents.launch(withFiles.copy(agentId = id), "Auto").getOrThrow()

        // Nothing went to the documented create: the account's start carried the prompt, its files and its images.
        assertThat(api.createRequests).isEmpty()
        val sent = start.requests.single()
        assertThat(sent.agentId).isEqualTo(id)
        assertThat(sent.text).isEqualTo(request.prompt)
        assertThat(sent.repoUrl).isEqualTo(request.repoUrl)
        assertThat(sent.ref).isEqualTo("main")
        assertThat(sent.modelId).isEqualTo("auto-smart")
        assertThat(sent.mode).isEqualTo(AgentMode.PLAN)
        assertThat(sent.autoCreatePr).isTrue()
        assertThat(sent.environmentName).isNull()
        assertThat(sent.images).hasSize(1)
        assertThat(sent.files.single().filename).isEqualTo("spec.pdf")
        assertThat(sent.files.single().mimeType).isEqualTo("application/pdf")
        assertThat(sent.files.single().data).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(uploads.presigned).containsExactly("spec.pdf")
        // Adopted the way a lost reply's chat is: the row from GET /v1/agents/{id}, its run read once named.
        assertThat(agent.id).isEqualTo(id)
        assertThat(agent.name).isEqualTo("Sync merge and chat state")
        assertThat(run?.id).isEqualTo("run-server-1")
        assertThat(agent.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(agent.modelDisplayName).isEqualTo("Auto")
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)
        // The files were kept for the transcript under the run, beside the image.
        val kept = AttachmentStore(ApplicationProvider.getApplicationContext()).forAgent(id).getValue("run-server-1")
        assertThat(kept.filter { it.isFile }.map { it.name }).containsExactly("spec.pdf")
    }

    /**
     * The composer uploads a file the moment it is attached (see [AttachmentUploads]); the launch then meets files
     * carrying their references and puts them in the start request as they are — no presign, no bytes on the wire.
     */
    @Test
    fun `a launch whose files were uploaded on attach sends their references and uploads nothing`() = runBlocking<Unit> {
        val start = FakeStart()
        val uploads = FakeUploads()
        val agents = withAccountStart(start, uploads)
        val ref = UploadRef("upl_spec", "s3-spec", "uuid-spec")
        val withFiles = request.copy(files = listOf(PromptFile(byteArrayOf(1, 2, 3), "spec.pdf", "application/pdf", ref)))
        val id = LaunchIdempotency.agentId(withFiles, "nonce")
        start.onStart = { api.addRunningAgent(it.agentId, "Sync merge and chat state", "run-server-1") }

        agents.launch(withFiles.copy(agentId = id), "Auto").getOrThrow()

        val sent = start.requests.single().files.single()
        assertThat(sent.uploadId).isEqualTo("upl_spec")
        assertThat(sent.s3UploadId).isEqualTo("s3-spec")
        assertThat(sent.uuid).isEqualTo("uuid-spec")
        assertThat(sent.data).isNull()
        assertThat(uploads.presigned).isEmpty()
    }

    @Test
    fun `a launch with files is refused before anything is sent when the mode is off, or the chat would run on a pool or machine`() = runBlocking<Unit> {
        val start = FakeStart()
        val withFiles = request.copy(files = listOf(PromptFile(byteArrayOf(1), "a.txt", "text/plain")))
        val id = LaunchIdempotency.agentId(withFiles, "nonce")

        val off = withAccountStart(start, FakeUploads(), capabilities = Capabilities.DOCUMENTED).launch(withFiles.copy(agentId = id), "Auto")
        assertThat(off.exceptionOrNull()).hasMessageThat().isEqualTo(AgentRepository.FILES_NEED_EXTENDED)

        val pool = withAccountStart(start, FakeUploads()).launch(withFiles.copy(agentId = id, env = DeviceTarget.pool("team-pool")), "Auto")
        assertThat(pool.exceptionOrNull()).hasMessageThat().isEqualTo(AgentRepository.FILES_NEED_CLOUD)

        assertThat(start.requests).isEmpty()
        assertThat(api.createRequests).isEmpty()
        assertThat(agents.state.value.agents).isEmpty()
    }

    @Test
    fun `an Ask or Debug chat starts on the account in its mode, and Plan or Agent still takes the documented create`() = runBlocking<Unit> {
        val start = FakeStart()
        val agents = withAccountStart(start, FakeUploads())
        start.onStart = { api.addRunningAgent(it.agentId, "Explain the cache", "run-server-2") }

        val ask = request.copy(accountMode = AgentMode.ASK, planMode = true)
        agents.launch(ask.copy(agentId = LaunchIdempotency.agentId(ask, "nonce")), "Auto").getOrThrow()
        val debug = request.copy(accountMode = AgentMode.DEBUG)
        agents.launch(debug.copy(agentId = LaunchIdempotency.agentId(debug, "nonce")), "Auto").getOrThrow()

        assertThat(api.createRequests).isEmpty()
        assertThat(start.requests.map { it.mode }).containsExactly(AgentMode.ASK, AgentMode.DEBUG).inOrder()
        assertThat(start.requests.map { it.text }).containsExactly(request.prompt, request.prompt)

        // Plan and Agent are the documented create's `mode` still.
        val plan = request.copy(planMode = true)
        agents.launch(plan.copy(agentId = LaunchIdempotency.agentId(plan, "nonce")), "Auto").getOrThrow()
        assertThat(api.createRequests.single().mode).isEqualTo("plan")
        assertThat(start.requests).hasSize(2)
    }

    @Test
    fun `an Ask or Debug chat is refused before anything is sent without the account's modes, or on a pool`() = runBlocking<Unit> {
        val start = FakeStart()
        val ask = request.copy(accountMode = AgentMode.ASK)
        val id = LaunchIdempotency.agentId(ask, "nonce")

        val off = withAccountStart(start, FakeUploads(), capabilities = Capabilities.DOCUMENTED).launch(ask.copy(agentId = id), "Auto")
        assertThat(off.exceptionOrNull()).hasMessageThat().isEqualTo(AgentRepository.modeNeedsExtended(AgentMode.ASK))

        val pool = withAccountStart(start, FakeUploads()).launch(ask.copy(agentId = id, env = DeviceTarget.pool("team-pool")), "Auto")
        assertThat(pool.exceptionOrNull()).hasMessageThat().isEqualTo(AgentRepository.modeNeedsCloud(AgentMode.ASK))

        assertThat(start.requests).isEmpty()
        assertThat(api.createRequests).isEmpty()
    }

    @Test
    fun `a retry after a lost reply adopts the agent the first attempt created`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        // The request reaches the server, but the reply never makes it back — and the server only gets round to
        // creating the agent after the moments the launch spends looking for it (see the next test for the other case).
        api.failNextCreate = SocketTimeoutException("timeout")
        val first = agents.launch(request.copy(agentId = id), "Auto")
        val unanswered = first.exceptionOrNull()
        assertThat(unanswered).isInstanceOf(LaunchUnansweredException::class.java)
        assertThat(unanswered!!.cause).isInstanceOf(SocketTimeoutException::class.java)
        // What the composer shows: not a bare timeout, but that the chat is nowhere and a retry is safe.
        assertThat(unanswered.userMessage()).contains("no chat appeared on your account")
        assertThat(unanswered.userMessage()).contains("Send it again")
        // The chat was looked for by id every time, and was not there.
        assertThat(api.getAgentCalls).isEqualTo(AgentRepository.LOST_REPLY_PROBES)
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
    fun `a launch whose reply was lost adopts the chat the server created meanwhile, without a retry`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        // The server acted on the request and then went quiet: the reply is lost to a read timeout, and the agent
        // exists under the id the request carried by the time the launch looks for it.
        api.failNextCreate = SocketTimeoutException("timeout")
        api.addRunningAgent(id, "Sync merge and chat state", "run-server-1")

        val (adopted, run) = agents.launch(request.copy(agentId = id), "Auto").getOrThrow()

        assertThat(api.createRequests).hasSize(1)
        assertThat(api.getAgentCalls).isEqualTo(1)
        assertThat(adopted.id).isEqualTo(id)
        assertThat(adopted.name).isEqualTo("Sync merge and chat state")
        assertThat(adopted.latestRunId).isEqualTo("run-server-1")
        assertThat(run?.id).isEqualTo("run-server-1")
        assertThat(adopted.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(adopted.modelDisplayName).isEqualTo("Auto")
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)
        assertThat(prefs.localAgentState.first().launchedHereIds).contains(id)
    }

    @Test
    fun `the search for a lost reply stops the moment the server stops answering reads too`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        // The reply is lost and the reads by id get no answer either: the server is out of reach, and asking four
        // more times would only hold the composer up for nothing.
        api.failNextCreate = SocketTimeoutException("timeout")
        api.failGetAgent = SocketTimeoutException("timeout")

        val failed = agents.launch(request.copy(agentId = id), "Auto")

        assertThat(failed.exceptionOrNull()).isInstanceOf(LaunchUnansweredException::class.java)
        assertThat(api.getAgentCalls).isEqualTo(1)
        assertThat(agents.state.value.agents).isEmpty()
    }

    @Test
    fun `an answer the server gave is never looked up again, nor waited on`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        // A refusal with the API's body — here the machine path's, as the server words it — fails the launch at once.
        api.failNextCreate = FakeCursorApi.httpError(400, "repository_required", "Repository is required. Either provide a repository URL in the repos[0].url field, or configure a default repository at https://cursor.com/settings.")

        val refused = agents.launch(request.copy(agentId = id), "Auto")

        assertThat(refused.exceptionOrNull()!!.toCursorError()!!.code).isEqualTo("repository_required")
        assertThat(refused.exceptionOrNull()!!.userMessage()).isEqualTo("Repository is required. Either provide a repository URL in the repos[0].url field, or configure a default repository at https://cursor.com/settings.")
        assertThat(api.getAgentCalls).isEqualTo(0)
        // Being offline is not a lost reply either: nothing went out, so nothing is looked for.
        api.failNextCreate = java.net.UnknownHostException("api.cursor.com")
        val offline = agents.launch(request.copy(agentId = id), "Auto")
        assertThat(offline.exceptionOrNull()).isInstanceOf(java.net.UnknownHostException::class.java)
        assertThat(api.getAgentCalls).isEqualTo(0)
        assertThat(agents.state.value.agents).isEmpty()
    }

    /** The `send:` block's launch line: which request the launch went out as, what it named, and what came of it. */
    @Test
    fun `the launch's decision and outcome are kept for the diagnostics`() = runBlocking<Unit> {
        // Accepted, with a repository at a branch.
        val accepted = LaunchIdempotency.agentId(request, "a")
        agents.launch(request.copy(agentId = accepted), "Auto").getOrThrow()
        val line = agents.launchDiagnostics(accepted)!!
        assertThat(line.via).isEqualTo("v1")
        assertThat(line.target).isEqualTo("repo(ref)")
        assertThat(line.files).isEqualTo(0)
        assertThat(line.outcome).isEqualTo("accepted run=${com.cursorforandroid.domain.ProjectDiagnostics.tail(api.agents.getValue(accepted).latestRunId!!)}")

        // Refused with the server's code; the words kept, redacted.
        val refused = LaunchIdempotency.agentId(request.copy(repoUrl = null, ref = null), "b")
        api.failNextCreate = FakeCursorApi.httpError(400, "repository_required", "Repository is required. Configure a default at https://cursor.com/settings (agent bc-00000000-0000-0000-0000-000000000001).")
        agents.launch(request.copy(repoUrl = null, ref = null, agentId = refused), "Auto")
        val refusal = agents.launchDiagnostics(refused)!!
        assertThat(refusal.target).isEqualTo("no-repo(repos:[])")
        assertThat(refusal.outcome).isEqualTo("refused http=400 code=repository_required")
        assertThat(refusal.detail).isEqualTo("Repository is required. Configure a default at <url> (agent bc-…).")

        // Unanswered, and the chat nowhere.
        val unanswered = LaunchIdempotency.agentId(request, "c")
        api.failNextCreate = SocketTimeoutException("timeout")
        agents.launch(request.copy(agentId = unanswered), "Auto")
        assertThat(agents.launchDiagnostics(unanswered)!!.outcome).isEqualTo("unanswered (SocketTimeoutException)")

        // A pool with the repository, a repo-less pool, and a launch never made this process.
        val pool = LaunchIdempotency.agentId(request.copy(env = DeviceTarget.pool("gpu")), "d")
        agents.launch(request.copy(env = DeviceTarget.pool("gpu"), agentId = pool), "Auto").getOrThrow()
        assertThat(agents.launchDiagnostics(pool)!!.target).isEqualTo("repo(ref) on pool")
        val anyRepoPool = LaunchIdempotency.agentId(request.copy(env = DeviceTarget.pool("gpu"), repoUrl = null, ref = null), "e")
        agents.launch(request.copy(env = DeviceTarget.pool("gpu"), repoUrl = null, ref = null, agentId = anyRepoPool), "Auto").getOrThrow()
        assertThat(agents.launchDiagnostics(anyRepoPool)!!.target).isEqualTo("env(pool)")
        assertThat(agents.launchDiagnostics("bc-never")).isNull()
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
    fun `a refused launch reports what the server said, not the status line`() = runBlocking<Unit> {
        val id = LaunchIdempotency.agentId(request, "nonce")
        // A real HTTP failure, whose body Retrofit buffers once: the conflict check reads it before the composer does.
        api.failNextCreate = HttpException(
            Response.error<Unit>(
                403,
                """{"error":{"code":"usage_limit_exceeded","message":"You've used all of this month's agents."}}"""
                    .toResponseBody("application/json".toMediaType()),
            ),
        )

        val failed = agents.launch(request.copy(agentId = id), "Auto")

        val error = failed.exceptionOrNull()!!
        assertThat(error.toCursorError()!!.code).isEqualTo("usage_limit_exceeded")
        // What ChatLauncher hands back to the composer as FailedLaunch.reason.
        assertThat(error.userMessage()).isEqualTo("Your Cursor usage limit has been reached.")
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

    @Test
    fun `a chat started from scratch sends an empty repos list and no env, and still launches`() = runBlocking<Unit> {
        // Auto-PR left on from an earlier launch: there is no repository for a pull request, so it stays out too.
        val noRepo = request.copy(repoUrl = null, ref = null, autoCreatePr = true)
        val id = LaunchIdempotency.agentId(noRepo, "nonce")
        val provisional = agents.beginLaunch(noRepo.copy(agentId = id), "Auto")!!
        assertThat(provisional.repoUrl).isNull()
        assertThat(provisional.envType).isEqualTo(EnvType.CLOUD)
        assertThat(provisional.autoCreatePr).isFalse()

        val (agent, run) = agents.launch(noRepo.copy(agentId = id), "Auto").getOrThrow()

        val sent = api.createRequests.single()
        assertThat(sent.repos).isEmpty()
        assertThat(sent.env).isNull()
        assertThat(sent.autoCreatePR).isNull()
        assertThat(agent.id).isEqualTo(id)
        assertThat(agent.repoUrl).isNull()
        assertThat(agent.envType).isEqualTo(EnvType.CLOUD)
        assertThat(agent.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(run?.id).isEqualTo(agent.latestRunId)
        assertThat(agents.state.value.agents.map { it.id }).containsExactly(id)
    }

    @Test
    fun `a machine or pool pick is sent as env and stamped on the provisional row`() = runBlocking<Unit> {
        val machine = request.copy(env = DeviceTarget.machine("bennett#/home/bennett/projects/app"))
        val id = LaunchIdempotency.agentId(machine, "nonce")
        val provisional = agents.beginLaunch(machine.copy(agentId = id), "Auto")!!
        assertThat(provisional.envType).isEqualTo(EnvType.MACHINE)
        assertThat(provisional.envName).isEqualTo("bennett")

        agents.launch(machine.copy(agentId = id), "Auto").getOrThrow()
        assertThat(api.createRequests.single().env).isEqualTo(AgentEnvDto(type = "machine", name = "bennett"))

        val pool = request.copy(env = DeviceTarget.pool("gpu"), agentId = LaunchIdempotency.agentId(request.copy(env = DeviceTarget.pool("gpu")), "pool"))
        agents.launch(pool, null).getOrThrow()
        assertThat(api.createRequests.last().env).isEqualTo(AgentEnvDto(type = "pool", name = "gpu"))
    }
}
