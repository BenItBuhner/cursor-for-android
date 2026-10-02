package com.cursorforandroid.promo

import com.cursorforandroid.data.api.GitHubPullRequestRef
import com.cursorforandroid.data.demo.DemoReview
import com.cursorforandroid.data.repo.DemoReviewSource
import com.cursorforandroid.domain.ChangedFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckRun
import com.cursorforandroid.domain.CheckStatus
import com.cursorforandroid.domain.PullRequestDetails
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.util.AppClock

/**
 * GitHub as the capture's account would see it: the pull request the queued follow-up opens is the scripted run's
 * edits, opened moments ago with its checks just started. Every other URL is the demo's own stand-in's, which answers
 * one plausible pull request for any URL and would otherwise show a different change under this one's number.
 */
internal class PromoReview(private val script: PromoScript) : DemoReviewSource {
    override fun pullRequest(url: String): PullRequestView? {
        if (url != HERO_PR) return DemoReview.pullRequest(url)
        val ref = checkNotNull(GitHubPullRequestRef.parse(url))
        val now = AppClock.now()
        val files = script.edits.values.map { edit ->
            val status = if (edit.diff.startsWith("@@ -0,0 ")) ChangedFileStatus.Added else ChangedFileStatus.Modified
            ChangedFile(edit.path, status, edit.added, edit.removed, patch = edit.diff)
        }
        val details = PullRequestDetails(
            url = url,
            host = ScmHost.GitHub,
            number = ref.number,
            title = "Usage meter on the account page",
            body = """
                ## Summary

                - `currentCycleUsage` in `convex/usage.ts` reads this cycle's rows from `usageDaily`, so the page never sums raw events
                - `UsageMeter` shows the minutes used against the plan's limit, amber from 80% and red at the cap
                - The account route renders it under the plan card, with its strings in `en.json`

                ## Test plan

                - [x] `npm test -- usage` passes (14 tests)
            """.trimIndent(),
            state = PullRequestState.Open,
            author = "cursor[bot]",
            headRef = HERO_BRANCH,
            baseRef = "main",
            additions = files.sumOf { it.additions },
            deletions = files.sumOf { it.deletions },
            changedFiles = files.size,
            commits = 1,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        val checks = listOf(
            CheckRun("test", CheckStatus.InProgress, null, source = "GitHub Actions", startedAtMillis = now),
            CheckRun("typecheck", CheckStatus.InProgress, null, source = "GitHub Actions", startedAtMillis = now),
        )
        return PullRequestView(details, files, checks)
    }
}
