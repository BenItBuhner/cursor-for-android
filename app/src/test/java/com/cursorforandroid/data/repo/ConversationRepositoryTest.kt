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
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
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

    /**
     * The common way to start a chat on the phone: launch it, watch the first seconds, leave, come back once it is
     * done. Whatever the earlier visit had followed so far — nothing at all, or a first thinking block — must not
     * be left standing in for the run once it has finished, or the chat reopens as just the prompt.
     */
    @Test
    fun `a chat left while its run streamed shows the reply and footer when reopened after the run finished`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Thinking("Looking around."))
        awaitUntil { conversations.state("bc-1").value.items.any { it is ThinkingBlock } }

        // The reader leaves while the run is still going; the hub lets go of the stream after its grace period...
        conversations.detach("bc-1")
        assertThat(conversations.state("bc-1").value.isStreaming).isFalse()
        delay(200)
        // ...and the run finishes unobserved: the server lists it as finished, with its reply in the transcript.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED", durationMs = 30_000, result = "Shipped.")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "IDLE")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it", "assistant_message" to "Shipped.")

        // Reopened, the reply and the footer come from the transcript. The run's log is asked for again — the story
        // followed so far was no replacement for it — but it cannot be read to its end yet.
        conversations.attach("bc-1")
        awaitUntil { !conversations.state("bc-1").value.isLoading && conversations.state("bc-1").value.items.lastOrNull() is RunFooter }
        val reopened = conversations.state("bc-1").value
        assertThat(reopened.items.map { it::class.simpleName }).containsExactly("DateHeader", "UserMessage", "AssistantMessage", "RunFooter").inOrder()
        assertThat(reopened.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat(reopened.isStreaming).isFalse()
        assertThat(reopened.runStatus).isEqualTo(RunStatus.FINISHED)
        awaitUntil { streamer.connections.count { it == "run-1" } == 2 }

        // Once the retained log reads to its end, the complete trace takes the reply's place. Reading it is not
        // news about the agent: the row keeps the timestamp the server gave it.
        streamer.emit("run-1", RunStreamEvent.Assistant("Shipped."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Shipped.", 30_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.any { it is ThinkingBlock } }
        val traced = conversations.state("bc-1").value
        assertThat(traced.items.map { it::class.simpleName }).containsExactly("DateHeader", "UserMessage", "ThinkingBlock", "AssistantMessage", "RunFooter").inOrder()
        assertThat(traced.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        delay(100)
        assertThat(agents.agent("bc-1")!!.updatedAtMillis).isEqualTo(parseIsoMillis(api.agents.getValue("bc-1").updatedAt))
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
    fun `the prefetch tells the sidebar how a turn ended, which the list alone cannot`() = runBlocking<Unit> {
        // The run errored; v1 lists the agent as merely idle and the legacy record says finished.
        api.addIdleAgent("bc-1", "Broke", "run-1")
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "ERROR", result = "Build failed", durationMs = 9_000)
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        repository(prefetchLimit = 1)

        agents.refresh()
        awaitUntil { agents.agent("bc-1")?.isError == true }
        val row = agents.agent("bc-1")!!
        assertThat(row.isRunning).isFalse()
        assertThat(row.summary).isEqualTo("Build failed")
        assertThat(row.durationMs).isEqualTo(9_000)
        awaitUntil { cache.read("bc-1") != null }

        // Later list fetches, with the legacy record still saying finished, do not undo it.
        agents.refresh()
        assertThat(agents.agent("bc-1")!!.isError).isTrue()
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
