package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #305's gap: a chat open and at rest on the phone did not notice a turn started elsewhere — the web, the desktop,
 * a coordinator messaging this worker — until a return to the foreground, a reopen or Reload transcript. On either
 * transcript engine it now hears of it within a few seconds and reads it in as a foreground return does: Beta over
 * the record's live stream, held the way Cursor's desktop holds it for an open chat; Stable over the chat's own entry
 * in the account's list, since it reads no private record. Neither reads a blob while it waits, and nothing is
 * watched while the turn it found is under way.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IdleChatWatchTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val worker = BigProject.WORKERS.first()
    private val coordinator = BigProject.AGENT_ID
    private val now = 1_800_000_000_000L
    private val turns = BigProject.workerTurns(now - 10 * BigProject.TURN_SPACING_MS, turns = 6)
    private val prompt = "Next: the fee table for market 200, then report back."
    private val runId = "run-elsewhere-1"

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 150L..300L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = worker, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.last())
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[worker] = AgentDto(id = worker, name = "Scanner", status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$worker")
        server.v0[worker] = V0AgentDto(id = worker, name = "Scanner", status = "FINISHED")
        server.transcripts[worker] = BigProject.v0Transcript(turns)
        server.records[worker] = turns.flatMap { it.record }
        server.composers[coordinator] = FaultServer.Composer(coordinator, BigProject.AGENT_NAME, activityMs = now - 1_000L, project = true)
        server.composers[worker] = FaultServer.Composer(worker, "Scanner", activityMs = newest.endedAt, manager = coordinator)
        server.workers[coordinator] = listOf(worker to "MANAGER_SPAWN_KIND_CREATED")
        // The turn started elsewhere stays under way: its stream delivers what it has and drops, and the hub comes back to it.
        server.outage(Route.Stream, Fault.StreamCut(events = 1), path = "/$runId/")
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    private fun rig(engine: TranscriptEngine): FaultRig = FaultRig(server.baseUrl, folder.newFolder("rig-${engine.name}"), readTimeoutMs = 8_000L, extended = true, engine = engine).also {
        it.now = now
        rig = it
    }

    private val state: ConversationState get() = rig!!.conversations.state(worker).value

    private fun shows(text: String, state: ConversationState = this.state): Boolean = state.items.any { it is UserMessage && it.text == text }

    /** What the server was asked from [since] on, by route. */
    private fun asked(since: Int): Map<Route, Int> = server.seen.drop(since).groupingBy { it.route }.eachCount()

    /** Opens the worker's chat and waits for it to settle at rest: the newest turn on screen, nothing loading or followed. */
    private suspend fun FaultRig.openAtRest() {
        agents.refresh()
        conversations.attach(worker)
        awaitUntil(60_000) { state.let { shows(turns.last().prompt, it) && !it.isLoading && !it.isStreaming && it.runStatus?.isActive != true && it.traceStatus.pending == 0 } }
    }

    /**
     * The chat sits open at rest for [restMs], then another client starts a turn: how long until the new prompt is on
     * screen with the turn under way, and what the server was asked while the chat was at rest and after.
     */
    private suspend fun FaultRig.turnStartedElsewhere(restMs: Long = 5_000L): Triple<Long, Map<Route, Int>, Int> {
        openAtRest()
        val restFrom = server.seen.size
        delay(restMs)
        val atRest = asked(restFrom)
        println("   asked while at rest for $restMs ms: $atRest")
        val startedFrom = server.seen.size
        val started = System.nanoTime()
        server.startTurnElsewhere(worker, prompt, runId)
        awaitUntil(15_000) { shows(prompt) && state.runStatus?.isActive == true }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        println("   the turn started elsewhere was on screen after $tookMs ms: ${asked(startedFrom)}")
        return Triple(tookMs, atRest, startedFrom)
    }

    @Test
    fun `Beta - a turn started elsewhere while the chat is open and at rest is on screen within seconds, over the live stream`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.BETA)
        val (tookMs, atRest, startedFrom) = rig.turnStartedElsewhere()
        // At rest: one live stream held — no state read, no blob, and the list entry not polled.
        assertWithMessage("asked while at rest: $atRest").that(atRest.keys).containsNoneOf(Route.RecordState, Route.Blob, Route.Record)
        assertThat(server.composerReads).doesNotContain(worker)
        assertThat(atRest[Route.Live] ?: 0).isAtMost(1)
        val watch = server.liveRequests.first()
        assertThat(watch["bcId"]!!.jsonPrimitive.content).isEqualTo(worker)
        assertThat(watch["purpose"]!!.jsonPrimitive.content).isEqualTo("STREAM_CONVERSATION_PURPOSE_LIVE")
        assertThat(watch["includeStreamHeartbeats"]!!.jsonPrimitive.content).isEqualTo("true")
        // Resumed from where the chat's own state read left the stream: nothing is sent again for a chat at rest.
        assertThat(watch["offsetKey"]!!.jsonPrimitive.content).isEqualTo("off-0")
        assertThat(tookMs).isLessThan(5_000L)
        // The new turn was read once, as a return to the foreground reads it: no blob twice.
        assertThat(server.blobReads.values.maxOfOrNull { it.get() } ?: 0).isAtMost(1)
        // Under way, the turn's own stream carries it: no live stream is held over it.
        val runningFrom = server.seen.size
        delay(3_000)
        assertThat(asked(runningFrom)[Route.Live]).isNull()
        assertThat(server.seen.drop(startedFrom).count { it.route == Route.Live }).isEqualTo(0)
    }

    /**
     * A server that will not hold the live stream (refused here, as a method it had removed would be): after a few
     * tries at the desktop's pace, the chat's list entry stands in, and a turn started elsewhere is still on screen
     * within seconds of the switch.
     */
    @Test
    fun `Beta - with the live stream refused, the list entry stands in`() = runBlocking<Unit> {
        server.outage(Route.Live, Fault.Status(404, "unimplemented", "streamConversation LIVE is not available"))
        val rig = rig(TranscriptEngine.BETA)
        rig.openAtRest()
        rig.awaitUntil(30_000) { server.composerReads.count { it == worker } >= 1 }
        val refused = server.requests(Route.Live).size
        println("   the live stream was refused $refused times before the list entry stood in")
        assertThat(refused).isEqualTo(3)
        val started = System.nanoTime()
        server.startTurnElsewhere(worker, prompt, runId)
        rig.awaitUntil(15_000) { shows(prompt) && state.runStatus?.isActive == true }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        println("   the turn started elsewhere was on screen after $tookMs ms")
        assertThat(tookMs).isLessThan(8_000L)
        assertThat(server.requests(Route.Live)).hasSize(refused)
    }

    @Test
    fun `Stable - a turn started elsewhere while the chat is open and at rest is on screen within seconds, over the list entry`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        val readsBefore = { server.composerReads.count { it == worker } }
        rig.openAtRest()
        val restFrom = server.seen.size
        val readsAtRest = readsBefore()
        delay(5_000)
        val atRest = asked(restFrom)
        val polls = readsBefore() - readsAtRest
        println("   asked while at rest for 5000 ms: $atRest, the list entry $polls times")
        // No private record at all, as the engine promises: the chat's list entry, a few seconds apart, and nothing else.
        assertWithMessage("asked while at rest: $atRest").that(atRest.keys).containsExactly(Route.AccountList)
        assertThat(polls).isIn(1..2)
        val started = System.nanoTime()
        server.startTurnElsewhere(worker, prompt, runId)
        rig.awaitUntil(15_000) { shows(prompt) && state.runStatus?.isActive == true }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        println("   the turn started elsewhere was on screen after $tookMs ms")
        assertThat(tookMs).isLessThan(8_000L)
        assertThat(server.seen.map { it.route }.toSet()).containsNoneOf(Route.RecordState, Route.Live, Route.Blob, Route.Record)
        // Under way, the turn's own stream carries it: the list entry is not asked over it.
        val runningReads = readsBefore()
        delay(6_000)
        assertThat(readsBefore()).isEqualTo(runningReads)
    }
}
