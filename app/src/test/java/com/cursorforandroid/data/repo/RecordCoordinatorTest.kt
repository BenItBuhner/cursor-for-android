package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.TurnTiming
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.CachedTrace
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.fixtures.CoordinatorFixtures
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
import kotlinx.serialization.json.jsonArray
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * A coordinator's chat read from the account's record (Extended mode) as Bennett's v0.3.21 frame had it (see
 * [CoordinatorFixtures], `record_coordinator_pages.json`: his message, the coordinator's narration and update, 148
 * silent injected turns, his next messages): the coordinator's updates are on screen whichever shape the record
 * gave them in — whole, in `is_streaming` pieces, streamed back — the run of silent turns reads as one stretch, a
 * turn the record shows without its update is completed from the run's own log, and "Reload transcript" throws the
 * copies away and reads again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecordCoordinatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fixture = CoordinatorFixtures.json("record_coordinator_pages.json")
    private val responses: JsonArray = fixture.getValue("responses").jsonArray
    private val turnStarts = fixture.getValue("turnStarts").jsonArray.map { it.jsonPrimitive.int }

    /** The account service on the wire: the record paged as asked, the state, the session handshake. */
    private val server = MockWebServer()
    private val fetches = AtomicInteger(0)
    /** The record served: the fixture's responses, or another list a test puts in their place. */
    @Volatile private var served: List<JsonObject> = responses.map { it.jsonObject }
    private lateinit var record: ConversationRecordApi

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = Instant.parse("2026-09-16T12:00:00Z").toEpochMilli()
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var cache: ConversationCache
    private lateinit var traces: TraceCache
    private val agentId = "bc-revenue"

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
                    path.endsWith("/FetchBackgroundComposer") -> {
                        fetches.incrementAndGet()
                        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val start = body["startIndex"]?.jsonPrimitive?.int ?: 0
                        val limit = body["limit"]?.jsonPrimitive?.int ?: 200
                        val page = served.drop(start).take(limit)
                        MockResponse().setBody(buildJsonObject { put("responses", JsonArray(page)); put("totalResponses", served.size) }.toString())
                    }
                    path.endsWith("/GetLatestAgentConversationState") -> {
                        // One turn per prompt, each twelve seconds long, an hour apart: the footers of the turns the run list does not reach.
                        val turns = served.count { it.containsKey("humanMessage") || it.containsKey("userMessage") }
                        val timings = (0 until turns).joinToString(",") { t -> """{"durationMs":"12000","timestampMs":"${now - (turns - t) * 3_600_000L + 12_000L}"}""" }
                        val ids = (0 until turns).joinToString(",") { "\"turn-$it\"" }
                        MockResponse().setBody("""{"latestConversationState":{"conversationState":{"turns":[$ids],"turnTimings":[$timings],"isRootProjectConversation":true}}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        // The step-indexed wire (`FetchBackgroundComposer`), whose captured shapes these fixtures are — the shapes the
        // record carried a turn's error and a streamed call's pieces in. The blob-backed read, which the server
        // answers today, is covered by BlobRecordTest and the fault tests (see BlobFixtures).
        record = HeadlessConversationApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { now }), readsTurns = false)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun hub() = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)

    private fun repository(record: ConversationRecordApi? = this.record) = ConversationRepository(
        session, agents, prefs, hub(), attachments, cache, traces,
        isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        record = record, capabilities = { Capabilities.EXTENDED },
    )

    private suspend fun awaitUntil(timeoutMs: Long = 30_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    /** The chat's runs: one per turn of the record, finished, an hour apart, their logs expired (the record is the transcript). */
    private suspend fun seedRuns(turns: Int) {
        val prompts = (1..turns).map { Triple("run-$it", "Prompt $it", "Reply $it") }.toTypedArray()
        api.addFinishedAgent(agentId, "Revenue Scaling Pipeline", *prompts, firstRunAt = Instant.ofEpochMilli(now - turns * 3_600_000L).toString())
        agents.refresh()
        for (i in 1..turns) {
            streamer.emit("run-$i", RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
            streamer.emit("run-$i", RunStreamEvent.Done)
        }
    }

    private fun ConversationState.messages(): List<String> = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }
        .map { CoordinatorTranscript.reinterpret(it) }
        .mapNotNull { (it.payload as? ToolPayload.CoordinatorMessage)?.takeUnless { m -> m.missing }?.message }

    private fun ConversationState.rows(): List<TranscriptRow> = TranscriptRows.of(CoordinatorTranscript.present(items, coordinatorMode = true), coordinatorMode = true)

    @Test
    fun `the coordinator's updates are on screen from the record, in every shape it recorded them, and the silent turns read as one stretch`() = runBlocking<Unit> {
        seedRuns(turnStarts.size)
        val conversations = repository()
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.isNotEmpty() }
        // The newest turns first; the reader scrolls up to the rest, the record paging in behind.
        var pages = 0
        while (conversations.state(agentId).value.hasOlder && pages++ < 40) {
            // One page at a time, as a scroll would ask: the previous page's read has to have settled first.
            awaitUntil { !conversations.state(agentId).value.isLoadingOlder }
            val before = conversations.state(agentId).value.items.size
            conversations.loadOlder(agentId)
            awaitUntil(10_000) { val s = conversations.state(agentId).value; (s.items.size > before && !s.isLoadingOlder) || !s.hasOlder }
        }
        awaitUntil { conversations.state(agentId).value.items.count { it is com.cursorforandroid.domain.UserMessage } >= 3 }
        val state = conversations.state(agentId).value
        assertThat(state.isProjectConversation).isTrue()

        // Every update the coordinator sent: the one recorded whole, the one in is_streaming pieces, the one streamed back.
        assertThat(state.messages()).containsExactly(
            "Shards are queued: kitchen v2 on R6, backyard v2 on Y1, the limb passes on their own workers. I will report as each lands, and the slice cutter is going in as a separate worker.",
            "All four shards rendered and their PRs are merged (#157\u2013#163); #165 and #166 are yours and stay open. The slice cutter is at 80 %: every scene is cut, reordering lands tonight.",
            "The phone worker has the Fold8 and Fold8 Ultra on its list and is rendering app-demo shots on every device; first frames in about twenty minutes.",
        ).inOrder()
        // The other coordinator tools are there too, as themselves.
        val calls = state.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }
        assertThat(calls.any { it.name == "createAgent" && it.payload is ToolPayload.WorkerAction }).isTrue()
        assertThat(calls.any { it.name == "sendToAgent" && CoordinatorTranscript.isCoordinatorCall(it) }).isTrue()
        // The turns whose update the record had were not replayed from their logs; the silent ones paired with a run were asked once, and their logs were gone.
        assertThat(streamer.connections.distinct()).containsNoneOf("run-151", "run-150")

        // The rows: each update a message of its own; everything between two of them one stretch, the 148 silent
        // turns behind one line inside it; no event row of its own; no note alone.
        val rows = state.rows()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).hasSize(3)
        assertThat(rows.none { it is TranscriptRow.Event || it is TranscriptRow.Events }).isTrue()
        assertThat(rows.filterIsInstance<TranscriptRow.Stretch>().none { it.single is TranscriptRow.Entry.Note }).isTrue()
        val wall = rows.filterIsInstance<TranscriptRow.Stretch>().single { it.eventCount == 148 }
        assertThat(wall.summary.text).startsWith("Worked ")
        assertThat(wall.summary.text).contains("148 events")
        val group = wall.listed.filterIsInstance<TranscriptRow.Entry.Events>().single()
        assertThat(group.group.count).isEqualTo(148)
        assertThat(group.group.startsOpen).isFalse()
        assertThat(rows.map { it.key }).containsNoDuplicates()
    }

    /**
     * Bennett's v0.3.35 chat (internal/reference/coordinator-notified-but-no-message.png): the record carries the
     * coordinator's narration of every turn — "Bennett is told what comes next" — but not its `SendMessage`, which
     * only the run's log has; and between two of his messages the Project injects dozens of turns (a subscribed pull
     * request's change, a subagent's report), each a run of its own. The run list's first page reaches twenty runs;
     * a user's turn behind more injected turns than that had no run paired, so its log was never replayed and its
     * reply never shown — the note that he was told stood alone. The run list is paged until the window's turns
     * have their runs, and every reply the logs still have is on screen, each turn with its footer.
     */
    @Test
    fun `a user's turn behind more injected turns than the run list's first page still gets its reply from the run's log`() = runBlocking<Unit> {
        val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
        val userTurns = listOf(
            "So where we at rn" to "The phones pass is at shot 12 and the realism coordinator is re-rendering the hands; next results in an hour.",
            "All right, the limit is now reset. You can continue." to "Resumed all six paused workers where they stopped; the week-1 posting batches are next.",
            "Please keep on pushing on all fronts." to "Pushing on every front: three new workers on the drop-shipping strategies, the rest continue.",
            "Fable 5.1 temporarily hit limits, but it is back so you can keep on working now. Thank you." to "All six paused workers are resumed where they stopped; next results due: the week-1 posting batches for the three winners and the ten pitch packages.",
        )
        val injectedPerGap = 30
        // The record: each of the user's turns as narration alone (the SendMessage lives in the run's log, not here),
        // then the injected turns, each answered with a remark.
        val record = ArrayList<JsonObject>()
        val runIds = ArrayList<String>()
        var t = now - 6 * 3_600_000L
        userTurns.forEachIndexed { u, (prompt, _) ->
            record += buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", t.toString()) }) }
            record += buildJsonObject { put("text", "All paused workers are resumed on Fable, Bennett is told what comes next, and the blocker entry is cleared from notes.") }
            record += buildJsonObject { put("text", ""); put("isMessageDone", true) }
            runIds += "run-user-$u"
            t += 60_000L
            repeat(injectedPerGap) { j ->
                record += buildJsonObject { put("humanMessage", buildJsonObject { put("text", wall[(u * injectedPerGap + j) % wall.size]); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", t.toString()) }) }
                record += buildJsonObject { put("text", "Noted; nothing for Bennett in this one.") }
                record += buildJsonObject { put("text", ""); put("isMessageDone", true) }
                runIds += "run-inj-$u-$j"
                t += 60_000L
            }
        }
        served = record
        // One run per turn, finished, a minute apart, in the same order as the record's turns.
        var at = now - 6 * 3_600_000L
        val prompts = runIds.map { id -> Triple(id, if (id.startsWith("run-user")) userTurns[id.removePrefix("run-user-").toInt()].first else "<system_notification>…</system_notification>", "") }
        api.addFinishedAgent(agentId, "Revenue Scaling Pipeline", *prompts.toTypedArray(), firstRunAt = Instant.ofEpochMilli(at).toString())
        // addFinishedAgent spaces the runs an hour apart; the order is what pairs them, and it is the record's.
        agents.refresh()
        // The logs: the user's runs still have the coordinator's SendMessage whole; the injected turns' logs are gone.
        userTurns.forEachIndexed { u, (_, reply) ->
            val runId = "run-user-$u"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Assistant("Resuming the workers."))
            streamer.emit(runId, RunStreamEvent.ToolCall(SseToolCallDto("send-$u", "sendMessage", ToolCall.STATUS_COMPLETED, buildJsonObject { put("text", buildJsonObject { put("content", reply) }) }, buildJsonObject { put("success", buildJsonObject { put("messageId", "msg-$u") }) })))
            streamer.emit(runId, RunStreamEvent.Assistant("All paused workers are resumed on Fable, Bennett is told what comes next, and the blocker entry is cleared from notes."))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "", 253_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        // The injected turns' logs, of the same day, are there too: the remark and the end — nothing to the user.
        runIds.filter { it.startsWith("run-inj") }.forEach { id ->
            streamer.emit(id, RunStreamEvent.Status(id, RunStatus.RUNNING))
            streamer.emit(id, RunStreamEvent.Assistant("Noted; nothing for Bennett in this one."))
            streamer.emit(id, RunStreamEvent.Result(id, RunStatus.FINISHED, "", 9_000, null))
            streamer.emit(id, RunStreamEvent.Done)
        }

        val conversations = repository()
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.isNotEmpty() }
        // The reader scrolls up to the first message: the window widens over every turn.
        var pages = 0
        while (conversations.state(agentId).value.hasOlder && pages++ < 40) {
            awaitUntil { !conversations.state(agentId).value.isLoadingOlder }
            val before = conversations.state(agentId).value.items.size
            conversations.loadOlder(agentId)
            awaitUntil(10_000) { val s = conversations.state(agentId).value; (s.items.size > before && !s.isLoadingOlder) || !s.hasOlder }
        }
        awaitUntil { conversations.state(agentId).value.items.count { it is com.cursorforandroid.domain.UserMessage } >= userTurns.size }
        // Every reply, from the logs, in order — the turns behind more than a page of injected runs included.
        awaitUntil(30_000) { conversations.state(agentId).value.messages().size >= userTurns.size }
        val state = conversations.state(agentId).value
        assertThat(state.messages()).containsExactlyElementsIn(userTurns.map { it.second }).inOrder()
        // The run list was paged until it covered the window: every turn paired with its run, a footer per turn.
        val diagnostics = conversations.loadDiagnostics(agentId)!!
        assertThat(diagnostics.runsLoaded).isEqualTo(runIds.size)
        assertThat(diagnostics.runsComplete).isTrue()
        val footers = state.items.filterIsInstance<com.cursorforandroid.domain.RunFooter>()
        assertThat(footers.size).isEqualTo(runIds.size)
        assertThat(footers.all { it.status == RunStatus.FINISHED }).isTrue()
        // On screen as messages, not as notes: the narration stays a note in the stretch beside them.
        val rows = state.rows()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>().map { (it.call.payload as ToolPayload.CoordinatorMessage).message }).containsExactlyElementsIn(userTurns.map { it.second }).inOrder()
        // The diagnostics say, turn by turn, where the message stands: the record has none, the log's copy is what is shown.
        val userLines = diagnostics.runs.filter { it.idTail.contains("user-") }
        assertThat(userLines).hasSize(userTurns.size)
        userLines.forEach { line ->
            assertThat(line.trace).isEqualTo("shown(log)")
            assertThat(line.message).isEqualTo("record=none rendered=yes via=log")
        }
        val injectedLine = diagnostics.runs.first { it.idTail.contains("nj-") }
        assertThat(injectedLine.message).isEqualTo("record=none rendered=none via=log")
    }

    /**
     * Bennett's v0.3.39 coordinator (`record_error_continued.json`): an infrastructure error ("Tool result not
     * found") logged mid-tool-call. In turn A the run went on and the server says it finished: nothing says failed.
     * In turn B the run ended on it and the server says it failed: the failure is the footer's, with the record's
     * error as the reason — and since the conversation went on (turn C), it is a line inside B's stretch, the summary
     * ending "· failed", not a banner and not a row; the chat stands as its newest run does. The diagnostics' status
     * line says which run failed, by whose word, with what reason, and that the chat moved past it.
     */
    @Test
    fun `a run that logged an error and finished is no failure, one the server says failed is a line inside its stretch once the chat moved on`() = runBlocking<Unit> {
        val fixture = CoordinatorFixtures.json("record_error_continued.json")
        served = fixture.getValue("responses").jsonArray.map { it.jsonObject }
        val prompts = served.mapNotNull { it["humanMessage"]?.jsonObject?.get("text")?.jsonPrimitive?.content }
        api.addFinishedAgent(agentId, "Revenue Scaling Pipeline", *prompts.mapIndexed { i, p -> Triple("run-${'A' + i}", p, "") }.toTypedArray(), firstRunAt = Instant.ofEpochMilli(now - 4 * 3_600_000L).toString())
        // The server's word on each run: A finished (the error was on the way), B failed on it, C finished.
        api.runs["run-A"] = api.runs.getValue("run-A").copy(durationMs = 253_000, result = null)
        api.runs["run-B"] = api.runs.getValue("run-B").copy(status = "ERROR", durationMs = 41_000, result = null)
        api.runs["run-C"] = api.runs.getValue("run-C").copy(durationMs = 30_000, result = null)
        agents.refresh()
        // The logs are gone: the record is what there is (B, without the coordinator's word, is asked for its log).
        listOf("run-A", "run-B", "run-C").forEach { id ->
            streamer.emit(id, RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
            streamer.emit(id, RunStreamEvent.Done)
        }

        val conversations = repository()
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.filterIsInstance<com.cursorforandroid.domain.RunFooter>().size == 3 }
        awaitUntil { !conversations.state(agentId).value.traceStatus.let { it.pending > 0 } }
        val state = conversations.state(agentId).value
        // No banner anywhere; the chat stands as its newest run does.
        assertThat(state.items.filterIsInstance<NoticeCard>()).isEmpty()
        assertThat(state.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(state.messages()).hasSize(2)
        val footers = state.items.filterIsInstance<com.cursorforandroid.domain.RunFooter>()
        assertThat(footers.map { it.status }).containsExactly(RunStatus.FINISHED, RunStatus.ERROR, RunStatus.FINISHED).inOrder()
        // Turn A: the error was logged and the run went on — no failure, no reason.
        assertThat(footers[0].reason).isNull()
        // Turn B: the server says it failed; the record's error is the reason; its end is the record's time.
        assertThat(footers[1].reason).isEqualTo("Tool result not found for toolu_status_01")
        assertThat(footers[1].endedAtMillis).isNotNull()

        val rows = state.rows()
        assertThat(rows.none { it is TranscriptRow.Failure }).isTrue()
        val stretches = rows.filterIsInstance<TranscriptRow.Stretch>().filter { it.single == null }
        // Turn A's stretches say what was done and nothing of a failure; turn B's ends on the word, its line inside.
        assertThat(stretches.map { it.summary.text }).containsExactly(
            "2 agents · 1 note",
            "Worked 4m 13s · 1 note",
            "Worked 41s · 1 agent · 1 note · failed",
            "1 note",
        ).inOrder()
        val failed = stretches[2]
        assertThat(failed.failures.single().footer.reason).isEqualTo("Tool result not found for toolu_status_01")
        assertThat(failed.listed.last()).isInstanceOf(TranscriptRow.Entry.Failure::class.java)
        assertThat(stretches.filterIndexed { i, _ -> i != 2 }.none { it.failures.isNotEmpty() }).isTrue()

        val status = conversations.loadDiagnostics(agentId)!!.status!!
        assertThat(status.shown).isEqualTo("FINISHED")
        assertThat(status.failure!!.text).isEqualTo("run=run-B source=run-record+account-record current=false reason=\"Tool result not found for toolu_status_01\"")
    }

    /**
     * The same chat before turn C: the failed run is the newest and the chat idle, so the failure is the chat's
     * state — a compact row of its own after the stretch, with the reason and the time — and the diagnostics say so.
     */
    @Test
    fun `the newest run's failure, the chat idle, is a row of its own with the server's reason and the chat's state`() = runBlocking<Unit> {
        val fixture = CoordinatorFixtures.json("record_error_continued.json")
        val all = fixture.getValue("responses").jsonArray.map { it.jsonObject }
        val starts = fixture.getValue("turnStarts").jsonArray.map { it.jsonPrimitive.int }
        served = all.take(starts[2])
        val prompts = served.mapNotNull { it["humanMessage"]?.jsonObject?.get("text")?.jsonPrimitive?.content }
        api.addFinishedAgent(agentId, "Revenue Scaling Pipeline", *prompts.mapIndexed { i, p -> Triple("run-${'A' + i}", p, "") }.toTypedArray(), firstRunAt = Instant.ofEpochMilli(now - 4 * 3_600_000L).toString())
        api.runs["run-A"] = api.runs.getValue("run-A").copy(durationMs = 253_000, result = null)
        api.runs["run-B"] = api.runs.getValue("run-B").copy(status = "ERROR", durationMs = 41_000, result = null)
        agents.refresh()
        listOf("run-A", "run-B").forEach { id ->
            streamer.emit(id, RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
            streamer.emit(id, RunStreamEvent.Done)
        }

        val conversations = repository()
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.filterIsInstance<com.cursorforandroid.domain.RunFooter>().size == 2 }
        awaitUntil { !conversations.state(agentId).value.traceStatus.let { it.pending > 0 } }
        val state = conversations.state(agentId).value
        assertThat(state.items.filterIsInstance<NoticeCard>()).isEmpty()
        // The server's status for the newest run, nothing newer, nothing running: the chat reads as failed.
        assertThat(state.runStatus).isEqualTo(RunStatus.ERROR)
        val rows = state.rows()
        val failure = rows.last() as TranscriptRow.Failure
        assertThat(failure.footer.reason).isEqualTo("Tool result not found for toolu_status_01")
        assertThat(failure.footer.endedAtMillis).isNotNull()
        assertThat((rows[rows.lastIndex - 1] as TranscriptRow.Stretch).summary.text).isEqualTo("Worked 41s · 1 agent · 1 note")
        val status = conversations.loadDiagnostics(agentId)!!.status!!
        assertThat(status.shown).isEqualTo("ERROR")
        assertThat(status.failure!!.text).isEqualTo("run=run-B source=run-record+account-record current=true reason=\"Tool result not found for toolu_status_01\"")
    }

    @Test
    fun `a turn the record shows without the coordinator's update is completed from the run's own log while it lasts`() = runBlocking<Unit> {
        // The record's copy of the update in a shape nothing reads: a call without an id of any kind.
        served = listOf(
            buildJsonObject { put("humanMessage", buildJsonObject { put("text", "Where do we stand?"); put("agentMode", "AGENT_MODE_PROJECT") }) },
            buildJsonObject { put("text", "Told Bennett.") },
            buildJsonObject { put("toolCall", buildJsonObject { put("tool", "CLIENT_SIDE_TOOL_V2_SEND_TO_USER"); put("name", "SendMessage"); put("rawArgs", """{"text":{"content":"PR #215 is merged."}}""") }) },
            buildJsonObject { put("finalToolResult", buildJsonObject { put("toolCallId", ""); put("result", buildJsonObject {}) }) },
            buildJsonObject { put("text", ""); put("isMessageDone", true) },
        )
        api.addFinishedAgent(agentId, "Revenue Scaling Pipeline", Triple("run-1", "Where do we stand?", "Told Bennett."), firstRunAt = Instant.ofEpochMilli(now - 3_600_000L).toString())
        agents.refresh()
        // The run's log still has the call whole.
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.ToolCall(SseToolCallDto("c1", "SendMessage", ToolCall.STATUS_COMPLETED, buildJsonObject { put("text", buildJsonObject { put("content", "PR #215 is merged.") }) })))
        streamer.emit("run-1", RunStreamEvent.Assistant("Told Bennett."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Told Bennett.", 12_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)

        val conversations = repository()
        conversations.attach(agentId)
        awaitUntil { conversations.state(agentId).value.messages().isNotEmpty() }
        val state = conversations.state(agentId).value
        assertThat(state.messages()).containsExactly("PR #215 is merged.")
        assertThat(streamer.connections.distinct()).containsExactly("run-1")
        assertThat(state.rows().filterIsInstance<TranscriptRow.Message>()).hasSize(1)
    }

    @Test
    fun `a message read leniently out of a record's cut pieces is shown as recovered, then replaced by the run's own copy while the log lasts`() = runBlocking<Unit> {
        val whole = """{"text":{"content":"PR #215 is merged and the release is cut."}}"""
        // The record has the first two pieces of the streamed call and never the rest.
        served = listOf(
            buildJsonObject { put("humanMessage", buildJsonObject { put("text", "Where do we stand?"); put("agentMode", "AGENT_MODE_PROJECT") }) },
            buildJsonObject { put("text", "Told Bennett.") },
            buildJsonObject { put("toolCall", buildJsonObject { put("toolCallId", "c1"); put("name", "SendMessage"); put("rawArgs", whole.take(30)); put("isStreaming", true) }) },
            buildJsonObject { put("toolCall", buildJsonObject { put("toolCallId", "c1"); put("name", "SendMessage"); put("rawArgs", whole.substring(30, 46)); put("isStreaming", true) }) },
            buildJsonObject { put("text", ""); put("isMessageDone", true) },
        )
        api.addFinishedAgent(agentId, "Revenue Scaling Pipeline", Triple("run-1", "Where do we stand?", "Told Bennett."), firstRunAt = Instant.ofEpochMilli(now - 3_600_000L).toString())
        agents.refresh()
        // Without a log to read, the recovered body is what there is.
        streamer.emit("run-1", RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
        streamer.emit("run-1", RunStreamEvent.Done)
        val conversations = repository()
        conversations.attach(agentId)
        awaitUntil { conversations.state(agentId).value.messages().isNotEmpty() }
        awaitUntil { streamer.connections.contains("run-1") }
        val recovered = conversations.state(agentId).value.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.mapNotNull { it.payload as? ToolPayload.CoordinatorMessage }.single()
        assertThat(recovered).isEqualTo(ToolPayload.CoordinatorMessage("PR #215 is merged and the", recovered = true))
        assertThat(conversations.state(agentId).value.rows().filterIsInstance<TranscriptRow.Message>()).hasSize(1)
        conversations.detach(agentId)

        // The log still has the call whole: the turn is asked for it, and the whole replaces the recovered reading.
        streamer.reset("run-1")
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.ToolCall(SseToolCallDto("c1", "SendMessage", ToolCall.STATUS_COMPLETED, buildJsonObject { put("text", buildJsonObject { put("content", "PR #215 is merged and the release is cut.") }) })))
        streamer.emit("run-1", RunStreamEvent.Assistant("Told Bennett."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Told Bennett.", 12_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        val again = repository()
        again.attach(agentId)
        awaitUntil { again.state(agentId).value.messages().any { it.endsWith("release is cut.") } }
        val payload = again.state(agentId).value.items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.mapNotNull { it.payload as? ToolPayload.CoordinatorMessage }.single()
        assertThat(payload).isEqualTo(ToolPayload.CoordinatorMessage("PR #215 is merged and the release is cut."))
    }

    @Test
    fun `Reload transcript throws the chat's copies away and reads the record again, past every cache`() = runBlocking<Unit> {
        seedRuns(turnStarts.size)
        val conversations = repository()
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.messages().isNotEmpty() }
        awaitUntil { traces.runIds(agentId).any { it.startsWith(TraceCache.RECORD_KEY_PREFIX) } && cache.read(agentId) != null }
        val shown = conversations.state(agentId).value.messages()
        assertThat(shown).hasSize(2)
        assertThat(shown.last()).startsWith("The phone worker has the Fold8")
        val before = fetches.get()

        // The record changes under the same indices — the newest turn's update now reads differently — with the
        // turn's step count unchanged, so a copy reused from memory or from the v5 files would keep the old words.
        served = served.map { response ->
            val back = response["streamedBackToolCall"]?.jsonObject ?: return@map response
            if (back["toolCallId"]?.jsonPrimitive?.content != "toolu_send_03") return@map response
            buildJsonObject {
                put("streamedBackToolCall", buildJsonObject {
                    back.forEach { (k, v) -> if (k != "rawArgs") put(k, v) }
                    put("rawArgs", """{"text":{"content":"Reloaded: the phone worker has the Fold8 pair on its list."}}""")
                })
            }
        }
        conversations.reloadTranscript(agentId)
        awaitUntil { conversations.state(agentId).value.items.isEmpty() || fetches.get() > before }
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.messages().isNotEmpty() && fetches.get() > before }
        awaitUntil { conversations.state(agentId).value.messages().any { it.startsWith("Reloaded:") } }
        val reloaded = conversations.state(agentId).value.messages()
        assertThat(reloaded).hasSize(2)
        assertThat(reloaded.last()).isEqualTo("Reloaded: the phone worker has the Fold8 pair on its list.")
        assertThat(reloaded.none { it.startsWith("The phone worker has the Fold8") }).isTrue()
        // Read again, not restored: the record was asked for afresh, and the turn's file on disk is the new copy.
        assertThat(fetches.get()).isGreaterThan(before)
        awaitUntil {
            val files = traces.read(agentId)
            files.keys.any { it.startsWith(TraceCache.RECORD_KEY_PREFIX) } && files.values.flatMap { it.items }.filterIsInstance<ActivityGroup>().flatMap { it.calls }
                .any { (CoordinatorTranscript.reinterpret(it).payload as? ToolPayload.CoordinatorMessage)?.message?.startsWith("Reloaded:") == true }
        }
    }

    @Test
    fun `a record turn written by an earlier build is read again, a run's trace is kept`() = runBlocking<Unit> {
        val record = CachedTrace(TraceCache.RECORD_KEY_PREFIX + "0", now, emptyList())
        val run = CachedTrace("run-1", now, emptyList())
        val files = JsonDiskCache(folder.newFolder("old")).child("traces")
        val old = TraceCache(files)
        // What the previous build wrote: version 4 files of both kinds.
        files.child(JsonDiskCache.sanitize(agentId)).write(record.runId, CachedTrace.serializer(), 4, record)
        files.child(JsonDiskCache.sanitize(agentId)).write(run.runId, CachedTrace.serializer(), 4, run)
        assertThat(old.read(agentId, listOf(record.runId, run.runId)).keys).containsExactly("run-1")
        // What this build writes is read back.
        old.put(agentId, listOf(record))
        assertThat(old.read(agentId, listOf(record.runId, run.runId)).keys).containsExactly(record.runId, "run-1")
    }

    private companion object {
        /** The fixture's turn count: Bennett's message, 148 silent turns, his two next messages. */
        @Suppress("unused")
        const val TURNS = 151
    }
}
