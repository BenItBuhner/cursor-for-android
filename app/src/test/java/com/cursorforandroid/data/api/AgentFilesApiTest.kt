package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.RepoEntry
import com.cursorforandroid.domain.WorkspaceTree
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
import java.util.Base64

/** The agent's VM reads of `aiserver.v1.BackgroundComposerService` over Connect JSON, against a fake api2 that also plays the token exchange. */
class AgentFilesApiTest {

    private val server = MockWebServer()
    private lateinit var api: AgentFilesApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = AgentFilesApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the workspace is listed by relative path, cleaned and deduplicated, and walked as a tree`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"relativePaths":["app/build.gradle.kts","/app/src/main/Main.kt","README.md","app/build.gradle.kts","  ","docs/a/b.md"]}"""))

        val tree = api.listFiles("bc-1")

        assertThat(tree.paths).containsExactly("app/build.gradle.kts", "app/src/main/Main.kt", "README.md", "docs/a/b.md").inOrder()
        assertThat(tree.list("")).containsExactly(
            RepoEntry("app", "app", isDirectory = true),
            RepoEntry("docs", "docs", isDirectory = true),
            RepoEntry("README.md", "README.md", isDirectory = false),
        ).inOrder()
        assertThat(tree.list("app")).containsExactly(
            RepoEntry("src", "app/src", isDirectory = true),
            RepoEntry("build.gradle.kts", "app/build.gradle.kts", isDirectory = false),
        ).inOrder()
        assertThat(tree.list("app/src/main")).containsExactly(RepoEntry("Main.kt", "app/src/main/Main.kt", isDirectory = false))
        assertThat(tree.list("nowhere")).isEmpty()
        assertThat(tree.contains("README.md")).isTrue()
        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListWorkspaceFiles")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer s")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(request.json()["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-1")
    }

    @Test
    fun `a file's bytes come back base64-encoded, in either alphabet, and an empty answer is an empty file`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        val text = "fun main() = println(\"hi\")\n"
        server.enqueue(MockResponse().setBody("""{"content":"${Base64.getEncoder().encodeToString(text.toByteArray())}"}"""))
        server.enqueue(MockResponse().setBody("""{"content":"${Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0x3E))}"}"""))
        server.enqueue(MockResponse().setBody("{}"))

