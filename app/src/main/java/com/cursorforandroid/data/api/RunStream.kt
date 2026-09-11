package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.dto.ApiErrorBodyDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.SseErrorDto
import com.cursorforandroid.data.api.dto.SseResultDto
import com.cursorforandroid.data.api.dto.SseStatusDto
import com.cursorforandroid.data.api.dto.SseTextDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.domain.RunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Events emitted by `GET /v1/agents/{id}/runs/{runId}/stream`. */
sealed interface RunStreamEvent {
    data class Status(val runId: String?, val status: RunStatus) : RunStreamEvent
    data class Assistant(val text: String) : RunStreamEvent
    data class Thinking(val text: String) : RunStreamEvent
    data class ToolCall(val call: SseToolCallDto) : RunStreamEvent
    data object Heartbeat : RunStreamEvent
    data class Result(
        val runId: String?,
        val status: RunStatus,
        val text: String?,
        val durationMs: Long?,
        val git: RunGitDto?,
    ) : RunStreamEvent
    /**
     * The stream stopped before the run did. Always the last event of a pass. [resumeFrom] is the id of the last
     * event delivered, to hand back as `lastEventId` when reconnecting; null means a reconnect has to start from
     * the run's first event again (nothing identified arrived, or the server rejected the id we had).
     */
    data class Error(val code: String, val message: String, val resumeFrom: String? = null) : RunStreamEvent {
        /** The log is past its retention window: reconnecting can never bring it back. */
        val isExpired: Boolean get() = code == STREAM_EXPIRED
        /** Nothing about this stream will change on a retry; only the run record can say how the run ended. */
        val isFatal: Boolean get() = isExpired || code in FATAL_CODES || code.startsWith("http_4")

        companion object {
            const val STREAM_EXPIRED = "stream_expired"
            const val INVALID_LAST_EVENT_ID = "invalid_last_event_id"
            /**
             * Codes after which asking the same stream again is pointless: the log is gone, or the request is one we
             * may not (or cannot) make. Everything else — the server's own `stream_unavailable` / `upstream_error` /
             * `internal_error`, a dropped socket, an exhausted retry budget — describes the connection, not the run,
             * and is worth another attempt once the run record confirms the run is still going.
             */
            val FATAL_CODES: Set<String> = setOf(
                STREAM_EXPIRED,
                "unauthorized", "api_key_not_found", "forbidden", "role_forbidden", "plan_required", "feature_unavailable",
                "service_account_required", "validation_error", "not_found", "run_not_found", "agent_not_found",
            )
        }
    }
    data object Done : RunStreamEvent
}

/**
 * Follows one run over its SSE stream. A pass ends after the run's `result` / `done`, or with exactly one
 * [RunStreamEvent.Error] saying why it stopped early. Implementations may ride out short transport failures
 * themselves (resuming with `Last-Event-ID`); anything the server says in-band is left to the caller, which is the
 * only party that can check the run record to tell a dead stream from a finished run.
 */
interface RunStreamer {
    fun stream(agentId: String, runId: String, lastEventId: String? = null): Flow<RunStreamEvent>
}

/**
 * A raw SSE frame: `event:`, `id:`, `retry:` and joined `data:` lines. [resetId] is the spec's empty `id:`, which
 * drops the resume position rather than setting it to an empty one, and [retryMillis] a digits-only `retry:`.
 */
data class SseFrame(
    val event: String,
    val id: String?,
    val data: String,
    val resetId: Boolean = false,
    val retryMillis: Long? = null,
)

/**
 * Server-Sent Events parser over an okio [BufferedSource], for streams framed the way this endpoint frames them:
 * LF or CRLF line endings. A bare CR, which the specification also allows as a line ending, is not recognised.
 */
object SseParser {
    /** A line longer than this, or a frame whose data is, is not one of ours; the connection is given up on. */
    private const val MAX_LINE_BYTES = 1L shl 20
    private const val MAX_DATA_CHARS = 4 shl 20

    /** What a `retry:` is honoured within: below a second it is a reconnect loop, past a minute the caller decides. */
    private const val MIN_RETRY_MS = 1_000L
    private const val MAX_RETRY_MS = 60_000L

