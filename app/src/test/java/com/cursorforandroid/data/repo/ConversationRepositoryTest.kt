package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
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
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** Opening a chat: what renders before each network answer, what is written back, and what is warmed in advance. */
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
        hub = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
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

    /** The retained log of a finished run has expired: the conversation keeps its text and asks nothing more. */
    private suspend fun expireStream(runId: String) {
        streamer.emit(runId, RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
        streamer.emit(runId, RunStreamEvent.Done)
    }

    private fun state(conversations: ConversationRepository) = conversations.state("bc-1").value

    @Test
    fun `a follow-up whose stream is refused while the machine wakes keeps its prompt, says so, and finishes in place`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"))
        agents.refresh()
        expireStream("run-1")
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && "run-1" in streamer.connections }

        // The agent was idle, so its machine has to wake up first: the stream endpoint refuses the first connections.
        val runId = "run-followup-1"
        streamer.dropNextConnection(runId)
        streamer.dropNextConnection(runId)
        assertThat(conversations.sendFollowUp("bc-1", "Prompt 2").isSuccess).isTrue()

        awaitUntil { state(conversations).isStreaming && state(conversations).isReconnecting }
        val waiting = state(conversations)
        assertThat(waiting.activeRunId).isEqualTo(runId)
        assertThat(waiting.runStatus?.isActive).isTrue()
        // The prompt is there and nothing has been said about the connection inside the transcript.
        assertThat(waiting.items.map { it::class.simpleName }.takeLast(2)).containsExactly("DateHeader", "UserMessage").inOrder()
        assertThat((waiting.items.last() as UserMessage).text).isEqualTo("Prompt 2")
        assertThat(waiting.items.none { it is NoticeCard }).isTrue()

        // Third time lucky: the trace arrives and the reconnecting state clears.
        awaitUntil { streamer.connections.count { it == runId } == 3 }
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Assistant("Reply 2"))
        awaitUntil { !state(conversations).isReconnecting && state(conversations).items.last() is AssistantMessage }
        assertThat(state(conversations).isStreaming).isTrue()

        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply 2", 5_000, null))
        streamer.emit(runId, RunStreamEvent.Done)
        awaitUntil { state(conversations).items.lastOrNull() is RunFooter && !state(conversations).isStreaming }
        val done = state(conversations)
        assertThat(done.isReconnecting).isFalse()
        assertThat(done.items.none { it is NoticeCard }).isTrue()
        assertThat(done.items.map { it::class.simpleName }.takeLast(4)).containsExactly("DateHeader", "UserMessage", "AssistantMessage", "RunFooter").inOrder()
        assertThat((done.items.last() as RunFooter).runId).isEqualTo(runId)
    }

    @Test
    fun `reloading while the run list lags a follow-up keeps following it and shows the prompt once`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"))
        agents.refresh()
        expireStream("run-1")
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && "run-1" in streamer.connections }

        // The transcript picks the prompt up at once (the fake appends it on createRun); the run list has not caught up.
        val runId = "run-followup-1"
        api.runsHiddenFromList += runId
        assertThat(conversations.sendFollowUp("bc-1", "Prompt 2").isSuccess).isTrue()
        awaitUntil { state(conversations).isStreaming }

        var listCalls = api.listRunsCalls
        conversations.reload("bc-1")
        awaitUntil { api.listRunsCalls > listCalls && !state(conversations).isLoading && state(conversations).isStreaming }
        val reloaded = state(conversations)
        // The previous run did not become the active one again; the follow-up is still what the screen follows.
        assertThat(reloaded.activeRunId).isEqualTo(runId)
        assertThat(reloaded.runStatus?.isActive).isTrue()
        // The prompt shows once, with its timestamp, although both the transcript and the local copy have it.
        assertThat(reloaded.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly("Prompt 1", "Prompt 2").inOrder()
        assertThat(reloaded.items.map { it::class.simpleName }.takeLast(2)).containsExactly("DateHeader", "UserMessage").inOrder()
        assertThat(reloaded.items.map { it.id }).containsNoDuplicates()

        // The run's events still land after the reload.
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Assistant("Reply 2"))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply 2", 5_000, null))
        streamer.emit(runId, RunStreamEvent.Done)
        awaitUntil { state(conversations).items.lastOrNull() is RunFooter && !state(conversations).isStreaming }
        val finished = state(conversations)
        assertThat(finished.items.map { it::class.simpleName }).containsExactly(
            "DateHeader", "UserMessage", "AssistantMessage", "RunFooter",
            "DateHeader", "UserMessage", "AssistantMessage", "RunFooter",
        ).inOrder()

        // The list catches up: the server's copy of the turn takes over, and the picture does not change.
        api.runsHiddenFromList -= runId
        listCalls = api.listRunsCalls
        conversations.reload("bc-1")
        awaitUntil { api.listRunsCalls > listCalls && !state(conversations).isLoading && !state(conversations).isStreaming }
        val caughtUp = state(conversations)
        assertThat(caughtUp.items.map { it::class.simpleName }).isEqualTo(finished.items.map { it::class.simpleName })
        assertThat(caughtUp.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly("Prompt 1", "Prompt 2").inOrder()
        assertThat(caughtUp.items.map { it.id }).containsNoDuplicates()
        assertThat(caughtUp.activeRunId).isEqualTo(runId)
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
