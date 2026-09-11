package com.cursorforandroid.data.api

import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.api.dto.ApiErrorBodyDto
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
 * gone or archived, a spent usage limit, or being offline.
 */
fun Throwable.isTransientFailure(): Boolean {
    toCursorError()?.let { e ->
        return when {
            e.isRateLimited || e.httpCode == 408 || e.httpCode in 500..599 -> true
            e.httpCode == 409 -> e.code !in SETTLED_CONFLICTS
            else -> false
        }
    }
    return this is IOException && this !is java.net.UnknownHostException
}

/** `409` codes that describe a state another attempt will find unchanged, or one the caller handles in its own way. */
private val SETTLED_CONFLICTS = setOf("agent_busy", "agent_id_conflict", "agent_archived", "run_not_cancellable", "usage_limit_exceeded")

fun Throwable.userMessage(): String {
    toCursorError()?.let { e ->
        return when {
            e.isUnauthorized -> "That API key was rejected. Create one at cursor.com/dashboard/api."
            e.isRateLimited -> "Rate limited by Cursor. Try again in a moment."
            e.code == "agent_busy" -> "The agent is still working on the previous prompt."
            e.code == "agent_archived" -> "This agent is archived. Unarchive it to send a follow-up."
            e.code == "usage_limit_exceeded" -> "Your Cursor usage limit has been reached."
            else -> e.message
        }
    }
    return when (this) {
        is java.net.UnknownHostException -> "You're offline. Check your connection."
        is java.net.SocketTimeoutException -> "Cursor took too long to respond."
        else -> message ?: "Something went wrong."
    }
}

object CursorApiFactory {

    fun okHttp(apiKeyProvider: () -> String?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Bounds the whole call, retries included, so nothing can hang a screen for longer than this.
        .callTimeout(90, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(apiKeyProvider))
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

    fun retrofit(client: OkHttpClient, baseUrl: String = CursorEndpoints.BASE_URL): CursorApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(CursorJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CursorApi::class.java)
}
