package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.GitHubApi
import com.cursorforandroid.data.api.GitHubApiException
import com.cursorforandroid.data.api.GitHubPullRequestRef
import com.cursorforandroid.data.api.GitHubRepo
import com.cursorforandroid.data.api.OriginApi
import com.cursorforandroid.data.api.OriginApiException
import com.cursorforandroid.data.api.OriginPullRequestRef
import com.cursorforandroid.data.api.OriginRepo
import com.cursorforandroid.data.api.OriginTokenMissingException
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.AgentUsage
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RunUsage
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.domain.TokenUsage
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** How reading a pull request for the panel ended. */
sealed interface PullRequestLoad {
    data class Loaded(val view: PullRequestView) : PullRequestLoad

    /**
     * The host cannot be read from here — a service the app has no client for, or Origin without a user access
     * token — so the panel offers the browser instead. [reason] says which, in a sentence.
     */
    data class Unsupported(val host: ScmHost, val reason: String) : PullRequestLoad

    /** The read failed: offline, refused (a private repository answers 404 anonymously), or GitHub's budget is spent. */
    data class Failed(val message: String, val rateLimitedUntilMillis: Long? = null, val notFound: Boolean = false) : PullRequestLoad
}

/** A stand-in for the hosts, for the demo: answers for the URLs the demo dataset names. */
fun interface DemoReviewSource {
    fun pullRequest(url: String): PullRequestView?
}

/**
 * The reads behind the panel's Pull request, Changes, Files › Repository and Usage sections, in default mode: GitHub's
 * REST API, anonymous, for GitHub-hosted repositories (private ones answer `404` and the budget is sixty an hour, so
 * every answer is kept for a while and a spent budget is remembered); Origin's for Origin-hosted ones when a token
 * exists (none does today, see [OriginApi]); the documented usage endpoint for the tokens. Nothing here reaches
 * `api2`. Answers are cached per key for [TTL_MS] so opening and closing the panel does not spend requests.
 */
