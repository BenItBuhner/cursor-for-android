package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.GitHubApi
import com.cursorforandroid.data.local.PullRequestCache
import com.cursorforandroid.domain.PullRequestRef
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
import kotlinx.coroutines.withContext
import retrofit2.Response

/** How reading one pull request ended. */
sealed interface PullRequestLookup {
    data class Found(val state: PullRequestState) : PullRequestLookup

    /** GitHub would not say: a private repository without a (sufficient) token, or a PR that no longer exists. */
    data object Unreadable : PullRequestLookup

    /** GitHub's rate limit is spent until [untilMillis]; nothing more is asked of it before then. */
    data class RateLimited(val untilMillis: Long) : PullRequestLookup

    /** A transient failure — offline, a `5xx` — that the next refresh may not run into. */
    data object Failed : PullRequestLookup
}

/**
 * Reads where one pull request stands: the account service or GitHub for a real account, the seeds for the demo.
 * [ref] is the URL parsed as a GitHub pull request, or null for one on another SCM, which only the account can answer.
 */
fun interface PullRequestSource {
    suspend fun lookup(url: String, ref: PullRequestRef?): PullRequestLookup
}

/**
 * [PullRequestSource] over GitHub's REST API. A repository the request may not see answers `404` exactly like a PR
 * that was deleted, so both are [PullRequestLookup.Unreadable]. The primary rate limit — `403` or `429` with no
 * requests remaining — is reported with the reset time GitHub sends, a secondary one with its `Retry-After`.
 */
class GitHubPullRequestSource(private val api: GitHubApi, private val now: () -> Long = AppClock::now) : PullRequestSource {

    override suspend fun lookup(url: String, ref: PullRequestRef?): PullRequestLookup {
        if (ref == null) return PullRequestLookup.Unreadable
        val response = try {
            api.pullRequest(ref.owner, ref.repo, ref.number)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline, a timeout, or an answer that is not the record asked for: nothing to remember.
            return PullRequestLookup.Failed
        }
        if (response.isSuccessful) {
            val body = response.body() ?: return PullRequestLookup.Failed
            val state = PullRequestState.of(body.state, body.draft, body.merged || body.mergedAt != null)
            return state?.let { PullRequestLookup.Found(it) } ?: PullRequestLookup.Unreadable
        }
        val remaining = response.headers()["X-RateLimit-Remaining"]?.trim()?.toLongOrNull()
        return when (val code = response.code()) {
            429 -> PullRequestLookup.RateLimited(resetAt(response))
            // A secondary (abuse) limit answers 403 with `Retry-After` while the primary budget still has requests
            // left in it; without either, a 403 is an authorization failure.
            403 -> if (remaining == 0L || retryAfterSeconds(response) != null) PullRequestLookup.RateLimited(resetAt(response)) else PullRequestLookup.Unreadable
            401, 404, 410, 451 -> PullRequestLookup.Unreadable
            else -> if (code in 400..499) PullRequestLookup.Unreadable else PullRequestLookup.Failed
        }
    }

