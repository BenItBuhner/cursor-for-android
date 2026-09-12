package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.ChangedFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.CheckRun
import com.cursorforandroid.domain.CheckStatus
import com.cursorforandroid.domain.PullRequestDetails
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.ReviewComment
import com.cursorforandroid.domain.ReviewThread
import com.cursorforandroid.domain.ReviewVerdict
import com.cursorforandroid.domain.ScmHost
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * A pull request read through the account, for every host Cursor connects (GitHub, GitLab, Bitbucket, Origin) with
 * the session alone. An interface so the repositories can be faked.
 */
interface ScmPullRequestApi {
    /** `SCMService/GetPullRequest {prUrl}`: the record, verbatim title and body included. */
    suspend fun pullRequest(prUrl: String): PullRequestDetails

    /** `SCMService/GetPullRequestDiff {prUrl}`: the changed files with their patches. */
    suspend fun files(prUrl: String): List<ChangedFile>

    /** `GetDetailedPullRequestStatus {prUrl}`: the checks on the head commit, plus the host's review decision. */
    suspend fun status(prUrl: String): PullRequestStatusDetails

    /** `GetPullRequestDiscussions {prUrl}`: the review threads, and the comments at the top of the pull request. */
    suspend fun discussions(prUrl: String): List<ReviewThread>
}

/** What `GetDetailedPullRequestStatus` adds to the record: the checks and the verdict. */
data class PullRequestStatusDetails(
    val checks: List<CheckRun>,
    val reviewDecision: ReviewVerdict?,
    val headSha: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val commits: Int? = null,
)

/** What opening a pull request from the phone came to. */
data class CreatedPullRequest(val url: String?, val branchName: String?, val hasCommits: Boolean = true, val error: String? = null) {
    val succeeded: Boolean get() = !url.isNullOrBlank()
}

/** Opening the agent's pull request from here, the way the Agents Window's button does. */
interface PullRequestCreationApi {
    /**
     * `MakePRBackgroundComposer {bcId, branchName?}`: Cursor pushes the branch and opens the pull request with the
     * agent's own title and body. The answer names the pull request, or says the branch has no commits yet.
     */
    suspend fun makePullRequest(agentId: String, branchName: String? = null): CreatedPullRequest

    /** `OpenPRBackgroundComposer {bcId, title?, body?, baseBranch?, draft?}`: the same with words chosen here. */
    suspend fun openPullRequest(agentId: String, title: String?, body: String?, baseBranch: String?, draft: Boolean): CreatedPullRequest
}

/**
 * `aiserver.v1.SCMService` — the account's view of any SCM it is connected to — for the pull request's record and
 * files, and the pull request corner of `aiserver.v1.BackgroundComposerService` for its checks, its discussions and
 * for opening one. Field names are the proto's in Connect JSON's lowerCamelCase; enums arrive by name or by number.
 * Every call carries the account session from [SessionTokenProvider], which Extended mode alone hands out.
 */
class PullRequestApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : ScmPullRequestApi, PullRequestCreationApi {

    override suspend fun pullRequest(prUrl: String): PullRequestDetails {
        val response = scm("GetPullRequest", GetPullRequestDto(prUrl, skipCache = false), GetPullRequestDto.serializer(), GetPullRequestResponseDto.serializer())
        val pr = response.pullRequest ?: throw ConnectRpcException(200, null, "Cursor has no record of this pull request.")
        return detailsOf(prUrl, pr)
    }

    override suspend fun files(prUrl: String): List<ChangedFile> {
        val response = scm("GetPullRequestDiff", PrUrlDto(prUrl), PrUrlDto.serializer(), GetPullRequestDiffResponseDto.serializer())
        return response.files.mapNotNull { file ->
            val path = file.filename?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val patch = file.patch?.takeIf { it.isNotBlank() }
            ChangedFile(
                path = path,
                status = diffStatusOf(file.status),
                additions = countLines(patch, '+'),
                deletions = countLines(patch, '-'),
                patch = patch,
                previousPath = file.previousFilename?.takeIf { it.isNotBlank() && it != path },
            )
        }
    }

    override suspend fun status(prUrl: String): PullRequestStatusDetails {
        val response = composer("GetDetailedPullRequestStatus", DetailedStatusDto(prUrl), DetailedStatusDto.serializer(), DetailedStatusResponseDto.serializer())
        return PullRequestStatusDetails(
            checks = response.checkStatus?.checks.orEmpty().mapNotNull { checkOf(it) },
            reviewDecision = ReviewVerdict.parseDecision(response.reviewDecision),
            headSha = response.headSha?.takeIf { it.isNotBlank() },
            additions = response.additions,
            deletions = response.deletions,
            commits = response.commitCount,
        )
    }

