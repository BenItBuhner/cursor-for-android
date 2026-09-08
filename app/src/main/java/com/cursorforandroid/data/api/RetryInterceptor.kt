package com.cursorforandroid.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
    private val now: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long, () -> Boolean) -> Unit = ::sleepInSlices,
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
                wait(chain, backoff(attempt, retryAfterMs = null))
                attempt++
                continue
            }
            if (attempt >= maxAttempts || !response.isTransientFailure() || chain.call().isCanceled()) return response
            val retryAfter = response.retryAfterMs()
            response.close()
            wait(chain, backoff(attempt, retryAfter))
            attempt++
        }
    }

    private fun wait(chain: Interceptor.Chain, delayMs: Long) = sleeper(delayMs) { chain.call().isCanceled() }

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

    /**
     * `Retry-After` in either form RFC 9110 allows. Read as delta-seconds only, an HTTP-date came back as null and
     * the client fell back to its own few hundred milliseconds — retrying almost at once against a rate limit that
     * had named a time, and spending two more requests of the budget doing it.
     */
    private fun Response.retryAfterMs(): Long? {
        val header = header("Retry-After")?.trim()?.ifEmpty { null } ?: return null
        header.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0L) }
        val at = runCatching {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (at - now()).coerceAtLeast(0L)
    }

    private companion object {
        const val MAX_RETRY_AFTER_MS = 10_000L
    }
}

/**
 * Taken in slices, because `Thread.sleep` inside an application interceptor is not interruptible: a cancelled call
 * gives its OkHttp dispatcher thread back within a slice instead of holding it for the whole backoff.
 */
private fun sleepInSlices(totalMs: Long, isCancelled: () -> Boolean) {
    var remaining = totalMs
    while (remaining > 0 && !isCancelled()) {
        val slice = remaining.coerceAtMost(SLEEP_SLICE_MS)
        Thread.sleep(slice)
        remaining -= slice
    }
}

private const val SLEEP_SLICE_MS = 250L
