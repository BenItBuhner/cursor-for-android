package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.HeadlessToolResult
import com.cursorforandroid.data.api.RunStreamEvent
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
 * Bennett's account, simulated rather than fixtured: a chat of 160 runs and nearly two thousand tool calls with
 * 40 000-character payloads, the server's logs expired for every run but the newest dozen, the newest run still
 * going, the transcript slow to arrive, the process dying mid-load, Extended mode on and off — and the one fact the
 * documented reference and the field disagree on, the order `/v1/agents/{id}/runs` lists runs in, exercised both
 * ways. Each case names the symptom it reproduced on 0.3.13: the tool calls missing behind the text, the running turn
 * not shown as running, the chat not loading at all.
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

    /** The account's record of the same chat: per turn its prompt, the reads with their results, and the reply. */
    private class FakeRecord(runs: Int, callsPerRun: Int, payloadChars: Int) : ConversationRecordApi {
        val steps: List<HeadlessStep> = (1..runs).flatMap { t ->
            listOf(HeadlessStep(userMessage = "Prompt $t")) +
                (1..callsPerRun).flatMap { n ->
                    listOf(
                        HeadlessStep(toolCall = HeadlessToolCall("rec-$t-$n", "read_file", buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) })),
                        HeadlessStep(toolResult = HeadlessToolResult("rec-$t-$n", buildJsonObject { put("content", JsonPrimitive("r".repeat(payloadChars))) })),
                    )
                } +
                listOf(HeadlessStep(text = "Reply $t"))
        }
        val calls = AtomicInteger()

        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
            calls.incrementAndGet()
            return HeadlessPage(steps.drop(startIndex).take(limit), startIndex, steps.size)
        }
    }

    private val api = StressApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_800_000_000_000L
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var cache: ConversationCache
    private lateinit var traces: TraceCache

    private val agentId = "bc-long"
    private val runs = 160
    private val callsPerRun = 12
    private val payloadChars = 40_000
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
        api.addFinishedAgent(agentId, "Long chat", *turns, firstRunAt = Instant.ofEpochMilli(now - runs * 3_600_000L).toString())
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
        api.pageSize = 10
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
        // Once fetched by id, a refresh does not read the sixteen pages again: the newest run is in hand.
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

    /**
     * The turns whose logs the server no longer has: in default mode their text stands and the chat says how many turns
     * lost their activity; in Extended mode the account's record fills them.
     */
    @Test
    fun `expired turns are counted in default mode and filled from the record in Extended mode`() = runBlocking<Unit> {
        api.ascendingRuns = true
        seed()
        val record = FakeRecord(runs, callsPerRun, payloadChars = 100)
        val documented = repository(hub(), record = record, capabilities = Capabilities.DOCUMENTED)
        documented.attach(agentId)
        assertOpensOnTheNewestTurns(documented)
        // Page up past the retained dozen: the turns beyond have no log to replay, and the chat says so.
        documented.loadOlder(agentId)
        awaitUntil { documented.state(agentId).value.items.count { it is UserMessage } == 20 && !documented.state(agentId).value.isLoadingOlder }
        awaitUntil(60_000) { documented.state(agentId).value.traceStatus.expired >= 20 - retained - 1 }
        assertThat(record.calls.get()).isEqualTo(0)
        documented.detach(agentId)

        // Extended mode reopens on the window that was open (twenty turns, from disk) and fills the expired ones.
        val extended = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        extended.attach(agentId)
        awaitUntil { !extended.state(agentId).value.isLoading && extended.state(agentId).value.items.count { it is UserMessage } >= 20 }
        awaitUntil(60_000) {
            val s = extended.state(agentId).value
            s.toolCalls().count { !it.isRunning } >= s.footers().size * callsPerRun && s.traceStatus.pending == 0
        }
        val filled = extended.state(agentId).value
        assertThat(filled.traceStatus.expired).isEqualTo(0)
        assertThat(filled.toolCalls().count { it.callId.startsWith("rec-") }).isAtLeast((20 - retained - 1) * callsPerRun)
        assertThat(record.calls.get()).isGreaterThan(0)
    }
}
