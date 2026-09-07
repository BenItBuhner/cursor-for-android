package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Opening a chat: what renders before each network answer, what is written back, and what is warmed in advance. And
 * starting one: the prompt is on screen before the server has answered and stays there while the server's transcript
 * and run list catch up with it at their own pace.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConversationRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_800_000_000_000L
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var hub: LiveRunHub
    private lateinit var cache: ConversationCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10)
        hub = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, scope = scope)
        cache = ConversationCache(disk.child("conversations"))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    /** Prefetching is off unless a test is about it, so request counters only reflect the explicit open. */
    private fun repository(prefetchLimit: Int = 0) =
        ConversationRepository(session, agents, prefs, hub, attachments, cache, isForeground = { true }, prefetchLimit = prefetchLimit, prefetchSpacingMs = 0, scope = scope)

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun transcript(vararg turns: Pair<String, String>) = turns.mapIndexed { i, (type, text) -> V0ConversationMessageDto("msg-$i", type, text) }

    private fun prompts(conversations: ConversationRepository, agentId: String) = conversations.state(agentId).value.items.filterIsInstance<UserMessage>()

    /** A draft as the composer sends it, with the id it mints for the chat. */
    private val launchRequest = LaunchRequest(
        prompt = "Do the thing",
        repoUrl = "https://github.com/acme/app",
        ref = "main",
        modelId = "auto-smart",
        modelParams = emptyList(),
        autoCreatePr = false,
        planMode = false,
    ).let { it.copy(agentId = LaunchIdempotency.agentId(it, "nonce")) }

    @Test
    fun `a new chat opens on its prompt before the server answers and keeps it while the transcript lags behind`() = runBlocking<Unit> {
        val conversations = repository()
        val id = launchRequest.agentId!!
        api.createGate = CompletableDeferred()
        var opened = false

        val launch = async { conversations.launch(launchRequest, "Auto", onStaged = { opened = true }) }
        awaitUntil { prompts(conversations, id).isNotEmpty() }

        // On screen before the request has been answered: the prompt, "Starting…", and the row in the list.
        assertThat(opened).isTrue()
        val shown = conversations.state(id).value
        assertThat(prompts(conversations, id).single().text).isEqualTo("Do the thing")
        assertThat(shown.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(shown.isLoading).isFalse()
        assertThat(shown.error).isNull()
        assertThat(agents.agent(id)?.name).isEqualTo("Do the thing")
        assertThat(agents.agent(id)?.isRunning).isTrue()
        assertThat(api.createGate!!.isCompleted).isFalse()
        // The screen attaches to it without asking a server that has nothing yet.
        conversations.attach(id)
        delay(100)
        assertThat(api.conversationCalls).isEqualTo(0)
        assertThat(api.listRunsCalls).isEqualTo(0)

        api.createGate!!.complete(Unit)
        val launched = launch.await().getOrThrow()
        val runId = launched.run!!.id
        awaitUntil { conversations.state(id).value.isStreaming }
        val live = conversations.state(id).value
        assertThat(live.activeRunId).isEqualTo(runId)
        assertThat(prompts(conversations, id).single().text).isEqualTo("Do the thing")
        // Connected, but the run has not said anything yet: still starting, not working.
        assertThat(live.runStatus).isEqualTo(RunStatus.CREATING)
        assertThat(streamer.connections).contains(runId)
        assertThat(agents.agent(id)?.latestRunId).isEqualTo(runId)
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        awaitUntil { conversations.state(id).value.runStatus == RunStatus.RUNNING }
        assertThat(prompts(conversations, id).single().text).isEqualTo("Do the thing")

        // Reopened: the server lists the run but its transcript is still empty — the prompt must not vanish.
        conversations.detach(id)
        conversations.attach(id)
        awaitUntil { api.conversationCalls == 1 && !conversations.state(id).value.isLoading }
        assertThat(prompts(conversations, id).map { it.text }).containsExactly("Do the thing")
        assertThat(conversations.state(id).value.error).isNull()
        assertThat(conversations.state(id).value.activeRunId).isEqualTo(runId)

        // Once the transcript has caught up, the server's copy takes over without a duplicate.
        api.transcripts[id] = transcript("user_message" to "Do the thing")
        conversations.reload(id)
        awaitUntil { api.conversationCalls == 2 && prompts(conversations, id).singleOrNull()?.id == "msg-0" }
        assertThat(prompts(conversations, id).single().text).isEqualTo("Do the thing")
    }

    @Test
    fun `a chat launched just before the app was killed keeps its prompt through a lagging transcript on the next start`() = runBlocking<Unit> {
        val id = launchRequest.agentId!!
        val launched = repository().launch(launchRequest, "Auto").getOrThrow()
        awaitUntil { cache.read(id)?.value?.local?.map { it.message.text } == listOf("Do the thing") }
        // Written as what it is — a prompt the server has not reported yet, with the run it answered with — not as
        // the server's transcript, which is still empty.
        val saved = cache.read(id)!!.value
        assertThat(saved.messages).isEmpty()
        assertThat(saved.runs).isEmpty()
        assertThat(saved.local.single().run.id).isEqualTo(launched.run!!.id)

        // A new process: the disk copy renders first, then the server answers with the run and an empty transcript.
        api.conversationGate = CompletableDeferred()
        val fresh = repository()
        fresh.attach(id)
        awaitUntil { prompts(fresh, id).isNotEmpty() }
        assertThat(fresh.state(id).value.runStatus).isEqualTo(RunStatus.CREATING)
        api.conversationGate!!.complete(Unit)
        awaitUntil { api.conversationCalls == 1 && !fresh.state(id).value.isLoading }
        assertThat(prompts(fresh, id).map { it.text }).containsExactly("Do the thing")
        assertThat(fresh.state(id).value.isStreaming).isTrue()
    }

    @Test
    fun `a launch the server rejects leaves neither a bubble nor a row behind`() = runBlocking<Unit> {
        val conversations = repository()
        val id = launchRequest.agentId!!
        api.failNextCreate = CursorApiException(429, "rate_limited", "Slow down.")

        val result = conversations.launch(launchRequest, "Auto")

        assertThat((result.exceptionOrNull() as CursorApiException).code).isEqualTo("rate_limited")
        assertThat(conversations.state(id).value.items).isEmpty()
        assertThat(agents.agent(id)).isNull()
        assertThat(api.agents).doesNotContainKey(id)
        // The same draft can be sent again: the retry goes through cleanly.
        assertThat(conversations.launch(launchRequest, "Auto").isSuccess).isTrue()
        assertThat(prompts(conversations, id).single().text).isEqualTo("Do the thing")
    }

    @Test
    fun `stopping a chat before the server has answered gives the launch up`() = runBlocking<Unit> {
        val conversations = repository()
        val id = launchRequest.agentId!!
        api.createGate = CompletableDeferred()

        val launch = async { conversations.launch(launchRequest, "Auto") }
        awaitUntil { prompts(conversations, id).isNotEmpty() }
        conversations.attach(id)
        assertThat(conversations.cancelActiveRun(id).isSuccess).isTrue()

        val result = launch.await()
        assertThat(result.exceptionOrNull()).isInstanceOf(LaunchCancelledException::class.java)
        assertThat(conversations.state(id).value.items).isEmpty()
        assertThat(agents.agent(id)).isNull()
        assertThat(api.agents).doesNotContainKey(id)
        // Nothing left in flight to stop.
        assertThat(conversations.cancelLaunch(id)).isFalse()
        api.createGate!!.complete(Unit)
    }

    @Test
    fun `a follow-up's prompt stays on screen while the run list leads the transcript`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Add a README", "Done."))
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !conversations.state("bc-1").value.isLoading && prompts(conversations, "bc-1").size == 1 }
        val before = api.transcripts.getValue("bc-1")

        conversations.sendFollowUp("bc-1", "Add troubleshooting").getOrThrow()
        // The server lists the new run, but its transcript has not caught up with the prompt.
        api.transcripts["bc-1"] = before
        conversations.reload("bc-1")
        awaitUntil { api.conversationCalls == 2 && !conversations.state("bc-1").value.isLoading }
        assertThat(prompts(conversations, "bc-1").map { it.text }).containsExactly("Add a README", "Add troubleshooting").inOrder()

        // Then it does, and the server's copy takes over.
        api.transcripts["bc-1"] = before + V0ConversationMessageDto("srv-u2", "user_message", "Add troubleshooting")
        conversations.reload("bc-1")
        awaitUntil { api.conversationCalls == 3 && prompts(conversations, "bc-1").lastOrNull()?.id == "srv-u2" }
        assertThat(prompts(conversations, "bc-1").map { it.text }).containsExactly("Add a README", "Add troubleshooting").inOrder()
    }

    @Test
    fun `a transcript saved earlier renders before the network and is then revalidated`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1", result = "Second reply")
        api.transcripts["bc-1"] = transcript("user_message" to "Do the thing", "assistant_message" to "First reply", "user_message" to "Again", "assistant_message" to "Second reply")
        agents.refresh()
        cache.write(
            CachedConversation(
                agentId = "bc-1",
                messages = transcript("user_message" to "Do the thing", "assistant_message" to "First reply"),
                runs = listOf(RunDto("run-0", "bc-1", "FINISHED", "2026-04-13T18:00:00.000Z", "2026-04-13T18:05:00.000Z", durationMs = 5_000)),
                agentUpdatedAtMillis = 1L,
            ),
        )
        api.conversationGate = CompletableDeferred()
        val conversations = repository()

        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is UserMessage } }
        val fromDisk = conversations.state("bc-1").value
        assertThat(fromDisk.items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("First reply")
        assertThat(fromDisk.items.last()).isInstanceOf(RunFooter::class.java)
        assertThat(fromDisk.runStatus).isEqualTo(RunStatus.FINISHED)

        api.conversationGate!!.complete(Unit)
        awaitUntil { conversations.state("bc-1").value.items.filterIsInstance<AssistantMessage>().size == 2 }
        val fresh = conversations.state("bc-1").value
        assertThat(fresh.isLoading).isFalse()
        assertThat(fresh.error).isNull()
        assertThat(fresh.activeRunId).isEqualTo("run-1")
        awaitUntil { cache.read("bc-1")?.value?.messages?.size == 4 }
        assertThat(cache.read("bc-1")!!.value.runs.map { it.id }).containsExactly("run-1")
    }

    @Test
    fun `the transcript never waits for the agent detail, which reuses the run already listed`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Hi", "assistant_message" to "Done.")
        agents.refresh()
        api.getAgentGate = CompletableDeferred()
        val conversations = repository()

        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.isNotEmpty() && !conversations.state("bc-1").value.isLoading }
        assertThat(api.getAgentCalls).isEqualTo(1)
        assertThat(api.getAgentGate!!.isCompleted).isFalse()

        api.getAgentGate!!.complete(Unit)
        delay(100)
        // The runs list already carried the latest run: no second round-trip for it.
        assertThat(api.getRunCalls).isEqualTo(0)
        assertThat(api.listRunsCalls).isEqualTo(1)
    }

    @Test
    fun `a run that finishes while watched is written back so the next open renders it from disk`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }

        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Assistant("Shipped."))
        now += 30_000
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Shipped.", 30_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter }

        awaitUntil { cache.read("bc-1")?.value?.runs?.singleOrNull()?.status == "FINISHED" }
        val saved = cache.read("bc-1")!!.value
        assertThat(saved.messages.map { it.type to it.text }).containsExactly("user_message" to "Ship it", "assistant_message" to "Shipped.").inOrder()
        assertThat(saved.runs.single().durationMs).isEqualTo(30_000)
        assertThat(saved.runs.single().result).isEqualTo("Shipped.")

        // A fresh repository (a new process) shows exactly that without the network.
        api.conversationGate = CompletableDeferred()
        val next = repository()
        next.attach("bc-1")
        awaitUntil { next.state("bc-1").value.items.lastOrNull() is RunFooter }
        assertThat(next.state("bc-1").value.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        api.conversationGate!!.complete(Unit)
    }

    @Test
    fun `recent idle transcripts are prefetched after a list fetch and running ones are left alone`() = runBlocking<Unit> {
        api.addIdleAgent("bc-a", "Newest", "run-a", createdAt = "2026-04-14T10:00:00.000Z")
        api.addIdleAgent("bc-b", "Older", "run-b", createdAt = "2026-04-13T10:00:00.000Z")
        api.addIdleAgent("bc-c", "Oldest", "run-c", createdAt = "2026-04-12T10:00:00.000Z")
        api.addRunningAgent("bc-live", "Running", "run-live", createdAt = "2026-04-15T10:00:00.000Z")
        api.transcripts["bc-a"] = transcript("user_message" to "A", "assistant_message" to "Done.")
        api.transcripts["bc-b"] = transcript("user_message" to "B", "assistant_message" to "Done.")
        api.transcripts["bc-c"] = transcript("user_message" to "C", "assistant_message" to "Done.")
        repository(prefetchLimit = 2)

        agents.refresh()
        awaitUntil { cache.read("bc-a") != null && cache.read("bc-b") != null }
        delay(150)
        assertThat(cache.read("bc-c")).isNull()
        assertThat(cache.read("bc-live")).isNull()
        assertThat(api.conversationCalls).isEqualTo(2)

        // Another list fetch that changes nothing costs no transcript requests.
        agents.refresh()
        delay(150)
        assertThat(api.conversationCalls).isEqualTo(2)

        // A row that moved on is fetched again.
        api.agents["bc-a"] = api.agents.getValue("bc-a").copy(updatedAt = "2026-04-16T10:00:00.000Z")
        api.transcripts["bc-a"] = transcript("user_message" to "A", "assistant_message" to "Done.", "user_message" to "More", "assistant_message" to "Done again.")
        agents.refresh()
        awaitUntil { cache.read("bc-a")?.value?.messages?.size == 4 }
        assertThat(api.conversationCalls).isEqualTo(3)
    }

    @Test
    fun `forgetting an agent removes its transcript from disk`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Hi", "assistant_message" to "Done.")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { cache.read("bc-1") != null }
        conversations.detach("bc-1")
        conversations.forget("bc-1")
        awaitUntil { cache.read("bc-1") == null }
    }
}
