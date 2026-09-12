package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.GitHubApi
import com.cursorforandroid.data.api.OriginApi
import com.cursorforandroid.data.api.dto.AgentUsageResponseDto
import com.cursorforandroid.data.api.dto.RunUsageDto
import com.cursorforandroid.data.api.dto.UsageTokensDto
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.ReviewVerdict
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.domain.TokenUsage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** How the panel's reads come together: five GitHub calls into one view, the caching, and every named refusal. */
class ReviewRepositoryTest {

    private val server = MockWebServer()
    private var now = 1_800_000_000_000L
    private var usageCalls = 0
    private lateinit var repo: ReviewRepository
    private val prUrl = "https://github.com/acme/app/pull/42"

    /** Answers each GitHub path from a table, so the five reads can be issued in any order. */
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = routes[request.path.orEmpty()] ?: MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
        }
        server.start()
        val gitHub = GitHubApi(OkHttpClient(), baseUrl = server.url("/").toString(), now = { now })
        val origin = OriginApi(OkHttpClient(), tokenProvider = { null }, baseUrl = server.url("/origin/").toString())
        repo = ReviewRepository(
            gitHub = { gitHub },
            origin = { origin },
            usageApi = { usageCalls++; ReviewRepository.usageOf(AgentUsageResponseDto(UsageTokensDto(1000, 500, 0, 200, 1700), listOf(RunUsageDto("run-1", UsageTokensDto(totalTokens = 1700))))) },
            now = { now },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun githubRoutes(checks: Boolean = true) {
        routes["/repos/acme/app/pulls/42"] = MockResponse().setBody("""{"number":42,"state":"open","merged":false,"draft":false,"title":"Add the panel","body":"Body","head":{"ref":"cursor/panel","sha":"abc"},"base":{"ref":"main"}}""")
        routes["/repos/acme/app/pulls/42/files?per_page=100&page=1"] = MockResponse().setBody("""[{"filename":"a.kt","status":"modified","additions":1,"deletions":1,"patch":"@@ -1 +1 @@\n-a\n+b"}]""")
        if (checks) routes["/repos/acme/app/commits/abc/check-runs?per_page=100"] = MockResponse().setBody("""{"check_runs":[{"name":"CI","status":"completed","conclusion":"success"}]}""")
        routes["/repos/acme/app/pulls/42/comments?per_page=100"] = MockResponse().setBody("""[{"id":1,"path":"a.kt","line":1,"body":"Hm","user":{"login":"b"},"created_at":"2026-09-11T10:00:00Z"}]""")
        routes["/repos/acme/app/pulls/42/reviews?per_page=100"] = MockResponse().setBody("""[{"id":1,"user":{"login":"b"},"state":"APPROVED","body":"","submitted_at":"2026-09-11T11:00:00Z"}]""")
    }

    @Test
    fun `a GitHub pull request is gathered from five reads and kept for a minute`() = runBlocking<Unit> {
        githubRoutes()
        val load = repo.pullRequest(prUrl) as PullRequestLoad.Loaded
        val view = load.view
        assertThat(view.details.title).isEqualTo("Add the panel")
        assertThat(view.details.state).isEqualTo(PullRequestState.Open)
        assertThat(view.files.single().path).isEqualTo("a.kt")
        assertThat(view.checks.single().conclusion).isEqualTo(CheckConclusion.Success)
        assertThat(view.threads.single().comments.single().body).isEqualTo("Hm")
        assertThat(view.reviews.single().verdict).isEqualTo(ReviewVerdict.Approved)
        assertThat(view.reviewDecision).isEqualTo(ReviewVerdict.Approved)
        assertThat(view.checksSummary.label).isEqualTo("All 1 check passed")
        assertThat(view.missing).isEmpty()
        // The record, its files, the checks on its head, the comments and the reviews: five requests, then none.
        assertThat(server.requestCount).isEqualTo(5)
        repo.pullRequest(prUrl)
        assertThat(server.requestCount).isEqualTo(5)
        now += ReviewRepository.TTL_MS
        repo.pullRequest(prUrl)
        assertThat(server.requestCount).isEqualTo(10)
    }

    @Test
    fun `a secondary read that fails is left out and named, the rest still shows`() = runBlocking<Unit> {
        githubRoutes(checks = false)
        val view = (repo.pullRequest(prUrl) as PullRequestLoad.Loaded).view
        assertThat(view.checks).isEmpty()
        assertThat(view.missing).containsExactly("checks")
        assertThat(view.files).hasSize(1)
    }

    @Test
    fun `what GitHub will not show anonymously is a failure that names the browser, and is not retried`() = runBlocking<Unit> {
        val load = repo.pullRequest("https://github.com/acme/private/pull/1") as PullRequestLoad.Failed
        assertThat(load.notFound).isTrue()
        assertThat(load.message).contains("anonymously")
        assertThat(server.requestCount).isEqualTo(1)
        repo.pullRequest("https://github.com/acme/private/pull/1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a spent rate limit is a failure with when it lifts, and is asked again next time`() = runBlocking<Unit> {
        routes["/repos/acme/app/pulls/42"] = MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setHeader("X-RateLimit-Reset", (now / 1000 + 60).toString()).setBody("{}")
        val load = repo.pullRequest(prUrl) as PullRequestLoad.Failed
        assertThat(load.rateLimitedUntilMillis).isEqualTo((now / 1000 + 60) * 1000)
        assertThat(load.notFound).isFalse()
        // Not cached: once the limit lifts the next open reads it.
        now += 61_000
        githubRoutes()
        assertThat(repo.pullRequest(prUrl)).isInstanceOf(PullRequestLoad.Loaded::class.java)
    }

    @Test
    fun `other hosts, and Origin without a token, are unsupported with the browser as the way in`() = runBlocking<Unit> {
        val gitlab = repo.pullRequest("https://gitlab.com/acme/app/-/merge_requests/3") as PullRequestLoad.Unsupported
        assertThat(gitlab.host).isEqualTo(ScmHost.Other)
        assertThat(gitlab.reason).contains("Extended mode")

        val origin = repo.pullRequest("https://origin.cursor.com/acme/rocket/pulls/17") as PullRequestLoad.Unsupported
        assertThat(origin.host).isEqualTo(ScmHost.Origin)
        assertThat(origin.reason).contains("Origin access token")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the demo answers from its own record and never reaches a host`() = runBlocking<Unit> {
        val demo = ReviewRepository(
            gitHub = { error("not for the demo") },
            origin = { error("not for the demo") },
            usageApi = { error("not for the demo") },
            isDemo = { true },
            demo = { url -> if (url == prUrl) com.cursorforandroid.data.demo.DemoReview.pullRequest(url) else null },
        )
        assertThat(demo.pullRequest(prUrl)).isInstanceOf(PullRequestLoad.Loaded::class.java)
        assertThat(demo.pullRequest("https://github.com/acme/app/pull/1")).isInstanceOf(PullRequestLoad.Unsupported::class.java)
        assertThat(demo.contents("https://github.com/acme/app", null, "").isFailure).isTrue()
    }

    @Test
    fun `repository contents are read from the host the URL names and cached by path`() = runBlocking<Unit> {
        routes["/repos/acme/app/contents?ref=cursor%2Fpanel"] = MockResponse().setBody("""[{"name":"app","path":"app","type":"dir"}]""")
        assertThat(repo.contentsHost("https://github.com/acme/app")).isEqualTo(ScmHost.GitHub)
        assertThat(repo.contentsHost("https://origin.cursor.com/acme/rocket")).isEqualTo(ScmHost.Origin)
        assertThat(repo.contentsHost("https://gitlab.com/acme/app")).isNull()

        val listing = repo.contents("https://github.com/acme/app", "cursor/panel", "").getOrThrow() as RepoContents.Directory
        assertThat(listing.entries.single().name).isEqualTo("app")
        repo.contents("https://github.com/acme/app", "cursor/panel", "")
        assertThat(server.requestCount).isEqualTo(1)

        val other = repo.contents("https://gitlab.com/acme/app", null, "")
        assertThat(other.isFailure).isTrue()
        assertThat(repo.describe(other.exceptionOrNull()!!)).contains("Extended mode")
        val origin = repo.contents("https://origin.cursor.com/acme/rocket", null, "")
        assertThat(repo.describe(origin.exceptionOrNull()!!)).contains("access token")
    }

    @Test
    fun `usage is typed and cached`() = runBlocking<Unit> {
        val usage = repo.usage("bc-1")
        assertThat(usage.total).isEqualTo(TokenUsage(1000, 500, 0, 200, 1700))
        assertThat(usage.runs.single().runId).isEqualTo("run-1")
        repo.usage("bc-1")
        assertThat(usageCalls).isEqualTo(1)
        repo.usage("bc-1", force = true)
        assertThat(usageCalls).isEqualTo(2)
        assertThat(TokenUsage.format(1700)).isEqualTo("1.7k")
        assertThat(TokenUsage.format(2_500_000)).isEqualTo("2.5M")
        assertThat(TokenUsage.format(999)).isEqualTo("999")
        assertThat(TokenUsage.format(12_000)).isEqualTo("12k")
    }
}
