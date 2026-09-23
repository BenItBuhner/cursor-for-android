package com.cursorforandroid.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * A failed Connect call: the HTTP status, the protocol's `code` (`unauthenticated`, `permission_denied`, …) when the
 * body carried one, and the most readable explanation the body offered.
 */
class ConnectRpcException(
    val httpCode: Int,
    val code: String?,
    message: String,
    /** How long the server asked us to wait (`Retry-After`), on a 429 or a 503 that named one; null otherwise. */
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null,
    /**
     * The request path the call was made on, as sent — `/<package>.<Service>/<Method>` — so a refusal can be read
     * beside exactly what was asked (a casing, a service, a method the server no longer routes), on the screen and in
     * the diagnostics. Null for a failure before any request was built.
     */
    val path: String? = null,
) : IOException(message, cause) {
    val isUnauthenticated: Boolean get() = httpCode == 401 || code == "unauthenticated"
    val isRateLimited: Boolean get() = httpCode == 429 || code == "resource_exhausted"
    /** The server answered and this build could not read the answer (see [UNREADABLE_ANSWER]): nothing the server said, and nothing a retry changes. */
    val isUnreadableAnswer: Boolean get() = code == UNREADABLE_ANSWER

    companion object {
        /** The [code] of an answer this build could not decode — a shape it does not know, not a refusal of the server's. */
        const val UNREADABLE_ANSWER = "unreadable_answer"
    }
}

/**
 * The Connect protocol (connectrpc.com) in its JSON encoding, which is how the app reaches the account-level
 * `aiserver.v1` services on api2 — the same handshake `@cursor/sdk` performs. A unary call is one `POST` to
 * `/<package>.<Service>/<Method>` with a JSON body in proto3's JSON mapping (lowerCamelCase fields, int64 as decimal
 * strings) and `Connect-Protocol-Version: 1`; no protobuf runtime is needed.
 */
object ConnectRpc {
    private val JSON = "application/json".toMediaType()

    /** A bare `application/json` body; the String overload would append a charset parameter. */
    fun jsonBody(json: String): RequestBody = json.toByteArray(Charsets.UTF_8).toRequestBody(JSON)

    /** The path of a Connect call as connect-es builds it: `/` + the service's `typeName` + `/` + the method's `name` (PascalCase, never the lowerCamel `localName`). */
    fun path(service: String, method: String): String = "/$service/$method"

    fun request(baseUrl: String, service: String, method: String, accessToken: String, json: String): Request =
        Request.Builder()
            .url("${baseUrl.trimEnd('/')}${path(service, method)}")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Connect-Protocol-Version", "1")
            .post(jsonBody(json))
            .build()

    // ---- streaming ----------------------------------------------------------------------------------------------

    private val CONNECT_JSON = "application/connect+json".toMediaType()

    /**
     * A server-streaming Connect call over HTTP (connectrpc.com/docs/protocol, "Streaming RPCs"): the same path as a
     * unary call, `Content-Type: application/connect+json`, and the one request message enveloped — a flags byte
     * (0), a four-byte big-endian length, the JSON — as every message of the response is. No compression is
     * offered (`Connect-Accept-Encoding` left out), so the frames come as they are.
     */
    fun streamRequest(baseUrl: String, service: String, method: String, accessToken: String, json: String): Request =
        Request.Builder()
            .url("${baseUrl.trimEnd('/')}${path(service, method)}")
            .header("Authorization", "Bearer $accessToken")
            .header("Connect-Protocol-Version", "1")
            .post(envelope(0, json.toByteArray(Charsets.UTF_8)).toRequestBody(CONNECT_JSON))
            .build()

    /** One enveloped message: [flags], the payload's length as four big-endian bytes, the payload. */
    fun envelope(flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = flags.toByte()
        val n = payload.size
        out[1] = (n ushr 24).toByte(); out[2] = (n ushr 16).toByte(); out[3] = (n ushr 8).toByte(); out[4] = n.toByte()
        payload.copyInto(out, 5)
        return out
    }

