package com.cursorforandroid.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
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
) : IOException(message) {
    val isUnauthenticated: Boolean get() = httpCode == 401 || code == "unauthenticated"
    val isRateLimited: Boolean get() = httpCode == 429 || code == "resource_exhausted"
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

    fun request(baseUrl: String, service: String, method: String, accessToken: String, json: String): Request =
        Request.Builder()
            .url("${baseUrl.trimEnd('/')}/$service/$method")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Connect-Protocol-Version", "1")
            .post(jsonBody(json))
            .build()

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
) {
    private val permits = Semaphore(maxInFlight)
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

    /** [block] under a permit, once the pause (if any) has passed; a 429 pauses and is retried once. */
    suspend fun <T> call(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            val wait = pausedUntilMillis - now()
            if (wait > 0) delay(wait)
            try {
                return permits.withPermit { block() }
            } catch (e: ConnectRpcException) {
                if (!e.isRateLimited) throw e
                refusals.incrementAndGet()
                pause(e.retryAfterMillis ?: DEFAULT_PAUSE_MS)
                if (attempt >= MAX_ATTEMPTS) throw e
            }
        }
    }

    companion object {
        /** Calls on the wire at once: enough to overlap round trips, few enough that the account service does not refuse them. */
        const val DEFAULT_MAX_IN_FLIGHT = 3
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
    ): O = throttle.call {
        withContext(Dispatchers.IO) {
            val json = CursorJson.encodeToString(requestSerializer, body)
            client.newCall(ConnectRpc.request(baseUrl, service, method, accessToken, json)).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ConnectRpcException(
                        httpCode = response.code,
                        code = ConnectRpc.errorCode(text),
                        message = ConnectRpc.errorReason(text) ?: "HTTP ${response.code}",
                        retryAfterMillis = response.retryAfterMillis(),
                    )
                }
                CursorJson.decodeFromString(responseSerializer, text.ifBlank { "{}" })
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