    /**
     * The next complete frame, or null when there is not going to be one: the stream ended (whatever it had
     * half-written when it did is discarded, as the SSE spec requires — a truncated frame is not a frame, and
     * passing one on would have the caller resume from an event it never really received), or a line ran past
     * anything this protocol has a use for, which is a connection to give up on rather than a buffer to grow.
     */
    fun readFrame(source: BufferedSource): SseFrame? {
        var event = "message"
        var id: String? = null
        var resetId = false
        var retry: Long? = null
        val data = StringBuilder()
        var sawField = false
        while (true) {
            val line = try {
                source.readUtf8LineStrict(MAX_LINE_BYTES)
            } catch (_: IOException) {
                return null
            }
            if (line.isEmpty()) {
                if (sawField) return SseFrame(event, id, data.toString().removeSuffix("\n"), resetId, retry)
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            sawField = true
            when (field) {
                "event" -> event = value
                // Past the cap the frame is left short, so it fails to decode and is skipped rather than kept whole.
                "data" -> if (data.length <= MAX_DATA_CHARS) data.append(value).append('\n')
                // An empty value says the stream has no resume position rather than that it is the empty string; a
                // value with a NUL in it is not an id at all and is ignored, leaving the position as it was.
                "id" -> if (!value.contains('\u0000')) {
                    id = value.ifEmpty { null }
                    resetId = value.isEmpty()
                }
                // The reconnection time the server asks for: ASCII digits only, and only as far as it is worth honouring.
                "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                    retry = value.toLongOrNull()?.coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)
                }
            }
        }
    }

    /** What a frame turned out to be. Only a frame that said something may be resumed from. */
    sealed interface Parsed {
        data class Delivered(val event: RunStreamEvent) : Parsed
        /** A frame of a kind this client has no use for (`interaction_update`, anything new). */
        data object Ignored : Parsed
        /** The frame's data is not what its name promised: truncated, or a shape this version cannot read. */
        data object Undecodable : Parsed
    }

    fun parse(frame: SseFrame): Parsed {
        val json = CursorJson
        val decoded = runCatching {
            when (frame.event) {
                "status" -> json.decodeFromString(SseStatusDto.serializer(), frame.data).let { RunStreamEvent.Status(it.runId, RunStatus.parse(it.status)) }
                "assistant" -> RunStreamEvent.Assistant(json.decodeFromString(SseTextDto.serializer(), frame.data).text)
                "thinking" -> RunStreamEvent.Thinking(json.decodeFromString(SseTextDto.serializer(), frame.data).text)
                "tool_call" -> RunStreamEvent.ToolCall(json.decodeFromString(SseToolCallDto.serializer(), frame.data))
                "heartbeat" -> RunStreamEvent.Heartbeat
                "result" -> json.decodeFromString(SseResultDto.serializer(), frame.data).let {
                    RunStreamEvent.Result(it.runId, RunStatus.parse(it.status), it.text, it.durationMs, it.git)
                }
                "error" -> json.decodeFromString(SseErrorDto.serializer(), frame.data).let { RunStreamEvent.Error(it.code, it.message) }
                "done" -> RunStreamEvent.Done
                else -> return Parsed.Ignored // interaction_update and unknown events are intentionally ignored
            }
        }
        return decoded.getOrNull()?.let { Parsed.Delivered(it) } ?: Parsed.Undecodable
    }

    fun toEvent(frame: SseFrame): RunStreamEvent? = (parse(frame) as? Parsed.Delivered)?.event
}

/**
 * Streams a run over SSE. Transport trouble — a dropped socket, a `429` / `5xx`, a connection the server closed
 * before the run ended — is retried a few times with `Last-Event-ID`, so a blip costs nothing. Anything the server
 * says in-band (an `error` frame, a `410`, a rejected `Last-Event-ID`) ends the pass with one [RunStreamEvent.Error]
 * carrying the position to resume from; the caller decides whether the run is worth reconnecting to.
 */