    /** One frame of a Connect stream as read off the wire. */
    class Frame(val flags: Int, val data: ByteArray) {
        /** The end-of-stream frame: `{ error?, metadata? }` rather than a message (bit 0b10). */
        val isEndStream: Boolean get() = flags and END_STREAM != 0
        /** Compressed with the encoding negotiated (bit 0b01); never expected, none having been offered. */
        val isCompressed: Boolean get() = flags and COMPRESSED != 0
    }

    /** The next frame of [source], or null at a clean end of the body. Throws [ConnectRpcException] for a frame cut short. */
    fun readFrame(source: okio.BufferedSource, path: String, httpCode: Int): Frame? {
        if (source.exhausted()) return null
        val flags = source.readByte().toInt() and 0xFF
        val length = source.readInt()
        if (length < 0 || length > MAX_FRAME_BYTES) throw ConnectRpcException(httpCode, ConnectRpcException.UNREADABLE_ANSWER, "A frame of $path declared $length bytes.", path = path)
        val data = try { source.readByteArray(length.toLong()) } catch (e: java.io.EOFException) { throw ConnectRpcException(httpCode, ConnectRpcException.UNREADABLE_ANSWER, "A frame of $path ended after fewer than its $length bytes.", cause = e, path = path) }
        return Frame(flags, data)
    }

    /** The end-of-stream frame's error, as the exception to throw, or null when the stream ended well. */
    fun endStreamError(frame: Frame, path: String, httpCode: Int): ConnectRpcException? {
        val text = String(frame.data, Charsets.UTF_8)
        val error = parse(text)?.get("error") as? JsonObject ?: return null
        val body = error.toString()
        return ConnectRpcException(httpCode, errorCode(body), errorReason(body) ?: "The stream ended in an error.", path = path)
    }

    const val END_STREAM = 0b10
    const val COMPRESSED = 0b01
    /** A frame larger than this is not a message this app reads (the largest, a page of blobs, is a few megabytes). */
    const val MAX_FRAME_BYTES = 64 * 1024 * 1024

    /** The protocol's `code` field of an error body, if the body is one. */
    fun errorCode(body: String): String? = parse(body)?.string("code")

    /**
     * A Connect error is `{ code, message, details[] }`; Cursor's `message` is often just "Error", and the readable
     * explanation sits in `details[].debug.details.detail`.
     */
    fun errorReason(body: String): String? {
        val root = parse(body) ?: return null
        val debugDetails = (root["details"] as? JsonArray)
            ?.firstNotNullOfOrNull { ((it as? JsonObject)?.get("debug") as? JsonObject)?.get("details") as? JsonObject }
        val detail = debugDetails?.string("detail") ?: debugDetails?.string("title")
        val message = root.string("message")?.takeIf { it.isNotBlank() && !it.equals("Error", ignoreCase = true) }
        return detail ?: message ?: root.string("code")
    }

