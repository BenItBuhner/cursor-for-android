package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * A Project coordinator mid-turn on the documented path — the account's record refused with the server's own
 * "FetchBackgroundComposer has been removed" — while the reader sends and watches (Bennett, 2026-09-20, v0.3.47:
 * "it is failing entirely to show my new messages as we speak, and it is removing all assistant verbatim and
 * tool-calls pertaining to it"). Through the app's own stack against the fault server at a phone's round trip.
 *
 * The invariants: a prompt the account queued from here moves into the transcript when the account delivers it —
 * under the run that starts on it, once, and it stays; a live run's streamed items are never replaced by a smaller
 * or empty snapshot while the run is under way, nor dropped when a load, a pause or the next run comes; the story
 * a stream told before it broke stands for its run until the whole trace replaces it; and a turn under way costs a
 * bounded number of publications, however many loads land on it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveTurnDeliveryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - TURNS * LongProject.TURN_SPACING_MS
    private lateinit var turns: List<LongProject.Turn>
    private val live get() = turns.last()
    private var recorder: Job? = null
    private val frames = CopyOnWriteArrayList<ConversationState>()

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
        // The account's record refused at its first read (the state, which names the turns), in the server's words:
        // the documented path stands in, as it did on Bennett's phone when `FetchBackgroundComposer` was removed.
        server.outage(Route.RecordState, Fault.Status(404, "unimplemented", REMOVED))
        // The live run's stream delivers everything it has and drops; the hub keeps coming back to it.
        holdOpen(live.runId, live.log.size)
    }

    @After
    fun tearDown() {
        recorder?.cancel()
        rig?.close()
        server.close()
    }

    private fun rig(): FaultRig = FaultRig(server.baseUrl, folder.newFolder("rig-${System.nanoTime()}"), readTimeoutMs = 8_000L, extended = true).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    /** The stream of [runId] delivers [events] frames and closes without `done`: a run under way whose connection keeps dropping. */
    private fun holdOpen(runId: String, events: Int) = server.outage(Route.Stream, Fault.StreamCut(events = events), path = "/$runId/")

    private val state get() = rig!!.conversations.state(agentId).value

    private fun items(state: ConversationState = this.state): List<TimelineItem> = state.items

    private fun runItems(runId: String, state: ConversationState = this.state): List<TimelineItem> = state.items.filter { it.id.startsWith("activity-$runId-") || it.id.startsWith("asst-$runId-") }

    private fun prompts(text: String, state: ConversationState = this.state): List<UserMessage> = state.items.filterIsInstance<UserMessage>().filter { it.text == text }

    private suspend fun FaultRig.open(record: Boolean = true) {
        agents.refresh()
        conversations.attach(agentId)
        if (record) recorder = scope.launch { conversations.state(agentId).collect { frames += it } }
        awaitUntil(60_000) { state.let { !it.isLoading && it.isStreaming && runItems(live.runId).isNotEmpty() } }
    }

    /** The turn after the live one, started on the reader's message [text]: its run, its log, and the account's word that it is the latest. */
    private fun nextTurn(text: String): LongProject.Turn {
        val next = LongProject.turns(firstAt, turns = TURNS + 1).last()
        server.runs[next.runId] = RunDto(id = next.runId, agentId = agentId, status = "RUNNING", createdAt = LongProject.iso(next.startedAt), updatedAt = LongProject.iso(next.startedAt))
        server.logs[next.runId] = next.log
        server.agents[agentId] = server.agents.getValue(agentId).copy(latestRunId = next.runId, updatedAt = LongProject.iso(next.startedAt))
        server.transcripts[agentId] = server.transcripts.getValue(agentId) + V0ConversationMessageDto("${next.runId}-u", "user_message", text)
        return next
    }

    /** The live run ends on its own stream: its log gets its `result`, and its connection is no longer cut. */
    private fun finishLiveOnStream() {
        server.logs[live.runId] = live.log + ("result" to """{"runId":"${live.runId}","status":"FINISHED","text":"","durationMs":40000}""")
        server.runs[live.runId] = server.runs.getValue(live.runId).copy(status = "FINISHED", durationMs = 40_000L, updatedAt = LongProject.iso(live.startedAt + 40_000L))
    }

    /** The account delivers [text] as the next turn: the live run ends and the new one starts on the message. */
    private fun deliverAsNextTurn(text: String): LongProject.Turn {
        val next = nextTurn(text)
        finishLiveOnStream()
        // The cut moves to the new run's stream: the old run's next connection delivers its result and `done`.
        holdOpen(next.runId, next.log.size)
        return next
    }

    private fun explain(label: String) {
        val s = state
        println("== $label: items=${s.items.size} run=${s.runStatus} streaming=${s.isStreaming} active=${s.activeRunId} loading=${s.isLoading}")
        println("   tail=${s.items.takeLast(8).map { describe(it) }}")
        rig!!.conversations.loadDiagnostics(agentId)?.let { d ->
            println("   load: source=${d.source} runs=${d.runsLoaded} prompts=${d.prompts} queue=${d.traceQueue} inFlight=${d.traceInFlight} live=${d.liveRunId} following=${d.following} stream=${d.liveStream}")
            d.runs.takeLast(4).forEach { println("   run ${it.idTail} ${it.status} trace=${it.trace} items=${it.items} ${it.message ?: ""}") }
        }
    }

    private fun describe(item: TimelineItem): String = when (item) {
        is UserMessage -> "user(${item.text.take(24)})"
        is ActivityGroup -> "group(${item.id}:${item.steps.count { it is ToolCall }} calls)"
        is RunFooter -> "footer(${item.runId}:${item.status})"
        else -> "${item::class.simpleName}(${item.id})"
    }

    /** From the first frame that shows [runId]'s items on, no frame shows fewer of them until [until] holds for the frame. */
    private fun assertRunItemsNeverShrink(runId: String, until: (ConversationState) -> Boolean = { false }) {
        var most = 0
        for (frame in frames) {
            if (until(frame)) break
            val count = runItems(runId, frame).sumOf { item -> if (item is ActivityGroup) item.steps.size else 1 }
            if (most > 0) assertWithMessage("a frame showed $count of $runId's streamed steps after one had shown $most: ${frame.items.map { describe(it) }}").that(count).isAtLeast(most)
            most = maxOf(most, count)
        }
        assertWithMessage("$runId's items were never on screen").that(most).isAtLeast(1)
    }

    // -- the record gone and a log expired: named rows, and no retry storm -------------------------------------------

    /**
     * With the record's read gone from the server (the wording is the server's) and a finished turn's log expired
     * (`410 stream_expired`, about a day after the run), that turn has no source for its activity: a named row says
     * so under its reply, where the activity would have been — never nothing, never another turn's activity. And a
     * removal is not a pause to wait out: the record is asked once, and not again on the loads that follow.
     */
    @Test
    fun `a turn whose log expired with the record gone shows a named row, and the removed record is not asked again`() = runBlocking<Unit> {
        // The newest finished turn the reader started: its log is gone.
        val gone = turns.dropLast(1).last { it.isUser }
        server.logs.remove(gone.runId)
        val rig = rig()
        rig.open(record = false)
        rig.awaitUntil(30_000) { state.items.any { it.id == "expired-${gone.runId}" } }
        val notice = state.items.first { it.id == "expired-${gone.runId}" } as NoticeCard
        assertThat(notice.title).isEqualTo(TimelineBuilder.EXPIRED_TITLE)
        // Under its own prompt, before its footer: the row stands where the activity would have been.
        val items = state.items
        val at = items.indexOf(notice)
        val footer = items.indexOfFirst { it is RunFooter && it.runId == gone.runId }
        assertThat(footer).isGreaterThan(at)
        assertThat(items.subList(at, footer).none { it is RunFooter }).isTrue()
        assertThat(items.subList(0, at).last { it is UserMessage }.let { (it as UserMessage).text }).isEqualTo(gone.prompt)
        // The turns whose logs the server still has draw their activity, not the row.
        assertThat(state.items.filterIsInstance<NoticeCard>().map { it.id }).containsExactly("expired-${gone.runId}")
        explain("expired turn named")
        // The record: asked once, refused with the removal, and not asked again by the loads a live turn brings.
        val recordReads = server.seen.count { it.route == Route.RecordState }
        assertThat(recordReads).isEqualTo(1)
        rig.conversations.reload(agentId)
        rig.awaitUntil(30_000) { !state.isLoading }
        rig.conversations.pause(agentId)
        rig.conversations.resume(agentId)
        rig.awaitUntil(30_000) { !state.isLoading && state.isStreaming }
        assertThat(server.seen.count { it.route == Route.RecordState }).isEqualTo(recordReads)
        // The diagnostics carry the server's words, the read they refused, and the hour's pause the removal earned.
        val record = rig.conversations.loadDiagnostics(agentId)!!.record!!
        assertThat(record.error).isEqualTo(REMOVED)
        assertThat(record.read).isEqualTo("turns")
        val refusedUntil = java.time.Instant.parse(record.fallback!!.refusedUntilIso!!).toEpochMilli()
        val since = java.time.Instant.parse(record.fallback!!.sinceIso).toEpochMilli()
        assertThat(refusedUntil - since).isEqualTo(60 * 60_000L)
    }

    // -- (a) the prompt delivered from the account's queue ------------------------------------------------------------

    @Test
    fun `a message queued on the account and delivered as the next turn is shown under that run, once, and stays`() = runBlocking<Unit> {
        val rig = rig()
        rig.open()
        // What the composer does once the account has taken the message into its queue behind the turn under way.
        rig.conversations.expectDelivery(agentId, MESSAGE)
        assertThat(prompts(MESSAGE)).isEmpty()
        val next = deliverAsNextTurn(MESSAGE)
        rig.awaitUntil(45_000) { state.activeRunId == next.runId && runItems(next.runId).isNotEmpty() }
        rig.awaitUntil(20_000) { prompts(MESSAGE).isNotEmpty() }
        explain("delivered as the next turn")
        // The message, once, right before the run it started; the turn it waited behind whole above it.
        rig.watch(3_000) {
            val s = state
            val shown = prompts(MESSAGE, s)
            assertWithMessage(s.items.map { describe(it) }.toString()).that(shown).hasSize(1)
            val at = s.items.indexOf(shown.single())
            val after = s.items.drop(at + 1).firstOrNull { it !is UserMessage }
            assertWithMessage("after the message: ${s.items.drop(at + 1).take(3).map { describe(it) }}").that(after?.id?.startsWith("activity-${next.runId}-")).isTrue()
            assertThat(s.items.any { it is RunFooter && it.runId == live.runId }).isTrue()
            assertThat(runItems(live.runId, s)).isNotEmpty()
        }
        // Nothing of the fallback's network was spent finding it: the run list's next-run look, not the transcript.
        assertThat(rig.conversations.loadDiagnostics(agentId)!!.liveRunId).isEqualTo(next.runId)
    }

    @Test
    fun `with runs the transcript has no prompt for, the delivered message is still under its own run and not under an older one`() = runBlocking<Unit> {
        // Two of the coordinator's turns have no `user_message` in `/v0` (a turn resumed after a usage limit): the
        // transcript's prompts, paired with the runs by position, sit runs too early — the shape of Bennett's export
        // (`prompts=298 runs=324`).
        val promptless = setOf(turns[2].runId, turns[4].runId)
        server.transcripts[agentId] = LongProject.v0Transcript(turns).filterNot { it.type == "user_message" && promptless.any { r -> it.id == "$r-u" } }
        val rig = rig()
        rig.open()
        rig.conversations.expectDelivery(agentId, MESSAGE)
        val next = deliverAsNextTurn(MESSAGE)
        rig.awaitUntil(45_000) { state.activeRunId == next.runId && runItems(next.runId).isNotEmpty() && prompts(MESSAGE).isNotEmpty() }
        // A load lands meanwhile — a return to the foreground — and reads the transcript, which now lists the message too.
        rig.now += 6_000
        rig.conversations.reload(agentId)
        rig.awaitUntil(45_000) { server.requests(Route.Conversation).size >= 2 && !state.isLoading }
        delay(1_500)
        explain("after the reload, with the transcript's own copy in hand")
        rig.watch(2_000) {
            val s = state
            val shown = prompts(MESSAGE, s)
            assertWithMessage(s.items.map { describe(it) }.toString()).that(shown).hasSize(1)
            val at = s.items.indexOf(shown.single())
            val after = s.items.drop(at + 1).firstOrNull { it !is UserMessage }
            assertWithMessage("after the message: ${s.items.drop(at + 1).take(3).map { describe(it) }}").that(after?.id?.startsWith("activity-${next.runId}-")).isTrue()
        }
    }

    // -- (b), (d) the live run's items across loads, a pause and the next run --------------------------------------------

    @Test
    fun `a load and a pause during the live turn never blank the live run's items, and cost few publications`() = runBlocking<Unit> {
        val rig = rig()
        rig.open()
        delay(1_000)
        val before = runItems(live.runId)
        assertThat(before).isNotEmpty()
        val framesBefore = frames.size
        // A return to the foreground past the revalidation interval: the run list and the transcript are read again.
        rig.now += 6_000
        rig.conversations.revalidate(agentId)
        rig.awaitUntil(45_000) { server.requests(Route.ListRuns).size >= 2 }
        rig.awaitUntil(45_000) { !state.isLoading }
        delay(1_500)
        explain("after a load during the live turn")
        assertRunItemsNeverShrink(live.runId)
        assertThat(state.isStreaming).isTrue()
        val loadFrames = frames.size - framesBefore
        // The screen stops and starts (the phone locked and unlocked): the story so far stands the whole time.
        rig.conversations.pause(agentId)
        delay(700)
        assertThat(runItems(live.runId)).isNotEmpty()
        rig.conversations.resume(agentId)
        rig.awaitUntil(30_000) { state.isStreaming }
        delay(1_500)
        explain("after a pause and a resume")
        assertRunItemsNeverShrink(live.runId)
        // Bounded: a load is a handful of frames, not a frame per item of the transcript.
        assertWithMessage("frames during one load: $loadFrames").that(loadFrames).isAtMost(12)
    }

    @Test
    fun `the story a stream told before its log expired stands for its run when the next run is followed`() = runBlocking<Unit> {
        val rig = rig()
        rig.open()
        delay(1_000)
        val told = runItems(live.runId)
        assertThat(told).isNotEmpty()
        val toldCalls = told.filterIsInstance<ActivityGroup>().flatMap { it.calls }.map { it.callId }
        assertThat(toldCalls).isNotEmpty()
        // The run ends on the server and its log is gone at once (`410`): only the run record says how it ended, and
        // what the stream told before is the only account of the turn there will ever be.
        server.logs.remove(live.runId)
        server.runs[live.runId] = server.runs.getValue(live.runId).copy(status = "FINISHED", durationMs = 40_000L, updatedAt = LongProject.iso(live.startedAt + 40_000L))
        rig.awaitUntil(30_000) { state.items.any { it is RunFooter && it.runId == live.runId } }
        explain("the live run settled off its record, log expired")
        // The next turn starts: the account names it, and the chat follows it.
        val next = nextTurn("Worker report ${TURNS + 1}")
        holdOpen(next.runId, next.log.size)
        rig.awaitUntil(45_000) { state.activeRunId == next.runId && runItems(next.runId).isNotEmpty() }
        delay(1_500)
        explain("the next run followed")
        rig.watch(2_000) {
            val s = state
            val calls = runItems(live.runId, s).filterIsInstance<ActivityGroup>().flatMap { it.calls }.map { it.callId }
            assertWithMessage("the previous run's story: ${s.items.map { describe(it) }}").that(calls).containsAtLeastElementsIn(toldCalls)
            assertThat(s.items.any { it is RunFooter && it.runId == live.runId && it.status.isTerminal }).isTrue()
        }
        assertRunItemsNeverShrink(live.runId)
    }

    // -- (b) the empty snapshot -----------------------------------------------------------------------------------------

    @Test
    fun `a stream rebuilt from nothing and then settled off the record never replaces the story with an empty one`() = runBlocking<Unit> {
        // A plain chat with a run under way, so the log is small enough to script frame by frame. Its records are
        // stamped by the rig's clock: a record older than the row by more than the list's slack reads as stale
        // and never ends the turn (see LiveRunHub.recordIsStale), which is not what this is about.
        server.close()
        server = FaultServer(rttMillis = 100L..250L).start()
        val now = 1_800_000_000_000L
        server.addRunningAgent("bc-1", "Agent", "run-1", createdAt = LongProject.iso(now - 60_000L), prompt = "Ship it")
        server.retain("run-1", "Shipped.")
        // The first connection delivers the thought and both tool calls and drops; the resumed one is refused with
        // `invalid_last_event_id`, so the hub rebuilds from the run's first event; the rebuild's connections deliver
        // nothing and close, until the streamer gives up — and by then the run record says the run is over.
        server.script(Route.Stream, Fault.StreamCut(events = 4), Fault.Status(400, "invalid_last_event_id", "Unknown event id."), Fault.StreamCut(events = 0), Fault.StreamCut(events = 0), Fault.StreamCut(events = 0), path = "run-1")
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig-plain"), readTimeoutMs = 8_000L).also { this@LiveTurnDeliveryTest.rig = it }
        rig.agents.refresh()
        rig.conversations.attach("bc-1")
        // Each frame with the hub's word at that instant: whether the run had ended off its record (finished, not streamed).
        val settledOffRecord = CopyOnWriteArrayList<Boolean>()
        recorder = rig.scope.launch { rig.conversations.state("bc-1").collect { frames += it; settledOffRecord += rig.hub.current("bc-1", "run-1")?.let { h -> h.finished && !h.streamed } ?: false } }
        rig.awaitUntil(30_000) { rig.conversations.state("bc-1").value.items.any { it is ActivityGroup && it.calls.size == 2 } }
        // The refused resume has gone out, and the hub's first read of the record has found the run still going.
        rig.awaitUntil(30_000) { server.requests(Route.Stream).size >= 2 && server.requests(Route.GetRun).isNotEmpty() }
        server.finish("bc-1", "run-1", "Shipped.", at = LongProject.iso(now + 5_000L))
        rig.awaitUntil(60_000) { rig.conversations.state("bc-1").value.let { s -> s.items.any { it is RunFooter && it.runId == "run-1" } } }
        // The run's end came off its record — the rebuild's connections delivered nothing — not off the stream.
        assertThat(server.requests(Route.GetRun).size).isAtLeast(2)
        rig.awaitUntil(10_000) { settledOffRecord.any { it } }
        // Whatever the record settled the run with, the two tool calls the stream had shown are still on screen —
        // and the replay of the retained log (asked for since the stream never told the end) completes the trace.
        rig.awaitUntil(60_000) { rig.conversations.state("bc-1").value.let { s -> s.traceStatus.pending == 0 } }
        delay(1_000)
        val s = rig.conversations.state("bc-1").value
        println("== settled: ${s.items.map { describe(it) }}")
        println("   streams: ${server.requests(Route.Stream).map { "${it.lastEventId ?: "-"}${it.fault?.let { f -> "!$f" } ?: ""}" }} getRun=${server.requests(Route.GetRun).size}")
        println("   frames (calls shown): ${frames.map { f -> f.items.filterIsInstance<ActivityGroup>().sumOf { it.calls.size } }}")
        assertThat(s.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
        var most = 0
        for (frame in frames) {
            val count = frame.items.filterIsInstance<ActivityGroup>().sumOf { it.calls.size }
            if (most > 0) assertWithMessage("a frame lost the streamed tool calls: ${frame.items.map { describe(it) }}").that(count).isAtLeast(most)
            most = maxOf(most, count)
        }
    }

    private companion object {
        const val TURNS = 8
        const val MESSAGE = "Push the pricing table to the notes and tell the research worker to verify the free tier once more."
        const val REMOVED = "streamConversation has been removed"
    }
}
