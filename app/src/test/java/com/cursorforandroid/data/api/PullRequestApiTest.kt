package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.CheckStatus
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.ReviewVerdict
import com.cursorforandroid.domain.ScmHost
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The account's pull request reads (`SCMService`, `BackgroundComposerService`) over Connect JSON, and opening one. */
class PullRequestApiTest {

    private val server = MockWebServer()
    private lateinit var api: PullRequestApi
    private val prUrl = "https://gitlab.com/acme/app/-/merge_requests/7"

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = PullRequestApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the record comes from SCMService with its title and body verbatim, the state read by name or flag`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"pullRequest":{"id":"gid://1","prUrl":"$prUrl","number":7,"title":"Add the panel","state":"SCM_PULL_REQUEST_STATE_OPEN","isMerged":false,"isDraft":true,
                    "repository":"acme/app","headRefName":"cursor/panel","baseRefName":"main","mergeStateStatus":"CLEAN","reviewDecision":"REVIEW_REQUIRED",
                    "authorLogin":"cursor[bot]","createdAt":"2026-09-11T10:00:00Z","updatedAt":"2026-09-11T11:00:00.500Z","body":"## What\n\nThe panel.","commitCount":3}}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"pullRequest":{"prUrl":"$prUrl","number":7,"title":"t","state":3,"isMerged":true}}"""))
        server.enqueue(MockResponse().setBody("""{"pullRequest":{"prUrl":"$prUrl","number":7,"title":"t","state":"SCM_PULL_REQUEST_STATE_CLOSED"}}"""))

        val details = api.pullRequest(prUrl)

        assertThat(details.url).isEqualTo(prUrl)
        assertThat(details.host).isEqualTo(ScmHost.Other)
        assertThat(details.number).isEqualTo(7)
        assertThat(details.title).isEqualTo("Add the panel")
        assertThat(details.body).isEqualTo("## What\n\nThe panel.")
        assertThat(details.state).isEqualTo(PullRequestState.Draft)
        assertThat(details.author).isEqualTo("cursor[bot]")
        assertThat(details.headRef).isEqualTo("cursor/panel")
        assertThat(details.baseRef).isEqualTo("main")
        assertThat(details.commits).isEqualTo(3)
        assertThat(details.mergeableState).isEqualTo("clean")
        assertThat(details.createdAtMillis).isEqualTo(1_789_120_800_000L)
        assertThat(details.updatedAtMillis).isEqualTo(1_789_124_400_500L)
        assertThat(api.pullRequest(prUrl).state).isEqualTo(PullRequestState.Merged)
        assertThat(api.pullRequest(prUrl).state).isEqualTo(PullRequestState.Closed)
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.SCMService/GetPullRequest")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer s")
        assertThat(request.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("prUrl", prUrl, "skipCache", "false")
    }

    @Test
    fun `the files come from GetPullRequestDiff with their patches, statuses and counts`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"files":[
                     {"filename":"a.kt","status":"SCM_PULL_REQUEST_DIFF_FILE_STATUS_MODIFIED","patch":"@@ -1,2 +1,2 @@\n-a\n+b\n c"},
                     {"filename":"b.kt","previousFilename":"old.kt","status":4,"patch":"@@ -0,0 +1 @@\n+x"},
                     {"filename":"c.png","status":"SCM_PULL_REQUEST_DIFF_FILE_STATUS_ADDED"},
                     {"filename":"d.kt","status":"SCM_PULL_REQUEST_DIFF_FILE_STATUS_DELETED","patch":"@@ -1 +0,0 @@\n-gone"},
                     {"filename":""}
                   ],"baseSha":"b","headSha":"h","baseRefName":"main","headRefName":"cursor/panel"}""",
            ),
        )

        val files = api.files(prUrl)

        assertThat(files.map { it.path }).containsExactly("a.kt", "b.kt", "c.png", "d.kt").inOrder()
        assertThat(files[0].status).isEqualTo(ChangedFileStatus.Modified)
        assertThat(files[0].additions).isEqualTo(1)
        assertThat(files[0].deletions).isEqualTo(1)
        assertThat(files[1].status).isEqualTo(ChangedFileStatus.Renamed)
        assertThat(files[1].previousPath).isEqualTo("old.kt")
        assertThat(files[2].status).isEqualTo(ChangedFileStatus.Added)
        assertThat(files[2].patch).isNull()
        assertThat(files[3].status).isEqualTo(ChangedFileStatus.Removed)
        assertThat(files[3].deletions).isEqualTo(1)
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.SCMService/GetPullRequestDiff")
        assertThat(request.json()["prUrl"]?.jsonPrimitive?.content).isEqualTo(prUrl)
    }

    @Test
    fun `the checks and the verdict come from GetDetailedPullRequestStatus on the composer service`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"isMerged":false,"state":"open","checkStatus":{"overallState":"pending","successCount":1,"failureCount":1,"pendingCount":1,"checks":[
                     {"name":"Build","status":"success","detailsUrl":"https://ci/1","provider":"GitLab CI","completedAt":"2026-09-11T10:00:00Z"},
                     {"name":"Tests","status":"failure","provider":"GitLab CI"},
                     {"name":"Screenshots","status":"in_progress"},
                     {"name":"Lint","status":"skipped"},
                     {"name":""}
                   ]},"reviewDecision":"CHANGES_REQUESTED","headSha":"h","additions":61,"deletions":12,"commitCount":2}""",
            ),
        )

        val status = api.status(prUrl)

        assertThat(status.checks.map { it.name }).containsExactly("Build", "Tests", "Screenshots", "Lint").inOrder()
        assertThat(status.checks[0].conclusion).isEqualTo(CheckConclusion.Success)
        assertThat(status.checks[0].source).isEqualTo("GitLab CI")
        assertThat(status.checks[0].detailsUrl).isEqualTo("https://ci/1")
        assertThat(status.checks[0].completedAtMillis).isEqualTo(1_789_120_800_000L)
        assertThat(status.checks[1].isFailure).isTrue()
        assertThat(status.checks[2].status).isEqualTo(CheckStatus.InProgress)
        assertThat(status.checks[2].isPending).isTrue()
        assertThat(status.checks[3].conclusion).isEqualTo(CheckConclusion.Skipped)
        assertThat(status.reviewDecision).isEqualTo(ReviewVerdict.ChangesRequested)
        assertThat(status.headSha).isEqualTo("h")
        assertThat(status.additions).isEqualTo(61)
        assertThat(status.deletions).isEqualTo(12)
        assertThat(status.commits).isEqualTo(2)
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetDetailedPullRequestStatus")
        assertThat(request.json()["prUrl"]?.jsonPrimitive?.content).isEqualTo(prUrl)
    }

    @Test
    fun `the discussions are the review threads, then the top-level conversation as one more thread`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"threads":[
                     {"id":"t1","path":"a.kt","line":12,"isResolved":true,"comments":[
                        {"id":"101","authorLogin":"bennett","body":"Why?","createdAt":"2026-09-11T10:00:00Z","diffHunk":"@@ -10 +12 @@"},
                        {"id":"102","authorLogin":"cursor[bot]","body":"Because.","createdAt":"1789124400000"}
                     ]},
                     {"id":"t2","path":"b.kt","startLine":3,"comments":[{"id":"x","authorName":"Someone","body":"   "}]}
                   ],"topLevelComments":[{"id":"201","authorLogin":"bennett","body":"LGTM overall","createdAt":"2026-09-11T12:00:00Z"}]}""",
            ),
        )

        val threads = api.discussions(prUrl)

        assertThat(threads).hasSize(2)
        val inline = threads[0]
        assertThat(inline.path).isEqualTo("a.kt")
        assertThat(inline.line).isEqualTo(12)
        assertThat(inline.isResolved).isTrue()
        assertThat(inline.diffHunk).isEqualTo("@@ -10 +12 @@")
        assertThat(inline.comments.map { it.body }).containsExactly("Why?", "Because.").inOrder()
        assertThat(inline.comments[0].id).isEqualTo(101L)
        assertThat(inline.comments[0].createdAtMillis).isEqualTo(1_789_120_800_000L)
        assertThat(inline.comments[1].createdAtMillis).isEqualTo(1_789_124_400_000L)
        // A thread whose comments are all blank is no thread; the top-level comments are one without a file.
        val top = threads[1]
        assertThat(top.path).isNull()
        assertThat(top.comments.single().body).isEqualTo("LGTM overall")
        server.takeRequest()
        assertThat(server.takeRequest().path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetPullRequestDiscussions")
    }

    @Test
    fun `opening the pull request goes out as MakePRBackgroundComposer, and a branch with no commits says so`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"prUrl":"https://github.com/acme/app/pull/9","branchName":"cursor/panel","hasCommits":true,"owner":"acme","repo":"app"}"""))
        server.enqueue(MockResponse().setBody("""{"prUrl":"","branchName":"cursor/panel","hasCommits":false}"""))

        val created = api.makePullRequest("bc-1", "cursor/panel")
        assertThat(created.succeeded).isTrue()
        assertThat(created.url).isEqualTo("https://github.com/acme/app/pull/9")
        assertThat(created.branchName).isEqualTo("cursor/panel")
        val empty = api.makePullRequest("bc-1", null)
        assertThat(empty.succeeded).isFalse()
        assertThat(empty.hasCommits).isFalse()

        server.takeRequest()
        val first = server.takeRequest()
        assertThat(first.path).isEqualTo("/aiserver.v1.BackgroundComposerService/MakePRBackgroundComposer")
        assertThat(first.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "branchName", "cursor/panel")
        assertThat(server.takeRequest().json().keys).containsExactly("bcId")
    }

    @Test
    fun `opening with chosen words goes out as OpenPRBackgroundComposer with the draft flag encoded either way`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"prUrl":"https://github.com/acme/app/pull/10","prNumber":10,"branchName":"b","baseBranch":"main","success":true}"""))
        server.enqueue(MockResponse().setBody("""{"success":false,"error":"branch has no commits"}"""))

        val opened = api.openPullRequest("bc-1", title = "Panel", body = "Adds it", baseBranch = "main", draft = false)
        assertThat(opened.url).isEqualTo("https://github.com/acme/app/pull/10")
        assertThat(opened.error).isNull()
        val failed = api.openPullRequest("bc-1", title = null, body = null, baseBranch = null, draft = true)
        assertThat(failed.succeeded).isFalse()
        assertThat(failed.error).isEqualTo("branch has no commits")

        server.takeRequest()
        val first = server.takeRequest()
        assertThat(first.path).isEqualTo("/aiserver.v1.BackgroundComposerService/OpenPRBackgroundComposer")
        assertThat(first.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "title", "Panel", "body", "Adds it", "baseBranch", "main", "draft", "false")
        assertThat(server.takeRequest().json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "draft", "true")
    }

    @Test
    fun `a record the account has no copy of is a Connect error, not an empty pull request`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))

        val error = runCatching { api.pullRequest(prUrl) }.exceptionOrNull()
        assertThat(error).isInstanceOf(ConnectRpcException::class.java)
        assertThat(error).hasMessageThat().contains("no record")
    }

    @Test
    fun `instants are read as ISO-8601 with or without an offset, or as epoch digits`() {
        assertThat(PullRequestApi.parseInstant("2026-09-11T10:00:00Z")).isEqualTo(1_789_120_800_000L)
        assertThat(PullRequestApi.parseInstant("2026-09-11T12:00:00+02:00")).isEqualTo(1_789_120_800_000L)
        assertThat(PullRequestApi.parseInstant("1789120800")).isEqualTo(1_789_120_800_000L)
        assertThat(PullRequestApi.parseInstant("1789120800000")).isEqualTo(1_789_120_800_000L)
        assertThat(PullRequestApi.parseInstant("yesterday")).isNull()
        assertThat(PullRequestApi.parseInstant(" ")).isNull()
    }

    @Test
    fun `a host's review decision is read by name, and review required is pending`() {
        assertThat(ReviewVerdict.parseDecision("APPROVED")).isEqualTo(ReviewVerdict.Approved)
        assertThat(ReviewVerdict.parseDecision("changes_requested")).isEqualTo(ReviewVerdict.ChangesRequested)
        assertThat(ReviewVerdict.parseDecision("REVIEW_REQUIRED")).isEqualTo(ReviewVerdict.Pending)
        assertThat(ReviewVerdict.parseDecision("")).isNull()
        assertThat(ReviewVerdict.parseDecision("SOMETHING_NEW")).isNull()
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