    private fun parse(body: String): JsonObject? = runCatching { CursorJson.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

/**
 * What the account service will bear from one client: a few calls in flight at once, and a pause it names. Shared by
 * every Connect call of the process ([ConnectJsonClient]), so a refresh with many passes — the list, the discovery
 * pages, each Project's memberships, the records by id, the pull request badges — never has more than [maxInFlight]
 * on the wire together, whoever asked. A `429` (`resource_exhausted`) pauses every caller for the `Retry-After` it
 * carries (a moment when it carries none, never longer than [MAX_PAUSE_MS]) and the refused call is made once more
 * after the pause: a 429 is a request the server did not take, so making it again duplicates nothing. A second
 * refusal is the caller's to hear, with the wait the server asked for.
 */
class ApiThrottle(
    maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    private val now: () -> Long = System::currentTimeMillis,
    blobsInFlight: Int = BLOBS_IN_FLIGHT,
) {
    /**
     * Where a call waits for its permit: [CONTROL], the account's lists, queues and writes, a few at a time; [BLOBS],
     * the record's blobs (`GetBlobForAgentKV`) — a few hundred bytes each, content-addressed, read by the dozen when a
     * long chat opens, as Cursor's own client reads them — in a lane of their own, so a chat's turns are not read three
     * at a time behind the sidebar's refresh. Both lanes wait out the same pause.
     */
    enum class Lane { CONTROL, BLOBS }

    private val permits = Semaphore(maxInFlight)
    private val blobPermits = Semaphore(blobsInFlight)
    @Volatile private var pausedUntilMillis = 0L
    private val refusals = java.util.concurrent.atomic.AtomicInteger()

    /** Refusals (429) heard so far, for the diagnostics. */
    val refusalCount: Int get() = refusals.get()

    /** Until when every call waits, or null when none does. */
    fun pausedUntil(): Long? = pausedUntilMillis.takeIf { it > now() }

    /** The server asked for a pause: every call from here on waits it out first. */
    fun pause(millis: Long) {
        val until = now() + millis.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
        if (until > pausedUntilMillis) pausedUntilMillis = until
    }

    /**
     * [block] under a permit, once the pause (if any) has passed; a 429 pauses every caller and is retried once —
     * unless [retryRefusals] is off: a caller with another way to what it asked for (the transcript, which the
     * documented endpoints can also give) hears the refusal at once rather than waiting the pause out for a second
     * try, and the pause still stands for everyone.
     */
    suspend fun <T> call(retryRefusals: Boolean = true, lane: Lane = Lane.CONTROL, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            val wait = pausedUntilMillis - now()
            if (wait > 0) delay(wait)
            try {
                return (if (lane == Lane.BLOBS) blobPermits else permits).withPermit { block() }
            } catch (e: ConnectRpcException) {
                if (!e.isRateLimited) throw e
                refusals.incrementAndGet()
                pause(e.retryAfterMillis ?: DEFAULT_PAUSE_MS)
                if (!retryRefusals || attempt >= MAX_ATTEMPTS) throw e
            }
        }
    }

    companion object {
        /** Calls on the wire at once: enough to overlap round trips, few enough that the account service does not refuse them. */
        const val DEFAULT_MAX_IN_FLIGHT = 3
        /** Blob reads on the wire at once (see [Lane.BLOBS]). */
        const val BLOBS_IN_FLIGHT = 8
        const val DEFAULT_PAUSE_MS = 1_500L
        const val MIN_PAUSE_MS = 250L
        const val MAX_PAUSE_MS = 15_000L
        private const val MAX_ATTEMPTS = 2
    }
}

/**
 * Unary Connect calls against one base URL, with the caller's serializers for the request and response messages —
 * every one of them through [throttle].
 */
class ConnectJsonClient(private val client: OkHttpClient, private val baseUrl: String, val throttle: ApiThrottle = ApiThrottle()) {

