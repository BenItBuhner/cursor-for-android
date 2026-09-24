package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
 * The transcript engine (see [TranscriptEngine]) through the real pipeline, on the 240-turn Project with the account
 * serving its record: under Stable a chat open makes no private record call at all — no state stream, no blob, no
 * legacy record page — and shows no "Account transcript unavailable" notice even when the record would have been
 * refused, the account's queue working as before; under Beta the same open reads the record over the state stream,
 * as 0.3.58 did. Switching from Beta to Stable clears what the record left, on the next open.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TranscriptEngineTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - LongProject.TURNS * LongProject.TURN_SPACING_MS
    private val turns = LongProject.turns(firstAt)
    private val live get() = turns.last()

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 60L..140L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = LongProject.result(turn))
            server.logs[turn.runId] = turn.log
        }
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(live.startedAt), latestRunId = live.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        // The live run's stream delivers what it has and drops; the hub keeps coming back to it.
        server.outage(Route.Stream, Fault.StreamCut(events = live.log.size), path = "/${live.runId}/")
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    private fun rig(engine: TranscriptEngine): FaultRig = FaultRig(server.baseUrl, folder.newFolder("rig-${System.nanoTime()}"), readTimeoutMs = 8_000L, extended = true, engine = engine).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value

    private fun requests(): Map<Route, Int> = server.seen.groupingBy { it.route }.eachCount()

    /** The routes of the account's record, none of which Stable may touch. */
    private val recordRoutes = setOf(Route.RecordState, Route.Blob, Route.Record)

    private suspend fun FaultRig.open() {
        agents.refresh()
        conversations.attach(agentId)
        steering.attach(agentId)
        awaitUntil(60_000) { state.let { !it.isLoading && it.items.isNotEmpty() } }
        // The traces of the window's finished runs read, the queue read once, and the frame still for a moment.
        awaitUntil(90_000) {
            val quiet = state.traceStatus.pending == 0 && conversations.loadDiagnostics(agentId)!!.let { it.traceQueue == 0 && it.traceInFlight == 0 && !it.traceWorkerRunning } && steering.state(agentId).value.isQueueAvailable
            if (!quiet) return@awaitUntil false
            val items = state.items
            delay(1_000)
            state.items == items
        }
    }

    @Test
    fun `Stable - a chat open makes no private record call, shows the documented transcript, and the account's queue still works`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.STABLE)
        rig.open()
        val seen = requests()
        assertWithMessage("the request log of the open: $seen").that(seen.keys.intersect(recordRoutes)).isEmpty()
        // The documented endpoints did the work: the runs, their logs, the transcript.
        assertThat(seen[Route.ListRuns]).isAtLeast(1)
        assertThat(seen[Route.Conversation]).isAtLeast(1)
        assertThat(seen[Route.Stream]).isAtLeast(1)
        // Everything else in Extended mode untouched: the account's queue was read over its private route.
        assertThat(seen[Route.QueueList]).isAtLeast(1)
        assertThat(rig.steering.state(agentId).value.isQueueAvailable).isTrue()
        // And the Goal strip's account read — the same record — was not made: the strip goes by the chat's own transcript.
        assertThat(rig.steering.state(agentId).value.goalKnown).isFalse()
        // The transcript: prompts and replies from /v0 (the newest turns of this Project are its workers' reports, drawn
        // as the notifications they are), activity from the logs, no notice of any kind about the record.
        assertThat(state.items.any { it is UserMessage || it is SystemNotification }).isTrue()
        assertThat(state.items.filterIsInstance<AssistantMessage>()).isNotEmpty()
        assertThat(state.recordFallback).isNull()
        val diagnostics = rig.conversations.loadDiagnostics(agentId)!!
        assertThat(diagnostics.source).isEqualTo("runs")
        assertThat(diagnostics.record).isNull()
        assertThat(rig.capabilities.accountTranscript).isFalse()
        assertThat(rig.capabilities.accountQueue).isTrue()
    }

    @Test
    fun `Stable - a record the server would refuse is never asked, so its notice never shows`() = runBlocking<Unit> {
        // The refusal Bennett's phone met, scripted; Stable does not get as far as meeting it.
        server.outage(Route.RecordState, Fault.Status(404, "unimplemented", FaultServer.REMOVED_UNARY))
        val rig = rig(TranscriptEngine.STABLE)
        rig.open()
        assertThat(requests().keys.intersect(recordRoutes)).isEmpty()
        assertThat(state.recordFallback).isNull()
        assertThat(state.error).isNull()
        // A reload and a pause/resume keep to the documented endpoints too.
        rig.conversations.reload(agentId)
        rig.awaitUntil(30_000) { !state.isLoading }
        rig.conversations.pause(agentId)
        rig.conversations.resume(agentId)
        rig.awaitUntil(30_000) { !state.isLoading && state.isStreaming }
        assertThat(requests().keys.intersect(recordRoutes)).isEmpty()
        assertThat(state.recordFallback).isNull()
    }

    @Test
    fun `Beta - the same open reads the record over the conversation state stream, as 0_3_58 did`() = runBlocking<Unit> {
        val rig = rig(TranscriptEngine.BETA)
        rig.open()
        val seen = requests()
        assertWithMessage("the request log of the open: $seen").that(seen[Route.RecordState]).isAtLeast(1)
        assertThat(server.seen.filter { it.route == Route.RecordState }.map { it.path }).contains("/aiserver.v1.BackgroundComposerService/StreamConversation")
        assertThat(rig.conversations.loadDiagnostics(agentId)!!.source).isEqualTo("record")
        assertThat(rig.capabilities.accountTranscript).isTrue()
    }

    @Test
    fun `Beta - a refused record shows its notice, and Stable chosen since clears it on the next open with no record call`() = runBlocking<Unit> {
        server.outage(Route.RecordState, Fault.Status(404, "unimplemented", FaultServer.REMOVED_UNARY))
        val rig = rig(TranscriptEngine.BETA)
        rig.open()
        rig.awaitUntil(30_000) { state.recordFallback != null }
        assertThat(state.recordFallback!!.reason).isEqualTo(FaultServer.REMOVED_UNARY)
        assertThat(state.recordFallback!!.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StreamConversation")
        // The reader chooses Stable in Settings; it takes effect on the next open.
        rig.capabilities = Capabilities.of(true, TranscriptEngine.STABLE)
        rig.conversations.detach(agentId)
        val before = requests()
        rig.conversations.attach(agentId)
        rig.awaitUntil(30_000) { !state.isLoading && state.recordFallback == null }
        delay(1_500)
        val delta = requests().mapValues { (route, n) -> n - (before[route] ?: 0) }.filterValues { it > 0 }
        assertWithMessage("the requests of the reopen: $delta").that(delta.keys.intersect(recordRoutes)).isEmpty()
        assertThat(state.recordFallback).isNull()
        assertThat(state.items.any { it is UserMessage || it is SystemNotification }).isTrue()
        assertThat(rig.conversations.loadDiagnostics(agentId)!!.source).isEqualTo("runs")
    }

    @Test
    fun `Stable - a turn whose log expired shows its calm row, which the reader may put away`() = runBlocking<Unit> {
        // The newest finished turn: its log is gone (Cursor keeps a log about a day).
        val gone = turns.dropLast(1).last()
        server.logs.remove(gone.runId)
        val rig = rig(TranscriptEngine.STABLE)
        rig.open()
        rig.awaitUntil(30_000) { state.items.any { it.id == "expired-${gone.runId}" } }
        val notice = state.items.first { it.id == "expired-${gone.runId}" } as NoticeCard
        assertThat(notice.dismissKey).isEqualTo(com.cursorforandroid.data.repo.TimelineBuilder.EXPIRED_DISMISS_KEY)
        assertThat(requests().keys.intersect(recordRoutes)).isEmpty()
    }
}
