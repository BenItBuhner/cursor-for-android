package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exchanges artifact paths for the presigned URLs `GET /v1/agents/{id}/artifacts/download` hands out. The URLs
 * live for 15 minutes, so they are cached per (agent, path) and re-fetched shortly before they expire; a loader
 * that still hits a 403 calls [invalidate] and asks again.
 *
 * The cache is bounded and drops an entry it finds expired, because a long session that browses many agents'
 * artifacts would otherwise keep every dead URL it ever resolved. Callers arriving together for the same artifact
 * take turns, so a message whose images all point at one recording spends one request rather than one each.
 */
class ArtifactRepository(
    /** The active backend's API — real or demo — looked up per call so a backend switch is honoured. */
    private val api: () -> CursorApi,
    private val now: () -> Long = AppClock::now,
    maxEntries: Int = MAX_ENTRIES,
) {
    constructor(session: SessionManager) : this({ session.current.api })

    private class Entry(val url: String, val expiresAtMillis: Long)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > maxEntries
    }

    /** Striped rather than per-key, so the map of locks cannot grow: a collision only makes two fetches take turns. */
    private val locks = List(FETCH_STRIPES) { Mutex() }

    private fun key(agentId: String, path: String) = "$agentId\u0000$path"

    /** A URL for [path] (the `artifacts/…` form) that is good for at least [MIN_REMAINING_MS] more. */
    suspend fun downloadUrl(agentId: String, path: String): String {
        val key = key(agentId, path)
        cached(key)?.let { return it }
        return locks[(key.hashCode() and Int.MAX_VALUE) % FETCH_STRIPES].withLock {
            // A caller that was waiting here may have just resolved it.
            cached(key)?.let { return@withLock it }
            val response = api().artifactUrl(agentId, path)
            if (response.url.isBlank()) throw IllegalStateException("The artifact isn't available any more.")
            val expiresAt = parseIsoMillis(response.expiresAt).takeIf { it > 0 } ?: (now() + DEFAULT_TTL_MS)
            synchronized(cache) { cache[key] = Entry(response.url, expiresAt) }
            response.url
        }
    }

    fun invalidate(agentId: String, path: String) {
        synchronized(cache) { cache.remove(key(agentId, path)) }
    }

    fun resetAll() {
        synchronized(cache) { cache.clear() }
    }

    private fun cached(key: String): String? = synchronized(cache) {
        val entry = cache[key] ?: return null
        if (entry.expiresAtMillis - now() > MIN_REMAINING_MS) return entry.url
        cache.remove(key)
        null
    }

    companion object {
        /** What the API documents for its presigned URLs. */
        const val DEFAULT_TTL_MS = 15 * 60_000L
        /** A URL with less than this left is not handed out: the fetch it feeds could outlive it. */
        const val MIN_REMAINING_MS = 60_000L
        /** Far more artifacts than a screen can show, and a few hundred URLs is a few hundred kilobytes. */
        const val MAX_ENTRIES = 256
        private const val FETCH_STRIPES = 8
    }
}
