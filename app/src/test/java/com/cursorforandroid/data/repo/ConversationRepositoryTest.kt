package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolActivity
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    private lateinit var traces: TraceCache

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
        traces = TraceCache(disk.child("traces"))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    /** Prefetching is off unless a test is about it, so request counters only reflect the explicit open. */
    private fun repository(prefetchLimit: Int = 0) = ConversationRepository(
        session, agents, prefs, hub, attachments, cache, traces,
        isForeground = { true }, prefetchLimit = prefetchLimit, prefetchSpacingMs = 0, scope = scope,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun transcript(vararg turns: Pair<String, String>) = turns.mapIndexed { i, (type, text) -> V0ConversationMessageDto("msg-$i", type, text) }

    private fun tool(id: String, name: String, status: String, path: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { put("path", JsonPrimitive(path)) }),
    )

    /** The first half of a run as a live connection delivers it: a thought and one tool call, no reply yet. */
    private suspend fun streamFirstHalf(runId: String) {
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Thinking("Reading the code first."))
        streamer.emit(runId, tool("$runId-c1", "read_file", "completed", "app/src/Composer.kt"))
    }

    /** The whole run as its retained log replays it: what [streamFirstHalf] had, then an edit, the reply and the result. */
    private suspend fun retainWholeRun(runId: String, reply: String, durationMs: Long = 30_000) {
        streamFirstHalf(runId)
        streamer.emit(runId, tool("$runId-c2", "edit_file", "completed", "app/src/Composer.kt"))
        streamer.emit(runId, RunStreamEvent.Assistant(reply))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, reply, durationMs, null))
        streamer.emit(runId, RunStreamEvent.Done)
    }

    /** The server's view once [runId] finished with [reply]: the run record, the agent row and the transcript. */
    private fun finishOnServer(agentId: String, runId: String, reply: String, at: String, durationMs: Long = 30_000) {
        api.runs[runId] = api.runs.getValue(runId).copy(status = "FINISHED", result = reply, durationMs = durationMs, updatedAt = at)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "IDLE", updatedAt = at)
        api.transcripts[agentId] = api.transcripts[agentId].orEmpty() + V0ConversationMessageDto("$runId-a", "assistant_message", reply)
    }

    private fun ConversationState.types() = items.map { it::class.simpleName }

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
    fun `leaving mid-run and coming back after it finished shows the whole run, not the fragment the screen left with`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        streamFirstHalf("run-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is ToolActivity } }

        // The screen goes away; nobody else is streaming, so the hub lets the connection go after its grace period.
        conversations.detach("bc-1")
        delay(150)
        // The run finishes meanwhile. A fresh connection now replays the whole retained log.
        streamer.emit("run-1", RunStreamEvent.Done)
        streamer.reset("run-1")
        now += 60_000
        finishOnServer("bc-1", "run-1", "Shipped.", at = "2026-04-13T19:00:00.000Z")
        retainWholeRun("run-1", "Shipped.")

        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter }
        val state = conversations.state("bc-1").value
        assertThat(state.isLoading).isFalse()
        assertThat(state.isStreaming).isFalse()
        assertThat(state.runStatus).isEqualTo(RunStatus.FINISHED)
        // Not the two items the screen left with: the edit that followed, the final reply and the footer are all there.
        assertThat(state.types()).containsExactly("DateHeader", "UserMessage", "ThinkingBlock", "ToolActivity", "AssistantMessage", "RunFooter").inOrder()
        assertThat(state.items.filterIsInstance<ToolActivity>().single().calls.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
        assertThat(state.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat((state.items.last() as RunFooter).durationMs).isEqualTo(30_000L)
        // The whole story is kept for the next open, and the transcript's copy of the reply too.
        awaitUntil { traces.read("bc-1")["run-1"] != null }
        assertThat(traces.read("bc-1").getValue("run-1").items.last()).isInstanceOf(RunFooter::class.java)
        assertThat(cache.read("bc-1")!!.value.messages.map { it.text }).containsExactly("Ship it", "Shipped.").inOrder()
    }

    @Test
    fun `a run that finishes while only the notification monitor streams it is complete on the next open, from disk`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        // The live notification keeps the stream open after the screen leaves.
        val monitor = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        conversations.detach("bc-1")
        delay(150)
        retainWholeRun("run-1", "Shipped.")

        // The finish reaches the transcript and the disk without a screen attached.
        awaitUntil { traces.read("bc-1")["run-1"]?.items?.lastOrNull() is RunFooter }
        awaitUntil { cache.read("bc-1")?.value?.runs?.single()?.status == "FINISHED" }
        assertThat(cache.read("bc-1")!!.value.messages.map { it.type to it.text }).containsExactly("user_message" to "Ship it", "assistant_message" to "Shipped.").inOrder()
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()
        monitor.cancel()
        assertThat(streamer.connections.count { it == "run-1" }).isEqualTo(1)

        // A new process opens the chat much later: the log has expired, the network is slow, and the run still reads
        // whole from disk — thinking, both tool calls, the reply and the footer.
        finishOnServer("bc-1", "run-1", "Shipped.", at = "2026-04-13T19:00:00.000Z")
        streamer.reset("run-1")
        streamer.emit("run-1", RunStreamEvent.Error("stream_expired", "This run's live stream has expired."))
        streamer.emit("run-1", RunStreamEvent.Done)
        api.conversationGate = CompletableDeferred()
        val next = repository()
        next.attach("bc-1")
        awaitUntil { next.state("bc-1").value.items.lastOrNull() is RunFooter }
        val fromDisk = next.state("bc-1").value
        assertThat(fromDisk.types()).containsExactly("DateHeader", "UserMessage", "ThinkingBlock", "ToolActivity", "AssistantMessage", "RunFooter").inOrder()
        assertThat(fromDisk.items.filterIsInstance<ToolActivity>().single().calls.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
        assertThat(fromDisk.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat(fromDisk.runStatus).isEqualTo(RunStatus.FINISHED)

        // The network answer changes nothing about the trace, and the expired log is never asked for.
        api.conversationGate!!.complete(Unit)
        awaitUntil { !next.state("bc-1").value.isLoading }
        delay(150)
        assertThat(next.state("bc-1").value.types()).isEqualTo(fromDisk.types())
        assertThat(streamer.connections.count { it == "run-1" }).isEqualTo(1)
    }

    @Test
    fun `revalidating an open chat swaps a fragment left by a dropped connection for the replayed run`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !conversations.state("bc-1").value.isLoading && conversations.state("bc-1").value.isStreaming }
        // Right after the open there is nothing to revalidate: no second fetch.
        conversations.revalidate("bc-1")
        delay(100)
        assertThat(api.conversationCalls).isEqualTo(1)

        // The connection drops after the first tool call; the hub polls, and the run is finished by then.
        streamFirstHalf("run-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is ToolActivity } }
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED", result = "Shipped.", durationMs = 30_000)
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter }
        val polled = conversations.state("bc-1").value
        assertThat(polled.isStreaming).isFalse()
        // The outcome is shown right away — final reply included — even though the edit in between never arrived.
        assertThat(polled.types()).containsExactly("DateHeader", "UserMessage", "ThinkingBlock", "ToolActivity", "AssistantMessage", "RunFooter").inOrder()
        assertThat(polled.items.filterIsInstance<ToolActivity>().single().calls).hasSize(1)
        assertThat(polled.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        // Not the whole story, so not kept as one.
        delay(100)
        assertThat(traces.read("bc-1")).isEmpty()

        // Back to the foreground later: the history is loaded again and the finished run replayed from its log.
        streamer.reset("run-1")
        now += 60_000
        finishOnServer("bc-1", "run-1", "Shipped.", at = "2026-04-13T19:00:00.000Z")
        retainWholeRun("run-1", "Shipped.")
        conversations.revalidate("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.filterIsInstance<ToolActivity>().singleOrNull()?.calls?.size == 2 }
        val replayed = conversations.state("bc-1").value
        assertThat(replayed.types()).isEqualTo(polled.types())
        assertThat(replayed.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat(api.conversationCalls).isEqualTo(2)
        awaitUntil { traces.read("bc-1")["run-1"]?.items?.filterIsInstance<ToolActivity>()?.single()?.calls?.size == 2 }
    }

    @Test
    fun `traces stored on disk are not replayed again, even when the stream is still retained`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"), Triple("run-2", "Prompt 2", "Reply 2"))
        agents.refresh()
        retainWholeRun("run-1", "Reply 1")
        retainWholeRun("run-2", "Reply 2")
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.count { it is ToolActivity } == 2 }
        awaitUntil { traces.read("bc-1").keys == setOf("run-1", "run-2") }
        assertThat(streamer.connections).containsExactly("run-1", "run-2")
        // Payloads are not what the disk keeps; the summaries the cards render are.
        val saved = traces.read("bc-1").getValue("run-2").items.filterIsInstance<ToolActivity>().single().calls
        assertThat(saved.map { it.summary }).containsExactly("app/src/Composer.kt", "app/src/Composer.kt")
        assertThat(saved.all { it.args == null }).isTrue()

        // A later process opens the chat: both traces come from disk and no stream is opened for either run.
        val next = repository()
        next.attach("bc-1")
        awaitUntil { !next.state("bc-1").value.isLoading }
        delay(150)
        assertThat(next.state("bc-1").value.items.count { it is ToolActivity }).isEqualTo(2)
        assertThat(streamer.connections).containsExactly("run-1", "run-2")

        // Deleting the agent takes its traces with it.
        next.detach("bc-1")
        next.forget("bc-1")
        awaitUntil { traces.read("bc-1").isEmpty() }
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