    override suspend fun discussions(prUrl: String): List<ReviewThread> {
        val response = composer("GetPullRequestDiscussions", DiscussionsDto(prUrl), DiscussionsDto.serializer(), DiscussionsResponseDto.serializer())
        val threads = response.threads.mapNotNull { thread ->
            val comments = thread.comments.mapNotNull { commentOf(it) }
            if (comments.isEmpty()) return@mapNotNull null
            ReviewThread(
                path = thread.path?.takeIf { it.isNotBlank() },
                line = thread.line ?: thread.startLine ?: thread.originalLine,
                comments = comments,
                diffHunk = thread.comments.firstNotNullOfOrNull { it.diffHunk?.takeIf { hunk -> hunk.isNotBlank() } },
                isResolved = thread.isResolved == true,
            )
        }
        // The top-level conversation is one more thread, without a file.
        val topLevel = response.topLevelComments.mapNotNull { commentOf(it) }
        return if (topLevel.isEmpty()) threads else threads + ReviewThread(path = null, line = null, comments = topLevel)
    }

    override suspend fun makePullRequest(agentId: String, branchName: String?): CreatedPullRequest {
        val response = composer("MakePRBackgroundComposer", MakePrDto(agentId, branchName?.takeIf { it.isNotBlank() }), MakePrDto.serializer(), MakePrResponseDto.serializer())
        return CreatedPullRequest(
            url = response.prUrl?.takeIf { it.isNotBlank() },
            branchName = response.branchName?.takeIf { it.isNotBlank() },
            hasCommits = response.hasCommits ?: true,
        )
    }

    override suspend fun openPullRequest(agentId: String, title: String?, body: String?, baseBranch: String?, draft: Boolean): CreatedPullRequest {
        val request = OpenPrDto(
            bcId = agentId,
            title = title?.takeIf { it.isNotBlank() },
            body = body?.takeIf { it.isNotBlank() },
            baseBranch = baseBranch?.takeIf { it.isNotBlank() },
            draft = draft,
        )
        val response = composer("OpenPRBackgroundComposer", request, OpenPrDto.serializer(), OpenPrResponseDto.serializer())
        return CreatedPullRequest(
            url = response.prUrl?.takeIf { it.isNotBlank() },
            branchName = response.branchName?.takeIf { it.isNotBlank() },
            error = response.error?.takeIf { it.isNotBlank() } ?: if (response.success == false) "Cursor could not open the pull request." else null,
        )
    }

