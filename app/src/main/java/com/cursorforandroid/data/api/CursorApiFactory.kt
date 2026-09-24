package com.cursorforandroid.data.api

import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.api.dto.ApiErrorBodyDto
import com.cursorforandroid.data.auth.SessionUnavailableException
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

val CursorJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

/** Adds `Authorization: Bearer <key>` (the Cloud Agents API accepts Basic or Bearer). */
class AuthInterceptor(private val apiKeyProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = apiKeyProvider()
        val original = chain.request()
        val request = original.newBuilder()
            .header("User-Agent", "cursor-for-android/${BuildConfig.VERSION_NAME}")
            .apply {
                // The SSE client sets `Accept: text/event-stream` itself; never clobber an explicit Accept.
                if (original.header("Accept") == null) header("Accept", "application/json")
                if (original.header("Authorization") == null && !key.isNullOrBlank()) header("Authorization", "Bearer $key")
            }
            .build()
        return chain.proceed(request)
    }
}

class CursorApiException(
    val httpCode: Int,
    val code: String,
    override val message: String,
    val helpUrl: String? = null,
) : IOException(message) {
    val isUnauthorized: Boolean get() = httpCode == 401 || code == "unauthorized" || code == "api_key_not_found"
    val isRateLimited: Boolean get() = httpCode == 429
}

/**
 * Converts Retrofit's HttpException into the API's standardized `{ error: { code, message } }` shape. An exception
 * that already is one (the demo backend raises them directly) passes through.
 *
 * The same exception is looked at more than once on its way up — a repository decides by the code (the launch path
 * checks for `agent_id_conflict`, the follow-up queue for `agent_busy`), then a screen words it — so the body is
 * peeked at rather than read: Retrofit buffers it once, and `string()` would drain that buffer, leaving every later
 * look with an empty body and a code that is only the status (`http_409`), which is how a `409 agent_busy` stopped
 * being recognised as one.
 */
fun Throwable.toCursorError(): CursorApiException? {
    if (this is CursorApiException) return this
    val http = this as? HttpException ?: return null
    val body = runCatching {
        http.response()?.errorBody()?.let { it.source().peek().readString(it.contentType()?.charset() ?: Charsets.UTF_8) }
    }.getOrNull()
    val parsed = body?.let { runCatching { CursorJson.decodeFromString(ApiErrorBodyDto.serializer(), it) }.getOrNull() }?.error
    return CursorApiException(
        httpCode = http.code(),
        code = parsed?.code ?: "http_${http.code()}",
        message = parsed?.message?.ifBlank { null } ?: http.message().ifBlank { "Request failed (${http.code()})" },
        helpUrl = parsed?.helpUrl,
    )
}

/**
 * How long the server asked us to wait, from a `Retry-After` on a throttled response. Delta-seconds only — the HTTP-date
 * form would need the server's clock — and capped, so a header we cannot make sense of cannot wedge a picker shut.
 */
fun Throwable.retryAfterMillis(): Long? {
    val http = this as? HttpException ?: return null
    if (http.code() != 429 && http.code() != 503) return null
    val seconds = http.response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull() ?: return null
    return seconds.takeIf { it > 0 }?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)?.times(1000L)
}

private const val MAX_RETRY_AFTER_SECONDS = 15 * 60L

/**
 * True for a failure worth asking again about in a moment: the server slow or unreachable (a timeout, a dropped
 * connection), a `429`, a `5xx`, or a `409` about the agent's state other than it being busy — the moments right
 * after a run was cancelled, when the agent is between turns. Busy is a wait, not a retry, and is left to the caller;
 * so is anything that will not change by itself: a request the server found wrong, a rejected key, an agent that is
 * gone or archived, a spent usage limit, or being offline. A name lookup that failed on a phone that is online is a
 * resolver's moment, and passes.
 */
fun Throwable.isTransientFailure(): Boolean {
    toCursorError()?.let { e ->
        return when {
            e.isRateLimited || e.httpCode == 408 || e.httpCode in 500..599 -> true
            e.httpCode == 409 -> e.code !in SETTLED_CONFLICTS
            else -> false
        }
    }
    return this is IOException && (this !is java.net.UnknownHostException || DeviceNetwork.isOnline() == true)
}

/** `409` codes that describe a state another attempt will find unchanged, or one the caller handles in its own way. */
private val SETTLED_CONFLICTS = setOf("agent_busy", "agent_id_conflict", "agent_archived", "run_not_cancellable", "usage_limit_exceeded")

/**
 * True for a request that went out and got no answer this client could read: the server silent past the read
 * timeout, the connection reset or closed mid-reply, the call's overall budget spent. Not an answer the server gave
 * (an HTTP status, with or without the API's body), and not being offline — those say what happened; a lost reply
 * says nothing about whether the server acted on the request, which is the caller's to find out.
 */
