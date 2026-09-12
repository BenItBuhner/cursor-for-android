package com.cursorforandroid.data.api

import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RepoContents
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

/** Origin's documented reads, against a fake `api.cursor.com/v1/origin`, and the refusal when no token exists. */
class OriginApiTest {

    private val server = MockWebServer()
    private var token: String? = "origin-user-token"
    private lateinit var origin: OriginApi
    private val ref = OriginPullRequestRef(OriginRepo("acme", "rocket"), 17)

    @Before
    fun setUp() {
        server.start()
        origin = OriginApi(OkHttpClient(), tokenProvider = { token }, baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `Origin URLs are recognised, GitHub's are not`() {
        assertThat(OriginRepo.parse("https://origin.cursor.com/acme/rocket")).isEqualTo(OriginRepo("acme", "rocket"))
        assertThat(OriginRepo.parse("https://origin.cursor.com/git/acme/rocket.git")).isEqualTo(OriginRepo("acme", "rocket"))
        assertThat(OriginRepo.parse("https://github.com/acme/rocket")).isNull()
        assertThat(OriginPullRequestRef.parse("https://origin.cursor.com/acme/rocket/pulls/17")).isEqualTo(ref)
        assertThat(OriginPullRequestRef.parse("https://origin.cursor.com/acme/rocket/pull/17/files")).isEqualTo(ref)
        assertThat(OriginPullRequestRef.parse("https://github.com/acme/rocket/pull/17")).isNull()
        assertThat(ScmHost.of("https://origin.cursor.com/acme/rocket")).isEqualTo(ScmHost.Origin)
        assertThat(ScmHost.of("https://gitlab.com/acme/rocket")).isEqualTo(ScmHost.Other)
    }

    @Test
    fun `without a token nothing is asked of Origin`() {
        token = null
        assertThrows(OriginTokenMissingException::class.java) { runBlocking { origin.pullRequest(ref) } }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the pull request is read with the bearer token and Origin's string-encoded numbers`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"pr_1","number":"17","state":"open","draft":false,"merged":false,"title":"Add launch telemetry","body":"Adds telemetry.",
                    "head":{"ref":"refs/heads/add-telemetry","sha":"9a41"},"base":{"ref":"main","sha":"3b1f"},
                    "author":{"user":{"id":"user_1","email":"jane@acme.dev"}},"createdAt":"2026-08-01T09:30:00Z","updatedAt":"2026-08-02T14:45:00Z",
                    "additions":128,"deletions":46,"changedFiles":5,"labels":[{"id":"lbl","name":"bug"}]}""",
            ),
        )
        val details = origin.pullRequest(ref)
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/repos/acme/rocket/pulls/17")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer origin-user-token")
        assertThat(details.host).isEqualTo(ScmHost.Origin)
        assertThat(details.number).isEqualTo(17)
        assertThat(details.state).isEqualTo(PullRequestState.Open)
        assertThat(details.author).isEqualTo("jane@acme.dev")
        assertThat(details.headRef).isEqualTo("add-telemetry")
        assertThat(details.lineStats).isEqualTo("+128 -46")
        assertThat(details.labels).containsExactly("bug")
        assertThat(details.url).isEqualTo("https://origin.cursor.com/acme/rocket/pulls/17")

        server.enqueue(MockResponse().setBody("""{"number":"17","state":"closed","merged":true,"title":"x"}"""))
        assertThat(origin.pullRequest(ref).state).isEqualTo(PullRequestState.Merged)
    }

    @Test
    fun `files and contents follow the documented shapes`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"files":[{"filename":"src/telemetry.ts","status":"modified","additions":6,"deletions":3,"changes":9,"patch":"@@ -12,6 +12,9 @@\n import x"}]}"""))
        val files = origin.pullRequestFiles(ref)
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/rocket/pulls/17/files?pageSize=100")
        assertThat(files.single().status).isEqualTo(ChangedFileStatus.Modified)
        assertThat(files.single().patch).startsWith("@@ -12,6 +12,9 @@")

        server.enqueue(MockResponse().setBody("""{"type":"file","encoding":"base64","size":"24","name":"telemetry.ts","path":"src/telemetry.ts","sha":"c9d8","content":"Y29uc29sZS5sb2coImxhdW5jaCIpOwo="}"""))
        val file = (origin.contents(OriginRepo("acme", "rocket"), "src/telemetry.ts", "main") as RepoContents.File).file
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/rocket/contents?path=src%2Ftelemetry.ts&ref=main")
        assertThat(file.text).isEqualTo("console.log(\"launch\");\n")
        assertThat(file.sizeBytes).isEqualTo(24L)

        server.enqueue(MockResponse().setBody("""{"type":"dir","name":"src","path":"src","entries":[{"type":"file","name":"b.ts","path":"src/b.ts","size":"10"},{"type":"dir","name":"lib","path":"src/lib"}]}"""))
        val dir = origin.contents(OriginRepo("acme", "rocket"), "src", null) as RepoContents.Directory
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/rocket/contents?path=src")
        assertThat(dir.entries.map { it.name }).containsExactly("lib", "b.ts").inOrder()
        assertThat(dir.entries.first().isDirectory).isTrue()
    }

    @Test
    fun `a refused token is an unauthorized error`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthenticated"}"""))
        val error = assertThrows(OriginApiException::class.java) { runBlocking { origin.pullRequest(ref) } }
        assertThat(error.isUnauthorized).isTrue()
    }
}
