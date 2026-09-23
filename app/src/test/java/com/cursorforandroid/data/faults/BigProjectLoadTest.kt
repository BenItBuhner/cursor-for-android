package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Bennett's 2026-09-23 frame (see [BigProject]): a Project of two thousand turns opened on the app's own stack
 * against the fault server at a phone's round trip (300–900 ms) and bandwidth (600 KB/s), on either transcript
 * engine. Each open reports what is on screen and when — the newest turn painted, everything the load reads settled
 * — what it cost in requests and bytes by route, and the heap it left behind; the Beta engine's reopen in a fresh
 * process on the same disk reports what a reopen costs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BigProjectLoadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val agentId = BigProject.AGENT_ID
    private val now = 1_800_000_000_000L
    private val firstAt = now - BigProject.TURNS * BigProject.TURN_SPACING_MS - 60_000L
    private lateinit var turns: List<BigProject.Turn>

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..900L).start()
        server.bytesPerSecond = 600_000L
        server.pageSize = 100
        turns = BigProject.turns(firstAt)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            if (BigProject.retained(turn, now)) server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "IDLE", createdAt = BigProject.iso(firstAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[agentId] = BigProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
    }

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
    }

    private fun rig(engine: TranscriptEngine, root: File): FaultRig = FaultRig(server.baseUrl, root, readTimeoutMs = 20_000L, extended = true, engine = engine).also {
        it.now = now
        rigs += it
    }

    private fun present(state: ConversationState): List<TranscriptRow> =
        TranscriptPresenter().present(state.items, coordinatorMode = true, runActive = state.runStatus?.isActive == true || state.isStreaming).rows

    /** What one open came to: the moments, the requests and bytes by route since it started, the heap it holds, the rows. */
    class Open(val label: String, val newestPaintMs: Long?, val fullMs: Long, val requests: Map<FaultServer.Route, Int>, val bytes: Map<FaultServer.Route, Long>, val heapKb: Long, val state: ConversationState, val rows: List<TranscriptRow>, val firstMessageMs: Long? = null) {
        val total: Int get() = requests.values.sum()
        val totalBytes: Long get() = bytes.values.sum()
    }

    private fun usedHeapKb(): Long {
        repeat(3) { System.gc(); Thread.sleep(80) }
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024
    }

    /** The newest turn is on screen: its report's row, by the title only that turn carries. */
    private fun newestPainted(state: ConversationState): Boolean =
        state.items.any { it is SystemNotification && it.raw.contains("title: Report ${BigProject.TURNS}\n") }

    /**
     * Opens the Project on [rig] and waits it out: the newest turn painted, then the server quiet for [quietMs] with
     * nothing loading. What was asked since [open] began is the open's cost.
     */
    private suspend fun open(label: String, rig: FaultRig, quietMs: Long = 3_000L, limitMs: Long = 120_000L): Open {
        val seenBefore = server.seen.size
        val bytesBefore = HashMap(server.bytesByRoute)
        val heapBefore = usedHeapKb()
        val conversations = rig.conversations
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        conversations.attach(agentId)
        var newestPaintMs: Long? = null
        var firstMessageMs: Long? = null
        val startMillis = server.nowMillis()
        val deadline = System.nanoTime() + limitMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val state = conversations.state(agentId).value
            if (newestPaintMs == null && newestPainted(state)) newestPaintMs = elapsed()
            if (firstMessageMs == null && state.items.isNotEmpty() && present(state).any { it is TranscriptRow.Message }) firstMessageMs = elapsed()
            val last = server.seen.drop(seenBefore).maxOfOrNull { it.atMillis } ?: startMillis
            val quiet = server.nowMillis() - last >= quietMs
            val loading = state.isLoading || state.isLoadingOlder || state.traceStatus.pending > 0
            if (quiet && !loading && state.items.isNotEmpty()) break
            delay(25)
        }
        val lastRequest = server.seen.drop(seenBefore).maxOfOrNull { it.atMillis } ?: startMillis
        val fullMs = (lastRequest - startMillis).coerceAtLeast(newestPaintMs ?: 0L)
        val state = conversations.state(agentId).value
        val requests = server.seen.drop(seenBefore).groupingBy { it.route }.eachCount()
        val bytes = server.bytesByRoute.mapValues { (route, n) -> n - (bytesBefore[route] ?: 0L) }.filterValues { it > 0 }
        val heap = usedHeapKb() - heapBefore
        return Open(label, newestPaintMs, fullMs, requests, bytes, heap, state, present(state), firstMessageMs).also { report(it) }
    }

    private fun report(open: Open) {
        val rows = open.rows
        val messages = rows.count { it is TranscriptRow.Message }
        val prompts = open.state.items.count { it is UserMessage }
        println("== ${open.label}")
        println("   newestPaint=${open.newestPaintMs ?: "never"} ms firstMessage=${open.firstMessageMs ?: "never"} ms full=${open.fullMs} ms requests=${open.total} bytes=${open.totalBytes / 1024} KB heap=${open.heapKb} KB")
        println("   by route: ${open.requests.entries.sortedBy { it.key.name }.joinToString(", ") { "${it.key}=${it.value}" }}")
        println("   bytes by route (KB): ${open.bytes.entries.sortedBy { it.key.name }.joinToString(", ") { "${it.key}=${it.value / 1024}" }}")
        println("   items=${open.state.items.size} rows=${rows.size} messages=$messages prompts=$prompts hasOlder=${open.state.hasOlder} fallback=${open.state.recordFallback?.reason ?: "-"}")
        println("   rows (newest 10): ${rows.takeLast(10).map { describe(it) }}")
    }

    private fun describe(row: TranscriptRow): String = when (row) {
        is TranscriptRow.Stretch -> "S(${row.summary.text})"
        is TranscriptRow.Message -> "M(${(row.call.payload as ToolPayload.CoordinatorMessage).message.takeLast(6)})"
        is TranscriptRow.Item -> when (val item = row.item) {
            is UserMessage -> "U(${item.text.takeLast(6)})"
            else -> "I(${item::class.simpleName})"
        }
        else -> row::class.simpleName!!
    }

    private fun messagesShown(open: Open): List<String> = open.rows.filterIsInstance<TranscriptRow.Message>().map { (it.call.payload as ToolPayload.CoordinatorMessage).message }

    /**
     * Every prompt of the user's and every message of the coordinator's in the turns the window covers — as the load
     * reports the window, [windowStart] onwards — is on screen, in the record's order; and the window reaches back
     * to the user's last prompt with at least three of the coordinator's messages in it.
     */
    private fun assertWholeWindow(open: Open, windowStart: Int) {
        val covered = turns.filter { it.index - 1 >= windowStart }
        val prompts = covered.filter { it.isUser }.map { it.prompt }
        val messages = covered.mapNotNull { it.message }
        println("   window from turn $windowStart: ${covered.size} turns, ${prompts.size} prompts, ${messages.size} messages")
        assertThat(prompts).isNotEmpty()
        assertThat(messages.size).isAtLeast(3)
        assertThat(messagesShown(open)).containsExactlyElementsIn(messages).inOrder()
        assertThat(open.state.items.filterIsInstance<UserMessage>().map { it.text }).containsExactlyElementsIn(prompts).inOrder()
    }

    private fun windowStart(rig: FaultRig): Int = rig.conversations.loadDiagnostics(agentId)!!.windowStart

    private fun betaLine(rig: FaultRig): String? = rig.conversations.loadDiagnostics(agentId)?.beta?.text?.also { println("   $it") }

    @Test
    fun `beta - the newest turns fast, every prompt and message of the window in order, a reopen costs the state`() = runBlocking<Unit> {
        val disk = folder.newFolder("beta-disk")
        val first = rig(TranscriptEngine.BETA, disk)
        val cold = open("beta: cold open", first)
        betaLine(first)
        assertWholeWindow(cold, windowStart(first))
        assertThat(cold.newestPaintMs).isNotNull()
        // No whole-history read: no `/v0` transcript, no step-indexed record, no replay of a turn the record holds.
        assertThat(cold.requests[FaultServer.Route.Conversation]).isNull()
        assertThat(cold.requests[FaultServer.Route.Record]).isNull()
        assertThat(cold.requests[FaultServer.Route.Stream] ?: 0).isEqualTo(0)
        // Each blob read once at most.
        assertThat(server.blobReads.values.maxOfOrNull { it.get() } ?: 0).isAtMost(1)

        // The process is gone; a new one opens the same Project on the same disk: the state read, nothing else of the record.
        rigs.forEach { it.close() }
        rigs.clear()
        val second = rig(TranscriptEngine.BETA, disk)
        val reopened = open("beta: reopen in a new process, nothing changed", second)
        betaLine(second)
        assertThat(reopened.requests[FaultServer.Route.RecordState]).isEqualTo(1)
        assertThat(reopened.requests[FaultServer.Route.Blob] ?: 0).isEqualTo(0)
        assertWholeWindow(reopened, windowStart(second))
    }

    @Test
    fun `beta - the run list oldest first does not hold the transcript up`() = runBlocking<Unit> {
        server.runsOldestFirst = true
        val rig = rig(TranscriptEngine.BETA, folder.newFolder("beta-asc"))
        val cold = open("beta: cold open, run list oldest first", rig)
        betaLine(rig)
        assertWholeWindow(cold, windowStart(rig))
        assertThat(cold.requests[FaultServer.Route.Conversation]).isNull()
    }

    /**
     * The shape of Bennett's frame: every step blob the prewarm did not carry answered `not_found` — a drifted
     * server, a scope the request lacks — so each turn reads as its prompt alone. That is not the account's
     * transcript: the chat falls back to the documented path, with the notice naming the request and the answer.
     */
    @Test
    fun `beta - step blobs refused as not found fall back with the notice`() = runBlocking<Unit> {
        server.blobAnswer = { chat, blobId ->
            if (blobId in server.prefetchedIds[chat].orEmpty()) null
            else MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"code":"not_found","message":"blob not found"}""")
        }
        val cold = open("beta: step blobs not found beyond the prefetch", rig(TranscriptEngine.BETA, folder.newFolder("beta-404")))
        val fallback = cold.state.recordFallback
        assertThat(fallback).isNotNull()
        assertThat(fallback!!.path).endsWith("/GetBlobForAgentKV")
        assertThat(fallback.httpCode).isEqualTo(404)
    }

    /** A rate limit on the blobs while the window reaches back: the pause waited out, the window whole, no fallback. */
    @Test
    fun `beta - a rate limit on the blobs is waited out`() = runBlocking<Unit> {
        server.script(FaultServer.Route.Blob, FaultServer.Fault.Status(429, "resource_exhausted", "Too many requests", retryAfter = "1"), FaultServer.Fault.Status(429, "resource_exhausted", "Too many requests", retryAfter = "1"))
        val rig = rig(TranscriptEngine.BETA, folder.newFolder("beta-429"))
        val cold = open("beta: cold open, two blob reads refused 429", rig)
        betaLine(rig)
        assertThat(cold.state.recordFallback).isNull()
        assertWholeWindow(cold, windowStart(rig))
        assertThat(rig.conversations.loadDiagnostics(agentId)!!.beta!!.incomplete).isEqualTo(0)
    }

    @Test
    fun `stable - the documented path, run list oldest first`() = runBlocking<Unit> {
        server.runsOldestFirst = true
        val cold = open("stable: cold open, run list oldest first", rig(TranscriptEngine.STABLE, folder.newFolder("stable-asc")))
        assertThat(cold.requests.keys).containsNoneOf(FaultServer.Route.RecordState, FaultServer.Route.Blob, FaultServer.Route.Record)
    }

    @Test
    fun `stable - the documented path on the same Project`() = runBlocking<Unit> {
        val cold = open("stable: cold open", rig(TranscriptEngine.STABLE, folder.newFolder("stable")))
        assertThat(cold.requests.keys).containsNoneOf(FaultServer.Route.RecordState, FaultServer.Route.Blob, FaultServer.Route.Record)
        assertThat(cold.newestPaintMs).isNotNull()
        rigs.forEach { it.close() }
        rigs.clear()
        delay(10)
    }
}