fun Throwable.isLostReply(): Boolean = this is IOException && this !is java.net.UnknownHostException && toCursorError() == null

/**
 * True for a failure on the way to Cursor rather than an answer from it: a name lookup, a connection that would not
 * open or that dropped, a reply cut off or too slow — and a session that could not be started for one of those. Not
 * an answer the server gave (a Connect error, the API's error body), nor a session refused (the key, a policy).
 */
fun Throwable.isTransportFailure(): Boolean = when (this) {
    is ConnectRpcException, is CursorApiException -> false
    is SessionUnavailableException -> cause?.isTransportFailure() == true
    is IOException -> true
    else -> false
}

fun Throwable.userMessage(): String {
    toCursorError()?.let { e ->
        return when {
            e.isUnauthorized -> "That API key was rejected. Create one at cursor.com/dashboard/api."
            e.isRateLimited -> rateLimitedMessage(e)
            e.code == "agent_busy" -> "The agent is still working on the previous prompt."
            e.code == "agent_archived" -> "This agent is archived. Unarchive it to send a follow-up."
            e.code == "usage_limit_exceeded" -> "Your Cursor usage limit has been reached."
            else -> e.message
        }
    }
    // A session that could not be started for want of a connection is said as the connection's failure.
    if (this is SessionUnavailableException) cause?.takeIf { it.isTransportFailure() }?.let { return it.userMessage() }
    // Offline is the phone's own word (see DeviceNetwork), never a guess from the failure: a lookup the resolver did
    // not answer with Wi-Fi and cellular both up is not being offline. An answer from the server never is either.
    val online = if (isTransportFailure()) DeviceNetwork.isOnline() else null
    if (online == false) return OFFLINE
    return when (this) {
        is java.net.UnknownHostException -> if (online == true) LOOKUP_FAILED_ONLINE else LOOKUP_FAILED
        is java.net.SocketTimeoutException -> "Cursor took too long to respond."
        is java.net.ConnectException -> "Cursor couldn't be reached. Check your connection."
        is IOException -> transportWords() ?: message ?: "Something went wrong."
        else -> message ?: "Something went wrong."
    }
}

/**
 * A rate limit, framed by the app and carrying the server's own words and the wait it named, so the reader knows
 * both what happened and when to try again: "Rate limited by Cursor: Too many requests from this key. Try again in
 * 7 s." A body without words of its own — a bare `429` from a proxy — reads "Rate limited by Cursor. Try again in a moment."
 */
private fun Throwable.rateLimitedMessage(e: CursorApiException): String {
    val words = e.message.trim().takeIf { it.isNotBlank() && !it.startsWith("Request failed") && !it.equals("Too Many Requests", ignoreCase = true) }
    val wait = retryAfterMillis()?.let { "in ${((it + 999) / 1000).coerceAtLeast(1)} s" } ?: "in a moment"
    return if (words == null) "Rate limited by Cursor. Try again $wait." else "Rate limited by Cursor: ${words.trimEnd('.')}. Try again $wait."
}

/**
 * The connection's own failures, in plain words rather than OkHttp's: a socket reset or closed under the request, a
 * reply cut before its status line or its end, a handshake that failed, the call's budget spent. Null for an
 * [IOException] that carries a message of its own worth showing (an API error, a launch left unanswered).
 */
private fun IOException.transportWords(): String? = when (this) {
    is java.io.InterruptedIOException -> if (message == "timeout") "Cursor took too long to respond." else null
    is java.net.SocketException, is java.io.EOFException, is javax.net.ssl.SSLException, is okhttp3.internal.http2.StreamResetException -> CONNECTION_DROPPED
    else -> if (message?.startsWith("unexpected end of stream") == true || message == "Socket closed" || message == "Canceled") CONNECTION_DROPPED else null
}

/** Said of a request whose reply never came back whole: the connection went, not the server. */
const val CONNECTION_DROPPED = "The connection to Cursor dropped before it answered."

/** Said of any failure to reach Cursor while the phone has no network (see [DeviceNetwork]). */
const val OFFLINE = "You're offline. Check your connection."

/** Said of a failed name lookup of Cursor's host while the phone has a network. */
const val LOOKUP_FAILED_ONLINE = "Couldn't look up Cursor's server, though the phone is online. Try again in a moment."

/** Said of a failed name lookup of Cursor's host when the phone cannot tell whether it has a network. */
const val LOOKUP_FAILED = "Couldn't look up Cursor's server. Check your connection."

