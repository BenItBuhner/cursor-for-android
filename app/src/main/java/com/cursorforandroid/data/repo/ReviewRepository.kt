package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.CreatedPullRequest
import com.cursorforandroid.data.api.GitHubApi
import com.cursorforandroid.data.api.GitHubApiException
import com.cursorforandroid.data.api.GitHubPullRequestRef
import com.cursorforandroid.data.api.GitHubRepo
import com.cursorforandroid.data.api.OriginApi
import com.cursorforandroid.data.api.OriginApiException
import com.cursorforandroid.data.api.OriginPullRequestRef
import com.cursorforandroid.data.api.OriginRepo
import com.cursorforandroid.data.api.OriginTokenMissingException
import com.cursorforandroid.data.api.PullRequestCreationApi
import com.cursorforandroid.data.api.ScmPullRequestApi
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.AgentUsage
import com.cursorforandroid.domain.Capabilities
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
 * The reads behind the panel's Pull request, Changes, Files › Repository and Usage sections. In default mode: GitHub's
 * REST API, anonymous, for GitHub-hosted repositories (private ones answer `404` and the budget is sixty an hour, so
 * every answer is kept for a while and a spent budget is remembered); Origin's for Origin-hosted ones when a token
 * exists (none does today, see [OriginApi]); the documented usage endpoint for the tokens — nothing on `api2`. In
 * Extended mode ([Capabilities.scmPullRequests]) the account's own view of the pull request stands in wherever those
 * cannot read it: every host Cursor connects, a private GitHub repository, a spent GitHub budget ([scm]); and a pull
 * request can be opened from here ([createPullRequest]). Answers are cached per key for [TTL_MS] so opening and
 * closing the panel does not spend requests.
 */
class ReviewRepository(
    private val gitHub: () -> GitHubApi,
    private val origin: () -> OriginApi,
    private val usageApi: suspend (agentId: String) -> AgentUsage,
    private val isDemo: () -> Boolean = { false },
    private val demo: DemoReviewSource? = null,
    private val now: () -> Long = AppClock::now,
    /** The account's SCM reads, when a build wires them; asked only with [Capabilities.scmPullRequests] on. */
    private val scm: (() -> ScmPullRequestApi)? = null,
    private val creation: (() -> PullRequestCreationApi)? = null,
    private val capabilities: suspend () -> Capabilities = { Capabilities.DOCUMENTED },
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
        val account = scm?.takeIf { capabilities().scmPullRequests }
        GitHubPullRequestRef.parse(url)?.let { ref ->
            // GitHub's own word first, in either mode: anonymous, and richer than the account's copy. Where GitHub
            // will not show it (a private repository, a spent budget), the account reads it with the session alone.
            if (account == null) return PullRequestLoad.Loaded(readGitHub(ref))
            return try {
                PullRequestLoad.Loaded(readGitHub(ref))
            } catch (e: GitHubApiException) {
                if (e.isNotFound || e.isRateLimited) PullRequestLoad.Loaded(readAccount(url, account())) else throw e
            }
        }
        if (account != null) return PullRequestLoad.Loaded(readAccount(url, account()))
        OriginPullRequestRef.parse(url)?.let { return PullRequestLoad.Loaded(readOrigin(it)) }
        return PullRequestLoad.Unsupported(ScmHost.of(url), "This pull request is on ${ScmHost.of(url).label}, which this app cannot read without Extended mode.")
    }

    /**
     * The account's view: the record (required), then its files, checks and discussions in parallel; a secondary
     * read that fails is left out and named in [PullRequestView.missing], like GitHub's.
     */
    private suspend fun readAccount(url: String, api: ScmPullRequestApi): PullRequestView = coroutineScope {
        val details = api.pullRequest(url)
        val files = async { runCatching { api.files(url) } }
        val status = async { runCatching { api.status(url) } }
        val threads = async { runCatching { api.discussions(url) } }
        val missing = mutableSetOf<String>()
        val statusDetails = status.await().getOrElse { missing += "checks"; null }
        PullRequestView(
            details = details.copy(
                headSha = details.headSha ?: statusDetails?.headSha,
                additions = details.additions ?: statusDetails?.additions,
                deletions = details.deletions ?: statusDetails?.deletions,
                commits = details.commits ?: statusDetails?.commits,
            ),
            files = files.await().getOrElse { missing += "files"; emptyList() },
            checks = statusDetails?.checks.orEmpty(),
            threads = threads.await().getOrElse { missing += "comments"; emptyList() },
            missing = missing,
            declaredReviewDecision = statusDetails?.reviewDecision,
        )
    }

    /**
     * Opens the agent's pull request from here (`MakePRBackgroundComposer`): Cursor pushes the branch and writes the
     * title and body the agent would have. Refused without [Capabilities.scmPullRequests] and in the demo, with the
     * reason in words; a branch with no commits yet is a named failure too.
     */
    suspend fun createPullRequest(agentId: String, branchName: String?): Result<CreatedPullRequest> {
        if (isDemo()) return Result.failure(IllegalStateException("The demo cannot open a pull request."))
        if (!capabilities().scmPullRequests) return Result.failure(IllegalStateException(NEEDS_EXTENDED_MODE))
        val api = creation?.invoke() ?: return Result.failure(IllegalStateException("Opening pull requests is not wired to the account service in this build."))
        return try {
            val created = api.makePullRequest(agentId, branchName)
            when {
                created.succeeded -> Result.success(created)
                !created.hasCommits -> Result.failure(IOException("The branch has no commits yet, so there is nothing to open a pull request from."))
                else -> Result.failure(IOException(created.error ?: "Cursor opened no pull request."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(IOException(describe(t), t))
        }
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
        is SessionUnavailableException -> PullRequestLoad.Failed(if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) NEEDS_EXTENDED_MODE else t.message ?: "Cursor couldn't start a session for this key.", notFound = t.isPermanent)
        is ConnectRpcException -> when {
            t.httpCode == 404 || t.code == "unimplemented" -> PullRequestLoad.Failed(ENDPOINT_CHANGED, notFound = true)
            t.code == "not_found" -> PullRequestLoad.Failed("Cursor has no record of this pull request.", notFound = true)
            else -> PullRequestLoad.Failed("Cursor refused (${t.message}).")
        }
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
        is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) NEEDS_EXTENDED_MODE else t.message ?: "Cursor couldn't start a session for this key."
        is ConnectRpcException -> if (t.httpCode == 404 || t.code == "unimplemented") ENDPOINT_CHANGED else "Cursor refused (${t.message})."
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

        const val NEEDS_EXTENDED_MODE = "Needs Extended mode: reading this pull request goes through Cursor's account service."
        const val ENDPOINT_CHANGED = "Cursor changed a private endpoint; this pull request cannot be read here until the app is updated."

        /** The typed usage the documented endpoint's DTOs map to. */
        fun usageOf(dto: com.cursorforandroid.data.api.dto.AgentUsageResponseDto): AgentUsage = AgentUsage(
            total = dto.totalUsage.let { TokenUsage(it.inputTokens, it.outputTokens, it.cacheWriteTokens, it.cacheReadTokens, it.totalTokens) },
            runs = dto.runs.map { run -> RunUsage(run.id, run.usage.let { TokenUsage(it.inputTokens, it.outputTokens, it.cacheWriteTokens, it.cacheReadTokens, it.totalTokens) }) },
        )
    }
}
