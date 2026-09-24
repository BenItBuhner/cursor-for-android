package com.cursorforandroid.data.repo

/**
 * The one refresh policy the composer's two catalogs — the models (`GET /v1/models`) and the repositories
 * (`GET /v1/repositories`) — keep: stale-while-revalidate. What was last listed, this session or saved by an earlier
 * one, is shown at once; a list older than [staleAfterMs] is fetched again — when a picker opens, when the app comes
 * to the foreground, and on its own while the app stays there (see [CatalogRepository.keepFresh]) — and a failed
 * fetch keeps what is shown.
 *
 * An attempt spends the endpoint's allowance, not a success: after one the next waits [minIntervalMs] (the
 * repositories endpoint takes one request a user a minute, shared with the user's other Cursor clients) and any
 * `Retry-After` the server named, a forced refresh included. A fetch nobody asked for also backs off after each
 * failure in a row — [FIRST_BACKOFF_MS], doubling, never longer than [staleAfterMs] — so an endpoint that keeps
 * failing is asked less and less often rather than on every pass.
 *
 * [owner] is the backend the list was fetched from: a list fetched for another one (the demo's, a signed-out
 * account's), or one nobody fetched (repositories harvested from the agent list), is never fresh. An empty list that
 * was fetched is as fresh as any other — an account with no repositories connected is not asked every minute. Not
 * thread-safe on its own; [CatalogRepository] reads and writes it under its lock.
 */
internal class CatalogRefresher(val staleAfterMs: Long, val minIntervalMs: Long) {
    /** When the shown list was fetched (or saved, when it came from disk); zero while nothing fetched is shown. */
    var fetchedAt = 0L
        private set
    /** When the last request went out, answered or not: what the endpoint's limit is measured against. */
    var requestedAt = 0L
        private set
    /** Until when the server asked not to be asked again (`Retry-After`); zero when it asked nothing. */
    var blockedUntil = 0L
        private set
    /** Failed fetches in a row since the last one that landed. */
    var failures = 0
        private set
    private var owner: Any? = null

    /** A list fetched for [backend] that is younger than [staleAfterMs]. */
    fun isFresh(now: Long, backend: Any?): Boolean =
        owner != null && owner === backend && now - fetchedAt < staleAfterMs

    /** Whether a request may go out at [now]: the endpoint's interval since the last one is up and the server's wait is over. */
    fun mayAsk(now: Long): Boolean = now - requestedAt >= minIntervalMs && now >= blockedUntil

    /**
     * When a fetch nobody asked for is next due: once the list is stale, and no sooner than [mayAsk] allows or the
     * failures in a row back it off to.
     */
    fun dueAt(backend: Any?): Long {
        val staleAt = if (owner != null && owner === backend) fetchedAt + staleAfterMs else 0L
        val backoff = if (failures == 0) 0L else requestedAt + backoffMs(failures)
        return maxOf(staleAt, requestedAt + minIntervalMs, blockedUntil, backoff)
    }

    fun asked(now: Long) {
        requestedAt = now
    }

    fun landed(now: Long, backend: Any?) {
        fetchedAt = now
        owner = backend
        failures = 0
    }

    /** A request that failed; [retryAfterMs] is the wait the server named, when it named one. */
    fun failed(now: Long, retryAfterMs: Long?) {
        failures++
        if (retryAfterMs != null) blockedUntil = maxOf(blockedUntil, now + retryAfterMs)
    }

    /**
     * The list an earlier process saved at [savedAt] is shown for [backend]. Its save stands for its fetch — and for
     * the request that fetched it, which spent the endpoint's minute as much as any this process makes.
     */
    fun restored(savedAt: Long, backend: Any?) {
        fetchedAt = savedAt
        requestedAt = maxOf(requestedAt, savedAt)
        owner = backend
    }

    fun reset() {
        fetchedAt = 0L
        requestedAt = 0L
        blockedUntil = 0L
        failures = 0
        owner = null
    }

    private fun backoffMs(failures: Int): Long =
        (FIRST_BACKOFF_MS shl (failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(staleAfterMs)

    companion object {
        /** How old a list may be before it is fetched again: the same for both catalogs. */
        const val STALE_AFTER_MS = 10 * 60 * 1000L
        /** `/v1/repositories` is limited to one request a user a minute; a little over, for the clocks' sake. */
        const val REPOSITORIES_MIN_INTERVAL_MS = 65 * 1000L
        /** `/v1/models` names no limit: this only keeps a burst of taps on the refresh button to one request. */
        const val MODELS_MIN_INTERVAL_MS = 5 * 1000L
        /** The wait after a fetch nobody asked for failed, doubled for each further failure in a row. */
        const val FIRST_BACKOFF_MS = 60 * 1000L
        private const val MAX_BACKOFF_SHIFT = 10
    }
}