        assertThat(api.readFile("bc-1", "src/Main.kt").toString(Charsets.UTF_8)).isEqualTo(text)
        assertThat(api.readFile("bc-1", "bin").toList()).containsExactly(0xFB.toByte(), 0xFF.toByte(), 0x3E.toByte()).inOrder()
        assertThat(api.readFile("bc-1", "empty")).isEmpty()
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ReadBinaryFile")
        assertThat(request.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "path", "src/Main.kt")
    }

    @Test
    fun `the branch diff rebuilds each file's patch from its hunks and reads the status off git's from and to`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"branchName":"cursor/panel","baseBranch":"main","diffs":[
                     {"path":"app/A.kt","originalContent":"a\n","modifiedContent":"b\n","gitDiff":{"diffs":[{"from":"a/app/A.kt","to":"b/app/A.kt","added":1,"removed":1,"chunks":[{"content":"@@ -1 +1 @@","lines":["-a","+b"],"oldStart":1,"oldLines":1,"newStart":1,"newLines":1}]}]}},
                     {"path":"app/New.kt","originalContent":"","modifiedContent":"new\n","gitDiff":{"diffs":[{"from":"/dev/null","to":"b/app/New.kt","added":1,"removed":0,"chunks":[{"content":"@@ -0,0 +1 @@","lines":["+new"]}]}]}},
                     {"path":"app/Gone.kt","gitDiff":{"diffs":[{"from":"a/app/Gone.kt","to":"/dev/null","added":0,"removed":2,"chunks":[{"lines":["-x","-y"],"oldStart":1,"oldLines":2,"newStart":0,"newLines":0}]}]}},
                     {"path":"app/Renamed.kt","gitDiff":{"diffs":[{"from":"a/app/Old.kt","to":"b/app/Renamed.kt","added":0,"removed":0,"chunks":[]}]}},
                     {"path":"assets/logo.png","gitDiff":{"diffs":[]}},
                     {"path":""}
                   ]}""",
            ),
        )

        val diff = api.diffDetails("bc-1")

        assertThat(diff.branchName).isEqualTo("cursor/panel")
        assertThat(diff.baseBranch).isEqualTo("main")
        assertThat(diff.files.map { it.path }).containsExactly("app/A.kt", "app/New.kt", "app/Gone.kt", "app/Renamed.kt", "assets/logo.png").inOrder()
        val modified = diff.files[0]
        assertThat(modified.status).isEqualTo(ChangedFileStatus.Modified)
        assertThat(modified.patch).isEqualTo("@@ -1 +1 @@\n-a\n+b")
        assertThat(modified.additions).isEqualTo(1)
        assertThat(modified.deletions).isEqualTo(1)
        assertThat(modified.originalContent).isEqualTo("a\n")
        assertThat(modified.modifiedContent).isEqualTo("b\n")
        assertThat(diff.files[1].status).isEqualTo(ChangedFileStatus.Added)
        assertThat(diff.files[1].patch).isEqualTo("@@ -0,0 +1 @@\n+new")
        val gone = diff.files[2]
        assertThat(gone.status).isEqualTo(ChangedFileStatus.Removed)
        // A hunk without its header gets one built from its line numbers.
        assertThat(gone.patch).isEqualTo("@@ -1,2 +0,0 @@\n-x\n-y")
        assertThat(gone.deletions).isEqualTo(2)
        val renamed = diff.files[3]
        assertThat(renamed.status).isEqualTo(ChangedFileStatus.Renamed)
        assertThat(renamed.previousPath).isEqualTo("app/Old.kt")
        assertThat(renamed.patch).isNull()
        // No hunks and no contents: a binary; the row says so instead of opening onto nothing.
        assertThat(diff.files[4].patch).isNull()
        assertThat(diff.files[4].status).isEqualTo(ChangedFileStatus.Modified)
        assertThat(diff.additions).isEqualTo(2)
        assertThat(diff.deletions).isEqualTo(3)
        assertThat(diff.files[0].asChangedFile().patch).isEqualTo(modified.patch)
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetBackgroundComposerDiffDetails")
        assertThat(request.json()["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-1")
    }

    @Test
    fun `a diff that only carries git hunks still lists its files, and an empty diff is empty`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"branchName":"b","baseBranch":"main","gitDiffs":[{"diffs":[{"from":"a/x.txt","to":"b/x.txt","added":1,"removed":0,"chunks":[{"content":"@@ -1 +1,2 @@","lines":[" a","+b"]}]}]}]}"""))
        server.enqueue(MockResponse().setBody("""{"branchName":"b","baseBranch":"main"}"""))

        val fromHunks = api.diffDetails("bc-1")
        assertThat(fromHunks.files.single().path).isEqualTo("x.txt")
        assertThat(fromHunks.files.single().patch).isEqualTo("@@ -1 +1,2 @@\n a\n+b")
        assertThat(api.diffDetails("bc-1").isEmpty).isTrue()
    }

    @Test
    fun `a refusal surfaces as the Connect error it was`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"unimplemented","message":"Error"}"""))

        val error = runCatching { api.listFiles("bc-1") }.exceptionOrNull() as ConnectRpcException
        assertThat(error.httpCode).isEqualTo(404)
        assertThat(error.code).isEqualTo("unimplemented")
    }

    @Test
    fun `the tree keeps a file and a directory of the same name apart and ignores blank paths`() {
        val tree = WorkspaceTree(listOf("a", "a/b.txt", "", "/", "c/"))
        assertThat(tree.list("")).containsExactly(
            RepoEntry("a", "a", isDirectory = true),
            RepoEntry("a", "a", isDirectory = false),
            RepoEntry("c", "c", isDirectory = false),
        ).inOrder()
        assertThat(tree.list("a")).containsExactly(RepoEntry("b.txt", "a/b.txt", isDirectory = false))
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
