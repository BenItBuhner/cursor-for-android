package com.cursorforandroid.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelRefDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.CachedTrace
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.HeldDispatcher
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private lateinit var traces: TraceCache
    private lateinit var traceDisk: JsonDiskCache
    private val traceWrites = AtomicInteger()

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
        // The cache stamps each write with the clock, so counting the stamps counts the writes to the trace file.
        traceWrites.set(0)
        traceDisk = JsonDiskCache(folder.newFolder("traces"), nowProvider = { traceWrites.incrementAndGet(); now }, dispatcher = Dispatchers.Unconfined)
        traces = TraceCache(traceDisk)
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
        awaitUntil { runId in streamer.connections }
        assertThat(conversations.state(id).value.runStatus).isEqualTo(RunStatus.CREATING)
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
        // The run is followed as soon as the run list answers; the transcript's answer ends the load. Wait for both.
        awaitUntil { api.conversationCalls == 1 && fresh.state(id).value.isStreaming && !fresh.state(id).value.isLoading }
        assertThat(fresh.state(id).value.isLoading).isFalse()
        assertThat(prompts(fresh, id).map { it.text }).containsExactly("Do the thing")
        assertThat(fresh.state(id).value.error).isNull()
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

    /**
     * Every frame [conversations] publishes for [agentId] from now on, subscribed before the caller's next line runs.
     * [assertNoEndedRunStreaming] is the rule #237/#241 pinned read the other way round: no frame shows a run's footer
     * while the stream on that run is still on — the record's footer and the stream's end arrive in one frame.
     * Main's CI at `adcd808` caught a frame that broke it, once, in the test below; a sampled `state.value` can miss it.
     */
    private inner class Frames(conversations: ConversationRepository, private val agentId: String) {
        /** Each frame with the agent row's word at the instant it was published: whether the row ran. */
        val frames = java.util.concurrent.CopyOnWriteArrayList<Pair<ConversationState, Boolean?>>()
        private val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { conversations.state(agentId).collect { frames += it to agents.agent(agentId)?.isRunning } }

        fun stop() = job.cancel()

        /**
         * No frame shows [runId]'s footer with the stream on it still on; and the first frame that shows the footer
         * finds the row no longer running — the row is patched before the frame that ends the stream (#237/#241),
         * which `LiveFinishFaultsTest` caught the merge-frame version of on the follow-up PR's first CI run.
         */
        fun assertNoEndedRunStreaming(runId: String) {
            var first = true
            frames.forEachIndexed { i, (f, rowRunning) ->
                val footer = f.items.any { it is RunFooter && it.runId == runId }
                assertWithMessage("frame $i shows $runId's footer with the stream still on: streaming=${f.isStreaming} status=${f.runStatus} items=${f.types()}").that(footer && f.isStreaming && f.activeRunId == runId).isFalse()
                if (footer && first) {
                    first = false
                    assertWithMessage("frame $i, the first with $runId's footer, finds the row still running").that(rowRunning).isFalse()
                }
            }
        }

        /**
         * Every frame that shows [runId]'s footer shows [reply] above it: the frame that first says the run is over
         * carries the reply it ended on. Main's CI on #281's head (`e24ef7f`) caught the footer a frame ahead of the
         * reply, once, in `revalidating an open chat swaps a fragment…`.
         */
        fun assertEndedRunWhole(runId: String, reply: String) {
            frames.forEachIndexed { i, (f, _) ->
                val footer = f.items.indexOfFirst { it is RunFooter && it.runId == runId }
                if (footer < 0) return@forEachIndexed
                assertWithMessage("frame $i shows $runId's footer without its reply: items=${f.types()}").that(f.items.take(footer).count { it is AssistantMessage && it.markdown == reply }).isEqualTo(1)
            }
        }
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
        // The detail read is launched beside the transcript, not before it: it may not have reached the API by the
        // time the items are on screen, and the point is that the transcript did not wait for it.
        awaitUntil { api.getAgentCalls == 1 }
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
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }

        // The reader leaves while the run is still going; the hub lets go of the stream after its grace period...
        conversations.detach("bc-1")
        assertThat(conversations.state("bc-1").value.isStreaming).isFalse()
        delay(200)
        // ...and the run finishes unobserved: the server lists it as finished, with its reply in the transcript.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED", durationMs = 30_000, result = "Shipped.")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "IDLE")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it", "assistant_message" to "Shipped.")

        // Reopened moments later, the chat stands as it was left and nothing is read again but the run it was
        // following: its stream is opened once more, and the transcript is not fetched (see REOPEN_FRESH_MS).
        val fetches = api.conversationCalls
        conversations.attach("bc-1")
        awaitUntil { streamer.connections.count { it == "run-1" } == 2 }
        awaitUntil { conversations.state("bc-1").value.let { it.isStreaming && it.items.any { item -> item is ActivityGroup } } }
        assertThat(api.conversationCalls).isEqualTo(fetches)
        assertThat(conversations.state("bc-1").value.items.map { it::class.simpleName }).containsExactly("UserMessage", "ActivityGroup").inOrder()

        // The stream replays the retained log to its end: the reply and the footer come from it, the run reads finished.
        streamer.emit("run-1", RunStreamEvent.Assistant("Shipped."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Shipped.", 30_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { !conversations.state("bc-1").value.isStreaming && conversations.state("bc-1").value.items.lastOrNull() is RunFooter }
        val traced = conversations.state("bc-1").value
        assertThat(traced.items.map { it::class.simpleName }).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(traced.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat(traced.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(api.conversationCalls).isEqualTo(fetches)

        // Left again and reopened after the fresh window, the chat is read again: the transcript's copy of the
        // reply stands with the trace, once, and the row keeps the timestamp the server gave it.
        conversations.detach("bc-1")
        now += 60_000
        conversations.attach("bc-1")
        awaitUntil { api.conversationCalls > fetches && !conversations.state("bc-1").value.isLoading }
        val reread = conversations.state("bc-1").value
        assertThat(reread.items.map { it::class.simpleName }).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(reread.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        delay(100)
        assertThat(agents.agent("bc-1")!!.updatedAtMillis).isEqualTo(parseIsoMillis(api.agents.getValue("bc-1").updatedAt))
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
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }

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
        // The transcript, the run page and the replayed log land in whichever order the scheduler has them, and
        // each is shown as it lands: with the transcript ahead of the replay the turn reads prompt, reply, footer
        // for a moment, its trace pending (the same footer this wait used to stop at — the flake in a full run).
        // Settled is when the one trace shown is on screen and nothing is pending.
        awaitUntil { conversations.state("bc-1").value.let { !it.isLoading && !it.isStreaming && it.traceStatus.pending == 0 && it.traceStatus.shown == 1 && it.items.lastOrNull() is RunFooter } }
        val state = conversations.state("bc-1").value
        assertThat(state.isStreaming).isFalse()
        assertThat(state.runStatus).isEqualTo(RunStatus.FINISHED)
        // Not the two items the screen left with: the edit that followed, the final reply and the footer are all there.
        assertThat(state.types()).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(state.items.filterIsInstance<ActivityGroup>().single().calls.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
        assertThat(state.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat((state.items.last() as RunFooter).durationMs).isEqualTo(30_000L)
        // The whole story is kept for the next open, and the transcript's copy of the reply too. The two writes are
        // the load's and the replay's, in either order.
        awaitUntil { traces.read("bc-1")["run-1"] != null }
        assertThat(traces.read("bc-1").getValue("run-1").items.last()).isInstanceOf(RunFooter::class.java)
        awaitUntil { cache.read("bc-1")?.value?.messages?.map { it.text } == listOf("Ship it", "Shipped.") }
    }

    /**
     * The chat's load ends by reading the agent's record and its latest run (`AgentRepository.loadDetail`), and its
     * run page is folded into the row when it lands. Either read can be in flight when the run finishes on its
     * stream — a phone's round trip is long, a turn about to end is short — and both read the run as `RUNNING`, the
     * record a moment behind the stream. Landing after the hub had settled the row, either put it back to running
     * for the finished run: the spinner back on a finished chat until the next refresh's verification, and a queued
     * message waiting its whole busy recheck on it. The hub now tells the list which run it saw end, and every run
     * record reaches a row through that memory (`AgentRepository.known`).
     */
    @Test
    fun `a record read that predates the run's finish does not put the row back to running`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        // Held from before the open: the load's run page and its record read are in flight across the finish.
        val runPage = CompletableDeferred<Unit>()
        val detail = CompletableDeferred<Unit>()
        api.runsGate = runPage
        api.getAgentGate = detail
        conversations.attach("bc-1")
        awaitUntil { api.listRunsCalls == 1 }
        // The run ends on its stream while the reads are out; the monitor's subscription is what follows it here.
        val monitor = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        awaitUntil { streamer.connections.contains("run-1") }
        now += 30_000
        retainWholeRun("run-1", "Shipped.")
        awaitUntil { agents.agent("bc-1")!!.runStatus == RunStatus.FINISHED }
        val settled = agents.agent("bc-1")!!
        assertThat(settled.durationMs).isEqualTo(30_000L)

        // The run page lands, RUNNING as it was read: the row stays finished, with what the finish gave it.
        runPage.complete(Unit)
        api.runsGate = null
        awaitUntil { !conversations.state("bc-1").value.isLoading }
        assertThat(agents.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()
        // Then the record read, the same run still RUNNING in it: the same.
        awaitUntil { api.getAgentCalls >= 1 }
        detail.complete(Unit)
        api.getAgentGate = null
        awaitUntil { agents.agent("bc-1")!!.lifecycle == com.cursorforandroid.domain.AgentLifecycle.ACTIVE }
        val row = agents.agent("bc-1")!!
        assertThat(row.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(row.isRunning).isFalse()
        assertThat(row.latestRunId).isEqualTo("run-1")
        assertThat(row.durationMs).isEqualTo(30_000L)
        assertThat(row.summary).isEqualTo("Shipped.")
        // The chat itself reads finished too, and whole.
        awaitUntil { conversations.state("bc-1").value.let { it.runStatus == RunStatus.FINISHED && it.items.lastOrNull() is RunFooter } }
        monitor.cancel()
    }

    @Test
    fun `a run that finishes while only the notification monitor streams it is complete on the next open, from disk`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        // The live notification keeps the stream open after the screen leaves: subscribed — its first snapshot in
        // hand — before the screen goes, so the hub's grace period is never what keeps the one connection alive.
        val subscribed = CompletableDeferred<Unit>()
        val monitor = scope.launch { hub.snapshots("bc-1", "run-1").collect { subscribed.complete(Unit) } }
        subscribed.await()
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
        // The disk copy renders in two steps — the transcript and its runs, then the traces from their own files —
        // so the wait is for the trace it asserts, not for the footer the first step already shows.
        awaitUntil { next.state("bc-1").value.let { s -> s.items.lastOrNull() is RunFooter && s.items.any { it is ActivityGroup } } }
        val fromDisk = next.state("bc-1").value
        assertThat(fromDisk.types()).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(fromDisk.items.filterIsInstance<ActivityGroup>().single().calls.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
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
        val frames = Frames(conversations, "bc-1")
        streamFirstHalf("run-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED", result = "Shipped.", durationMs = 30_000)
        streamer.emit("run-1", RunStreamEvent.Done)
        // The footer and the stream's end come in one frame, whichever of the hub's poll and a list refresh brings the
        // finished record first: waited for as a pair, and every frame between checked for the pair's halves apart.
        awaitUntil { conversations.state("bc-1").value.let { it.items.lastOrNull() is RunFooter && !it.isStreaming } }
        frames.stop()
        frames.assertNoEndedRunStreaming("run-1")
        // Whichever of the hub's two listeners publishes first, the footer's first frame has the reply above it.
        frames.assertEndedRunWhole("run-1", "Shipped.")
        val polled = conversations.state("bc-1").value
        assertThat(polled.isStreaming).isFalse()
        // The outcome is shown right away — final reply included — even though the edit in between never arrived.
        assertThat(polled.types()).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(polled.items.filterIsInstance<ActivityGroup>().single().calls).hasSize(1)
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
        awaitUntil { conversations.state("bc-1").value.items.filterIsInstance<ActivityGroup>().singleOrNull()?.calls?.size == 2 }
        val replayed = conversations.state("bc-1").value
        assertThat(replayed.types()).isEqualTo(polled.types())
        assertThat(replayed.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat(api.conversationCalls).isEqualTo(2)
        awaitUntil { traces.read("bc-1")["run-1"]?.items?.filterIsInstance<ActivityGroup>()?.single()?.calls?.size == 2 }
    }

    /**
     * The ordering main's CI hit once (`adcd808`, 1 of 2046): the run list reports the followed run finished before
     * its stream has said so — a revalidation landing while the connection is only dropped — and the merge drew the
     * record's footer under the run in one frame and turned the stream off in the next. Forced here: the record is
     * finished and merged by a revalidation while the stream is still open; the frame that first carries the footer
     * must be the frame the stream ends in, and the run's status must be the record's in that same frame.
     */
    @Test
    fun `a run list that reports the followed run finished before its stream does ends the follow in the frame that shows the footer`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !conversations.state("bc-1").value.isLoading && conversations.state("bc-1").value.isStreaming }
        streamFirstHalf("run-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }
        val frames = Frames(conversations, "bc-1")
        // The server has the run over; the stream has not said so (the connection is merely quiet).
        now += 60_000
        finishOnServer("bc-1", "run-1", "Shipped.", at = "2026-04-13T19:00:00.000Z")
        conversations.revalidate("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is RunFooter && it.runId == "run-1" } }
        frames.stop()
        frames.assertNoEndedRunStreaming("run-1")
        // The run list may land before the transcript (see the pin below): either way the footer comes with the reply.
        frames.assertEndedRunWhole("run-1", "Shipped.")
        val settled = conversations.state("bc-1").value
        assertThat(settled.isStreaming).isFalse()
        assertThat(settled.isReconnecting).isFalse()
        assertThat(settled.runStatus).isEqualTo(RunStatus.FINISHED)
        // The story the stream told stands under the run, with the record's footer and the transcript's reply.
        assertThat(settled.items.filterIsInstance<ActivityGroup>().single().calls).hasSize(1)
        assertThat(settled.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        // The stream's own late word changes nothing.
        streamer.emit("run-1", RunStreamEvent.Done)
        delay(200)
        assertThat(conversations.state("bc-1").value.isStreaming).isFalse()
        assertThat(conversations.state("bc-1").value.types()).isEqualTo(settled.types())
    }

    /**
     * The ordering main's CI hit once on #281's head (`e24ef7f`, shard 2/5) in `revalidating an open chat swaps a
     * fragment…`: the hub reports a run's end to two listeners at once — the follow's own collector and the
     * repository's finish listener — and the finish listener published first. Its frame marked the run's record over
     * under the story the stream had told before it broke: the footer under the tool call, the final reply the run
     * ended on drawn a frame later, above it. Forced here: the follow's collector is held while the finish lands, so
     * the finish listener's frame is the first to show the run over; it must carry the reply.
     */
    @Test
    fun `a finish the hub reports before the follow applies it shows the run's reply in the frame that first shows its footer`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val follow = HeldDispatcher()
        val conversations = ConversationRepository(
            session, agents, prefs, hub, attachments, cache, traces,
            isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
            entryDispatcher = follow.dispatcher,
        )
        try {
            conversations.attach("bc-1")
            awaitUntil { !conversations.state("bc-1").value.isLoading && conversations.state("bc-1").value.isStreaming }
            streamFirstHalf("run-1")
            awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }
            val frames = Frames(conversations, "bc-1")
            // The connection drops after the first tool call; the run is over by its record, and the follow is held.
            follow.hold = true
            api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED", result = "Shipped.", durationMs = 30_000)
            streamer.emit("run-1", RunStreamEvent.Done)
            awaitUntil { conversations.state("bc-1").value.items.any { it is RunFooter && it.runId == "run-1" } }
            assertWithMessage("the follow's collector was held while the finish landed").that(follow.heldCount).isAtLeast(1)
            val first = conversations.state("bc-1").value
            assertThat(first.types()).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
            assertThat(first.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
            assertThat(first.items.filterIsInstance<ActivityGroup>().single().calls).hasSize(1)
            assertThat(first.isStreaming).isFalse()
            assertThat(first.runStatus).isEqualTo(RunStatus.FINISHED)
            // The follow's own frame, landing after, is the same frame.
            follow.release()
            awaitUntil { follow.heldCount == 0 }
            delay(200)
            assertThat(conversations.state("bc-1").value.types()).isEqualTo(first.types())
            frames.stop()
            frames.assertEndedRunWhole("run-1", "Shipped.")
            frames.assertNoEndedRunStreaming("run-1")
        } finally {
            follow.close()
        }
    }

    /**
     * The same frame from a revalidation whose run list lands before its transcript (see [ConversationRepository]'s
     * `publishRunsFirst`): the page shows the followed run over and ends the follow, its story kept, under the
     * record's footer — while the reply is in the transcript still on its way (megabytes, for a long chat on a cell
     * connection). The run's record carries the reply it ended on; the frame shows it, and the transcript's copy takes
     * over without a second one.
     */
    @Test
    fun `a run list that lands before the transcript shows the followed run's reply from its record with the footer`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !conversations.state("bc-1").value.isLoading && conversations.state("bc-1").value.isStreaming }
        streamFirstHalf("run-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }
        val frames = Frames(conversations, "bc-1")
        now += 60_000
        finishOnServer("bc-1", "run-1", "Shipped.", at = "2026-04-13T19:00:00.000Z")
        api.conversationGate = CompletableDeferred()
        conversations.revalidate("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is RunFooter && it.runId == "run-1" } }
        val runsFirst = conversations.state("bc-1").value
        assertThat(runsFirst.isLoading).isTrue()
        assertThat(runsFirst.types()).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(runsFirst.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat(runsFirst.isStreaming).isFalse()
        api.conversationGate!!.complete(Unit)
        awaitUntil { !conversations.state("bc-1").value.isLoading }
        delay(150)
        frames.stop()
        frames.assertEndedRunWhole("run-1", "Shipped.")
        frames.assertNoEndedRunStreaming("run-1")
        assertThat(conversations.state("bc-1").value.types()).isEqualTo(runsFirst.types())
    }

    @Test
    fun `traces stored on disk are not replayed again, even when the stream is still retained`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"), Triple("run-2", "Prompt 2", "Reply 2"))
        agents.refresh()
        retainWholeRun("run-1", "Reply 1")
        retainWholeRun("run-2", "Reply 2")
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.count { it is ActivityGroup } == 2 }
        awaitUntil { traces.read("bc-1").keys == setOf("run-1", "run-2") }
        assertThat(streamer.connections).containsExactly("run-1", "run-2")
        // Payloads are not what the disk keeps; the summaries the lines render, and the paths behind them, are.
        val saved = traces.read("bc-1").getValue("run-2").items.filterIsInstance<ActivityGroup>().single().calls
        assertThat(saved.map { it.summary }).containsExactly("Composer.kt", "Composer.kt")
        assertThat(saved.map { it.detail }).containsExactly("app/src/Composer.kt", "app/src/Composer.kt")
        // An edit's row opens onto its path, so there is no output behind it to have been written out.
        assertThat(saved.all { it.output == null }).isTrue()

        // A later process opens the chat: both traces come from disk and no stream is opened for either run.
        val next = repository()
        next.attach("bc-1")
        awaitUntil { !next.state("bc-1").value.isLoading }
        delay(150)
        assertThat(next.state("bc-1").value.items.count { it is ActivityGroup }).isEqualTo(2)
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
        assertThat(waiting.items.last()).isInstanceOf(UserMessage::class.java)
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
        assertThat(done.items.map { it::class.simpleName }.takeLast(3)).containsExactly("UserMessage", "AssistantMessage", "RunFooter").inOrder()
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
        // The prompt shows once, although both the transcript and the local copy have it.
        assertThat(reloaded.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly("Prompt 1", "Prompt 2").inOrder()
        assertThat(reloaded.items.last()).isInstanceOf(UserMessage::class.java)
        assertThat(reloaded.items.map { it.id }).containsNoDuplicates()

        // The run's events still land after the reload.
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Assistant("Reply 2"))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply 2", 5_000, null))
        streamer.emit(runId, RunStreamEvent.Done)
        awaitUntil { state(conversations).items.lastOrNull() is RunFooter && !state(conversations).isStreaming }
        val finished = state(conversations)
        assertThat(finished.items.map { it::class.simpleName }).containsExactly(
            "UserMessage", "AssistantMessage", "RunFooter",
            "UserMessage", "AssistantMessage", "RunFooter",
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
    fun `a follow-up that switches the model carries it to the run and the row, and streams the run`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Hi", "assistant_message" to "Done.")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !conversations.state("bc-1").value.isLoading }

        val result = conversations.sendFollowUp(
            "bc-1", "Try Composer",
            planMode = false,
            modelId = "composer-2", modelParams = listOf(ModelParam("fast", "true")), modelDisplayName = "Composer 2 · Fast",
        )

        assertThat(result.isSuccess).isTrue()
        val body = api.runRequests.single()
        assertThat(body.prompt.text).isEqualTo("Try Composer")
        assertThat(body.model).isEqualTo(ModelRefDto("composer-2", listOf(ModelParamDto("fast", "true"))))
        assertThat(body.mode).isEqualTo("agent")
        val row = agents.agent("bc-1")!!
        assertThat(row.modelId).isEqualTo("composer-2")
        assertThat(row.modelDisplayName).isEqualTo("Composer 2 · Fast")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        assertThat(conversations.state("bc-1").value.items.filterIsInstance<UserMessage>().last().text).isEqualTo("Try Composer")
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
    fun `a run list that fails to load leaves the footers and the cached runs alone`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"), Triple("run-2", "Prompt 2", "Reply 2"))
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && state(conversations).items.count { it is RunFooter } == 2 }
        awaitUntil { cache.read("bc-1")?.value?.runs?.size == 2 }

        // The transcript answers, the run list times out: the turn footers and the run state must survive it.
        api.failListRuns = CursorApiException(503, "unavailable", "Try again later.")
        val before = api.conversationCalls
        conversations.reload("bc-1")
        awaitUntil { api.conversationCalls > before && !state(conversations).isLoading }
        delay(100)

        val degraded = state(conversations)
        assertThat(degraded.items.count { it is RunFooter }).isEqualTo(2)
        assertThat(degraded.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly("Prompt 1", "Prompt 2").inOrder()
        assertThat(degraded.activeRunId).isEqualTo("run-2")
        assertThat(degraded.runStatus).isEqualTo(RunStatus.FINISHED)
        // Said under the transcript in the server's words, with the way to ask again, rather than swallowed.
        assertThat(degraded.transcriptError).isEqualTo("Try again later.")
        // And the degraded shape is never the one written back.
        assertThat(cache.read("bc-1")!!.value.runs.map { it.id }).containsExactly("run-1", "run-2")

        // The next read that goes through clears the word.
        api.failListRuns = null
        conversations.reload("bc-1")
        awaitUntil { !state(conversations).isLoading && state(conversations).transcriptError == null }
    }

    @Test
    fun `a transcript that fails to load does not erase the cached one`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"))
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && prompts(conversations, "bc-1").size == 1 }
        awaitUntil { cache.read("bc-1")?.value?.messages?.size == 2 }

        api.failConversation = CursorApiException(500, "internal_error", "Server error.")
        val before = api.listRunsCalls
        conversations.reload("bc-1")
        awaitUntil { api.listRunsCalls > before && !state(conversations).isLoading }
        delay(100)

        val kept = state(conversations)
        assertThat(kept.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly("Prompt 1")
        assertThat(kept.items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("Reply 1")
        assertThat(kept.transcriptUnavailable).isFalse()
        assertThat(cache.read("bc-1")!!.value.messages.map { it.text }).containsExactly("Prompt 1", "Reply 1").inOrder()
    }

    /**
     * A long chat opens on its newest window, and the rest of its run records are paged in behind the first page so
     * every prompt pairs with its own run. A transcript that then fails to reload must not cut that list back down
     * to one page: the older turns would lose their footers, traces and images, on screen and on disk, and the ones
     * left would pair with the wrong prompts.
     */
    @Test
    fun `a transcript that fails does not cut a long chat's run list down to one page`() = runBlocking<Unit> {
        val turns = Array(60) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent("bc-1", "Agent", *turns)
        agents.refresh()
        turns.forEach { expireStream(it.first) }
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && state(conversations).items.count { it is RunFooter } == 10 }
        awaitUntil { cache.read("bc-1")?.value?.runs?.size == 60 }
        // The window is the newest ten turns, paired with their own runs; the fifty before it wait for a scroll-up.
        val opened = state(conversations)
        assertThat(opened.hasOlder).isTrue()
        assertThat(opened.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly(*(51..60).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(opened.items.filterIsInstance<RunFooter>().map { it.runId }).containsExactly(*(51..60).map { "run-$it" }.toTypedArray()).inOrder()
        assertThat(cache.read("bc-1")!!.value.runsComplete).isTrue()

        api.failConversation = CursorApiException(500, "internal_error", "Server error.")
        val before = api.listRunsCalls
        conversations.reload("bc-1")
        awaitUntil { api.listRunsCalls > before && !state(conversations).isLoading }
        delay(100)

        assertThat(state(conversations).items.count { it is RunFooter }).isEqualTo(10)
        assertThat(state(conversations).items.filterIsInstance<UserMessage>().map { it.text }).containsExactly(*(51..60).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(cache.read("bc-1")!!.value.runs).hasSize(60)
        assertThat(state(conversations).hasOlder).isTrue()
    }

    /** Scrolling up widens the window a page at a time until the first turn; each window pairs its own runs. */
    @Test
    fun `scrolling up pages the older turns in, a window at a time, down to the first`() = runBlocking<Unit> {
        val turns = Array(25) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent("bc-1", "Agent", *turns)
        agents.refresh()
        turns.forEach { expireStream(it.first) }
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && state(conversations).items.count { it is RunFooter } == 10 }
        awaitUntil { cache.read("bc-1")?.value?.runsComplete == true }

        conversations.loadOlder("bc-1")
        awaitUntil { state(conversations).items.count { it is RunFooter } == 20 && !state(conversations).isLoadingOlder }
        var shown = state(conversations)
        assertThat(shown.hasOlder).isTrue()
        assertThat(shown.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly(*(6..25).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(shown.items.filterIsInstance<RunFooter>().map { it.runId }).containsExactly(*(6..25).map { "run-$it" }.toTypedArray()).inOrder()
        // Each footer sits under its own turn: the run's duration is the turn's.
        assertThat(shown.items.filterIsInstance<RunFooter>().first().durationMs).isEqualTo(6 * 60_000L)

        conversations.loadOlder("bc-1")
        awaitUntil { state(conversations).items.count { it is RunFooter } == 25 && !state(conversations).isLoadingOlder }
        shown = state(conversations)
        assertThat(shown.hasOlder).isFalse()
        assertThat(shown.items.filterIsInstance<UserMessage>()).hasSize(25)
        // Asking again at the top changes nothing.
        conversations.loadOlder("bc-1")
        delay(100)
        assertThat(state(conversations).items.count { it is RunFooter }).isEqualTo(25)
        // The window the reader had open is what the next start renders from disk.
        awaitUntil { (cache.read("bc-1")?.value?.window ?: 0) >= 25 }
    }

    /**
     * A follow-up on a long chat whose run records are still being paged in: its prompt stands on its own until the
     * server reports it, and the window's older turns keep their own runs meanwhile — whether the transcript or the
     * run list catches up with the follow-up first.
     */
    @Test
    fun `a follow-up on a long chat pairs the window's turns with their own runs while the list is still paging`() = runBlocking<Unit> {
        val turns = Array(60) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        // Every turn before the follow-up the fake will file (it stamps those in the morning of the 14th).
        api.addFinishedAgent("bc-1", "Agent", *turns, firstRunAt = "2026-04-11T00:00:00.000Z")
        agents.refresh()
        turns.forEach { expireStream(it.first) }
        // The pages behind the first are held: the list stays incomplete for the whole of this.
        api.runsLaterPagesGate = CompletableDeferred()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && state(conversations).items.count { it is RunFooter } == 10 }

        // The run list has the follow-up's run before the transcript has its prompt (the fake appends the prompt on
        // createRun; take it back out to stage the lag).
        val runId = "run-followup-1"
        api.runsHiddenFromList += runId
        assertThat(conversations.sendFollowUp("bc-1", "Prompt 61").isSuccess).isTrue()
        api.transcripts["bc-1"] = api.transcripts.getValue("bc-1").filterNot { it.text == "Prompt 61" }
        awaitUntil { state(conversations).isStreaming }
        var shown = state(conversations)
        assertThat(shown.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly(*(52..60).map { "Prompt $it" }.toTypedArray(), "Prompt 61").inOrder()
        assertThat(shown.items.filterIsInstance<RunFooter>().map { it.runId }).containsExactly(*(52..60).map { "run-$it" }.toTypedArray()).inOrder()

        // The transcript catches up first: the prompt shows once, paired the same way.
        api.transcripts["bc-1"] = api.transcripts.getValue("bc-1") + V0ConversationMessageDto("srv-61", "user_message", "Prompt 61")
        var listCalls = api.listRunsCalls
        conversations.reload("bc-1")
        awaitUntil { api.listRunsCalls > listCalls && !state(conversations).isLoading }
        shown = state(conversations)
        assertThat(shown.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly(*(52..60).map { "Prompt $it" }.toTypedArray(), "Prompt 61").inOrder()
        assertThat(shown.items.filterIsInstance<RunFooter>().map { it.runId }).containsExactly(*(52..60).map { "run-$it" }.toTypedArray()).inOrder()
        assertThat(shown.activeRunId).isEqualTo(runId)

        // Then the run list: the server's copy of the turn takes over and nothing moves.
        api.runsHiddenFromList -= runId
        listCalls = api.listRunsCalls
        conversations.reload("bc-1")
        awaitUntil { api.listRunsCalls > listCalls && !state(conversations).isLoading }
        shown = state(conversations)
        assertThat(shown.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly(*(52..61).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(shown.items.filterIsInstance<RunFooter>().map { it.runId }).containsExactly(*(52..60).map { "run-$it" }.toTypedArray()).inOrder()
        assertThat(shown.items.map { it.id }).containsNoDuplicates()
        api.runsLaterPagesGate!!.complete(Unit)
    }

    /**
     * A restart renders the window that was open from disk — the older turns' run records included — and the
     * network only revalidates it; scrolling up afterwards needs no page the disk did not keep.
     */
    @Test
    fun `a restart reopens the chat on the window that was open, from disk`() = runBlocking<Unit> {
        val turns = Array(25) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent("bc-1", "Agent", *turns)
        agents.refresh()
        turns.forEach { expireStream(it.first) }
        val first = repository()
        first.attach("bc-1")
        awaitUntil { !state(first).isLoading && state(first).items.count { it is RunFooter } == 10 }
        awaitUntil { cache.read("bc-1")?.value?.runsComplete == true }
        first.loadOlder("bc-1")
        awaitUntil { state(first).items.count { it is RunFooter } == 20 && !state(first).isLoadingOlder }
        awaitUntil { cache.read("bc-1")?.value?.window == 20 }
        first.detach("bc-1")

        api.conversationGate = CompletableDeferred()
        val next = repository()
        next.attach("bc-1")
        awaitUntil { state(next).items.count { it is RunFooter } == 20 }
        val fromDisk = state(next)
        assertThat(fromDisk.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly(*(6..25).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(fromDisk.hasOlder).isTrue()
        // Rendered before the network answered.
        assertThat(api.conversationGate!!.isCompleted).isFalse()
        api.conversationGate!!.complete(Unit)
        awaitUntil { !state(next).isLoading }
        assertThat(state(next).items.count { it is RunFooter }).isEqualTo(20)
    }

    /**
     * A streamed event may only cost what the live run itself costs. The turns that have settled keep the very
     * items they had — so the derivation stays proportional to the run rather than to the whole chat, the list can
     * reuse the rows, and the reply keeps one id while it grows, which is what a markdown parse cache keys on.
     */
    @Test
    fun `a streamed event keeps the settled turns' items identical instead of rebuilding the timeline`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"), Triple("run-2", "Prompt 2", "Reply 2"))
        agents.refresh()
        expireStream("run-1")
        expireStream("run-2")
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && state(conversations).items.count { it is RunFooter } == 2 }

        val runId = "run-followup-1"
        assertThat(conversations.sendFollowUp("bc-1", "Prompt 3").isSuccess).isTrue()
        awaitUntil { state(conversations).isStreaming }
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Assistant("Reply"))
        awaitUntil { state(conversations).items.lastOrNull() is AssistantMessage }

        val opening = state(conversations).items
        val settled = opening.subList(0, opening.size - 1).toList()
        assertThat(settled.map { it::class.simpleName }).containsExactly(
            "UserMessage", "AssistantMessage", "RunFooter", "UserMessage", "AssistantMessage", "RunFooter", "UserMessage",
        ).inOrder()
        val replyIds = mutableSetOf((opening.last() as AssistantMessage).id)

        repeat(16) { n ->
            streamer.emit(runId, RunStreamEvent.Assistant(" $n"))
            awaitUntil { (state(conversations).items.lastOrNull() as? AssistantMessage)?.markdown?.endsWith(" $n") == true }
            val grown = state(conversations).items
            assertThat(grown).hasSize(settled.size + 1)
            settled.forEachIndexed { i, item -> assertThat(grown[i]).isSameInstanceAs(item) }
            replyIds += (grown.last() as AssistantMessage).id
        }

        assertThat(replyIds).hasSize(1)
        assertThat((state(conversations).items.last() as AssistantMessage).markdown)
            .isEqualTo("Reply" + (0..15).joinToString("") { " $it" })
    }

    /**
     * The transcript pairs its prompts with the runs by position, so a run list that stops at one page pairs the
     * older turns with the wrong run and leaves the newest ones without one at all.
     */
    @Test
    fun `a chat with more turns than one page of runs still pairs each turn with its own run`() = runBlocking<Unit> {
        api.addFinishedAgent(
            "bc-1", "Agent",
            Triple("run-1", "Prompt 1", "Reply 1"),
            Triple("run-2", "Prompt 2", "Reply 2"),
            Triple("run-3", "Prompt 3", "Reply 3"),
        )
        agents.refresh()
        listOf("run-1", "run-2", "run-3").forEach { expireStream(it) }
        api.pageSize = 2

        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { !state(conversations).isLoading && state(conversations).items.count { it is RunFooter } == 3 }

        val items = state(conversations).items
        assertThat(items.map { it::class.simpleName }).containsExactly(
            "UserMessage", "AssistantMessage", "RunFooter",
            "UserMessage", "AssistantMessage", "RunFooter",
            "UserMessage", "AssistantMessage", "RunFooter",
        ).inOrder()
        assertThat(items.filterIsInstance<RunFooter>().map { it.runId }).containsExactly("run-1", "run-2", "run-3").inOrder()
        assertThat(items.filterIsInstance<RunFooter>().map { it.durationMs }).containsExactly(60_000L, 120_000L, 180_000L).inOrder()
        // Two pages were enough to cover the three prompts; a third was not asked for.
        assertThat(api.listRunsCalls).isEqualTo(2)
    }

    /**
     * Each replayed trace is written the moment it is whole, to a file of its own: the agent's other runs are never
     * read back or serialised again for it, and a process that dies after the third replay keeps three traces.
     */
    @Test
    fun `opening a chat writes each replayed trace to its own file as it lands`() = runBlocking<Unit> {
        api.addFinishedAgent(
            "bc-1", "Agent",
            Triple("run-1", "Prompt 1", "Reply 1"),
            Triple("run-2", "Prompt 2", "Reply 2"),
            Triple("run-3", "Prompt 3", "Reply 3"),
        )
        agents.refresh()
        listOf("run-1", "run-2", "run-3").forEach { runId ->
            streamer.emit(runId, RunStreamEvent.Thinking("Looking at $runId."))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply", 1_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }

        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { traces.runIds("bc-1").size == 3 }
        delay(150)

        assertThat(traces.read("bc-1").keys).containsExactly("run-1", "run-2", "run-3")
        val files = folder.root.walkTopDown().filter { it.isFile && it.path.contains("/traces/") }.map { it.name }.toList()
        assertThat(files).containsExactly("run-1.json", "run-2.json", "run-3.json", "_index.json")
        // One write per run and one per index update: nothing was rewritten wholesale.
        assertThat(traceWrites.get()).isEqualTo(6)
    }

    /**
     * A settled trace is written at once, under the cache generation the pass started in — so a sign-out that lands
     * while the pass is still reading refuses the write, and the next account never sees this one's chat.
     */
    @Test
    fun `a trace pass a sign-out cancelled does not write back into the wiped cache`() = runBlocking<Unit> {
        api.addFinishedAgent(
            "bc-1", "Agent",
            Triple("run-1", "Prompt 1", "Reply 1"),
            Triple("run-2", "Prompt 2", "Reply 2"),
        )
        agents.refresh()

        val conversations = repository()
        conversations.attach("bc-1")
        // Both replays are under way and neither log has read to its end when the sign-out lands.
        awaitUntil { "run-1" in streamer.connections && "run-2" in streamer.connections }
        assertThat(traces.read("bc-1")).isEmpty()
        traceDisk.invalidate()
        traceDisk.clear()

        // run-1's log then reads to its end: the pass has a trace to write, under the generation it started in.
        streamer.emit("run-1", RunStreamEvent.Thinking("Looking at run-1."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Reply 1", 1_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }
        delay(200)
        assertThat(traces.read("bc-1")).isEmpty()

        conversations.resetAll()
        delay(200)
        assertThat(traces.read("bc-1")).isEmpty()
    }

    /**
     * The pass replaying a long chat's runs is not cut short by the run being followed finishing — that used to
     * replace it with a replay of the one run, and the runs it had not reached stayed text-only until the next fetch.
     */
    @Test
    fun `a run finishing while older runs are being replayed does not cut the replay short`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"), Triple("run-2", "Prompt 2", "Reply 2"))
        val startedAt = "2026-04-13T22:00:00.000Z"
        api.runs["run-3"] = RunDto(id = "run-3", agentId = "bc-1", status = "RUNNING", createdAt = startedAt, updatedAt = startedAt)
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "ACTIVE", latestRunId = "run-3", updatedAt = startedAt)
        api.transcripts["bc-1"] = api.transcripts.getValue("bc-1") + V0ConversationMessageDto("run-3-u", "user_message", "Prompt 3")
        agents.refresh()
        // The live connection breaks after its first event; the record then says the run is over, without a trace.
        streamer.dropNextConnection("run-3", afterEvents = 1)
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { state(conversations).isStreaming && "run-1" in streamer.connections && "run-2" in streamer.connections }
        api.runs["run-3"] = api.runs.getValue("run-3").copy(status = "FINISHED", result = "Reply 3", durationMs = 5_000)
        streamer.emit("run-3", RunStreamEvent.Status("run-3", RunStatus.RUNNING))
        awaitUntil { !state(conversations).isStreaming && state(conversations).runStatus == RunStatus.FINISHED }

        // The replays then read to their ends and both traces land: the finish asked for run-3's log and left theirs alone.
        listOf("run-1", "run-2").forEach { runId ->
            streamer.emit(runId, RunStreamEvent.Thinking("Looking at $runId."))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply", 1_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        awaitUntil { state(conversations).items.count { it is ActivityGroup } == 2 }
        awaitUntil { traces.runIds("bc-1").containsAll(listOf("run-1", "run-2")) }
        assertThat(streamer.connections.count { it == "run-1" }).isEqualTo(1)
        assertThat(streamer.connections.count { it == "run-2" }).isEqualTo(1)
    }

    @Test
    fun `a paused chat stops following and picks the run up again on resume`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        streamer.emit("run-1", RunStreamEvent.Assistant("Working"))
        awaitUntil { conversations.state("bc-1").value.items.any { it is AssistantMessage } }

        // The screen stopped: nothing publishes into it — the story the stream had told stands as it was (the frame
        // the reader comes back to is not blank), and what the run says meanwhile waits for the resume.
        conversations.pause("bc-1")
        awaitUntil { !conversations.state("bc-1").value.isStreaming }
        assertThat(conversations.state("bc-1").value.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Working")
        streamer.emit("run-1", RunStreamEvent.Assistant(" on it."))
        delay(100)
        assertThat(conversations.state("bc-1").value.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Working")

        // Back on screen well inside the revalidate window: the run is followed again without waiting for a fetch,
        // and what it said meanwhile joins the story kept — once, in the same message.
        val fetches = api.conversationCalls
        conversations.resume("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        assertThat(api.conversationCalls).isEqualTo(fetches)
        awaitUntil { conversations.state("bc-1").value.items.filterIsInstance<AssistantMessage>().any { it.markdown == "Working on it." } }
        assertThat(conversations.state("bc-1").value.items.filterIsInstance<AssistantMessage>().single().markdown)
            .isEqualTo("Working on it.")
    }

    /**
     * The screen can go away while the load is still waiting on the network. Nothing can see the chat by the time
     * that answer arrives, so the load must not be the thing that opens a stream or replays a log behind it.
     */
    @Test
    fun `a load that answers after the screen went away opens nothing until it is back`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"))
        val startedAt = "2026-04-13T20:00:00.000Z"
        api.runs["run-2"] = RunDto(id = "run-2", agentId = "bc-1", status = "RUNNING", createdAt = startedAt, updatedAt = startedAt)
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "ACTIVE", latestRunId = "run-2", updatedAt = startedAt)
        agents.refresh()
        // run-1's retained log reads to its end, so a replay pass would settle and write its trace.
        streamer.emit("run-1", RunStreamEvent.Thinking("Looking at run-1."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Reply 1", 1_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)

        val conversations = repository()
        // Both answers held: the runs render before the transcript, and would follow the run, while a screen can see them.
        api.conversationGate = CompletableDeferred()
        api.runsGate = CompletableDeferred()
        conversations.attach("bc-1")
        awaitUntil { api.conversationCalls == 1 }
        conversations.pause("bc-1")
        api.conversationGate!!.complete(Unit)
        api.runsGate!!.complete(Unit)

        // The inputs the load fetched are still worth having; the stream and the replay are not started for them.
        awaitUntil { conversations.state("bc-1").value.items.any { it is UserMessage } }
        delay(150)
        assertThat(conversations.state("bc-1").value.isStreaming).isFalse()
        assertThat(streamer.connections).doesNotContain("run-2")
        assertThat(traces.read("bc-1")).isEmpty()

        // Back on screen: the run is followed and the replay the pause turned away runs now.
        conversations.resume("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        awaitUntil { traces.read("bc-1").keys == setOf("run-1") }
    }

    @Test
    fun `a run whose status this build cannot read does not leave the chat working`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        // Every state the screen would have seen from here, not the one a poll happens to catch: the run's end must
        // read as one step — the stream over and the turn over in the same frame — with nothing active after it.
        // The hub used to publish the finished snapshot before it patched the row idle; the collector, on its own
        // thread, read the row still running and kept the chat at RUNNING for the six seconds its next-run looks
        // took (a loaded CI runner: 35156980926). The hub now patches the row first, so no frame can show that.
        val frames = CopyOnWriteArrayList<Pair<Boolean, RunStatus?>>()
        val recorder = scope.launch { conversations.state("bc-1").collect { frames += it.isStreaming to it.runStatus } }

        // The record starts answering with a status this build has never heard of, and the stream is gone for good.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "HIBERNATING")
        streamer.emit("run-1", RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { frames.any { (streaming, _) -> !streaming } }
        val ended = frames.indexOfFirst { (streaming, _) -> !streaming }
        assertWithMessage("frames from the stream's end: ${frames.drop(ended)}").that(frames.drop(ended).none { (_, status) -> status?.isActive == true }).isTrue()
        assertThat(frames[ended].second).isEqualTo(RunStatus.UNKNOWN)
        // The row agrees, and the list remembers how the run ended.
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()
        assertThat(agents.endedStatus("bc-1", "run-1")).isEqualTo(RunStatus.UNKNOWN)

        // A reopen reads the same record and still shows the turn as over, with a footer closing it.
        now += 60_000
        conversations.revalidate("bc-1")
        // The footer and the end of streaming are published a beat apart; the wait covers both.
        awaitUntil { conversations.state("bc-1").value.let { it.items.lastOrNull() is RunFooter && !it.isStreaming } }
        val reopened = conversations.state("bc-1").value
        assertThat(reopened.isStreaming).isFalse()
        assertThat((reopened.items.last() as RunFooter).status).isEqualTo(RunStatus.UNKNOWN)
        recorder.cancel()
        assertThat(frames.drop(ended).none { (_, status) -> status?.isActive == true }).isTrue()
    }

    /**
     * The other way a load can meet a run that ended: the stream is still being followed — the connection dropped
     * and the hub is between attempts, or the connection is open and silent — and the run page says the run is over.
     * The load keeps the stream's word for the status ("a stream already open on the latest run keeps its word"),
     * then stops following because the server says the run is over, and stopping only turned the stream off: the
     * frame read `isStreaming=false, runStatus=RUNNING`, and with the collector gone nothing corrected it until the
     * next load (a foreground return five seconds on at the soonest, or a reopen) — the Stop button and "Working…"
     * over the reply and footer. On the phone: a network switch mid-turn, the turn ending while the connection is
     * down, the app brought back. The frame that stops following now settles the status with it.
     */
    @Test
    fun `a load that finds the followed run over by the server's account ends the turn in the frame that stops the stream`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        // The first connection drops after the first half; the hub reads the record (still running) and comes back
        // with a resumed connection that has nothing more to say.
        streamer.dropNextConnection("run-1", afterEvents = 3)
        streamFirstHalf("run-1")
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.let { !it.isLoading && it.isStreaming && it.items.any { item -> item is ActivityGroup } } }
        awaitUntil { streamer.connections.count { it == "run-1" } == 2 }
        assertThat(conversations.state("bc-1").value.runStatus).isEqualTo(RunStatus.RUNNING)
        val frames = CopyOnWriteArrayList<Pair<Boolean, RunStatus?>>()
        val recorder = scope.launch { conversations.state("bc-1").collect { frames += it.isStreaming to it.runStatus } }

        // The run ends on the server while the resumed connection stays silent; the app comes back to the foreground.
        finishOnServer("bc-1", "run-1", "Shipped.", at = "2026-04-13T19:00:00.000Z")
        now += 60_000
        conversations.revalidate("bc-1")
        awaitUntil { conversations.state("bc-1").value.let { !it.isLoading && !it.isStreaming } }
        awaitUntil { frames.any { (streaming, _) -> !streaming } }
        val ended = frames.indexOfFirst { (streaming, _) -> !streaming }
        assertWithMessage("frames: $frames").that(frames[ended].second).isEqualTo(RunStatus.FINISHED)
        assertThat(frames.drop(ended).none { (_, status) -> status?.isActive == true }).isTrue()
        val settled = conversations.state("bc-1").value
        assertThat(settled.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(settled.items.lastOrNull()).isInstanceOf(RunFooter::class.java)
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()
        // And it stays so: the released stream's late word, if any, cannot bring the turn back.
        delay(300)
        recorder.cancel()
        assertThat(frames.drop(ended).none { (_, status) -> status?.isActive == true }).isTrue()
    }

    /** A settings store whose writes wait for [gate] when one is set: holds a caller inside `prefs.markRead`. */
    private class HeldWrites(context: android.content.Context) : DataStore<Preferences> {
        private val delegate = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("held_writes") },
        )
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val waiting = AtomicInteger()
        override val data: Flow<Preferences> get() = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            gate?.let { waiting.incrementAndGet(); it.await() }
            return delegate.updateData(transform)
        }
    }

    /**
     * A load landing while the finish is still being filed — the read marker's write, the write to disk — found the
     * follower's job alive and the run's story so far in hand, and took the two for "streaming": `chatStatus`
     * answered RUNNING over the footer, and the load's own publication kept it, until the next-run looks gave up
     * six seconds later. On the phone: a foreground return, or a reopen, in the moment a turn ends. Pinned by
     * holding the finish's read-marker write, which is where the screen's collector sits right after the frame that
     * ended the stream, while a revalidation lands with its run page ahead of its transcript (the page-first render
     * is what asks `chatStatus`). A run this device saw end is not being followed live, whatever the job's state.
     */
    @Test
    fun `a load landing while the finish is still being filed does not call the chat running again`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = HeldWrites(context)
        val heldPrefs = PreferencesStore(context, settings)
        val conversations = ConversationRepository(
            session, agents, heldPrefs, hub, attachments, cache, traces,
            isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        )
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.let { it.isStreaming && !it.isLoading } }
        awaitUntil { heldPrefs.localAgentState.first().readMarkers.containsKey("bc-1") }
        val frames = CopyOnWriteArrayList<Pair<Boolean, RunStatus?>>()
        val recorder = scope.launch { conversations.state("bc-1").collect { frames += it.isStreaming to it.runStatus } }

        // The run ends off its record (a status this build cannot read; the stream gone for good). The finish's
        // read-marker write is held: the screen's collector is still inside the finish when the load below lands.
        settings.gate = CompletableDeferred()
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "HIBERNATING")
        streamer.emit("run-1", RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { frames.any { (streaming, _) -> !streaming } && settings.waiting.get() > 0 }
        val ended = frames.indexOfFirst { (streaming, _) -> !streaming }
        assertThat(frames[ended].second).isEqualTo(RunStatus.UNKNOWN)

        // A revalidation now, its run page landing ahead of its transcript.
        api.conversationGate = CompletableDeferred()
        now += 60_000
        val pages = api.listRunsCalls
        conversations.revalidate("bc-1")
        awaitUntil { api.listRunsCalls > pages }
        awaitUntil { api.conversationCalls >= 2 }
        api.conversationGate!!.complete(Unit)
        awaitUntil { !conversations.state("bc-1").value.isLoading }
        settings.gate!!.complete(Unit)
        settings.gate = null
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter }
        recorder.cancel()

        assertWithMessage("frames from the stream's end: ${frames.drop(ended)}").that(frames.drop(ended).none { (_, status) -> status?.isActive == true }).isTrue()
        val settled = conversations.state("bc-1").value
        assertThat(settled.isStreaming).isFalse()
        assertThat(settled.runStatus).isEqualTo(RunStatus.UNKNOWN)
    }

    /**
     * The common way a turn ends on the phone: on its own stream, with the chat open. The hub's finish reaches the
     * list before the snapshot reaches the chat (see `LiveRunHub.finish`), so the frame that ends the stream ends
     * the turn too — never a frame that reads "Working…" over the reply and the footer — and nothing the chat's
     * next-run looks read afterwards (the agent's record, a poll behind) puts it back.
     */
    @Test
    fun `a run finishing on its stream ends the chat in the frame that ends the stream, and nothing puts it back`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val conversations = repository()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        val frames = CopyOnWriteArrayList<Pair<Boolean, RunStatus?>>()
        val recorder = scope.launch { conversations.state("bc-1").collect { frames += it.isStreaming to it.runStatus } }

        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Assistant("Shipped."))
        now += 30_000
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Shipped.", 30_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter && frames.any { (streaming, _) -> !streaming } }
        val ended = frames.indexOfFirst { (streaming, _) -> !streaming }
        assertWithMessage("frames from the stream's end: ${frames.drop(ended)}").that(frames[ended].second).isEqualTo(RunStatus.FINISHED)
        assertThat(agents.agent("bc-1")!!.let { it.isRunning to it.runStatus }).isEqualTo(false to RunStatus.FINISHED)

        // The next-run looks that follow a finish read the agent's record, whose run still reads RUNNING on a server
        // a poll behind its own stream: the list's memory of the finish outranks it, in the row and in the chat.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "RUNNING")
        agents.refresh()
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()
        assertThat(agents.loadDetail("bc-1").getOrThrow().runStatus).isEqualTo(RunStatus.FINISHED)
        conversations.revalidate("bc-1")
        delay(300)
        recorder.cancel()
        assertWithMessage("frames from the stream's end: ${frames.drop(ended)}").that(frames.drop(ended).none { (_, status) -> status?.isActive == true }).isTrue()
        assertThat(conversations.state("bc-1").value.runStatus).isEqualTo(RunStatus.FINISHED)
    }

    @Test
    fun `the first screen to open a chat is reported once, and again after the last one leaves`() {
        val opened = mutableListOf<String>()
        val conversations = ConversationRepository(
            session, agents, prefs, hub, attachments, cache, traces,
            isForeground = { true }, onOpened = { opened += it }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        )
        conversations.attach("bc-1")
        conversations.attach("bc-1")
        assertThat(opened).containsExactly("bc-1")
        conversations.detach("bc-1")
        conversations.detach("bc-1")
        conversations.attach("bc-1")
        assertThat(opened).containsExactly("bc-1", "bc-1")
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

    @Test
    fun `forgetting a chat a screen still shows empties it in place and leaves its flow alive`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"))
        agents.refresh()
        val conversations = repository()
        // The screen captures the flow once, at construction, exactly as the ViewModel does.
        val screen = conversations.state("bc-1")
        conversations.attach("bc-1")
        awaitUntil { screen.value.items.isNotEmpty() }

        conversations.forget("bc-1")
        awaitUntil { screen.value.items.isEmpty() }
        assertThat(screen.value.isLoading).isFalse()
        awaitUntil { cache.read("bc-1") == null }

        // Refresh still reaches the flow the screen is collecting rather than a replacement nobody watches.
        conversations.reload("bc-1")
        awaitUntil { screen.value.items.filterIsInstance<UserMessage>().map { it.text } == listOf("Prompt 1") }
    }

    /**
     * [ConversationRepository.resume] and [ConversationRepository.revalidate] are called from the composition, and
     * the entry's monitor is held by the load and the live stream while they rebuild the timeline. Neither may do
     * its work on the caller's thread: with the entry's own thread occupied, both must still return, and what they
     * asked for must happen once it is free.
     */
    @Test
    fun `resuming and revalidating hand their work to the entry rather than doing it on the caller's thread`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.transcripts["bc-1"] = transcript("user_message" to "Ship it")
        agents.refresh()
        val entryThread = Executors.newSingleThreadExecutor()
        val conversations = ConversationRepository(
            session, agents, prefs, hub, attachments, cache, traces,
            isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
            entryDispatcher = entryThread.asCoroutineDispatcher(),
        )
        try {
            conversations.attach("bc-1")
            awaitUntil { conversations.state("bc-1").value.isStreaming }
            conversations.pause("bc-1")
            awaitUntil { !conversations.state("bc-1").value.isStreaming }
            streamer.reset("run-1")

            // The entry's thread is taken, standing in for the load that holds its monitor across a timeline rebuild.
            val busy = CountDownLatch(1)
            val occupied = CountDownLatch(1)
            entryThread.execute {
                occupied.countDown()
                busy.await()
            }
            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue()

            now += 60_000
            val fetches = api.listRunsCalls
            // Both return while the entry is unreachable, and neither has done anything yet.
            withTimeout(1_000) { launch(Dispatchers.Default) { conversations.resume("bc-1") }.join() }
            withTimeout(1_000) { launch(Dispatchers.Default) { conversations.revalidate("bc-1") }.join() }
            assertThat(conversations.state("bc-1").value.isStreaming).isFalse()
            assertThat(api.listRunsCalls).isEqualTo(fetches)

            // Released: the run is followed again and the history is fetched, as the resume asked — the run list; the
            // transcript only when the list says a turn started or ended since, which none did (see runsUnchanged).
            busy.countDown()
            awaitUntil { conversations.state("bc-1").value.isStreaming }
            awaitUntil { api.listRunsCalls > fetches }
        } finally {
            entryThread.shutdownNow()
        }
    }

    /**
     * A coordinator's trace as 0.3.4 wrote it: the `SendMessage` call filed under `Other` with nothing read off its
     * arguments (the name was not known), the arguments dropped as every call's are. The message's body is not on
     * the disk at all, so re-reading the file cannot bring it back; only the run's log (or the account's record) can.
     */
    private fun staleCoordinatorTrace(runId: String, createdAt: String) = CachedTrace(
        runId,
        java.time.Instant.parse(createdAt).toEpochMilli(),
        listOf(
            AssistantMessage("asst-$runId-0", "The release worker shipped v0.3.3."),
            ActivityGroup("activity-$runId-1", listOf(ToolCall("$runId-send", "SendMessage", ToolKind.Other, "completed", ""))),
            RunFooter("run-$runId-2", runId, RunStatus.FINISHED, 60_000, emptyList()),
        ),
    )

    /** The same run as its retained log carries it: the `SendMessage` frame with its text under `text.content`. */
    private suspend fun retainCoordinatorRun(runId: String, message: String) {
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Assistant("The release worker shipped v0.3.3."))
        streamer.emit(
            runId,
            RunStreamEvent.ToolCall(
                SseToolCallDto(
                    callId = "$runId-send", name = "sendMessage", status = "completed",
                    args = buildJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(message)) }) },
                    result = buildJsonObject { put("success", buildJsonObject { put("timestamp", JsonPrimitive("1789341071043")) }) },
                ),
            ),
        )
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, null, 60_000, null))
        streamer.emit(runId, RunStreamEvent.Done)
    }

    private fun ConversationState.sendMessagePayload(runId: String) =
        items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.firstOrNull { it.callId == "$runId-send" }?.payload as? ToolPayload.CoordinatorMessage

    @Test
    fun `a trace saved by a build that did not read SendMessage is shown, replayed from the log and replaced on disk`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-coord", "Cursor for Android", Triple("run-1", "Start this Project.", "The release worker shipped v0.3.3."))
        agents.refresh()
        val createdAt = api.runs.getValue("run-1").createdAt
        traces.put("bc-coord", listOf(staleCoordinatorTrace("run-1", createdAt)))
        val message = "The keyboard worker merged #113; **v0.3.4** is being cut from it."
        retainCoordinatorRun("run-1", message)

        val conversations = repository()
        conversations.attach("bc-coord")
        // The disk copy is shown first, the call as it was filed; a screen re-reads it as a coordinator's on the way.
        awaitUntil { conversations.state("bc-coord").value.items.any { it is ActivityGroup } }
        // The stale run is asked for again although it has a trace, and the log's frame brings the message.
        awaitUntil { conversations.state("bc-coord").value.sendMessagePayload("run-1")?.message == message }
        assertThat(streamer.connections).containsExactly("run-1")
        val call = conversations.state("bc-coord").value.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.single { it.callId == "run-1-send" }
        assertThat(call.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(call.argKeys).containsExactly("text")

        // The file is the replayed trace now, and the next open reads it without another replay.
        awaitUntil { (traces.read("bc-coord").getValue("run-1").items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.single().payload as? ToolPayload.CoordinatorMessage)?.message == message }
        conversations.detach("bc-coord")
        val next = repository()
        next.attach("bc-coord")
        awaitUntil { !next.state("bc-coord").value.isLoading && next.state("bc-coord").value.sendMessagePayload("run-1")?.message == message }
        delay(150)
        assertThat(streamer.connections).containsExactly("run-1")
    }

    @Test
    fun `a stale trace whose log has expired stays shown, re-read as a coordinator's, and is not asked for again`() = runBlocking<Unit> {
        api.addFinishedAgent("bc-coord", "Cursor for Android", Triple("run-1", "Start this Project.", "The release worker shipped v0.3.3."))
        agents.refresh()
        val createdAt = api.runs.getValue("run-1").createdAt
        traces.put("bc-coord", listOf(staleCoordinatorTrace("run-1", createdAt)))
        streamer.dropNextConnection("run-1", code = RunStreamEvent.Error.STREAM_EXPIRED, message = "The run stream has expired")

        val conversations = repository()
        conversations.attach("bc-coord")
        awaitUntil { !conversations.state("bc-coord").value.isLoading && streamer.connections.contains("run-1") }
        delay(200)
        // What the disk had is still what is shown: the call, as stored, waits to be re-read by the screen.
        val stored = conversations.state("bc-coord").value.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.single()
        assertThat(stored.name).isEqualTo("SendMessage")
        assertThat(CoordinatorTranscript.reinterpret(stored).payload).isEqualTo(ToolPayload.CoordinatorMessage("", missing = true))
        assertThat(CoordinatorTranscript.hasCoordinatorContent(conversations.state("bc-coord").value.items)).isTrue()
        // Expired is expired: a reload does not open the stream a second time.
        conversations.reload("bc-coord")
        awaitUntil { api.conversationCalls == 2 }
        delay(150)
        assertThat(streamer.connections).containsExactly("run-1")
    }
}
