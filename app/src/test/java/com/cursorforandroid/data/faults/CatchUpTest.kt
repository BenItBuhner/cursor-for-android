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
 * Ctrl+R's catch-up (`ConversationRepository.catchUp`) on each engine: a turn started elsewhere that the open chat's
 * watch has not heard of (its list entry down) is on screen once the catch-up is answered, counted as new, read
 * without the chat being read again from nothing; one with nothing new reads no documented transcript; a failed one
 * says the server's own words. And Ctrl+Shift+R (`reloadTranscript`) on Beta reads the record again from nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CatchUpTest {

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
        // The turn started elsewhere stays under way: its stream delivers what it has and drops.
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

    /** The open chat's watch hears nothing — its list entry is down — so only the catch-up can find a new turn. */
    private suspend fun quietWatch() {
        server.outage(Route.AccountList, Fault.Gateway(502))
        delay(500)
    }

    @Test
    fun `Stable - Ctrl+R reads a turn started elsewhere off the run list and the transcript, then no transcript again`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        rig.openAtRest()
        quietWatch()
        val from = server.seen.size
        server.startTurnElsewhere(worker, prompt, runId)
        // Two presses at once: one read, one answer.
        val first = async { rig.conversations.catchUp(worker) }
        val second = async { rig.conversations.catchUp(worker) }
        val answer = first.await()
        assertThat(second.await()).isEqualTo(answer)
        val caught = asked(from)
        assertThat(answer.error).isNull()
        assertThat(answer.newMessages).isEqualTo(1)
        assertThat(answer.changed).isTrue()
        assertThat(shows(prompt)).isTrue()
        assertThat(state.runStatus?.isActive).isTrue()
        assertThat(state.activeRunId).isEqualTo(runId)
        // The run list named a run the phone had not seen: the transcript was read once, for its prompt.
        assertWithMessage("asked: $caught").that(caught[Route.ListRuns] ?: 0).isAtLeast(1)
        assertWithMessage("asked: $caught").that(caught[Route.Conversation]).isEqualTo(1)
        assertWithMessage("asked: $caught").that(caught.keys).containsNoneOf(Route.RecordState, Route.Record, Route.Blob)

        // Again with nothing new: the run list, and not the transcript.
        val again = server.seen.size
        val upToDate = rig.conversations.catchUp(worker)
        val caughtAgain = asked(again)
        assertThat(upToDate.error).isNull()
        assertThat(upToDate.newMessages).isEqualTo(0)
        assertWithMessage("asked: $caughtAgain").that(caughtAgain[Route.ListRuns] ?: 0).isAtLeast(1)
        assertWithMessage("asked: $caughtAgain").that(caughtAgain[Route.Conversation]).isNull()
        assertThat(shows(prompt)).isTrue()
    }

    @Test
    fun `Stable - a Ctrl+R that fails says the server's own words and keeps the chat`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        rig.openAtRest()
        server.outage(Route.ListRuns, Fault.Status(500, "internal_error", "Run index unavailable; try again shortly."))
        val answer = rig.conversations.catchUp(worker)
        assertThat(answer.error).isEqualTo("Run index unavailable; try again shortly.")
        assertThat(answer.newMessages).isEqualTo(0)
        assertThat(shows(turns.last().prompt)).isTrue()
        server.clear(Route.ListRuns)
        assertThat(rig.conversations.catchUp(worker).error).isNull()
    }

    @Test
    fun `Beta - Ctrl+R reads the record's new turn - no documented transcript, no blob twice`() = runBlocking<Unit> {
        server.outage(Route.Live, Fault.Gateway(502))
        val rig = rig(TranscriptEngine.BETA)
        rig.openAtRest()
        quietWatch()
        val from = server.seen.size
        server.startTurnElsewhere(worker, prompt, runId)
        val answer = rig.conversations.catchUp(worker)
        val caught = asked(from)
        assertThat(answer.error).isNull()
        assertThat(answer.newMessages).isEqualTo(1)
        assertThat(shows(prompt)).isTrue()
        assertThat(state.runStatus?.isActive).isTrue()
        assertWithMessage("asked: $caught").that(caught[Route.RecordState]).isEqualTo(1)
        assertWithMessage("asked: $caught").that(caught[Route.Conversation]).isNull()
        // Only what was new or moved was read: no blob the chat already had read again.
        assertThat(server.blobReads.values.maxOfOrNull { it.get() } ?: 0).isAtMost(1)

        val again = server.seen.size
        val upToDate = rig.conversations.catchUp(worker)
        assertThat(upToDate.error).isNull()
        assertThat(upToDate.newMessages).isEqualTo(0)
        assertWithMessage("asked: ${asked(again)}").that(asked(again)[Route.Conversation]).isNull()
    }

    @Test
    fun `Beta - Ctrl+Shift+R throws the chat away and reads the record again`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.BETA)
        rig.openAtRest()
        val from = server.seen.size
        rig.conversations.reloadTranscript(worker)
        rig.conversations.awaitLoad(worker)
        rig.awaitUntil(30_000) { shows(turns.last().prompt) && !state.isLoading }
        val read = asked(from)
        assertWithMessage("asked: $read").that(read[Route.RecordState] ?: 0).isAtLeast(1)
        assertThat(state.error).isNull()
        assertThat(state.transcriptError).isNull()
        assertThat(turns.all { shows(it.prompt) }).isTrue()
    }
}
