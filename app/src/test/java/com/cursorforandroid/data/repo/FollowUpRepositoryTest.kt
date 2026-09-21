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
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.HeldSettings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var hub: LiveRunHub
    private lateinit var conversations: ConversationRepository
    private lateinit var store: FollowUpStore

    /** Holds a send just before it reaches the server, where the repository asks for the MCP servers to attach. */
    @Volatile private var mcpGate: CompletableDeferred<Unit>? = null
    private val mcpCalls = AtomicInteger()

    @Before
    fun setUp() {
        api.failCreateRun = false
        api.busyCreateRun = false
        api.createRunGate = null
        api.failCancelWith = null
        api.failCancel = false
        mcpGate = null
        mcpCalls.set(0)
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        attachments = AttachmentStore(context)
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

    private fun repository(
        persist: Boolean = false,
        idleSettleMs: Long = 20_000,
        retryBaseMs: Long = 20,
        scope: CoroutineScope = this.scope,
        conversations: ConversationRepository = this.conversations,
    ) = FollowUpRepository(
        conversations, agents, hub,
        mcpServers = { mcpCalls.incrementAndGet(); mcpGate?.await(); emptyList() },
        store = store.takeIf { persist },
        persist = { true },
        scope = scope,
        draftSaveDelayMs = 10,
        idleSettleMs = idleSettleMs,
        retryBaseMs = retryBaseMs,
    )

    // Generous on purpose: these waits are for round trips through the fake API and the hub's own timers, and a
    // loaded runner (CI, or the whole suite at once) has been seen to take several times longer than a quiet one.
    private suspend fun awaitUntil(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
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

        // One attempt, refused; the message is neither gone nor marked failed. The row is not patched to running on
        // the server's say-so: the message waits, its card saying so.
        awaitUntil { api.runRequests.size == 1 }
        awaitUntil { followUps.state("bc-1").value.queue.single().isHeld }
        assertThat(agents.agent("bc-1")?.isRunning).isFalse()
        val waiting = followUps.state("bc-1").value.queue.single()
        assertThat(waiting.error).isNull()
        assertThat(waiting.isSending).isFalse()
        assertThat(waiting.busyRefusals).isAtLeast(1)

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
        // Refused as busy — the server still winding the cancelled turn down — the message stays pending on screen,
        // asked again after a pause that grows, and nothing behind it moves. The record the settle reads still calls
        // run-1 running; it does not put the row back to running for the run this device stopped (the spinner back
        // on after a Stop, and the send held ten seconds on it: the flap Bennett saw in 0.3.33).
        awaitUntil { api.runRequests.size >= 2 }
        assertThat(agents.agent("bc-1")?.isRunning).isFalse()
        assertThat(agents.agent("bc-1")?.runStatus).isEqualTo(RunStatus.CANCELLED)
        assertThat(prompts("bc-1").single { it.text == "Actually, stop and do this" }.isPending).isTrue()
        assertThat(sent()).doesNotContain("First")
        assertThat(followUps.sendDiagnostics("bc-1")!!.attempts.map { it.outcome }.distinct()).containsExactly("busy")

        // The turn is over: the steered message is the next thing the server hears, ahead of the other.
        api.busyCreateRun = false
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "CANCELLED")
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
        awaitUntil { followUps.state("bc-1").value.queue.single().isHeld }
        assertThat(agents.agent("bc-1")?.isRunning).isFalse()
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
    fun `a message the server keeps refusing as busy waits with a growing pause, says so, and never marks the row running`() = runBlocking<Unit> {
        // The list has not loaded the chat, and the server calls it busy against every word here: the row is not
        // patched to running on the server's say-so (that status, undone by the next read of the record, is what
        // made the card flap between queued and sending — Bennett, v0.3.33). The message waits, its card saying so.
        api.busyCreateRun = true
        val followUps = repository(retryBaseMs = 50)
        followUps.enqueue("bc-1", "Go on")

        awaitUntil { api.runRequests.size == 1 }
        awaitUntil { followUps.state("bc-1").value.queue.single().isHeld }
        val held = followUps.state("bc-1").value.queue.single()
        assertThat(held.error).isNull()
        assertThat(held.isSending).isFalse()
        assertThat(held.busyRefusals).isEqualTo(1)
        assertThat(held.serverReason).isNull()
        assertThat(agents.agent("bc-1")?.isRunning ?: false).isFalse()
        // Asked again after a pause that doubles each time — 50, 100, 200 ms here — and from the third refusal the
        // server's own words go on the card.
        awaitUntil { followUps.state("bc-1").value.queue.single().busyRefusals >= 3 }
        val thrice = followUps.state("bc-1").value.queue.single()
        assertThat(thrice.serverReason).isEqualTo("Agent is busy.")
        assertThat(thrice.isHeld).isTrue()
        assertThat(thrice.heldSinceMillis).isEqualTo(held.heldSinceMillis)
        // The pauses have grown: after the fourth refusal the next attempt is 400 ms away.
        awaitUntil { followUps.state("bc-1").value.queue.single().busyRefusals >= 4 }
        val refusals = followUps.state("bc-1").value.queue.single().busyRefusals
        val asked = api.runRequests.size
        delay(200)
        assertThat(api.runRequests.size).isEqualTo(asked)

        // The row arrives and says running: the message waits on the row, asking nothing.
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        awaitUntil { agents.agent("bc-1")?.isRunning == true }
        awaitUntil { api.runRequests.size >= asked }
        val askedWithRow = api.runRequests.size
        delay(1_500)
        assertThat(api.runRequests.size).isAtMost(askedWithRow + 1)
        assertThat(followUps.state("bc-1").value.queue.single().busyRefusals).isAtLeast(refusals)
        // The turn ends: the message goes out, once, and the wait is over.
        api.busyCreateRun = false
        awaitUntil { streamer.connections.contains("run-1") }
        finish("run-1")
        awaitUntil { sent().last() == "Go on" && followUps.state("bc-1").value.queue.isEmpty() }
        // The diagnostics carry the attempts — the refusals, then the one acceptance — and the run the server named.
        val diagnostics = followUps.sendDiagnostics("bc-1")!!
        assertThat(diagnostics.attempts.count { it.outcome == "busy" }).isAtLeast(3)
        assertThat(diagnostics.attempts.count { it.outcome == "accepted" }).isEqualTo(1)
        assertThat(diagnostics.attempts.last().outcome).isEqualTo("accepted")
        assertThat(diagnostics.accepted).hasSize(1)
        assertThat(diagnostics.render()).contains("send: decision=")
    }

    @Test
    fun `a message the composer's own send had refused arrives waiting, and its first attempt from here is a pause away`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        api.busyCreateRun = true
        val followUps = repository(retryBaseMs = 300)

        val item = followUps.enqueue("bc-1", "Go on", refusedAsBusy = true)

        // Held from the first frame — the card reads "waiting", not "sending" — and nothing is asked at once.
        assertThat(item.isHeld).isTrue()
        assertThat(item.busyRefusals).isEqualTo(1)
        delay(150)
        assertThat(api.runRequests).isEmpty()
        assertThat(followUps.state("bc-1").value.queue.single().isHeld).isTrue()
        // The pause over, the server is asked; refused again, the message keeps waiting and the count grows.
        awaitUntil { api.runRequests.size == 1 }
        awaitUntil { followUps.state("bc-1").value.queue.single().busyRefusals == 2 }
        assertThat(followUps.state("bc-1").value.queue.single().heldSinceMillis).isEqualTo(item.heldSinceMillis)
        // The turn ends: the message goes out.
        api.busyCreateRun = false
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(followUps.sendDiagnostics("bc-1")!!.attempts.map { it.outcome }).containsExactly("busy", "accepted").inOrder()
    }

    @Test
    fun `a busy refusal in Extended mode hands the message to the account's queue, once`() = runBlocking<Unit> {
        api.busyCreateRun = true
        val handed = mutableListOf<String>()
        val followUps = FollowUpRepository(
            conversations, agents, hub,
            mcpServers = { emptyList() },
            accountQueue = { _, item -> handed += item.previewText; FollowUpRepository.AccountHandoff(null, "fu-test") },
            accountQueueAvailable = { true },
            scope = scope,
            draftSaveDelayMs = 10,
            idleSettleMs = 20_000,
            retryBaseMs = 20,
        )
        followUps.enqueue("bc-1", "Go on")

        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(handed).containsExactly("Go on")
        assertThat(api.runRequests).hasSize(1)
        delay(300)
        // Nothing is sent again: the account has it.
        assertThat(api.runRequests).hasSize(1)
        assertThat(handed).hasSize(1)
        val diagnostics = followUps.sendDiagnostics("bc-1")!!
        assertThat(diagnostics.attempts.map { it.outcome }).containsExactly("busy", "queued-on-account").inOrder()
    }

    @Test
    fun `an idle chat sends at once, whichever order its status sources arrived in`() = runBlocking<Unit> {
        // Two hundred orderings of the sources the decision reads — the row (idle), the account's running set (read,
        // empty), the run record (finished), a stream that only tries to reconnect — none of which calls the agent
        // busy: the message goes out on the first attempt, with no pause and no second request, every time.
        val random = java.util.Random(7)
        repeat(200) { round ->
            val id = "bc-idle-$round"
            // Newest first in the list, so the round's chat is on the first page whatever came before it.
            api.addFinishedAgent(id, "Agent $round", Triple("run-$round", "Earlier", "Done."), firstRunAt = java.time.Instant.parse("2026-04-13T18:30:00Z").plusSeconds(3600L * round).toString())
            val steps = mutableListOf<suspend () -> Unit>(
                { agents.refresh(); awaitUntil { agents.agent(id) != null } },
                // The account's word (Extended mode): this chat finished; another one runs.
                { agents.applyAccountSnapshots(listOf(ComposerSnapshot(id, status = RunStatus.FINISHED), ComposerSnapshot("bc-other", status = RunStatus.RUNNING))) },
                { conversations.attach(id); awaitUntil { conversations.state(id).value.runStatus == RunStatus.FINISHED } },
            )
            steps.shuffle(random)
            // Some of the sources may not have spoken at all when the message is queued.
            steps.take(1 + random.nextInt(steps.size)).forEach { it() }
            val followUps = repository(retryBaseMs = 5_000)
            val before = api.runRequests.size
            val queuedAt = System.nanoTime()
            followUps.enqueue(id, "Now $round")
            awaitUntil(5_000) { api.runRequests.size == before + 1 }
            awaitUntil(5_000) { followUps.state(id).value.queue.isEmpty() }
            val took = (System.nanoTime() - queuedAt) / 1_000_000
            assertWithMessage("round $round took ${took}ms").that(took).isLessThan(2_000)
            assertThat(api.runRequests.last().prompt.text).isEqualTo("Now $round")
            assertThat(followUps.sendDiagnostics(id)!!.decision!!.busy).isFalse()
        }
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

    /**
     * The chat is open on a finished turn while the row, a refresh later, reports a newer run under way. The steer
     * stops the run the row names, not the one the chat happens to be looking at.
     *
     * The load's last read — the agent's record, `GET /v1/agents/{id}`, and the run it names — is held from before
     * the chat opens, so it is in flight across everything below, the way a phone's slow round trip holds it, and
     * the order the steps land in is the test's rather than the scheduler's. Two orderings of that read flaked this
     * test on CI. Landing while the row said run-2, it took the chat to run-2 before the steer (35317258909; the
     * hold fixed that). Landing after the steer's cancel and before the send — the runner loaded, the scheduler's
     * choice — it read run-2 as the server still had it, `RUNNING`, the cancel not yet reached, and folded that
     * into the row: the row went back to running for the very run this device had stopped, and the steered message
     * waited its whole busy recheck on it, twice over, since every settle re-read the same lagging record
     * (35440131899, the 20 s timeout). `AgentRepository.cancelRun` now remembers the run it stopped and every record
     * read there — the detail read, the refresh's verification — reads that run as cancelled (`known`), the way the
     * chat's own run page has since #179. Here the read lands exactly there, between the cancel and the send, and
     * the message goes out at once, on its first attempt, with no settle of the row.
     */
    @Test
    fun `a steer stops the run the row reports when the chat still follows an older one`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val detail = CompletableDeferred<Unit>()
        api.getAgentGate = detail
        conversations.attach("bc-1")
        // The whole load, not its first word: the runs render ahead of the transcript (activeRunId is run-1 from
        // then on), and only the transcript's landing clears isLoading. The record read is the load's tail, held.
        awaitUntil { conversations.state("bc-1").value.let { it.activeRunId == "run-1" && !it.isLoading } }
        awaitUntil { api.getAgentCalls == 1 }
        // A turn started elsewhere: the list learns of run-2 on its next refresh, the open chat has not reloaded.
        api.runs["run-2"] = RunDto(id = "run-2", agentId = "bc-1", status = "RUNNING", createdAt = "2026-04-13T19:30:00.000Z", updatedAt = "2026-04-13T19:30:00.000Z")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(status = "ACTIVE", latestRunId = "run-2", updatedAt = "2026-04-13T19:30:00.000Z")
        api.v0["bc-1"] = api.v0.getValue("bc-1").copy(status = "RUNNING")
        agents.refresh()
        awaitUntil { agents.agent("bc-1")?.let { it.isRunning && it.latestRunId == "run-2" } == true }
        assertThat(conversations.state("bc-1").value.activeRunId).isEqualTo("run-1")
        val followUps = repository()
        // The steered send is held just before its request, once the row has read idle to it.
        mcpGate = CompletableDeferred()
        val item = followUps.enqueue("bc-1", "Now")

        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()

        // The turn cancelled is the one under way, not the finished one the chat happened to be looking at.
        awaitUntil { api.cancelled == listOf("run-2") }
        awaitUntil { mcpCalls.get() == 1 }
        assertThat(agents.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.CANCELLED)
        // The load's record lands now, between the cancel and the send: it names run-2, whose record the server
        // still has as RUNNING. The row learns the record (its lifecycle) but not a status for the run this device stopped.
        detail.complete(Unit)
        api.getAgentGate = null
        awaitUntil { agents.agent("bc-1")!!.lifecycle == AgentLifecycle.ACTIVE }
        val row = agents.agent("bc-1")!!
        assertThat(row.latestRunId).isEqualTo("run-2")
        assertThat(row.isRunning).isFalse()
        assertThat(row.runStatus).isEqualTo(RunStatus.CANCELLED)

        mcpGate!!.complete(Unit)
        // First attempt, at once, no busy recheck: the queue's settle of the row comes only after idleSettleMs
        // (twenty seconds here), so a send inside a few seconds never had one. Not counted in agent reads: the
        // load's own tail makes one more when its row check happens to land after the refresh above moved the row
        // to run-2 — the look for a next run any chat makes on a row that says running — and that look is not a settle.
        awaitUntil(5_000) { sent() == listOf("Now") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(followUps.sendDiagnostics("bc-1")!!.attempts.map { it.outcome }).containsExactly("accepted")
        assertThat(agents.agent("bc-1")!!.let { it.isRunning && it.latestRunId == "run-followup-1" }).isTrue()
    }

    /**
     * The same record read landing after a plain Stop — no steer, nothing queued: the row stays stopped, so the
     * refresh a moment later (which verifies the row against the same lagging record) does not put the spinner
     * back on a turn the user just ended, and a follow-up queued now goes out rather than waiting on the row.
     */
    @Test
    fun `a record read that predates a Stop does not put the row back to running for the stopped run`() = runBlocking<Unit> {
        // Active a minute ago by the clock, so the refresh below verifies the row against its run record.
        api.addRunningAgent("bc-1", "Agent", "run-1", createdAt = java.time.Instant.ofEpochMilli(now - 60_000).toString())
        agents.refresh()
        val detail = CompletableDeferred<Unit>()
        api.getAgentGate = detail
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.let { it.activeRunId == "run-1" && !it.isLoading } }
        awaitUntil { api.getAgentCalls == 1 }

        assertThat(conversations.cancelRun("bc-1", "run-1").isSuccess).isTrue()
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()
        // The detail read lands after the Stop; the server's record of run-1 still reads RUNNING.
        detail.complete(Unit)
        api.getAgentGate = null
        awaitUntil { agents.agent("bc-1")!!.lifecycle == AgentLifecycle.ACTIVE }
        assertThat(agents.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.CANCELLED)
        // So does the refresh's own verification of the row, a second after the Stop: the record it reads is the same.
        val runReads = api.getRunCalls
        agents.refresh()
        assertThat(api.getRunCalls).isGreaterThan(runReads)
        assertThat(agents.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.CANCELLED)
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()

        val followUps = repository()
        followUps.enqueue("bc-1", "Now")
        awaitUntil { sent() == listOf("Now") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        // The next run the server names is followed as it is: the memory is of run-1 alone.
        assertThat(agents.agent("bc-1")!!.let { it.isRunning && it.latestRunId == "run-followup-1" }).isTrue()
        // And a record of that run lands as running, as it should.
        assertThat(agents.loadDetail("bc-1").getOrThrow().isRunning).isTrue()
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

    /**
     * The load a chat opens with reads its run page, renders, and — a read marker and a disk write later — follows
     * the page's latest run. A steer in that gap, of a chat whose turn had moved on already (run-1 over, run-2 begun
     * from another device, neither known here yet): the refused cancel of run-1 settles the row against the record,
     * which names run-2, and run-2 is the turn stopped. The load then lands with the page it read before all that,
     * calling run-1 running, and so does the detail read it launched. Followed as the turn under way, run-1 put the
     * spinner back on a turn long over and held the steered message behind a stream with nothing to say (a replay
     * round trip on a phone; forever here, the fake's stream being silent), and the detail moved the row back to it
     * — the way the steer test above timed out one run in a few hundred under load. Run-1 began before the run this
     * device saw end, so it is over by that alone (`AgentRepository.endedBefore`), a record naming it the latest is
     * a read from before the end, and a load the Stop overtook is read again once it lands.
     */
    @Test
    fun `a steer during a load whose run page predates the turn it stops does not put the chat back on the old turn`() = runBlocking<Unit> {
        val settings = HeldSettings(context)
        val conversations = ConversationRepository(session, agents, PreferencesStore(context, settings), hub, attachments, isForeground = { true }, prefetchLimit = 0, scope = scope)
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val detailReads = api.getAgentCalls
        // The load renders its page, then parks in the read marker it writes for it (the attach's own is the write
        // before); its follow of the page's latest run is behind that — unless the page rendered ahead of the
        // transcript, which follows at once: either order is a phone's, and the invariant below holds for both.
        settings.holdFrom = 2
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.let { it.activeRunId == "run-1" && !it.isLoading } && settings.waiting.get() == 1 && api.getAgentCalls == detailReads + 1 }

        // Meanwhile, on the server: run-1 ended and run-2 began, and neither the row nor the chat has heard of it.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED")
        api.notCancellable += "run-1"
        api.runs["run-2"] = RunDto(id = "run-2", agentId = "bc-1", status = "RUNNING", createdAt = "2026-04-13T19:30:00.000Z", updatedAt = "2026-04-13T19:30:00.000Z")
        api.agents["bc-1"] = api.agents.getValue("bc-1").copy(latestRunId = "run-2", updatedAt = "2026-04-13T19:30:00.000Z")

        // Every frame of the row and of the chat from here: once each has shown the Stop of run-2 (the row cancelled
        // on run-2, the chat cancelled), neither may go back to run-1 as the turn under way — the row naming it its
        // latest or running on anything but the send's run, the chat calling it active. (Its stream may still be
        // opened, for the replay that brings its trace; what it must not say is that the turn is under way.)
        val rowFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val chatFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recorder = scope.launch {
            launch {
                var stopped = false
                agents.state.map { s -> s.agents.firstOrNull { it.id == "bc-1" } }.collect { row ->
                    if (row?.runStatus == RunStatus.CANCELLED && row.latestRunId == "run-2") stopped = true
                    if (stopped && row != null && (row.latestRunId == "run-1" || row.isRunning && row.latestRunId != "run-followup-1")) rowFrames += "${row.runStatus}/${row.latestRunId}"
                }
            }
            launch {
                var stopped = false
                conversations.state("bc-1").collect { chat ->
                    if (chat.runStatus == RunStatus.CANCELLED) stopped = true
                    if (stopped && chat.activeRunId == "run-1" && chat.runStatus?.isActive == true) chatFrames += "${chat.runStatus}/${chat.activeRunId}/streaming=${chat.isStreaming}"
                }
            }
        }
        val followUps = repository(conversations = conversations)
        val item = followUps.enqueue("bc-1", "Now")
        assertThat(followUps.sendNow("bc-1", item.id)).isTrue()
        awaitUntil { api.cancelled == listOf("run-2") }
        val pagesRead = api.listRunsCalls
        settings.release()

        awaitUntil { sent() == listOf("Now") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        // The page the Stop overtook was read again once the load landed; the chat is on the turn the send started.
        awaitUntil { api.listRunsCalls > pagesRead && conversations.state("bc-1").value.activeRunId == "run-followup-1" }
        recorder.cancel()
        assertThat(rowFrames).isEmpty()
        assertThat(chatFrames).isEmpty()
        assertThat(agents.agent("bc-1")!!.latestRunId).isEqualTo("run-followup-1")
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
        // The second attempt comes after the record has been read and the row settled from the hub, a few round
        // trips through the fake API. It used to sit out the repository's whole busy recheck (20 s) whenever the hub
        // saw the run end between the busy answer and the wait for the row to read running — the change it waited
        // for had come and gone — which is what timed this test out on loaded runners; the hold now re-reads the row.
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

    /**
     * The chat is opened and, before its load has answered, the turn is stopped. The load then completes on a record
     * that predates the cancel — the run still running — and starts following that run. That used to put the chat
     * back to running for the very run this device had stopped, and a follow-up (a steer's, or the queue's) then
     * waited on a stream that would report nothing for it: the intermittent 20 s timeout in the sign-out test here.
     *
     * Two orderings of the same load flaked this case afterwards, both fixed in the repository rather than waited
     * out here. The load's run page, read before the Stop, patched the row back to running for the stopped run
     * after the run's own end had settled it — the hub reports an end once — so the queue waited its whole busy
     * recheck (20 s) for a row nothing would settle (`Entry.known`: a record of the run this device cancelled reads
     * cancelled). And the load's follow of its stale latest run landed after a follow-up accepted meanwhile had
     * started the new run's follow, displacing it: the chat sat on the cancelled run, not streaming, over a
     * follow-up under way (`startStreaming(unlessMovedOn)`: a load's run is followed only while it is still the
     * chat's latest).
     */
    @Test
    fun `a follow-up queued after a Stop is not held by a load that re-follows the stopped run`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        api.conversationGate = CompletableDeferred()
        val detailsBefore = api.getAgentCalls
        conversations.attach("bc-1")
        awaitUntil { api.conversationCalls == 1 }
        assertThat(conversations.cancelRun("bc-1", "run-1").isSuccess).isTrue()

        api.conversationGate!!.complete(Unit)
        awaitUntil { conversations.state("bc-1").value.let { it.isStreaming && it.activeRunId == "run-1" } }
        // The word from this device stands: the run was stopped, and starting to follow it says nothing to the contrary.
        assertThat(conversations.state("bc-1").value.runStatus).isEqualTo(RunStatus.CANCELLED)
        // Nor does the load's own patch of the row from that record (it precedes the detail read it launches): the
        // row stays idle for the run this device stopped, so nothing queued waits on it.
        awaitUntil { !conversations.state("bc-1").value.isLoading && api.getAgentCalls > detailsBefore }
        assertThat(agents.agent("bc-1")!!.isRunning).isFalse()
        assertThat(agents.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.CANCELLED)

        val followUps = repository()
        followUps.enqueue("bc-1", "Now")
        // The stopped run's stream reports its end. Whether the message went out before that (the row was idle from
        // the Stop on) or on it, it goes out now — never after the queue's busy recheck.
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.CANCELLED, "", 5_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil(5_000) { sent() == listOf("Now") }
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() }
        // The chat is on the new run by the time the queue moves on — its follow started before the send was answered
        // — and stays there: the load's late follow of the stopped run does not displace it.
        val settled = conversations.state("bc-1").value
        assertThat(settled.activeRunId).isEqualTo("run-followup-1")
        assertThat(settled.isStreaming).isTrue()
        assertThat(settled.runStatus).isEqualTo(RunStatus.RUNNING)
        delay(200)
        val later = conversations.state("bc-1").value
        assertThat(later.activeRunId).isEqualTo("run-followup-1")
        assertThat(later.isStreaming).isTrue()
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
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        api.failCreateRun = true
        val followUps = repository(scope = testScope)

        val first = followUps.enqueue("bc-1", "First")
        followUps.enqueue("bc-1", "Second")

        followUps.state("bc-1").first { it.queue.first().error != null }
        assertThat(api.runRequests).hasSize(1)
        assertThat(followUps.state("bc-1").value.queue.map { it.text }).containsExactly("First", "Second").inOrder()
        assertThat(followUps.state("bc-1").value.queue[1].error).isNull()

        api.failCreateRun = false
        followUps.retry("bc-1", first.id)
        followUps.state("bc-1").first { sent().count { it == "First" } == 2 && it.queue.map { q -> q.text } == listOf("Second") }
        } finally {
            testScope.cancel()
        }
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
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            api.addIdleAgent("bc-1", "Agent", "run-0")
            agents.refresh()
            api.failCreateRun = true
            val followUps = repository(scope = testScope)
            val stuck = followUps.enqueue("bc-1", "Stuck")

            followUps.state("bc-1").first { it.queue.single().error != null }
            assertThat(api.runRequests).hasSize(1)
            assertThat(FollowUpRepositoryTestHelper.dispatcherActive(followUps, "bc-1")).isFalse()

            followUps.enqueue("bc-1", "Second")
            api.failCreateRun = false
            followUps.retry("bc-1", stuck.id)
            assertThat(FollowUpRepositoryTestHelper.dispatcherActive(followUps, "bc-1")).isTrue()
            followUps.state("bc-1").first { sent().count { it == "Stuck" } == 2 && it.queue.map { q -> q.text } == listOf("Second") }
        } finally {
            testScope.cancel()
        }
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
