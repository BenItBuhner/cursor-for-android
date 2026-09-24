package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertThat
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
 * Bennett's 2026-09-20 frame (see [LongProject]): a 240-turn Project, the coordinator mid-turn, opened on the app's
 * own stack against the fault server at a phone's round trip. What the chat costs to open, what is on screen and
 * when, what the reader's presence at the top of a short transcript makes the app page in, and what a reopen a
 * moment later costs — on the record path (Extended mode) and on the documented path the app falls back to when the
 * account service refuses the record.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LongProjectLoadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - LongProject.TURNS * LongProject.TURN_SPACING_MS
    private lateinit var turns: List<LongProject.Turn>

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..900L).start()
        // A cell connection's bandwidth: the record's pages are hundreds of kilobytes.
        server.bytesPerSecond = 600_000L
        turns = LongProject.turns(firstAt)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(newest.startedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        // The live run's stream: its events so far, then the connection closes without `done` — the run goes on.
        server.outage(FaultServer.Route.Stream, FaultServer.Fault.StreamCut(events = newest.log.size), path = "/${newest.runId}/")
        // The run list pages as the real one does: twenty a page.
        server.pageSize = 100
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    private fun rig(extended: Boolean): FaultRig = FaultRig(server.baseUrl, folder.newFolder("rig-${if (extended) "record" else "runs"}-${System.nanoTime()}"), readTimeoutMs = 20_000L, extended = extended).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private fun requests(): Map<FaultServer.Route, Int> = server.seen.groupingBy { it.route }.eachCount()

    private fun present(state: ConversationState, presenter: TranscriptPresenter = TranscriptPresenter()): List<TranscriptRow> =
        presenter.present(state.items, coordinatorMode = true, runActive = state.runStatus?.isActive == true || state.isStreaming).rows

    /**
     * The reader scrolls up twice: two pages of older turns, as `ConversationScreen` asks for them on a scroll toward
     * the top (until 0.3.47 it asked whenever the end of a short transcript was in view, and a Project's transcript
     * folded into a few rows paged itself in whole). Then everything the pages asked for is waited out.
     */
    private suspend fun FaultRig.scrollUpTwice(conversations: ConversationRepository) {
        repeat(2) {
            val before = conversations.state(agentId).value.items.size
            conversations.loadOlder(agentId)
            awaitUntil(30_000) { conversations.state(agentId).value.let { it.items.size > before && !it.isLoadingOlder } }
        }
        awaitUntil(60_000) {
            val quiet = conversations.state(agentId).value.traceStatus.pending == 0 && conversations.loadDiagnostics(agentId)!!.let { it.traceQueue == 0 && it.traceInFlight == 0 && !it.traceWorkerRunning }
            if (!quiet) return@awaitUntil false
            delay(1_500)
            conversations.loadDiagnostics(agentId)!!.let { it.traceQueue == 0 && it.traceInFlight == 0 && !it.traceWorkerRunning }
        }
    }

    private fun report(label: String, state: ConversationState, presenter: TranscriptPresenter, since: Map<FaultServer.Route, Int> = emptyMap()) {
        val perf = TranscriptPerf.sessionOrNull(agentId)?.snapshot()
        val rows = present(state, presenter)
        val counts = requests().mapValues { (route, n) -> n - (since[route] ?: 0) }.filterValues { it > 0 }
        println("== $label")
        println("   items=${state.items.size} rows=${rows.size} messages=${rows.count { it is TranscriptRow.Message }} prompts=${state.items.count { it is UserMessage }} hasOlder=${state.hasOlder} loadingOlder=${state.isLoadingOlder} traces=${state.traceStatus} run=${state.runStatus} loading=${state.isLoading} error=${state.error} transcriptError=${state.transcriptError}")
        println("   perf: firstContent=${perf?.firstContentMs} newestWhole=${perf?.newestPageWholeMs} network=${perf?.network}")
        println("   requests: ${counts.entries.sortedBy { it.key.name }.joinToString(", ") { "${it.key}=${it.value}" }} total=${counts.values.sum()} recordBytes=${server.recordBytes[agentId] ?: 0}")
        println("   rows: ${rows.takeLast(8).map { row -> when (row) { is TranscriptRow.Stretch -> "S(${row.summary.text})"; is TranscriptRow.Message -> "M(${(row.call.payload as ToolPayload.CoordinatorMessage).message.take(30)}…)"; is TranscriptRow.Item -> "I(${row.item::class.simpleName})"; else -> row::class.simpleName!! } }}")
    }

    private fun messages(items: List<TimelineItem>): List<String> = present(ConversationState(agentId, items = items)).filterIsInstance<TranscriptRow.Message>().map { (it.call.payload as ToolPayload.CoordinatorMessage).message }

    /**
     * The record path: the newest ten turns on screen within two round trips of the account service and one page
     * of its record — a hundred steps, not two hundred — and no log replayed for a turn whose calls the record
     * holds; two scroll-ups later, two more pages and the few replays the record's narration-only turns ask for;
     * a reopen a moment later from memory, the live stream alone fetched.
     */
    @Test
    fun `record path - the newest turns within two round trips, older on scroll, a reopen from memory`() = runBlocking<Unit> {
        val rig = rig(extended = true)
        val conversations = rig.conversations
        val presenter = TranscriptPresenter()
        val opened = System.nanoTime()
        conversations.attach(agentId)
        rig.awaitUntil(60_000) { conversations.state(agentId).value.items.isNotEmpty() }
        val firstPaintMs = (System.nanoTime() - opened) / 1_000_000
        val atPaint = requests()
        report("record: first paint after ${firstPaintMs} ms", conversations.state(agentId).value, presenter)
        // The handshake and the state (the turn list, the newest turns' structures, prompts and last steps prefetched
        // with it), then at most the message steps the prefetch did not carry, the run list beside them: a couple of
        // round trips of the account service at 300–900 ms each — never the removed step-indexed read
        // (`FetchBackgroundComposer`), and never the window's every step before the first frame.
        assertThat(atPaint[FaultServer.Route.Record]).isNull()
        assertThat(atPaint[FaultServer.Route.RecordState]).isAtLeast(1)
        assertThat(atPaint[FaultServer.Route.Blob] ?: 0).isAtMost(12)
        assertThat(firstPaintMs).isLessThan(6_000L)
        assertThat(server.recordBytes[agentId] ?: 0L).isLessThan(400_000L)
        rig.awaitUntil(60_000) { !conversations.state(agentId).value.isLoading }
        rig.scrollUpTwice(conversations)
        val before = conversations.state(agentId).value
        report("record: after two scroll-ups", before, presenter)
        val paged = requests()
        // The window's thirty turns, each by its blobs and each read once, and a log asked only for the turns whose
        // record holds no call at all (a remark and nothing else), never for the ones it holds with their calls.
        assertThat(paged[FaultServer.Route.Record]).isNull()
        assertThat(paged[FaultServer.Route.Blob]).isAtLeast(30)
        val narrationOnly = turns.takeLast(30).count { it.durationMs != null && !it.isUser && it.record.none { step -> step.containsKey("toolCall") } }
        assertThat(TranscriptPerf.sessionOrNull(agentId)!!.snapshot().network["replay"] ?: 0).isAtMost(narrationOnly)
        assertThat(before.traceStatus.pending).isEqualTo(0)
        assertThat(messages(before.items)).isNotEmpty()
        // Leave, and come back within a second: the transcript as it was, from memory; only the live stream fetched.
        val seenBefore = requests()
        conversations.detach(agentId)
        delay(500)
        conversations.attach(agentId)
        delay(3_000)
        val after = conversations.state(agentId).value
        report("record: reopened after 0.5 s (requests since the reopen)", after, presenter, since = seenBefore)
        assertThat(after.items).isEqualTo(before.items)
        assertThat(since(seenBefore).keys).containsNoneOf(FaultServer.Route.Record, FaultServer.Route.RecordState, FaultServer.Route.Blob, FaultServer.Route.ListRuns, FaultServer.Route.Conversation)
    }

    /**
     * The documented path, the account service refusing the record: the refusal is heard at once (no pause waited
     * out for a second try), the run list read beside it is the fallback's, and the newest runs are on screen within
     * two round trips; the fallback's window is ten runs and their logs, older on scroll; a reopen from memory.
     */
    @Test
    fun `documented path - the record refused, the fallback paints within two round trips and pages on scroll`() = runBlocking<Unit> {
        // The record's first read — the conversation state, which names the turns — refused: nothing else of it is asked.
        server.outage(FaultServer.Route.RecordState, FaultServer.Fault.Status(429, "resource_exhausted", "Too many requests", retryAfter = "2"))
        val rig = rig(extended = true)
        val conversations = rig.conversations
        val presenter = TranscriptPresenter()
        val opened = System.nanoTime()
        conversations.attach(agentId)
        rig.awaitUntil(90_000) { conversations.state(agentId).value.items.isNotEmpty() }
        val firstPaintMs = (System.nanoTime() - opened) / 1_000_000
        val atPaint = requests()
        report("runs: first paint after ${firstPaintMs} ms", conversations.state(agentId).value, presenter)
        assertThat(atPaint[FaultServer.Route.RecordState]).isEqualTo(1)
        assertThat(atPaint[FaultServer.Route.Blob]).isNull()
        assertThat(atPaint[FaultServer.Route.ListRuns]).isEqualTo(1)
        assertThat(firstPaintMs).isLessThan(5_000L)
        rig.awaitUntil(90_000) { !conversations.state(agentId).value.isLoading }
        rig.scrollUpTwice(conversations)
        val before = conversations.state(agentId).value
        report("runs: after two scroll-ups", before, presenter)
        println("   messages shown: ${messages(before.items).size}; diagnostics source=${conversations.loadDiagnostics(agentId)?.source} record=${conversations.loadDiagnostics(agentId)?.record}")
        assertThat(conversations.loadDiagnostics(agentId)!!.source).isEqualTo("runs")
        // Never silently: the refusal stands on the screen in the server's words, with the pause it named, and the
        // diagnostics carry the path, the reason and the timings.
        val fallback = before.recordFallback!!
        assertThat(fallback.reason).contains("Too many requests")
        assertThat(fallback.retryAfterMillis).isEqualTo(2_000L)
        assertThat(fallback.readMillis).isLessThan(3_000L)
        val recordLine = conversations.loadDiagnostics(agentId)!!.record!!
        assertThat(recordLine.error).contains("Too many requests")
        assertThat(recordLine.fallback!!.text).startsWith("fallback=runs since=")
        assertThat(recordLine.fallback!!.retryAfterMs).isEqualTo(2_000L)
        // The record was asked once at the open and not again for the window's replays: its logs were the source.
        assertThat(requests()[FaultServer.Route.RecordState]).isEqualTo(1)
        assertThat(requests()[FaultServer.Route.Blob]).isNull()
        // The window's runs and the two pages behind it, and nothing of the hundreds of turns past them.
        assertThat(TranscriptPerf.sessionOrNull(agentId)!!.snapshot().network["replay"] ?: 0).isAtMost(30)
        assertThat(messages(before.items)).isNotEmpty()
        val seenBefore = requests()
        conversations.detach(agentId)
        delay(500)
        conversations.attach(agentId)
        delay(3_000)
        val after = conversations.state(agentId).value
        report("runs: reopened after 0.5 s (requests since the reopen)", after, presenter, since = seenBefore)
        assertThat(after.items).isEqualTo(before.items)
        assertThat(since(seenBefore).keys).containsNoneOf(FaultServer.Route.Record, FaultServer.Route.RecordState, FaultServer.Route.Blob, FaultServer.Route.ListRuns, FaultServer.Route.Conversation)
    }

    private fun since(before: Map<FaultServer.Route, Int>): Map<FaultServer.Route, Int> = requests().mapValues { (route, n) -> n - (before[route] ?: 0) }.filterValues { it > 0 }
}
