package com.cursorforandroid.data.api

import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Until when each endpoint asked this app to hold its calls: the `Retry-After` of a `429`, heard by whichever client
 * met it — the REST calls' [RetryInterceptor], the run streams' [SseRunStreamer], the Connect calls' [ApiThrottle] —
 * and waited out by every call to the same endpoint, so a stream does not reconnect into a window a stream was
 * refused in, nor a read go out into one its endpoint was refused in. Cursor scopes its limits to the endpoint
 * ("most are scoped to a single endpoint", cursor.com/docs/api): `GET /v1/repositories` allows one a minute, and its
 * refusal held every call to api.cursor.com for that minute — a new Project's chat opened inside it and heard
 * "Rate limited" for reads Cursor had never been asked (v0.4.31). The REST clients key their pauses by [endpoint];
 * the account service's [ApiThrottle] keeps one for its whole host. A later refusal can only lengthen a pause, and
 * none holds longer than [MAX_MS].
 */
class HostPause(private val now: () -> Long = System::currentTimeMillis) {

    private val until = ConcurrentHashMap<String, Long>()

    /** Holds [scope] — a host, or an [endpoint] of one — for [millis]. */
    fun pause(scope: String, millis: Long) {
        until.merge(scope, now() + millis.coerceIn(0L, MAX_MS), ::maxOf)
    }

    /** Until when [scope]'s calls wait, or null when none does. */
    fun until(scope: String): Long? = until[scope]?.takeIf { it > now() }

    fun remainingMs(scope: String): Long = until[scope]?.let { (it - now()).coerceAtLeast(0L) } ?: 0L

    companion object {
        const val MAX_MS = 60_000L

        /** Path segments after which the API's path names one resource: `/v1/agents/{id}`, `/v1/agents/{id}/runs/{runId}`. */
        private val COLLECTIONS = setOf("agents", "runs")

        /**
         * The endpoint [url] is a call to, as a pause is kept for it: its host, [method] and path with each resource's
         * id stood in for by `*`, so every chat's run list is one endpoint and the repositories' list another.
         */
        fun endpoint(method: String, url: HttpUrl): String {
            val segments = url.pathSegments
            val path = segments.mapIndexed { i, segment -> if (i > 0 && segments[i - 1] in COLLECTIONS && segment.isNotEmpty()) "*" else segment }
            return "${url.host} ${method.uppercase()} /${path.joinToString("/")}"
        }
    }
}
