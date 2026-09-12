package com.cursorforandroid.data.demo

import com.cursorforandroid.data.api.GitHubPullRequestRef
import com.cursorforandroid.data.repo.DemoReviewSource
import com.cursorforandroid.domain.ChangedFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.CheckRun
import com.cursorforandroid.domain.CheckStatus
import com.cursorforandroid.domain.PullRequestDetails
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.Review
import com.cursorforandroid.domain.ReviewComment
import com.cursorforandroid.domain.ReviewThread
import com.cursorforandroid.domain.ReviewVerdict
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.util.AppClock

/** The demo's stand-in for GitHub: one plausible pull request per URL the dataset names, so the panel has something to show. */
object DemoReview : DemoReviewSource {
    override fun pullRequest(url: String): PullRequestView? {
        val ref = GitHubPullRequestRef.parse(url) ?: return null
        val state = DemoData.pullRequestStates[url] ?: PullRequestState.Open
        val now = AppClock.now()
        val hour = 60 * 60_000L
        val details = PullRequestDetails(
            url = url,
            host = ScmHost.GitHub,
            number = ref.number,
            title = "Wire the revenue pipeline into the nightly report",
            body = """
                ## Summary

                Adds the `RevenuePipeline` job and its scheduler entry, and points the nightly report at its output.

                - New `pipeline/revenue.py` with the aggregation and the two fixtures it needs
                - `report/nightly.py` reads the pipeline's parquet output instead of the legacy CSV
                - Scheduler entry at 02:15 UTC, after the ingestion finishes

                ## Test plan

                - [x] `pytest tests/pipeline` passes
                - [x] Dry run against last week's ingestion output matches the legacy report to the cent
            """.trimIndent(),
            state = state,
            author = "cursor[bot]",
            headRef = "cursor/revenue-pipeline-3f2a",
            baseRef = "main",
            additions = 312,
            deletions = 48,
            changedFiles = 3,
            commits = 4,
            createdAtMillis = now - 26 * hour,
            updatedAtMillis = now - 3 * hour,
            mergedAtMillis = if (state == PullRequestState.Merged) now - 2 * hour else null,
            mergeableState = if (state == PullRequestState.Open) "clean" else null,
            labels = listOf("pipeline"),
        )
        val files = listOf(
            ChangedFile(
                "pipeline/revenue.py", ChangedFileStatus.Added, 214, 0,
                patch = "@@ -0,0 +1,12 @@\n+\"\"\"Aggregates settled revenue by region for the nightly report.\"\"\"\n+import polars as pl\n+\n+from pipeline.ingest import settled_orders\n+\n+\n+def run(day: str) -> pl.DataFrame:\n+    orders = settled_orders(day)\n+    return (\n+        orders.group_by(\"region\")\n+        .agg(pl.col(\"amount_usd\").sum().alias(\"revenue_usd\"))\n+    )",
            ),
            ChangedFile(
                "report/nightly.py", ChangedFileStatus.Modified, 41, 48,
                patch = "@@ -18,9 +18,8 @@ def build(day: str) -> Report:\n-    revenue = pd.read_csv(LEGACY_CSV.format(day=day))\n-    revenue = revenue.groupby(\"region\").sum()\n+    revenue = revenue_pipeline.run(day).to_pandas().set_index(\"region\")\n     sections.append(RevenueSection(revenue))",
            ),
            ChangedFile("scheduler/jobs.yaml", ChangedFileStatus.Modified, 57, 0, patch = "@@ -40,3 +40,6 @@ jobs:\n   ingest:\n     cron: \"0 1 * * *\"\n+  revenue_pipeline:\n+    cron: \"15 2 * * *\"\n+    after: ingest"),
        )
        val checks = listOf(
            CheckRun("pytest", CheckStatus.Completed, CheckConclusion.Success, source = "GitHub Actions", startedAtMillis = now - 4 * hour, completedAtMillis = now - 4 * hour + 6 * 60_000L),
            CheckRun("lint", CheckStatus.Completed, CheckConclusion.Success, source = "GitHub Actions"),
            CheckRun("dry-run report", CheckStatus.Completed, if (state == PullRequestState.Draft) CheckConclusion.Failure else CheckConclusion.Success, source = "GitHub Actions"),
        )
        val threads = listOf(
            ReviewThread(
                path = "report/nightly.py",
                line = 20,
                comments = listOf(
                    ReviewComment(1, "bennett", "Does `to_pandas()` keep the region order the legacy CSV had? The chart downstream assumes it.", now - 5 * hour),
                    ReviewComment(2, "cursor[bot]", "It does not; I sort by region explicitly in the follow-up commit.", now - 4 * hour),
                ),
                diffHunk = "@@ -18,9 +18,8 @@ def build(day: str) -> Report:",
            ),
        )
        val reviews = listOf(Review(1, "bennett", if (state == PullRequestState.Merged) ReviewVerdict.Approved else ReviewVerdict.Commented, "One question inline, otherwise this reads well.", now - 5 * hour))
        return PullRequestView(details, files, checks, threads, reviews)
    }
}