    suspend fun <I, O> unary(
        service: String,
        method: String,
        accessToken: String,
        body: I,
        requestSerializer: KSerializer<I>,
        responseSerializer: KSerializer<O>,
        /** Whether a rate limit is waited out and the call made once more (see [ApiThrottle.call]); off for a call with a fallback. */
        retryRefusals: Boolean = true,
        /** Which of the throttle's lanes the call waits in (see [ApiThrottle.Lane]). */
        lane: ApiThrottle.Lane = ApiThrottle.Lane.CONTROL,
    ): O = throttle.call(retryRefusals, lane) {
        withContext(Dispatchers.IO) {
            val json = CursorJson.encodeToString(requestSerializer, body)
            val path = ConnectRpc.path(service, method)
            client.newCall(ConnectRpc.request(baseUrl, service, method, accessToken, json)).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ConnectRpcException(
                        httpCode = response.code,
                        code = ConnectRpc.errorCode(text),
                        message = ConnectRpc.errorReason(text) ?: "HTTP ${response.code}",
                        retryAfterMillis = response.retryAfterMillis(),
                        path = path,
                    )
                }
                try {
                    CursorJson.decodeFromString(responseSerializer, text.ifBlank { "{}" })
                } catch (e: SerializationException) {
                    // The server answered; this build could not read the answer. Said as that — the call and the
                    // reason — rather than as the serializer's own words about a field, which is what a composer
                    // showed when the account's environment list carried a shape the DTO refused.
                    throw ConnectRpcException(response.code, ConnectRpcException.UNREADABLE_ANSWER, "Cursor's answer to $method could not be read: ${e.message ?: e.javaClass.simpleName}", cause = e, path = path)
                } catch (e: IllegalArgumentException) {
                    throw ConnectRpcException(response.code, ConnectRpcException.UNREADABLE_ANSWER, "Cursor's answer to $method could not be read: ${e.message ?: e.javaClass.simpleName}", cause = e, path = path)
                }
            }
        }
    }

    /**
     * A server-streaming Connect call (see [ConnectRpc.streamRequest]): [onMessage] is given each message of the
     * stream as JSON and says whether to read on; false closes the connection — the caller has what it came for —
     * without waiting for the server's end. A refusal before the stream (a status other than 200, a Connect error
     * body) and an error in the end-of-stream frame are thrown as [ConnectRpcException], the request path on them.
     * Returns how many messages [onMessage] was given.
     */
    suspend fun <I> serverStream(
        service: String,
        method: String,
        accessToken: String,
        body: I,
        requestSerializer: KSerializer<I>,
        retryRefusals: Boolean = false,
        onMessage: (JsonObject) -> Boolean,
    ): Int = throttle.call(retryRefusals) {
        withContext(Dispatchers.IO) {
            val json = CursorJson.encodeToString(requestSerializer, body)
            val path = ConnectRpc.path(service, method)
            client.newCall(ConnectRpc.streamRequest(baseUrl, service, method, accessToken, json)).await().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    throw ConnectRpcException(
                        httpCode = response.code,
                        code = ConnectRpc.errorCode(text),
                        message = ConnectRpc.errorReason(text) ?: "HTTP ${response.code}",
                        retryAfterMillis = response.retryAfterMillis(),
                        path = path,
                    )
                }
                val responseBody = response.body ?: return@use 0
                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.startsWith("application/connect+json")) {
                    // A unary-shaped answer to a streaming call: an error body the server wrote plainly, or a shape this build does not read.
                    val text = responseBody.string()
                    val code = ConnectRpc.errorCode(text)
                    throw ConnectRpcException(response.code, code ?: ConnectRpcException.UNREADABLE_ANSWER, ConnectRpc.errorReason(text) ?: "Cursor answered $path with '$contentType', not a Connect stream.", path = path)
                }
                val source = responseBody.source()
                var messages = 0
                while (true) {
                    val frame = ConnectRpc.readFrame(source, path, response.code) ?: break
                    if (frame.isEndStream) {
                        ConnectRpc.endStreamError(frame, path, response.code)?.let { throw it }
                        break
                    }
                    if (frame.isCompressed) throw ConnectRpcException(response.code, ConnectRpcException.UNREADABLE_ANSWER, "A frame of $path came compressed, which was not offered.", path = path)
                    val message = try {
                        CursorJson.parseToJsonElement(String(frame.data, Charsets.UTF_8)).jsonObject
                    } catch (e: Exception) {
                        throw ConnectRpcException(response.code, ConnectRpcException.UNREADABLE_ANSWER, "A message of $path could not be read: ${e.message ?: e.javaClass.simpleName}", cause = e, path = path)
                    }
                    messages++
                    if (!onMessage(message)) break
                }
                messages
            }
        }
    }

    /** `Retry-After` in either form RFC 9110 allows: delta-seconds, or an HTTP-date. */
    private fun okhttp3.Response.retryAfterMillis(): Long? {
        val header = header("Retry-After")?.trim()?.ifEmpty { null } ?: return null
        header.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0L) }
        val at = runCatching { java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return null
        return (at - System.currentTimeMillis()).coerceAtLeast(0L)
    }
}
