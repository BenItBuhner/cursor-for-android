package com.cursorforandroid.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.ThreadLocalRandom

/**
 * Retries idempotent requests (GET / HEAD) that fail transiently — a dropped connection, a `429` or a `5xx` — with
 * jittered exponential backoff, honouring `Retry-After` when the server sends one. Anything else (a POST, a `4xx`,
 * being offline, a cancelled call, or an event stream) goes straight through, so a retry can never duplicate a
 * launch or stall a UI that is waiting for a definite answer. Bounded to a few short attempts: the point is to
 * ride out a blip, not to hide an outage.
 */
class RetryInterceptor(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 400L,
    private val maxDelayMs: Long = 4_000L,
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isIdempotent() || request.header("Accept") == "text/event-stream") return chain.proceed(request)
        var attempt = 1
        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (attempt >= maxAttempts || !e.isTransient() || chain.call().isCanceled()) throw e
                sleeper(backoff(attempt, retryAfterMs = null))
                attempt++
                continue
            }
            if (attempt >= maxAttempts || !response.isTransientFailure() || chain.call().isCanceled()) return response
            val retryAfter = response.retryAfterMs()
            response.close()
            sleeper(backoff(attempt, retryAfter))
            attempt++
        }
    }

    private fun backoff(attempt: Int, retryAfterMs: Long?): Long {
        val exponential = (baseDelayMs shl (attempt - 1)).coerceAtMost(maxDelayMs)
        val jittered = (exponential * (0.5 + random() * 0.5)).toLong()
        return retryAfterMs?.coerceIn(0L, MAX_RETRY_AFTER_MS)?.coerceAtLeast(jittered) ?: jittered
    }

    private fun okhttp3.Request.isIdempotent() = method == "GET" || method == "HEAD"

    private fun Response.isTransientFailure() = code == 429 || code == 408 || code in 500..599

    /** Connection resets and timeouts are worth a second try; an unknown host means we are offline, which is not. */
    private fun IOException.isTransient() = this !is UnknownHostException &&
        !(this is InterruptedIOException && message == "Canceled")

    private fun Response.retryAfterMs(): Long? = header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000 }

    private companion object {
        const val MAX_RETRY_AFTER_MS = 10_000L
    }
}
