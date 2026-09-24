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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The reader's pull past the newest message (`ConversationRepository.catchUp`): what is new since the last thing the
 * phone has, read by each engine's cheapest path and nothing else — never a reload. A turn started elsewhere that the
 * open chat's watch has not heard of (its list entry and live stream down here) is on screen once the pull is
 * answered, counted as new; a pull with nothing new reads no transcript and no blob; a failed pull says the server's
 * own words; two pulls at once are one read; and on Beta the live stream is asked again at once, its backoff forgotten.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PullToCatchUpTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val worker = BigProject.WORKERS.first()
    private val coordinator = BigProject.AGENT_ID
    private val now = 1_800_000_000_000L
    private val turns = BigProject.workerTurns(now - 10 * BigProject.TURN_SPACING_MS, turns = 6)
    private val prompt = "From the desktop: the fee table for market 200, then report back."
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

    private fun shows(text: String): Boolean = state.items.any { it is UserMessage && it.text == text }

    /** What the server was asked from [since] on, by route. */
    private fun asked(since: Int): Map<Route, Int> = server.seen.drop(since).groupingBy { it.route }.eachCount()

    private suspend fun FaultRig.openAtRest() {
        agents.refresh()
        conversations.attach(worker)
        awaitUntil(60_000) { shows(turns.last().prompt) && !state.isLoading && !state.isStreaming && state.runStatus?.isActive != true && state.traceStatus.pending == 0 }
    }

    @Test
    fun `Stable - a pull reads a turn started elsewhere off the run list and the transcript, then nothing more`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        rig.openAtRest()
        // The open chat's watch hears nothing: its list entry is down. Only the pull can find the turn.
        server.outage(Route.AccountList, Fault.Gateway(502))
        delay(500)
        val from = server.seen.size
        server.startTurnElsewhere(worker, prompt, runId)
        // Two pulls at once, as a jittery release might send: one read, one answer.
        val first = async { rig.conversations.catchUp(worker) }
        val second = async { rig.conversations.catchUp(worker) }
        val answer = first.await()
        assertThat(second.await()).isEqualTo(answer)
        val pulled = asked(from)
        println("   the pull asked: $pulled, answered $answer")
        assertThat(answer.error).isNull()
        assertThat(answer.newMessages).isEqualTo(1)
        assertThat(shows(prompt)).isTrue()
        assertThat(state.runStatus?.isActive).isTrue()
        assertThat(state.activeRunId).isEqualTo(runId)
        // The run list's first page named a run the phone had not seen: the transcript was read once for its prompt.
        assertThat(pulled[Route.ListRuns]).isEqualTo(1)
        assertThat(pulled[Route.Conversation]).isEqualTo(1)
        assertWithMessage("asked: $pulled").that(pulled.keys).containsNoneOf(Route.RecordState, Route.Record, Route.Blob, Route.Live)

        // Pulled again with nothing new: the run list's first page, and not the transcript.
        val again = server.seen.size
        val upToDate = rig.conversations.catchUp(worker)
        val pulledAgain = asked(again)
        println("   the second pull asked: $pulledAgain, answered $upToDate")
        assertThat(upToDate.error).isNull()
        assertThat(upToDate.newMessages).isEqualTo(0)
        assertThat(pulledAgain[Route.ListRuns]).isEqualTo(1)
        assertThat(pulledAgain[Route.Conversation]).isNull()
        assertThat(shows(prompt)).isTrue()
    }

    @Test
    fun `Stable - a pull that fails says the server's own words and keeps the chat as it was`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        rig.openAtRest()
        val shown = state.items
        server.outage(Route.ListRuns, Fault.Status(500, "internal_error", "Run index unavailable; try again shortly."))
        val answer = rig.conversations.catchUp(worker)
        println("   answered $answer")
        assertThat(answer.error).isEqualTo("Run index unavailable; try again shortly.")
        assertThat(answer.newMessages).isEqualTo(0)
        assertThat(state.items).isEqualTo(shown)
        server.clear(Route.ListRuns)
        assertThat(rig.conversations.catchUp(worker).error).isNull()
    }

    @Test
    fun `Beta - a pull reads the record's new turn alone - no transcript, no blob twice`() = runBlocking<Unit> {
        // The watch hears nothing: the live stream is refused and the list entry down. Only the pull can find the turn.
        server.outage(Route.Live, Fault.Gateway(502))
        val rig = rig(TranscriptEngine.BETA)
        rig.openAtRest()
        server.outage(Route.AccountList, Fault.Gateway(502))
        delay(500)
        val from = server.seen.size
        server.startTurnElsewhere(worker, prompt, runId)
        val answer = rig.conversations.catchUp(worker)
        val pulled = asked(from)
        println("   the pull asked: $pulled, answered $answer")
        assertThat(answer.error).isNull()
        assertThat(answer.newMessages).isEqualTo(1)
        assertThat(shows(prompt)).isTrue()
        assertThat(state.runStatus?.isActive).isTrue()
        assertThat(pulled[Route.RecordState]).isEqualTo(1)
        assertThat(pulled[Route.ListRuns]).isEqualTo(1)
        assertThat(pulled[Route.Conversation]).isNull()
        // Only what was new or moved was read: no blob the chat already had read again.
        assertThat(server.blobReads.values.maxOfOrNull { it.get() } ?: 0).isAtMost(1)
    }

    @Test
    fun `Beta - a pull asks the live stream again at once, its backoff forgotten`() = runBlocking<Unit> {
        server.outage(Route.Live, Fault.Gateway(502))
        val rig = rig(TranscriptEngine.BETA)
        rig.openAtRest()
        // Four failures in a row: the next try is eight seconds out.
        rig.awaitUntil(40_000) { server.requests(Route.Live).size >= 4 }
        server.clear(Route.Live)
        val tried = server.requests(Route.Live).size
        val from = server.seen.size
        val answer = rig.conversations.catchUp(worker)
        println("   answered $answer, asked ${asked(from)}")
        assertThat(answer.error).isNull()
        assertThat(answer.newMessages).isEqualTo(0)
        assertThat(asked(from)[Route.Blob]).isNull()
        rig.awaitUntil(3_000) { server.requests(Route.Live).size > tried }
        // Held again: a turn started elsewhere now comes over it, without another pull.
        val started = System.nanoTime()
        server.startTurnElsewhere(worker, prompt, runId)
        rig.awaitUntil(10_000) { shows(prompt) && state.runStatus?.isActive == true }
        println("   the turn started elsewhere was on screen after ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