    private suspend fun <I, O> scm(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(SCM_SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    private suspend fun <I, O> composer(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    // ---- SCMService ---------------------------------------------------------------------------------------------------

    /** [skipCache] has no default so `false` is encoded (`CursorJson` drops defaulted fields). */
    @Serializable
    private data class GetPullRequestDto(val prUrl: String, val skipCache: Boolean)

    @Serializable
    private data class PrUrlDto(val prUrl: String)

    @Serializable
    private data class GetPullRequestResponseDto(val pullRequest: ScmPullRequestDto? = null)

    /** `aiserver.v1.SCMPullRequest`, the corner read here. */
    @Serializable
    internal data class ScmPullRequestDto(
        val id: String? = null,
        val prUrl: String? = null,
        val number: Int? = null,
        val title: String? = null,
        val state: JsonPrimitive? = null,
        val isMerged: Boolean? = null,
        val isDraft: Boolean? = null,
        val repository: String? = null,
        val headRefName: String? = null,
        val baseRefName: String? = null,
        val mergeStateStatus: String? = null,
        val reviewDecision: String? = null,
        val authorLogin: String? = null,
        val authorAvatarUrl: String? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
        val mergedAt: String? = null,
        val closedAt: String? = null,
        val body: String? = null,
        val commitCount: Int? = null,
        val reviewCount: Int? = null,
    )

    @Serializable
    private data class GetPullRequestDiffResponseDto(
        val files: List<ScmDiffFileDto> = emptyList(),
        val baseSha: String? = null,
        val headSha: String? = null,
        val baseRefName: String? = null,
        val headRefName: String? = null,
    )

    /** `aiserver.v1.SCMPullRequestDiffFile`. */
    @Serializable
    internal data class ScmDiffFileDto(
        val filename: String? = null,
        val previousFilename: String? = null,
        val status: JsonPrimitive? = null,
        val originalContents: String? = null,
        val modifiedContents: String? = null,
        val patch: String? = null,
        val isGenerated: Boolean? = null,
    )

    // ---- BackgroundComposerService ------------------------------------------------------------------------------------

    @Serializable
    private data class DetailedStatusDto(val prUrl: String)

    @Serializable
    private data class DetailedStatusResponseDto(
        val isMerged: Boolean? = null,
        val isClosed: Boolean? = null,
        val state: String? = null,
        val isDraft: Boolean? = null,
        val checkStatus: CheckStatusDto? = null,
        val reviewDecision: String? = null,
        val headSha: String? = null,
        val additions: Int? = null,
        val deletions: Int? = null,
        val commitCount: Int? = null,
    )

    /** `aiserver.v1.PRCheckStatus`. */
    @Serializable
    internal data class CheckStatusDto(
        val overallState: String? = null,
        val successCount: Int? = null,
        val failureCount: Int? = null,
        val pendingCount: Int? = null,
        val checks: List<CheckDto> = emptyList(),
    )

    /** `aiserver.v1.PRCheck`: [status] is the host's word (`success`, `failure`, `pending`, `in_progress`, …). */
    @Serializable
    internal data class CheckDto(
        val name: String? = null,
        val status: String? = null,
        val detailsUrl: String? = null,
        val summary: String? = null,
        val startedAt: String? = null,
        val completedAt: String? = null,
        val provider: String? = null,
        val isRequired: Boolean? = null,
    )

    @Serializable
    private data class DiscussionsDto(val prUrl: String)

    @Serializable
    private data class DiscussionsResponseDto(
        val threads: List<ThreadDto> = emptyList(),
        val topLevelComments: List<CommentDto> = emptyList(),
    )

    /** `aiserver.v1.PRReviewThread`. */
    @Serializable
    internal data class ThreadDto(
        val id: String? = null,
        val path: String? = null,
        val line: Int? = null,
        val startLine: Int? = null,
        val originalLine: Int? = null,
        val diffSide: String? = null,
        val isResolved: Boolean? = null,
        val isOutdated: Boolean? = null,
        val comments: List<CommentDto> = emptyList(),
    )

    /** `aiserver.v1.PRReviewComment` and `PRTopLevelComment`, which share their fields. */
    @Serializable
    internal data class CommentDto(
        val id: String? = null,
        val authorLogin: String? = null,
        val authorName: String? = null,
        val avatarUrl: String? = null,
        val body: String? = null,
        val createdAt: String? = null,
        val diffHunk: String? = null,
    )

    @Serializable
    private data class MakePrDto(val bcId: String, val branchName: String? = null)

    @Serializable
    private data class MakePrResponseDto(
        val prUrl: String? = null,
        val branchName: String? = null,
        val hasCommits: Boolean? = null,
        val owner: String? = null,
        val repo: String? = null,
    )

    @Serializable
    private data class OpenPrDto(
        val bcId: String,
        val title: String? = null,
        val body: String? = null,
        val baseBranch: String? = null,
        val draft: Boolean,
    )

    @Serializable
    private data class OpenPrResponseDto(
        val prUrl: String? = null,
        val prNumber: Int? = null,
        val branchName: String? = null,
        val baseBranch: String? = null,
        val success: Boolean? = null,
        val error: String? = null,
    )

    internal companion object {
        const val SCM_SERVICE = "aiserver.v1.SCMService"

        /** An enum's bare name whatever the encoding: `SCM_PULL_REQUEST_STATE_OPEN` and `OPEN` both give `OPEN`; a number stays digits. */
        fun enumName(raw: JsonPrimitive?, prefix: String): String? = raw?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()?.removePrefix(prefix)

        /** `aiserver.v1.SCMPullRequestDiffFileStatus`, by name or by number (ADDED 1, DELETED 2, MODIFIED 3, RENAMED 4). */
        fun diffStatusOf(raw: JsonPrimitive?): ChangedFileStatus = when (enumName(raw, "SCM_PULL_REQUEST_DIFF_FILE_STATUS_")) {
            "ADDED", "1" -> ChangedFileStatus.Added
            "DELETED", "REMOVED", "2" -> ChangedFileStatus.Removed
            "MODIFIED", "3" -> ChangedFileStatus.Modified
            "RENAMED", "4" -> ChangedFileStatus.Renamed
            null, "", "UNSPECIFIED", "0" -> ChangedFileStatus.Other
            else -> ChangedFileStatus.parse(enumName(raw, "SCM_PULL_REQUEST_DIFF_FILE_STATUS_"))
        }

        fun detailsOf(prUrl: String, pr: ScmPullRequestDto): PullRequestDetails {
            val url = pr.prUrl?.takeIf { it.isNotBlank() } ?: prUrl
            val state = when {
                pr.isMerged == true -> PullRequestState.Merged
                else -> when (enumName(pr.state, "SCM_PULL_REQUEST_STATE_")) {
                    "MERGED", "3" -> PullRequestState.Merged
                    "CLOSED", "2" -> PullRequestState.Closed
                    else -> if (pr.isDraft == true) PullRequestState.Draft else PullRequestState.Open
                }
            }
            return PullRequestDetails(
                url = url,
                host = ScmHost.of(url),
                number = pr.number ?: url.substringAfterLast('/').toIntOrNull() ?: 0,
                title = pr.title.orEmpty(),
                body = pr.body.orEmpty(),
                state = state,
                author = pr.authorLogin?.takeIf { it.isNotBlank() },
                authorAvatarUrl = pr.authorAvatarUrl?.takeIf { it.isNotBlank() },
                headRef = pr.headRefName?.takeIf { it.isNotBlank() },
                baseRef = pr.baseRefName?.takeIf { it.isNotBlank() },
                commits = pr.commitCount,
                createdAtMillis = parseInstant(pr.createdAt),
                updatedAtMillis = parseInstant(pr.updatedAt),
                mergedAtMillis = parseInstant(pr.mergedAt),
                mergeableState = pr.mergeStateStatus?.takeIf { it.isNotBlank() }?.lowercase(),
            )
        }

        fun checkOf(dto: CheckDto): CheckRun? {
            val name = dto.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val word = dto.status?.trim()?.lowercase().orEmpty()
            val (status, conclusion) = when (word) {
                "", "queued", "waiting", "requested" -> CheckStatus.Queued to null
                "pending", "in_progress", "running", "expected" -> CheckStatus.InProgress to null
                "success", "passed", "completed" -> CheckStatus.Completed to CheckConclusion.Success
                "failure", "failed", "error" -> CheckStatus.Completed to CheckConclusion.Failure
                "neutral" -> CheckStatus.Completed to CheckConclusion.Neutral
                "cancelled", "canceled" -> CheckStatus.Completed to CheckConclusion.Cancelled
                "skipped" -> CheckStatus.Completed to CheckConclusion.Skipped
                "timed_out" -> CheckStatus.Completed to CheckConclusion.TimedOut
                "action_required" -> CheckStatus.Completed to CheckConclusion.ActionRequired
                "stale" -> CheckStatus.Completed to CheckConclusion.Stale
                else -> CheckStatus.Completed to (CheckConclusion.parse(word) ?: CheckConclusion.Other)
            }
            return CheckRun(
                name = name,
                status = status,
                conclusion = conclusion,
                detailsUrl = dto.detailsUrl?.takeIf { it.isNotBlank() },
                source = dto.provider?.takeIf { it.isNotBlank() },
                startedAtMillis = parseInstant(dto.startedAt),
                completedAtMillis = parseInstant(dto.completedAt),
            )
        }

        fun commentOf(dto: CommentDto): ReviewComment? {
            val body = dto.body?.takeIf { it.isNotBlank() } ?: return null
            return ReviewComment(
                id = dto.id?.toLongOrNull() ?: (dto.id?.hashCode()?.toLong() ?: 0L),
                author = dto.authorLogin?.takeIf { it.isNotBlank() } ?: dto.authorName?.takeIf { it.isNotBlank() },
                body = body,
                createdAtMillis = parseInstant(dto.createdAt),
                authorAvatarUrl = dto.avatarUrl?.takeIf { it.isNotBlank() },
            )
        }

        /** An ISO-8601 instant, with or without an offset, or epoch millis as digits; null for anything else. */
        fun parseInstant(raw: String?): Long? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            text.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
            return try {
                Instant.parse(text).toEpochMilli()
            } catch (_: DateTimeParseException) {
                runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            }
        }

        private fun countLines(patch: String?, sign: Char): Int =
            patch?.lineSequence()?.count { it.startsWith(sign) && !it.startsWith("$sign$sign$sign") } ?: 0
    }
}
