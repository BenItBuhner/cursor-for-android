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
     * The screen's own behaviour at the top of a short transcript: `ConversationScreen` asks for older turns whenever
     * the last visible row is within six rows of the end, so a transcript of a handful of rows keeps asking as long
     * as the chat has older turns and none is being paged. Runs until [untilMs] have passed or the chat has no older turns.
     */
    private fun FaultRig.screenAtTop(conversations: ConversationRepository, presenter: TranscriptPresenter, untilMs: Long): Job = scope.launch {
        val deadline = System.nanoTime() + untilMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val state = conversations.state(agentId).value
            if (state.hasOlder && !state.isLoadingOlder && state.items.isNotEmpty() && present(state, presenter).size <= OLDER_TURNS_PREFETCH_ROWS) conversations.loadOlder(agentId)
            delay(50)
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

    @Test
    fun `record path - the newest turns, the auto-paging at the top, and a reopen a moment later`() = runBlocking<Unit> {
        val rig = rig(extended = true)
        val conversations = rig.conversations
        val presenter = TranscriptPresenter()
        val opened = System.nanoTime()
        conversations.attach(agentId)
        rig.awaitUntil(60_000) { conversations.state(agentId).value.items.isNotEmpty() }
        val firstPaintMs = (System.nanoTime() - opened) / 1_000_000
        report("record: first paint after ${firstPaintMs} ms", conversations.state(agentId).value, presenter)
        rig.awaitUntil(60_000) { !conversations.state(agentId).value.isLoading }
        report("record: load settled", conversations.state(agentId).value, presenter)
        // The reader at the top of a short transcript, for a while.
        val top = rig.screenAtTop(conversations, presenter, untilMs = 20_000)
        top.join()
        rig.awaitUntil(30_000) { !conversations.state(agentId).value.isLoadingOlder }
        val before = conversations.state(agentId).value
        report("record: after 20 s at the top", before, presenter)
        // Leave, and come back within a second.
        val seenBefore = requests()
        conversations.detach(agentId)
        delay(500)
        conversations.attach(agentId)
        rig.awaitUntil(30_000) { !conversations.state(agentId).value.isLoading }
        delay(3_000)
        val after = conversations.state(agentId).value
        report("record: reopened after 0.5 s (requests since the reopen)", after, presenter, since = seenBefore)
        println("   identical=${after.items == before.items} itemsBefore=${before.items.size} itemsAfter=${after.items.size}")
        assertThat(after.items).isNotEmpty()
    }

    @Test
    fun `documented path - the record refused, the app falls back and pages`() = runBlocking<Unit> {
        server.outage(FaultServer.Route.Record, FaultServer.Fault.Status(429, "resource_exhausted", "Too many requests", retryAfter = "2"))
        val rig = rig(extended = true)
        val conversations = rig.conversations
        val presenter = TranscriptPresenter()
        val opened = System.nanoTime()
        conversations.attach(agentId)
        rig.awaitUntil(90_000) { conversations.state(agentId).value.items.isNotEmpty() }
        val firstPaintMs = (System.nanoTime() - opened) / 1_000_000
        report("runs: first paint after ${firstPaintMs} ms", conversations.state(agentId).value, presenter)
        rig.awaitUntil(90_000) { !conversations.state(agentId).value.isLoading }
        report("runs: load settled", conversations.state(agentId).value, presenter)
        val top = rig.screenAtTop(conversations, presenter, untilMs = 20_000)
        top.join()
        rig.awaitUntil(30_000) { !conversations.state(agentId).value.isLoadingOlder }
        val before = conversations.state(agentId).value
        report("runs: after 20 s at the top", before, presenter)
        println("   messages shown: ${messages(before.items).size}; diagnostics source=${conversations.loadDiagnostics(agentId)?.source} record=${conversations.loadDiagnostics(agentId)?.record}")
        val seenBefore = requests()
        conversations.detach(agentId)
        delay(500)
        conversations.attach(agentId)
        rig.awaitUntil(60_000) { !conversations.state(agentId).value.isLoading }
        delay(3_000)
        val after = conversations.state(agentId).value
        report("runs: reopened after 0.5 s (requests since the reopen)", after, presenter, since = seenBefore)
        println("   identical=${after.items == before.items} itemsBefore=${before.items.size} itemsAfter=${after.items.size}")
        assertThat(after.items).isNotEmpty()
    }

    private companion object {
        /** `ConversationScreen.OlderTurnsPrefetchRows`. */
        const val OLDER_TURNS_PREFETCH_ROWS = 6
    }
}
