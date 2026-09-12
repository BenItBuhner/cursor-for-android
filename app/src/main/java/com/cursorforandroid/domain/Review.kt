package com.cursorforandroid.domain

/** Which service hosts a pull request or a repository, decided from its URL. */
enum class ScmHost(val label: String) {
    GitHub("GitHub"),
    Origin("Origin"),
    Other("its host");

    companion object {
        fun of(url: String?): ScmHost {
            val trimmed = url?.trim()?.lowercase() ?: return Other
            return when {
                trimmed.contains("github.com") -> GitHub
                trimmed.contains("origin.cursor.com") || trimmed.contains("cursor.com/origin") -> Origin
                else -> Other
            }
        }
    }
}

/**
 * A pull request as its host describes it: the title and body verbatim, its state, who opened it and where it goes.
 * One shape for GitHub (`GET /repos/{o}/{r}/pulls/{n}`) and Origin (`GET /v1/origin/repos/{o}/{r}/pulls/{n}`).
 */
data class PullRequestDetails(
    val url: String,
    val host: ScmHost,
    val number: Int,
    val title: String,
    /** The description as written, markdown; empty when the author left it out. */
    val body: String,
    val state: PullRequestState,
    val author: String?,
    val authorAvatarUrl: String? = null,
    val headRef: String?,
    val baseRef: String?,
    /** The head commit, which the checks are reported against. */
    val headSha: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
    val commits: Int? = null,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    val mergedAtMillis: Long? = null,
    /** GitHub's `mergeable_state` (`clean`, `dirty`, `blocked`, `unstable`, `unknown`) when it said. */
    val mergeableState: String? = null,
    val labels: List<String> = emptyList(),
) {
    val lineStats: String? get() = lineStatsOf(additions, deletions)
}

/** How a pull request touched one file. */
enum class ChangedFileStatus(val label: String) {
    Added("Added"), Modified("Modified"), Removed("Removed"), Renamed("Renamed"), Copied("Copied"), Other("Changed");

    companion object {
        fun parse(raw: String?): ChangedFileStatus = when (raw?.trim()?.lowercase()) {
            "added" -> Added
            "modified", "changed" -> Modified
            "removed", "deleted" -> Removed
            "renamed" -> Renamed
            "copied" -> Copied
            else -> Other
        }
    }
}

/** One file of a pull request with its patch (`pulls/{n}/files`); [patch] is absent for binary and very large files. */
data class ChangedFile(
    val path: String,
    val status: ChangedFileStatus,
    val additions: Int,
    val deletions: Int,
    val patch: String?,
    val previousPath: String? = null,
) {
    val name: String get() = ToolNames.basename(path)
    val lineStats: String? get() = lineStatsOf(additions, deletions)
}

enum class CheckStatus { Queued, InProgress, Completed, Unknown }

enum class CheckConclusion(val label: String) {
    Success("Passed"), Failure("Failed"), Neutral("Neutral"), Cancelled("Cancelled"), Skipped("Skipped"), TimedOut("Timed out"), ActionRequired("Action required"), Stale("Stale"), Other("Finished");

    val isFailure: Boolean get() = this == Failure || this == TimedOut || this == ActionRequired

    companion object {
        fun parse(raw: String?): CheckConclusion? = when (raw?.trim()?.lowercase()) {
            null, "" -> null
            "success" -> Success
            "failure" -> Failure
            "neutral" -> Neutral
            "cancelled", "canceled" -> Cancelled
            "skipped" -> Skipped
            "timed_out" -> TimedOut
            "action_required" -> ActionRequired
            "stale" -> Stale
            else -> Other
        }
    }
}

/** One check run on the pull request's head commit. */
data class CheckRun(
    val name: String,
    val status: CheckStatus,
    val conclusion: CheckConclusion?,
    val detailsUrl: String? = null,
    /** The app that reported it ("GitHub Actions"). */
    val source: String? = null,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
) {
    val isPending: Boolean get() = status != CheckStatus.Completed
    val isFailure: Boolean get() = conclusion?.isFailure == true
}

/** What a set of checks adds up to, for the state chip. */
data class ChecksSummary(val total: Int, val passed: Int, val failed: Int, val pending: Int) {
    val label: String
        get() {
            val checks = if (total == 1) "check" else "checks"
            return when {
                total == 0 -> "No checks"
                failed > 0 -> "$failed of $total $checks failed"
                pending > 0 -> "$pending of $total $checks running"
                else -> "All $total $checks passed"
            }
        }

    companion object {
        fun of(checks: List<CheckRun>): ChecksSummary = ChecksSummary(
            total = checks.size,
            passed = checks.count { it.conclusion == CheckConclusion.Success || it.conclusion == CheckConclusion.Skipped || it.conclusion == CheckConclusion.Neutral },
            failed = checks.count { it.isFailure },
            pending = checks.count { it.isPending },
        )
    }
}

