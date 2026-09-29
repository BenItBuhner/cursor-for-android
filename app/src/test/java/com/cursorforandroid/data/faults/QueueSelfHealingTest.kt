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
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueueLoad
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.Executors

/**
 * The queue card lets go of every message the account has consumed, whatever went wrong on the way to filing it.
 *
 * Bennett's frame of 2026-09-29 (v0.4.22, the Polymarket Project): two messages on the card as "Being delivered to
 * the agent." a quarter of an hour after the account had sent them, one of them drawn in the transcript above with
 * its whole turn under it. The account had let go of both — that is what the note says — and only this device's
 * copy kept them on the card: the one adoption asked when the list let go could not read `/v0` (or ran for another
 * message at that moment), nothing asked again, the Beta engine's record drew the message without the card ever
 * looking at it, and a message given up on was left on disk to come back at the next start.
 *
 * Pinned here: the record's drawing of a message the account let go of takes it off the card in that frame; a failed
 * transcript read is asked again by the next read of the account's queue; a message the account let go of that no
 * transcript ever files leaves after a grace; a message the account still lists is never let go, a read that missed
 * it notwithstanding; and a message given up on stays given up on across a restart.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueueSelfHealingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - TURNS * LongProject.TURN_SPACING_MS
    private val turns = LongProject.turns(firstAt, turns = TURNS)
    private val live get() = turns.last()
    private var recorder: Job? = null
    private val screenThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private class Frame(val controls: ConversationControls, val state: ConversationState, val atNanos: Long) {
        val card: List<PendingFollowup> get() = controls.placed(state.queuePlacement).queue
        fun cardShows(followupId: String) = card.any { it.id == followupId }
        fun transcriptShows(text: String) = state.items.any { it is UserMessage && it.text == text }
        fun listed(followupId: String) = controls.queue.any { it.id == followupId }
    }

    private val frames = CopyOnWriteArrayList<Frame>()

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 150L..350L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = LongProject.result(turn))
            server.logs[turn.runId] = turn.log
        }
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(live.startedAt), latestRunId = live.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        server.recordsDeliveries = true
        server.outage(Route.Stream, Fault.StreamCut(events = live.log.size), path = "/${live.runId}/")
        server.queueLagMs = 0L
    }

    @After
    fun tearDown() {
        recorder?.cancel()
        rig?.let { it.steering.detach(agentId); it.close() }
        server.close()
        screenThread.close()
    }

    private fun rig(engine: TranscriptEngine, root: java.io.File = folder.newFolder("rig-${System.nanoTime()}")): FaultRig = FaultRig(server.baseUrl, root, readTimeoutMs = 8_000L, extended = true, engine = engine, queuePollMs = 1_000L).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value
    private val controls: ConversationControls get() = rig!!.steering.state(agentId).value
    private fun card(): List<PendingFollowup> = controls.placed(state.queuePlacement).queue
    private fun transcriptCount(text: String) = state.items.count { it is UserMessage && it.text == text }

    private fun explain(label: String) {
        val s = state
        println("== $label: items=${s.items.size} run=${s.runStatus} streaming=${s.isStreaming} active=${s.activeRunId} placement=${s.queuePlacement}")
        println("   card(raw)=${controls.queue.map { "${it.id}:${it.text.take(16)}" }} load=${controls.queueLoad} card(placed)=${card().map { "${it.id}${it.note?.let { n -> "[$n]" } ?: ""}" }}")
        println("   prompts=${s.items.filterIsInstance<UserMessage>().takeLast(4).map { "${it.id}:${it.text.take(20)}" }}")
        println("   server: pending=${server.pending[agentId]?.map { "${it.followupId}@${it.consumedAtMs}" }} delivered=${server.delivered} requests=${server.seen.groupingBy { it.route }.eachCount()}")
    }

    private suspend fun FaultRig.awaitUntilOr(timeoutMs: Long, label: String, condition: suspend () -> Boolean) {
        try { awaitUntil(timeoutMs, condition) } catch (t: Throwable) { explain("TIMEOUT waiting for $label"); throw t }
    }

    private suspend fun FaultRig.open() {
        server.composers[agentId] = FaultServer.Composer(agentId, LongProject.AGENT_NAME, live.startedAt, running = true)
        agents.refresh()
        conversations.attach(agentId)
        steering.attach(agentId)
        recorder = scope.launch(screenThread) {
            combine(steering.state(agentId), conversations.state(agentId)) { c, s -> c to s }.collect { (c, s) -> frames += Frame(c, s, System.nanoTime()) }
        }
        awaitUntilOr(60_000, "the chat open on its turn") { state.let { !it.isLoading && it.isStreaming && it.activeRunId == live.runId } && controls.isQueueAvailable }
    }

    /** What the composer does mid-turn in Extended mode: the bubble at the tap, the account's follow-up behind it, the queue read once it is settled. */
    private suspend fun FaultRig.sendMidTurn(text: String): String {
        val followupId = AccountFollowup.newId()
        val staged = conversations.stageFollowUp(agentId, text)
        conversations.sendStagedVia(agentId, staged, followupId = followupId, discardOnFailure = false) {
            steering.sendFollowup(agentId, AccountFollowup(text = text, followupId = followupId), refresh = false).getOrThrow()
        }.getOrThrow()
        steering.refreshQueue(agentId)
        awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        return followupId
    }

    private fun endTurnAndDeliver(): RunDto {
        server.endTurn(agentId)
        val next = server.deliverNext(agentId, log = LongProject.turns(firstAt, turns = TURNS + 1).last().log)!!
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/${next.id}/")
        return next
    }

    /** The account lets go of [followupId] without delivering it — taken back from another client. */
    private fun takeBackElsewhere(followupId: String): FaultServer.Pending {
        val list = server.pending.getValue(agentId)
        val p = list.single { it.followupId == followupId }
        list.remove(p)
        return p
    }

    private suspend fun FaultRig.awaitReads(count: Int) {
        val before = server.requests(Route.QueueList).size
        awaitUntil(30_000) { server.requests(Route.QueueList).size >= before + count }
        delay(server.rttMillis.last + 150)
    }

    // -- the record draws it: the Beta engine's transcript is the card's word too -------------------------------------

    @Test
    fun `Beta - the record draws a delivered message while the documented transcript never answers, and the card lets go of it`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.BETA)
        rig.open()
        val followupId = rig.sendMidTurn(MESSAGE)
        // `/v0` stops answering: the adoption that files a delivered message can never read it.
        server.outage(Route.Conversation, Fault.Gateway(503))
        val deliveredAt = System.nanoTime()
        endTurnAndDeliver()
        rig.awaitUntilOr(45_000, "the record to draw the message") { transcriptCount(MESSAGE) > 0 }
        rig.awaitUntilOr(10_000, "the card to let it go") { card().none { it.id == followupId } }
        delay(2_000)
        assertThat(transcriptCount(MESSAGE)).isEqualTo(1)
        assertThat(card()).isEmpty()
        assertThat(state.queuePlacement.waiting).isEmpty()
        // Once the account's list had let it go, no frame drew it on the card and in the transcript at once.
        val unlisted = frames.filter { it.atNanos >= deliveredAt && it.controls.queueLoad == QueueLoad.Loaded && !it.listed(followupId) }
        assertThat(unlisted).isNotEmpty()
        unlisted.forEachIndexed { i, f ->
            assertWithMessage("frame $i: on the card and in the transcript at once — card=${f.card.map { it.id }} placement=${f.state.queuePlacement}").that(f.cardShows(followupId) && f.transcriptShows(MESSAGE)).isFalse()
        }
    }

    // -- a failed read of the transcript is asked again ---------------------------------------------------------------

    @Test
    fun `Stable - a transcript read that fails when the message is delivered is asked again, and the card lets go once it answers`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        rig.open()
        val followupId = rig.sendMidTurn(MESSAGE)
        server.outage(Route.Conversation, Fault.Gateway(503))
        endTurnAndDeliver()
        // The account lets it go; the card carries it, "being delivered", while no transcript read gets through.
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } && card().any { it.id == followupId } }
        assertThat(card().single { it.id == followupId }.note).isEqualTo(QueuePlacement.DELIVERING_NOTE)
        // Longer than an adoption's own attempts: every read it made in them failed.
        delay(14_000)
        assertThat(card().map { it.id }).contains(followupId)
        server.clear(Route.Conversation)
        rig.awaitUntilOr(10_000, "the transcript to file it and the card to let it go") { transcriptCount(MESSAGE) == 1 && card().none { it.id == followupId } }
        delay(1_500)
        assertThat(transcriptCount(MESSAGE)).isEqualTo(1)
        assertThat(card()).isEmpty()
    }

    // -- a grace for what no transcript files; never for what the account still lists ---------------------------------

    @Test
    fun `a message the account let go of that no transcript read can file leaves the card after a grace, and one it still lists never does`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        rig.open()
        val keep = rig.sendMidTurn(FIRST)
        val gone = rig.sendMidTurn(SECOND)
        server.outage(Route.Conversation, Fault.Gateway(503))
        takeBackElsewhere(gone)
        rig.awaitUntilOr(20_000, "the list to let it go") { card().singleOrNull { it.id == gone }?.note == QueuePlacement.DELIVERING_NOTE }
        rig.now += DELIVERY_GRACE_MS + 1_000L
        rig.awaitUntilOr(10_000, "the card to let it go after the grace") { card().none { it.id == gone } }
        assertThat(card().map { it.id }).containsExactly(keep)
        // However long the account keeps listing the other, it stays: on the card, and this device's own for its filing.
        rig.now += 10 * DELIVERY_GRACE_MS
        rig.awaitReads(3)
        assertThat(card().map { it.id }).containsExactly(keep)
        assertThat(state.queuePlacement.waiting.map { it.id }).containsExactly(keep)
    }

    @Test
    fun `a read of the account's queue that misses a message it still lists does not let the message go`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        rig.open()
        val followupId = rig.sendMidTurn(MESSAGE)
        // One read misses it; the next ones list it again.
        val p = takeBackElsewhere(followupId)
        rig.awaitUntilOr(20_000, "a read that missed it") { card().singleOrNull { it.id == followupId }?.note == QueuePlacement.DELIVERING_NOTE }
        server.pending.getValue(agentId).add(0, p)
        rig.awaitUntilOr(10_000, "the list to name it again") { controls.queue.any { it.id == followupId } }
        // Past an adoption's attempts and past the grace: still this device's to file when it is delivered.
        rig.now += DELIVERY_GRACE_MS + 1_000L
        delay(13_000)
        assertThat(state.queuePlacement.waiting.map { it.id }).contains(followupId)
        assertThat(card().single { it.id == followupId }.note).isNull()
        val sentAt = System.nanoTime()
        endTurnAndDeliver()
        rig.awaitUntilOr(45_000, "the message filed under its run") { transcriptCount(MESSAGE) == 1 }
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } }
        delay(1_500)
        assertThat(transcriptCount(MESSAGE)).isEqualTo(1)
        assertThat(card()).isEmpty()
        frames.filter { it.atNanos >= sentAt }.forEachIndexed { i, f ->
            assertWithMessage("frame $i: in neither place — card=${f.card.map { it.id }} placement=${f.state.queuePlacement}").that(f.cardShows(followupId) || f.transcriptShows(MESSAGE)).isTrue()
        }
    }

    // -- given up on, for good: the disk does not bring it back -------------------------------------------------------

    @Test
    fun `a message let go of and given up on is written back, and the next start does not put it back on the card`() = runBlocking<Unit> {
        val root = folder.newFolder("rig-restarted")
        val before = rig(TranscriptEngine.STABLE, root)
        before.open()
        val gone = before.sendMidTurn(MESSAGE)
        takeBackElsewhere(gone)
        // The transcript answers and never shows it: given up on after the adoption's attempts.
        before.awaitUntilOr(30_000, "the card to let it go") { card().none { it.id == gone } }
        delay(1_000)
        recorder?.cancel()
        before.steering.detach(agentId)
        before.close()
        frames.clear()
        val after = rig(TranscriptEngine.STABLE, root)
        after.open()
        after.awaitReads(3)
        assertWithMessage("frames that put it back on the card: ${frames.count { it.cardShows(gone) }} of ${frames.size}").that(frames.none { it.cardShows(gone) }).isTrue()
        assertThat(state.queuePlacement.waiting).isEmpty()
    }

    private companion object {
        const val TURNS = 8
        /** `ConversationRepository.DELIVERY_GRACE_MS`. */
        const val DELIVERY_GRACE_MS = 45_000L
        const val MESSAGE = "Yes, the primary is allowed to delete and clean up all of the conversations."
        const val FIRST = "All right. So, assuming we genuinely hit the target, what next?"
        const val SECOND = "Also, file the scorecard under research."
    }
}
