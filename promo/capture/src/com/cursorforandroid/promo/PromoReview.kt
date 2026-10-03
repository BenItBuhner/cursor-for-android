package com.cursorforandroid.promo

import com.cursorforandroid.data.api.GitHubPullRequestRef
import com.cursorforandroid.data.demo.DemoReview
import com.cursorforandroid.data.repo.DemoReviewSource
import com.cursorforandroid.domain.ChangedFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.CheckRun
import com.cursorforandroid.domain.CheckStatus
import com.cursorforandroid.domain.PullRequestDetails
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.util.AppClock

/**
 * GitHub as the capture's account would see it: the pull request the scripted run opens holds the run's edits, each
 * file's whole change, and its checks pass one after another, the last [CHECKS_MS] after it opened. Every other URL is the
 * demo's own stand-in's, which answers one plausible pull request for any URL and would otherwise show a different
 * change under this one's number.
 */
internal class PromoReview(private val script: PromoScript) : DemoReviewSource {

    @Volatile
    private var openedAt: Long? = null

    /** The run opened the pull request at [millis] of the app's clock. */
    fun opened(millis: Long) {
        openedAt = millis
    }

    override fun pullRequest(url: String): PullRequestView? {
        if (url != HERO_PR) return DemoReview.pullRequest(url)
        val ref = checkNotNull(GitHubPullRequestRef.parse(url))
        val now = AppClock.now()
        val opened = openedAt ?: now
        val files = script.pullRequest.map { edit ->
            val status = if (edit.diff.startsWith("@@ -0,0 ")) ChangedFileStatus.Added else ChangedFileStatus.Modified
            ChangedFile(edit.path, status, edit.added, edit.removed, patch = edit.diff)
        }
        val details = PullRequestDetails(
            url = url,
            host = ScmHost.GitHub,
            number = ref.number,
            title = "Dark mode for the dashboard",
            body = """
                ## Summary

                - The dashboard's colors are theme tokens now, with a dark set
                - Dark mode follows the system setting; the header's toggle overrides it and is remembered

                ## Test plan

                - [x] `npm run typecheck`
                - [x] `npm test` (10 tests)
            """.trimIndent(),
            state = PullRequestState.Open,
            author = "cursor[bot]",
            headRef = HERO_BRANCH,
            baseRef = "main",
            additions = files.sumOf { it.additions },
            deletions = files.sumOf { it.deletions },
            changedFiles = files.size,
            commits = 1,
            createdAtMillis = opened,
            updatedAtMillis = opened,
        )
        val checks = CHECKS.mapIndexed { i, name ->
            val finishedAt = opened + CHECKS_MS - (CHECKS.size - 1 - i) * CHECK_GAP_MS
            if (now >= finishedAt) {
                CheckRun(name, CheckStatus.Completed, CheckConclusion.Success, source = "GitHub Actions", startedAtMillis = opened, completedAtMillis = finishedAt)
            } else {
                CheckRun(name, CheckStatus.InProgress, null, source = "GitHub Actions", startedAtMillis = opened)
            }
        }
        return PullRequestView(details, files, checks)
    }

    companion object {
        /** From the pull request opening to its last check passing; the run waits them out before it answers. */
        const val CHECKS_MS = 3_000L
        private const val CHECK_GAP_MS = 700L
        private val CHECKS = listOf("typecheck", "test", "build")
    }
}
