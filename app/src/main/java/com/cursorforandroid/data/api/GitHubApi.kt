package com.cursorforandroid.data.api

import com.cursorforandroid.domain.ChangedFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.CheckRun
import com.cursorforandroid.domain.CheckStatus
import com.cursorforandroid.domain.PullRequestDetails
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoEntry
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.Review
import com.cursorforandroid.domain.ReviewComment
import com.cursorforandroid.domain.ReviewThread
import com.cursorforandroid.domain.ReviewVerdict
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.util.AppClock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

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

    // -- the pull request, verbatim, for the conversation panel --------------------------------------------------

    /** The pull request as GitHub describes it: title and body verbatim, state, author, refs and counts. */
    suspend fun pullRequestDetails(ref: GitHubPullRequestRef): PullRequestDetails {
        val dto = get("repos/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}", PullDetailsDto.serializer())
        return PullRequestDetails(
            url = dto.htmlUrl ?: "https://github.com/${ref.repo.owner}/${ref.repo.name}/pull/${ref.number}",
            host = ScmHost.GitHub,
            number = dto.number ?: ref.number,
            title = dto.title,
            body = dto.body.orEmpty(),
            state = GitHubPullRequest(dto.state, dto.merged, dto.draft).toPullRequestState(),
            author = dto.user?.login,
            authorAvatarUrl = dto.user?.avatarUrl,
            headRef = dto.head?.ref,
            baseRef = dto.base?.ref,
            headSha = dto.head?.sha,
            additions = dto.additions,
            deletions = dto.deletions,
            changedFiles = dto.changedFiles,
            commits = dto.commits,
            createdAtMillis = millis(dto.createdAt),
            updatedAtMillis = millis(dto.updatedAt),
            mergedAtMillis = millis(dto.mergedAt),
            mergeableState = dto.mergeableState,
            labels = dto.labels.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } },
        )
    }

    /** The files a pull request changed, with their patches (absent for binaries); the first [PAGE_SIZE] of them. */
    suspend fun pullRequestFiles(ref: GitHubPullRequestRef, page: Int = 1): List<ChangedFile> =
        get("repos/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}/files?per_page=$PAGE_SIZE&page=$page", ListSerializer(PullFileDto.serializer()))
            .filter { it.filename.isNotBlank() }
            .map { ChangedFile(it.filename, ChangedFileStatus.parse(it.status), it.additions, it.deletions, it.patch, it.previousFilename) }

    /** The check runs on [sha] (`commits/{sha}/check-runs`): CI and the other apps that report against a commit. */
    suspend fun checkRuns(repo: GitHubRepo, sha: String): List<CheckRun> =
        get("repos/${repo.owner}/${repo.name}/commits/$sha/check-runs?per_page=$PAGE_SIZE", CheckRunsDto.serializer()).checkRuns
            .filter { it.name.isNotBlank() }
            .map {
                CheckRun(
                    name = it.name,
                    status = when (it.status?.lowercase()) {
                        "queued", "waiting", "requested", "pending" -> CheckStatus.Queued
                        "in_progress" -> CheckStatus.InProgress
                        "completed" -> CheckStatus.Completed
                        else -> CheckStatus.Unknown
                    },
                    conclusion = CheckConclusion.parse(it.conclusion),
                    detailsUrl = it.detailsUrl ?: it.htmlUrl,
                    source = it.app?.name,
                    startedAtMillis = millis(it.startedAt),
                    completedAtMillis = millis(it.completedAt),
                )
            }

    /** The review comments on a pull request's diff, threaded by `in_reply_to_id`, oldest thread first. */
    suspend fun reviewThreads(ref: GitHubPullRequestRef): List<ReviewThread> {
        val comments = get("repos/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}/comments?per_page=$PAGE_SIZE", ListSerializer(ReviewCommentDto.serializer()))
        val byId = comments.associateBy { it.id }
        // A reply names the comment it answers; follow the chain to the first comment of the thread.
        fun rootOf(comment: ReviewCommentDto): Long {
            var current = comment
            var hops = 0
            while (current.inReplyToId != null && hops++ < 64) current = byId[current.inReplyToId] ?: return current.inReplyToId!!
            return current.id
        }
        return comments.groupBy(::rootOf).values.map { thread ->
            val ordered = thread.sortedBy { it.createdAt.orEmpty() }
            val first = ordered.first()
            ReviewThread(
                path = first.path,
                line = first.line ?: first.originalLine,
                comments = ordered.map { ReviewComment(it.id, it.user?.login, it.body.orEmpty(), millis(it.createdAt), it.htmlUrl, it.user?.avatarUrl) },
                diffHunk = first.diffHunk,
            )
        }.sortedBy { it.comments.first().createdAtMillis ?: 0L }
    }

    /** The reviews submitted on a pull request: verdicts and their summaries, oldest first. */
    suspend fun reviews(ref: GitHubPullRequestRef): List<Review> =
        get("repos/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}/reviews?per_page=$PAGE_SIZE", ListSerializer(ReviewDto.serializer()))
            .map { Review(it.id, it.user?.login, ReviewVerdict.parse(it.state), it.body.orEmpty(), millis(it.submittedAt)) }
            .sortedBy { it.submittedAtMillis ?: 0L }

    /**
     * What sits at [path] of the repository at [ref] (`contents/{path}?ref=`): a directory's entries, or a file with
     * its bytes (GitHub inlines files up to 1 MiB as base64; larger ones come back without `content` and are refused
     * here rather than shown empty).
     */
    suspend fun contents(repo: GitHubRepo, path: String, ref: String?): RepoContents {
        val clean = path.trim().trim('/')
        val query = ref?.trim()?.takeIf { it.isNotEmpty() }?.let { "?ref=${encode(it)}" }.orEmpty()
        val encodedPath = clean.split('/').filter { it.isNotEmpty() }.joinToString("/") { encode(it) }
        val target = if (encodedPath.isEmpty()) "contents" else "contents/$encodedPath"
        val element = get("repos/${repo.owner}/${repo.name}/$target$query", JsonElement.serializer())
        return when (element) {
            is JsonArray -> RepoContents.Directory(
                clean,
                CursorJson.decodeFromJsonElement(ListSerializer(ContentsEntryDto.serializer()), element)
                    .filter { it.name.isNotBlank() }
                    .map { RepoEntry(it.name, it.path.ifBlank { listOf(clean, it.name).filter { s -> s.isNotEmpty() }.joinToString("/") }, isDirectory = it.type == "dir", sizeBytes = it.size.takeIf { s -> it.type != "dir" }) }
                    .sortedWith(compareByDescending<RepoEntry> { it.isDirectory }.thenBy { it.name.lowercase() }),
            )
            is JsonObject -> {
                val dto = CursorJson.decodeFromJsonElement(ContentsEntryDto.serializer(), element)
                if (dto.type == "dir") return RepoContents.Directory(clean, emptyList())
                val encoded = dto.content?.takeIf { it.isNotBlank() }
                    ?: throw GitHubApiException(413, "${dto.name.ifBlank { "This file" }} is too large for GitHub to send inline.")
                val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrElse { throw IOException("GitHub sent a file that could not be decoded.", it) }
                RepoContents.File(RepoFile(dto.path.ifBlank { clean }, bytes, dto.size ?: bytes.size.toLong(), dto.sha, dto.downloadUrl))
            }
            else -> throw IOException("GitHub sent an answer that could not be read.")
        }
    }

    private fun millis(iso: String?): Long? = iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

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

    @Serializable
    private data class UserDto(val login: String? = null, @SerialName("avatar_url") val avatarUrl: String? = null)

    @Serializable
    private data class RefDto(val ref: String? = null, val sha: String? = null)

    @Serializable
    private data class LabelDto(val name: String? = null)

    @Serializable
    private data class PullDetailsDto(
        val number: Int? = null,
        val state: String = "",
        val merged: Boolean = false,
        val draft: Boolean = false,
        val title: String = "",
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val user: UserDto? = null,
        val head: RefDto? = null,
        val base: RefDto? = null,
        val additions: Int? = null,
        val deletions: Int? = null,
        @SerialName("changed_files") val changedFiles: Int? = null,
        val commits: Int? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("merged_at") val mergedAt: String? = null,
        @SerialName("mergeable_state") val mergeableState: String? = null,
        val labels: List<LabelDto> = emptyList(),
    )

    @Serializable
    private data class PullFileDto(
        val filename: String = "",
        val status: String? = null,
        val additions: Int = 0,
        val deletions: Int = 0,
        val patch: String? = null,
        @SerialName("previous_filename") val previousFilename: String? = null,
    )

    @Serializable
    private data class AppDto(val name: String? = null)

    @Serializable
    private data class CheckRunDto(
        val name: String = "",
        val status: String? = null,
        val conclusion: String? = null,
        @SerialName("details_url") val detailsUrl: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val app: AppDto? = null,
        @SerialName("started_at") val startedAt: String? = null,
        @SerialName("completed_at") val completedAt: String? = null,
    )

    @Serializable
    private data class CheckRunsDto(@SerialName("check_runs") val checkRuns: List<CheckRunDto> = emptyList())

    @Serializable
    private data class ReviewCommentDto(
        val id: Long,
        @SerialName("in_reply_to_id") val inReplyToId: Long? = null,
        val path: String? = null,
        val line: Int? = null,
        @SerialName("original_line") val originalLine: Int? = null,
        val body: String? = null,
        val user: UserDto? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("diff_hunk") val diffHunk: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
    )

    @Serializable
    private data class ReviewDto(
        val id: Long,
        val user: UserDto? = null,
        val state: String? = null,
        val body: String? = null,
        @SerialName("submitted_at") val submittedAt: String? = null,
    )

    @Serializable
    private data class ContentsEntryDto(
        val name: String = "",
        val path: String = "",
        val type: String = "file",
        val size: Long? = null,
        val sha: String? = null,
        val content: String? = null,
        @SerialName("download_url") val downloadUrl: String? = null,
    )

    companion object {
        const val BASE_URL = "https://api.github.com/"
        const val API_VERSION = "2022-11-28"
        /** GitHub's maximum page; one page is what the panel shows, and every page is a request against the hourly budget. */
        const val PAGE_SIZE = 100
        /** How long GitHub is left alone after a rate-limit answer that did not say when it lifts. */
        const val DEFAULT_RATE_LIMIT_PAUSE_MS = 60_000L
        /** A reset time further out than this is not believed: the primary limit is hourly. */
        const val MAX_RATE_LIMIT_PAUSE_MS = 61 * 60_000L
    }
}
