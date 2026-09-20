package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.SseToolCallTruncationDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.fixtures.BlobFixtures
import com.cursorforandroid.fixtures.SevenRunCoordinator
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Bennett's 2026-09-19 frame (see [SevenRunCoordinator]): a coordinator's message drawn four times in a row, once per
 * run of the four that followed it, each over that run's own footer. The runs' logs are where the copies come from —
 * the log of a run that sent nothing carries the last message sent as if it were the run's own — and the app drew
 * every `SendMessage` every log carried. These hold the invariants the transcript now keeps whichever source a turn
 * is drawn from: a message once, a silent run contributing to the stretch alone, one stretch between two messages
 * with the stats of every run it covers, and a running run's re-fetches replacing its snapshot rather than adding to it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CoordinatorDuplicateMessagesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    /** The record served for the chat, or nothing when the account service refuses (the documented path stands then). */
    @Volatile private var served: List<JsonObject> = emptyList()
    @Volatile private var recordRefused = false
    private lateinit var record: ConversationRecordApi

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = Instant.parse("2026-09-19T19:30:00Z").toEpochMilli()
    private val firstAt = now - 10 * 60_000L
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var cache: ConversationCache
    private lateinit var traces: TraceCache
    private val agentId = SevenRunCoordinator.AGENT_ID
    private lateinit var runs: List<SevenRunCoordinator.Run>

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
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("exchange_user_api_key") -> MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}""")
                    recordRefused -> MockResponse().setResponseCode(429).setBody("""{"code":"resource_exhausted","message":"Too many requests"}""")
                    path.endsWith("/FetchBackgroundComposer") -> {
                        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val start = body["startIndex"]?.jsonPrimitive?.int ?: 0
                        val limit = body["limit"]?.jsonPrimitive?.int ?: 200
                        val page = served.drop(start).take(limit)
                        MockResponse().setBody(buildJsonObject { put("responses", JsonArray(page)); put("totalResponses", served.size) }.toString())
                    }
                    path.endsWith("/GetLatestAgentConversationState") -> {
                        val timings = runs.joinToString(",") { r -> """{"durationMs":"${r.durationMs ?: 0}","timestampMs":"${firstAt + r.index * 60_000L + (r.durationMs ?: 0)}"}""" }
                        // The turns by their blob ids, as the account names them: the blob-backed read (see BlobFixtures).
                        val ids = blobs().turnIds.joinToString(",") { "\"$it\"" }
                        MockResponse().setBody("""{"latestConversationState":{"conversationState":{"turns":[$ids],"turnTimings":[$timings],"isRootProjectConversation":true}}}""")
                    }
                    path.endsWith("/GetBlobForAgentKV") -> {
                        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val id = body["blobId"]?.jsonPrimitive?.content ?: ""
                        val bytes = blobs().blobs[id] ?: return MockResponse().setResponseCode(404).setBody("""{"code":"not_found","message":"blob not found"}""")
                        MockResponse().setBody("""{"blobData":"${java.util.Base64.getEncoder().encodeToString(bytes)}"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        record = HeadlessConversationApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { now }))
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        AppClock.nowMillis = System::currentTimeMillis
    }

    /** The served steps as the blob-backed record, made again whenever the steps change (a re-fetch of the running turn serves more). */
    private var blobsFrom: List<JsonObject>? = null
    private var blobRecord: BlobFixtures.Record? = null
    private fun blobs(): BlobFixtures.Record = synchronized(this) {
        val steps = served
        if (blobsFrom !== steps) { blobRecord = BlobFixtures.record(steps); blobsFrom = steps }
        blobRecord!!
    }

    private fun hub() = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)

    private fun repository() = ConversationRepository(
        session, agents, prefs, hub(), attachments, cache, traces,
        isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        record = record, capabilities = { Capabilities.EXTENDED },
    )

    private suspend fun awaitUntil(timeoutMs: Long = 30_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    /**
     * The chat on the wire: the seven runs' records (the newest running), the agent active on the newest, the record
     * served as the account holds it, and every finished run's log on the stream. [v0Prompts] puts the prompts into
     * the documented transcript too; without them `/v0` lags the runs, as it does for a busy Project.
     */
    private suspend fun seed(leak: Boolean = true, v0Prompts: Boolean = false, liveEvents: Int = Int.MAX_VALUE) {
        runs = SevenRunCoordinator.runs(firstAt, leak)
        served = runs.flatMap { it.record }
        runs.forEach { run ->
            val at = Instant.ofEpochMilli(firstAt + run.index * 60_000L).toString()
            val ended = Instant.ofEpochMilli(firstAt + run.index * 60_000L + (run.durationMs ?: 0L)).toString()
            api.runs[run.id] = RunDto(id = run.id, agentId = agentId, status = run.status, createdAt = at, updatedAt = ended, durationMs = run.durationMs, result = null)
        }
        val newest = runs.last()
        api.agents[agentId] = AgentDto(id = agentId, name = SevenRunCoordinator.AGENT_NAME, status = "ACTIVE", createdAt = Instant.ofEpochMilli(firstAt).toString(), updatedAt = api.runs.getValue(newest.id).updatedAt, latestRunId = newest.id)
        api.v0[agentId] = V0AgentDto(id = agentId, name = SevenRunCoordinator.AGENT_NAME, status = "RUNNING")
        api.transcripts[agentId] = if (v0Prompts) runs.map { V0ConversationMessageDto("${it.id}-u", "user_message", it.prompt) } else emptyList()
        agents.refresh()
        runs.forEach { run ->
            val events = if (run.durationMs == null) run.events.take(liveEvents) else run.events
            events.forEach { streamer.emit(run.id, it) }
        }
    }

    private fun present(state: ConversationState): List<TranscriptRow> =
        TranscriptPresenter().present(state.items, coordinatorMode = true, runActive = state.runStatus?.isActive == true || state.isStreaming).rows

    private fun List<TranscriptRow>.messages(): List<String> = filterIsInstance<TranscriptRow.Message>().map { (it.call.payload as ToolPayload.CoordinatorMessage).message }

    private fun List<TranscriptRow>.stretches(): List<TranscriptRow.Stretch> = filterIsInstance<TranscriptRow.Stretch>()

    /** The rows between the message reading [after] and the one reading [before]. */
    private fun List<TranscriptRow>.between(after: String, before: String): List<TranscriptRow> {
        val from = indexOfFirst { it is TranscriptRow.Message && (it.call.payload as ToolPayload.CoordinatorMessage).message == after }
        val to = indexOfFirst { it is TranscriptRow.Message && (it.call.payload as ToolPayload.CoordinatorMessage).message == before }
        check(from >= 0 && to > from) { "no rows between $after and $before" }
        return subList(from + 1, to)
    }

    private fun ConversationState.messageCalls(): List<ToolCall> = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }
        .map { CoordinatorTranscript.reinterpret(it) }.filter { CoordinatorTranscript.isUserMessageCall(it) }

    /**
     * Runs 1 to 6 on screen with their footers and the running run's story: the frame as the phone would have it.
     * On the documented path every finished run's log is replayed before the traces read as whole; on the record
     * path a silent turn reads as shown from its record body, so its log's arrival is waited for by the diagnostics'
     * word that the turn is drawn from the log.
     */
    private suspend fun awaitLoaded(conversations: ConversationRepository) {
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.isNotEmpty() }
        awaitUntil { conversations.state(agentId).value.items.filterIsInstance<RunFooter>().size >= 6 && conversations.state(agentId).value.traceStatus.pending == 0 }
        awaitUntil {
            val load = conversations.loadDiagnostics(agentId)!!
            load.source != "record" || load.runs.filter { line -> SevenRunCoordinator.SILENT_RUNS.any { line.idTail.endsWith(it) } }.let { it.size == 3 && it.all { line -> line.trace == "shown" } }
        }
        // The running run's stream is being followed: its calls are on screen.
        awaitUntil { conversations.state(agentId).value.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.any { it.callId.startsWith("turn-6:") } }
    }

    /**
     * The frame itself: the account's record refused (the documented path stands), `/v0` behind the runs, the six
     * finished runs replayed from their logs, the seventh followed. Three messages were sent; three are drawn, each
     * once, in order, and everything between two of them is one stretch whose "Worked" sums the runs it covers.
     */
    @Test
    fun `the documented path draws each of the coordinator's messages once although the silent runs' logs carry the last one again`() = runBlocking<Unit> {
        recordRefused = true
        seed()
        val conversations = repository()
        conversations.attach(agentId)
        awaitLoaded(conversations)
        val state = conversations.state(agentId).value
        // The items carry what the logs carried: the message once per log that had it (the record refused, the logs are the source).
        assertThat(conversations.loadDiagnostics(agentId)!!.source).isEqualTo("runs")
        assertThat(state.messageCalls().count { it.callId == SevenRunCoordinator.M2_CALL_ID }).isEqualTo(4)

        val rows = present(state)
        assertThat(rows.messages()).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>().map { it.call.callId }).containsNoDuplicates()
        assertThat(rows.map { it.key }).containsNoDuplicates()
        // Between the second and the third message: run 2's remaining work and the three silent runs, as one stretch
        // worked for all their time — 20 s + 21 s + 22 s + 2 m 7 s — counting every run's work (the four edits of
        // one file read as one edit, as the desktop counts files; the seven workers addressed or checked; the six notes).
        val between = rows.between(SevenRunCoordinator.M2, SevenRunCoordinator.M6)
        assertThat(between.map { it::class.simpleName }).containsExactly("Stretch")
        val stretch = between.single() as TranscriptRow.Stretch
        assertThat(stretch.summary.action).isEqualTo("Worked 3m 10s")
        assertThat(stretch.summary.details).isEqualTo("1 edit · 7 agents · 6 notes")
        assertThat(stretch.summary.lineStats).isEqualTo("+4 -4")
        assertThat(stretch.entries.filterIsInstance<TranscriptRow.Entry.Footer>().map { it.footer.durationMs }).containsExactly(20_000L, 21_000L, 22_000L, 127_000L).inOrder()
        // Between the first two: run 1's tail and run 2's opening — its one footer, 59 s.
        val first = rows.between(SevenRunCoordinator.M1, SevenRunCoordinator.M2).single() as TranscriptRow.Stretch
        assertThat(first.summary.action).isEqualTo("Worked 59s")
        // After the third: run 6's tail and the running run, with no prompt between them on this path, are one
        // stretch — live, since the run still writes into it — run 6's footer inside it.
        val after = rows.drop(rows.indexOfLast { it is TranscriptRow.Message } + 1)
        assertThat(after.map { it::class.simpleName }).containsExactly("Stretch")
        val tail = after.single() as TranscriptRow.Stretch
        assertThat(tail.summary.action).isEqualTo("Working")
        assertThat(tail.summary.details).isEqualTo("1 edit · 5 agents · 1 note")
        assertThat(tail.entries.filterIsInstance<TranscriptRow.Entry.Footer>().map { it.footer.durationMs }).containsExactly(61_000L)
        // The diagnostics name the repeats, so a dump says which run's log said what again.
        val lines = conversations.loadDiagnostics(agentId)!!.runs
        assertThat(lines.filter { line -> SevenRunCoordinator.SILENT_RUNS.any { line.idTail.endsWith(it) } }.map { it.message }).containsExactly(
            "rendered=none via=log repeat=…1:tool",
            "rendered=none via=log repeat=…1:tool",
            "rendered=none via=log repeat=…1:tool",
        )
        assertThat(lines.single { it.idTail.endsWith("run-2") }.message).isEqualTo("rendered=yes via=log")
    }

    /**
     * The same chat in Extended mode's record path: the record has every turn, the silent turns with their calls
     * and no `SendMessage` among them — so they sent none, and their logs are not asked for (see
     * `RecordTurn.wantsLogForMessage`): the copies the logs carry never reach the screen, and the message is drawn
     * once, where the record has it, the silent turns reading as the stretch under it, footers summed.
     */
    @Test
    fun `the record path keeps the message in its own turn and asks no silent turn for its log`() = runBlocking<Unit> {
        seed()
        val conversations = repository()
        conversations.attach(agentId)
        awaitLoaded(conversations)
        // No finished turn's log was asked for: the record has each turn's calls, a message among them when one was sent.
        delay(300)
        assertThat(streamer.connections.distinct()).containsNoneOf("run-1", "run-2", "run-3", "run-4", "run-5", "run-6")
        val state = conversations.state(agentId).value
        assertThat(conversations.loadDiagnostics(agentId)!!.source).isEqualTo("record")
        assertThat(state.isProjectConversation).isTrue()

        val rows = present(state)
        assertThat(rows.messages()).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
        assertThat(rows.map { it.key }).containsNoDuplicates()
        // The message stands in run 2's turn — after the event that opened it, before the events of the silent turns.
        val m2 = rows.indexOfFirst { it is TranscriptRow.Message && (it.call.payload as ToolPayload.CoordinatorMessage).message == SevenRunCoordinator.M2 }
        val before = rows.subList(0, m2).stretches().last()
        assertThat(before.eventCount).isEqualTo(1)
        val between = rows.between(SevenRunCoordinator.M2, SevenRunCoordinator.M6)
        assertThat(between.map { it::class.simpleName }).containsExactly("Stretch")
        val stretch = between.single() as TranscriptRow.Stretch
        // The events that opened turns 3 to 6 — each followed by its turn's work, so none groups with the next.
        assertThat(stretch.eventCount).isEqualTo(4)
        assertThat(stretch.summary.action).isEqualTo("Worked 3m 10s")
        assertThat(stretch.entries.filterIsInstance<TranscriptRow.Entry.Footer>().map { it.footer.durationMs }).containsExactly(20_000L, 21_000L, 22_000L, 127_000L).inOrder()
        assertThat(stretch.summary.details).isEqualTo("4 events · 1 edit · 7 agents")
        // The diagnostics: the record had none for the silent turns, the log brought a repeat, nothing of it is drawn.
        val lines = conversations.loadDiagnostics(agentId)!!.runs.filter { line -> SevenRunCoordinator.SILENT_RUNS.any { line.idTail.endsWith(it) } }
        assertThat(lines).hasSize(3)
        lines.forEach { assertThat(it.message).isEqualTo("record=none rendered=none via=record") }
    }

    /** The control: the same seven runs with logs that carry their own events alone read the same. */
    @Test
    fun `clean logs render the same three messages and the same stretches`() = runBlocking<Unit> {
        seed(leak = false)
        val conversations = repository()
        conversations.attach(agentId)
        awaitLoaded(conversations)
        val rows = present(conversations.state(agentId).value)
        assertThat(rows.messages()).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
        val stretch = rows.between(SevenRunCoordinator.M2, SevenRunCoordinator.M6).single() as TranscriptRow.Stretch
        assertThat(stretch.summary.action).isEqualTo("Worked 3m 10s")
        assertThat(stretch.eventCount).isEqualTo(4)
        val lines = conversations.loadDiagnostics(agentId)!!.runs.filter { line -> SevenRunCoordinator.SILENT_RUNS.any { line.idTail.endsWith(it) } }
        lines.forEach { assertThat(it.message).isEqualTo("record=none rendered=none via=record") }
    }

    /**
     * The running run fetched three times as it goes: its stream delivers its calls in three bursts, and between
     * them the chat is loaded again as a return to the foreground loads it. Each time, the run's calls are on screen
     * once — the snapshot replaced by run id, never appended — and once the run ends its complete trace stands alone.
     */
    @Test
    fun `a running run re-fetched as it progresses replaces its snapshot and never appends`() = runBlocking<Unit> {
        seed(liveEvents = 3)
        val live = runs.last()
        val conversations = repository()
        conversations.attach(agentId)
        awaitLoaded(conversations)
        fun liveCalls(state: ConversationState): List<String> = state.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.filter { it.callId.startsWith("turn-6:") }.map { it.callId }
        // Burst one: the status and the first call, running then completed.
        assertThat(liveCalls(conversations.state(agentId).value)).containsExactly("turn-6:step:1:tool")
        assertThat(present(conversations.state(agentId).value).messages()).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()

        // Burst two, and the chat loaded again while the run goes on.
        live.events.drop(3).take(4).forEach { streamer.emit(live.id, it) }
        now += 6_000L
        conversations.revalidate(agentId)
        awaitUntil { liveCalls(conversations.state(agentId).value).size >= 3 && !conversations.state(agentId).value.isLoading }
        delay(200)
        assertThat(liveCalls(conversations.state(agentId).value)).containsExactly("turn-6:step:1:tool", "turn-6:step:2:tool", "turn-6:step:3:tool").inOrder()
        assertThat(present(conversations.state(agentId).value).messages()).hasSize(3)

        // Burst three: the rest of the run, its end included; loaded once more after it ended.
        live.events.drop(7).forEach { streamer.emit(live.id, it) }
        val ended = live.events.filterIsInstance<RunStreamEvent.ToolCall>().map { it.call.callId }.distinct()
        streamer.emit(live.id, RunStreamEvent.Result(live.id, RunStatus.FINISHED, "", 95_000L, null))
        streamer.emit(live.id, RunStreamEvent.Done)
        api.runs[live.id] = api.runs.getValue(live.id).copy(status = "FINISHED", durationMs = 95_000L)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "IDLE")
        awaitUntil { conversations.state(agentId).value.items.filterIsInstance<RunFooter>().any { it.runId == live.id } }
        now += 6_000L
        conversations.revalidate(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading }
        delay(200)
        val state = conversations.state(agentId).value
        assertThat(liveCalls(state)).containsExactlyElementsIn(ended).inOrder()
        assertThat(state.items.filterIsInstance<RunFooter>().count { it.runId == live.id }).isEqualTo(1)
        val rows = present(state)
        assertThat(rows.messages()).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
        assertThat(rows.stretches().last().summary.action).isEqualTo("Worked 1m 35s")
        assertThat(rows.map { it.key }).containsNoDuplicates()
    }

    /**
     * The record path, the running run saying the same words as an earlier message — a status given again — in a
     * call of its own. The record's copy of the live turn lags the stream and holds no message call yet, so the rule
     * for a silent turn's log (a copy of an earlier message ahead of the run's own events, told by its text) used to
     * take the live call for a copy and leave it out. The live turn's own words are never a copy of anything: the
     * identity rule alone applies to it, and a new call with new ids is drawn.
     */
    @Test
    fun `the running run's own message is drawn even when it says what an earlier message said`() = runBlocking<Unit> {
        seed()
        val live = runs.last()
        val conversations = repository()
        conversations.attach(agentId)
        awaitLoaded(conversations)
        val again = SseToolCallDto("turn-6:step:7:tool", "sendMessage", ToolCall.STATUS_COMPLETED, buildJsonObject { put("text", buildJsonObject { put("content", SevenRunCoordinator.M6) }) }, buildJsonObject { put("success", buildJsonObject { put("messageId", "msg_07Again") }) })
        streamer.emit(live.id, RunStreamEvent.ToolCall(again))
        awaitUntil { conversations.state(agentId).value.messageCalls().any { it.callId == "turn-6:step:7:tool" } }
        delay(300)
        val state = conversations.state(agentId).value
        assertThat(present(state).messages()).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6, SevenRunCoordinator.M6).inOrder()
        assertThat(conversations.loadDiagnostics(agentId)!!.runs.last().message).startsWith("record=none rendered=yes via=live")
    }

    /**
     * A restart: the record's turns are read back from disk before the network answers, and the message is drawn
     * once from the copies as it was from the record.
     */
    @Test
    fun `the copies restored from disk draw the message once as well`() = runBlocking<Unit> {
        seed()
        val first = repository()
        first.attach(agentId)
        awaitLoaded(first)
        awaitUntil { traces.runIds(agentId).count { it.startsWith(TraceCache.RECORD_TURN_KEY_PREFIX) } >= 7 && cache.read(agentId) != null }
        first.detach(agentId)
        // The next process: the disk answers before the network, and the network answers the same.
        val again = repository()
        again.attach(agentId)
        awaitLoaded(again)
        val rows = present(again.state(agentId).value)
        assertThat(rows.messages()).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, SevenRunCoordinator.M6).inOrder()
        assertThat((rows.between(SevenRunCoordinator.M2, SevenRunCoordinator.M6).single() as TranscriptRow.Stretch).summary.action).isEqualTo("Worked 3m 10s")
    }

    /**
     * A message whose arguments never reached this device — the stream left them out for size — is not given
     * anyone else's words: the row says the update could not be read, and the diagnostics carry the call's shape.
     */
    @Test
    fun `a message the stream left without its text is a row saying so, never another message's text`() = runBlocking<Unit> {
        // The documented path: the logs are the source, and run 4's log is the one with the cut message.
        recordRefused = true
        seed(leak = false)
        // Run 4's log: its own message, arguments truncated by the stream.
        streamer.reset("run-4")
        val cut = runs.first { it.id == "run-4" }
        streamer.emit(cut.id, RunStreamEvent.Status(cut.id, RunStatus.RUNNING))
        streamer.emit(cut.id, RunStreamEvent.ToolCall(SseToolCallDto("turn-3:step:0:tool", "sendMessage", ToolCall.STATUS_COMPLETED, null, buildJsonObject { put("success", buildJsonObject { put("messageId", "msg_04Cut") }) }, SseToolCallTruncationDto(args = true))))
        cut.events.drop(1).forEach { streamer.emit(cut.id, it) }
        val conversations = repository()
        conversations.attach(agentId)
        awaitLoaded(conversations)
        val rows = present(conversations.state(agentId).value)
        val messages = rows.filterIsInstance<TranscriptRow.Message>()
        assertThat(messages.map { (it.call.payload as ToolPayload.CoordinatorMessage).message }).containsExactly(SevenRunCoordinator.M1, SevenRunCoordinator.M2, "", SevenRunCoordinator.M6).inOrder()
        val unread = messages[2].call.payload as ToolPayload.CoordinatorMessage
        assertThat(unread.missing).isTrue()
        val line = conversations.loadDiagnostics(agentId)!!.runs.single { it.idTail.endsWith("run-4") }
        assertThat(line.message).isEqualTo("rendered=missing via=log")
    }
}
