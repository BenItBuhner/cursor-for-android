package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import java.io.File
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A message sent while the coordinator is mid-turn goes into the account's queue: the card above the composer is what
 * shows it, until the account delivers it and the transcript shows it under the run the account started on it. At
 * any instant it is in exactly one of the two places.
 *
 * Bennett's frame of 2026-09-20 (v0.3.48, the Polymarket Project): his message as a sent bubble with "Starting…"
 * under it and, at the same instant, on the card with the cloud glyph. The transcript had filed it under the new run
 * (#244's `adoptDelivered`) while the card still reflected a read of the account's queue from before the run
 * started — the poll is ten seconds apart, and the account's own list trails the run it starts. His words: "this
 * message here is in a queue while it's also simultaneously already sent. We have to figure out which one it is and
 * make sure it is not both."
 *
 * The rule pinned here (see `QueuePlacement`): the card is projected from the very frame the transcript is drawn
 * from — `controls.placed(state.queuePlacement)` — so the moment a frame shows the message, that frame's card does
 * not, whatever the last queue read said; the account's queue is read again the moment the transcript files the
 * message; until the transcript shows it the card does, from this device's own knowledge when the account's list
 * has not caught up on either side; and a message the transcript filed under a run that ended while the account
 * still listed it — its copy gone from the conversation — goes back on the card, said so. The poll is driven stale
 * by seconds on either side of the run's start; the account's list is made to trail the start too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueuedMessagePlacementTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - TURNS * LongProject.TURN_SPACING_MS
    private lateinit var turns: List<LongProject.Turn>
    private val live get() = turns.last()
    private var recorder: Job? = null

    /** What the screen pairs: the account's queue as last read, and the transcript's frame, with when the pair was seen. */
    private class Frame(val controls: ConversationControls, val state: ConversationState, val atNanos: Long) {
        /** The card as the screen draws it from this frame (see `ConversationScreen`): the queue projected through the frame's placement. */
        val card: List<String> get() = controls.placed(state.queuePlacement).queue.map { it.id }
        fun cardShows(followupId: String, text: String) = controls.placed(state.queuePlacement).queue.any { it.id == followupId || QueuePlacement.textKey(it.text) == QueuePlacement.textKey(text) }
        fun transcriptShows(text: String) = state.items.any { it is UserMessage && QueuePlacement.textKey(it.text) == QueuePlacement.textKey(text) }
    }

    private val frames = CopyOnWriteArrayList<Frame>()

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 150L..350L).start()
        turns = LongProject.turns(firstAt, turns = TURNS)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            server.logs[turn.runId] = turn.log
        }
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(live.startedAt), latestRunId = live.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        // The documented path, as on Bennett's phone: the record refused in the server's words.
        server.outage(Route.RecordState, Fault.Status(404, "unimplemented", "GetLatestAgentConversationState has been removed"))
        // The live run's stream delivers everything it has and drops; the hub keeps coming back to it.
        server.outage(Route.Stream, Fault.StreamCut(events = live.log.size), path = "/${live.runId}/")
    }

    @After
    fun tearDown() {
        recorder?.cancel()
        rig?.let { it.steering.detach(agentId); it.close() }
        server.close()
    }

    /** The rig, its account queue read every [pollMs]: the card's staleness (production: 10 s). */
    private fun rig(pollMs: Long): FaultRig = FaultRig(server.baseUrl, folder.newFolder("rig-${System.nanoTime()}"), readTimeoutMs = 8_000L, extended = true, queuePollMs = pollMs).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private val state get() = rig!!.conversations.state(agentId).value

    private fun explain(label: String) {
        val s = state
        val c = rig!!.steering.state(agentId).value
        println("== $label: items=${s.items.size} run=${s.runStatus} streaming=${s.isStreaming} active=${s.activeRunId} loading=${s.isLoading} placement=${s.queuePlacement}")
        println("   card(raw)=${c.queue.map { "${it.id}:${it.text.take(16)}" }} load=${c.queueLoad} card(placed)=${c.placed(s.queuePlacement).queue.map { "${it.id}${it.note?.let { n -> "[$n]" } ?: ""}" }}")
        println("   prompts=${s.items.filterIsInstance<UserMessage>().map { it.text.take(20) }}")
        println("   server: pending=${server.pending[agentId]?.map { "${it.followupId}@${it.consumedAtMs}" }} delivered=${server.delivered} requests=${server.seen.groupingBy { it.route }.eachCount()}")
        rig!!.conversations.loadDiagnostics(agentId)?.let { d -> println("   load: source=${d.source} runs=${d.runsLoaded} live=${d.liveRunId} following=${d.following} stream=${d.liveStream}") }
    }

    private suspend fun FaultRig.awaitUntilOr(timeoutMs: Long, label: String, condition: suspend () -> Boolean) {
        try { awaitUntil(timeoutMs, condition) } catch (t: Throwable) { explain("TIMEOUT waiting for $label"); throw t }
    }

    private suspend fun FaultRig.open() {
        agents.refresh()
        conversations.attach(agentId)
        steering.attach(agentId)
        recorder = scope.launch {
            combine(steering.state(agentId), conversations.state(agentId)) { c, s -> c to s }.collect { (c, s) -> frames += Frame(c, s, System.nanoTime()) }
        }
        awaitUntil(60_000) { state.let { !it.isLoading && it.isStreaming } && steering.state(agentId).value.isQueueAvailable }
    }

    /** What the composer does mid-turn (see `ConversationViewModel.queueOnAccount`): the message staged unshown, filed with the account under an id minted here. */
    private suspend fun FaultRig.queueOnAccount(text: String): String {
        val followupId = AccountFollowup.newId()
        val staged = conversations.stageFollowUp(agentId, text, show = false)
        conversations.sendStagedVia(agentId, staged, followupId = followupId) {
            steering.sendFollowup(agentId, AccountFollowup(text = text, followupId = followupId)).getOrThrow()
        }.getOrThrow()
        return followupId
    }

    /** Waits for the next read of the account's queue to complete, so the read after it is a full poll interval away. */
    private suspend fun FaultRig.awaitNextQueueRead() {
        val before = server.requests(Route.QueueList).size
        awaitUntil(30_000) { server.requests(Route.QueueList).size > before }
        // The answer is on its way back: give it the round trip.
        delay(server.rttMillis.last + 100)
    }

    /**
     * The invariant, over every frame from the first the card showed the message on: never in both places, never in
     * neither. Frames before [fromNanos] are ignored (the message was not yet sent).
     */
    private fun assertOnePlace(followupId: String, text: String, fromNanos: Long = 0L) {
        val since = frames.filter { it.atNanos >= fromNanos }
        val first = since.indexOfFirst { it.cardShows(followupId, text) }
        assertWithMessage("the card never showed the message").that(first).isAtLeast(0)
        since.forEachIndexed { i, frame ->
            val card = frame.cardShows(followupId, text)
            val transcript = frame.transcriptShows(text)
            assertWithMessage("frame $i: on the card and in the transcript at once — card=${frame.card} items=${frame.state.items.filterIsInstance<UserMessage>().map { it.text.take(24) }} placement=${frame.state.queuePlacement}").that(card && transcript).isFalse()
            if (i >= first) assertWithMessage("frame $i: in neither place — card=${frame.card} placement=${frame.state.queuePlacement}").that(card || transcript).isTrue()
        }
    }

    // -- (1) the ordinary case: the list trails the run the account starts, the poll is seconds behind on both sides ---

    @Test
    fun `a queued message moves from the card to the transcript in one frame although the queue read is seconds stale on either side`() = runBlocking<Unit> {
        // The account's own list keeps naming the followup for four seconds after the run starts on it, and the app
        // reads the list every four seconds: on either side of the start, the card's word is seconds old.
        server.queueLagMs = 4_000L
        val rig = rig(pollMs = 4_000L)
        rig.open()
        val sentAt = System.nanoTime()
        val followupId = rig.queueOnAccount(MESSAGE)
        // On the card, from the moment the account took it — and from the account's list once read.
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.any { it.id == followupId } }
        assertThat(state.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
        // A poll completes; the turn ends and the account starts the next run on the message right after it, so the
        // next poll is a full interval away and the one before it said "still queued".
        rig.awaitNextQueueRead()
        server.endTurn(agentId)
        val next = server.deliverNext(agentId, log = LongProject.turns(firstAt, turns = TURNS + 1).last().log)!!
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/${next.id}/")
        // The transcript files it under the new run …
        val readsBeforeFiling = server.requests(Route.QueueList).size
        rig.awaitUntilOr(45_000, "line") { state.items.any { it is UserMessage && it.text == MESSAGE } }
        // … and the account's queue is read again at once — within a second, not at the poll's four.
        rig.awaitUntilOr(1_000, "line") { server.requests(Route.QueueList).size > readsBeforeFiling }
        // The account drops it from its list once the lag has passed; the card is empty, the transcript has it, once.
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.none { it.id == followupId } }
        delay(1_500)
        assertOnePlace(followupId, MESSAGE, fromNanos = sentAt)
        val s = state
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        // The account has let it go: nothing left to keep off the card, and the card is empty on its own account.
        assertThat(rig.steering.state(agentId).value.queue).isEmpty()
        assertThat(rig.steering.state(agentId).value.placed(s.queuePlacement).queue).isEmpty()
        // Its run: the message stands right before the run the account started on it.
        val at = s.items.indexOfFirst { it is UserMessage && it.text == MESSAGE }
        assertThat(s.items.drop(at + 1).firstOrNull { it !is UserMessage }?.id?.startsWith("activity-${next.id}-")).isTrue()
    }

    // -- (2) the list drops it before the transcript has it: the card keeps it from this device's own knowledge -------

    @Test
    fun `when the account drops the message from its list before the transcript shows it, the card keeps it until the transcript does`() = runBlocking<Unit> {
        // No lag on the account's side, and the read lands right after the delivery: the list says "gone" while the
        // app has yet to look for the run the message started.
        server.queueLagMs = 0L
        val rig = rig(pollMs = 1_500L)
        rig.open()
        val sentAt = System.nanoTime()
        val followupId = rig.queueOnAccount(MESSAGE)
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.any { it.id == followupId } }
        server.endTurn(agentId)
        val next = server.deliverNext(agentId, log = LongProject.turns(firstAt, turns = TURNS + 1).last().log)!!
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/${next.id}/")
        rig.awaitUntilOr(45_000, "line") { state.items.any { it is UserMessage && it.text == MESSAGE } }
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.none { it.id == followupId } }
        delay(1_500)
        assertOnePlace(followupId, MESSAGE, fromNanos = sentAt)
        // Somewhere along the way the list had dropped it while the transcript had not yet shown it: the card carried it, saying so.
        val carried = frames.filter { it.atNanos >= sentAt && it.controls.queue.none { q -> q.id == followupId } && !it.transcriptShows(MESSAGE) && it.cardShows(followupId, MESSAGE) }
        assertWithMessage("the card never had to carry the message on its own").that(carried).isNotEmpty()
        // Once the list had said "gone", the card said why it still showed the message.
        assertThat(carried.any { f -> f.controls.queueLoad == com.cursorforandroid.domain.QueueLoad.Loaded && f.controls.placed(f.state.queuePlacement).queue.single { it.id == followupId }.note == QueuePlacement.DELIVERING_NOTE }).isTrue()
    }

    // -- (3) steered: delivered into the turn under way ---------------------------------------------------------------

    @Test
    fun `a queued message delivered into the running turn as a steer follows the same rule`() = runBlocking<Unit> {
        server.queueLagMs = 3_000L
        val rig = rig(pollMs = 3_000L)
        rig.open()
        val sentAt = System.nanoTime()
        val followupId = rig.queueOnAccount(MESSAGE)
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.any { it.id == followupId } }
        rig.awaitNextQueueRead()
        // "Steer now" on the card: the account promotes the queued message into the running turn.
        rig.steering.promotePending(agentId, followupId).getOrThrow()
        rig.awaitUntilOr(45_000, "line") { state.items.any { it is UserMessage && it.text == MESSAGE } }
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.none { it.id == followupId } }
        delay(1_500)
        assertOnePlace(followupId, MESSAGE, fromNanos = sentAt)
        val s = state
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        // Among the running turn's rows, not ahead of it: the run's activity is on both sides of it or before it.
        val at = s.items.indexOfFirst { it is UserMessage && it.text == MESSAGE }
        assertThat(s.items.take(at).any { it.id.startsWith("activity-${live.runId}-") }).isTrue()
        assertThat(s.activeRunId).isEqualTo(live.runId)
    }

    // -- (4) refused as busy from this device's queue: handed to the account, the same rule -----------------------------

    @Test
    fun `a message this device's queue hands to the account when refused as busy follows the same rule`() = runBlocking<Unit> {
        server.queueLagMs = 3_000L
        // The chat is idle as far as this device knows: every turn over, nothing followed.
        server.runs[live.runId] = server.runs.getValue(live.runId).copy(status = "FINISHED", durationMs = 40_000L, updatedAt = LongProject.iso(live.startedAt + 40_000L))
        server.logs[live.runId] = live.log + ("result" to """{"runId":"${live.runId}","status":"FINISHED","text":"","durationMs":40000}""")
        server.agents[agentId] = server.agents.getValue(agentId).copy(status = "IDLE")
        server.v0[agentId] = server.v0.getValue(agentId).copy(status = "FINISHED")
        val rig = rig(pollMs = 3_000L)
        rig.agents.refresh()
        rig.conversations.attach(agentId)
        rig.steering.attach(agentId)
        recorder = rig.scope.launch {
            combine(rig.steering.state(agentId), rig.conversations.state(agentId)) { c, s -> c to s }.collect { (c, s) -> frames += Frame(c, s, System.nanoTime()) }
        }
        rig.awaitUntilOr(60_000, "the chat open") { state.let { !it.isLoading && it.items.isNotEmpty() } && rig.steering.state(agentId).value.isQueueAvailable }
        // Meanwhile the account has started a turn this device has not seen (a worker's report came in): the server
        // refuses `POST /runs` as busy against everything the composer read as idle.
        val hidden = server.deliverNext(agentId) ?: run {
            server.pending.getOrPut(agentId) { java.util.concurrent.CopyOnWriteArrayList() } += FaultServer.Pending("fu-worker-report", LongProject.injected("Worker report 9", "bc-worker-9", rig.now), rig.now)
            server.deliverNext(agentId)!!
        }
        server.outage(Route.Stream, Fault.StreamCut(events = 1), path = "/${hidden.id}/")
        val sentAt = System.nanoTime()
        // Sent as a plain follow-up: `POST /runs` is refused as busy, and the device's queue hands it to the account.
        rig.followUps.enqueue(agentId, MESSAGE)
        rig.awaitUntilOr(30_000, "the account to take the message") { server.pending[agentId]?.any { it.text == MESSAGE } == true }
        val followupId = server.pending.getValue(agentId).single { it.text == MESSAGE }.followupId
        rig.awaitUntilOr(20_000, "the card") { frames.lastOrNull()?.cardShows(followupId, MESSAGE) == true }
        rig.awaitUntilOr(20_000, "the device's queue to let go") { rig.followUps.state(agentId).value.queue.isEmpty() }
        rig.awaitNextQueueRead()
        server.endTurn(agentId)
        val next = server.deliverNext(agentId, log = LongProject.turns(firstAt, turns = TURNS + 1).last().log)!!
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/${next.id}/")
        rig.awaitUntilOr(45_000, "line") { state.items.any { it is UserMessage && it.text == MESSAGE } }
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.none { it.id == followupId } }
        delay(1_500)
        assertOnePlace(followupId, MESSAGE, fromNanos = sentAt)
        assertThat(state.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
    }

    // -- (5) put back: the run it was filed under ended, the account still lists it, the conversation no longer has it --

    @Test
    fun `a message filed under a run that ended while the account still lists it, its copy gone, goes back on the card and says so`() = runBlocking<Unit> {
        // The account never drops the followup from its list.
        server.queueLagMs = Long.MAX_VALUE / 4
        val rig = rig(pollMs = 1_500L)
        rig.open()
        val sentAt = System.nanoTime()
        val followupId = rig.queueOnAccount(MESSAGE)
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.any { it.id == followupId } }
        server.endTurn(agentId)
        val next = server.deliverNext(agentId, log = LongProject.turns(firstAt, turns = TURNS + 1).last().log)!!
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/${next.id}/")
        rig.awaitUntilOr(45_000, "line") { state.items.any { it is UserMessage && it.text == MESSAGE } }
        rig.awaitUntilOr(10_000, "line") { state.queuePlacement.deliveredIds.contains(followupId) }
        // The run ends; the conversation is rewound past the message (it is no longer in `/v0`), and the account still
        // lists the followup as waiting: the run did not carry it after all.
        server.transcripts[agentId] = server.transcripts.getValue(agentId).filterNot { it.text == MESSAGE }
        server.endTurn(agentId)
        rig.conversations.reload(agentId)
        rig.awaitUntilOr(30_000, "the run seen over") { state.runStatus?.isActive == false && !state.isLoading }
        // A read that finds the run over marks the moment; the put-back waits for a read begun a couple of seconds
        // after it (the app's clock, frozen in this rig, is moved by hand as the seconds pass).
        rig.awaitNextQueueRead()
        rig.now += 3_000
        rig.awaitUntilOr(30_000, "the message put back") { state.queuePlacement.returned.containsKey(followupId) }
        delay(1_000)
        val s = state
        assertThat(s.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
        val card = rig.steering.state(agentId).value.placed(s.queuePlacement).queue
        assertThat(card.map { it.id }).containsExactly(followupId)
        assertThat(card.single().note).isEqualTo(QueuePlacement.RETURNED_NOTE)
        assertOnePlace(followupId, MESSAGE, fromNanos = sentAt)
    }

    // -- (6) the composer's own message, its attachments with it: bubble, then card, then the run it is filed under ----

    /**
     * The chat's composer shows the message at the tap, its picture and its file on the bubble, and sends behind it
     * (`OutgoingMessages`); the account queues it. The attachments go where the message goes, and the message is in
     * one place at every step: on the bubble while the send is out — the account's list may already name it, the
     * reply still on its way back, and the card leaves that row out; then the bubble comes down and the card takes
     * the message in the one frame, its row naming the file and counting the picture; then the run the account
     * starts on it gets the message with both attachments under it, moved off the staging area.
     */
    @Test
    fun `a message the composer shows ahead of the request keeps its attachments from the bubble to the card to the run it is filed under`() = runBlocking<Unit> {
        server.queueLagMs = 2_000L
        val rig = rig(pollMs = 1_000L)
        rig.open()
        val sentAt = System.nanoTime()
        val followupId = AccountFollowup.newId()
        val picture = PromptImage(png(), "image/png")
        val spec = PromptFile(ByteArray(2_048) { 0x25 }, "Q3-billing-spec.pdf", "application/pdf")
        // Shown ahead of the request, as the composer does at the tap: the bubble carries both from its first frame.
        val staged = rig.conversations.stageFollowUp(agentId, MESSAGE, images = listOf(picture), files = listOf(spec))
        val bubble = state.items.filterIsInstance<UserMessage>().single { it.text == MESSAGE }
        assertThat(bubble.attachments.map { it.isFile }).containsExactly(false, true).inOrder()
        assertThat(state.queuePlacement.waiting).isEmpty()
        // The send, the composer's way (the bubble stays up on a failure), on a slow link: the account takes the
        // message at once, its reply is three seconds coming back. The link is quick again the moment the request has
        // landed, so the queue read that follows names the message while the bubble still stands.
        val addsBefore = server.requests(Route.QueueAdd).size
        server.rttMillis = 3_000L..3_000L
        val send = async {
            rig.conversations.sendStagedVia(agentId, staged, followupId = followupId, discardOnFailure = false) {
                rig.steering.sendFollowup(agentId, AccountFollowup(text = MESSAGE, followupId = followupId)).getOrThrow()
            }.getOrThrow()
        }
        rig.awaitUntilOr(10_000, "the request to land") { server.requests(Route.QueueAdd).size > addsBefore }
        server.rttMillis = 150L..350L
        rig.awaitUntilOr(10_000, "the list to name it") { rig.steering.state(agentId).value.queue.any { it.id == followupId } }
        // The bubble is the message's place: the list names it, the card does not show it.
        assertThat(send.isActive).isTrue()
        val inFlight = state
        assertThat(inFlight.items.any { it is UserMessage && it.text == MESSAGE }).isTrue()
        assertThat(inFlight.queuePlacement.shownIds).containsExactly(followupId)
        assertThat(rig.steering.state(agentId).value.placed(inFlight.queuePlacement).queue).isEmpty()
        send.await()
        // Queued: in the same frame the bubble is down and the card has the message — with what it carries.
        val queued = state
        assertThat(queued.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
        assertThat(queued.queuePlacement.shownIds).isEmpty()
        val row = queued.queuePlacement.waiting.single()
        assertThat(row.id).isEqualTo(followupId)
        assertThat(row.files.map { it.name }).containsExactly("Q3-billing-spec.pdf")
        assertThat(row.files.single().mimeType).isEqualTo("application/pdf")
        assertThat(row.imageCount).isEqualTo(1)
        assertThat(rig.steering.state(agentId).value.placed(queued.queuePlacement).queue.map { it.id }).containsExactly(followupId)
        // From the first frame the bubble stood on: never a frame with the message in neither place, nor in both — the
        // recorder saw the bubble, or the card, at every step. (A frame recorded between the tap and the bubble's publish
        // — a queue read's — has the message nowhere yet, rightly.)
        val sinceSent = frames.filter { it.atNanos >= sentAt }
        val firstShown = sinceSent.indexOfFirst { it.transcriptShows(MESSAGE) }
        assertWithMessage("the recorder never saw the bubble").that(firstShown).isAtLeast(0)
        sinceSent.drop(firstShown).forEachIndexed { i, frame ->
            val card = frame.cardShows(followupId, MESSAGE)
            val transcript = frame.transcriptShows(MESSAGE)
            assertWithMessage("frame $i: in neither place — card=${frame.card} placement=${frame.state.queuePlacement}").that(card || transcript).isTrue()
            assertWithMessage("frame $i: on the card and in the transcript at once — card=${frame.card} placement=${frame.state.queuePlacement}").that(card && transcript).isFalse()
        }
        // The turn ends; the account starts the next run on the message — and keeps listing the message for a while
        // after, its list seconds behind its runs. A read landing then, the run known and the message still listed,
        // must not take the message for one waiting behind its own run (it would be filed into that run as a steer,
        // after the run's first rows, and drawn twice while the run is followed).
        // The transcript is out of reach meanwhile (the filing reads it once the run is known; here that read hangs
        // to the timeout), so the reads land with the run known and the message unfiled: the case a slow transcript
        // makes of every delivery.
        server.queueLagMs = 20_000L
        server.outage(Route.Conversation, Fault.Silence())
        server.endTurn(agentId)
        val next = server.deliverNext(agentId, log = LongProject.turns(firstAt, turns = TURNS + 1).last().log)!!
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/${next.id}/")
        rig.awaitUntilOr(30_000, "the new run to be known") { rig.conversations.loadDiagnostics(agentId)?.runsLoaded == TURNS + 1 }
        rig.awaitNextQueueRead()
        rig.awaitNextQueueRead()
        assertWithMessage("the list should still name the message: its lag is the case").that(rig.steering.state(agentId).value.queue.map { it.id }).contains(followupId)
        assertThat(state.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
        // The transcript answers again, and the list lets the message go: it is filed where the transcript has it.
        server.clear(Route.Conversation)
        server.queueLagMs = 0L
        rig.awaitUntilOr(45_000, "line") { state.items.any { it is UserMessage && it.text == MESSAGE } }
        rig.awaitUntilOr(20_000, "line") { rig.steering.state(agentId).value.queue.none { it.id == followupId } }
        delay(1_500)
        assertOnePlace(followupId, MESSAGE, fromNanos = sentAt)
        // Once, ahead of the run the account started on it — the prompt that started it, not a steer among its rows.
        val s = state
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        val at = s.items.indexOfFirst { it is UserMessage && it.text == MESSAGE }
        assertThat(s.items.drop(at + 1).firstOrNull { it !is UserMessage }?.id?.startsWith("activity-${next.id}-")).isTrue()
        // Filed under the run the account started on it, its attachments with it — the copies moved under that run.
        val filed = s.items.filterIsInstance<UserMessage>().single { it.text == MESSAGE }
        assertThat(filed.attachments.map { it.isFile }).containsExactly(false, true).inOrder()
        val pdf = filed.attachments.single { it.isFile }
        assertThat(pdf.name).isEqualTo("Q3-billing-spec.pdf")
        assertThat(pdf.mimeType).isEqualTo("application/pdf")
        assertThat(pdf.sizeBytes).isEqualTo(2_048L)
        filed.attachments.forEach { attachment ->
            assertWithMessage("${attachment.path} should be a file under the run's directory").that(File(attachment.path).isFile).isTrue()
            assertThat(File(attachment.path).parentFile?.name).isEqualTo(next.id)
        }
        assertThat(File(pdf.path).readBytes()).isEqualTo(spec.bytes)
    }

    // -- (7) Bennett's frames: the chat idle, the account starts the run at once, the poll lands in the round trip ------

    /**
     * Bennett's frames of 2026-09-20 23:24 and 2026-09-21 09:18: the chat idle, his message (the second with one
     * image) drawn as the sent bubble under "Starting…" and, at the same instant, on the account-queue card. The
     * account starts the run on such a message at once and keeps listing it for a moment after; the queue poll
     * (every 10 s) that lands inside the send's round trip names it, and the next one is a poll away. The card must
     * leave the row out from the tap through the filing until a read no longer names it: the bubble is the one place.
     */
    @Test
    fun `a message the account starts the run on at once is never on the card beside its bubble, before or after the reply`() = runBlocking<Unit> {
        // The account's list names a started message for six seconds; the app polls every second.
        server.queueLagMs = 6_000L
        val rig = rig(pollMs = 1_000L)
        rig.open()
        // The turn ends: the chat is idle, as it was under both of Bennett's frames ("Worked 55s", "Worked 59s").
        server.endTurn(agentId)
        rig.awaitUntilOr(30_000, "the turn seen over") { state.runStatus?.isActive == false && !state.isStreaming }
        val sentAt = System.nanoTime()
        val followupId = AccountFollowup.newId()
        val picture = PromptImage(png(), "image/png")
        val spec = PromptFile(ByteArray(1_024) { 0x25 }, "Q3-billing-spec.pdf", "application/pdf")
        val staged = rig.conversations.stageFollowUp(agentId, MESSAGE, images = listOf(picture), files = listOf(spec))
        assertThat(state.items.filterIsInstance<UserMessage>().single { it.text == MESSAGE }.attachments).hasSize(2)
        // The send on a slow link: the account takes the message and starts the run at once, its reply three seconds
        // coming back; quick again once the request has landed, so the poll's read lands inside the round trip.
        val addsBefore = server.requests(Route.QueueAdd).size
        server.rttMillis = 3_000L..3_000L
        val send = async {
            rig.conversations.sendStagedVia(agentId, staged, followupId = followupId, discardOnFailure = false) {
                rig.steering.sendFollowup(agentId, AccountFollowup(text = MESSAGE, followupId = followupId)).getOrThrow()
            }.getOrThrow()
        }
        rig.awaitUntilOr(10_000, "the request to land") { server.requests(Route.QueueAdd).size > addsBefore }
        server.rttMillis = 150L..350L
        rig.awaitUntilOr(10_000, "the list to name it") { rig.steering.state(agentId).value.queue.any { it.id == followupId } }
        // Before the reply: the bubble stands, pending; the list names the message; the card does not show it.
        assertThat(send.isActive).isTrue()
        val inFlight = state
        assertThat(inFlight.items.filterIsInstance<UserMessage>().single { it.text == MESSAGE }.isPending).isTrue()
        assertThat(inFlight.queuePlacement.shownIds).containsExactly(followupId)
        assertThat(rig.steering.state(agentId).value.placed(inFlight.queuePlacement).queue).isEmpty()
        send.await()
        val run = server.delivered.single { it.first == followupId }.second
        // After the reply: the bubble is the run's — filed, no longer pending, its attachments moved under the run —
        // the list still names the message, and the card still does not show it.
        rig.awaitUntilOr(10_000, "the bubble filed") { state.items.filterIsInstance<UserMessage>().singleOrNull { it.text == MESSAGE }?.isPending == false }
        val filed = state
        assertThat(rig.steering.state(agentId).value.queue.map { it.id }).contains(followupId)
        assertWithMessage("Bennett's frame: the filed bubble under \"Starting…\" and the row on the card at once").that(rig.steering.state(agentId).value.placed(filed.queuePlacement).queue).isEmpty()
        assertThat(filed.queuePlacement.deliveredIds).containsExactly(followupId)
        assertThat(filed.queuePlacement.shownIds).isEmpty()
        val message = filed.items.filterIsInstance<UserMessage>().single { it.text == MESSAGE }
        assertThat(message.attachments.map { it.isFile }).containsExactly(false, true).inOrder()
        message.attachments.forEach { assertThat(File(it.path).parentFile?.name).isEqualTo(run) }
        // The list lets it go; the card is empty on its own account, the message once in the transcript.
        rig.awaitUntilOr(20_000, "the list to let it go") { rig.steering.state(agentId).value.queue.none { it.id == followupId } }
        rig.awaitUntilOr(10_000, "the delivery confirmed") { state.queuePlacement.deliveredIds.isEmpty() }
        delay(1_200)
        val s = state
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertThat(rig.steering.state(agentId).value.placed(s.queuePlacement).queue).isEmpty()
        // From the bubble's first frame on: never on the card and in the transcript at once, never in neither.
        val sinceSent = frames.filter { it.atNanos >= sentAt }
        val firstShown = sinceSent.indexOfFirst { it.transcriptShows(MESSAGE) }
        assertWithMessage("the recorder never saw the bubble").that(firstShown).isAtLeast(0)
        sinceSent.drop(firstShown).forEachIndexed { i, frame ->
            val card = frame.cardShows(followupId, MESSAGE)
            val transcript = frame.transcriptShows(MESSAGE)
            assertWithMessage("frame $i: on the card and in the transcript at once — card=${frame.card} placement=${frame.state.queuePlacement}").that(card && transcript).isFalse()
            assertWithMessage("frame $i: in neither place — card=${frame.card} placement=${frame.state.queuePlacement}").that(card || transcript).isTrue()
        }
    }

    /** A real 4 x 3 PNG, one colour: the platform's decoder reads its size off it, as it would off a paste (a fake decodes to nothing). */
    private fun png(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x03,
        0x08, 0x06, 0x00, 0x00, 0x00, 0xB4.toByte(), 0xF4.toByte(), 0xAE.toByte(), 0xC6.toByte(), 0x00, 0x00, 0x00, 0x15, 0x49, 0x44, 0x41, 0x54, 0x78, 0xDA.toByte(),
        0x63, 0x34, 0xA9.toByte(), 0xF8.toByte(), 0xF6.toByte(), 0x9F.toByte(), 0x01, 0x09, 0x30, 0x31, 0xA0.toByte(), 0x01, 0x0C, 0x01, 0x00, 0x7E, 0xDD.toByte(),
        0x02, 0xA7.toByte(), 0x02, 0xAD.toByte(), 0x36, 0x36, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    private companion object {
        const val TURNS = 8
        const val MESSAGE = "Just tell me if it is better or worse overall for us. I'm not looking for a yes and I'm looking for a decisive direction."
    }
}
