package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.PromptImage
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
import kotlinx.coroutines.flow.first
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
 * The follow-up queue against the fake backend: a message sent mid-turn waits, goes out when the turn ends, and is
 * sent first — the turn stopped for it — when steered. And the draft: kept across visits and restarts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FollowUpRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_800_000_000_000L
    private lateinit var context: Context
    private lateinit var agents: AgentRepository
    private lateinit var hub: LiveRunHub
    private lateinit var conversations: ConversationRepository
    private lateinit var store: FollowUpStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = false)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        val attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10)
        hub = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
        conversations = ConversationRepository(session, agents, prefs, hub, attachments, isForeground = { true }, prefetchLimit = 0, scope = scope)
        store = FollowUpStore(context)
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { store.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun repository(persist: Boolean = false, busyRecheckMs: Long = 20_000, idleSettleMs: Long = 20_000, retryBaseMs: Long = 20) = FollowUpRepository(
        conversations, agents, hub,
        mcpServers = { emptyList() },
        store = store.takeIf { persist },
        persist = { true },
        scope = scope,
        draftSaveDelayMs = 10,
        busyRecheckMs = busyRecheckMs,
        idleSettleMs = idleSettleMs,
        retryBaseMs = retryBaseMs,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    /** The texts of the follow-ups the server has accepted, in order. */
    private fun sent() = api.runRequests.map { it.prompt.text }

    private fun prompts(agentId: String) = conversations.state(agentId).value.items.filterIsInstance<UserMessage>()

    private suspend fun finish(runId: String, text: String = "Done.") {
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, text, 5_000, null))
        streamer.emit(runId, RunStreamEvent.Done)
    }

    @Test
    fun `a follow-up sent mid-turn waits and goes out by itself when the turn ends`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val followUps = repository()

        followUps.enqueue("bc-1", "Now the tests")
        delay(300)

        // Nothing goes to the server while the row says the agent is on its turn.
        assertThat(sent()).isEmpty()
        assertThat(followUps.state("bc-1").value.queue.map { it.text }).containsExactly("Now the tests")

        // The queue follows the run through the hub, so its end is seen with no screen attached and no monitor running.
        awaitUntil { streamer.connections.contains("run-1") }
        finish("run-1")

        awaitUntil { sent() == listOf("Now the tests") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(agents.agent("bc-1")?.isRunning).isTrue()
    }

    @Test
    fun `queued follow-ups go out one per turn, in order`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val followUps = repository()

        followUps.enqueue("bc-1", "First")
        followUps.enqueue("bc-1", "Second")
        awaitUntil { streamer.connections.contains("run-1") }
        finish("run-1")

        awaitUntil { sent() == listOf("First") }
        delay(200)
        assertThat(sent()).containsExactly("First")
        assertThat(followUps.state("bc-1").value.queue.map { it.text }).containsExactly("Second")

        // The first follow-up's run is followed in turn; its end releases the second.
        val second = agents.agent("bc-1")!!.latestRunId!!
        awaitUntil { streamer.connections.contains(second) }
        finish(second)
        awaitUntil { sent() == listOf("First", "Second") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
    }

    @Test
    fun `a busy answer the row did not predict keeps the message waiting rather than failing it`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        api.busyCreateRun = true
        val followUps = repository()

        followUps.enqueue("bc-1", "Go on")

        // One attempt, refused; the message is neither gone nor marked failed, and the row takes the server's word.
        awaitUntil { api.runRequests.size == 1 }
        awaitUntil { agents.agent("bc-1")?.isRunning == true }
        delay(200)
        assertThat(api.runRequests).hasSize(1)
        val waiting = followUps.state("bc-1").value.queue.single()
        assertThat(waiting.error).isNull()
        assertThat(waiting.isSending).isFalse()

        // The turn ends (a refresh says so): the message goes out.
        api.busyCreateRun = false
        agents.patch("bc-1") { it.copy(runStatus = RunStatus.FINISHED) }
        awaitUntil { api.runRequests.size == 2 }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
    }

    @Test
    fun `steering sends a queued follow-up first and stops the turn under way`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        // Like the real server, the cancelled turn is still winding down when the first send arrives.
        api.busyCreateRun = true
        val followUps = repository()
        followUps.enqueue("bc-1", "First")
        val urgent = followUps.enqueue("bc-1", "Actually, stop and do this")

        assertThat(followUps.sendNow("bc-1", urgent.id)).isTrue()

        // Steered, the message has left the cards and is in the transcript as a pending prompt, as if sent.
        assertThat(followUps.state("bc-1").value.queue.single { it.id == urgent.id }.isSteered).isTrue()
        awaitUntil { prompts("bc-1").any { it.text == "Actually, stop and do this" && it.isPending } }
        awaitUntil { api.cancelled == listOf("run-1") }
        awaitUntil { api.runRequests.isNotEmpty() }
        // Refused as busy, the row takes the server's word again; the message stays pending on screen and nothing
        // behind it moves.
        awaitUntil { agents.agent("bc-1")?.runStatus == RunStatus.RUNNING }
        delay(100)
        assertThat(prompts("bc-1").single { it.text == "Actually, stop and do this" }.isPending).isTrue()
        assertThat(sent()).doesNotContain("First")

        // The turn is over: the steered message is the next thing the server hears, ahead of the other.
        api.busyCreateRun = false
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "CANCELLED")
        agents.patch("bc-1") { it.copy(runStatus = RunStatus.CANCELLED) }
        awaitUntil { api.runs.values.any { it.id.startsWith("run-followup") } }
        assertThat(sent().last()).isEqualTo("Actually, stop and do this")
        awaitUntil { followUps.state("bc-1").value.queue.map { it.text } == listOf("First") }
        // Filed by the server, the prompt comes up to full strength.
        awaitUntil { prompts("bc-1").single { it.text == "Actually, stop and do this" }.isPending.not() }
    }

    @Test
    fun `a steer whose cancel fails returns the message to the cards with the reason`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        api.failCancelWith = CursorApiException(503, "unavailable", "Try again later.")
        val followUps = repository()
        val item = followUps.enqueue("bc-1", "Now")

        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()

        awaitUntil { followUps.state("bc-1").value.queue.single().error != null }
        val back = followUps.state("bc-1").value.queue.single()
        assertThat(back.isSteered).isFalse()
        // The pending bubble came down with it.
        assertThat(prompts("bc-1").none { it.text == "Now" }).isTrue()
        assertThat(sent()).isEmpty()
    }

    @Test
    fun `a steer whose cancel finds the turn already over is not a failure`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        api.failCancel = true
        val followUps = repository()
        val item = followUps.enqueue("bc-1", "Now")

        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()

        // The message is still first in line and goes out when the row learns the turn is over.
        awaitUntil { streamer.connections.contains("run-1") }
        finish("run-1")
        awaitUntil { sent() == listOf("Now") }
    }

    @Test
    fun `a busy answer as the wire delivers it keeps the message waiting too`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        // Not the fake's ready-made exception: the HttpException Retrofit raises, its body still unread. The
        // repository reads the code and the chat words the failure, and both must find `agent_busy` in it.
        api.createRunError = FakeCursorApi.httpError(409, "agent_busy", "Agent is busy.")
        val followUps = repository()

        followUps.enqueue("bc-1", "Go on")

        awaitUntil { api.runRequests.size == 1 }
        awaitUntil { agents.agent("bc-1")?.isRunning == true }
        delay(200)
        val waiting = followUps.state("bc-1").value.queue.single()
        assertThat(waiting.error).isNull()
        assertThat(waiting.isSending).isFalse()
        // Nor is being busy an error for the chat to show.
        assertThat(conversations.state("bc-1").value.error).isNull()

        api.createRunError = null
        agents.patch("bc-1") { it.copy(runStatus = RunStatus.FINISHED) }
        awaitUntil { api.runRequests.size == 2 }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
    }

    @Test
    fun `a steer whose send stumbles once is tried again rather than failed`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        // The agent is still winding down the turn that was just cancelled for the message: the first send is
        // refused for a reason other than being busy, as the wire delivers it.
        api.failNextCreateRun = FakeCursorApi.httpError(409, "agent_stopping", "The agent is stopping.")
        val followUps = repository()
        val item = followUps.enqueue("bc-1", "Now")

        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()

        awaitUntil { api.cancelled == listOf("run-1") }
        awaitUntil { sent() == listOf("Now", "Now") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        // The message stayed on screen throughout and is filed under its run now.
        awaitUntil { prompts("bc-1").single { it.text == "Now" }.isPending.not() }
        assertThat(conversations.state("bc-1").value.error).isNull()
    }

    @Test
    fun `a steer whose send keeps failing returns the message to the cards with the reason`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        api.failCreateRun = true
        val followUps = repository()
        val item = followUps.enqueue("bc-1", "Now")

        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()

        awaitUntil { followUps.state("bc-1").value.queue.single().error != null }
        val back = followUps.state("bc-1").value.queue.single()
        // Tried a few times, then given up with the server's reason on the card, where it can be read.
        assertThat(api.runRequests).hasSize(4)
        assertThat(back.error).isEqualTo("Try again later.")
        assertThat(back.isSteered).isFalse()
        assertThat(back.isSending).isFalse()
        assertThat(prompts("bc-1").none { it.text == "Now" }).isTrue()
        // Send-now on the failed card is the retry; the turn was stopped already, so nothing is cancelled again.
        api.failCreateRun = false
        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(api.cancelled).containsExactly("run-1")
        assertThat(sent().last()).isEqualTo("Now")
    }

    @Test
    fun `a steer stops the run the row reports when the chat still follows an older one`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        // A turn started elsewhere: the list learns of run-2 on its next refresh, the open chat has not reloaded.
        api.runs["run-2"] = RunDto(id = "run-2", agentId = "bc-1", status = "RUNNING", createdAt = "2026-04-13T19:30:00.000Z", updatedAt = "2026-04-13T19:30:00.000Z")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "ACTIVE", latestRunId = "run-2", updatedAt = "2026-04-13T19:30:00.000Z")
        api.v0["bc-1"] = api.v0.getValue("bc-1").copy(status = "RUNNING")
        agents.refresh()
        awaitUntil { agents.agent("bc-1")?.let { it.isRunning && it.latestRunId == "run-2" } == true }
        assertThat(conversations.state("bc-1").value.activeRunId).isEqualTo("run-1")
        val followUps = repository()
        val item = followUps.enqueue("bc-1", "Now")

        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()

        // The turn cancelled is the one under way, not the finished one the chat happened to be looking at.
        awaitUntil { api.cancelled == listOf("run-2") }
        awaitUntil { sent() == listOf("Now") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
    }

    @Test
    fun `a steer whose cancel finds its run over stops the turn the server is actually on`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        // Meanwhile, on the server: run-1 ended and run-2 began (a follow-up from another device), and neither the
        // row nor the chat has heard of it yet.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED")
        api.notCancellable += "run-1"
        api.runs["run-2"] = RunDto(id = "run-2", agentId = "bc-1", status = "RUNNING", createdAt = "2026-04-13T19:30:00.000Z", updatedAt = "2026-04-13T19:30:00.000Z")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(latestRunId = "run-2", updatedAt = "2026-04-13T19:30:00.000Z")
        val followUps = repository()
        val item = followUps.enqueue("bc-1", "Now")

        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()

        // The refused cancel is not the end of it: the row is settled against the record, which names run-2, and
        // that is the turn stopped for the message.
        awaitUntil { api.cancelled == listOf("run-2") }
        awaitUntil { sent() == listOf("Now") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(agents.agent("bc-1")?.latestRunId).isNotEqualTo("run-1")
    }

    @Test
    fun `a steered message is not held by a row the record put back to running`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        awaitUntil { streamer.connections.contains("run-1") }
        // The server goes on answering busy for a while after the cancel, its record a step behind its stream.
        api.busyCreateRun = true
        val followUps = repository(idleSettleMs = 100)
        val item = followUps.enqueue("bc-1", "Now")

        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()
        awaitUntil { api.cancelled == listOf("run-1") }
        awaitUntil { api.runRequests.isNotEmpty() }
        // The stream reports the end of the cancelled turn: the row is idle, the next send is refused as busy all
        // the same — and the row must not be left saying running by that, since the stream will not say the run
        // ended a second time.
        finish("run-1", text = "")
        awaitUntil { api.runRequests.size >= 2 }
        delay(300)
        assertThat(followUps.state("bc-1").value.queue.single().error).isNull()

        // Nothing here corrects the row but the repository itself: no refresh, no patch.
        api.busyCreateRun = false
        awaitUntil { api.runs.values.any { it.id.startsWith("run-followup") } }
        assertThat(sent().last()).isEqualTo("Now")
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        awaitUntil { prompts("bc-1").single { it.text == "Now" }.isPending.not() }
    }

    @Test
    fun `steering while the chat is still being created waits for its first turn rather than stopping the launch`() = runBlocking<Unit> {
        val request = LaunchRequest(prompt = "Do the thing", repoUrl = "https://github.com/acme/app", ref = "main", modelId = "auto-smart", modelParams = emptyList(), autoCreatePr = false, planMode = false)
            .let { it.copy(agentId = LaunchIdempotency.agentId(it, "nonce")) }
        val id = request.agentId!!
        api.createGate = CompletableDeferred()
        val launch = async { conversations.launch(request, "Auto") }
        awaitUntil { prompts(id).isNotEmpty() }
        conversations.attach(id)
        // Prompts sent from here are placed by the clock; the steer comes after the launch, as it would.
        now += 1_000
        val followUps = repository()
        val item = followUps.enqueue(id, "Also this")

        assertThat(followUps.sendNow(id, item.id)).isTrue()
        delay(200)
        // Nothing to stop yet, and the launch is not what gets stopped: the message shows as pending and waits.
        assertThat(launch.isActive).isTrue()
        assertThat(api.cancelled).isEmpty()
        assertThat(prompts(id).any { it.text == "Also this" && it.isPending }).isTrue()

        api.createGate!!.complete(Unit)
        assertThat(launch.await().isSuccess).isTrue()
        val first = agents.agent(id)!!.latestRunId!!
        awaitUntil { streamer.connections.contains(first) }
        finish(first)
        awaitUntil { sent() == listOf("Also this") }
        awaitUntil { followUps.state(id).value.queue.isEmpty() }
        assertThat(api.cancelled).isEmpty()
    }

    @Test
    fun `steering a message that is no longer queued stops nothing`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val followUps = repository()
        followUps.enqueue("bc-1", "Still here")

        assertThat(followUps.sendNow("bc-1", "gone")).isFalse()
        delay(200)

        assertThat(api.cancelled).isEmpty()
        assertThat(followUps.state("bc-1").value.queue.map { it.text }).containsExactly("Still here")
    }

    @Test
    fun `steering an idle agent's queued follow-up cancels nothing and sends it`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        api.failCreateRun = true
        val followUps = repository()
        followUps.enqueue("bc-1", "Try")
        awaitUntil { followUps.state("bc-1").value.queue.single().error != null }
        api.failCreateRun = false

        // A message that is no longer queued is not a reason to stop anything.
        assertThat(followUps.sendNow("bc-1", "missing")).isFalse()
        assertThat(followUps.sendNow("bc-1", followUps.state("bc-1").value.queue.single().id)).isTrue()

        assertThat(api.cancelled).isEmpty()
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(sent().last()).isEqualTo("Try")
    }

    @Test
    fun `a failure other than busy holds the queue at that message until it is retried`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        api.failCreateRun = true
        val followUps = repository()

        val first = followUps.enqueue("bc-1", "First")
        followUps.enqueue("bc-1", "Second")

        awaitUntil { followUps.state("bc-1").value.queue.first().error != null }
        delay(200)
        assertThat(api.runRequests).hasSize(1)
        assertThat(followUps.state("bc-1").value.queue.map { it.text }).containsExactly("First", "Second").inOrder()
        assertThat(followUps.state("bc-1").value.queue[1].error).isNull()

        api.failCreateRun = false
        followUps.retry("bc-1", first.id)
        awaitUntil { sent().count { it == "First" } == 2 }
        awaitUntil { followUps.state("bc-1").value.queue.map { it.text } == listOf("Second") }
    }

    @Test
    fun `editing a queued follow-up hands it to the composer and queues the draft in its place`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val followUps = repository()
        val image = DraftImage("img-1", PromptImage(byteArrayOf(1, 2, 3), "image/png"))
        val first = followUps.enqueue("bc-1", "First", images = listOf(image))
        followUps.enqueue("bc-1", "Second")
        followUps.setDraftText("bc-1", "Half-typed")

        val taken = followUps.takeForEdit("bc-1", first.id)

        assertThat(taken?.text).isEqualTo("First")
        val state = followUps.state("bc-1").value
        assertThat(state.draft.text).isEqualTo("First")
        assertThat(state.draft.images.map { it.id }).containsExactly("img-1")
        assertThat(state.queue.map { it.text }).containsExactly("Half-typed", "Second").inOrder()
        // The displaced draft keeps the taken message's place, with its own id.
        assertThat(state.queue.first().id).isNotEqualTo(first.id)
    }

    @Test
    fun `editing with an empty composer simply takes the message out of the queue`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val followUps = repository()
        val only = followUps.enqueue("bc-1", "Only")

        followUps.takeForEdit("bc-1", only.id)

        val state = followUps.state("bc-1").value
        assertThat(state.draft.text).isEqualTo("Only")
        assertThat(state.queue).isEmpty()
    }

    @Test
    fun `removing a queued follow-up drops it and the rest keep their order`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val followUps = repository()
        followUps.enqueue("bc-1", "A")
        val b = followUps.enqueue("bc-1", "B")
        followUps.enqueue("bc-1", "C")

        followUps.remove("bc-1", b.id)

        assertThat(followUps.state("bc-1").value.queue.map { it.text }).containsExactly("A", "C").inOrder()
    }

    @Test
    fun `the draft and the queue survive a restart and the queue goes out once the agent is free`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val image = DraftImage("img-1", PromptImage(byteArrayOf(9, 8, 7), "image/jpeg"))
        run {
            val followUps = repository(persist = true)
            followUps.state("bc-1").first { it.restored }
            followUps.enqueue("bc-1", "Queued before the restart", images = listOf(image))
            followUps.setDraftText("bc-1", "Typed before the restart")
            followUps.setDraftImages("bc-1", listOf(DraftImage("img-2", PromptImage(byteArrayOf(4, 5, 6), "image/png"))))
            awaitUntil { store.read("bc-1")?.let { it.draft.text == "Typed before the restart" && it.queue.size == 1 } == true }
            followUps.resetAll()
        }

        val followUps = repository(persist = true)
        val restored = followUps.state("bc-1").first { it.restored }
        assertThat(restored.draft.text).isEqualTo("Typed before the restart")
        assertThat(restored.draft.images.single().let { it.id to it.image.bytes.toList() }).isEqualTo("img-2" to listOf<Byte>(4, 5, 6))
        val queued = restored.queue.single()
        assertThat(queued.text).isEqualTo("Queued before the restart")
        assertThat(queued.images.single().image.bytes.toList()).isEqualTo(listOf<Byte>(9, 8, 7))

        // Restored, the queue is live: the run's end sends it, images and all.
        awaitUntil { streamer.connections.contains("run-1") }
        finish("run-1")
        awaitUntil { sent() == listOf("Queued before the restart") }
        assertThat(api.runRequests.single().prompt.images).hasSize(1)
        awaitUntil { store.read("bc-1")?.queue?.isEmpty() == true }
    }

    @Test
    fun `a chat with nothing typed and nothing queued leaves nothing on disk`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        val followUps = repository(persist = true)
        followUps.state("bc-1").first { it.restored }

        followUps.setDraftText("bc-1", "Something")
        awaitUntil { store.read("bc-1")?.draft?.text == "Something" }
        followUps.setDraftText("bc-1", "")
        awaitUntil { store.read("bc-1") == null }
    }
}
