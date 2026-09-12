package com.cursorforandroid.data.api

import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.CheckStatus
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.ReviewVerdict
import com.cursorforandroid.domain.ScmHost
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Base64

/** The reads behind the panel's Pull request, Changes and Files › Repository sections, against a fake api.github.com. */
class GitHubReviewApiTest {

    private val server = MockWebServer()
    private lateinit var gitHub: GitHubApi
    private val ref = GitHubPullRequestRef(GitHubRepo("acme", "app"), 42)

    @Before
    fun setUp() {
        server.start()
        gitHub = GitHubApi(OkHttpClient(), baseUrl = server.url("/").toString(), now = { 1_800_000_000_000L })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the pull request comes back verbatim, with its author, refs and counts`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"number":42,"state":"open","merged":false,"draft":true,"title":"Add the panel","body":"## Summary\n\nA right-side panel.",
                    "html_url":"https://github.com/acme/app/pull/42","user":{"login":"cursor[bot]","avatar_url":"https://a/v"},
                    "head":{"ref":"cursor/panel","sha":"abc123"},"base":{"ref":"main","sha":"def456"},
                    "additions":312,"deletions":48,"changed_files":3,"commits":4,"created_at":"2026-09-11T10:00:00Z","updated_at":"2026-09-11T12:00:00Z",
                    "mergeable_state":"clean","labels":[{"name":"ui"},{"name":""}]}""",
            ),
        )
        val details = gitHub.pullRequestDetails(ref)
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/app/pulls/42")
        assertThat(details.host).isEqualTo(ScmHost.GitHub)
        assertThat(details.number).isEqualTo(42)
        assertThat(details.title).isEqualTo("Add the panel")
        assertThat(details.body).isEqualTo("## Summary\n\nA right-side panel.")
        assertThat(details.state).isEqualTo(PullRequestState.Draft)
        assertThat(details.author).isEqualTo("cursor[bot]")
        assertThat(details.headRef).isEqualTo("cursor/panel")
        assertThat(details.baseRef).isEqualTo("main")
        assertThat(details.lineStats).isEqualTo("+312 -48")
        assertThat(details.changedFiles).isEqualTo(3)
        assertThat(details.commits).isEqualTo(4)
        assertThat(details.createdAtMillis).isEqualTo(Instant.parse("2026-09-11T10:00:00Z").toEpochMilli())
        assertThat(details.mergeableState).isEqualTo("clean")
        assertThat(details.labels).containsExactly("ui")
        // A body GitHub sends as null reads as empty.
        server.enqueue(MockResponse().setBody("""{"state":"closed","merged":true,"title":"x","body":null}"""))
        assertThat(gitHub.pullRequestDetails(ref).let { it.body to it.state }).isEqualTo("" to PullRequestState.Merged)
    }

    @Test
    fun `the files carry their patches and statuses, a hundred to the page`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """[{"filename":"a/b.kt","status":"modified","additions":2,"deletions":1,"patch":"@@ -1 +1,2 @@\n-a\n+b\n+c"},
                    {"filename":"new.md","status":"added","additions":5,"deletions":0,"patch":"@@ -0,0 +1,5 @@\n+x"},
                    {"filename":"gone.txt","status":"removed","additions":0,"deletions":9},
                    {"filename":"moved.kt","status":"renamed","additions":0,"deletions":0,"previous_filename":"old.kt"},
                    {"filename":"","status":"added"}]""",
            ),
        )
        val files = gitHub.pullRequestFiles(ref)
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/app/pulls/42/files?per_page=100&page=1")
        assertThat(files.map { it.path }).containsExactly("a/b.kt", "new.md", "gone.txt", "moved.kt").inOrder()
        assertThat(files.map { it.status }).containsExactly(ChangedFileStatus.Modified, ChangedFileStatus.Added, ChangedFileStatus.Removed, ChangedFileStatus.Renamed).inOrder()
        assertThat(files[0].patch).startsWith("@@ -1 +1,2 @@")
        assertThat(files[0].lineStats).isEqualTo("+2 -1")
        assertThat(files[2].patch).isNull()
        assertThat(files[3].previousPath).isEqualTo("old.kt")
    }

    @Test
    fun `check runs on the head commit map their status and conclusion`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"title":"x","head":{"sha":"abc123"}}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"total_count":3,"check_runs":[
                    {"name":"Unit tests","status":"completed","conclusion":"success","details_url":"https://ci/1","app":{"name":"GitHub Actions"},"started_at":"2026-09-11T10:00:00Z","completed_at":"2026-09-11T10:05:00Z"},
                    {"name":"Lint","status":"in_progress","conclusion":null,"html_url":"https://ci/2","app":{"name":"GitHub Actions"}},
                    {"name":"Screenshots","status":"completed","conclusion":"failure"}]}""",
            ),
        )
        val sha = gitHub.pullRequestDetails(ref).headSha
        assertThat(sha).isEqualTo("abc123")
        val checks = gitHub.checkRuns(ref.repo, sha!!)
        server.takeRequest()
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/app/commits/abc123/check-runs?per_page=100")
        assertThat(checks.map { it.name }).containsExactly("Unit tests", "Lint", "Screenshots").inOrder()
        assertThat(checks[0].status).isEqualTo(CheckStatus.Completed)
        assertThat(checks[0].conclusion).isEqualTo(CheckConclusion.Success)
        assertThat(checks[0].detailsUrl).isEqualTo("https://ci/1")
        assertThat(checks[0].source).isEqualTo("GitHub Actions")
        assertThat(checks[1].isPending).isTrue()
        assertThat(checks[1].detailsUrl).isEqualTo("https://ci/2")
        assertThat(checks[2].isFailure).isTrue()
    }

    @Test
    fun `review comments are threaded by what they reply to, oldest thread first`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":3,"in_reply_to_id":1,"path":"a.kt","body":"Reply","user":{"login":"bot"},"created_at":"2026-09-11T10:02:00Z"},
                    {"id":1,"path":"a.kt","line":12,"body":"Question?","user":{"login":"bennett"},"created_at":"2026-09-11T10:00:00Z","diff_hunk":"@@ -10,3 +10,4 @@","html_url":"https://github.com/acme/app/pull/42#discussion_r1"},
                    {"id":2,"path":"b.kt","original_line":4,"body":"Nit","user":{"login":"bennett"},"created_at":"2026-09-11T09:00:00Z"},
                    {"id":4,"in_reply_to_id":99,"path":"c.kt","body":"Orphan","created_at":"2026-09-11T11:00:00Z"}]""",
            ),
        )
        val threads = gitHub.reviewThreads(ref)
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/app/pulls/42/comments?per_page=100")
        assertThat(threads.map { it.path }).containsExactly("b.kt", "a.kt", "c.kt").inOrder()
        val a = threads[1]
        assertThat(a.line).isEqualTo(12)
        assertThat(a.diffHunk).isEqualTo("@@ -10,3 +10,4 @@")
        assertThat(a.comments.map { it.body }).containsExactly("Question?", "Reply").inOrder()
        assertThat(a.comments[0].author).isEqualTo("bennett")
        assertThat(a.comments[0].url).endsWith("#discussion_r1")
        assertThat(threads[0].line).isEqualTo(4)
        // A reply to a comment outside the page still shows, as its own thread.
        assertThat(threads[2].comments.single().body).isEqualTo("Orphan")
    }

    @Test
    fun `reviews map their verdicts`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":2,"user":{"login":"bennett"},"state":"APPROVED","body":"LGTM","submitted_at":"2026-09-11T12:00:00Z"},
                    {"id":1,"user":{"login":"cursor[bot]"},"state":"COMMENTED","body":"","submitted_at":"2026-09-11T11:00:00Z"},
                    {"id":3,"user":{"login":"x"},"state":"CHANGES_REQUESTED","body":"No"}]""",
            ),
        )
        val reviews = gitHub.reviews(ref)
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/app/pulls/42/reviews?per_page=100")
        assertThat(reviews.map { it.verdict }).containsExactly(ReviewVerdict.ChangesRequested, ReviewVerdict.Commented, ReviewVerdict.Approved).inOrder()
        assertThat(reviews.last().body).isEqualTo("LGTM")
    }

    @Test
    fun `contents list a directory, folders first, and decode a file`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """[{"name":"README.md","path":"README.md","type":"file","size":120},
                    {"name":"app","path":"app","type":"dir"},
                    {"name":".github","path":".github","type":"dir"},
                    {"name":"build.gradle.kts","path":"build.gradle.kts","type":"file","size":3000}]""",
            ),
        )
        val listing = gitHub.contents(GitHubRepo("acme", "app"), "", "cursor/panel") as RepoContents.Directory
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/app/contents?ref=cursor%2Fpanel")
        assertThat(listing.path).isEmpty()
        assertThat(listing.entries.map { it.name }).containsExactly(".github", "app", "build.gradle.kts", "README.md").inOrder()
        assertThat(listing.entries.first().isDirectory).isTrue()
        assertThat(listing.entries.last().sizeBytes).isEqualTo(120L)

        val text = "fun main() = println(\"hi\")\n"
        server.enqueue(
            MockResponse().setBody(
                """{"name":"Main.kt","path":"app/src/Main.kt","type":"file","size":${text.length},"sha":"s1","encoding":"base64",
                    "content":"${Base64.getEncoder().encodeToString(text.toByteArray())}","download_url":"https://raw/Main.kt"}""",
            ),
        )
        val file = (gitHub.contents(GitHubRepo("acme", "app"), "/app/src/Main.kt", null) as RepoContents.File).file
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/app/contents/app/src/Main.kt")
        assertThat(file.path).isEqualTo("app/src/Main.kt")
        assertThat(file.text).isEqualTo(text)
        assertThat(file.kind).isEqualTo(RepoFile.Kind.Code)
        assertThat(file.downloadUrl).isEqualTo("https://raw/Main.kt")

        // Past GitHub's inline limit the content is left out; that is a named refusal, not an empty file.
        server.enqueue(MockResponse().setBody("""{"name":"big.bin","path":"big.bin","type":"file","size":2000000,"content":"","encoding":"none"}"""))
        val large = assertThrows(GitHubApiException::class.java) { runBlocking { gitHub.contents(GitHubRepo("acme", "app"), "big.bin", null) } }
        assertThat(large.httpCode).isEqualTo(413)
    }

    @Test
    fun `a path with spaces and a ref with a slash are encoded`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("[]"))
        gitHub.contents(GitHubRepo("acme", "app"), "docs/my notes", "feature/x")
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/app/contents/docs/my%20notes?ref=feature%2Fx")
    }
}