    private fun retryAfterSeconds(response: Response<*>): Long? =
        response.headers()["Retry-After"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** When to ask again: `Retry-After` (seconds from now) first, else `X-RateLimit-Reset` (epoch seconds), bounded either way. */
    private fun resetAt(response: Response<*>): Long {
        val at = now()
        val retryAfter = retryAfterSeconds(response)?.let { at + it * 1000 }
        val reset = response.headers()["X-RateLimit-Reset"]?.trim()?.toLongOrNull()?.let { it * 1000 }
        return (retryAfter ?: reset ?: (at + DEFAULT_BACKOFF_MS)).coerceIn(at + MIN_BACKOFF_MS, at + MAX_BACKOFF_MS)
    }

    private companion object {
        const val MIN_BACKOFF_MS = 30_000L
        const val DEFAULT_BACKOFF_MS = 5 * 60_000L
        const val MAX_BACKOFF_MS = 65 * 60_000L
    }
}

/**
 * Where the agents' pull requests stand. The Cloud Agents API only names them, so the states come from two places:
 * the Cursor account, which keeps a status per agent in step with the SCM (the view the desktop and iOS apps show,
 * private repositories and non-GitHub SCMs included) — in bulk with every read of the account's agent list
 * ([seed]) and one at a time through [account] — and GitHub as the fallback for whatever the account does not
 * answer. States are remembered on disk and restored before the network is asked, then revalidated on every list
 * refresh according to how likely they are to have moved: an open or draft PR every few minutes, a closed one a few
 * times a day, a merged one never. What neither source would answer — a private repository without a token — is
 * remembered too, so the same refused request is not repeated at every refresh; a token added later asks again at
 * once. A spent GitHub rate limit stops the pass and nothing is asked of GitHub until it says the budget is back.
 * The demo answers from its seeds and never touches the disk.
 */
class PullRequestRepository(
    private val gitHub: PullRequestSource,
    private val demo: PullRequestSource,
    private val isDemo: () -> Boolean,
    private val readToken: () -> String?,
    private val writeToken: (String?) -> Unit,
    private val cache: PullRequestCache? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxPerRefresh: Int = MAX_PER_REFRESH,
    /** The account service, asked before GitHub; absent in tests that only exercise GitHub. */
    private val account: PullRequestSource? = null,
) {
    private val _statuses = MutableStateFlow<Map<String, PullRequestStatus>>(emptyMap())

    /** Everything remembered, by `prUrl`, the pull requests GitHub would not answer for included. */
    val statuses: StateFlow<Map<String, PullRequestStatus>> = _statuses.asStateFlow()

    /** The known states by `prUrl`: what the list's filter and pills go by. */
    val states: Flow<Map<String, PullRequestState>> =
        _statuses.map { all -> all.mapNotNull { (url, status) -> status.state?.let { url to it } }.toMap() }.distinctUntilChanged()

    private val _hasToken = MutableStateFlow(false)

    /** Whether a GitHub token is set; without one only public repositories answer. */
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    private val passMutex = Mutex()
    private val restoreMutex = Mutex()
    @Volatile private var restored = false
    /** The mode of the last pass; states learned for the demo never mix with a real account's. */
    @Volatile private var demoMode: Boolean? = null
    @Volatile private var rateLimitedUntil = 0L

    init {
        // The encrypted store opens the Android Keystore on first use, so the token is not read where this is built.
        scope.launch { _hasToken.value = !readToken().isNullOrBlank() }
    }

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
        if (!demo && now < rateLimitedUntil) return
        val known = _statuses.value
        val account = if (demo) null else this.account
        val due = urls.distinct()
            .map { url -> url to PullRequestRef.parse(url) }
            // Without the account only GitHub can be asked, so only GitHub pull requests are worth asking about.
            .filter { (_, ref) -> ref != null || account != null }
            .filter { (url, _) -> known[url].isDue(now, eager) }
            .sortedBy { (url, _) -> known[url]?.checkedAtMillis ?: 0L }
            .take(maxPerRefresh)
        val fallback = if (demo) this.demo else gitHub
        for ((url, ref) in due) {
            // The account's answer is the one the first-party apps show; GitHub is asked when the account has none.
            val first = account?.lookup(url, ref)
            val result = when {
                first is PullRequestLookup.Found -> first
                // A pull request that is not GitHub's (GitLab, Bitbucket, an enterprise host) is only the account's
                // to answer: GitHub would call every one of them unreadable, which would remember a passing account
                // failure as a private repository and stop asking for an hour.
                ref == null && first != null -> first
                else -> fallback.lookup(url, ref)
            }
            when (result) {
                is PullRequestLookup.Found -> remember(url, PullRequestStatus(result.state, AppClock.now()), persist = !demo)
                PullRequestLookup.Unreadable -> remember(url, PullRequestStatus(null, AppClock.now()), persist = !demo)
                is PullRequestLookup.RateLimited -> {
                    rateLimitedUntil = result.untilMillis
                    return
                }
                // Offline, or GitHub is down: the rest of the pass would end the same way.
                PullRequestLookup.Failed -> return
            }
        }
        if (!demo) forgetStale(now)
    }

    /**
     * States the account service reported alongside its agent list (every read, whether or not the pins are being
     * synced). They are what the first-party apps show, so they replace whatever GitHub said — except that a merge
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
        // Eagerness does not extend to what GitHub refused: without a token, private repositories answer 404 every time.
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

    /**
     * Stores the GitHub token (blank or null removes it) and forgets what GitHub refused to say, so the next refresh
     * asks again — with the token, private repositories answer; without it, they are marked unreadable once more.
     */
    suspend fun setToken(token: String?) {
        val trimmed = token?.trim()?.takeIf { it.isNotEmpty() }
        withContext(Dispatchers.IO) { writeToken(trimmed) }
        _hasToken.value = trimmed != null
        // A token has a budget of its own; a limit spent anonymously says nothing about it.
        rateLimitedUntil = 0L
        val next = _statuses.updateAndGet { all -> all.filterValues { it.state != null } }
        if (!isDemo()) cache?.write(next)
    }

    /** Forgets everything on sign-out; the disk copy goes with the other caches. */
    fun reset() {
        _statuses.value = emptyMap()
        restored = false
        demoMode = null
        rateLimitedUntil = 0L
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
