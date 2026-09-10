package com.cursorforandroid.data.api

import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.util.AppClock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** `owner/name` of a repository on github.com, from the URL forms the Cloud Agents API and git use; null for any other host. */
data class GitHubRepo(val owner: String, val name: String) {
    companion object {
        private val URL = Regex("""^(?:https?://)?(?:[^@/\s]+@)?(?:www\.)?github\.com[/:]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)

        fun parse(url: String?): GitHubRepo? {
            val match = URL.matchEntire(url?.trim().orEmpty()) ?: return null
            val (owner, name) = match.destructured
            return GitHubRepo(owner, name)
        }
    }
}

/** One pull request on github.com, by the URL the Cloud Agents API names it with (`https://github.com/o/r/pull/N`). */
data class GitHubPullRequestRef(val repo: GitHubRepo, val number: Int) {
    companion object {
        private val URL = Regex("""^(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull/(\d+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

        fun parse(url: String?): GitHubPullRequestRef? {
            val match = URL.matchEntire(url?.trim().orEmpty()) ?: return null
            val (owner, name, number) = match.destructured
            return GitHubPullRequestRef(GitHubRepo(owner, name), number.toIntOrNull() ?: return null)
        }
    }
}

/** GitHub answered with an error. [rateLimitResetAtMs] is set when the answer was the rate limit, with when it lifts. */
class GitHubApiException(val httpCode: Int, message: String, val rateLimitResetAtMs: Long? = null) : IOException(message) {
    /** The resource is not there for this reader: gone, never existed, or private to an anonymous request (GitHub says 404 for that too). */
    val isNotFound: Boolean get() = httpCode == 404 || httpCode == 410 || httpCode == 451
    val isRateLimited: Boolean get() = rateLimitResetAtMs != null
}

/** The corner of a pull request record the app reads. */
data class GitHubPullRequest(val state: String, val merged: Boolean, val draft: Boolean) {
    fun toPullRequestState(): PullRequestState = when {
        merged -> PullRequestState.Merged
        state.equals("closed", ignoreCase = true) -> PullRequestState.Closed
        draft -> PullRequestState.Draft
        else -> PullRequestState.Open
    }
}

/** The paths of a repository tree; [truncated] when GitHub cut the listing short (100k entries / 7 MB). */
data class GitHubTree(val paths: List<String>, val truncated: Boolean)

/**
 * The two reads of GitHub's REST API the app makes when Extended mode is off, for GitHub-hosted repositories: where
 * a pull request stands, and what a repository's tree contains. Anonymous — the Cursor API key never leaves for
 * GitHub, and no GitHub token is held — so private repositories answer `404` and the budget is GitHub's 60 requests
 * an hour per address. An exhausted budget is remembered until GitHub says it lifts, so nothing keeps asking.
 */
class GitHubApi(
    private val client: OkHttpClient,
    private val baseUrl: String = BASE_URL,
    private val now: () -> Long = AppClock::now,
) {
    @Volatile private var rateLimitedUntilMs = 0L

    /** Epoch millis until which GitHub is not asked, or null when it may be. */
    val rateLimitedUntil: Long? get() = rateLimitedUntilMs.takeIf { it > now() }

    suspend fun pullRequest(ref: GitHubPullRequestRef): GitHubPullRequest {
        val dto = get("repos/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}", PullDto.serializer())
        return GitHubPullRequest(state = dto.state, merged = dto.merged, draft = dto.draft)
    }

    /** The whole tree at [ref] (a branch, a tag, a commit, or `HEAD` for the default branch), blobs and trees alike. */
    suspend fun tree(repo: GitHubRepo, ref: String): GitHubTree {
        val dto = get("repos/${repo.owner}/${repo.name}/git/trees/${ref.trim().ifEmpty { "HEAD" }}?recursive=1", TreeDto.serializer())
        return GitHubTree(paths = dto.tree.map { it.path }.filter { it.isNotBlank() }, truncated = dto.truncated)
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T {
        rateLimitedUntil?.let { throw GitHubApiException(429, "GitHub's rate limit for this address is used up.", it) }
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().resolve(path) ?: throw IOException("Bad GitHub path: $path"))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .get()
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw failure(response.code, response.header("Retry-After"), response.header("X-RateLimit-Remaining"), response.header("X-RateLimit-Reset"))
            val text = response.body?.string().orEmpty()
            return runCatching { CursorJson.decodeFromString(serializer, text) }.getOrElse { throw IOException("GitHub sent an answer that could not be read.", it) }
        }
    }

    /**
     * A `403` with no budget left (the primary limit) or a `429` (a secondary one) is the rate limit; GitHub names when
     * it lifts, in `X-RateLimit-Reset` (epoch seconds) or `Retry-After` (seconds), and a minute stands in for neither.
     */
    private fun failure(code: Int, retryAfter: String?, remaining: String?, reset: String?): GitHubApiException {
        val limited = code == 429 || (code == 403 && (remaining?.trim()?.toIntOrNull() == 0 || retryAfter != null))
        if (!limited) return GitHubApiException(code, "GitHub answered HTTP $code.")
        val until = reset?.trim()?.toLongOrNull()?.times(1000)
            ?: retryAfter?.trim()?.toLongOrNull()?.times(1000)?.let { now() + it }
            ?: (now() + DEFAULT_RATE_LIMIT_PAUSE_MS)
        rateLimitedUntilMs = until.coerceAtMost(now() + MAX_RATE_LIMIT_PAUSE_MS)
        return GitHubApiException(code, "GitHub's rate limit for this address is used up.", rateLimitedUntilMs)
    }

    @Serializable
    private data class PullDto(val state: String = "", val merged: Boolean = false, val draft: Boolean = false)

    @Serializable
    private data class TreeDto(val tree: List<TreeEntryDto> = emptyList(), val truncated: Boolean = false)

    @Serializable
    private data class TreeEntryDto(val path: String = "")

    companion object {
        const val BASE_URL = "https://api.github.com/"
        const val API_VERSION = "2022-11-28"
        /** How long GitHub is left alone after a rate-limit answer that did not say when it lifts. */
        const val DEFAULT_RATE_LIMIT_PAUSE_MS = 60_000L
        /** A reset time further out than this is not believed: the primary limit is hourly. */
        const val MAX_RATE_LIMIT_PAUSE_MS = 61 * 60_000L
    }
}
