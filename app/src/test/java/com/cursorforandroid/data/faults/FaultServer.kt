package com.cursorforandroid.data.faults

import com.cursorforandroid.data.api.ConnectRpc
import com.cursorforandroid.data.api.ConnectStreamFixtures
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
import okhttp3.Protocol
import okhttp3.internal.http2.Http2Stream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.internal.duplex.DuplexResponseBody
import okio.buffer
import java.io.IOException
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
    /**
     * Served over HTTP/2 (prior knowledge), as `api2.cursor.sh` and `api.cursor.com` answer: every call a client makes
     * to the host shares one connection, and a stream stays open while the other calls come and go beside it. The
     * rig's clients must speak it too (see `FaultRig`'s `http2`).
     */
    private val http2: Boolean = false,
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
    data class Pending(val followupId: String, val text: String, val createdAtMs: Long, @Volatile var consumedAtMs: Long? = null, val runId: String? = null) {
        /** When the account consumed it, by the server's own monotonic clock (the app's clock is frozen in most tests). */
        @Volatile var consumedAtServerMs: Long? = null
    }

    /** The account's queue per chat, oldest first. */
    val pending: MutableMap<String, MutableList<Pending>> = ConcurrentHashMap()
    /** How long the account's list keeps naming a followup after the run it started (or the steer it became) exists. */
    @Volatile var queueLagMs: Long = 0L
    /**
     * A Project's coordinator: behind a turn under way the account names, in its answer, the run the queued message
     * will start ([Pending.runId]) and starts that very run once the turn is over (see [deliverNext]), the list naming
     * the message as waiting meanwhile — what Bennett's frames of 2026-09-20 23:24, 2026-09-21 09:18 and 2026-09-22
     * 19:42 show the account answering in Projects. Off, an ordinary chat's answer: no run until one starts.
     */
    @Volatile var namesQueuedRuns = false
    /** With [namesQueuedRuns], the run list also carries the named run as `CREATING` while it waits (not seen on the account; the app must not follow it either way). */
    @Volatile var listsQueuedRuns = false
    /** Whether the account may start the next run on a queued message on its own the moment the turn ends (see [endTurn]); tests deliver by hand otherwise. */
    @Volatile var autoDeliver = false
    /** The followups delivered, in order, and the run each started (a steer's run is the one it was delivered into). */
    val delivered = CopyOnWriteArrayList<Pair<String, String>>()
    /** The largest page the list endpoints serve whatever `limit` asks, so a small account still pages. */
    @Volatile var pageSize = Int.MAX_VALUE
    /**
     * The run list oldest first, as the field has shown it (`ConversationRepository.newestRuns`): on a chat of
     * thousands of runs the newest are pages away.
     */
    @Volatile var runsOldestFirst = false
    /** Body bytes served per route (the answers' bodies, not their headers): what a load costs the connection. */
    val bytesByRoute: MutableMap<Route, Long> = ConcurrentHashMap()
    /** How many times each blob was asked for by `GetBlobForAgentKV`, by id: a blob asked twice was not kept. */
    val blobReads: MutableMap<String, AtomicInteger> = ConcurrentHashMap()
    /**
     * Answers `GetBlobForAgentKV` for a blob in place of the record when it returns one: a drifted server's answer
     * for the blobs the prefetch did not carry, a blob the account no longer has.
     */
    @Volatile var blobAnswer: ((agentId: String, blobId: String) -> MockResponse?)? = null
    /** The blobs `StreamConversation` has prefetched so far, per chat: what a scripted [blobAnswer] can tell apart. */
    val prefetchedIds: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()

    /** One request as the server saw it: its route, path, when it arrived (millis since the server started), and the fault it met. */
    data class Seen(val route: Route, val method: String, val path: String, val atMillis: Long, val fault: Fault?, val lastEventId: String?)

    val seen = CopyOnWriteArrayList<Seen>()
    /** Connections the clients opened, all told: each one a name lookup and a handshake on a phone. */
    val connections = AtomicInteger()
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

    /** [Live] is `StreamConversation` asked with `purpose = LIVE` (an open chat's watch); [RecordState] the same method's state read. */
    enum class Route { Me, ListAgents, ListAgentsV0, GetAgent, ListRuns, GetRun, CreateRun, CancelRun, Conversation, Stream, Auth, Record, RecordState, Live, Blob, QueueAdd, QueueList, QueueDelete, QueueUpdate, QueueReorder, QueueSendNow, QueueEditing, Steer, AccountList, Workers, Children, Pins, Other }

    /**
     * One row of the account's own list (`ListBackgroundComposers`, Extended mode): the record the sidebar's rows
     * are placed and named by, and what the discovery scan pages over. [activityMs] orders the list (newest first)
     * and pages it; [project] is the desktop's `project_metadata` present; [manager] a worker's coordinator;
     * [sideChatOf] a side chat's parent.
     */
    data class Composer(
        val id: String,
        val name: String,
        val activityMs: Long,
        val running: Boolean = false,
        val archived: Boolean = false,
        val project: Boolean = false,
        val manager: String? = null,
        val sideChatOf: String? = null,
    )

    /** The ids `ListBackgroundComposers` was asked for one at a time (`bc_id`), in order. */
    val composerReads = CopyOnWriteArrayList<String>()
    /** The account's list, by id; served newest first (see [Composer.activityMs]). */
    val composers: MutableMap<String, Composer> = ConcurrentHashMap()
    /** `ListWorkersForManager`: each coordinator's workers, as (workerId, spawnKind). */
    val workers: MutableMap<String, List<Pair<String, String>>> = ConcurrentHashMap()
    /**
     * While above zero, a running run's stream is held open about this long after what its log has so far, a
     * keep-alive at a time, as the API holds a turn under way, rather than answered whole and closed: each running
     * chat followed costs a connection's worth of the client's resources, which is what a load test has to see.
     */
    @Volatile var holdRunningStreamsMs: Long = 0L
    /** The account's pinned ids, as the first page of the list reports them. */
    val pinned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** How many rows one account page carries whatever `n` asks (the service's window is 200; a small account still pages when this is small). */
    @Volatile var accountPageSize = 200

    /** What one request meets instead of, or before, its answer. */
    sealed interface Fault {
        /** No fault: the request is answered normally. For scripting a fault on the second request of a route rather than the first. */
        data object Pass : Fault
        /** An HTTP refusal in the API's own words, with the `Retry-After` the server names when it does (seconds, or an HTTP-date). */
        data class Status(val code: Int, val errorCode: String, val message: String, val retryAfter: String? = null) : Fault
        /**
         * The load balancer's own answer when the backend behind it failed or reset: a bare [code] with its HTML page
         * and no Connect body — what Bennett's phone met on `GetBlobForAgentKV` on 2026-09-23 ("HTTP 502"). Nothing
         * was processed.
         */
        data class Gateway(val code: Int = 502, val retryAfter: String? = null) : Fault
        /** The request is read — and processed when [processed] — and the connection closes without a byte of the reply. */
        data class LostReply(val processed: Boolean = true) : Fault
        /** The reply's body is cut half-way; the request was processed. */
        data object TruncatedBody : Fault
        /** No answer at all until the client's read timeout; the request was processed when [processed]. */
        data class Silence(val processed: Boolean = false) : Fault
        /** A stream that delivers [events] frames past the resume position and then closes without `done`. */
        data class StreamCut(val events: Int) : Fault

        /**
         * The request is processed at once and its answer held on its way back until [release]: a reply exactly as
         * slow as the test needs, where a round trip set long enough stood in for one — and slowed every other request
         * the server met meanwhile, the queue's poll among them (#281's CI, `QueuedMessagePlacementTest`).
         */
        class Held : Fault {
            private val gate = java.util.concurrent.CountDownLatch(1)
            fun release() = gate.countDown()
            internal fun await() = gate.await(HOLD_CEILING_S, TimeUnit.SECONDS)
        }
    }

    fun start(): FaultServer {
        if (http2) server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                if (request.sequenceNumber == 0) connections.incrementAndGet()
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
            // The conversation state, however it is asked: the removed unary, or the stream the desktop reads it off —
            // which, asked with `purpose = LIVE`, is an open chat holding the stream rather than a read.
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "StreamConversation" && isLive(request) -> Route.Live
            segments.size == 2 && segments[0] == RECORD_SERVICE && (segments[1] == "GetLatestAgentConversationState" || segments[1] == "StreamConversation") -> Route.RecordState
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "GetBlobForAgentKV" -> Route.Blob
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "AddAsyncFollowupBackgroundComposer" -> Route.QueueAdd
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "ListPendingFollowups" -> Route.QueueList
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "DeletePendingFollowup" -> Route.QueueDelete
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "UpdatePendingFollowup" -> Route.QueueUpdate
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "ReorderPendingFollowup" -> Route.QueueReorder
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "SubmitPendingFollowupNow" -> Route.QueueSendNow
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "MarkFollowupEditing" -> Route.QueueEditing
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "InjectBackgroundComposerContext" -> Route.Steer
            // The account's list and the Projects' memberships (the sidebar's account layer, Extended mode).
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "ListBackgroundComposers" -> Route.AccountList
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "ListWorkersForManager" -> Route.Workers
            segments.size == 2 && segments[0] == RECORD_SERVICE && segments[1] == "ListBackgroundComposerChildren" -> Route.Children
            segments.size == 2 && segments[0] == RECORD_SERVICE && (segments[1] == "PinBackgroundComposers" || segments[1] == "UnpinBackgroundComposers") -> Route.Pins
            else -> Route.Other
        }
    }

    private fun serve(request: RecordedRequest): MockResponse {
        val route = route(request)
        val url = request.requestUrl!!
        val fault = faults[route]?.let { queue -> take(queue, url.encodedPath) }?.fault
            ?: standing[route]?.takeIf { it.matches(url.encodedPath) }?.fault
        seen += Seen(route, request.method ?: "", request.path ?: "", nowMillis(), fault, request.getHeader("Last-Event-ID"))
        val segments = url.encodedPath.trimStart('/').split('/')
        val processed = when (fault) {
            is Fault.LostReply -> fault.processed
            is Fault.Silence -> fault.processed
            is Fault.Status, is Fault.Gateway -> false
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
                val newestFirst = runs.values.filter { it.agentId == segments[2] }.sortedWith(compareByDescending<RunDto> { it.createdAt }.thenByDescending { it.id })
                val all = if (runsOldestFirst) newestFirst.asReversed() else newestFirst
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
            // A refused live stream is refused at once, not held first.
            Route.Live -> if (fault is Fault.Status || fault is Fault.Gateway) MockResponse() else live(request)
            Route.Blob -> blob(request)
            Route.QueueAdd -> queueAdd(request, processed)
            Route.QueueList -> queueList(request)
            Route.QueueDelete -> queueDelete(request)
            Route.QueueUpdate -> queueUpdate(request)
            Route.QueueReorder -> queueReorder(request)
            Route.QueueSendNow -> queueSendNow(request)
            Route.QueueEditing -> json(200, """{"success":true}""")
            Route.Steer -> steer(request)
            Route.AccountList -> accountList(request)
            Route.Workers -> {
                val manager = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject["managerBcId"]?.jsonPrimitive?.contentOrNull ?: ""
                val items = workers[manager].orEmpty().joinToString(",") { (worker, kind) -> """{"workerBcId":"$worker","managerBcId":"$manager","spawnKind":"$kind"}""" }
                json(200, """{"memberships":[$items]}""")
            }
            Route.Children -> {
                val parent = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject["parentBcId"]?.jsonPrimitive?.contentOrNull ?: ""
                json(200, """{"composers":[${composers.values.filter { it.sideChatOf == parent }.sortedByDescending { it.activityMs }.joinToString(",") { it.json() }}]}""")
            }
            Route.Pins -> json(200, "{}")
            Route.Other -> json(404, error("not_found", "No such route in the fault server: ${request.method} ${url.encodedPath}"))
        }
        answer.getBody()?.size?.let { size -> bytesByRoute.merge(route, size, Long::plus) }
        return when (fault) {
            null, Fault.Pass, is Fault.StreamCut -> answer.withWeather()
            // A refusal from the account service is a Connect error body; from the documented API, the API's own.
            is Fault.Status -> json(fault.code, if (route.isAccount) connectError(fault.errorCode, fault.message) else error(fault.errorCode, fault.message)).apply { fault.retryAfter?.let { setHeader("Retry-After", it) } }.withWeather()
            is Fault.Gateway -> gateway(fault.code, fault.retryAfter).withWeather()
            is Fault.LostReply -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            Fault.TruncatedBody -> answer.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY).withWeather()
            is Fault.Silence -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            is Fault.Held -> {
                held += fault
                fault.await()
                answer.withWeather()
            }
        }
    }

    /** The next fault queued for [path], taken by one request only: requests served side by side never share one. */
    private fun take(queue: ConcurrentLinkedQueue<Scripted>, path: String): Scripted? {
        while (true) {
            val next = queue.firstOrNull { it.matches(path) } ?: return null
            if (queue.remove(next)) return next
        }
    }

    /** The replies held back by [Fault.Held], let go on [close] so a test that failed before its release does not wedge the shutdown. */
    private val held = CopyOnWriteArrayList<Fault.Held>()

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

    /** A `StreamConversation` request asking for the live stream: its body, read without consuming it. */
    private fun isLive(request: RecordedRequest): Boolean = runCatching {
        val body = request.body.clone().readByteArray()
        val length = ((body[1].toInt() and 0xFF) shl 24) or ((body[2].toInt() and 0xFF) shl 16) or ((body[3].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
        CursorJson.parseToJsonElement(String(body, 5, length, Charsets.UTF_8)).jsonObject["purpose"]?.jsonPrimitive?.contentOrNull == "STREAM_CONVERSATION_PURPOSE_LIVE"
    }.getOrDefault(false)

    private val Route.isAccount: Boolean get() = this == Route.Record || this == Route.RecordState || this == Route.Live || this == Route.Blob || this == Route.QueueAdd || this == Route.QueueList || this == Route.QueueDelete || this == Route.QueueUpdate || this == Route.QueueReorder || this == Route.QueueSendNow || this == Route.QueueEditing || this == Route.Steer || this == Route.AccountList || this == Route.Workers || this == Route.Children || this == Route.Pins

    // ---- the account's list ----------------------------------------------------------------------------------------

    private fun Composer.json(): String = buildString {
        append("{\"bcId\":\"").append(id).append("\",\"name\":").append(quote(name))
        append(",\"isArchived\":").append(archived)
        append(",\"status\":\"").append(if (running) "BACKGROUND_COMPOSER_STATUS_RUNNING" else "BACKGROUND_COMPOSER_STATUS_FINISHED").append('"')
        append(",\"lastMessageActivityAtMs\":\"").append(activityMs).append('"')
        append(",\"createdAtMs\":\"").append(activityMs).append('"')
        if (project) append(",\"projectMetadata\":{\"appearance\":{\"icon\":\"lightning\",\"colorId\":\"blue\"}}")
        manager?.let { append(",\"managerAgentId\":\"").append(it).append('"') }
        sideChatOf?.let { append(",\"sideChatInfo\":{\"parentBcId\":\"").append(it).append("\"}") }
        append('}')
    }

    /**
     * `ListBackgroundComposers`: one record by `bcId`; else the list newest first, `n` at a time and never more than
     * [accountPageSize], paged by `pageToken` (asked for with `usePageTokens`) or by `lastMessageActivityAtMsOffset`
     * the older way; the pins ride with the first page when `includePinnedState` asks.
     */
    private fun accountList(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val ordered = composers.values.sortedWith(compareByDescending<Composer> { it.activityMs }.thenBy { it.id })
        body["bcId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { id ->
            composerReads += id
            val one = composers[id]
            return json(200, """{"composers":[${one?.json() ?: ""}],"hasMore":false,"didLoadStatus":true}""")
        }
        val n = minOf(body["n"]?.jsonPrimitive?.intOrNull ?: 200, accountPageSize)
        val start = body["pageToken"]?.jsonPrimitive?.contentOrNull?.removePrefix("p:")?.toIntOrNull()
            ?: body["lastMessageActivityAtMsOffset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { offset -> ordered.indexOfFirst { it.activityMs < offset }.takeIf { it >= 0 } ?: ordered.size }
            ?: 0
        val page = ordered.drop(start).take(n)
        val hasMore = start + n < ordered.size
        val pins = if (body["includePinnedState"]?.jsonPrimitive?.booleanOrNull == true) ""","pinnedBcIds":[${pinned.joinToString(",") { "\"$it\"" }}],"didLoadPinnedState":true""" else ""
        val token = if (hasMore && body["usePageTokens"]?.jsonPrimitive?.booleanOrNull == true) ""","nextPageToken":"p:${start + n}"""" else ""
        return json(200, """{"composers":[${page.joinToString(",") { it.json() }}],"hasMore":$hasMore,"didLoadStatus":true$pins$token}""")
    }

    // ---- the account's queue ----------------------------------------------------------------------------------------

    /**
     * `AddAsyncFollowupBackgroundComposer {bcId, followup, followupId, synchronous, …}`: behind a turn under way the
     * message joins the account's queue and the answer names no run — a Project's names the run it will start (see
     * [namesQueuedRuns]); on a free agent (or sent `synchronous`) the account starts the run at once and names it,
     * filing the prompt in the transcript as `POST /runs` does.
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
            if (!namesQueuedRuns) {
                pending.getOrPut(agentId) { CopyOnWriteArrayList() } += Pending(followupId, text, clock())
                return json(200, "{}")
            }
            val sequence = ids.incrementAndGet()
            val runId = "run-followup-$sequence"
            if (listsQueuedRuns) {
                val at = Instant.ofEpochMilli(clock() + sequence).toString()
                runs[runId] = RunDto(id = runId, agentId = agentId, status = "CREATING", createdAt = at, updatedAt = at)
            }
            pending.getOrPut(agentId) { CopyOnWriteArrayList() } += Pending(followupId, text, clock(), runId = runId)
            return json(200, """{"runId":"$runId"}""")
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

    /** `UpdatePendingFollowup {bcId, followupId, updatedMessage{text}}`: the queued message's words replaced in place. */
    private fun queueUpdate(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val followupId = body["followupId"]?.jsonPrimitive?.contentOrNull ?: ""
        val text = body["updatedMessage"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        val list = pending[agentId] ?: return json(404, connectError("not_found", "no such followup"))
        val at = list.indexOfFirst { it.followupId == followupId }
        if (at < 0) return json(404, connectError("not_found", "no such followup"))
        list[at] = list[at].copy(text = text)
        return json(200, """{"success":true}""")
    }

    /** `ReorderPendingFollowup {bcId, followupId, targetFollowupId, insertAfter}`: the message moved next to the target. */
    private fun queueReorder(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val followupId = body["followupId"]?.jsonPrimitive?.contentOrNull ?: ""
        val target = body["targetFollowupId"]?.jsonPrimitive?.contentOrNull ?: ""
        val after = body["insertAfter"]?.jsonPrimitive?.booleanOrNull == true
        val list = pending[agentId] ?: return json(404, connectError("not_found", "no such followup"))
        synchronized(list) {
            val moved = list.firstOrNull { it.followupId == followupId } ?: return json(404, connectError("not_found", "no such followup"))
            list.remove(moved)
            val at = list.indexOfFirst { it.followupId == target }.takeIf { it >= 0 } ?: return json(404, connectError("not_found", "no such target"))
            list.add(if (after) at + 1 else at, moved)
        }
        return json(200, """{"success":true}""")
    }

    /**
     * `SubmitPendingFollowupNow {bcId, followupId}`: the message sent in place of the turn under way — that turn's run
     * ends cancelled, and the message's run starts now (the one named for it when it was queued), its log whatever
     * [logs] holds for it; the followup consumed, still listed for [queueLagMs].
     */
    private fun queueSendNow(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val followupId = body["followupId"]?.jsonPrimitive?.contentOrNull ?: ""
        val p = pending[agentId]?.firstOrNull { it.followupId == followupId && it.consumedAtMs == null } ?: return json(404, connectError("not_found", "no such followup"))
        val agent = agents[agentId] ?: return json(404, connectError("not_found", "no such composer"))
        agent.latestRunId?.let { id ->
            runs[id]?.takeIf { com.cursorforandroid.domain.RunStatus.parse(it.status).isActive }?.let { run ->
                runs[id] = run.copy(status = "CANCELLED", updatedAt = Instant.ofEpochMilli(clock()).toString())
                logs[id] = logs[id].orEmpty() + ("result" to """{"runId":"$id","status":"CANCELLED","text":""}""")
            }
        }
        val run = startRun(agentId, p.text, named = p.runId)
        p.consumedAtMs = clock()
        p.consumedAtServerMs = nowMillis()
        delivered += p.followupId to run.id
        return json(200, """{"success":true}""")
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

    /** The account starts a run on [text] now — [named] when it named one when it took the message: the run record, the agent's latest, the transcript's prompt. */
    private fun startRun(agentId: String, text: String, named: String? = null): RunDto {
        val agent = agents.getValue(agentId)
        val sequence = ids.incrementAndGet()
        val runId = named ?: "run-followup-$sequence"
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
        val run = startRun(agentId, next.text, named = next.runId)
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
    /**
     * The conversation state as the account gives it. Asked as `StreamConversation` (the read Cursor's client makes,
     * PREWARM): a Connect stream — the newest turns' blobs prefetched first ([prefetchBlobs] of them, the ones the
     * request says the client holds left out), then `initial_state` with the state, then the end of the stream.
     * Asked as the removed unary `GetLatestAgentConversationState`: refused in the server's own words, as on
     * Bennett's phone on 2026-09-21. [recordStates] scripts the `conversationState` JSON per chat.
     */
    private fun recordState(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        if (path.endsWith("/GetLatestAgentConversationState")) return json(404, connectError("unimplemented", REMOVED_UNARY))
        val body = CursorJson.parseToJsonElement(ConnectStreamFixtures.requestJson(request)).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        val held = (body["preFetchedBlobIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
        streamRequests += body
        val state = stateJson(agentId) ?: return ConnectStreamFixtures.prewarmResponse("""{"turns":[],"turnTimings":[]}""", cloudAgentExtra = liveExtra(agentId), workflowStatus = workflowStatus(agentId))
        // The prefetch, as the desktop's request asks for it (`prefetch_only_last_step_per_turn`, `max_blobs_after_prefetch`):
        // the newest [prefetchTurns] turns' blobs — each turn's structure, its prompt, its last step — less what the
        // client says it holds, up to [prefetchBlobs] in all. A client holding them all is sent none.
        val record = blobRecord(agentId)
        val prefetched = ArrayList<Pair<String, ByteArray>>()
        // `filter_heavy_step_data`: a prefetched step's heavy payloads are left out, the blob keeping its id.
        val filterHeavy = body["filterHeavyStepData"]?.jsonPrimitive?.booleanOrNull == true
        if (record != null && prefetchBlobs > 0) {
            for (turnId in record.turnIds.asReversed().take(prefetchTurns)) {
                if (prefetched.size >= prefetchBlobs) break
                val turn = com.cursorforandroid.data.api.proto.ProtoWire.decode(record.blobs.getValue(turnId), com.cursorforandroid.data.api.proto.AgentSchemas.CONVERSATION_TURN)
                val agentTurn = turn["agentConversationTurn"]?.jsonObject
                val lastStep = (agentTurn?.get("steps") as? JsonArray)?.lastOrNull()?.jsonPrimitive?.contentOrNull
                val ids = listOf(turnId) + listOfNotNull(agentTurn?.get("userMessage")?.jsonPrimitive?.contentOrNull) + listOfNotNull(lastStep)
                for (id in ids) if (id !in held && prefetched.none { it.first == id }) record.blobs[id]?.let { bytes -> prefetched += id to (if (filterHeavy && id == lastStep) filteredStep(bytes) else bytes) }
            }
        }
        prefetched.forEach { (id, bytes) ->
            recordBytes.merge(agentId, bytes.size.toLong(), Long::plus)
            prefetchedIds.getOrPut(agentId) { ConcurrentHashMap.newKeySet() } += id
        }
        return ConnectStreamFixtures.prewarmResponse(state, prefetched, cloudAgentExtra = liveExtra(agentId), workflowStatus = workflowStatus(agentId))
    }

    /** The chat's `conversationState` JSON: the scripted one, else one turn per prompt of its record with the runs' timings; null when it has neither. */
    private fun stateJson(agentId: String): String? = recordStates[agentId] ?: run {
        val record = blobRecord(agentId) ?: return null
        val prompts = record.turnCount
        val chatRuns = runs.values.filter { it.agentId == agentId }.sortedBy { it.createdAt }
        val timings = (0 until prompts).joinToString(",") { t ->
            val run = chatRuns.getOrNull(t)
            val ended = run?.let { Instant.parse(it.updatedAt).toEpochMilli() } ?: 0L
            """{"durationMs":"${run?.durationMs ?: 0}","timestampMs":"$ended"}"""
        }
        val ids = record.turnIds.joinToString(",") { "\"$it\"" }
        // A Project's root when its prompts were sent in Project mode, as the account marks it.
        val root = records[agentId].orEmpty().any { step -> (step["humanMessage"] as? JsonObject)?.get("agentMode")?.jsonPrimitive?.contentOrNull == "AGENT_MODE_PROJECT" }
        """{"turns":[$ids],"turnTimings":[$timings]${if (root) ",\"isRootProjectConversation\":true" else ""}}"""
    }

    // ---- the live stream -------------------------------------------------------------------------------------------

    /** Every live `StreamConversation` request body seen (`purpose = LIVE`): what an open chat asked to hold. */
    val liveRequests = CopyOnWriteArrayList<JsonObject>()
    /** How long a live stream is held with nothing to say before the server sends a heartbeat and ends it. */
    @Volatile var liveHoldMs: Long = 20_000L
    /**
     * Over [http2], a live stream held as the account holds one: its first words at once, then a heartbeat every this
     * long and each change of the chat the moment it comes, for as long as the client reads — the server never ends
     * it. 0: held before its first byte and ended after one word, as MockWebServer answers otherwise (see [live]).
     */
    @Volatile var liveHeartbeatMs: Long = 0L
    /** The live streams held open at this moment, per chat (see [liveHeartbeatMs]): one the client gave up on and did not close still counts. */
    val liveOpen: MutableMap<String, AtomicInteger> = ConcurrentHashMap()

    fun liveOpen(agentId: String): Int = liveOpen[agentId]?.get() ?: 0
    fun liveOpenCount(): Int = liveOpen.values.sumOf { it.get() }
    private val liveVersions = ConcurrentHashMap<String, AtomicInteger>()
    private val liveLock = Object()
    @Volatile private var closed = false

    private fun liveVersion(agentId: String): Int = liveVersions[agentId]?.get() ?: 0

    /** Where the chat's live stream stands: the offset a stream resumes from, moved on by each change. */
    fun offsetKey(agentId: String): String = "off-${liveVersion(agentId)}"

    private fun liveExtra(agentId: String): String = """"lastInteractionUpdateOffsetKey":"${offsetKey(agentId)}""""

    private fun workflowStatus(agentId: String): String =
        if (agents[agentId]?.status == "ACTIVE" || agents[agentId]?.status == "RUNNING") "CLOUD_AGENT_WORKFLOW_STATUS_RUNNING" else "CLOUD_AGENT_WORKFLOW_STATUS_IDLE"

    /** The chat moved on: its offset advances, and every live stream held on it hears so at once. */
    private fun changed(agentId: String) {
        liveVersions.getOrPut(agentId) { AtomicInteger() }.incrementAndGet()
        synchronized(liveLock) { liveLock.notifyAll() }
    }

    /**
     * `StreamConversation` with `purpose = LIVE`, as an open chat holds it (`ConversationStateReader.watch`): the
     * state first unless the request resumes from the chat's current offset, then — the stream held open — the chat's
     * next change the moment it comes (see [startTurnElsewhere]), else a heartbeat once [liveHoldMs] has passed, and
     * the end of the stream. MockWebServer writes an answer whole, so the stream is held before its first byte rather
     * than between frames; the frames the client reads are the same.
     */
    private fun live(request: RecordedRequest): MockResponse {
        val body = CursorJson.parseToJsonElement(ConnectStreamFixtures.requestJson(request)).jsonObject
        val agentId = body["bcId"]?.jsonPrimitive?.contentOrNull ?: return json(400, connectError("invalid_argument", "bcId is required"))
        liveRequests += body
        val from = liveVersion(agentId)
        val messages = ArrayList<String>()
        if (body["offsetKey"]?.jsonPrimitive?.contentOrNull != offsetKey(agentId)) {
            messages += """{"initialState":{"blobId":"c3RhdGU=","cloudAgentState":{"conversationState":${stateJson(agentId) ?: """{"turns":[]}"""},${liveExtra(agentId)}},"workflowStatus":"${workflowStatus(agentId)}"}}"""
        }
        if (http2 && liveHeartbeatMs > 0) return heldLive(agentId, messages, from)
        val deadline = System.nanoTime() + liveHoldMs * 1_000_000
        synchronized(liveLock) {
            while (liveVersion(agentId) == from && !closed) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                liveLock.wait(left)
            }
        }
        messages += if (liveVersion(agentId) != from) """{"workflowStatusWithOffset":{"offsetKey":"${offsetKey(agentId)}","workflowStatus":"${workflowStatus(agentId)}"}}""" else """{"streamHeartbeat":{}}"""
        return ConnectStreamFixtures.streamResponse(messages)
    }

    /**
     * The live stream as the account holds it (see [liveHeartbeatMs]): the headers and [first] at once, then a
     * heartbeat every [liveHeartbeatMs] and each change as it comes, until the client resets the stream — which a
     * write then fails on — or the server closes.
     */
    private fun heldLive(agentId: String, first: List<String>, from: Int): MockResponse =
        // No length: a MockResponse starts out as an empty body of `Content-Length: 0`, which the client would hold the stream's first frame against.
        MockResponse().setResponseCode(200).removeHeader("Content-Length").setHeader("Content-Type", "application/connect+json").setBody(object : DuplexResponseBody {
            override fun onRequest(request: RecordedRequest, http2Stream: Http2Stream) {
                val open = liveOpen.getOrPut(agentId) { AtomicInteger() }
                open.incrementAndGet()
                try {
                    val sink = http2Stream.getSink().buffer()
                    first.forEach { sink.write(ConnectRpc.envelope(0, it.toByteArray(Charsets.UTF_8))) }
                    sink.flush()
                    var version = from
                    while (!closed) {
                        synchronized(liveLock) { if (liveVersion(agentId) == version && !closed) liveLock.wait(liveHeartbeatMs) }
                        if (closed) break
                        val now = liveVersion(agentId)
                        val message = if (now != version) {
                            version = now
                            """{"workflowStatusWithOffset":{"offsetKey":"${offsetKey(agentId)}","workflowStatus":"${workflowStatus(agentId)}"}}"""
                        } else {
                            """{"streamHeartbeat":{}}"""
                        }
                        sink.write(ConnectRpc.envelope(0, message.toByteArray(Charsets.UTF_8)))
                        sink.flush()
                    }
                } catch (_: IOException) {
                    // The client reset the stream: it is gone.
                } finally {
                    open.decrementAndGet()
                }
            }
        })

    /**
     * Another client — the web, the desktop, a coordinator messaging this worker — starts a turn in [agentId] with
     * [prompt]: the run is made running, the agent and its list entry say so, the transcript and the record take the
     * prompt, and the chat's held live streams hear it at once. Returns the run.
     */
    fun startTurnElsewhere(agentId: String, prompt: String, runId: String = "run-elsewhere-${ids.incrementAndGet()}"): RunDto {
        val at = Instant.ofEpochMilli(clock() + 60_000L).toString()
        val run = RunDto(id = runId, agentId = agentId, status = "RUNNING", createdAt = at, updatedAt = at)
        runs[runId] = run
        logs[runId] = listOf("status" to """{"runId":"$runId","status":"RUNNING"}""")
        agents[agentId] = agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = runId, updatedAt = at)
        v0[agentId]?.let { v0[agentId] = it.copy(status = "RUNNING") }
        transcripts[agentId] = transcripts[agentId].orEmpty() + V0ConversationMessageDto("$runId-u", "user_message", prompt)
        records[agentId]?.let { steps -> records[agentId] = steps + buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt) }) } }
        composers[agentId]?.let { composers[agentId] = it.copy(running = true, activityMs = it.activityMs + 60_000L) }
        changed(agentId)
        return run
    }

    /** The test changed [agentId] by hand — its runs, its record, its status: the chat's held live streams hear it at once. */
    fun touch(agentId: String) = changed(agentId)

    /** A step blob with its tool result's heavy strings emptied (over [HEAVY_CHARS] characters), as `filter_heavy_step_data` asks. */
    private fun filteredStep(bytes: ByteArray): ByteArray {
        val schema = com.cursorforandroid.data.api.proto.AgentSchemas.CONVERSATION_STEP
        val step = runCatching { com.cursorforandroid.data.api.proto.ProtoWire.decode(bytes, schema) }.getOrNull() ?: return bytes
        fun strip(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, v) -> strip(v) })
            is JsonArray -> JsonArray(element.map { strip(it) })
            is kotlinx.serialization.json.JsonPrimitive -> if (element.isString && element.content.length > HEAVY_CHARS) kotlinx.serialization.json.JsonPrimitive("") else element
        }
        val call = step["toolCall"] as? JsonObject ?: return bytes
        val stripped = JsonObject(call.mapValues { (key, value) -> if (value is JsonObject && value["result"] != null) JsonObject(value.mapValues { (k, v) -> if (k == "result") strip(v) else v }) else value })
        return com.cursorforandroid.data.api.proto.ProtoEncoder.encode(JsonObject(step + ("toolCall" to stripped)), schema)
    }

    /** Every `StreamConversation` request body seen, for the tests that check what the client told the server it holds. */
    val streamRequests = CopyOnWriteArrayList<JsonObject>()
    /** How many blobs the state stream prefetches for a chat at most (the desktop asks for 30); 0 sends none. */
    @Volatile var prefetchBlobs: Int = 30
    /** How many of the newest turns the prefetch draws from. */
    @Volatile var prefetchTurns: Int = 10

    /** The chat's blob-backed record: made from its legacy steps (and made again whenever they change), else as scripted. */
    fun blobRecord(agentId: String): BlobFixtures.Record? {
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
        blobRequests += body
        blobReads.getOrPut(blobId) { AtomicInteger() }.incrementAndGet()
        blobAnswer?.invoke(agentId, blobId)?.let { return it }
        val bytes = blobRecord(agentId)?.blobs?.get(blobId) ?: return json(404, connectError("not_found", "blob not found"))
        recordBytes.merge(agentId, bytes.size.toLong(), Long::plus)
        return json(200, """{"blobData":"${java.util.Base64.getEncoder().encodeToString(bytes)}"}""")
    }

    /** Every `GetBlobForAgentKV` request body seen, faults and scripted answers included: what the client asked with. */
    val blobRequests = CopyOnWriteArrayList<JsonObject>()

    /** The load balancer's page for a backend that failed (see [Fault.Gateway]), for a scripted [blobAnswer] too. */
    fun gateway(code: Int = 502, retryAfter: String? = null): MockResponse {
        val reason = when (code) { 502 -> "Bad Gateway"; 503 -> "Service Temporarily Unavailable"; 504 -> "Gateway Time-out"; else -> "Error" }
        return MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html")
            .setBody("<html>\r\n<head><title>$code $reason</title></head>\r\n<body>\r\n<center><h1>$code $reason</h1></center>\r\n</body>\r\n</html>\r\n")
            .apply { retryAfter?.let { setHeader("Retry-After", it) } }
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
        val holding = cutAfter == null && holdRunningStreamsMs > 0 && runs[runId]?.status == "RUNNING"
        if (holding) {
            // As the API holds a turn under way: what the log has so far, then its keep-alive comments for as long as
            // the turn goes on here, trickled so the connection (and the client's thread on it) stays taken.
            val beats = (holdRunningStreamsMs / HELD_BEAT_MS).toInt().coerceAtLeast(1)
            repeat(beats) { body.append(": keep-alive ").append("-".repeat(HELD_BEAT_BYTES - 15)).append("\n\n") }
        } else if (cutAfter == null) {
            body.append("event: done\ndata: {}\n\n")
        }
        val response = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body.toString())
        if (holding) response.throttleBody(HELD_BEAT_BYTES.toLong(), HELD_BEAT_MS, TimeUnit.MILLISECONDS)
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

    override fun close() {
        held.forEach { it.release() }
        closed = true
        synchronized(liveLock) { liveLock.notifyAll() }
        server.shutdown()
    }

    companion object {
        /** The longest a [Fault.Held] reply waits for its release: past any test's own wait, short of wedging the run. */
        const val HOLD_CEILING_S = 60L
        /** A held stream's keep-alive: its size, and how often one goes out (see [holdRunningStreamsMs]). */
        private const val HELD_BEAT_BYTES = 64
        private const val HELD_BEAT_MS = 500L
        /** A string of a prefetched step longer than this is heavy data `filter_heavy_step_data` leaves out. */
        const val HEAVY_CHARS = 2_000
        /** The account service the record RPCs belong to (see `HeadlessConversationApi.SERVICE`). */
        const val RECORD_SERVICE = "aiserver.v1.BackgroundComposerService"
        /** The server's own words for the removed unary, lowerCamel as its handler names it (Bennett's phone, 2026-09-21). */
        const val REMOVED_UNARY = "getLatestAgentConversationState has been removed"
    }
}
