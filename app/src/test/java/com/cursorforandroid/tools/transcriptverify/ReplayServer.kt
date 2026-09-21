package com.cursorforandroid.tools.transcriptverify

import com.cursorforandroid.data.api.BackgroundComposerApi
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.fixtures.BlobFixtures
import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.fixtures.CoordinatorFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The recorded fixtures served the way the two hosts serve them, so the harness's replay mode runs the very same
 * clients as a live run — Retrofit over OkHttp for api.cursor.com, the Connect JSON client and the session exchange
 * for api2, the SSE streamer — against one in-process server:
 *
 *  - `record_coordinator_pages.json` is the account's record (`FetchBackgroundComposer`, paged as asked): Bennett's
 *    message, the coordinator's narration and update, 148 silent injected turns, his next messages, the updates in
 *    every shape the record gives them. After it come the eighteen turns of `event_wall.json` (a subagent's report,
 *    a subscribed pull request's change, seven minutes apart, each answered with a remark and nothing to the user),
 *    then the user's newest prompt, whose run is still going.
 *  - `coordinator_send_fragments.sse` is that run's stream (`GET /v1/agents/{id}/runs/{runId}/stream`): the
 *    coordinator's message announced before its arguments, the arguments in growing fragments, the result on its own.
 *    Once served, the agent reads idle and the run finished, as the account would after the turn.
 *  - The run list has one finished run per record turn plus the live one, newest first, paged by `limit` with a
 *    cursor — far more turns than the list's first page, so the app has to page it (#202). Every finished run's log
 *    is gone (`410`), so the record is the only source for the finished turns.
 *  - `POST /v1/agents/{id}/runs` (a `--send`) is refused as busy while the live turn runs and names a new run once
 *    it is over; the new run's stream is the same fragments fixture. `GET /v0/agents/{id}/conversation` is the
 *    record's prompts and texts, for the documented path.
 */
class ReplayServer : AutoCloseable {
    val agentId = AGENT_ID
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }

    private val fixture = CoordinatorFixtures.json("record_coordinator_pages.json")
    private val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
    private val fragments = CoordinatorFixtures.text("coordinator_send_fragments.sse")

    /** The record: the fixture's responses, the wall's turns, then the live turn's prompt. */
    private val responses: List<JsonObject>
    /** The record index of each turn's prompt. */
    private val turnStarts: List<Int>

    init {
        val out = ArrayList<JsonObject>(fixture.getValue("responses").jsonArray.map { it.jsonObject })
        val starts = ArrayList<Int>(fixture.getValue("turnStarts").jsonArray.map { it.jsonPrimitive.int })
        wall.forEachIndexed { i, text ->
            starts += out.size
            out += buildJsonObject { put("humanMessage", buildJsonObject { put("text", text); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", turnStartedAt(starts.lastIndex).toEpochMilli().toString()) }) }
            out += buildJsonObject { put("text", "Logged; nothing for Bennett in this one.") }
            out += buildJsonObject { put("text", ""); put("isMessageDone", true) }
            out += buildJsonObject { put("status", buildJsonObject { put("type", "finished"); put("isComplete", true) }) }
        }
        starts += out.size
        out += buildJsonObject { put("humanMessage", buildJsonObject { put("text", LIVE_PROMPT); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", turnStartedAt(starts.lastIndex).toEpochMilli().toString()) }) }
        responses = out
        turnStarts = starts
    }

    /** How many turns the record has, the live one included. */
    val turnCount: Int get() = turnStarts.size

    /** The same record as the account serves it now: each turn, its prompt and its steps as blobs (see BlobFixtures). */
    private val blobs: BlobFixtures.Record by lazy { BlobFixtures.record(responses) }

    private val liveServed = AtomicBoolean(false)
    /** The runs a `--send` created, by id, and whether their stream has been served (the turn is then over). */
    private val sentRuns = ConcurrentHashMap<String, Boolean>()
    private val sentOrder = ArrayList<String>()
    private val sentPrompts = ArrayList<String>()
    private val sends = AtomicInteger(0)
    val fetches = AtomicInteger(0)
    val streams = AtomicInteger(0)

    private fun turnStartedAt(turn: Int): Instant = Instant.ofEpochMilli(FIRST_TURN_AT + turn * TURN_SPACING_MS)
    private fun liveStartedAt(): Instant = turnStartedAt(turnStarts.lastIndex)

    /** One run per record turn, oldest first, the live one newest; then the runs a send created. */
    private fun runs(): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        turnStarts.indices.forEach { i ->
            val live = i == turnStarts.lastIndex
            out += run(if (live) LIVE_RUN else "run-%03d".format(i), turnStartedAt(i), finished = !live || liveServed.get())
        }
        synchronized(sentOrder) { sentOrder.forEachIndexed { j, id -> out += run(id, liveStartedAt().plusMillis((j + 1) * 60_000L), finished = sentRuns[id] == true) } }
        return out
    }

    private fun run(id: String, at: Instant, finished: Boolean): JsonObject = buildJsonObject {
        put("id", id); put("agentId", agentId)
        put("status", if (finished) "FINISHED" else "RUNNING")
        put("createdAt", at.toString())
        put("updatedAt", (if (finished) at.plusMillis(TURN_MS) else at).toString())
        if (finished) { put("durationMs", TURN_MS); put("result", "") }
    }

    /** The agent is on a turn: the live one before its stream was served, or a sent one before its. */
    private fun running(): Boolean = !liveServed.get() || sentRuns.values.any { !it }

    private fun latestRunId(): String = synchronized(sentOrder) { sentOrder.lastOrNull() } ?: LIVE_RUN

    fun start(): ReplayServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                serve(request)
            } catch (t: Throwable) {
                MockResponse().setResponseCode(500).setBody("""{"error":{"code":"replay_error","message":"${t.message?.replace('"', '\'')}"}}""")
            }
        }
        server.start()
        return this
    }

    val baseUrl: String get() = server.url("/").toString()

    /** The harness's backend over this server: the app's own clients, pointed here, through [ledger]. */
    fun backend(extended: Boolean, ledger: CallLedger): VerifyBackend {
        val key = { "replay-key" }
        val apiClient = CursorApiFactory.okHttp(key).newBuilder().addInterceptor(ledger).build()
        val api = CursorApiFactory.retrofit(apiClient, baseUrl)
        val streamer = SseRunStreamer(CursorApiFactory.sseClient(apiClient), key, urlFor = { a, r -> "${baseUrl}v1/agents/$a/runs/$r/stream" })
        val accountClient = CursorApiFactory.loginClient().newBuilder().addInterceptor(ledger).build()
        val rpc = ConnectJsonClient(accountClient, baseUrl.trimEnd('/'))
        val tokens = SessionTokenProvider(accountClient, key, apiUrl = baseUrl.trimEnd('/'), sessionAllowed = { extended })
        val record = HeadlessConversationApi(rpc, tokens)
        val account = BackgroundComposerApi(rpc, tokens)
        return VerifyBackend(api, streamer, record, { account.list() }, ledger, "backend=replay (fixtures served in-process at ${server.hostName}:${server.port})")
    }

    private fun serve(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
        val path = url.encodedPath
        val segments = path.trimStart('/').split('/')
        return when {
            path.endsWith("/auth/exchange_user_api_key") -> json("""{"accessToken":"${fakeJwt()}","refreshToken":"rt"}""")
            path.endsWith("/FetchBackgroundComposer") -> {
                fetches.incrementAndGet()
                val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val start = body["startIndex"]?.jsonPrimitive?.int ?: 0
                val limit = body["limit"]?.jsonPrimitive?.int ?: 200
                val page = responses.drop(start).take(limit)
                json(buildJsonObject { put("responses", JsonArray(page)); put("totalResponses", responses.size) }.toString())
            }
            path.endsWith("/GetLatestAgentConversationState") -> {
                // The turns by their blob ids, as the account names them (see BlobFixtures): the blob-backed read.
                val ids = blobs.turnIds.joinToString(",") { "\"$it\"" }
                val timings = turnStarts.indices.joinToString(",") { t ->
                    val live = t == turnStarts.lastIndex && !liveServed.get()
                    if (live) "{}" else """{"durationMs":"$TURN_MS","timestampMs":"${turnStartedAt(t).toEpochMilli() + TURN_MS}"}"""
                }
                json("""{"latestConversationState":{"conversationState":{"turns":[$ids],"turnTimings":[$timings],"isRootProjectConversation":true},"numPriorInteractionUpdates":"0"}}""")
            }
            path.endsWith("/GetBlobForAgentKV") -> {
                fetches.incrementAndGet()
                val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val blobId = body["blobId"]?.jsonPrimitive?.content ?: ""
                val bytes = blobs.blobs[blobId] ?: return json("""{"code":"not_found","message":"blob not found"}""", 404)
                json("""{"blobData":"${java.util.Base64.getEncoder().encodeToString(bytes)}"}""")
            }
            path.endsWith("/ListBackgroundComposers") -> {
                val status = if (running()) "BACKGROUND_COMPOSER_STATUS_RUNNING" else "BACKGROUND_COMPOSER_STATUS_FINISHED"
                json("""{"composers":[{"bcId":"$agentId","name":"Revenue Scaling Pipeline","isArchived":false,"status":"$status","source":"BACKGROUND_COMPOSER_SOURCE_GLASS","projectMetadata":{},"lastMessageActivityAtMs":"${liveStartedAt().toEpochMilli()}"}],"pinnedBcIds":[],"didLoadPinnedState":true,"hasMore":false}""")
            }
            segments.size == 2 && segments[0] == "v1" && segments[1] == "me" -> json("""{"apiKeyName":"replay","userEmail":"replay@example.com"}""")
            segments.size == 3 && segments[0] == "v1" && segments[1] == "agents" && segments[2] == agentId && request.method == "GET" -> json(agent().toString())
            segments.size == 4 && segments[0] == "v1" && segments[1] == "agents" && segments[3] == "runs" && request.method == "GET" -> {
                val limit = url.queryParameter("limit")?.toIntOrNull() ?: 50
                val cursor = url.queryParameter("cursor")
                val newestFirst = runs().asReversed()
                val from = cursor?.let { c -> newestFirst.indexOfFirst { it.getValue("id").jsonPrimitive.content == c }.takeIf { it >= 0 } } ?: 0
                val page = newestFirst.drop(from).take(limit)
                val next = newestFirst.getOrNull(from + limit)?.getValue("id")?.jsonPrimitive?.content
                json(buildJsonObject { put("items", JsonArray(page)); next?.let { put("nextCursor", it) } }.toString())
            }
            segments.size == 4 && segments[0] == "v1" && segments[1] == "agents" && segments[3] == "runs" && request.method == "POST" -> {
                val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val text = body["prompt"]?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
                if (running()) return json("""{"error":{"code":"agent_busy","message":"Agent is busy."}}""", 409)
                val id = "run-sent-${sends.incrementAndGet()}"
                val at: Instant
                synchronized(sentOrder) { sentOrder += id; sentPrompts += text; at = liveStartedAt().plusMillis(sentOrder.size * 60_000L) }
                sentRuns[id] = false
                json(buildJsonObject { put("run", run(id, at, finished = false)) }.toString())
            }
            segments.size == 5 && segments[0] == "v1" && segments[3] == "runs" && request.method == "GET" -> {
                val run = runs().firstOrNull { it.getValue("id").jsonPrimitive.content == segments[4] } ?: return json("""{"error":{"code":"not_found","message":"Run not found."}}""", 404)
                json(run.toString())
            }
            segments.size == 6 && segments[0] == "v1" && segments[3] == "runs" && segments[5] == "stream" -> {
                streams.incrementAndGet()
                val runId = segments[4]
                when {
                    runId == LIVE_RUN -> { liveServed.set(true); sse(fragments.replace(LIVE_RUN, runId)) }
                    sentRuns.containsKey(runId) -> { sentRuns[runId] = true; sse(fragments.replace(LIVE_RUN, runId)) }
                    else -> json("""{"error":{"code":"stream_expired","message":"This run's live stream has expired."}}""", 410)
                }
            }
            segments.size == 4 && segments[0] == "v0" && segments[3] == "conversation" -> {
                val messages = buildJsonArray {
                    turnStarts.forEachIndexed { i, start ->
                        val prompt = responses[start]["humanMessage"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: responses[start]["userMessage"]?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
                        add(buildJsonObject { put("id", "m-$i-u"); put("type", "user_message"); put("text", prompt) })
                        val end = turnStarts.getOrNull(i + 1) ?: responses.size
                        val reply = (start + 1 until end).mapNotNull { responses[it]["text"]?.jsonPrimitive?.content?.takeIf { t -> t.isNotBlank() } }.joinToString("\n\n")
                        if (reply.isNotEmpty()) add(buildJsonObject { put("id", "m-$i-a"); put("type", "assistant_message"); put("text", reply) })
                    }
                    synchronized(sentOrder) { sentPrompts.forEachIndexed { j, text -> add(buildJsonObject { put("id", "m-sent-$j"); put("type", "user_message"); put("text", text) }) } }
                }
                json(buildJsonObject { put("id", agentId); put("messages", messages) }.toString())
            }
            else -> json("""{"error":{"code":"not_found","message":"No such route in the replay server: ${request.method} $path"}}""", 404)
        }
    }

    private fun agent(): JsonObject = buildJsonObject {
        put("id", agentId); put("name", "Revenue Scaling Pipeline")
        put("status", if (running()) "ACTIVE" else "IDLE")
        put("createdAt", turnStartedAt(0).toString())
        put("updatedAt", (if (liveServed.get()) liveStartedAt().plusMillis(TURN_MS) else liveStartedAt()).toString())
        put("latestRunId", latestRunId())
        put("url", "https://cursor.com/agents/$agentId")
    }

    private fun json(body: String, code: Int = 200): MockResponse = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun sse(body: String): MockResponse = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

    /** A token whose `exp` is an hour away, so the session provider never re-exchanges mid-run. */
    private fun fakeJwt(): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":${System.currentTimeMillis() / 1000 + 3600}}""".toByteArray())
        return "$header.$payload.sig"
    }

    override fun close() = server.shutdown()

    companion object {
        const val AGENT_ID = "bc-verify-replay"
        const val LIVE_RUN = "run-coord-frag"
        const val LIVE_PROMPT = "So where we at rn"
        /** The wall's cadence: one turn every seven minutes, from a fixed morning. */
        val FIRST_TURN_AT: Long = Instant.parse("2026-09-15T08:00:00Z").toEpochMilli()
        const val TURN_SPACING_MS = 7 * 60_000L
        const val TURN_MS = 12_000L
    }
}
