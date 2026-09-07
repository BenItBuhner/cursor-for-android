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
import okio.BufferedSource
import java.io.IOException

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

/** A raw SSE frame: `event:`, `id:` and joined `data:` lines. */
data class SseFrame(val event: String, val id: String?, val data: String)

/** Minimal, spec-compliant Server-Sent Events parser over an okio [BufferedSource]. */
object SseParser {
    fun readFrame(source: BufferedSource): SseFrame? {
        var event = "message"
        var id: String? = null
        val data = StringBuilder()
        var sawField = false
        while (true) {
            val line = source.readUtf8Line() ?: return if (sawField) SseFrame(event, id, data.toString().removeSuffix("\n")) else null
            if (line.isEmpty()) {
                if (sawField) return SseFrame(event, id, data.toString().removeSuffix("\n"))
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
                "data" -> data.append(value).append('\n')
                "id" -> id = value
            }
        }
    }

    fun toEvent(frame: SseFrame): RunStreamEvent? {
        val json = CursorJson
        return runCatching {
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
                else -> null // interaction_update and unknown events are intentionally ignored
            }
        }.getOrNull()
    }
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
) : RunStreamer {

    override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> = flow {
        var lastId = lastEventId
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val outcome = connectOnce(agentId, runId, lastId) { frame ->
                frame.id?.let { lastId = it }
                SseParser.toEvent(frame)?.takeUnless { it is RunStreamEvent.Error }?.let { emit(it) }
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
                    if (attempt > maxAttempts) {
                        emit(RunStreamEvent.Error("stream_unavailable", outcome.reason, resumeFrom = lastId))
                        return@flow
                    }
                    delay(backoffMillis(attempt))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private sealed interface Outcome {
        /** The run's `result` (and `done`) came through. */
        data object Terminal : Outcome
        /** Transport trouble; worth another connection right away. */
        data class Retry(val reason: String) : Outcome
        /** The server ended the stream on purpose: an in-band `error` frame, a `410`, a `4xx`. */
        data class Stopped(val code: String, val message: String) : Outcome
        /** `400 invalid_last_event_id`: only a connection without a `Last-Event-ID` can help. */
        data class Restart(val message: String) : Outcome
    }

    private suspend fun FlowCollector<RunStreamEvent>.connectOnce(
        agentId: String,
        runId: String,
        lastId: String?,
        onFrame: suspend FlowCollector<RunStreamEvent>.(SseFrame) -> Unit,
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
                        408, 429, in 500..599 -> Outcome.Retry("HTTP ${resp.code}")
                        else -> Outcome.Stopped(error?.code ?: "http_${resp.code}", error?.message?.ifBlank { null } ?: "Stream request failed (${resp.code})")
                    }
                }
                val source = resp.body?.source() ?: return Outcome.Retry("empty body")
                var sawResult = false
                try {
                    while (true) {
                        val frame = SseParser.readFrame(source) ?: break
                        onFrame(frame)
                        when (frame.event) {
                            // The server's word on why it is stopping. Not part of the run: the caller checks the
                            // run record and, unless the log is gone for good, comes back with `Last-Event-ID`.
                            "error" -> {
                                val error = SseParser.toEvent(frame) as? RunStreamEvent.Error
                                return Outcome.Stopped(error?.code ?: "stream_error", error?.message?.ifBlank { null } ?: "The run's stream reported an error.")
                            }
                            "result" -> sawResult = true
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

    private fun backoffMillis(attempt: Int): Long = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)

    private companion object {
        /**
         * Short: a blip is ridden out here (1 + 2 + 4 + 8 s), anything longer is the caller's decision, which it
         * takes with the run record in hand rather than blindly reconnecting to a run that may have finished.
         */
        const val MAX_ATTEMPTS = 4
    }
}
