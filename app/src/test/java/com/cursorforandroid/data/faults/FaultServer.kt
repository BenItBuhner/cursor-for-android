package com.cursorforandroid.data.faults

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.CreateRunResponseDto
import com.cursorforandroid.data.api.dto.IdResponseDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.ListRunsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.dto.V0ConversationResponseDto
import com.cursorforandroid.data.api.dto.V0ListAgentsResponseDto
import com.cursorforandroid.fixtures.BlobFixtures
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The documented Cloud Agents API served in-process over a real socket, with the weather of a phone's connection
 * scripted per request: the app's own OkHttp / Retrofit / SSE clients run against it unchanged, so what the tests
 * see is what the phone sees — the retry interceptor's backoff, OkHttp's own reconnects, the read timeouts, the
 * lost replies.
 *
 * The account is an in-memory map the test mutates, like `FakeCursorApi`'s: agents, their runs (newest first,
 * paged by `limit` and `cursor`), the `/v0` transcript per agent, and a scripted event log per run for the stream
 * endpoint. Every request goes through [weather] — a round trip drawn from [rttMillis], never under 300 ms by
 * default (the project rule: nothing is measured at 60 ms) — and then through the [Fault] queued for its [Route],
 * if any: an HTTP refusal in the API's own words with or without `Retry-After`, a connection reset before the
 * request is read, a reply lost after the request was processed, a body cut half-way, or silence until the client
 * gives up. A fault that says the request was processed applies its side effect first (a run created, a cancel
 * taken), so the tests can tell a message the server took from one it did not.
 */
