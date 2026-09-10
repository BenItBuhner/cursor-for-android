package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

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

    /** Holds a send just before it reaches the server, where the repository asks for the MCP servers to attach. */
    @Volatile private var mcpGate: CompletableDeferred<Unit>? = null
    private val mcpCalls = AtomicInteger()

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

    private fun repository(persist: Boolean = false, busyRecheckMs: Long = 20_000) = FollowUpRepository(
        conversations, agents, hub,
        mcpServers = { mcpCalls.incrementAndGet(); mcpGate?.await(); emptyList() },
        store = store.takeIf { persist },
        persist = { true },
        scope = scope,
        draftSaveDelayMs = 10,
        busyRecheckMs = busyRecheckMs,
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

    /**
     * A steer is the piece of work a sign-out used to leave running: launched on the repository's own scope and
     * tracked by nothing, it would go on under the next account, post the previous one's message with its key, and
     * write the queue back behind the wiped files.
     */
    @Test
    fun `a steer held across a sign-out reaches neither the next account's server nor its files`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.addIdleAgent("bc-2", "Other", "run-0b")
        agents.refresh()
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.activeRunId == "run-1" }
        mcpGate = CompletableDeferred()
        val followUps = repository(persist = true)
        followUps.state("bc-1").first { it.restored }
        followUps.enqueue("bc-1", "And the one behind it")
        val urgent = followUps.enqueue("bc-1", "Under the old account")
        assertThat(followUps.sendNow("bc-1", urgent.id)).isTrue()
        // The turn has been stopped for it and the steer is on its way out, held before it reaches the server.
        awaitUntil { api.cancelled == listOf("run-1") }
        awaitUntil { mcpCalls.get() == 1 }
        awaitUntil { store.read("bc-1")?.queue?.size == 2 }

        // Signing out, in the order AppGraph uses: the queue is dropped, then the files are wiped.
        followUps.resetAll()
        store.clear()
        mcpGate?.complete(Unit)

        // A follow-up for another chat is the marker for the next account. It is queued after the held send was let
        // go and has further to travel — an entry to make, a file to read, a turn to be found free — so by the time
        // the server has it, a send the sign-out failed to stop would already have arrived, and its save with it.
        api.createRunGate = CompletableDeferred()
        followUps.enqueue("bc-2", "Under the new account")
        awaitUntil { api.runRequests.isNotEmpty() }

        assertThat(sent()).containsExactly("Under the new account")
        assertThat(store.read("bc-1")).isNull()
    }

    @Test
    fun `a follow-up the dispatcher has claimed is left alone by steer, edit and remove`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        val gate = CompletableDeferred<Unit>()
        api.createRunGate = gate
        val followUps = repository()
        val item = followUps.enqueue("bc-1", "Only once")

        // The dispatcher has claimed the head and its request is out; the card reads as in flight.
        awaitUntil { api.runRequests.size == 1 }
        assertThat(followUps.state("bc-1").value.queue.single().isSending).isTrue()

        assertThat(followUps.sendNow("bc-1", item.id)).isFalse()
        assertThat(followUps.takeForEdit("bc-1", item.id)).isNull()
        followUps.remove("bc-1", item.id)
        assertThat(followUps.state("bc-1").value.queue).hasSize(1)
        assertThat(followUps.state("bc-1").value.draft.isEmpty).isTrue()

        gate.complete(Unit)
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(sent()).containsExactly("Only once")
        assertThat(api.cancelled).isEmpty()
    }

    /**
     * The steer and the dispatcher both go for the head, from different threads, on forty agents at once. Whichever
     * wins, the message reaches the server exactly once: claiming the head and marking it sending are one step under
     * the monitor [FollowUpRepository.sendNow] holds.
     */
    @Test
    fun `a steer racing the dispatcher for the head still sends the message once`() = runBlocking<Unit> {
        val count = 40
        repeat(count) { i -> api.addIdleAgent("bc-$i", "Agent $i", "run-$i") }
        agents.refresh()
        val followUps = repository()
        val start = CountDownLatch(1)
        val steered = CountDownLatch(count)
        val threads = (0 until count).map { i ->
            Thread {
                start.await()
                followUps.state("bc-$i").value.queue.firstOrNull()?.let { followUps.sendNow("bc-$i", it.id) }
                steered.countDown()
            }.apply { start() }
        }

        repeat(count) { i -> followUps.enqueue("bc-$i", "Message $i") }
        start.countDown()
        steered.await()
        threads.forEach { it.join() }

        repeat(count) { i -> awaitUntil { followUps.state("bc-$i").value.queue.isEmpty() } }
        // Nothing sends twice, whichever of the two got there first.
        repeat(count) { i -> assertThat(sent().count { it == "Message $i" }).isEqualTo(1) }
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

    /**
     * The follow-up POST carries no idempotency key. A send marker on disk before the request means a restore must
     * ask whether the server took it, not send again by itself.
     */
    @Test
    fun `a follow-up mid-send is flagged on restore and not sent again`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        api.createRunGate = CompletableDeferred()
        val first = repository(persist = true)
        first.state("bc-1").first { it.restored }
        first.enqueue("bc-1", "Maybe already")
        awaitUntil { store.read("bc-1")?.queue?.single()?.sendStartedAtMillis != null }

        first.resetAll()
        val second = repository(persist = true)
        second.state("bc-1").first { it.restored }
        awaitUntil { second.state("bc-1").value.queue.singleOrNull()?.needsConfirmation == true }
        assertThat(api.runs.keys.none { it.startsWith("run-followup") }).isTrue()

        api.createRunGate?.complete(Unit)
        delay(300)
        assertThat(api.runs.keys.none { it.startsWith("run-followup") }).isTrue()
    }

    @Test
    fun `a follow-up the server already took is dropped on restore`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        val sendStarted = AppClock.now()
        val runAt = java.time.Instant.ofEpochMilli(sendStarted + 5_000).toString()
        api.runs["run-fu"] = RunDto(id = "run-fu", agentId = "bc-1", status = "RUNNING", createdAt = runAt, updatedAt = runAt)
        api.transcripts["bc-1"] = listOf(V0ConversationMessageDto("run-fu-u", "user_message", "Already there"))
        store.write(
            "bc-1",
            FollowUpDraft.EMPTY,
            listOf(
                QueuedFollowUp(
                    id = "queued-test",
                    text = "Already there",
                    queuedAtMillis = sendStarted - 1_000,
                    sendStartedAtMillis = sendStarted,
                ),
            ),
        )

        val followUps = repository(persist = true)
        followUps.state("bc-1").first { it.restored }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(api.runs.keys).contains("run-fu")
    }

    @Test
    fun `a failed head leaves the dispatcher inactive until retry`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        api.failCreateRun = true
        val followUps = repository()
        val stuck = followUps.enqueue("bc-1", "Stuck")
        followUps.enqueue("bc-1", "Second")

        awaitUntil { followUps.state("bc-1").value.queue.first().error != null }
        delay(300)
        assertThat(api.runRequests).hasSize(1)

        api.failCreateRun = false
        followUps.retry("bc-1", stuck.id)
        awaitUntil { sent() == listOf("Stuck") }
        awaitUntil { followUps.state("bc-1").value.queue.map { it.text } == listOf("Second") }
    }

    @Test
    fun `empty entries are evicted once the cap is reached`() = runBlocking<Unit> {
        repeat(FollowUpRepositoryTestHelper.MAX_ENTRIES) { i ->
            api.addIdleAgent("bc-$i", "Agent $i", "run-$i")
        }
        agents.refresh()
        val followUps = repository(persist = false)
        repeat(FollowUpRepositoryTestHelper.MAX_ENTRIES) { i ->
            followUps.state("bc-$i").first { it.restored }
        }
        assertThat(FollowUpRepositoryTestHelper.entryCount(followUps)).isEqualTo(FollowUpRepositoryTestHelper.MAX_ENTRIES)

        followUps.state("bc-overflow").first { it.restored }
        assertThat(FollowUpRepositoryTestHelper.entryCount(followUps)).isAtMost(FollowUpRepositoryTestHelper.MAX_ENTRIES)
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
