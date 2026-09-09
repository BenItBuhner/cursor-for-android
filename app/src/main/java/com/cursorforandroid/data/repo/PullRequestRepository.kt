package com.cursorforandroid.data.repo

import com.cursorforandroid.data.local.PullRequestCache
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestStatus
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How reading one pull request ended. */
sealed interface PullRequestLookup {
    data class Found(val state: PullRequestState) : PullRequestLookup

    /** The source would not say: a PR the account has not classified, or one that no longer exists. */
    data object Unreadable : PullRequestLookup

    /** A transient failure — offline, a `5xx` — that the next refresh may not run into. */
    data object Failed : PullRequestLookup
}

/** Reads where one pull request stands, by the `prUrl` the Cloud Agents API names it with: the account service for a real account, the seeds for the demo. */
fun interface PullRequestSource {
    suspend fun lookup(url: String): PullRequestLookup
}

/**
 * Where the agents' pull requests stand. The Cloud Agents API only names them, so the states come from the Cursor
 * account, which keeps a status per agent in step with the SCM (the view the desktop and iOS apps show, private
 * repositories and non-GitHub SCMs included) — in bulk with every read of the account's agent list ([seed]) and one
 * at a time through [account]. States are remembered on disk and restored before the network is asked, then
 * revalidated on every list refresh according to how likely they are to have moved: an open or draft PR every few
 * minutes, a closed one a few times a day, a merged one never. What the account would not answer is remembered too,
 * so the same refused request is not repeated at every refresh. The demo answers from its seeds and never touches
 * the disk.
 */
class PullRequestRepository(
    private val account: PullRequestSource,
    private val demo: PullRequestSource,
    private val isDemo: () -> Boolean,
    private val cache: PullRequestCache? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxPerRefresh: Int = MAX_PER_REFRESH,
) {
    private val _statuses = MutableStateFlow<Map<String, PullRequestStatus>>(emptyMap())

    /** Everything remembered, by `prUrl`, the pull requests the account would not answer for included. */
    val statuses: StateFlow<Map<String, PullRequestStatus>> = _statuses.asStateFlow()

    /** The known states by `prUrl`: what the list's filter and pills go by. */
    val states: Flow<Map<String, PullRequestState>> =
        _statuses.map { all -> all.mapNotNull { (url, status) -> status.state?.let { url to it } }.toMap() }.distinctUntilChanged()

    private val passMutex = Mutex()
    private val restoreMutex = Mutex()
    @Volatile private var restored = false
    /** The mode of the last pass; states learned for the demo never mix with a real account's. */
    @Volatile private var demoMode: Boolean? = null

    /** Shows what was saved by the previous session. Idempotent, and a no-op for the demo. */
    suspend fun restoreFromCache() {
        if (cache == null || restored || isDemo()) return
        restoreMutex.withLock {
            if (restored) return
            restored = true
            val saved = cache.read() ?: return
            // Anything learned in the meantime is newer than the disk.
            _statuses.update { current -> saved + current }
        }
    }

    /**
     * Brings the states of [urls] up to date, oldest first, up to a budget per pass. [eager] is for a refresh the
     * user asked for: states that can still move are re-read after a minute rather than after their regular interval.
     * Runs in the repository's own scope, so a caller that goes away does not abandon a pass half-way through.
     */
    suspend fun refresh(urls: Collection<String>, eager: Boolean = false) {
        scope.launch {
            // Best effort, like the legacy enrichment of the list: a pass that fails leaves the states as they were.
            try {
                pass(urls, eager)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }.join()
    }

    private suspend fun pass(urls: Collection<String>, eager: Boolean) = passMutex.withLock {
        val demo = isDemo()
        if (demoMode != demo) {
            if (demoMode != null) {
                _statuses.value = emptyMap()
                restored = false
            }
            demoMode = demo
        }
        if (!demo) restoreFromCache()
        val now = AppClock.now()
        val known = _statuses.value
        val due = urls.distinct()
            .filter { url -> known[url].isDue(now, eager) }
            .sortedBy { url -> known[url]?.checkedAtMillis ?: 0L }
            .take(maxPerRefresh)
        val source = if (demo) this.demo else account
        for (url in due) {
            when (val result = source.lookup(url)) {
                is PullRequestLookup.Found -> remember(url, PullRequestStatus(result.state, AppClock.now()), persist = !demo)
                PullRequestLookup.Unreadable -> remember(url, PullRequestStatus(null, AppClock.now()), persist = !demo)
                // Offline, or the account service is down: the rest of the pass would end the same way.
                PullRequestLookup.Failed -> return
            }
        }
        if (!demo) forgetStale(now)
    }

    /**
     * States the account service reported alongside its agent list (every read, whether or not the pins are being
     * synced). They are what the first-party apps show, so they replace whatever was remembered — except that a merge
     * is final, so a stale report never undoes one.
     */
    suspend fun seed(states: Map<String, PullRequestState>) {
        if (states.isEmpty() || isDemo()) return
        if (demoMode == true) {
            // Leaving the demo: its seeded states never mix with the account's.
            _statuses.value = emptyMap()
            restored = false
        }
        demoMode = false
        restoreFromCache()
        val at = AppClock.now()
        val next = _statuses.updateAndGet { all ->
            all + states.mapNotNull { (url, state) ->
                if (all[url]?.state == PullRequestState.Merged && state != PullRequestState.Merged) null else url to PullRequestStatus(state, at)
            }
        }
        cache?.write(next)
    }

    private fun PullRequestStatus?.isDue(now: Long, eager: Boolean): Boolean {
        if (this == null) return true
        val interval = when (state) {
            PullRequestState.Merged -> return false
            null -> UNREADABLE_INTERVAL_MS
            PullRequestState.Closed -> CLOSED_INTERVAL_MS
            PullRequestState.Open, PullRequestState.Draft -> OPEN_INTERVAL_MS
        }
        // Eagerness does not extend to what the account refused: a PR it has no record of answers the same every time.
        val effective = if (eager && state != null) minOf(interval, EAGER_INTERVAL_MS) else interval
        return now - checkedAtMillis >= effective
    }

    private suspend fun remember(url: String, status: PullRequestStatus, persist: Boolean) {
        val next = _statuses.updateAndGet { it + (url to status) }
        if (persist) cache?.write(next)
    }

    /**
     * Keeps the record bounded: a pull request not looked at for a month belongs to an agent that is long gone from
     * the list (a merged one is never re-read, so it is asked about once more should its agent still be around).
     */
    private suspend fun forgetStale(now: Long) {
        var dropped = false
        val next = _statuses.updateAndGet { all ->
            val kept = all.filterValues { now - it.checkedAtMillis < MAX_AGE_MS }
            dropped = kept.size != all.size
            kept
        }
        if (dropped) cache?.write(next)
    }

    /** Forgets everything on sign-out; the disk copy goes with the other caches. */
    fun reset() {
        _statuses.value = emptyMap()
        restored = false
        demoMode = null
    }

    private companion object {
        const val MAX_PER_REFRESH = 50
        const val OPEN_INTERVAL_MS = 10 * 60_000L
        const val CLOSED_INTERVAL_MS = 6 * 60 * 60_000L
        const val UNREADABLE_INTERVAL_MS = 60 * 60_000L
        const val EAGER_INTERVAL_MS = 60_000L
        const val MAX_AGE_MS = 30L * 24 * 60 * 60_000L
    }
}
