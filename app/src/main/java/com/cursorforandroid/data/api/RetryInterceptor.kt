package com.cursorforandroid.data.api

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom

/**
 * Retries idempotent requests (GET / HEAD) that fail transiently — a dropped connection, a `429` or a `5xx` — with
 * jittered exponential backoff, honouring `Retry-After` when the server sends one — a wait longer than a call sleeps
 * here is handed back to the caller with the response, and held by the host's other reads. Anything else (a POST, a `4xx`,
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
    /**
     * Until when each host asked every call to wait: a `429` on one call pauses the others before they go out, rather
     * than each of them meeting the same refusal and backing off on its own. Shared with the run streams (see
     * [SseRunStreamer]), which wait it out themselves.
     */
    private val pauses: HostPause = HostPause(now),
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Accept") == "text/event-stream") return chain.proceed(request)
        val host = request.url.host
        val held = pauses.remainingMs(host)
        // A read the host asked to wait longer than a call sleeps here hears the refusal at once, with the wait left,
        // rather than going out into the window or holding an OkHttp thread for it. A write keeps its bounded hold and
        // goes out once, as ever.
        if (held > MAX_RETRY_AFTER_MS && request.isIdempotent()) return refused(request, held)
        if (held > 0) sleeper(held.coerceAtMost(MAX_RETRY_AFTER_MS)) { chain.call().isCanceled() }
        if (!request.isIdempotent()) return chain.proceed(request).also { if (it.code == 429) pauses.pause(host, it.retryAfterMs() ?: baseDelayMs) }
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
            // A refusal pauses the other calls of this client for what the server asked; this call's own backoff
            // below covers the same wait, so it does not hold twice.
            if (response.code == 429) pauses.pause(host, response.retryAfterMs() ?: baseDelayMs)
            if (attempt >= maxAttempts || !response.isTransientFailure() || chain.call().isCanceled()) return response
            val retryAfter = response.retryAfterMs()
            // Asked to wait longer than a call sleeps here: another try inside the window would only be refused again.
            // The caller hears the wait (`retryAfterMillis`), and the pause holds this client's other reads meanwhile.
            if (retryAfter != null && retryAfter > MAX_RETRY_AFTER_MS) return response
            response.close()
            wait(chain, backoff(attempt, retryAfter))
            attempt++
        }
    }

    /** The refusal the host would give a read inside its pause, said without asking it: a `429` naming the wait left. */
    private fun refused(request: Request, waitMs: Long): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(429)
        .message("Too Many Requests")
        .header("Retry-After", ((waitMs + 999) / 1000).toString())
        .body("".toResponseBody(null))
        .build()

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
        /** The longest a call sleeps in here, for a pause or a `Retry-After`; a longer wait is the caller's. */
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
