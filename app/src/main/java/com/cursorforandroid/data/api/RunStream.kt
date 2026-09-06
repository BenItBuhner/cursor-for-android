package com.cursorforandroid.data.api

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
    data class Error(val code: String, val message: String) : RunStreamEvent
    data object Done : RunStreamEvent
}

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
 * Streams a run over SSE, reconnecting with `Last-Event-ID` after transient failures until the run reports a
 * terminal `result`/`done`, the stream expires (410) or the collector cancels.
 */
class SseRunStreamer(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String?,
    private val urlFor: (agentId: String, runId: String) -> String = CursorEndpoints::streamUrl,
) : RunStreamer {

    override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> = flow {
        var lastId = lastEventId
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val outcome = connectOnce(agentId, runId, lastId) { frame ->
                frame.id?.let { lastId = it }
                SseParser.toEvent(frame)?.let { emit(it) }
            }
            when (outcome) {
                is Outcome.Terminal -> return@flow
                is Outcome.Fatal -> {
                    emit(RunStreamEvent.Error(outcome.code, outcome.message))
                    return@flow
                }
                is Outcome.Retry -> {
                    attempt++
                    if (attempt > MAX_ATTEMPTS) {
                        emit(RunStreamEvent.Error("stream_unavailable", outcome.reason))
                        return@flow
                    }
                    delay(backoffMillis(attempt))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private sealed interface Outcome {
        data object Terminal : Outcome
        data class Retry(val reason: String) : Outcome
        data class Fatal(val code: String, val message: String) : Outcome
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
                    return when (resp.code) {
                        410 -> Outcome.Fatal("stream_expired", "This run's live stream has expired.")
                        401, 403 -> Outcome.Fatal("unauthorized", "Not authorized to stream this run.")
                        404 -> Outcome.Fatal("run_not_found", "Run not found.")
                        429, in 500..599 -> Outcome.Retry("HTTP ${resp.code}")
                        else -> Outcome.Fatal("http_${resp.code}", body.ifBlank { "Stream request failed (${resp.code})" })
                    }
                }
                val source = resp.body?.source() ?: return Outcome.Retry("empty body")
                var terminal = false
                try {
                    while (true) {
                        val frame = SseParser.readFrame(source) ?: break
                        onFrame(frame)
                        if (frame.event == "result") terminal = true
                        if (frame.event == "done") {
                            terminal = true
                            break
                        }
                    }
                } catch (e: IOException) {
                    if (!currentCoroutineContext().isActive) return Outcome.Terminal
                    if (!terminal) return Outcome.Retry(e.message ?: "stream interrupted")
                }
                return if (terminal) Outcome.Terminal else Outcome.Retry("stream closed before result")
            }
        } finally {
            handle?.dispose()
        }
    }

    private fun backoffMillis(attempt: Int): Long = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
