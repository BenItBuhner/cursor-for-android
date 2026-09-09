package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
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

    private fun repository(persist: Boolean = false, busyRecheckMs: Long = 20_000) = FollowUpRepository(
        conversations, agents, hub,
        mcpServers = { emptyList() },
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
        val followUps = repository()
        followUps.enqueue("bc-1", "First")
        val urgent = followUps.enqueue("bc-1", "Actually, stop and do this")

        val result = followUps.sendNow("bc-1", urgent.id)

        assertThat(result.isSuccess).isTrue()
        assertThat(api.cancelled).containsExactly("run-1")
        // The cancel marks the row idle, so the steered message is the next thing the server hears, ahead of the other.
        awaitUntil { sent() == listOf("Actually, stop and do this") }
        awaitUntil { followUps.state("bc-1").value.queue.map { it.text } == listOf("First") }
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

        assertThat(followUps.sendNow("bc-1", item.id).isSuccess).isTrue()

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

        assertThat(followUps.sendNow("bc-1", "gone").isFailure).isTrue()

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
        assertThat(followUps.sendNow("bc-1", "missing").isFailure).isTrue()
        assertThat(followUps.sendNow("bc-1", followUps.state("bc-1").value.queue.single().id).isSuccess).isTrue()

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