class ReviewRepository(
    private val gitHub: () -> GitHubApi,
    private val origin: () -> OriginApi,
    private val usageApi: suspend (agentId: String) -> AgentUsage,
    private val isDemo: () -> Boolean = { false },
    private val demo: DemoReviewSource? = null,
    private val now: () -> Long = AppClock::now,
) {
    private class Cached<T>(val value: T, val at: Long)

    private val pullRequests = HashMap<String, Cached<PullRequestLoad>>()
    private val contents = HashMap<String, Cached<Result<RepoContents>>>()
    private val usages = HashMap<String, Cached<AgentUsage>>()
    private val locks = List(8) { Mutex() }

    private fun lock(key: String) = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    /** The pull request at [url], with its files, checks, threads and reviews; see [PullRequestLoad]. */
    suspend fun pullRequest(url: String, force: Boolean = false): PullRequestLoad {
        val key = "pr:$url"
        if (!force) fresh(pullRequests, key)?.let { return it }
        return lock(key).withLock {
            if (!force) fresh(pullRequests, key)?.let { return@withLock it }
            val load = try {
                readPullRequest(url)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failure(t)
            }
            // A spent budget or a dropped connection is not worth remembering: the next open should try again.
            if (load !is PullRequestLoad.Failed || load.notFound) synchronized(pullRequests) { pullRequests[key] = Cached(load, now()) }
            load
        }
    }

    private suspend fun readPullRequest(url: String): PullRequestLoad {
        if (isDemo()) return demo?.pullRequest(url)?.let { PullRequestLoad.Loaded(it) } ?: PullRequestLoad.Unsupported(ScmHost.of(url), "The demo has no record of this pull request.")
        GitHubPullRequestRef.parse(url)?.let { return PullRequestLoad.Loaded(readGitHub(it)) }
        OriginPullRequestRef.parse(url)?.let { return PullRequestLoad.Loaded(readOrigin(it)) }
        return PullRequestLoad.Unsupported(ScmHost.of(url), "This pull request is on ${ScmHost.of(url).label}, which this app cannot read without Extended mode.")
    }

    /**
     * Five reads, in parallel after the first: the record, then its files, the checks on its head commit, the review
     * threads and the reviews. The record is required; a secondary read that fails is left out and named in
     * [PullRequestView.missing], so a rate limit half-way through still shows what came back. Five of GitHub's sixty
     * anonymous requests an hour, once a minute at most (see [TTL_MS]).
     */
    private suspend fun readGitHub(ref: GitHubPullRequestRef): PullRequestView = coroutineScope {
        val api = gitHub()
        val details = api.pullRequestDetails(ref)
        val files = async { runCatching { api.pullRequestFiles(ref) } }
        val checks = async { runCatching { details.headSha?.let { api.checkRuns(ref.repo, it) }.orEmpty() } }
        val threads = async { runCatching { api.reviewThreads(ref) } }
        val reviews = async { runCatching { api.reviews(ref) } }
        val missing = mutableSetOf<String>()
        PullRequestView(
            details = details,
            files = files.await().getOrElse { missing += "files"; emptyList() },
            checks = checks.await().getOrElse { missing += "checks"; emptyList() },
            threads = threads.await().getOrElse { missing += "comments"; emptyList() },
            reviews = reviews.await().getOrElse { missing += "reviews"; emptyList() },
            missing = missing,
        )
    }

    /** Origin's documented reads: the record and its files. Checks and threads have endpoints too; they follow once a token exists to test them with. */
    private suspend fun readOrigin(ref: OriginPullRequestRef): PullRequestView {
        val api = origin()
        val details = api.pullRequest(ref)
        val files = runCatching { api.pullRequestFiles(ref) }
        files.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return PullRequestView(details = details, files = files.getOrElse { emptyList() }, missing = if (files.isFailure) setOf("files") else emptySet())
    }

    private fun failure(t: Throwable): PullRequestLoad = when (t) {
        is OriginTokenMissingException -> PullRequestLoad.Unsupported(ScmHost.Origin, "This pull request is on Origin. Reading it needs an Origin access token, which this app cannot mint yet.")
        is OriginApiException -> if (t.isUnauthorized) PullRequestLoad.Unsupported(ScmHost.Origin, "Origin refused the token this app holds.") else PullRequestLoad.Failed(t.message ?: "Origin could not be read.", notFound = t.isNotFound)
        is GitHubApiException -> when {
            t.isRateLimited -> PullRequestLoad.Failed("GitHub's anonymous rate limit for this network is used up.", rateLimitedUntilMillis = t.rateLimitResetAtMs)
            t.isNotFound -> PullRequestLoad.Failed("GitHub would not show this pull request anonymously; a private repository needs its page in the browser.", notFound = true)
            else -> PullRequestLoad.Failed(t.message ?: "GitHub could not be read.")
        }
        is IOException -> PullRequestLoad.Failed(t.userMessage())
        else -> PullRequestLoad.Failed(t.message ?: "The pull request could not be read.")
    }

    /**
     * Whether [repoUrl] can be browsed from here: GitHub-hosted repositories, and Origin-hosted ones once a token
     * exists. Null names nothing browsable; the Files section says which host it is instead.
     */
    fun contentsHost(repoUrl: String?): ScmHost? = when {
        GitHubRepo.parse(repoUrl) != null -> ScmHost.GitHub
        OriginRepo.parse(repoUrl) != null -> ScmHost.Origin
        else -> null
    }

    /** The directory or file at [path] of [repoUrl] at [ref] (`null` for the default branch). */
    suspend fun contents(repoUrl: String, ref: String?, path: String, force: Boolean = false): Result<RepoContents> {
        val key = "contents:$repoUrl@${ref.orEmpty()}:$path"
        if (!force) fresh(contents, key)?.let { return it }
        return lock(key).withLock {
            if (!force) fresh(contents, key)?.let { return@withLock it }
            val result = runCatching {
                if (isDemo()) throw IOException("The demo does not browse repositories.")
                GitHubRepo.parse(repoUrl)?.let { return@runCatching gitHub().contents(it, path, ref) }
                OriginRepo.parse(repoUrl)?.let { return@runCatching origin().contents(it, path, ref) }
                throw IOException("Browsing ${ScmHost.of(repoUrl).label} needs Extended mode.")
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (result.isSuccess) synchronized(contents) { contents[key] = Cached(result, now()) }
            result
        }
    }

    /** The agent's token usage, total and by run (`GET /v1/agents/{id}/usage`). */
    suspend fun usage(agentId: String, force: Boolean = false): AgentUsage {
        val key = "usage:$agentId"
        if (!force) fresh(usages, key)?.let { return it }
        return lock(key).withLock {
            if (!force) fresh(usages, key)?.let { return@withLock it }
            usageApi(agentId).also { synchronized(usages) { usages[key] = Cached(it, now()) } }
        }
    }

    /** What the message of a failed usage or contents read should say. */
    fun describe(t: Throwable): String = when (t) {
        is OriginTokenMissingException -> "Reading Origin needs an access token this app cannot mint yet."
        is GitHubApiException -> when {
            t.isRateLimited -> "GitHub's anonymous rate limit for this network is used up."
            t.isNotFound -> "GitHub would not show this anonymously."
            else -> t.message ?: "GitHub could not be read."
        }
        else -> t.userMessage()
    }

    fun reset() {
        synchronized(pullRequests) { pullRequests.clear() }
        synchronized(contents) { contents.clear() }
        synchronized(usages) { usages.clear() }
    }

    private fun <T> fresh(map: HashMap<String, Cached<T>>, key: String): T? = synchronized(map) {
        map[key]?.takeIf { now() - it.at < TTL_MS }?.value
    }

    companion object {
        /** Long enough that reopening the panel is free, short enough that a pushed commit shows within a minute. */
        const val TTL_MS = 60_000L

        /** The typed usage the documented endpoint's DTOs map to. */
        fun usageOf(dto: com.cursorforandroid.data.api.dto.AgentUsageResponseDto): AgentUsage = AgentUsage(
            total = dto.totalUsage.let { TokenUsage(it.inputTokens, it.outputTokens, it.cacheWriteTokens, it.cacheReadTokens, it.totalTokens) },
            runs = dto.runs.map { run -> RunUsage(run.id, run.usage.let { TokenUsage(it.inputTokens, it.outputTokens, it.cacheWriteTokens, it.cacheReadTokens, it.totalTokens) }) },
        )
    }
}