class SseRunStreamer(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String?,
    private val urlFor: (agentId: String, runId: String) -> String = CursorEndpoints::streamUrl,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val now: () -> Long = System::currentTimeMillis,
    /** Seam for tests: the wait between attempts, so a honoured `Retry-After` is asserted without spending it. */
    private val waiter: suspend (Long) -> Unit = { delay(it) },
) : RunStreamer {

    override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> = flow {
        var lastId = lastEventId
        var attempt = 0
        /** The reconnection time the server last asked for, which outlives the connection that carried it. */
        var serverRetryMs: Long? = null
        while (currentCoroutineContext().isActive) {
            val outcome = connectOnce(agentId, runId, lastId) { frame ->
                frame.retryMillis?.let { serverRetryMs = it }
                // A stream that says it has no resume position has to be believed even when the frame saying so is
                // one this client cannot read: the pass then ends and the caller rebuilds from nothing.
                if (frame.resetId) lastId = null
                // Only a frame this client actually read may move the resume position: reconnecting past one it
                // could not decode would skip whatever the frame was carrying for good.
                val parsed = SseParser.parse(frame)
                when (parsed) {
                    is SseParser.Parsed.Delivered -> {
                        frame.id?.let { lastId = it }
                        parsed.event.takeUnless { it is RunStreamEvent.Error }?.let { emit(it) }
                    }
                    SseParser.Parsed.Ignored -> frame.id?.let { lastId = it }
                    SseParser.Parsed.Undecodable -> Unit
                }
                parsed
            }
            when (outcome) {
                is Outcome.Terminal -> return@flow
                is Outcome.Stopped -> {
                    emit(RunStreamEvent.Error(outcome.code, outcome.message, resumeFrom = lastId))
                    return@flow
                }
                is Outcome.Restart -> {
                    // The id we held is not one of this run's (or the server forgot it): the next connection replays
                    // the run from its first event, and the caller must rebuild from nothing.
                    emit(RunStreamEvent.Error(RunStreamEvent.Error.INVALID_LAST_EVENT_ID, outcome.message, resumeFrom = null))
                    return@flow
                }
                is Outcome.Retry -> {
                    attempt++
                    // Without a position to resume from, the next connection replays the run from its first event —
                    // into an accumulator that already holds part of it, which would read as the agent saying
                    // everything twice. Ending the pass hands that decision to the caller, which rebuilds from
                    // nothing when it comes back (see [RunStreamEvent.Error.resumeFrom]).
                    if (attempt > maxAttempts || lastId == null) {
                        emit(RunStreamEvent.Error("stream_unavailable", outcome.reason, resumeFrom = lastId))
                        return@flow
                    }
                    waiter(backoffMillis(attempt, outcome.retryAfterMs, serverRetryMs))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private sealed interface Outcome {
        /** The run's `result` (and `done`) came through. */
        data object Terminal : Outcome
        /** Transport trouble; worth another connection right away. [retryAfterMs] is what the server asked for, if it did. */
        data class Retry(val reason: String, val retryAfterMs: Long? = null) : Outcome
        /** The server ended the stream on purpose: an in-band `error` frame, a `410`, a `4xx`. */
        data class Stopped(val code: String, val message: String) : Outcome
        /** `400 invalid_last_event_id`: only a connection without a `Last-Event-ID` can help. */
        data class Restart(val message: String) : Outcome
    }

    private suspend fun FlowCollector<RunStreamEvent>.connectOnce(
        agentId: String,
        runId: String,
        lastId: String?,
        onFrame: suspend FlowCollector<RunStreamEvent>.(SseFrame) -> SseParser.Parsed,
    ): Outcome {
        val request = Request.Builder()
            .url(urlFor(agentId, runId))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply {
                apiKeyProvider()?.let { header("Authorization", "Bearer $it") }
                lastId?.let { header("Last-Event-ID", it) }
            }
            .build()
        val call: Call = client.newCall(request)
        // Blocking socket reads are not interruptible; cancelling the call from the completion handler is.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                return Outcome.Retry(e.message ?: "connection failed")
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    val error = body.takeIf { it.isNotBlank() }?.let { runCatching { CursorJson.decodeFromString(ApiErrorBodyDto.serializer(), it) }.getOrNull() }?.error
                    return when (resp.code) {
                        410 -> Outcome.Stopped(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired.")
                        401, 403 -> Outcome.Stopped("unauthorized", "Not authorized to stream this run.")
                        404 -> Outcome.Stopped("run_not_found", "Run not found.")
                        400 -> if (lastId != null && error?.code == RunStreamEvent.Error.INVALID_LAST_EVENT_ID) {
                            Outcome.Restart(error.message.ifBlank { "The server rejected the resume position." })
                        } else {
                            Outcome.Stopped(error?.code ?: "http_400", error?.message?.ifBlank { null } ?: "Stream request failed (400)")
                        }
                        408, 429, in 500..599 -> Outcome.Retry("HTTP ${resp.code}", resp.retryAfterMillis())
                        else -> Outcome.Stopped(error?.code ?: "http_${resp.code}", error?.message?.ifBlank { null } ?: "Stream request failed (${resp.code})")
                    }
                }
                val source = resp.body?.source() ?: return Outcome.Retry("empty body")
                var sawResult = false
                try {
                    while (true) {
                        val frame = SseParser.readFrame(source) ?: break
                        val delivered = (onFrame(frame) as? SseParser.Parsed.Delivered)?.event
                        when (frame.event) {
                            // The server's word on why it is stopping. Not part of the run: the caller checks the
                            // run record and, unless the log is gone for good, comes back with `Last-Event-ID`.
                            "error" -> {
                                val error = delivered as? RunStreamEvent.Error
                                return Outcome.Stopped(error?.code ?: "stream_error", error?.message?.ifBlank { null } ?: "The run's stream reported an error.")
                            }
                            // Only a result this client could read ends the run. One it could not is a frame it
                            // never received: the resume position stayed where it was, so another connection asks
                            // the server to send it again rather than the pass declaring the run finished without it.
                            "result" -> if (delivered is RunStreamEvent.Result) {
                                sawResult = true
                            } else {
                                return Outcome.Retry("the run's result could not be read")
                            }
                            "done" -> return if (sawResult) Outcome.Terminal else Outcome.Stopped("stream_closed", "The stream ended before the run did.")
                        }
                    }
                } catch (e: IOException) {
                    if (!currentCoroutineContext().isActive) return Outcome.Terminal
                    if (!sawResult) return Outcome.Retry(e.message ?: "stream interrupted")
                }
                return if (sawResult) Outcome.Terminal else Outcome.Retry("stream closed before result")
            }
        } finally {
            handle?.dispose()
        }
    }

    /** The schedule this client keeps, never shorter than the reconnection time the server asked for in a `retry:`. */
    private fun backoffMillis(attempt: Int, retryAfterMs: Long?, serverRetryMs: Long?): Long {
        val exponential = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)
        val floor = maxOf(exponential, serverRetryMs ?: 0L)
        return retryAfterMs?.coerceIn(floor, MAX_RETRY_AFTER_MS) ?: floor
    }

    /**
     * `Retry-After` in either form RFC 9110 allows, from the response itself: the shared
     * [Throwable.retryAfterMillis] only reads Retrofit's `HttpException`, and a rejected stream request never
     * becomes one. A rate limit that names a time was previously answered a second later, three more times.
     */
    private fun Response.retryAfterMillis(): Long? {
        val header = header("Retry-After")?.trim()?.ifEmpty { null } ?: return null
        header.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0L) }
        val at = runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull() ?: return null
        return (at - now()).coerceAtLeast(0L)
    }

    private companion object {
        /**
         * Short: a blip is ridden out here (1 + 2 + 4 + 8 s), anything longer is the caller's decision, which it
         * takes with the run record in hand rather than blindly reconnecting to a run that may have finished.
         */
        const val MAX_ATTEMPTS = 4

        /** A `Retry-After` beyond this is honoured only this far; the caller decides what to do about the rest. */
        const val MAX_RETRY_AFTER_MS = 60_000L
    }
}