/** One review comment, in a thread on a line of a file or at the top of the pull request. */
data class ReviewComment(
    val id: Long,
    val author: String?,
    val body: String,
    val createdAtMillis: Long?,
    val url: String? = null,
    val authorAvatarUrl: String? = null,
)

/** The comments on one line, the first one and its replies, as GitHub threads them by `in_reply_to_id`. */
data class ReviewThread(
    val path: String?,
    val line: Int?,
    val comments: List<ReviewComment>,
    val diffHunk: String? = null,
    /** The thread was marked resolved on its host, when the host says (GitHub's REST comments do not). */
    val isResolved: Boolean = false,
) {
    val name: String? get() = path?.let(ToolNames::basename)
}

enum class ReviewVerdict(val label: String) {
    Approved("Approved"), ChangesRequested("Changes requested"), Commented("Commented"), Pending("Pending"), Dismissed("Dismissed"), Other("Reviewed");

    companion object {
        fun parse(raw: String?): ReviewVerdict = when (raw?.trim()?.uppercase()) {
            "APPROVED" -> Approved
            "CHANGES_REQUESTED" -> ChangesRequested
            "COMMENTED" -> Commented
            "PENDING", "REVIEW_REQUIRED" -> Pending
            "DISMISSED" -> Dismissed
            else -> Other
        }

        /** A host's `reviewDecision` field: null for none (an empty string, or a value that says nothing was decided). */
        fun parseDecision(raw: String?): ReviewVerdict? {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return parse(token).takeIf { it != Other }
        }
    }
}

/** A review someone submitted: their verdict and the summary they wrote, if any. */
data class Review(
    val id: Long,
    val author: String?,
    val verdict: ReviewVerdict,
    val body: String,
    val submittedAtMillis: Long?,
)

/** Everything the panel shows about a pull request, gathered from its host. */
data class PullRequestView(
    val details: PullRequestDetails,
    val files: List<ChangedFile> = emptyList(),
    val checks: List<CheckRun> = emptyList(),
    val threads: List<ReviewThread> = emptyList(),
    val reviews: List<Review> = emptyList(),
    /** Whichever of the four secondary reads did not come back, by name, so the section can say so. */
    val missing: Set<String> = emptySet(),
    /**
     * The host's own verdict on the pull request (`reviewDecision`: approved, changes requested, review required),
     * when the source reported one outright rather than the reviews it is derived from; it outranks the derivation.
     */
    val declaredReviewDecision: ReviewVerdict? = null,
) {
    val checksSummary: ChecksSummary get() = ChecksSummary.of(checks)
    val reviewDecision: ReviewVerdict?
        get() = declaredReviewDecision ?: reviews.groupBy { it.author }.values.mapNotNull { byAuthor -> byAuthor.maxByOrNull { it.submittedAtMillis ?: 0L } }
            .map { it.verdict }
            .let { latest ->
                when {
                    ReviewVerdict.ChangesRequested in latest -> ReviewVerdict.ChangesRequested
                    ReviewVerdict.Approved in latest -> ReviewVerdict.Approved
                    latest.isNotEmpty() -> ReviewVerdict.Commented
                    else -> null
                }
            }
}

/** One entry of a repository directory listing. */
data class RepoEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** A file read from the repository at a ref: text when it decodes as such, bytes for an image. */
data class RepoFile(
    val path: String,
    val bytes: ByteArray,
    val sizeBytes: Long,
    val sha: String? = null,
    /** Where the host serves the raw file, for opening in a browser. */
    val downloadUrl: String? = null,
) {
    val name: String get() = ToolNames.basename(path)
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val kind: Kind
        get() = when (extension) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Kind.Image
            "svg" -> Kind.Svg
            "md", "markdown", "mdx" -> Kind.Markdown
            else -> if (looksBinary()) Kind.Binary else Kind.Code
        }

    /** The file as text, for code and markdown; decoded as UTF-8 with replacement for anything else. */
    val text: String get() = bytes.toString(Charsets.UTF_8)

    /** A NUL in the first kilobyte is how git tells a binary from text. */
    private fun looksBinary(): Boolean {
        val probe = minOf(bytes.size, 1024)
        for (i in 0 until probe) if (bytes[i] == 0.toByte()) return true
        return false
    }

    enum class Kind { Code, Markdown, Image, Svg, Binary }

    override fun equals(other: Any?): Boolean = other is RepoFile && other.path == path && other.sha == sha && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = path.hashCode() * 31 + bytes.contentHashCode()
}

/** What browsing a repository path came to: a directory's entries or one file. */
sealed interface RepoContents {
    data class Directory(val path: String, val entries: List<RepoEntry>) : RepoContents
    data class File(val file: RepoFile) : RepoContents
}
