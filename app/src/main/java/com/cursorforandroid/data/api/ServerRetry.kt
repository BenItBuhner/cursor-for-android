package com.cursorforandroid.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.random.Random

/**
 * The account service's failures that are its own, or the network's, rather than an answer about what was asked: a
 * 5xx — among them the bare `502 Bad Gateway` Cursor's load balancer returns when the backend behind it failed or
 * reset the connection, which carries no Connect body — a `408`, a Connect `unavailable` / `deadline_exceeded` /
 * `internal` / `unknown` / `aborted`, or the connection itself dropping or timing out. Worth asking again a moment
 * later: none of them says anything about the request. A rate limit is not among them: the shared throttle pauses
 * every caller for it (see [ApiThrottle]).
 */
object ServerRetry {

    /** Whether [t] is a failure a later attempt may not meet (see [ServerRetry]). */
    fun isTransient(t: Throwable?): Boolean = when (t) {
        null, is CancellationException -> false
        is ConnectRpcException -> !t.isUnreadableAnswer && !t.isRateLimited && (t.httpCode in 500..599 || t.httpCode == 408 || t.code in TRANSIENT_CODES)
        is IOException -> true
        else -> false
    }

    /**
     * [block], asked again after each transient failure (see [isTransient]) up to [delaysMs]'s length more times: the
     * next delay, with a quarter of jitter either way, or the `Retry-After` the answer named when that is longer,
     * never more than [MAX_WAIT_MS]. [onRetry] hears each failure it waits out, with the wait. The last failure — or
     * any other — is thrown as it came.
     */
    suspend fun <T> withRetries(delaysMs: List<Long> = DELAYS_MS, onRetry: (attempt: Int, failure: Throwable, waitMs: Long) -> Unit = { _, _, _ -> }, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (t: Throwable) {
                if (!isTransient(t) || attempt >= delaysMs.size) throw t
                val base = delaysMs[attempt]
                val jittered = base + (Random.nextDouble(-0.25, 0.25) * base).toLong()
                val wait = maxOf(jittered, (t as? ConnectRpcException)?.retryAfterMillis ?: 0L).coerceAtMost(MAX_WAIT_MS)
                attempt++
                onRetry(attempt, t, wait)
                delay(wait)
            }
        }
    }

    /** The waits between attempts: four more after the first, seven and a half seconds all told before a piece is left for later. */
    val DELAYS_MS = listOf(500L, 1_000L, 2_000L, 4_000L)

    /**
     * The waits a reader of the record takes between attempts (see [withRetries]): [pieces] for a blob read behind
     * the screen, [onScreen] for one the screen is waiting on — a single quick retry, the piece then left for the
     * reads behind it — and [state] for the conversation state, which the first paint waits on. [passes] are the
     * pauses before the pieces still failing after all that are read again, a few times, before they are left for
     * the reader's Retry (see `ConversationRepository.runBlobWork`).
     */
    data class Waits(
        val pieces: List<Long> = DELAYS_MS,
        val onScreen: List<Long> = listOf(500L),
        val state: List<Long> = listOf(500L, 1_000L, 2_000L),
        val passes: List<Long> = listOf(3_000L, 10_000L, 30_000L),
    )

    /** The longest one wait is, whatever `Retry-After` asks. */
    const val MAX_WAIT_MS = 30_000L

    /** Connect codes that say the server failed or is overloaded, not that the request was wrong. */
    private val TRANSIENT_CODES = setOf("unavailable", "deadline_exceeded", "internal", "unknown", "aborted")
}
