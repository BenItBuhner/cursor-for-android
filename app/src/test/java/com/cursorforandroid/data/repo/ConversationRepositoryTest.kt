package com.cursorforandroid.data.repo

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
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.ModelParam
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
import kotlinx.coroutines.async
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
        // The stream starts after the answer has been published and written back; wait for that, the last step.
        awaitUntil { api.conversationCalls == 1 && fresh.state(id).value.isStreaming }
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
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }

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
        assertThat(reopened.items.map { it::class.simpleName }).containsExactly("UserMessage", "AssistantMessage", "RunFooter").inOrder()
        assertThat(reopened.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat(reopened.isStreaming).isFalse()
        assertThat(reopened.runStatus).isEqualTo(RunStatus.FINISHED)
        awaitUntil { streamer.connections.count { it == "run-1" } == 2 }

        // Once the retained log reads to its end, the complete trace takes the reply's place. Reading it is not
        // news about the agent: the row keeps the timestamp the server gave it.
        streamer.emit("run-1", RunStreamEvent.Assistant("Shipped."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Shipped.", 30_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }
        val traced = conversations.state("bc-1").value
        assertThat(traced.items.map { it::class.simpleName }).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(traced.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
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
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter }
        val state = conversations.state("bc-1").value
        assertThat(state.isLoading).isFalse()
        assertThat(state.isStreaming).isFalse()
        assertThat(state.runStatus).isEqualTo(RunStatus.FINISHED)
        // Not the two items the screen left with: the edit that followed, the final reply and the footer are all there.
        assertThat(state.types()).containsExactly("UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(state.items.filterIsInstance<ActivityGroup>().single().calls.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
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
        streamFirstHalf("run-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is ActivityGroup } }
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED", result = "Shipped.", durationMs = 30_000)
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter }
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
        assertThat(saved.all { it.args == null && it.result == null }).isTrue()

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
}
