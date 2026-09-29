package com.cursorforandroid.data.api

import java.util.concurrent.ConcurrentHashMap

/**
 * Until when each host asked this app to hold its calls: the `Retry-After` of a `429`, heard by whichever client met
 * it — the REST calls' [RetryInterceptor], the run streams' [SseRunStreamer], the Connect calls' [ApiThrottle] — and
 * waited out by all of them, so a stream does not reconnect into a window a GET was refused in, nor the other way
 * round. A later refusal can only lengthen the pause, and none holds longer than [MAX_MS].
 */
class HostPause(private val now: () -> Long = System::currentTimeMillis) {

    private val until = ConcurrentHashMap<String, Long>()

    fun pause(host: String, millis: Long) {
        until.merge(host, now() + millis.coerceIn(0L, MAX_MS), ::maxOf)
    }

    /** Until when [host]'s calls wait, or null when none does. */
    fun until(host: String): Long? = until[host]?.takeIf { it > now() }

    fun remainingMs(host: String): Long = until[host]?.let { (it - now()).coerceAtLeast(0L) } ?: 0L

    companion object {
        const val MAX_MS = 60_000L
    }
}
