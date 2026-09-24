package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.ServerRetry
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BlobFixtures
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bennett's two frames of 2026-09-23, on the app's own stack against the fault server. In the first, the
 * coordinator's reply he had read was gone from the turn after his message, which showed only "Worked 4s"; in the
 * second, the reply to his latest message stood under the one before, the latest showed "Worked 3s" and nothing
 * else, and the reply that belonged to the one before ("Yep, I see it…") was gone.
 *
 * The chat: three turns of his — each answered with a message — and a worker's report the coordinator answers
 * silently, its run's log carrying the latest message again ahead of its own events (#228). Each case puts the
 * newest turn in one source and not yet in another (the run list, the record, `/v0`), or has a read after a reply
 * was shown come back without it (the watch, a reopen, a switch of source, a reload), and holds the two rules:
 * each reply stands under the turn that sent it, and a reply once shown is never taken off it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CoordinatorRepliesStayTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L

    /** The production waits cut short: a piece the server fails reads as missing in seconds rather than a minute. */
    private val quickWaits = ServerRetry.Waits(pieces = listOf(100L, 200L, 400L), onScreen = listOf(100L), state = listOf(100L, 200L), passes = listOf(300L, 600L, 1_200L))

    private val first = Turn(1, "Where are we on the scanner?", now - 40 * MINUTE, 40_000L, "The scanner is on day two: forty markets read, nothing flagged for you.")
    private val previous = Turn(2, "Another regression: the SendMessage replies vanish again.", now - 20 * MINUTE, 30_000L, "Yep, I see it: the reply under your last message is gone. A worker is on it.")
    private val latest = Turn(3, "This has been a recurring issue for a long time, not just this version.", now - 6 * MINUTE, 25_000L, "Understood: it has come and gone for many versions, and 0.3.85 made it steady. The worker is after the old cause.")
    /** The worker's report, two seconds after the latest turn ended: a three-second silent turn. */
    private val report = Turn(4, workerReport(now - 6 * MINUTE + 27_000L), now - 6 * MINUTE + 27_000L, 3_000L, message = null, replays = latest)
    /** A prompt sent from the web after the report, answered at once. */
    private val newest = Turn(5, "Ship the fix before the release.", now - 4 * MINUTE, 20_000L, "Will do: the fix goes in first, then the release.")

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 60L..160L).start()
    }

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
    }

    // ---- the chat on the server --------------------------------------------------------------------------------

    /** One turn of the coordinator's chat in the wire's shapes, as `BigProject` writes them. */
    private class Turn(val n: Int, val prompt: String, val startedAt: Long, val durationMs: Long, val message: String?, val replays: Turn? = null) {
        val runId = "run-replies-$n"
        val endedAt: Long get() = startedAt + durationMs
        /** The message call's id in the run's log, and in the logs that carry it again. */
        val streamCallId = "turn-${n - 1}:step:1:tool"
        /** How the reader's rows name the turn: its prompt, or a report's one line. */
        val label: String get() = if (prompt.startsWith("<timestamp>")) REPORT else prompt

        fun run(status: String = "FINISHED"): RunDto = RunDto(
            id = runId, agentId = AGENT_ID, status = status, createdAt = iso(startedAt),
            updatedAt = iso(if (status == "FINISHED") endedAt else startedAt), durationMs = durationMs.takeIf { status == "FINISHED" }, result = null,
        )

        /** The record's steps: the prompt in Project mode, the message call when the turn sent one ([result] its answer as the record has it), the end. */
        fun record(result: JsonObject = JsonObject(emptyMap())): List<JsonObject> = buildList {
            add(buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", startedAt.toString()) }) })
            if (message != null) {
                add(buildJsonObject { put("toolCall", buildJsonObject { put("tool", "CLIENT_SIDE_TOOL_V2_UNSPECIFIED"); put("toolCallId", "toolu_replies_$n"); put("name", "SendMessage"); put("rawArgs", args(message).toString()); put("modelCallId", "model_replies_$n") }) })
                add(buildJsonObject { put("finalToolResult", buildJsonObject { put("toolCallId", "toolu_replies_$n"); put("result", result) }) })
            }
            add(buildJsonObject { put("text", ""); put("isMessageDone", true) })
        }

        /** The run's log: a silent run's carries the last message sent ahead of its own events (#228); [ended] closes it with its result. */
        fun log(ended: Boolean = true): List<Pair<String, String>> = buildList {
            add("status" to """{"runId":"$runId","status":"RUNNING"}""")
            replays?.let { add("tool_call" to messageEvent(it.streamCallId, it.message!!, "completed", it.n)) }
            if (message != null) {
                add("tool_call" to messageEvent(streamCallId, message, "running", n))
                add("tool_call" to messageEvent(streamCallId, message, "completed", n))
            }
            if (ended) add("result" to """{"runId":"$runId","status":"FINISHED","text":"","durationMs":$durationMs}""")
        }
    }

    /**
     * The chat on the server: [inRecord] the account's record's turns, [listed] the run list's runs (each with its
     * log; [running] still under way), [inV0] the `/v0` transcript's prompts.
     */
    private fun serve(inRecord: List<Turn>, listed: List<Turn>, inV0: List<Turn> = inRecord, running: Turn? = null) {
        listed.forEach { t ->
            server.runs[t.runId] = t.run(if (t === running) "RUNNING" else "FINISHED")
            server.logs[t.runId] = t.log(ended = t !== running)
        }
        val newestRun = listed.maxBy { it.startedAt }
        server.agents[AGENT_ID] = AgentDto(
            id = AGENT_ID, name = NAME, status = if (running != null) "ACTIVE" else "IDLE", createdAt = iso(first.startedAt),
            updatedAt = iso(if (running != null) newestRun.startedAt else newestRun.endedAt), latestRunId = newestRun.runId, url = "https://cursor.com/agents/$AGENT_ID",
        )
        server.v0[AGENT_ID] = V0AgentDto(id = AGENT_ID, name = NAME, status = if (running != null) "RUNNING" else "FINISHED")
        server.transcripts[AGENT_ID] = v0(inV0)
        server.records[AGENT_ID] = inRecord.flatMap { it.record() }
    }

    private fun v0(turns: List<Turn>): List<V0ConversationMessageDto> = turns.map { V0ConversationMessageDto("${it.runId}-u", "user_message", it.prompt) }

    /**
     * [turn] starts elsewhere while the chat is open and at rest, and the phone follows its run to the end, as it
     * follows every turn of the chat on screen: the watch hears the turn start, the run's stream tells it, the run
     * ends. The turn is then drawn from the trace the stream gave. [recordHas] is the record's turns from the moment
     * the turn starts; without [turn], the record has not caught up with it.
     */
    private suspend fun FaultRig.followElsewhere(turn: Turn, recordHas: List<Turn>): ConversationState {
        settled()
        until("the watch held") { server.liveRequests.isNotEmpty() }
        server.runs[turn.runId] = turn.run("RUNNING")
        server.logs[turn.runId] = turn.log()
        // The stream tells the turn's first events, and the rest once the run is over by its record.
        server.outage(Route.Stream, Fault.StreamCut(events = 2), path = "/${turn.runId}/")
        server.records[AGENT_ID] = recordHas.flatMap { it.record() }
        server.transcripts[AGENT_ID] = v0(recordHas)
        server.agents[AGENT_ID] = server.agents.getValue(AGENT_ID).copy(status = "ACTIVE", latestRunId = turn.runId, updatedAt = iso(turn.startedAt))
        server.v0[AGENT_ID] = server.v0.getValue(AGENT_ID).copy(status = "RUNNING")
        val watches = server.liveRequests.size
        server.touch(AGENT_ID)
        until("\"${turn.label.take(30)}…\" followed", 30_000) { server.requests(Route.Stream).any { it.path.contains("/${turn.runId}/") } }
        server.runs[turn.runId] = turn.run()
        server.agents[AGENT_ID] = server.agents.getValue(AGENT_ID).copy(status = "IDLE", updatedAt = iso(turn.endedAt))
        server.v0[AGENT_ID] = server.v0.getValue(AGENT_ID).copy(status = "FINISHED")
        server.clear(Route.Stream)
        until("\"${turn.label.take(30)}…\"'s run seen to end", 30_000) { state().runStatus?.isActive != true && !state().isStreaming }
        // At rest again, the watch reads the chat from where the record's last read left it; a turn the record has
        // is then read in. (Found by the look for a next run a turn's end sets off, a run is followed before then.)
        until("the watch back after \"${turn.label.take(30)}…\"", 30_000) { server.liveRequests.size > watches }
        if (turn in recordHas) until("\"${turn.label.take(30)}…\" read from the record", 30_000) { replies(state()).any { it.first == turn.label } }
        return settled()
    }

    /**
     * [ids] fail every time they are asked for. The state's prefetch would carry them — each turn's last step, and a
     * message is these turns' last — so it carries nothing: a piece that fails is one the prefetch left out.
     */
    private fun failing(ids: Set<String>) {
        server.prefetchBlobs = 0
        server.blobAnswer = { _, blobId -> if (blobId in ids) server.gateway() else null }
    }

    /**
     * The previous turn's message gets its result in the record — the step is a new piece, which the server fails
     * every time — and that turn's run log is gone: a read of the turn now has no source for its reply.
     */
    private fun loseThePreviousReply(inRecord: List<Turn>): String {
        server.records[AGENT_ID] = inRecord.flatMap { if (it === previous) it.record(result = sent(it.n)) else it.record() }
        val piece = BlobFixtures.stepId(server.blobRecord(AGENT_ID)!!, inRecord.indexOf(previous), 0)
        failing(setOf(piece))
        server.logs.remove(previous.runId)
        return piece
    }

    // ---- the reader's side ---------------------------------------------------------------------------------------

    private fun rig(engine: TranscriptEngine, root: File = folder.newFolder()): FaultRig =
        FaultRig(server.baseUrl, root, readTimeoutMs = 8_000L, extended = true, engine = engine, recordWaits = quickWaits).also {
            it.now = now
            rigs += it
        }

    private fun FaultRig.state(): ConversationState = conversations.state(AGENT_ID).value

    /**
     * The chat as the reader sees it: each prompt (a report as its one line), with the coordinator's messages
     * drawn under it before the next.
     */
    private fun replies(state: ConversationState): List<Pair<String, List<String>>> {
        val rows = TranscriptPresenter().present(state.items, coordinatorMode = true, runActive = state.runStatus?.isActive == true || state.isStreaming).rows
        val out = ArrayList<Pair<String, MutableList<String>>>()
        fun walk(rows: List<TranscriptRow>) {
            for (row in rows) when (row) {
                is TranscriptRow.Item -> (row.item as? UserMessage)?.let { out += it.text to ArrayList() }
                is TranscriptRow.Event -> out += REPORT to ArrayList()
                is TranscriptRow.Events -> walk(row.rows)
                is TranscriptRow.Stretch -> row.entries.forEach { entry ->
                    when (entry) {
                        is TranscriptRow.Entry.Event -> out += REPORT to ArrayList()
                        is TranscriptRow.Entry.Events -> walk(entry.group.rows)
                        else -> Unit
                    }
                }
                is TranscriptRow.Message -> {
                    val text = CoordinatorTranscript.messageTexts(listOf(ActivityGroup(row.group.id, listOf(row.call)))).singleOrNull() ?: continue
                    (out.lastOrNull() ?: ("" to ArrayList<String>()).also { out += it }).second += text
                }
                else -> Unit
            }
        }
        walk(rows)
        return out
    }

    private fun expected(vararg turns: Turn): List<Pair<String, List<String>>> = turns.map { it.label to listOfNotNull(it.message) }

    private fun describe(replies: List<Pair<String, List<String>>>): String =
        replies.joinToString("\n") { (prompt, messages) -> "  ${prompt.take(40)} -> ${messages.map { it.take(40) }}" }

    private fun assertReplies(state: ConversationState, vararg turns: Turn) {
        val seen = replies(state)
        assertWithMessage("each reply under the turn that sent it; the reader saw\n${describe(seen)}\n").that(seen).containsExactlyElementsIn(expected(*turns)).inOrder()
    }

    /**
     * Opens the chat (the list read first, as the app does) and waits for it to settle: nothing loading, nothing
     * followed or replayed, and the server not asked anything for a while.
     */
    private suspend fun FaultRig.open(): ConversationState {
        agents.refresh()
        conversations.attach(AGENT_ID)
        return settled()
    }

    private suspend fun FaultRig.settled(quietMs: Long = 1_500L, limitMs: Long = 45_000L): ConversationState {
        val deadline = System.nanoTime() + limitMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val state = state()
            val last = server.seen.filter { it.route != Route.Live }.maxOfOrNull { it.atMillis } ?: 0L
            val quiet = server.nowMillis() - last >= quietMs
            if (quiet && state.items.isNotEmpty() && !state.isLoading && !state.isLoadingOlder && !state.isStreaming && state.runStatus?.isActive != true && state.traceStatus.pending == 0) return state
            delay(25)
        }
        val state = state()
        throw AssertionError("the chat never settled: loading=${state.isLoading} streaming=${state.isStreaming} run=${state.runStatus} traces=${state.traceStatus}\n${describe(replies(state))}")
    }

    /** Waits for [condition]; past [timeoutMs], fails with what the reader saw and what the server was asked. */
    private suspend fun FaultRig.until(what: String, timeoutMs: Long = 20_000L, condition: suspend () -> Boolean) {
        try {
            awaitUntil(timeoutMs, condition)
        } catch (_: TimeoutCancellationException) {
            val state = state()
            throw AssertionError(
                "$what: not within $timeoutMs ms (loading=${state.isLoading} streaming=${state.isStreaming} run=${state.runStatus} traces=${state.traceStatus})\n" +
                    "asked: ${server.seen.groupingBy { it.route }.eachCount()}\n${describe(replies(state))}",
            )
        }
    }

    /** Every frame the chat publishes from now on, as the screen collects them. */
    private class Frames(rig: FaultRig) {
        val seen = CopyOnWriteArrayList<ConversationState>()
        private val job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.conversations.state(AGENT_ID).collect { seen += it } }
        fun stop() = job.cancel()
    }

    /**
     * In every frame that shows a turn's prompt, the turn's reply is under it from the first frame it was on — and
     * no reply is ever drawn under another turn's prompt. With [pastLoading], the frames of a load the reader asked
     * for are passed over: it shows the chat as it reads it back in, from nothing.
     */
    private fun assertNeverLost(frames: Frames, vararg turns: Turn, pastLoading: Boolean = false) {
        frames.stop()
        val shown = HashSet<Turn>()
        frames.seen.forEachIndexed { i, state ->
            if (pastLoading && state.isLoading) return@forEachIndexed
            val replies = replies(state)
            for (turn in turns) {
                val text = turn.message!!
                replies.firstOrNull { it.first != turn.label && text in it.second }?.let { (other, _) ->
                    throw AssertionError("frame ${i + 1} of ${frames.seen.size} drew \"${text.take(30)}…\" under \"${other.take(30)}…\":\n${describe(replies)}")
                }
                val under = replies.firstOrNull { it.first == turn.label }?.second ?: continue
                if (text in under) shown += turn
                else if (turn in shown) throw AssertionError("frame ${i + 1} of ${frames.seen.size} took \"${text.take(30)}…\" off \"${turn.label.take(30)}…\" after it was shown:\n${describe(replies)}")
            }
        }
        turns.forEach { assertWithMessage("\"${it.message!!.take(30)}…\" shown under its turn at some point").that(shown).contains(it) }
        println("   ${frames.seen.size} frames, every reply kept under its own turn")
    }

    // ---- Beta: the account's record ------------------------------------------------------------------------------

    /**
     * Bennett's second frame. He is in the chat, so the phone follows every turn's run and draws each turn from the
     * trace its stream gave. The worker's report's run is followed like the others, and the record has not caught up
     * with its turn. Paired by position from the newest end, every turn took the run after its own: the previous
     * turn drew the latest's trace and so its reply, the latest drew the report's run — whose log carries the latest
     * message again, which the reader drops as a copy — leaving "Worked 3s", and the previous turn's own reply was
     * nowhere. Every version did so until the next read of the record; on 0.3.85 that read failed or was skipped
     * while the run list still landed, so it stayed. Paired by the account's timings, each turn draws its own run and
     * the report's run trails the record's turns until the record has its turn.
     */
    @Test
    fun `Beta - a worker's report run the record has not caught up with takes no turn's reply`() = runBlocking<Unit> {
        serve(inRecord = listOf(first), listed = listOf(first))
        val rig = rig(TranscriptEngine.BETA)
        assertReplies(rig.open(), first)
        val frames = Frames(rig)
        rig.followElsewhere(previous, recordHas = listOf(first, previous))
        val inRecord = listOf(first, previous, latest)
        assertReplies(rig.followElsewhere(latest, recordHas = inRecord), first, previous, latest)

        assertReplies(rig.followElsewhere(report, recordHas = inRecord), first, previous, latest)

        // The record catches up with the report's turn and its state read fails, as 0.3.85's did: the window read
        // before stands, and the run list is read fresh beside it.
        server.records[AGENT_ID] = (inRecord + report).flatMap { it.record() }
        server.transcripts[AGENT_ID] = v0(inRecord + report)
        server.outage(Route.RecordState, Fault.Gateway())
        val stateReads = server.requests(Route.RecordState).size
        val runReads = server.requests(Route.ListRuns).size
        rig.conversations.revalidate(AGENT_ID, force = true)
        rig.until("the state read failed and the run list read again") { server.requests(Route.RecordState).size > stateReads && server.requests(Route.ListRuns).size > runReads }
        assertReplies(rig.settled(), first, previous, latest)

        // The state read answers again: the report's turn takes its own run, and says nothing.
        server.clear(Route.RecordState)
        rig.conversations.revalidate(AGENT_ID, force = true)
        rig.until("the report's turn on screen") { replies(rig.state()).any { it.first == REPORT } }
        assertReplies(rig.settled(), first, previous, latest, report)
        assertNeverLost(frames, first, previous, latest)
    }

    /**
     * The other way round: the record has the newest turn — a prompt sent from the web, answered — and the run list
     * has not listed its run yet. By position every turn took the run before its own, the latest drawing the
     * previous turn's trace and the newest the latest's; by the timings the newest turn has none yet, and every
     * other turn has its own.
     */
    @Test
    fun `Beta - a turn the record has before the run list does takes no other turn's run`() = runBlocking<Unit> {
        serve(inRecord = listOf(first), listed = listOf(first))
        val rig = rig(TranscriptEngine.BETA)
        assertReplies(rig.open(), first)
        val frames = Frames(rig)
        rig.followElsewhere(previous, recordHas = listOf(first, previous))
        assertReplies(rig.followElsewhere(latest, recordHas = listOf(first, previous, latest)), first, previous, latest)

        val inRecord = listOf(first, previous, latest, newest)
        server.records[AGENT_ID] = inRecord.flatMap { it.record() }
        server.transcripts[AGENT_ID] = v0(inRecord)
        rig.conversations.revalidate(AGENT_ID, force = true)
        rig.until("the newest turn on screen") { replies(rig.state()).any { it.first == newest.label } }
        assertReplies(rig.settled(), first, previous, latest, newest)

        // The run list catches up.
        server.runs[newest.runId] = newest.run()
        server.logs[newest.runId] = newest.log()
        server.agents[AGENT_ID] = server.agents.getValue(AGENT_ID).copy(latestRunId = newest.runId, updatedAt = iso(newest.endedAt))
        val runReads = server.requests(Route.ListRuns).size
        rig.conversations.revalidate(AGENT_ID, force = true)
        rig.until("the run list read again") { server.requests(Route.ListRuns).size > runReads }
        assertReplies(rig.settled(), first, previous, latest, newest)
        assertNeverLost(frames, first, previous, latest, newest)
    }

    /**
     * Bennett's first frame. The chat is open and at rest, every reply shown. A worker's report starts elsewhere; the
     * watch hears it and the chat is read again — and that read finds the previous turn changed (its message's result
     * landed), the new piece failing, and the turn's log gone. The read has the turn without its reply; the reply stays.
     */
    @Test
    fun `Beta - a reply stays through the watch's read of its turn without it`() = runBlocking<Unit> {
        val inRecord = listOf(first, previous, latest)
        serve(inRecord = inRecord, listed = inRecord)
        val rig = rig(TranscriptEngine.BETA)
        assertReplies(rig.open(), first, previous, latest)
        rig.until("the watch held") { server.liveRequests.isNotEmpty() }
        val frames = Frames(rig)

        val piece = loseThePreviousReply(inRecord)
        // The report's run starts, its log carrying the latest message again; the record has not caught up with it.
        server.runs[report.runId] = report.run("RUNNING")
        server.logs[report.runId] = report.log(ended = false)
        server.outage(Route.Stream, Fault.StreamCut(events = 2), path = "/${report.runId}/")
        server.agents[AGENT_ID] = server.agents.getValue(AGENT_ID).copy(status = "ACTIVE", latestRunId = report.runId, updatedAt = iso(report.startedAt))
        server.v0[AGENT_ID] = server.v0.getValue(AGENT_ID).copy(status = "RUNNING")
        server.touch(AGENT_ID)
        rig.until("the watch's read at the changed turn, the report's run followed") { (server.blobReads[piece]?.get() ?: 0) > 0 && rig.state().runStatus?.isActive == true }

        // The report's turn ends, and the record catches up with it.
        server.runs[report.runId] = report.run()
        server.logs[report.runId] = report.log()
        server.agents[AGENT_ID] = server.agents.getValue(AGENT_ID).copy(status = "IDLE", updatedAt = iso(report.endedAt))
        server.v0[AGENT_ID] = server.v0.getValue(AGENT_ID).copy(status = "FINISHED")
        server.records[AGENT_ID] = (inRecord + report).flatMap { if (it === previous) it.record(result = sent(it.n)) else it.record() }
        server.clear(Route.Stream)
        rig.until("the report's run seen to end", 30_000) { rig.state().runStatus?.isActive != true && !rig.state().isStreaming }
        rig.conversations.revalidate(AGENT_ID, force = true)
        rig.until("the report's turn on screen") { replies(rig.state()).any { it.first == REPORT } }
        assertReplies(rig.settled(), first, previous, latest, report)
        assertNeverLost(frames, first, previous, latest)
    }

    /**
     * The saved copy: the chat read and left, then opened in a new process on the same phone. The saved copy shows
     * the previous turn's reply; the network's read of that turn — changed since, its new piece failing, its log
     * gone — has it without. The reply stays.
     */
    @Test
    fun `Beta - a reply the saved copy shows stays when the reopen's read of its turn comes back without it`() = runBlocking<Unit> {
        val inRecord = listOf(first, previous, latest)
        serve(inRecord = inRecord, listed = inRecord)
        val root = folder.newFolder("phone")
        val before = rig(TranscriptEngine.BETA, root)
        assertReplies(before.open(), first, previous, latest)
        before.conversations.detach(AGENT_ID)
        delay(500)
        before.close()

        val piece = loseThePreviousReply(inRecord)
        val rig = rig(TranscriptEngine.BETA, root)
        val frames = Frames(rig)
        rig.agents.refresh()
        rig.conversations.attach(AGENT_ID)
        rig.until("the reopen's read at the changed turn") { (server.blobReads[piece]?.get() ?: 0) > 0 }
        assertReplies(rig.settled(), first, previous, latest)
        assertNeverLost(frames, first, previous, latest)
    }

    /**
     * One source, then another: the chat read from the record — the previous turn's reply there, its run's log gone —
     * then from the run logs (the engine switched to Stable). The logs have no copy of that reply; it stays.
     */
    @Test
    fun `A reply the record showed stays when the chat is read from the run logs instead`() = runBlocking<Unit> {
        val inRecord = listOf(first, previous, latest)
        serve(inRecord = inRecord, listed = inRecord)
        server.logs.remove(previous.runId)
        val rig = rig(TranscriptEngine.BETA)
        assertReplies(rig.open(), first, previous, latest)
        val frames = Frames(rig)

        rig.capabilities = Capabilities.of(true, TranscriptEngine.STABLE)
        rig.conversations.detach(AGENT_ID)
        rig.conversations.attach(AGENT_ID)
        rig.until("the transcript read from `/v0`") { server.requests(Route.Conversation).isNotEmpty() }
        assertReplies(rig.settled(), first, previous, latest)
        assertNeverLost(frames, first, previous, latest)
    }

    // ---- Stable: `/v0` and the run logs --------------------------------------------------------------------------

    /**
     * The report's run is listed, over, and `/v0` has not caught up with its prompt: no turn takes its run. With the
     * run list unchanged the chat does not ask `/v0` again, so the reader reloads it — which empties the chat and
     * reads it back from nothing — and `/v0` has the report: from the reload's first whole frame on, each reply is
     * under its own turn and stays there.
     */
    @Test
    fun `Stable - a worker's report run the transcript has not caught up with takes no turn's reply`() = runBlocking<Unit> {
        val turns = listOf(first, previous, latest)
        serve(inRecord = turns, listed = turns + report, inV0 = turns)
        val rig = rig(TranscriptEngine.STABLE)
        val opening = Frames(rig)
        assertReplies(rig.open(), first, previous, latest)
        rig.watch(2_000) { assertReplies(rig.state(), first, previous, latest) }
        assertNeverLost(opening, first, previous, latest)

        server.transcripts[AGENT_ID] = v0(turns + report)
        val reread = Frames(rig)
        rig.conversations.reloadTranscript(AGENT_ID)
        rig.until("the report's turn on screen") { replies(rig.state()).any { it.first == REPORT } }
        assertReplies(rig.settled(), first, previous, latest, report)
        rig.watch(2_000) { assertReplies(rig.state(), first, previous, latest, report) }
        assertNeverLost(reread, first, previous, latest, pastLoading = true)
    }

    /** The report's run under way and `/v0` without its prompt yet: the newest prompt is not the running run's. */
    @Test
    fun `Stable - a worker's report run under way that the transcript has not caught up with takes no turn's reply`() = runBlocking<Unit> {
        val turns = listOf(first, previous, latest)
        serve(inRecord = turns, listed = turns + report, inV0 = turns, running = report)
        server.outage(Route.Stream, Fault.StreamCut(events = 2), path = "/${report.runId}/")
        val rig = rig(TranscriptEngine.STABLE)
        val frames = Frames(rig)
        rig.agents.refresh()
        rig.conversations.attach(AGENT_ID)
        rig.until("every turn's reply on screen while the report runs", 30_000) { replies(rig.state()).let { r -> r.size == 3 && r.all { it.second.isNotEmpty() } } && rig.state().traceStatus.pending == 0 }
        rig.watch(2_000) { assertReplies(rig.state(), first, previous, latest) }

        // The report's turn ends and `/v0` catches up.
        server.runs[report.runId] = report.run()
        server.logs[report.runId] = report.log()
        server.agents[AGENT_ID] = server.agents.getValue(AGENT_ID).copy(status = "IDLE", updatedAt = iso(report.endedAt))
        server.v0[AGENT_ID] = server.v0.getValue(AGENT_ID).copy(status = "FINISHED")
        server.transcripts[AGENT_ID] = v0(turns + report)
        server.clear(Route.Stream)
        rig.until("the report's run seen to end", 30_000) { rig.state().runStatus?.isActive != true && !rig.state().isStreaming }
        rig.conversations.revalidate(AGENT_ID, force = true)
        rig.until("the report's turn on screen") { replies(rig.state()).any { it.first == REPORT } }
        assertReplies(rig.settled(), first, previous, latest, report)
        assertNeverLost(frames, first, previous, latest)
    }

    /** `/v0` has the newest prompt and the run list has not listed its run yet: it takes no other turn's run. */
    @Test
    fun `Stable - a prompt whose run is not listed yet takes no other turn's run`() = runBlocking<Unit> {
        val turns = listOf(first, previous, latest)
        serve(inRecord = turns + newest, listed = turns, inV0 = turns + newest)
        val rig = rig(TranscriptEngine.STABLE)
        val frames = Frames(rig)
        val opened = rig.open()
        assertWithMessage(describe(replies(opened))).that(replies(opened)).containsExactlyElementsIn(expected(first, previous, latest) + (newest.prompt to emptyList())).inOrder()

        server.runs[newest.runId] = newest.run()
        server.logs[newest.runId] = newest.log()
        server.agents[AGENT_ID] = server.agents.getValue(AGENT_ID).copy(latestRunId = newest.runId, updatedAt = iso(newest.endedAt))
        rig.conversations.revalidate(AGENT_ID, force = true)
        rig.until("the newest turn's reply on screen") { replies(rig.state()).lastOrNull()?.second?.isNotEmpty() == true }
        assertReplies(rig.settled(), first, previous, latest, newest)
        assertNeverLost(frames, first, previous, latest, newest)
    }

    /**
     * The other switch: the chat read from the run logs, then from the record (the engine switched to Beta) — whose
     * piece with the previous turn's message fails every time. The record's read has the turn without it; it stays.
     */
    @Test
    fun `A reply the run logs showed stays when the chat is read from the record instead`() = runBlocking<Unit> {
        val inRecord = listOf(first, previous, latest)
        serve(inRecord = inRecord, listed = inRecord)
        val rig = rig(TranscriptEngine.STABLE)
        assertReplies(rig.open(), first, previous, latest)
        val frames = Frames(rig)

        val piece = BlobFixtures.stepId(server.blobRecord(AGENT_ID)!!, inRecord.indexOf(previous), 0)
        failing(setOf(piece))
        rig.capabilities = Capabilities.of(true, TranscriptEngine.BETA)
        rig.conversations.detach(AGENT_ID)
        rig.conversations.attach(AGENT_ID)
        rig.until("the record's read at the failing piece") { (server.blobReads[piece]?.get() ?: 0) > 0 }
        assertReplies(rig.settled(), first, previous, latest)
        assertNeverLost(frames, first, previous, latest)
    }

    companion object {
        const val AGENT_ID = "bc-replies-coordinator-0000000000000000000001"
        const val NAME = "Cursor for Android"
        const val WORKER = "bc-00000001-replies-worker-000000000000000000000001"
        const val REPORT = "(a worker's report)"
        const val MINUTE = 60_000L

        fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

        fun args(text: String): JsonObject = buildJsonObject { put("text", buildJsonObject { put("content", text) }) }

        fun sent(n: Int): JsonObject = buildJsonObject { put("success", buildJsonObject { put("timestamp", "1799999000000"); put("messageId", "msg_replies_$n") }) }

        fun messageEvent(callId: String, text: String, status: String, n: Int): String = buildJsonObject {
            put("callId", callId)
            put("name", "sendMessage")
            put("status", status)
            put("args", args(text))
            if (status == "completed") put("result", sent(n))
        }.toString()

        fun workerReport(at: Long): String =
            "<timestamp>${Instant.ofEpochMilli(at)}</timestamp>\n<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n" +
                "<task>\nkind: subagent\nstatus: success\ntask_id: $WORKER\ntitle: Find the old cause\ntool_call_id: toolu_notify_replies\nagent_id: $WORKER\ndetail: This is the last output of the subagent:\nThe replies are paired to the runs by position.\nAgent ID: $WORKER (Find the old cause)\n</task>\n</system_notification>\n" +
                "<user_query>Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required. Don't repeat the same confirmation every time.</user_query>"
    }
}
