package com.cursorforandroid.data.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GitHubReleasesClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var client: GitHubReleasesClient

    @Before
    fun setUp() {
        server.start()
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        client = GitHubReleasesClient(http, GitHubFixtures.OWNER_REPO, apiBaseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the release list is requested with GitHub's headers and answered conditionally the second time`() = runBlocking {
        server.enqueue(MockResponse().setBody(GitHubFixtures.releasesJson).setHeader("ETag", "W/\"abc\""))
        server.enqueue(MockResponse().setResponseCode(304))

        val first = client.listReleases(etag = null) as GitHubReleasesClient.ReleasesFetch.Changed
        assertThat(first.releases.map { it.tagName }).containsExactly("v0.3.0-rc.1", "v0.3.0", "screenshots-2026-09", "v0.2.0", "v0.1.1", "v0.1.0").inOrder()
        assertThat(first.etag).isEqualTo("W/\"abc\"")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/repos/BenItBuhner/cursor-for-android/releases?per_page=20")
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github+json")
        assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28")
        assertThat(request.getHeader("If-None-Match")).isNull()
        assertThat(request.getHeader("Authorization")).isNull()

        assertThat(client.listReleases(etag = first.etag)).isEqualTo(GitHubReleasesClient.ReleasesFetch.Unchanged)
        assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("W/\"abc\"")
    }

    @Test
    fun `an exhausted anonymous quota and a missing repository read as clear messages`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setBody("""{"message":"API rate limit exceeded"}"""))
        val rateLimited = runCatching { client.listReleases(null) }.exceptionOrNull()
        assertThat(rateLimited).isInstanceOf(UpdateCheckException::class.java)
        assertThat(rateLimited!!.message).contains("Try again in an hour")

        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(runCatching { client.listReleases(null) }.exceptionOrNull()!!.message).contains("No releases found")

        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))
        assertThat(runCatching { client.listReleases(null) }.exceptionOrNull()!!.message).contains("couldn't read")
    }

    @Test
    fun `a download is streamed to a file, hashed on the way and reported as it progresses`() = runBlocking {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val target = File(folder.root, "20199.apk")
        val progress = mutableListOf<Pair<Long, Long>>()

        val sha256 = client.download(server.url("/dl/app.apk").toString(), target) { read, total -> progress += read to total }

        assertThat(sha256).isEqualTo(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        assertThat(target.readBytes()).isEqualTo(bytes)
        assertThat(File(folder.root, "20199.apk.part").exists()).isFalse()
        assertThat(progress.last()).isEqualTo(bytes.size.toLong() to bytes.size.toLong())
        assertThat(progress.map { it.first }).isInOrder()
    }

    @Test
    fun `a download that ends early fails and leaves nothing behind`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(50_000))).setHeader("Content-Length", "80000").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END))
        val target = File(folder.root, "x.apk")
        val error = runCatching { client.download(server.url("/dl").toString(), target) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(folder.root.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `cancelling a download cancels the call and removes the partial file`() = runBlocking {
        val body = Buffer().write(ByteArray(2_000_000))
        server.enqueue(MockResponse().setBody(body).throttleBody(64 * 1024, 50, TimeUnit.MILLISECONDS))
        val target = File(folder.root, "y.apk")
        val started = CompletableDeferred<Unit>()
        val job = async { client.download(server.url("/dl").toString(), target) { _, _ -> started.complete(Unit) } }
        withTimeout(5_000) { started.await() }
        job.cancel()
        runCatching { job.await() }
        // The .part file is removed synchronously by the cancellation path; give the IO thread a beat to unwind.
        withTimeout(5_000) { while (folder.root.listFiles()!!.isNotEmpty()) delay(20) }
        assertThat(target.exists()).isFalse()
    }
}
