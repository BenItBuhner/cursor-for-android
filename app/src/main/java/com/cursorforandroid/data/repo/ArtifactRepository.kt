package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.util.AppClock

/**
 * Exchanges artifact paths for the presigned URLs `GET /v1/agents/{id}/artifacts/download` hands out. The URLs
 * live for 15 minutes, so they are cached per (agent, path) and re-fetched shortly before they expire; a loader
 * that still hits a 403 calls [invalidate] and asks again.
 */
class ArtifactRepository(
    /** The active backend's API — real or demo — looked up per call so a backend switch is honoured. */
    private val api: () -> CursorApi,
    private val now: () -> Long = AppClock::now,
) {
    constructor(session: SessionManager) : this({ session.current.api })

    private class Entry(val url: String, val expiresAtMillis: Long)

    private val cache = HashMap<String, Entry>()

    private fun key(agentId: String, path: String) = "$agentId\u0000$path"

    /** A URL for [path] (the `artifacts/…` form) that is good for at least [MIN_REMAINING_MS] more. */
    suspend fun downloadUrl(agentId: String, path: String): String {
        val key = key(agentId, path)
        synchronized(cache) { cache[key] }?.takeIf { it.expiresAtMillis - now() > MIN_REMAINING_MS }?.let { return it.url }
        val response = api().artifactUrl(agentId, path)
        if (response.url.isBlank()) throw IllegalStateException("The artifact isn't available any more.")
        val expiresAt = parseIsoMillis(response.expiresAt).takeIf { it > 0 } ?: (now() + DEFAULT_TTL_MS)
        synchronized(cache) { cache[key] = Entry(response.url, expiresAt) }
        return response.url
    }

    fun invalidate(agentId: String, path: String) {
        synchronized(cache) { cache.remove(key(agentId, path)) }
    }

    fun resetAll() {
        synchronized(cache) { cache.clear() }
    }

    companion object {
        /** What the API documents for its presigned URLs. */
        const val DEFAULT_TTL_MS = 15 * 60_000L
        /** A URL with less than this left is not handed out: the fetch it feeds could outlive it. */
        const val MIN_REMAINING_MS = 60_000L
    }
}
