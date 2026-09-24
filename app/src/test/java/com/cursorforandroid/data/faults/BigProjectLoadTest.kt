package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.ServerRetry
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.cursorforandroid.ui.conversation.LoadNotices
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun rig(engine: TranscriptEngine, root: File, waits: ServerRetry.Waits = ServerRetry.Waits()): FaultRig =
        FaultRig(server.baseUrl, root, readTimeoutMs = 20_000L, extended = true, engine = engine, recordWaits = waits).also {
            it.now = now
            rigs += it
        }

    /** The production waits cut short, for the tests that let a read fail for good: as many attempts and passes, in seconds. */
    private val quickWaits = ServerRetry.Waits(pieces = listOf(100L, 200L, 400L), onScreen = listOf(100L), state = listOf(100L, 200L), passes = listOf(300L, 600L, 1_200L))

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
    private suspend fun open(label: String, rig: FaultRig, quietMs: Long = 3_000L, limitMs: Long = 120_000L, id: String = agentId, painted: (ConversationState) -> Boolean = ::newestPainted): Open {
        val seenBefore = server.seen.size
        val bytesBefore = HashMap(server.bytesByRoute)
        val heapBefore = usedHeapKb()
        val conversations = rig.conversations
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        conversations.attach(id)
        var newestPaintMs: Long? = null
        var firstMessageMs: Long? = null
        val startMillis = server.nowMillis()
        val deadline = System.nanoTime() + limitMs * 1_000_000
        var settled = false
        while (System.nanoTime() < deadline) {
            val state = conversations.state(id).value
            if (newestPaintMs == null && painted(state)) newestPaintMs = elapsed()
            if (firstMessageMs == null && state.items.isNotEmpty() && present(state).any { it is TranscriptRow.Message }) firstMessageMs = elapsed()
            val last = server.seen.drop(seenBefore).maxOfOrNull { it.atMillis } ?: startMillis
            val quiet = server.nowMillis() - last >= quietMs
            val loading = state.isLoading || state.isLoadingOlder || state.traceStatus.pending > 0
            if (quiet && !loading && state.items.isNotEmpty()) { settled = true; break }
            delay(25)
        }
        conversations.state(id).value.let { s -> assertWithMessage("$label never settled: loading=${s.isLoading} loadingOlder=${s.isLoadingOlder} traces=${s.traceStatus}").that(settled).isTrue() }
        val lastRequest = server.seen.drop(seenBefore).maxOfOrNull { it.atMillis } ?: startMillis
        val fullMs = (lastRequest - startMillis).coerceAtLeast(newestPaintMs ?: 0L)
        val state = conversations.state(id).value
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

    private fun windowStart(rig: FaultRig, id: String = agentId): Int = rig.conversations.loadDiagnostics(id)!!.windowStart

    private fun betaLine(rig: FaultRig, id: String = agentId): String? = rig.conversations.loadDiagnostics(id)?.beta?.text?.also { println("   $it") }

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

    /**
     * Bennett's 2026-09-23 frame: a bare `HTTP 502` — the load balancer's page, no Connect body — on
     * `GetBlobForAgentKV`. A burst of them on the blobs, and two on the state stream, is ridden out: each read asked
     * again with backoff, a piece whose quick retry the burst also took read again behind the screen, the window
     * whole, no fallback. Every 502 was either asked again or left for later, none thrown away with its page.
     */
    @Test
    fun `beta - a burst of 502s on the state and the blobs is ridden out`() = runBlocking<Unit> {
        server.script(FaultServer.Route.RecordState, FaultServer.Fault.Gateway(), FaultServer.Fault.Gateway())
        server.script(FaultServer.Route.Blob, *Array(BURST) { FaultServer.Fault.Gateway() })
        val rig = rig(TranscriptEngine.BETA, folder.newFolder("beta-502-burst"))
        val cold = open("beta: cold open, $BURST blob reads and 2 state reads answered 502", rig)
        betaLine(rig)
        val beta = rig.conversations.loadDiagnostics(agentId)!!.beta!!
        assertThat(cold.state.recordFallback).isNull()
        assertWholeWindow(cold, windowStart(rig))
        assertThat(beta.incomplete).isEqualTo(0)
        assertThat(cold.state.traceStatus.failed).isEqualTo(0)
        assertThat(server.requests(FaultServer.Route.Blob).count { it.fault is FaultServer.Fault.Gateway }).isEqualTo(BURST)
        assertThat(beta.retried + beta.failed).isEqualTo(BURST + 2)
    }

    /**
     * One piece — the newest message to the user, a step of a turn the prefetch did not carry — that the server
     * fails every time. It does not take the page with it, nor the chat: every other prompt and message of the
     * window is on screen, its turn waits for the rest and is asked again a few times, a bounded number of reads,
     * then reads as missing with the Retry — which, the server well again, brings the message.
     */
    @Test
    fun `beta - one piece that always answers 502 leaves only its own turn short, and Retry brings it`() = runBlocking<Unit> {
        val record = server.blobRecord(agentId)!!
        val target = turns.last { it.message != null }
        val structure = ProtoWire.decode(record.blobs.getValue(record.turnIds[target.index - 1]), AgentSchemas.CONVERSATION_TURN)["agentConversationTurn"]!!.jsonObject
        val messageStep = structure["steps"]!!.jsonArray[structure["sendMessageStepIndices"]!!.jsonArray.first().jsonPrimitive.int].jsonPrimitive.content
        server.blobAnswer = { _, blobId -> if (blobId == messageStep) server.gateway() else null }
        val rig = rig(TranscriptEngine.BETA, folder.newFolder("beta-502-piece"), waits = quickWaits)
        val cold = open("beta: cold open, the newest message's step always answered 502", rig)
        betaLine(rig)
        assertThat(cold.state.recordFallback).isNull()
        val start = windowStart(rig)
        val covered = turns.filter { it.index - 1 >= start }
        assertThat(covered).contains(target)
        assertThat(messagesShown(cold)).containsExactlyElementsIn(covered.mapNotNull { it.message } - target.message!!).inOrder()
        assertThat(cold.state.items.filterIsInstance<UserMessage>().map { it.text }).containsExactlyElementsIn(covered.filter { it.isUser }.map { it.prompt }).inOrder()
        assertThat(cold.state.traceStatus.failed).isEqualTo(1)
        assertThat(rig.conversations.loadDiagnostics(agentId)!!.beta!!.incomplete).isEqualTo(1)
        // Bounded: the quick reads the screen waits on (the reach-back's, the prefetch's), then the patient read and its passes.
        val attempts = server.blobReads.getValue(messageStep).get()
        println("   the failing piece was asked $attempts times")
        assertThat(attempts).isAtMost(2 * (1 + quickWaits.onScreen.size) + (1 + quickWaits.passes.size) * (1 + quickWaits.pieces.size))

        server.blobAnswer = null
        rig.conversations.retryTraces(agentId)
        rig.awaitUntil(30_000) { rig.conversations.state(agentId).value.let { s -> s.traceStatus.failed == 0 && s.traceStatus.pending == 0 && target.message in present(s).filterIsInstance<TranscriptRow.Message>().map { (it.call.payload as ToolPayload.CoordinatorMessage).message } } }
        assertThat(rig.conversations.loadDiagnostics(agentId)!!.beta!!.incomplete).isEqualTo(0)
    }

    /**
     * The state stream answering 502 every time: nothing of the record to read, so after its retries the chat falls
     * back to the documented path, and the notice says Cursor's server errored — the chat is not gone. Once the
     * server is well again and the pause it named has passed, the record is asked again by itself.
     */
    @Test
    fun `beta - a state stream that keeps answering 502 falls back after its retries, says so, and is read again once well`() = runBlocking<Unit> {
        server.outage(FaultServer.Route.RecordState, FaultServer.Fault.Gateway(retryAfter = "1"))
        val rig = rig(TranscriptEngine.BETA, folder.newFolder("beta-502-state"), waits = quickWaits)
        val conversations = rig.conversations
        conversations.attach(agentId)
        rig.awaitUntil(60_000) { conversations.state(agentId).value.recordFallback != null }
        val fallback = conversations.state(agentId).value.recordFallback!!
        assertThat(fallback.serverError).isTrue()
        assertThat(fallback.httpCode).isEqualTo(502)
        assertThat(fallback.path).endsWith("/StreamConversation")
        assertThat(server.requests(FaultServer.Route.RecordState)).hasSize(1 + quickWaits.state.size)
        val notice = LoadNotices.recordFallback(fallback)
        println("   notice: ${notice.title} / ${notice.detail}")
        assertThat(notice.title).isEqualTo("Cursor's server errored: HTTP 502")
        assertThat(notice.detail).startsWith("Asked: POST /aiserver.v1.BackgroundComposerService/StreamConversation → HTTP 502\n")
        assertThat(notice.detail).contains("Nothing is lost")

        server.clear(FaultServer.Route.RecordState)
        rig.now += 5_000L
        rig.awaitUntil(60_000) { conversations.state(agentId).value.recordFallback == null && server.requests(FaultServer.Route.Blob).isNotEmpty() }
    }

    /**
     * A child of the Project — one of its workers' own chats, the kind Bennett opened when the notice read "HTTP 502".
     * The worker's record is read by the worker's own id and nothing else (the desktop's `{bcId, blobId}`, no scope),
     * whole, through a burst of 502s on its state and blobs.
     */
    @Test
    fun `beta - a Project's worker chat reads its own record by its own id, and rides out a burst of 502s`() = runBlocking<Unit> {
        val worker = BigProject.WORKERS.first()
        val workerTurns = BigProject.workerTurns(now - 40 * BigProject.TURN_SPACING_MS - 60_000L)
        workerTurns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = worker, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.last())
            server.logs[turn.runId] = turn.log
        }
        val newest = workerTurns.last()
        server.agents[worker] = AgentDto(id = worker, name = "Scanner", status = "IDLE", createdAt = BigProject.iso(workerTurns.first().startedAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$worker")
        server.v0[worker] = V0AgentDto(id = worker, name = "Scanner", status = "FINISHED")
        server.transcripts[worker] = BigProject.v0Transcript(workerTurns)
        server.records[worker] = workerTurns.flatMap { it.record }
        server.composers[agentId] = FaultServer.Composer(agentId, BigProject.AGENT_NAME, activityMs = now - 1_000L, project = true)
        server.composers[worker] = FaultServer.Composer(worker, "Scanner", activityMs = now - 2_000L, manager = agentId)
        server.workers[agentId] = listOf(worker to "MANAGER_SPAWN_KIND_CREATED")
        server.script(FaultServer.Route.RecordState, FaultServer.Fault.Gateway())
        server.script(FaultServer.Route.Blob, *Array(12) { FaultServer.Fault.Gateway() })
        val rig = rig(TranscriptEngine.BETA, folder.newFolder("beta-worker"))
        rig.agents.refresh()
        rig.awaitUntil(30_000) { rig.agents.agent(worker)?.parent == AgentParent(agentId, AgentParentKind.PROJECT_WORKER) }

        val cold = open("beta: a worker's chat, a burst of 12 blob reads and a state read answered 502", rig, id = worker, painted = { s -> s.items.any { it is UserMessage && it.text == newest.prompt } })
        betaLine(rig, worker)
        assertThat(cold.state.recordFallback).isNull()
        assertThat(cold.state.isProjectConversation).isFalse()
        assertThat(rig.conversations.loadDiagnostics(worker)!!.beta!!.incomplete).isEqualTo(0)
        val covered = workerTurns.filter { it.index - 1 >= windowStart(rig, worker) }
        assertThat(covered).isNotEmpty()
        assertThat(cold.state.items.filterIsInstance<UserMessage>().map { it.text }).containsExactlyElementsIn(covered.map { it.prompt }).inOrder()
        assertThat(cold.state.items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsAtLeastElementsIn(covered.map { it.narration.last() }).inOrder()
        assertThat(cold.state.items.filterIsInstance<ActivityGroup>().sumOf { group -> group.steps.count { it is ToolCall } }).isEqualTo(covered.sumOf { it.calls.size })
        // On the wire: the worker's own id on every read of its record, and nothing but the id and the blob asked for.
        assertThat(server.streamRequests.map { it["bcId"]?.jsonPrimitive?.content }.toSet()).containsExactly(worker)
        assertThat(server.blobRequests).isNotEmpty()
        server.blobRequests.forEach { body ->
            assertThat(body.keys).containsExactly("bcId", "blobId")
            assertThat(body["bcId"]!!.jsonPrimitive.content).isEqualTo(worker)
        }
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

    private companion object {
        /** Blob reads a burst of 502s takes: three rounds of the eight in flight. */
        const val BURST = 24
    }
}