/**
 * Marks the body of every request that is not idempotent — a `POST`, a `DELETE`, anything but `GET` and `HEAD` —
 * as one-shot, so OkHttp never sends it a second time on its own. OkHttp's transparent recovery — the same request
 * again on a fresh connection when a pooled connection turns out dead, or the reply is cut before its status line —
 * is what a `GET` wants and what `POST /v1/agents/{id}/runs` must never get: the runs API takes no idempotency key,
 * so a follow-up whose reply was lost on a network handoff went out again on the retry — a second run when the
 * server had already taken the first, or `409 agent_busy`, which queued the message to be sent once more when that
 * run ended. Either way the agent heard it twice. Such a failure now reaches the repositories as the lost reply it
 * is (see [isLostReply]), and they ask the server whether the message arrived before anything goes out again.
 */
class OneShotWritesInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (body == null || request.method == "GET" || request.method == "HEAD" || body.isOneShot()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().method(request.method, OneShot(body)).build())
    }

    private class OneShot(private val delegate: okhttp3.RequestBody) : okhttp3.RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: okio.BufferedSink) = delegate.writeTo(sink)
        override fun isDuplex() = delegate.isDuplex()
        override fun isOneShot() = true
    }
}

object CursorApiFactory {

    fun okHttp(apiKeyProvider: () -> String?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Bounds the whole call, retries included, so nothing can hang a screen for longer than this. Generous on
        // purpose: `/v0/agents/{id}/conversation` has no paging and answers with every turn of a chat at once —
        // megabytes for a chat of hundreds of turns — which a slow connection delivers over minutes while the read
        // timeout above, sixty seconds of silence, still catches a connection that has died. At ninety seconds the
        // long chats' transcripts failed on every open, and the failure read as the chat not loading at all.
        .callTimeout(5, TimeUnit.MINUTES)
        .dns(LastGoodDns.CURSOR)
        .addInterceptor(AuthInterceptor(apiKeyProvider))
        // Writes go out once: a lost reply is reported, not resent behind the app's back (see the class).
        .addInterceptor(OneShotWritesInterceptor())
        .addInterceptor(RetryInterceptor())
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    /**
     * For the browser login's `/auth/poll` and the dashboard RPC that mints the key: no stored credential may ride
     * along, and nothing is logged on any build, not even on debug — the poll carries the verifier that redeems the
     * login, in its body, and in the query string of the GET an older backend falls back to, which even
     * [HttpLoggingInterceptor.Level.BASIC] would write to logcat as part of the request line.
     */
    fun loginClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .dns(LastGoodDns.CURSOR)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "cursor-for-android/${BuildConfig.VERSION_NAME}").build())
        }
        .build()

    /**
     * SSE connections have no call timeout — a run streams for as long as it takes. The read timeout is a heartbeat
     * watchdog: the server sends `heartbeat` frames on a quiet stream, so a socket that goes this long without a
     * byte is one the network dropped without telling us (a Wi-Fi to cellular handoff, Doze). Reading it fails with
     * an [java.io.IOException], which the streamer answers by resuming with `Last-Event-ID`; nothing is lost.
     */
    fun sseClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .readTimeout(SSE_SILENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private const val SSE_SILENCE_TIMEOUT_MS = 120_000L

    /**
     * For media bytes: artifact downloads are presigned S3 URLs, which reject a request that also carries an
     * `Authorization` header, and the API key must never travel to arbitrary image hosts anyway.
     */
    fun mediaClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    /**
     * For the GitHub releases API and the APK downloads it points at. Anonymous by design — the Cursor API key must
     * never leave for GitHub — and without a call timeout, since an APK download on a slow link takes what it takes;
     * the read timeout still catches a stalled connection, and GETs ride out blips through the retry interceptor.
     */
    fun updateClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "cursor-for-android/${BuildConfig.VERSION_NAME}").build())
        }
        .addInterceptor(RetryInterceptor())
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    /**
     * For the anonymous reads of GitHub's REST API that stand in for the account service when Extended mode is off
     * (pull request states, repository trees). No Cursor credential rides along, and no retry: GitHub's rate limit is
     * hourly and a `429` retried in seconds only spends more of it; the repositories behind these calls have their
     * own schedules.
     */
    /**
     * The client for a cloud agent's cursor-server (see [com.cursorforandroid.data.api.CursorServerApi]): a plain
     * client with no API-key interceptor — the request carries only the connection token and the headers the server
     * named — and bounded timeouts, since a picture off the machine should not hang a viewer.
     */
    fun cursorServerClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun gitHubClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "cursor-for-android/${BuildConfig.VERSION_NAME}").build())
        }
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    /**
     * For Origin's REST API (`api.cursor.com/v1/origin`), which takes a user access token rather than the API key:
     * the same bare client as GitHub's, so the stored key never rides along, and each call sets its own bearer.
     */
    fun originClient(): OkHttpClient = gitHubClient()

    fun retrofit(client: OkHttpClient, baseUrl: String = CursorEndpoints.BASE_URL): CursorApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(CursorJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CursorApi::class.java)
}
