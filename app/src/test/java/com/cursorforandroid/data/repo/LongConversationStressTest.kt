package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.HeadlessToolResult
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.TurnTiming
import com.cursorforandroid.data.api.dto.ListRunsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.dto.V0ConversationResponseDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bennett's account, simulated rather than fixtured: a chat of 320 turns and nearly four thousand tool calls with
 * 40 000-character payloads on the run logs, the server's logs expired for every run but the newest dozen, the
 * newest run still going, the transcript slow to arrive, the process dying mid-load, Extended mode on and off — and
 * the one fact the documented reference and the field disagree on, the order `/v1/agents/{id}/runs` lists runs in,
 * exercised both ways. The default-mode cases name the symptom each reproduced on 0.3.13: the tool calls missing
 * behind the text, the running turn not shown as running, the chat not loading at all. The Extended cases exercise
 * the account's record as the transcript's source: the newest page whole from the record, whatever the run logs
 * kept; the first content from disk in under a second; older pages on request; the documented path standing in when
 * the record has nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LongConversationStressTest {

    @get:Rule
    val folder = TemporaryFolder()

    /**
     * The chat's server. [ascendingRuns] lists runs oldest first (as the field has shown it), else newest first (as the
     * reference documents it). [transcriptTimeouts] makes that many `/v0` transcript reads time out first, as a slow
     * network does with a transcript of hundreds of turns.
     */
    private class StressApi : FakeCursorApi() {
        @Volatile var ascendingRuns = false
        val transcriptTimeouts = AtomicInteger(0)
        val runListCalls = ConcurrentHashMap<String, Int>()

        override suspend fun listRuns(id: String, limit: Int, cursor: String?): ListRunsResponseDto {
            runListCalls.merge(cursor ?: "first", 1, Int::plus)
            if (!ascendingRuns) return super.listRuns(id, limit, cursor)
            listRunsCalls++
            runsGate?.await()
            val all = runs.values.filter { it.agentId == id && it.id !in runsHiddenFromList }.sortedWith(compareBy({ it.createdAt }, { it.id }))
            val start = cursor?.let { c -> all.indexOfFirst { it.id == c }.takeIf { it >= 0 } } ?: 0
            val size = minOf(limit, pageSize)
            val items = all.drop(start).take(size)
            val next = all.getOrNull(start + size)?.id
            runsLaterPagesGate?.takeIf { cursor != null }?.await()
            return ListRunsResponseDto(items = items, nextCursor = next)
        }

        override suspend fun conversationV0(id: String): V0ConversationResponseDto {
            if (transcriptTimeouts.get() > 0) {
                transcriptTimeouts.decrementAndGet()
                conversationCalls++
                throw SocketTimeoutException("timeout")
            }
            return super.conversationV0(id)
        }
    }

    /**
     * The account's record of the same chat (`FetchBackgroundComposer` / `GetLatestAgentConversationState`): per turn
     * its prompt, [callsPerRun] reads with their results, and the reply — the newest turn under way, its reply not
     * written yet. Generated by index, so a record of thousands of steps costs nothing until a page is read.
     * [gate] holds every answer (a slow connection); [failures] makes that many reads fail first; [empty] answers as
     * a record the account does not serve for this chat.
     */
    private class FakeRecord(@Volatile var turns: Int, val callsPerRun: Int, val payloadChars: Int, val firstTurnAt: Long, @Volatile var liveSteps: Int = 3) : ConversationRecordApi {
        val perTurn = 2 + 2 * callsPerRun
        /** The record's size: every turn whole but the newest, which has [liveSteps] of its steps so far. */
        val total: Int get() = (turns - 1) * perTurn + liveSteps
        val calls = AtomicInteger()
        val stateCalls = AtomicInteger()
        val failures = AtomicInteger(0)
        @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var empty = false
        /** The record keeps the prompts and files the bodies elsewhere: every step but a prompt comes back blank. */
        @Volatile var promptsOnly = false
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()

        fun stepAt(index: Int): HeadlessStep {
            val t = index / perTurn + 1
            val k = index % perTurn
            return when {
                k == 0 -> HeadlessStep(userMessage = "Prompt $t")
                promptsOnly -> HeadlessStep()
                k == perTurn - 1 -> HeadlessStep(text = "Reply $t")
                k % 2 == 1 -> HeadlessStep(toolCall = HeadlessToolCall("rec-$t-${(k + 1) / 2}", "read_file", buildJsonObject { put("path", JsonPrimitive("app/src/File${(k + 1) / 2}.kt")) }))
                else -> HeadlessStep(toolResult = HeadlessToolResult("rec-$t-${k / 2}", buildJsonObject { put("content", JsonPrimitive("r".repeat(payloadChars))) }))
            }
        }

        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
            calls.incrementAndGet()
            requests += startIndex to limit
            gate?.await()
            if (failures.get() > 0) {
                failures.decrementAndGet()
                throw SocketTimeoutException("record timeout")
            }
            if (empty) return HeadlessPage(emptyList(), startIndex, 0)
            val end = minOf(total, startIndex + limit)
            val steps = if (startIndex >= end) emptyList() else (startIndex until end).map { stepAt(it) }
            return HeadlessPage(steps, startIndex, total)
        }

        override suspend fun state(agentId: String): RecordState {
            stateCalls.incrementAndGet()
            gate?.await()
            if (empty) return RecordState(0, emptyList(), 0, false, 0L, 0L)
            val timings = (0 until turns).map { t -> if (t == turns - 1) TurnTiming(null, null) else TurnTiming(30_000L, firstTurnAt + t * 3_600_000L + 30_000L) }
            return RecordState(turns, timings, pendingToolCalls = 1, isRootProject = false, numPriorInteractionUpdates = total.toLong(), rewindEpoch = 0L)
        }
    }

    private val api = StressApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The clock: before the fake stamps follow-up runs (April 2026), so a follow-up sorts after every seeded run. */
    private var now = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli()
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var cache: ConversationCache
    private lateinit var traces: TraceCache

    private val agentId = "bc-long"
    private val runs = 320
    private val callsPerRun = 12
    private val payloadChars = 40_000
    /** The record's payloads: smaller than the logs' so four thousand of them fit a test JVM; the count is what is asserted. */
    private val recordPayloadChars = 8_000
    private val firstRunAt get() = now - runs * 3_600_000L
    /** The runs whose logs the server still has: the newest dozen. */
    private val retained = 12

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val disk = JsonDiskCache(folder.newFolder("cache"))
        attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10)
        cache = ConversationCache(disk.child("conversations"))
        traces = TraceCache(disk.child("traces"))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun hub() = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)

    private fun repository(hub: LiveRunHub, record: ConversationRecordApi? = null, capabilities: Capabilities = Capabilities.DOCUMENTED) = ConversationRepository(
        session, agents, prefs, hub, attachments, cache, traces,
        isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        record = record, capabilities = { capabilities },
    )

    private suspend fun awaitUntil(timeoutMs: Long = 30_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun readCall(runId: String, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("x".repeat(payloadChars))); put("path", JsonPrimitive("app/src/File$n.kt")) }) },
        ),
    )

    /**
     * The chat: [runs] turns an hour apart, the newest still running; the logs of the newest [retained] finished runs
     * on the server whole, every older log expired, the live run's stream open with its first events.
     */
    private suspend fun seed() {
        val turns = Array(runs) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Long chat", *turns, firstRunAt = Instant.ofEpochMilli(firstRunAt).toString())
        // The newest turn is under way: its run RUNNING, its reply not in the transcript yet.
        val live = "run-$runs"
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        agents.refresh()
        for (i in 1 until runs) {
            val runId = "run-$i"
            if (i > runs - 1 - retained) {
                streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
                streamer.emit(runId, RunStreamEvent.Thinking("Turn $i."))
                repeat(callsPerRun) { n -> streamer.emit(runId, readCall(runId, n + 1)) }
                streamer.emit(runId, RunStreamEvent.Assistant("Reply $i"))
                streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
            } else {
                streamer.emit(runId, RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
            }
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        streamer.emit(live, RunStreamEvent.Thinking("Working on the newest turn."))
        streamer.emit(live, readCall(live, 1))
    }

    private fun ConversationState.toolCalls() = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }
    private fun ConversationState.prompts() = items.filterIsInstance<UserMessage>().map { it.text }
    private fun ConversationState.footers() = items.filterIsInstance<RunFooter>().map { it.runId }

    /** What the screen would draw: the rows, with the newest stretch live when the run is. */
    private fun ConversationState.rows(): List<TranscriptRow> = TranscriptRows.of(items, coordinatorMode = false, runActive = runStatus?.isActive == true || isStreaming)

    /**
     * Whatever order the server lists runs in, the chat opens on its newest turns — the live one followed and shown
     * as running, the finished ones behind it with their tool calls — and never on a window of old runs dressed in
     * the newest prompts' text.
     */
    private suspend fun assertOpensOnTheNewestTurns(conversations: ConversationRepository) {
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.isNotEmpty() }
        // Symptom 3 on 0.3.13: the ongoing run not shown as ongoing (the run followed was the newest of the wrong page).
        try {
            awaitUntil { conversations.state(agentId).value.isStreaming }
        } catch (t: Throwable) {
            val s = conversations.state(agentId).value
            println("SYMPTOMS activeRun=${s.activeRunId} status=${s.runStatus} streaming=${s.isStreaming} prompts=${s.prompts().take(2)}..${s.prompts().takeLast(1)} footers=${s.footers()} toolCalls=${s.toolCalls().size} hasOlder=${s.hasOlder} err=${s.error} row.isRunning=${agents.agent(agentId)?.isRunning} row.latestRun=${agents.agent(agentId)?.latestRunId} connections=${streamer.connections.distinct()}")
            throw t
        }
        val state = conversations.state(agentId).value
        assertThat(state.activeRunId).isEqualTo("run-$runs")
        assertThat(state.runStatus?.isActive).isTrue()
        assertThat(state.prompts()).containsExactly(*((runs - 9)..runs).map { "Prompt $it" }.toTypedArray()).inOrder()
        // The window's footers are the window's own runs, none of them the chat's first turns.
        assertThat(state.footers()).containsExactly(*((runs - 9) until runs).map { "run-$it" }.toTypedArray()).inOrder()
        // Symptom 2 on 0.3.13: the text without its tool calls. The window's nine finished runs have theirs, payloads and all.
        awaitUntil(60_000) { conversations.state(agentId).value.toolCalls().count { !it.isRunning } >= 9 * callsPerRun }
        val loaded = conversations.state(agentId).value
        val finished = loaded.toolCalls().filter { !it.isRunning }
        assertThat(finished.map { it.callId }.toSet()).containsAtLeastElementsIn(((runs - 9) until runs).flatMap { r -> (1..callsPerRun).map { "run-$r-c$it" } })
        assertThat(finished.count { (it.payload as? ToolPayload.FileContent)?.content?.length == payloadChars }).isAtLeast(9 * callsPerRun)
        // The live run's first tool call is on screen too, in a stretch that reads as working.
        assertThat(loaded.toolCalls().any { it.callId == "run-$runs-c1" }).isTrue()
        assertThat((loaded.rows().last() as? TranscriptRow.Stretch)?.live).isTrue()
    }

    @Test
    fun `runs listed newest first - the chat opens on its newest turns with their tool calls and the live run running`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        val conversations = repository(hub())
        conversations.attach(agentId)
        assertOpensOnTheNewestTurns(conversations)
    }

    @Test
    fun `runs listed oldest first - the chat still opens on its newest turns, not on old runs wearing the newest prompts`() = runBlocking<Unit> {
        api.ascendingRuns = true
        seed()
        val conversations = repository(hub())
        conversations.attach(agentId)
        assertOpensOnTheNewestTurns(conversations)
    }

    /**
     * An oldest-first list longer than the load will page (small pages here): the runs read are old ones and are not
     * laid under the newest prompts; the newest run alone stands, fetched by id, paired with the newest prompt and
     * followed. The prompts before it show without runs, and the chat says nothing false about their activity.
     */
    @Test
    fun `past the page bound on an oldest-first list, the newest run alone stands, fetched by id`() = runBlocking<Unit> {
        api.ascendingRuns = true
        // Sixty-four pages, past the forty an oldest-first list is read to its end within (MAX_ASCENDING_RUN_PAGES).
        api.pageSize = 5
        seed()
        val conversations = repository(hub())
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.isStreaming }
        val state = conversations.state(agentId).value
        assertThat(state.activeRunId).isEqualTo("run-$runs")
        assertThat(state.prompts()).containsExactly(*((runs - 9)..runs).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(state.footers()).isEmpty()
        assertThat(state.hasOlder).isTrue()
        assertThat(state.traceStatus).isEqualTo(TraceStatus())
        assertThat(api.getRunCalls).isAtLeast(1)
        // Once fetched by id, a refresh does not read the pages again: the newest run is in hand.
        val listCalls = api.listRunsCalls
        conversations.reload(agentId)
        awaitUntil { api.listRunsCalls > listCalls }
        delay(300)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.isStreaming }
        assertThat(api.listRunsCalls - listCalls).isEqualTo(1)
    }

    /** An oldest-first list read to its end once is not read again on a refresh: the row's latest run is already in hand. */
    @Test
    fun `an oldest-first list read to its end is not paged again on a refresh`() = runBlocking<Unit> {
        api.ascendingRuns = true
        seed()
        val conversations = repository(hub())
        conversations.attach(agentId)
        assertOpensOnTheNewestTurns(conversations)
        val listCalls = api.listRunsCalls
        conversations.reload(agentId)
        awaitUntil { api.listRunsCalls > listCalls }
        delay(300)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.isStreaming }
        assertThat(api.listRunsCalls - listCalls).isEqualTo(1)
        assertThat(conversations.state(agentId).value.footers()).containsExactly(*((runs - 9) until runs).map { "run-$it" }.toTypedArray()).inOrder()
    }

    /**
     * What 0.3.13 left on Bennett's disk: the process died with the first page persisted and the rest of the list
     * unread, in the field's order — a page of the chat's oldest twenty runs, marked incomplete, laid under the newest
     * prompts. The restart must not open the chat on it, and must recover once the network answers, whether the
     * transcript arrives slowly or not at all on the first try.
     */
    @Test
    fun `a restart on a persisted page of old runs never shows them under the newest prompts`() = runBlocking<Unit> {
        api.ascendingRuns = true
        seed()
        val oldest = api.runs.values.filter { it.agentId == agentId }.sortedBy { it.createdAt }.take(20)
        cache.write(
            com.cursorforandroid.data.local.CachedConversation(
                agentId = agentId,
                messages = api.transcripts.getValue(agentId),
                runs = oldest,
                agentUpdatedAtMillis = 1L,
                runsComplete = false,
                olderRunsCursor = "run-21",
                window = 10,
            ),
        )
        // The network is slow: the transcript times out once and the run list's later pages are held for a moment.
        api.transcriptTimeouts.set(1)
        api.runsLaterPagesGate = kotlinx.coroutines.CompletableDeferred()

        val next = repository(hub())
        next.attach(agentId)
        awaitUntil { next.state(agentId).value.items.isNotEmpty() }
        // From the disk: the newest prompts alone, with no old run's footer dressed under them, and nothing running yet.
        val fromDisk = next.state(agentId).value
        assertThat(fromDisk.prompts()).containsExactly(*((runs - 9)..runs).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(fromDisk.footers()).isEmpty()
        assertThat(fromDisk.activeRunId).isNull()

        api.runsLaterPagesGate!!.complete(Unit)
        api.runsLaterPagesGate = null
        assertOpensOnTheNewestTurns(next)
        // The transcript's timeout was said while it stood, and cleared by the reload that brought the transcript.
        next.reload(agentId)
        awaitUntil { !next.state(agentId).value.isLoading && next.state(agentId).value.transcriptError == null }
        assertThat(next.state(agentId).value.prompts()).hasSize(10)
    }

    /**
     * A transcript of hundreds of turns times out twice on a slow network. The chat is not "unavailable" for that: the
     * run list still opens the newest turns, the failure is said, and the transcript lands on the next attempt.
     */
    @Test
    fun `a transcript that times out is said, not swallowed, and the chat still opens on its runs`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        api.transcriptTimeouts.set(2)
        val conversations = repository(hub())
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading }
        val degraded = conversations.state(agentId).value
        // Symptom 1 on 0.3.13: the chat failing to load outright. The runs are on screen and the failure is visible.
        assertThat(degraded.items).isNotEmpty()
        assertThat(degraded.transcriptError).isNotNull()
        assertThat(degraded.activeRunId).isEqualTo("run-$runs")
        awaitUntil { conversations.state(agentId).value.isStreaming }

        var calls = api.conversationCalls
        conversations.reload(agentId)
        awaitUntil { api.conversationCalls > calls && !conversations.state(agentId).value.isLoading }
        assertThat(conversations.state(agentId).value.transcriptError).isNotNull()
        calls = api.conversationCalls
        conversations.reload(agentId)
        awaitUntil { api.conversationCalls > calls && !conversations.state(agentId).value.isLoading }
        assertThat(conversations.state(agentId).value.transcriptError).isNull()
        assertThat(conversations.state(agentId).value.prompts()).hasSize(10)
    }

    private fun record() = FakeRecord(runs, callsPerRun, recordPayloadChars, firstRunAt)

    /** The record's tool calls of turns [from]..[to] (inclusive), by id. */
    private fun recordCalls(from: Int, to: Int) = (from..to).flatMap { t -> (1..callsPerRun).map { "rec-$t-$it" } }

    /**
     * Extended mode: the chat opens on the record's newest ten turns — every tool call of the nine finished ones from
     * the record, whatever the run logs kept (their logs expired for all but the newest dozen runs here, and the
     * record is asked, not the logs) — the live run followed and shown working, the runs giving the footers, and the
     * turns before the window a scroll away, also from the record.
     */
    @Test
    fun `extended - the chat opens on the record's newest page, every tool call present, the live run followed`() = runBlocking<Unit> {
        api.ascendingRuns = true
        seed()
        val record = record()
        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.isNotEmpty() }
        awaitUntil { conversations.state(agentId).value.isStreaming }
        val state = conversations.state(agentId).value
        assertThat(state.prompts()).containsExactly(*((runs - 9)..runs).map { "Prompt $it" }.toTypedArray()).inOrder()
        val finished = state.toolCalls().filter { !it.isRunning }
        // Symptom 2 on 0.3.19: text with no tool calls. Here every finished turn of the page has all twelve, from the record.
        assertThat(finished.map { it.callId }).containsAtLeastElementsIn(recordCalls(runs - 9, runs - 1))
        assertThat(finished.count { (it.payload as? ToolPayload.FileContent)?.content?.length == recordPayloadChars }).isAtLeast(9 * callsPerRun)
        assertThat(state.footers()).containsExactly(*((runs - 9) until runs).map { "run-$it" }.toTypedArray()).inOrder()
        assertThat(state.activeRunId).isEqualTo("run-$runs")
        assertThat(state.hasOlder).isTrue()
        assertThat(state.traceStatus).isEqualTo(TraceStatus(shown = 10))
        // The live run's first events land (a burst is one publication, a beat later) and its stretch reads as working.
        awaitUntil { conversations.state(agentId).value.toolCalls().any { it.callId == "run-$runs-c1" } }
        assertThat((conversations.state(agentId).value.rows().last() as? TranscriptRow.Stretch)?.live).isTrue()
        // No run log was replayed for the page: the record is the source. Only the live run's stream was opened.
        assertThat(streamer.connections.distinct()).containsExactly("run-$runs")
        val diagnostics = conversations.loadDiagnostics(agentId)!!
        assertThat(diagnostics.source).isEqualTo("record")
        assertThat(diagnostics.record!!.turnCount).isEqualTo(runs)
        assertThat(diagnostics.windowStart).isEqualTo(runs - 10)

        // Older turns, a page at a time, from the record: their footers from the state's timings where the run list has not reached.
        conversations.loadOlder(agentId)
        awaitUntil(60_000) { conversations.state(agentId).value.prompts().size >= 20 && !conversations.state(agentId).value.isLoadingOlder }
        val widened = conversations.state(agentId).value
        assertThat(widened.prompts().first()).isEqualTo("Prompt ${runs - 19}")
        assertThat(widened.toolCalls().map { it.callId }).containsAtLeastElementsIn(recordCalls(runs - 19, runs - 1))
        assertThat(widened.footers()).hasSize(19)
        assertThat(widened.footers().first()).isEqualTo("run-${runs - 19}")
    }

    /**
     * A restart on a chat visited before: the record's window is on screen from disk — prompts and every tool call —
     * in under a second, before the record, the runs or the state have answered (they are held here), and the network
     * then brings the live run without disturbing what is shown.
     */
    @Test
    fun `extended - a restart shows the record's window from disk in under a second, before the network answers`() = runBlocking<Unit> {
        api.ascendingRuns = true
        seed()
        val record = record()
        val first = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        first.attach(agentId)
        awaitUntil { first.state(agentId).value.isStreaming && first.state(agentId).value.toolCalls().count { !it.isRunning } >= 9 * callsPerRun }
        awaitUntil { cache.read(agentId)?.value?.record != null }
        first.detach(agentId)
        awaitUntil { first.state(agentId).value.isStreaming.not() }

        // The next process: a slow network holds every answer.
        record.gate = kotlinx.coroutines.CompletableDeferred()
        api.runsGate = kotlinx.coroutines.CompletableDeferred()
        val next = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        val startedAt = System.nanoTime()
        next.attach(agentId)
        awaitUntil(5_000) { next.state(agentId).value.toolCalls().count { !it.isRunning } >= 9 * callsPerRun }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val fromDisk = next.state(agentId).value
        println("STRESS first content from cache in ${elapsedMs}ms: prompts=${fromDisk.prompts().size} toolCalls=${fromDisk.toolCalls().size}")
        assertThat(elapsedMs).isLessThan(1_000)
        assertThat(fromDisk.prompts()).containsExactly(*((runs - 9)..runs).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(fromDisk.toolCalls().map { it.callId }).containsAtLeastElementsIn(recordCalls(runs - 9, runs - 1))
        assertThat(fromDisk.isLoading).isTrue()

        record.gate!!.complete(Unit)
        api.runsGate!!.complete(Unit)
        awaitUntil { !next.state(agentId).value.isLoading && next.state(agentId).value.isStreaming }
        val settled = next.state(agentId).value
        assertThat(settled.prompts()).hasSize(10)
        assertThat(settled.toolCalls().map { it.callId }).containsAtLeastElementsIn(recordCalls(runs - 9, runs - 1))
        assertThat(settled.footers()).hasSize(9)
        assertThat(settled.transcriptError).isNull()
    }

    /**
     * The process dies mid-load, with the record's page persisted and the runs never answered: the restart shows the
     * page from disk and completes it once the network answers.
     */
    @Test
    fun `extended - a restart mid-load reopens on the persisted record page and completes it`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        val record = record()
        api.runsGate = kotlinx.coroutines.CompletableDeferred()
        val first = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        first.attach(agentId)
        awaitUntil { cache.read(agentId)?.value?.record?.turns?.isNotEmpty() == true }
        // Killed before the runs answered; the next process finds the record's page on disk and a network that answers.
        first.detach(agentId)
        api.runsGate!!.complete(Unit)
        api.runsGate = null

        val next = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        next.attach(agentId)
        awaitUntil { next.state(agentId).value.items.isNotEmpty() }
        assertThat(next.state(agentId).value.prompts()).containsExactly(*((runs - 9)..runs).map { "Prompt $it" }.toTypedArray()).inOrder()
        awaitUntil { !next.state(agentId).value.isLoading && next.state(agentId).value.isStreaming }
        val settled = next.state(agentId).value
        assertThat(settled.footers()).containsExactly(*((runs - 9) until runs).map { "run-$it" }.toTypedArray()).inOrder()
        assertThat(settled.toolCalls().map { it.callId }).containsAtLeastElementsIn(recordCalls(runs - 9, runs - 1))
    }

    /**
     * A record the account answers nothing for (a chat it does not serve this way): the documented path stands in,
     * as it did before, and the diagnostics say the record was empty.
     */
    @Test
    fun `extended - a record with nothing for the chat leaves the documented path standing`() = runBlocking<Unit> {
        api.ascendingRuns = true
        seed()
        val record = record().apply { empty = true }
        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        conversations.attach(agentId)
        assertOpensOnTheNewestTurns(conversations)
        val diagnostics = conversations.loadDiagnostics(agentId)!!
        assertThat(diagnostics.source).isEqualTo("runs")
        assertThat(diagnostics.record!!.empty).isTrue()
        // Asked once, not again on every refresh.
        val before = record.calls.get()
        conversations.reload(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.isStreaming }
        assertThat(record.calls.get()).isEqualTo(before)
    }

    /**
     * The record failing to answer on a refresh — the network, not the record — leaves the copy shown standing and
     * says so; the next refresh that goes through clears it. On a chat never read, the documented path stands in.
     */
    @Test
    fun `extended - a record that cannot be read keeps the copy shown and says so`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        val record = record()
        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        conversations.attach(agentId)
        awaitUntil { conversations.state(agentId).value.isStreaming && conversations.state(agentId).value.toolCalls().count { !it.isRunning } >= 9 * callsPerRun }
        record.failures.set(1)
        var calls = record.calls.get()
        conversations.reload(agentId)
        awaitUntil { record.calls.get() > calls && !conversations.state(agentId).value.isLoading }
        val degraded = conversations.state(agentId).value
        assertThat(degraded.transcriptError).isNotNull()
        assertThat(degraded.toolCalls().map { it.callId }).containsAtLeastElementsIn(recordCalls(runs - 9, runs - 1))
        assertThat(conversations.loadDiagnostics(agentId)!!.record!!.error).isNotNull()
        calls = record.calls.get()
        conversations.reload(agentId)
        awaitUntil { record.calls.get() > calls && !conversations.state(agentId).value.isLoading }
        awaitUntil { conversations.state(agentId).value.transcriptError == null }

        // A chat never read from the record, whose record fails: the documented endpoints show what they can.
        val fresh = FakeRecord(runs, callsPerRun, recordPayloadChars, firstRunAt).apply { failures.set(1) }
        val other = repository(hub(), record = fresh, capabilities = Capabilities.EXTENDED)
        val otherCache = ConversationCache(JsonDiskCache(folder.newFolder("other")).child("c"))
        val otherRepo = ConversationRepository(session, agents, prefs, hub(), attachments, otherCache, TraceCache(JsonDiskCache(folder.newFolder("t")).child("t")), isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope, record = fresh, capabilities = { Capabilities.EXTENDED })
        otherRepo.attach(agentId)
        assertOpensOnTheNewestTurns(otherRepo)
        assertThat(otherRepo.loadDiagnostics(agentId)!!.source).isEqualTo("runs")
        other.detach(agentId)
    }

    /**
     * A follow-up from this device in Extended mode: the prompt shows at once behind the record's turns, its run is
     * followed, and once the record has the turn and the list its run, the record's copy stands and the prompt shows
     * once — never twice, never paired with another turn's run.
     */
    @Test
    fun `extended - a follow-up trails the record's turns, streams, and is taken over by the record once`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        // The newest run finished: the chat is idle and takes a follow-up.
        val last = "run-$runs"
        api.runs[last] = api.runs.getValue(last).copy(status = "FINISHED", result = "Reply $runs", durationMs = 30_000)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "FINISHED")
        api.transcripts[agentId] = api.transcripts.getValue(agentId) + V0ConversationMessageDto("m-reply-$runs", "assistant_message", "Reply $runs")
        agents.refresh()
        val record = record().apply { liveSteps = perTurn }
        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.footers().size == 10 }

        val followUpText = "Prompt ${runs + 1}"
        assertThat(conversations.sendFollowUp(agentId, followUpText).isSuccess).isTrue()
        awaitUntil { conversations.state(agentId).value.isStreaming }
        val pending = conversations.state(agentId).value
        assertThat(pending.prompts()).containsExactly(*((runs - 9)..(runs + 1)).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(pending.footers()).hasSize(10)
        val runId = pending.activeRunId!!
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, readCall(runId, 1))
        streamer.emit(runId, RunStreamEvent.Assistant("Reply ${runs + 1}"))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply ${runs + 1}", 5_000, null))
        streamer.emit(runId, RunStreamEvent.Done)
        awaitUntil { !conversations.state(agentId).value.isStreaming && conversations.state(agentId).value.footers().size == 11 }

        // The record has caught up (the fake grows by the turn); the next read takes the turn over from the local
        // copy — the prompt once, in the window's newest place — while the trace the stream gave stands for it.
        record.turns = runs + 1
        val reads = record.calls.get()
        conversations.reload(agentId)
        // Everything the refresh brings, awaited in full: the record's tail (the window's prompts), the state's turn
        // count, and the publication that carries both (a burst publishes once, a beat later).
        awaitUntil {
            val s = conversations.state(agentId).value
            record.calls.get() > reads && !s.isLoading && s.prompts().first() == "Prompt ${runs - 8}" &&
                s.footers().size == 10 && conversations.loadDiagnostics(agentId)?.record?.turnCount == runs + 1
        }
        val settled = conversations.state(agentId).value
        assertThat(settled.prompts()).containsExactly(*((runs - 8)..(runs + 1)).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(settled.prompts().count { it == followUpText }).isEqualTo(1)
        assertThat(settled.footers()).hasSize(10)
        assertThat(settled.footers().last()).isEqualTo(runId)
        assertThat(settled.toolCalls().any { it.callId == "$runId-c1" }).isTrue()
        assertThat(conversations.loadDiagnostics(agentId)!!.record!!.turnCount).isEqualTo(runs + 1)
    }

    /**
     * The false "cancelled" of 0.3.21: the account runs on (the row says so, with the newest word), the record's last
     * turn is in progress, and the `/v1` record of the run — on disk from an earlier visit and on the server — says
     * cancelled. The freshest word wins: the chat reads as running, the newest turn wears no "Cancelled" footer and no
     * "Run cancelled" notice, and its steps from the record are on screen.
     */
    @Test
    fun `extended - a running account beats a cancelled run record, cached or served`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        val live = "run-$runs"
        // The server's run record lags the account and calls the run cancelled; the row (the account) runs on, newer.
        api.runs[live] = api.runs.getValue(live).copy(status = "CANCELLED", updatedAt = Instant.ofEpochMilli(now - 600_000L).toString())
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = Instant.ofEpochMilli(now).toString())
        // The account's word (the running set the account list keeps in Extended mode): running, with the row's
        // activity newer than the run record; the row's own run-level status stays the record's, as it does there.
        api.v0[agentId] = api.v0.getValue(agentId).copy(status = "RUNNING")
        agents.refresh()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot(agentId, name = "Long chat", archived = false, status = RunStatus.RUNNING)))
        awaitUntil { agentId in agents.runningScan.value.all }
        // And the copy on disk from an earlier visit says the same of the run.
        val record = record()
        cache.write(
            com.cursorforandroid.data.local.CachedConversation(
                agentId = agentId,
                messages = emptyList(),
                runs = api.runs.values.filter { it.agentId == agentId }.sortedByDescending { it.createdAt }.take(20),
                agentUpdatedAtMillis = 1L,
                runsComplete = false,
                olderRunsCursor = "run-300",
                window = 10,
            ),
        )
        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        conversations.attach(agentId)
        // From the disk copy already: running, not cancelled.
        awaitUntil { conversations.state(agentId).value.items.isNotEmpty() || conversations.state(agentId).value.runStatus != null }
        assertThat(conversations.state(agentId).value.runStatus).isNotEqualTo(RunStatus.CANCELLED)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.prompts().size == 10 }
        val state = conversations.state(agentId).value
        assertThat(state.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(state.footers()).doesNotContain(live)
        assertThat(state.footers()).hasSize(9)
        assertThat(state.items.filterIsInstance<com.cursorforandroid.domain.NoticeCard>()).isEmpty()
        // The newest turn's steps so far, from the record.
        assertThat(state.toolCalls().any { it.callId == "rec-$runs-1" }).isTrue()
        assertThat(state.toolCalls().map { it.callId }).containsAtLeastElementsIn(recordCalls(runs - 9, runs - 1))
        assertThat(conversations.loadDiagnostics(agentId)!!.runs.last().status).isEqualTo("CANCELLED")
    }

    /**
     * A record that keeps the prompts and files the bodies elsewhere (0.3.21's outright failure: prompts and nothing
     * else): the prompts render at once, the steps of each turn come from its run's log — progressively, newest first
     * — and a turn whose log is gone says so itself. Never an empty transcript.
     */
    @Test
    fun `extended - a record without bodies renders prompts at once and each turn's steps from its log, or says why not`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        val record = record().apply { promptsOnly = true }
        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        conversations.attach(agentId)
        awaitUntil { conversations.state(agentId).value.prompts().size == 10 }
        awaitUntil { conversations.state(agentId).value.isStreaming }
        // The newest page: every finished turn's twelve calls, from the logs the server still has.
        awaitUntil(60_000) { conversations.state(agentId).value.toolCalls().count { !it.isRunning } >= 9 * callsPerRun }
        val page = conversations.state(agentId).value
        assertThat(page.toolCalls().map { it.callId }).containsAtLeastElementsIn(((runs - 9) until runs).flatMap { r -> (1..callsPerRun).map { "run-$r-c$it" } })
        assertThat(page.footers()).containsExactly(*((runs - 9) until runs).map { "run-$it" }.toTypedArray()).inOrder()
        awaitUntil { conversations.state(agentId).value.traceStatus == TraceStatus(shown = 10) }
        assertThat(page.items.filterIsInstance<com.cursorforandroid.domain.NoticeCard>()).isEmpty()

        // Older turns: the ones whose logs expired say so, one notice each; the rest have their steps.
        conversations.loadOlder(agentId)
        awaitUntil(60_000) {
            val s = conversations.state(agentId).value
            s.prompts().size == 20 && !s.isLoadingOlder && s.traceStatus.pending == 0
        }
        val widened = conversations.state(agentId).value
        val expiredTurns = (runs - 19) until (runs - retained)
        assertThat(widened.traceStatus.expired).isEqualTo(expiredTurns.count())
        val notices = widened.items.filterIsInstance<com.cursorforandroid.domain.NoticeCard>()
        assertThat(notices).hasSize(expiredTurns.count())
        assertThat(notices.all { it.title == "This turn's activity is no longer available" }).isTrue()
        assertThat(widened.toolCalls().map { it.callId }).containsAtLeastElementsIn(((runs - retained) until runs).flatMap { r -> (1..callsPerRun).map { "run-$r-c$it" } })
        // On a restart the replayed steps are on screen from disk with the record's prompts, before the network answers.
        awaitUntil { traces.runIds(agentId).any { it.startsWith("run-") } }
        conversations.detach(agentId)
        record.gate = kotlinx.coroutines.CompletableDeferred()
        api.runsGate = kotlinx.coroutines.CompletableDeferred()
        val next = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        next.attach(agentId)
        awaitUntil(5_000) { next.state(agentId).value.toolCalls().count { !it.isRunning } >= 9 * callsPerRun }
        assertThat(next.state(agentId).value.prompts().size).isAtLeast(10)
        record.gate!!.complete(Unit)
        api.runsGate!!.complete(Unit)
        awaitUntil { !next.state(agentId).value.isLoading }
    }

    /**
     * Catching up is quiet: opening the chat and paging older turns publish the transcript a handful of times, not
     * once per item; the rows already shown keep their ids when older pages insert above them, so the list stays
     * anchored where the reader is; a burst of the live run's deltas lands as one publication or two.
     */
    @Test
    fun `extended - pages land as single publications, shown rows keep their ids, live bursts coalesce`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        val record = record()
        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        val publications = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val collector = scope.launch {
            conversations.state(agentId).collect { s -> publications += s.items.map { it.id } }
        }
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.isStreaming && conversations.state(agentId).value.footers().size == 9 }
        delay(300)
        val opening = publications.distinct()
        println("STRESS publications on open: ${opening.size}")
        // Cache miss: the record, the runs (with the state), the live run's first events — a handful, not per item.
        assertThat(opening.size).isAtMost(8)
        val shownBefore = conversations.state(agentId).value.items.map { it.id }

        publications.clear()
        conversations.loadOlder(agentId)
        awaitUntil(60_000) { conversations.state(agentId).value.prompts().size == 20 && !conversations.state(agentId).value.isLoadingOlder }
        delay(300)
        val paging = publications.distinct().filter { it.size > shownBefore.size }
        println("STRESS publications while paging older: ${paging.size}")
        assertThat(paging.size).isAtMost(3)
        val after = conversations.state(agentId).value.items.map { it.id }
        // Every row that was on screen is still there, in the same order, under the same id: the older page went above.
        assertThat(after.filter { it in shownBefore.toSet() }).containsExactlyElementsIn(shownBefore).inOrder()
        assertThat(after.takeLast(shownBefore.size)).containsExactlyElementsIn(shownBefore).inOrder()

        publications.clear()
        val live = "run-$runs"
        repeat(40) { streamer.emit(live, RunStreamEvent.Assistant("word $it ")) }
        awaitUntil { conversations.state(agentId).value.items.filterIsInstance<com.cursorforandroid.domain.AssistantMessage>().any { it.markdown.contains("word 39") } }
        delay(300)
        println("STRESS publications for 40 deltas: ${publications.distinct().size}")
        assertThat(publications.distinct().size).isAtMost(6)
        collector.cancel()
    }

    /**
     * Default mode, the `/v0` transcript slow: the runs render first — footers, the traces the logs still have, the
     * live run — and the prompts' text lays over them when the transcript lands. A transcript that fails is said
     * under the runs, with the diagnostics naming the failure, and the runs stay.
     */
    @Test
    fun `default - the runs render before a slow transcript, which merges when it lands or is said when it fails`() = runBlocking<Unit> {
        api.ascendingRuns = false
        seed()
        api.conversationGate = kotlinx.coroutines.CompletableDeferred()
        val conversations = repository(hub())
        val startedAt = System.nanoTime()
        conversations.attach(agentId)
        awaitUntil(5_000) { conversations.state(agentId).value.footers().size >= 9 }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val runsFirst = conversations.state(agentId).value
        println("STRESS runs on screen before the transcript in ${elapsedMs}ms")
        assertThat(elapsedMs).isLessThan(1_000)
        assertThat(runsFirst.prompts()).isEmpty()
        assertThat(runsFirst.footers()).containsExactly(*((runs - 9) until runs).map { "run-$it" }.toTypedArray()).inOrder()
        awaitUntil { conversations.state(agentId).value.isStreaming }
        awaitUntil(60_000) { conversations.state(agentId).value.toolCalls().count { !it.isRunning } >= 9 * callsPerRun }

        api.conversationGate!!.complete(Unit)
        api.conversationGate = null
        awaitUntil { conversations.state(agentId).value.prompts().size == 10 && !conversations.state(agentId).value.isLoading }
        assertThat(conversations.state(agentId).value.prompts()).containsExactly(*((runs - 9)..runs).map { "Prompt $it" }.toTypedArray()).inOrder()
        assertThat(conversations.state(agentId).value.toolCalls().count { !it.isRunning }).isAtLeast(9 * callsPerRun)

        // The transcript fails on a refresh: said, with Retry and the diagnostics; the runs and their traces stay.
        api.transcriptTimeouts.set(1)
        val calls = api.conversationCalls
        conversations.reload(agentId)
        awaitUntil { api.conversationCalls > calls && !conversations.state(agentId).value.isLoading }
        val failed = conversations.state(agentId).value
        assertThat(failed.transcriptError).isEqualTo("Cursor took too long to respond.")
        assertThat(failed.footers()).hasSize(9)
        assertThat(failed.toolCalls().count { !it.isRunning }).isAtLeast(9 * callsPerRun)
        assertThat(conversations.loadDiagnostics(agentId)!!.transcriptError).isEqualTo("Cursor took too long to respond.")
    }
}
