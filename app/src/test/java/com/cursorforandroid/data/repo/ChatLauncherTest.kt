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
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The launcher takes a new chat off the composer's hands: [ChatLauncher.launch] returns once the chat is on screen,
 * the request completes without its caller, and only what does not go through comes back — with the draft and the
 * nonce it went out under, and the reason unless it was stopped on purpose.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatLauncherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var agents: AgentRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var launcher: ChatLauncher
    private val failures = CopyOnWriteArrayList<FailedLaunch>()

    private val request = LaunchRequest(
        prompt = "Do the thing",
        repoUrl = "https://github.com/acme/app",
        ref = "main",
        modelId = "auto-smart",
        modelParams = emptyList(),
        autoCreatePr = false,
        planMode = false,
    ).let { it.copy(agentId = LaunchIdempotency.agentId(it, "nonce-1")) }
    private val id get() = request.agentId!!

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferencesStore(context)
        val streamer = FakeRunStreamer()
        val backend = CursorBackend(api, streamer, isDemo = false)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val attachments = AttachmentStore(context)
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10)
        val hub = LiveRunHub(session, agents, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
        conversations = ConversationRepository(session, agents, prefs, hub, attachments, isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope)
        launcher = ChatLauncher(conversations, scope)
        scope.launch { launcher.failures.collect { failures += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun prompts() = conversations.state(id).value.items.filterIsInstance<UserMessage>().map { it.text }

    @Test
    fun `launch returns once the chat is on screen, before the server has answered`() = runBlocking<Unit> {
        api.createGate = CompletableDeferred()

        launcher.launch(request, "Auto", "nonce-1")

        // Back in the caller's hands while the request is still in flight, the chat already on screen and in the list.
        assertThat(api.createGate!!.isCompleted).isFalse()
        assertThat(prompts()).containsExactly("Do the thing")
        assertThat(conversations.state(id).value.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(agents.agent(id)?.isRunning).isTrue()

        api.createGate!!.complete(Unit)
        awaitUntil { conversations.state(id).value.activeRunId != null }
        assertThat(conversations.state(id).value.activeRunId).isEqualTo(agents.agent(id)?.latestRunId)
        // Accepted: nothing for the composer to hear about.
        delay(100)
        assertThat(failures).isEmpty()
    }

    @Test
    fun `the request completes without the caller that handed it over`() = runBlocking<Unit> {
        api.createGate = CompletableDeferred()
        val composer = CoroutineScope(Job() + Dispatchers.Default)
        val handedOver = CompletableDeferred<Unit>()
        composer.launch {
            launcher.launch(request, "Auto", "nonce-1")
            handedOver.complete(Unit)
        }
        handedOver.await()

        // The composer that sent it is gone (its screen left, its view model cleared); the launch is not.
        composer.cancel()
        assertThat(agents.agent(id)?.isRunning).isTrue()
        api.createGate!!.complete(Unit)

        awaitUntil { agents.agent(id)?.latestRunId != null }
        assertThat(api.agents).containsKey(id)
        assertThat(prompts()).containsExactly("Do the thing")
        assertThat(conversations.state(id).value.runStatus).isEqualTo(RunStatus.CREATING)
        delay(100)
        assertThat(failures).isEmpty()
    }

    @Test
    fun `a launch the server rejects comes back with its draft, its nonce and the reason`() = runBlocking<Unit> {
        api.failNextCreate = CursorApiException(429, "rate_limited", "Slow down.")

        launcher.launch(request, "Auto", "nonce-1")

        awaitUntil { failures.isNotEmpty() }
        val failed = failures.single()
        assertThat(failed.agentId).isEqualTo(id)
        assertThat(failed.request).isEqualTo(request)
        assertThat(failed.nonce).isEqualTo("nonce-1")
        assertThat(failed.reason).isEqualTo("Rate limited by Cursor. Try again in a moment.")
        // Nothing of the chat is left behind.
        assertThat(prompts()).isEmpty()
        assertThat(agents.agent(id)).isNull()
    }

    @Test
    fun `a launch stopped from the chat comes back without a reason`() = runBlocking<Unit> {
        api.createGate = CompletableDeferred()
        launcher.launch(request, "Auto", "nonce-1")
        conversations.attach(id)

        assertThat(conversations.cancelActiveRun(id).isSuccess).isTrue()

        awaitUntil { failures.isNotEmpty() }
        val failed = failures.single()
        assertThat(failed.agentId).isEqualTo(id)
        assertThat(failed.reason).isNull()
        assertThat(failed.nonce).isEqualTo("nonce-1")
        assertThat(prompts()).isEmpty()
        assertThat(agents.agent(id)).isNull()
        api.createGate!!.complete(Unit)
    }
}