class FaultServer(
    rttMillis: LongRange = 300L..900L,
    seed: Int = 7,
) : AutoCloseable {
    private val server = MockWebServer()
    private val random = Random(seed)

    /** The round trip every answer takes, headers included; set to `0..0` for a test that is about something else. */
    @Volatile var rttMillis: LongRange = rttMillis
    /**
     * The connection's bandwidth for every body served, bytes per second; 0 leaves the body unthrottled. A phone on
     * a cell connection reads a few hundred kilobytes a second, and the account's record of a long chat is megabytes.
     */
    @Volatile var bytesPerSecond: Long = 0L
    /** The server's clock, for the runs it files: the app's own by default, so a send marker and a run stamp compare as they do in production. */
    @Volatile var clock: () -> Long = { com.cursorforandroid.util.AppClock.now() }

    val agents: MutableMap<String, AgentDto> = ConcurrentHashMap()
    val v0: MutableMap<String, V0AgentDto> = ConcurrentHashMap()
    val runs: MutableMap<String, RunDto> = ConcurrentHashMap()
    val transcripts: MutableMap<String, List<V0ConversationMessageDto>> = ConcurrentHashMap()
    /** Each run's retained log, as `event` to `data` pairs; ids are `<runId>#<n>`. Absent: the log has expired (`410`). */
    val logs: MutableMap<String, List<Pair<String, String>>> = ConcurrentHashMap()
    /**
     * The account's own record of each chat (`FetchBackgroundComposer`, Extended mode): its responses in order, as
     * JSON objects, paged by `startIndex` / `limit` with `totalResponses`. Absent: the record has nothing for the chat.
     */
    val records: MutableMap<String, List<JsonObject>> = ConcurrentHashMap()
    /** The account's conversation state per chat (`GetLatestAgentConversationState`), as the JSON body to serve; synthesised from [records] when absent. */
    val recordStates: MutableMap<String, String> = ConcurrentHashMap()
    /**
     * The blob-backed record per chat (`GetBlobForAgentKV`, the read Cursor's client makes; see `BlobFixtures`):
     * made from [records] on the first read and remade whenever [records] changes; scripted here for a chat that has
     * no [records]. Neither: the chat has no turns.
     */
    val blobRecords: MutableMap<String, BlobFixtures.Record> = ConcurrentHashMap()
    private val blobRecordsFrom = ConcurrentHashMap<String, List<JsonObject>>()
    /** Bytes of record body served so far, per chat: what the account's transcript costs a connection. */
    val recordBytes: MutableMap<String, Long> = ConcurrentHashMap()
    /** The prompts `POST /runs` filed, in order, and the runs they started. */
    val sent = CopyOnWriteArrayList<Pair<String, String>>()
    val cancelled = CopyOnWriteArrayList<String>()
    /** Answers every `POST /runs` with `409 agent_busy` while set, as the server does during a turn. */
    @Volatile var busy = false

    /**
     * One message in the account's queue for a chat (`AddAsyncFollowupBackgroundComposer` behind a turn under way;
     * `ListPendingFollowups`): the account's followup id (the client's, as sent), the words, and when the account
     * consumed it — started the next run on it, or promoted it into the running turn — after which the list still
     * names it for [queueLagMs] (the account's bookkeeping trails the run it starts, and a poll that began before is
     * older still). Null while it waits.
     */
    data class Pending(val followupId: String, val text: String, val createdAtMs: Long, @Volatile var consumedAtMs: Long? = null) {
        /** When the account consumed it, by the server's own monotonic clock (the app's clock is frozen in most tests). */
        @Volatile var consumedAtServerMs: Long? = null
    }

    /** The account's queue per chat, oldest first. */
    val pending: MutableMap<String, MutableList<Pending>> = ConcurrentHashMap()
    /** How long the account's list keeps naming a followup after the run it started (or the steer it became) exists. */
    @Volatile var queueLagMs: Long = 0L
    /** Whether the account may start the next run on a queued message on its own the moment the turn ends (see [endTurn]); tests deliver by hand otherwise. */
    @Volatile var autoDeliver = false
    /** The followups delivered, in order, and the run each started (a steer's run is the one it was delivered into). */
    val delivered = CopyOnWriteArrayList<Pair<String, String>>()
    /** The largest page the list endpoints serve whatever `limit` asks, so a small account still pages. */
    @Volatile var pageSize = Int.MAX_VALUE

    /** One request as the server saw it: its route, path, when it arrived (millis since the server started), and the fault it met. */
    data class Seen(val route: Route, val method: String, val path: String, val atMillis: Long, val fault: Fault?, val lastEventId: String?)

    val seen = CopyOnWriteArrayList<Seen>()
    private val startedAt = System.nanoTime()
    private val ids = AtomicInteger()
    private val faults = ConcurrentHashMap<Route, ConcurrentLinkedQueue<Scripted>>()
    /** A fault every request of a route meets for as long as it is set — an outage, not a blip. */
    private val standing = ConcurrentHashMap<Route, Scripted>()

    /** A fault queued for a route, for the requests whose path contains [path] when one is given. */
    private class Scripted(val fault: Fault, val path: String?) {
        fun matches(requestPath: String) = path == null || requestPath.contains(path)
    }
    /** Connections still to be closed the moment they open, before a byte of the request is read (see [resetNextConnections]). */
    private val resets = AtomicInteger()

    enum class Route { Me, ListAgents, ListAgentsV0, GetAgent, ListRuns, GetRun, CreateRun, CancelRun, Conversation, Stream, Auth, Record, RecordState, Blob, QueueAdd, QueueList, QueueDelete, Steer, Other }

    /** What one request meets instead of, or before, its answer. */
    sealed interface Fault {
        /** No fault: the request is answered normally. For scripting a fault on the second request of a route rather than the first. */
        data object Pass : Fault
        /** An HTTP refusal in the API's own words, with the `Retry-After` the server names when it does (seconds, or an HTTP-date). */
        data class Status(val code: Int, val errorCode: String, val message: String, val retryAfter: String? = null) : Fault
        /** The request is read — and processed when [processed] — and the connection closes without a byte of the reply. */
        data class LostReply(val processed: Boolean = true) : Fault
        /** The reply's body is cut half-way; the request was processed. */
        data object TruncatedBody : Fault
        /** No answer at all until the client's read timeout; the request was processed when [processed]. */
        data class Silence(val processed: Boolean = false) : Fault
        /** A stream that delivers [events] frames past the resume position and then closes without `done`. */
        data class StreamCut(val events: Int) : Fault
    }

    fun start(): FaultServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                serve(request)
            } catch (t: Throwable) {
                json(500, error("harness_error", t.toString()))
            }

            // The one fault a dispatcher cannot script per request: the socket closed as it opens, the way a
            // network handoff kills a connection under a request. MockWebServer reads it off the next response before
            // the request is read.
            override fun peek(): MockResponse = if (resets.get() > 0 && resets.decrementAndGet() >= 0) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) else MockResponse()
        }
        server.start()
        return this
    }

    val baseUrl: String get() = server.url("/").toString()
    val hostName: String get() = server.hostName
    val port: Int get() = server.port

    /**
     * Queues [faults] for the next requests of [route], in order — the next requests whose path contains [path],
     * when one is given (one run's stream among the window's) — and answers a request with none queued normally.
     */
    fun script(route: Route, vararg faults: Fault, path: String? = null) {
        this.faults.getOrPut(route) { ConcurrentLinkedQueue() }.addAll(faults.map { Scripted(it, path) })
    }

    /** Every request of [route] (whose path contains [path], when given) meets [fault] until [clear]ed: a host down, a rate limit that lasts. */
    fun outage(route: Route, fault: Fault, path: String? = null) { standing[route] = Scripted(fault, path) }

    fun clear(route: Route) { faults.remove(route); standing.remove(route) }

    /**
     * The next [count] connections are closed the moment they open, before the request is read: the client's write
     * fails or its read meets the end of the stream, and nothing was processed. Whichever request opens a connection
     * next meets it, so a test empties the client's pool first when it wants a particular request to.
     */
    fun resetNextConnections(count: Int = 1) { resets.addAndGet(count) }

    fun requests(route: Route): List<Seen> = seen.filter { it.route == route }

    fun nowMillis(): Long = (System.nanoTime() - startedAt) / 1_000_000

    // -- the account ---------------------------------------------------------------------------------------------

    fun addIdleAgent(id: String, name: String, runId: String, createdAt: String = "2026-04-13T18:30:00.000Z", prompt: String = "Do the thing", reply: String = "Done.") {
        agents[id] = AgentDto(id = id, name = name, status = "IDLE", createdAt = createdAt, updatedAt = createdAt, latestRunId = runId, url = "https://cursor.com/agents/$id")
        v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED")
        runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = createdAt, updatedAt = createdAt, durationMs = 65_000, result = reply)
        transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", prompt), V0ConversationMessageDto("$runId-a", "assistant_message", reply))
    }

    fun addRunningAgent(id: String, name: String, runId: String, createdAt: String = "2026-04-13T18:30:00.000Z", prompt: String = "Do the thing") {
        agents[id] = AgentDto(id = id, name = name, status = "ACTIVE", createdAt = createdAt, updatedAt = createdAt, latestRunId = runId, url = "https://cursor.com/agents/$id")
        v0[id] = V0AgentDto(id = id, name = name, status = "RUNNING")
        runs[runId] = RunDto(id = runId, agentId = id, status = "RUNNING", createdAt = createdAt, updatedAt = createdAt)
        transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", prompt))
    }

    /** A finished run's whole log: a thought, two tool calls, the reply and the result, the way a replay delivers it. */
    fun retain(runId: String, reply: String, durationMs: Long = 30_000) {
        logs[runId] = listOf(
            "status" to """{"runId":"$runId","status":"RUNNING"}""",
            "thinking" to """{"text":"Reading the code first."}""",
            "tool_call" to """{"callId":"$runId-c1","name":"read_file","status":"completed","args":{"path":"app/src/Composer.kt"}}""",
            "tool_call" to """{"callId":"$runId-c2","name":"edit_file","status":"completed","args":{"path":"app/src/Composer.kt"}}""",
            "assistant" to """{"text":${quote(reply)}}""",
            "result" to """{"runId":"$runId","status":"FINISHED","text":${quote(reply)},"durationMs":$durationMs}""",
        )
    }

    /** Marks [runId] over on the server: its record, the row, the transcript's reply. */
    fun finish(agentId: String, runId: String, reply: String, at: String = "2026-04-13T19:00:00.000Z", durationMs: Long = 30_000) {
        runs[runId] = runs.getValue(runId).copy(status = "FINISHED", result = reply, durationMs = durationMs, updatedAt = at)
        agents[agentId] = agents.getValue(agentId).copy(status = "IDLE", updatedAt = at)
        v0[agentId] = v0.getValue(agentId).copy(status = "FINISHED")
        transcripts[agentId] = transcripts[agentId].orEmpty() + V0ConversationMessageDto("$runId-a", "assistant_message", reply)
    }

    // -- serving -------------------------------------------------------------------------------------------------

    private fun route(request: RecordedRequest): Route {
        val segments = (request.requestUrl?.encodedPath ?: "").trimStart('/').split('/')
        val v1Agents = segments.size >= 2 && segments[0] == "v1" && segments[1] == "agents"
        return when {
            segments.size == 2 && segments[0] == "v1" && segments[1] == "me" -> Route.Me
            segments.size == 2 && v1Agents -> Route.ListAgents
            segments.size == 2 && segments[0] == "v0" && segments[1] == "agents" -> Route.ListAgentsV0
            segments.size == 3 && v1Agents && request.method == "GET" -> Route.GetAgent
            segments.size == 4 && v1Agents && segments[3] == "runs" && request.method == "GET" -> Route.ListRuns
            segments.size == 4 && v1Agents && segments[3] == "runs" && request.method == "POST" -> Route.CreateRun
            segments.size == 5 && v1Agents && segments[3] == "runs" && request.method == "GET" -> Route.GetRun
            segments.size == 6 && v1Agents && segments[3] == "runs" && segments[5] == "cancel" -> Route.CancelRun
            segments.size == 6 && v1Agents && segments[3] == "runs" && segments[5] == "stream" -> Route.Stream
            segments.size == 4 && segments[0] == "v0" && segments[1] == "agents" && segments[3] == "conversation" -> Route.Conversation
            // The account service: the session handshake and the two record RPCs (Extended mode), on the same host.
            segments.size == 2 && segments[0] == "auth" && segments[1] == "exchange_user_api_key" -> Route.Auth
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "FetchBackgroundComposer" -> Route.Record
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "GetLatestAgentConversationState" -> Route.RecordState
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "GetBlobForAgentKV" -> Route.Blob
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "AddAsyncFollowupBackgroundComposer" -> Route.QueueAdd
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "ListPendingFollowups" -> Route.QueueList
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "DeletePendingFollowup" -> Route.QueueDelete
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "InjectBackgroundComposerContext" -> Route.Steer
            else -> Route.Other
        }
    }

    private fun serve(request: RecordedRequest): MockResponse {
        val route = route(request)
        val url = request.requestUrl!!
        val fault = faults[route]?.let { queue -> queue.firstOrNull { it.matches(url.encodedPath) }?.also { queue.remove(it) } }?.fault
            ?: standing[route]?.takeIf { it.matches(url.encodedPath) }?.fault
        seen += Seen(route, request.method ?: "", request.path ?: "", nowMillis(), fault, request.getHeader("Last-Event-ID"))
        val segments = url.encodedPath.trimStart('/').split('/')
        val processed = when (fault) {
            is Fault.LostReply -> fault.processed
            is Fault.Silence -> fault.processed
            is Fault.Status -> false
            else -> true
        }
        val answer: MockResponse = when (route) {
            Route.Me -> json(200, encode(ApiKeyInfoDto.serializer(), ApiKeyInfoDto(apiKeyName = "faults")))
            Route.ListAgents -> {
                val (items, next) = page(newestFirst().filter { url.queryParameter("includeArchived") != "false" || it.status != "ARCHIVED" }, { it.id }, url.queryParameter("limit")?.toIntOrNull() ?: 100, url.queryParameter("cursor"))
                json(200, encode(ListAgentsResponseDto.serializer(), ListAgentsResponseDto(items.map { AgentSummaryDto(it.id, it.name, it.status, it.env, it.url, it.createdAt, it.updatedAt, it.latestRunId) }, next)))
            }
            Route.ListAgentsV0 -> {
                val (items, next) = page(newestFirst().mapNotNull { v0[it.id] }, { it.id }, url.queryParameter("limit")?.toIntOrNull() ?: 100, url.queryParameter("cursor"))
                json(200, encode(V0ListAgentsResponseDto.serializer(), V0ListAgentsResponseDto(items, next)))
            }
            Route.GetAgent -> agents[segments[2]]?.let { json(200, encode(AgentDto.serializer(), it)) } ?: notFound()
            Route.ListRuns -> {
                val all = runs.values.filter { it.agentId == segments[2] }.sortedWith(compareByDescending<RunDto> { it.createdAt }.thenByDescending { it.id })
                val (items, next) = page(all, { it.id }, url.queryParameter("limit")?.toIntOrNull() ?: 50, url.queryParameter("cursor"))
                json(200, encode(ListRunsResponseDto.serializer(), ListRunsResponseDto(items, next)))
            }
            Route.GetRun -> runs[segments[4]]?.let { json(200, encode(RunDto.serializer(), it)) } ?: notFound()
            Route.CreateRun -> createRun(segments[2], request, processed)
            Route.CancelRun -> {
                if (processed) {
                    cancelled += segments[4]
                    runs[segments[4]]?.let { runs[segments[4]] = it.copy(status = "CANCELLED") }
                    agents[segments[2]]?.let { agents[segments[2]] = it.copy(status = "IDLE") }
                }
                json(200, encode(IdResponseDto.serializer(), IdResponseDto(segments[4])))
            }
            Route.Conversation -> transcripts[segments[2]]?.let { json(200, encode(V0ConversationResponseDto.serializer(), V0ConversationResponseDto(segments[2], it))) }
                ?: if (segments[2] in agents) json(200, encode(V0ConversationResponseDto.serializer(), V0ConversationResponseDto(segments[2]))) else notFound()
            Route.Stream -> stream(segments[4], request.getHeader("Last-Event-ID"), (fault as? Fault.StreamCut)?.events)
            Route.Auth -> json(200, """{"accessToken":"session-token","refreshToken":"refresh-token"}""")
            Route.Record -> record(request)
            Route.RecordState -> recordState(request)
            Route.Blob -> blob(request)
            Route.QueueAdd -> queueAdd(request, processed)
            Route.QueueList -> queueList(request)
            Route.QueueDelete -> queueDelete(request)
            Route.Steer -> steer(request)
            Route.Other -> json(404, error("not_found", "No such route in the fault server: ${request.method} ${url.encodedPath}"))
        }
        return when (fault) {
            null, Fault.Pass, is Fault.StreamCut -> answer.withWeather()
            // A refusal from the account service is a Connect error body; from the documented API, the API's own.
            is Fault.Status -> json(fault.code, if (route.isAccount) connectError(fault.errorCode, fault.message) else error(fault.errorCode, fault.message)).apply { fault.retryAfter?.let { setHeader("Retry-After", it) } }.withWeather()
            is Fault.LostReply -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            Fault.TruncatedBody -> answer.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY).withWeather()
            is Fault.Silence -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
    }

    private fun createRun(agentId: String, request: RecordedRequest, processed: Boolean): MockResponse {
        val body = CursorJson.decodeFromString(CreateRunRequestDto.serializer(), request.body.readUtf8())
        val agent = agents[agentId] ?: return notFound()
        // One turn at a time, as the real server has it: a second request while the latest run is under way is
        // refused as busy — which is what a duplicate of an accepted follow-up meets.
        val onATurn = agent.latestRunId?.let { runs[it]?.status }?.let { com.cursorforandroid.domain.RunStatus.parse(it).isActive } == true
        if (busy || onATurn) return json(409, error("agent_busy", "Agent is busy."))
        if (!processed) return json(500, error("not_processed", "The fault said this request was never processed."))
        val sequence = ids.incrementAndGet()
        val runId = "run-followup-$sequence"
        val now = Instant.ofEpochMilli(clock() + sequence * 1000L).toString()
        val run = RunDto(id = runId, agentId = agentId, status = "RUNNING", createdAt = now, updatedAt = now)
        runs[runId] = run
        agents[agentId] = agent.copy(status = "ACTIVE", latestRunId = runId, updatedAt = now)
        v0[agentId]?.let { v0[agentId] = it.copy(status = "RUNNING") }
        transcripts[agentId] = transcripts[agentId].orEmpty() + V0ConversationMessageDto("$runId-u", "user_message", body.prompt.text)
        sent += body.prompt.text to runId
        return json(200, encode(CreateRunResponseDto.serializer(), CreateRunResponseDto(run)))
    }

    private val Route.isAccount: Boolean get() = this == Route.Record || this == Route.RecordState || this == Route.Blob || this == Route.QueueAdd || this == Route.QueueList || this == Route.QueueDelete || this == Route.Steer

    // ---- the account's queue ----------------------------------------------------------------------------------------

    /**
     * `AddAsyncFollowupBackgroundComposer {bcId, followup, followupId, synchronous, …}`: behind a turn under way the
     * message joins the account's queue and the answer names no run; on a free agent (or sent `synchronous`) the
     * account starts the run at once and names it, filing the prompt in the transcript as `POST /runs` does.
     */
    private fun queueAdd(request: RecordedRequest, processed: Boolean): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val text = body["followup"]?.jsonPrimitive?.contentOrNull ?: ""
        val followupId = body["followupId"]?.jsonPrimitive?.contentOrNull ?: "fu-server-${ids.incrementAndGet()}"
        val synchronous = body["synchronous"]?.jsonPrimitive?.booleanOrNull == true
        val agent = agents[agentId] ?: return json(404, connectError("not_found", "no such composer"))
        if (!processed) return json(500, connectError("internal", "The fault said this request was never processed."))
        val onATurn = agent.latestRunId?.let { runs[it]?.status }?.let { com.cursorforandroid.domain.RunStatus.parse(it).isActive } == true
        if (onATurn && !synchronous) {
            pending.getOrPut(agentId) { CopyOnWriteArrayList() } += Pending(followupId, text, clock())
            return json(200, "{}")
        }
        val run = startRun(agentId, text)
        // A message the account starts the run on at once still passes through its queue: the list names it for
        // [queueLagMs] after (Bennett's frames of 2026-09-20 23:24 and 2026-09-21 09:18, the chat idle when he sent).
        if (!synchronous) {
            pending.getOrPut(agentId) { CopyOnWriteArrayList() } += Pending(followupId, text, clock(), consumedAtMs = clock()).also { it.consumedAtServerMs = nowMillis() }
            delivered += followupId to run.id
        }
        return json(200, """{"runId":"${run.id}"}""")
    }

    /** `ListPendingFollowups {bcId}`: what waits, and what the account consumed within the last [queueLagMs]. */
    private fun queueList(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val now = nowMillis()
        val listed = pending[agentId].orEmpty().filter { p -> p.consumedAtServerMs?.let { now < it + queueLagMs } ?: true }
        val items = listed.joinToString(",") { p ->
            """{"followupId":"${p.followupId}","text":${CursorJson.encodeToString(String.serializer(), p.text)},"createdAtMs":"${p.createdAtMs}","source":"BACKGROUND_COMPOSER_SOURCE_MOBILE"}"""
        }
        return json(200, """{"pendingFollowups":[$items]}""")
    }

    private fun queueDelete(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val followupId = body["followupId"]?.jsonPrimitive?.contentOrNull ?: ""
        pending[agentId]?.removeAll { it.followupId == followupId }
        return json(200, """{"success":true}""")
    }

    /**
     * `InjectBackgroundComposerContext`: a steer into the turn under way — with `promoteFollowupId`, a queued
     * message delivered into the running turn: the account consumes it and files it in the transcript among the
     * turn's messages. A plain steer's words are filed the same way.
     */
    private fun steer(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val agent = agents[agentId] ?: return json(404, connectError("not_found", "no such composer"))
        val runId = agent.latestRunId?.takeIf { runs[it]?.status?.let { st -> com.cursorforandroid.domain.RunStatus.parse(st).isActive } == true }
            ?: return json(200, """{"outcome":"OUTCOME_REJECTED"}""")
        val promote = body["promoteFollowupId"]?.jsonPrimitive?.contentOrNull
        val text = if (promote != null) {
            val p = pending[agentId]?.firstOrNull { it.followupId == promote } ?: return json(200, """{"outcome":"OUTCOME_REJECTED"}""")
            p.consumedAtMs = clock()
            p.consumedAtServerMs = nowMillis()
            delivered += p.followupId to runId
            p.text
        } else {
            body["injectContextAction"]?.jsonObject?.get("userContext")?.jsonObject?.get("userMessage")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        }
        transcripts[agentId] = transcripts[agentId].orEmpty() + V0ConversationMessageDto("$runId-steer-${ids.incrementAndGet()}", "user_message", text)
        return json(200, """{"outcome":"OUTCOME_QUEUED"}""")
    }

    /** The account starts a run on [text] now: the run record, the agent's latest, the transcript's prompt. */
    private fun startRun(agentId: String, text: String): RunDto {
        val agent = agents.getValue(agentId)
        val sequence = ids.incrementAndGet()
        val runId = "run-followup-$sequence"
        val now = Instant.ofEpochMilli(clock() + sequence).toString()
        val run = RunDto(id = runId, agentId = agentId, status = "RUNNING", createdAt = now, updatedAt = now)
        runs[runId] = run
        agents[agentId] = agent.copy(status = "ACTIVE", latestRunId = runId, updatedAt = now)
        v0[agentId]?.let { v0[agentId] = it.copy(status = "RUNNING") }
        transcripts[agentId] = transcripts[agentId].orEmpty() + V0ConversationMessageDto("$runId-u", "user_message", text)
        sent += text to runId
        return run
    }

    /**
     * The account delivers the oldest queued message of [agentId] as the next turn: the run it starts (RUNNING, its
     * log [log]), the prompt in the transcript, the row's latest run — and the followup consumed, still listed for
     * [queueLagMs]. Null when nothing waits. The turn under way must be over first (see [endTurn]).
     */
    fun deliverNext(agentId: String, log: List<Pair<String, String>> = emptyList()): RunDto? {
        val next = pending[agentId]?.firstOrNull { it.consumedAtMs == null } ?: return null
        val run = startRun(agentId, next.text)
        logs[run.id] = log
        next.consumedAtMs = clock()
        next.consumedAtServerMs = nowMillis()
        delivered += next.followupId to run.id
        return run
    }

    /** The run under way of [agentId] ends on the server: its record FINISHED, its log closed with its result; then, with [autoDeliver], the next queued message is delivered. */
    fun endTurn(agentId: String, durationMs: Long = 40_000L, nextLog: List<Pair<String, String>> = emptyList()): RunDto? {
        val agent = agents[agentId] ?: return null
        val runId = agent.latestRunId ?: return null
        val run = runs[runId] ?: return null
        val endedAt = Instant.ofEpochMilli(clock()).toString()
        runs[runId] = run.copy(status = "FINISHED", durationMs = durationMs, updatedAt = endedAt, result = null)
        logs[runId] = logs[runId].orEmpty() + ("result" to """{"runId":"$runId","status":"FINISHED","text":"","durationMs":$durationMs}""")
        agents[agentId] = agent.copy(status = "IDLE", updatedAt = endedAt)
        v0[agentId]?.let { v0[agentId] = it.copy(status = "FINISHED") }
        return if (autoDeliver) deliverNext(agentId, nextLog) else null
    }

    /** `FetchBackgroundComposer {bcId, startIndex, limit}`: the record's responses from `startIndex`, `limit` of them, with the record's size. */
    private fun record(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val start = body["startIndex"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = body["limit"]?.jsonPrimitive?.intOrNull ?: 200
        val all = records[agentId] ?: return json(200, """{"responses":[],"totalResponses":0}""")
        val page = all.drop(start).take(limit)
        val text = buildJsonObject { put("responses", JsonArray(page)); put("totalResponses", all.size) }.toString()
        recordBytes.merge(agentId, text.length.toLong(), Long::plus)
        return json(200, text)
    }

    /**
     * `GetLatestAgentConversationState {bcId}`: the scripted state, else one turn per prompt of the record — each
     * turn its blob id, as the account names them (see [blobRecord]) — with the runs' timings.
     */
    private fun recordState(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        recordStates[agentId]?.let { return json(200, it) }
        val record = blobRecord(agentId) ?: return json(200, """{"latestConversationState":{"conversationState":{"turns":[],"turnTimings":[]}}}""")
        val prompts = record.turnCount
        val chatRuns = runs.values.filter { it.agentId == agentId }.sortedBy { it.createdAt }
        val timings = (0 until prompts).joinToString(",") { t ->
            val run = chatRuns.getOrNull(t)
            val ended = run?.let { Instant.parse(it.updatedAt).toEpochMilli() } ?: 0L
            """{"durationMs":"${run?.durationMs ?: 0}","timestampMs":"$ended"}"""
        }
        val ids = record.turnIds.joinToString(",") { "\"$it\"" }
        return json(200, """{"latestConversationState":{"conversationState":{"turns":[$ids],"turnTimings":[$timings],"isRootProjectConversation":true}}}""")
    }

    /** The chat's blob-backed record: made from its legacy steps (and made again whenever they change), else as scripted. */
    private fun blobRecord(agentId: String): BlobFixtures.Record? {
        val steps = records[agentId] ?: return blobRecords[agentId]
        synchronized(blobRecordsFrom) {
            if (blobRecordsFrom[agentId] !== steps) {
                synthesized[agentId] = BlobFixtures.record(steps)
                blobRecordsFrom[agentId] = steps
            }
            return synthesized[agentId]
        }
    }
    private val synthesized = ConcurrentHashMap<String, BlobFixtures.Record>()

    /** `GetBlobForAgentKV {bcId, blobId}`: the blob's bytes, base64; `not_found` for an id the record does not have. */
    private fun blob(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val blobId = body["blobId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "blobId is required"))
        val bytes = blobRecord(agentId)?.blobs?.get(blobId) ?: return json(404, connectError("not_found", "blob not found"))
        recordBytes.merge(agentId, bytes.size.toLong(), Long::plus)
        return json(200, """{"blobData":"${java.util.Base64.getEncoder().encodeToString(bytes)}"}""")
    }

    /** A Connect error body, as the account service writes one. */
    private fun connectError(code: String, message: String): String = """{"code":"$code","message":${quote(message)}}"""

    private fun stream(runId: String, lastEventId: String?, cutAfter: Int?): MockResponse {
        val log = logs[runId] ?: return json(410, error("stream_expired", "This run's live stream has expired."))
        val skip = lastEventId?.substringAfterLast('#')?.toIntOrNull() ?: 0
        val remaining = log.drop(skip)
        val served = if (cutAfter != null) remaining.take(cutAfter) else remaining
        val body = StringBuilder()
        served.forEachIndexed { i, (event, data) ->
            body.append("event: ").append(event).append('\n')
            body.append("id: ").append(runId).append('#').append(skip + i + 1).append('\n')
            body.append("data: ").append(data).append("\n\n")
        }
        if (cutAfter == null) body.append("event: done\ndata: {}\n\n")
        val response = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body.toString())
        if (cutAfter != null) response.setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        return response
    }

    private fun MockResponse.withWeather(): MockResponse {
        val rtt = rttMillis.let { if (it.first >= it.last) it.first else synchronized(random) { random.nextLong(it.first, it.last + 1) } }
        if (rtt > 0) setHeadersDelay(rtt, TimeUnit.MILLISECONDS)
        // The body at the connection's bandwidth: in slices of a tenth of a second, so a small answer is not held a whole second.
        val bandwidth = bytesPerSecond
        if (bandwidth > 0) throttleBody((bandwidth / 10).coerceAtLeast(1024), 100, TimeUnit.MILLISECONDS)
        return this
    }

    private fun newestFirst() = agents.values.sortedWith(compareByDescending<AgentDto> { it.createdAt }.thenBy { it.id })

    private fun <T : Any> page(all: List<T>, id: (T) -> String, limit: Int, cursor: String?): Pair<List<T>, String?> {
        val start = cursor?.let { c -> all.indexOfFirst { id(it) == c }.takeIf { it >= 0 } } ?: 0
        val size = minOf(limit, pageSize)
        val items = all.drop(start).take(size)
        val next = all.getOrNull(start + size)?.let(id)
        return items to next
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = CursorJson.encodeToString(serializer, value)
    private fun error(code: String, message: String) = """{"error":{"code":"$code","message":${quote(message)}}}"""
    private fun notFound() = json(404, error("not_found", "Not found."))
    private fun json(code: Int, body: String): MockResponse = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun quote(text: String): String = CursorJson.encodeToString(String.serializer(), text)

    override fun close() = server.shutdown()

    companion object {
        /** The account service the record RPCs belong to (see `HeadlessConversationApi.SERVICE`). */
        const val RECORD_SERVICE = "aiserver.v1.BackgroundComposerService"
    }
}
