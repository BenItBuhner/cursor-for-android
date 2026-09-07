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
 */
fun Throwable.toCursorError(): CursorApiException? {
    if (this is CursorApiException) return this
    val http = this as? HttpException ?: return null
    val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    val parsed = body?.let { runCatching { CursorJson.decodeFromString(ApiErrorBodyDto.serializer(), it) }.getOrNull() }?.error
    return CursorApiException(
        httpCode = http.code(),
        code = parsed?.code ?: "http_${http.code()}",
        message = parsed?.message?.ifBlank { null } ?: http.message().ifBlank { "Request failed (${http.code()})" },
        helpUrl = parsed?.helpUrl,
    )
}

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
        .addInterceptor(AuthInterceptor(apiKeyProvider))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    /** SSE connections need no read timeout — heartbeats keep the socket alive indefinitely. */
    fun sseClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

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

    fun retrofit(client: OkHttpClient, baseUrl: String = CursorEndpoints.BASE_URL): CursorApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(CursorJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CursorApi::class.java)
}
