package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.AttachmentUploads
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.cursorforandroid.ui.conversation.OutgoingMessages
import com.cursorforandroid.ui.conversation.OutgoingSends
import com.cursorforandroid.ui.conversation.workingCaption
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Bennett's 0.4.24 frames of 2026-09-30 ("Polymarket Bot Scaling & Research"): a follow-up sent as the coordinator's
 * turn had just ended — its footer drawn — shown at once as a sent bubble under "Starting…", Stop up, as if a run had
 * started on it; the chat read again, the true state: the message on the account's queue card, never sent. And every
 * send after that did the same. The server was on a turn the `/v1` run list does not show as the newest run under
 * way — a Project's injected turn (a worker's report), or an older run the account went on with past a newer one it
 * cancelled (the chat of #504) — and queued the message behind it, naming the run it would start on it once that turn
 * was over. The app took the name for a run started: the newest run it listed was over.
 *
 * The rule pinned here: while the server is on a turn, a follow-up is on the card from the account's answer until the
 * server delivers it — across a reload and a restart, for every send after the first — never a filed bubble, never
 * "Starting…", its named run neither the chat's nor the row's nor streamed; once delivered, it is in the transcript
 * once, in its turn, the card letting it go in the same frame.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueuedUntilDeliveredTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var agentId: String
    private var http2 = false
    private val rigs = ArrayList<FaultRig>()
    private var rig: FaultRig? = null
    private var sends: OutgoingSends? = null
    private var recorder: Job? = null
    // One thread, as the screen's main thread (see QueuedMessageDisplayTest).
    private val screenThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val now = 1_800_000_000_000L
    private val turns = BigProject.workerTurns(now - 20 * BigProject.TURN_SPACING_MS, turns = 6)

    /** What the screen pairs — the account's queue as last read and the transcript's frame — with the row as it stood then. */
    private class Frame(val controls: ConversationControls, val state: ConversationState, val row: Agent?, val atNanos: Long) {
        val card: List<PendingFollowup> get() = controls.placed(state.queuePlacement).queue
        fun cardShows(text: String) = card.any { it.text == text }
        fun transcriptShows(text: String) = state.items.any { it is UserMessage && it.text == text }
    }

    private val frames = CopyOnWriteArrayList<Frame>()

    @After
    fun tearDown() {
        recorder?.cancel()
        rigs.forEach { runCatching { it.steering.detach(agentId) }; it.close() }
        if (::server.isInitialized) server.close()
        screenThread.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    // -- the server's shapes ------------------------------------------------------------------------------------------

    /**
     * A Project's coordinator on a worker's report (InjectedTurnStatusTest's chat): every listed run finished, the row
     * where the last one left it, the account on a turn no run of the list stands for — the report the newest turn of
     * its record. [listed]: the account lists the run it names for a queued message as CREATING while it waits.
     * [accountLags]: the account's list, a poll behind, still says the coordinator is idle, the footer of the last
     * listed turn all the chat shows — the tap reads idle and the run request is refused as busy.
     */
    private fun onAReport(listed: Boolean = false, accountLags: Boolean = false) {
        agentId = BigProject.AGENT_ID
        http2 = false
        server = FaultServer(rttMillis = 150L..300L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = "FINISHED", createdAt = iso(turn.startedAt), updatedAt = iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.last())
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "IDLE", createdAt = iso(turns.first().startedAt), updatedAt = iso(newest.endedAt + 834L), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[agentId] = BigProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record } + buildJsonObject { put("humanMessage", buildJsonObject { put("text", REPORT); put("createdAt", (now - 10_000L).toString()) }) }
        server.composers[agentId] = FaultServer.Composer(agentId, BigProject.AGENT_NAME, activityMs = now - 5_000L, running = !accountLags, project = true)
        server.accountTurns += agentId
        server.namesQueuedRuns = true
        server.listsQueuedRuns = listed
        server.recordsDeliveries = true
        server.queueLagMs = 2_000L
    }

    /**
     * RunOutlivesCancelledTest's chat, a Project's: the older run still running and followed, the newer one cancelled
     * and the agent's latest; the account on the older one. A message queues behind that turn.
     */
    private fun pastACancelledRun() {
        agentId = "bc-outlived"
        http2 = true
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = 300L
        server.runs[OLDER] = RunDto(id = OLDER, agentId = agentId, status = "RUNNING", createdAt = iso(now - 180_000L), updatedAt = iso(now - 2_000L))
        server.logs[OLDER] = listOf("status" to """{"runId":"$OLDER","status":"RUNNING"}""", "assistant" to """{"text":"$OLDER_WORDS"}""")
        server.runs[NEWER] = RunDto(id = NEWER, agentId = agentId, status = "CANCELLED", createdAt = iso(now - 120_000L), updatedAt = iso(now - 110_000L))
        server.logs[NEWER] = listOf("result" to """{"runId":"$NEWER","status":"CANCELLED","text":"","durationMs":10000}""")
        server.agents[agentId] = AgentDto(id = agentId, name = "Outlived", status = "ACTIVE", createdAt = iso(now - 180_000L), updatedAt = iso(now - 110_000L), latestRunId = NEWER, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Outlived", status = "CANCELLED")
        server.transcripts[agentId] = emptyList()
        server.composers[agentId] = FaultServer.Composer(agentId, "Outlived", activityMs = now - 1_000L, running = true, project = true)
        server.accountTurns += agentId
        server.namesQueuedRuns = true
        server.queueLagMs = 2_000L
    }

    // -- the phone ----------------------------------------------------------------------------------------------------

    /** A process of the app on [root] — the same [root] again is the same phone after a restart — with the composer's sends wired as `AppGraph` wires them. */
    private fun rig(engine: TranscriptEngine, root: File): FaultRig =
        FaultRig(server.baseUrl, root, readTimeoutMs = if (http2) 20_000L else 8_000L, extended = true, engine = engine, queuePollMs = 1_000L, http2 = http2).also { r ->
            r.now = now
            rigs += r
            rig = r
            sends = OutgoingSends(
                r.conversations,
                AttachmentUploads(uploader = { error("These messages carry no files.") }, scope = r.scope),
                r.steering,
                r.followUps,
                mcpServers = { emptyList() },
                capabilities = { r.capabilities },
                isDemo = { false },
                scope = r.scope,
            )
        }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value
    private val controls: ConversationControls get() = rig!!.steering.state(agentId).value
    private fun card(): List<PendingFollowup> = controls.placed(state.queuePlacement).queue

    /** The run the account named for [text] when it took it; null before it has, or when it named none. */
    private fun namedRun(text: String): String? = server.pending[agentId].orEmpty().firstOrNull { it.text == text }?.runId
    private fun streamed(runId: String): Boolean = server.seen.any { it.route == Route.Stream && it.path.contains("/$runId/") }
    private fun heldByTheServer(text: String): Boolean = server.pending[agentId].orEmpty().any { it.text == text && it.consumedAtMs == null } && server.sent.none { it.first == text }

    private fun explain(label: String) {
        val r = rig ?: return
        val s = state
        println("== $label: items=${s.items.size} run=${s.runStatus} streaming=${s.isStreaming} active=${s.activeRunId} caption=${s.workingCaption()} loading=${s.isLoading}")
        println("   card(raw)=${controls.queue.map { "${it.id.take(8)}:${it.text.take(16)}" }} card(placed)=${card().map { it.text.take(16) }} placement=${s.queuePlacement}")
        println("   prompts=${s.items.filterIsInstance<UserMessage>().takeLast(4).map { "${it.id.take(28)}:${it.text.take(20)}${if (it.isPending) "(pending)" else ""}" }}")
        println("   row=${r.agents.agent(agentId)?.let { "${it.runStatus}/${it.latestRunId}" }} word=${r.agents.runningScan.value.accountWord[agentId]}")
        println("   server: pending=${server.pending[agentId]?.map { "${it.text.take(12)}->${it.runId}@${it.consumedAtMs}" }} sent=${server.sent.map { it.second }} streams=${server.seen.filter { it.route == Route.Stream }.map { it.path.substringAfter("/runs/").substringBefore('/') }.distinct()}")
        r.conversations.loadDiagnostics(agentId)?.let { d -> println("   load: source=${d.source} runs=${d.runsLoaded} live=${d.liveRunId} following=${d.following}") }
    }

    private suspend fun FaultRig.awaitUntilOr(timeoutMs: Long, label: String, condition: suspend () -> Boolean) {
        try { awaitUntil(timeoutMs, condition) } catch (t: Throwable) { explain("TIMEOUT waiting for $label"); throw t }
    }

    /** The chat opened on the turn the server is on — [followed], the run it follows, when that turn is a listed one — its queue read, every frame recorded. */
    private suspend fun FaultRig.open(followed: String? = null, reads: RunStatus = RunStatus.RUNNING) {
        agents.refresh()
        awaitUntilOr(30_000, "the row placed with the account's word") { agents.agent(agentId)?.isProjectRoot == true && agents.runningScan.value.accountWord[agentId] != null }
        conversations.attach(agentId)
        steering.attach(agentId)
        recorder?.cancel()
        recorder = scope.launch(screenThread) {
            combine(steering.state(agentId), conversations.state(agentId)) { c, s -> c to s }.collect { (c, s) -> frames += Frame(c, s, agents.agent(agentId), System.nanoTime()) }
        }
        awaitUntilOr(60_000, "the chat open, reading $reads") {
            state.let { s -> !s.isLoading && s.runStatus == reads && (followed == null || (s.activeRunId == followed && s.isStreaming)) } && controls.isQueueAvailable
        }
    }

    /**
     * The composer's send of words alone, routed as `ConversationViewModel.submit` routes it in Extended mode: onto the
     * card at the tap while the chat reads a turn under way or the account's queue holds messages, the documented run
     * request otherwise — which the server refuses as busy, and the message goes to the account's queue then. Returns
     * once the account holds it and the send has settled.
     */
    private suspend fun FaultRig.tap(text: String, expectBusy: Boolean) {
        val sends = sends!!
        val draft = OutgoingMessages.Draft(text = text, typed = text, images = emptyList(), files = emptyList(), mode = null, planMode = null, override = null)
        val decision = followUps.decide(agentId)
        val queued = decision.busy || controls.queue.isNotEmpty()
        assertWithMessage("decided at the tap: $decision, the card holding ${controls.queue.map { it.text.take(16) }}").that(queued).isEqualTo(expectBusy)
        sends.forAgent(agentId).send(draft, if (queued) sends.accountRoute(agentId, draft, queued = true) else sends.documentedRoute())
        awaitUntilOr(30_000, "the account to hold \"$text\"") { heldByTheServer(text) && sends.forAgent(agentId).statuses.value.isEmpty() }
    }

    /** Now: on the card and nowhere in the transcript, the chat on the turn under way, the named run neither the chat's nor the row's nor streamed. */
    private fun assertQueued(text: String, label: String) {
        val s = state
        val named = namedRun(text)
        val row = rig!!.agents.agent(agentId)
        val prompts = s.items.filterIsInstance<UserMessage>().takeLast(3).map { "${it.text.take(24)}${if (it.isPending) "(pending)" else ""}" }
        val chat = "caption=${s.workingCaption()} row=${row?.runStatus}/${row?.latestRunId} followed=${s.activeRunId} named=$named"
        assertWithMessage("$label: held by the server").that(heldByTheServer(text)).isTrue()
        assertWithMessage("$label: \"$text\" in the transcript, the server still holding it — $prompts, $chat").that(s.items.none { it is UserMessage && it.text == text }).isTrue()
        assertWithMessage("$label: the caption").that(s.workingCaption()).isNotEqualTo("Starting…")
        assertWithMessage("$label: the card — ${card().map { it.text.take(24) }}").that(card().map { it.text }).contains(text)
        assertWithMessage("$label: the row's status").that(row?.runStatus).isNotEqualTo(RunStatus.CREATING)
        if (named != null) {
            assertWithMessage("$label: the chat on the named run").that(s.activeRunId).isNotEqualTo(named)
            assertWithMessage("$label: the row on the named run").that(row?.latestRunId).isNotEqualTo(named)
            assertWithMessage("$label: the named run streamed").that(streamed(named)).isFalse()
        }
    }

    /**
     * Every frame recorded since [since] until [until], from the first that shows [text] anywhere: on the card and not in
     * the transcript — a pending bubble before the card is allowed when [bubbleFirst] (the run request refused as busy) —
     * never "Starting…", never the chat or the row on the run the account named for it, the row never CREATING.
     */
    private fun assertOnTheCardInEveryFrame(text: String, since: Long, until: Long, bubbleFirst: Boolean = false) {
        val named = namedRun(text)
        val seen = frames.filter { it.atNanos in since until until }
        val first = seen.indexOfFirst { it.cardShows(text) || it.transcriptShows(text) }
        assertWithMessage("\"$text\" was never shown").that(first).isAtLeast(0)
        var carded = false
        seen.drop(first).forEachIndexed { i, f ->
            val prompts = f.state.items.filterIsInstance<UserMessage>().takeLast(3).map { "${it.text.take(24)}${if (it.isPending) "(pending)" else ""}" }
            carded = carded || f.cardShows(text)
            val allowedBubble = bubbleFirst && !carded && f.state.items.any { it is UserMessage && it.text == text && it.isPending }
            if (!allowedBubble) {
                assertWithMessage("frame $i: \"$text\" in the transcript while the server held it — $prompts").that(f.transcriptShows(text)).isFalse()
                assertWithMessage("frame $i: \"$text\" off the card — ${f.card.map { it.text.take(24) }}").that(f.cardShows(text)).isTrue()
            }
            assertWithMessage("frame $i: \"Starting…\" for a run the server had not started").that(f.state.workingCaption()).isNotEqualTo("Starting…")
            assertWithMessage("frame $i: the row's status").that(f.row?.runStatus).isNotEqualTo(RunStatus.CREATING)
            if (named != null) {
                assertWithMessage("frame $i: the chat on the named run").that(f.state.activeRunId).isNotEqualTo(named)
                assertWithMessage("frame $i: the row on the named run").that(f.row?.latestRunId).isNotEqualTo(named)
            }
        }
    }

    /** From the first frame since [since] that shows [text] anywhere: in one place, never both, never neither. */
    private fun assertOnePlace(text: String, since: Long) {
        val seen = frames.filter { it.atNanos >= since }
        val first = seen.indexOfFirst { it.cardShows(text) || it.transcriptShows(text) }
        assertWithMessage("\"$text\" was never shown").that(first).isAtLeast(0)
        seen.drop(first).forEachIndexed { i, f ->
            val card = f.cardShows(text)
            val transcript = f.transcriptShows(text)
            assertWithMessage("frame $i: \"$text\" on the card and in the transcript at once — card=${f.card.map { it.text.take(16) }} placement=${f.state.queuePlacement}").that(card && transcript).isFalse()
            assertWithMessage("frame $i: \"$text\" in neither place — card=${f.card.map { it.text.take(16) }} placement=${f.state.queuePlacement}").that(card || transcript).isTrue()
        }
    }

    private fun runLog(runId: String, text: String): List<Pair<String, String>> =
        listOf("status" to """{"runId":"$runId","status":"RUNNING"}""", "assistant" to """{"text":"On it: ${text.take(24)}"}""")

    /**
     * The turn [text] waited behind ends ([endTurn]) and the account delivers it: the run it named starts, the account's
     * list saying the chat is running again. Returns once the chat has it filed under that run and the list has let it go.
     */
    private suspend fun FaultRig.deliver(text: String, endTurn: () -> Unit): String {
        val named = namedRun(text)!!
        endTurn()
        val run = server.deliverNext(agentId, log = runLog(named, text))!!
        assertThat(run.id).isEqualTo(named)
        server.composers[agentId] = server.composers.getValue(agentId).copy(running = true, activityMs = now)
        if (!http2) server.outage(Route.Stream, Fault.StreamCut(events = 2), path = "/$named/")
        awaitUntilOr(45_000, "\"$text\" filed under its run") { state.items.any { it is UserMessage && it.text == text && !it.isPending } && state.activeRunId == named }
        awaitUntilOr(20_000, "the account's list to let \"$text\" go") { controls.queue.none { it.text == text } }
        return named
    }

    private fun assertFiledOnce(vararg texts: String) {
        val s = state
        val at = texts.map { text ->
            assertWithMessage("copies of \"$text\"").that(s.items.count { it is UserMessage && it.text == text }).isEqualTo(1)
            s.items.indexOfFirst { it is UserMessage && it.text == text }
        }
        assertWithMessage("in the order sent: $at").that(at).isInOrder()
        assertWithMessage("the card — ${card().map { it.text.take(24) }}").that(card().none { it.text in texts }).isTrue()
    }

    // -- a Project's injected turn: both screenshots ------------------------------------------------------------------

    @Test
    fun `Stable - a follow-up to a coordinator on a worker's report waits on the card until delivered, across a reload and a restart`() = runBlocking<Unit> {
        onTheCardAcrossAReloadAndARestart(TranscriptEngine.STABLE)
    }

    @Test
    fun `Beta - a follow-up to a coordinator on a worker's report waits on the card until delivered, across a reload and a restart`() = runBlocking<Unit> {
        onTheCardAcrossAReloadAndARestart(TranscriptEngine.BETA)
    }

    private suspend fun onTheCardAcrossAReloadAndARestart(engine: TranscriptEngine) {
        onAReport()
        val root = folder.newFolder("phone")
        val before = rig(engine, root)
        before.open()
        val tappedAt = System.nanoTime()
        // Screenshot 1: the account holds the message behind the report's turn. The card is its place, not a bubble under "Starting…".
        before.tap(FIRST, expectBusy = true)
        assertQueued(FIRST, "the account's answer")
        before.watch(3_000) { assertQueued(FIRST, "held") }

        // Screenshot 2 is the state after a reload, and nothing else is: the message on the card, unsent.
        before.conversations.reload(agentId)
        before.conversations.awaitLoad(agentId)
        before.steering.refreshQueue(agentId)
        before.awaitUntilOr(15_000, "the account's list to name it") { controls.queue.any { it.text == FIRST } }
        before.watch(2_000) { assertQueued(FIRST, "after a reload") }
        assertOnTheCardInEveryFrame(FIRST, tappedAt, System.nanoTime())

        // The process goes; the chat was written to disk when the account took the message.
        recorder?.cancel()
        before.steering.detach(agentId)
        before.close()
        rigs -= before
        frames.clear()
        val after = rig(engine, root)
        after.open()
        val openedAt = System.nanoTime()
        after.awaitUntilOr(15_000, "the account's list to name it") { controls.queue.any { it.text == FIRST } }
        after.watch(2_000) { assertQueued(FIRST, "after a restart") }

        // Every send after that: behind the first, on the card too.
        after.tap(SECOND, expectBusy = true)
        assertQueued(SECOND, "the second message")
        assertThat(card().map { it.text }.filter { it == FIRST || it == SECOND }).containsExactly(FIRST, SECOND).inOrder()
        after.watch(2_000) { assertQueued(FIRST, "both held"); assertQueued(SECOND, "both held") }
        val heldUntil = System.nanoTime()
        assertOnTheCardInEveryFrame(FIRST, openedAt, heldUntil)
        assertOnTheCardInEveryFrame(SECOND, openedAt, heldUntil)

        // The report's turn ends; the account delivers the first as its run, then — that turn over — the second.
        after.deliver(FIRST) { server.endAccountTurn(agentId) }
        assertThat(card().map { it.text }).contains(SECOND)
        assertThat(state.items.none { it is UserMessage && it.text == SECOND }).isTrue()
        after.deliver(SECOND) { server.endTurn(agentId) }
        assertFiledOnce(FIRST, SECOND)
        assertOnePlace(FIRST, openedAt)
        assertOnePlace(SECOND, openedAt)
    }

    @Test
    fun `a run the account lists as CREATING while it waits behind a worker's report is neither followed nor the chat's`() = runBlocking<Unit> {
        onAReport(listed = true)
        val rig = rig(TranscriptEngine.STABLE, folder.newFolder("phone"))
        rig.open()
        val tappedAt = System.nanoTime()
        rig.tap(FIRST, expectBusy = true)
        val named = namedRun(FIRST)!!
        // A load reads the run list, which now carries the named run as CREATING.
        rig.conversations.reload(agentId)
        rig.conversations.awaitLoad(agentId)
        rig.awaitUntilOr(30_000, "the run list read with the named run") { rig.conversations.loadDiagnostics(agentId)?.runsLoaded == turns.size + 1 && !state.isLoading }
        rig.awaitUntilOr(15_000, "the account's list to name it") { controls.queue.any { it.text == FIRST } }
        rig.watch(3_000) { assertQueued(FIRST, "listed as CREATING") }
        assertOnTheCardInEveryFrame(FIRST, tappedAt, System.nanoTime())
        assertWithMessage("the named run streamed before it started").that(streamed(named)).isFalse()

        rig.deliver(FIRST) { server.endAccountTurn(agentId) }
        assertFiledOnce(FIRST)
        assertOnePlace(FIRST, tappedAt)
    }

    @Test
    fun `the account's list a poll behind the coordinator - refused as busy, the message waits on the card, and so does the next`() = runBlocking<Unit> {
        onAReport(accountLags = true)
        val rig = rig(TranscriptEngine.STABLE, folder.newFolder("phone"))
        // All the chat shows is the last listed turn's footer: the tap reads idle, and the run request is refused as busy.
        rig.open(reads = RunStatus.FINISHED)
        val tappedAt = System.nanoTime()
        rig.tap(FIRST, expectBusy = false)
        assertWithMessage("the run request").that(server.requests(Route.CreateRun)).isNotEmpty()
        rig.awaitUntilOr(15_000, "the account's list to name it") { controls.queue.any { it.text == FIRST } }
        assertQueued(FIRST, "refused as busy")
        rig.watch(3_000) { assertQueued(FIRST, "held") }
        assertOnTheCardInEveryFrame(FIRST, tappedAt, System.nanoTime(), bubbleFirst = true)

        // The card holds a message: the next goes behind it from the tap, as the placeholder says it will.
        val requests = server.requests(Route.CreateRun).size
        val nextAt = System.nanoTime()
        rig.tap(SECOND, expectBusy = true)
        assertThat(server.requests(Route.CreateRun)).hasSize(requests)
        assertQueued(SECOND, "the next message")
        assertOnTheCardInEveryFrame(SECOND, nextAt, System.nanoTime())

        rig.deliver(FIRST) { server.endAccountTurn(agentId) }
        rig.deliver(SECOND) { server.endTurn(agentId) }
        assertFiledOnce(FIRST, SECOND)
    }

    // -- an older run the account went on with past a cancelled one (#504's chat) -------------------------------------

    @Test
    fun `Stable - a follow-up behind an older run that outlived a cancelled one waits on the card until delivered`() = runBlocking<Unit> {
        behindTheRunThatOutlivedACancelledOne(TranscriptEngine.STABLE)
    }

    @Test
    fun `Beta - a follow-up behind an older run that outlived a cancelled one waits on the card until delivered`() = runBlocking<Unit> {
        behindTheRunThatOutlivedACancelledOne(TranscriptEngine.BETA)
    }

    private suspend fun behindTheRunThatOutlivedACancelledOne(engine: TranscriptEngine) {
        pastACancelledRun()
        val rig = rig(engine, folder.newFolder("phone"))
        rig.open(followed = OLDER)
        val tappedAt = System.nanoTime()
        rig.tap(FIRST, expectBusy = true)
        assertQueued(FIRST, "the account's answer")
        assertThat(state.activeRunId).isEqualTo(OLDER)
        rig.watch(3_000) { assertQueued(FIRST, "held") }
        rig.conversations.reload(agentId)
        rig.conversations.awaitLoad(agentId)
        rig.awaitUntilOr(30_000, "the older run followed again") { state.let { !it.isLoading && it.activeRunId == OLDER && it.isStreaming } }
        rig.watch(2_000) { assertQueued(FIRST, "after a reload") }
        assertOnTheCardInEveryFrame(FIRST, tappedAt, System.nanoTime())

        rig.deliver(FIRST) { server.endAccountTurn(agentId, runId = OLDER) }
        assertFiledOnce(FIRST)
        assertOnePlace(FIRST, tappedAt)
    }

    private companion object {
        const val FIRST = "Figure this out: the fills stopped scaling past four markets."
        const val SECOND = "And pull the research notes on the maker rebates after that."
        const val OLDER = "run-older-running"
        const val NEWER = "run-newer-cancelled"
        const val OLDER_WORDS = "Reading the repository."
        const val REPORT = "<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n<task>\nkind: subagent\nstatus: success\ntask_id: task-1443\ntitle: Expansion shard 7\nagent_id: bc-worker-1443\ndetail: This is the last output of the subagent:\n\nThe shard ran clean.\n</task>\n</system_notification>"
    }
}
