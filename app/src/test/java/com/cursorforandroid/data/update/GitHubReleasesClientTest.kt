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
    private var now = 1_788_900_000_000L // 2026-09-08T20:40:00Z

    @Before
    fun setUp() {
        server.start()
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        client = GitHubReleasesClient(
            http,
            GitHubFixtures.OWNER_REPO,
            apiBaseUrl = server.url("/").toString(),
            now = { now },
            // A device with room; the tests that are about running out build their own client.
            freeSpace = { Long.MAX_VALUE / 2 },
        )
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
        assertThat(request.path).isEqualTo("/repos/BenItBuhner/cursor-for-android/releases?per_page=100")
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

        // The wait has to pass before the next request is allowed out, so the clock moves on for the cases below.
        now += 60 * 60 * 1000L
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(runCatching { client.listReleases(null) }.exceptionOrNull()!!.message).contains("No releases found")

        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))
        assertThat(runCatching { client.listReleases(null) }.exceptionOrNull()!!.message).contains("couldn't read")
    }

    @Test
    fun `the reset GitHub reports is waited out instead of asked over`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("X-RateLimit-Remaining", "0")
                .setHeader("X-RateLimit-Reset", ((now + 25 * 60_000L) / 1000L).toString()),
        )
        val limited = runCatching { client.listReleases(null) }.exceptionOrNull() as UpdateCheckException
        assertThat(limited.message).contains("Try again in 25 minutes")
        assertThat(limited.retryAfterMillis).isEqualTo(25 * 60_000L)

        // Nothing is enqueued: another check inside the window must not reach the network at all.
        now += 24 * 60_000L
        val again = runCatching { client.listReleases(null) }.exceptionOrNull() as UpdateCheckException
        assertThat(again.message).contains("Try again in a minute")
        assertThat(server.requestCount).isEqualTo(1)

        now += 60_000L
        server.enqueue(MockResponse().setBody(GitHubFixtures.releasesJson))
        assertThat(client.listReleases(null)).isInstanceOf(GitHubReleasesClient.ReleasesFetch.Changed::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a secondary limit's Retry-After is honoured the same way`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val limited = runCatching { client.listReleases(null) }.exceptionOrNull() as UpdateCheckException
        assertThat(limited.retryAfterMillis).isEqualTo(120_000L)
        assertThat(limited.message).contains("Try again in 2 minutes")
    }

    // ---- pagination ---------------------------------------------------------------------------------------------

    /** [tags] as a release list page; every entry carries an APK so only the channel decides eligibility. */
    private fun page(vararg tags: Pair<String, Boolean>): String = tags.joinToString(",", "[", "]") { (tag, prerelease) ->
        val version = tag.removePrefix("v")
        """{"tag_name":"$tag","prerelease":$prerelease,"assets":[
            {"name":"cursor-for-android-$version.apk","size":1,"browser_download_url":"https://x/$tag.apk"}]}"""
    }

    private fun nextLink(path: String) = """<${server.url(path)}>; rel="next", <${server.url(path)}>; rel="last""""

    /** The stable channel: a release counts when it is not a pre-release. */
    private val stableIsEligible: (List<GitHubReleaseDto>) -> Boolean =
        { seen -> seen.mapNotNull(ReleaseCatalog::toRelease).any { !it.isPreRelease } }

    @Test
    fun `a stable release on the second page is reached past a first page of pre-releases`() = runBlocking {
        val preReleases = (1..20).map { "v0.3.0-rc.$it" to true }.toTypedArray()
        server.enqueue(MockResponse().setHeader("ETag", "W/\"p1\"").setHeader("Link", nextLink("/page2")).setBody(page(*preReleases)))
        server.enqueue(MockResponse().setHeader("ETag", "W/\"p2\"").setBody(page("v0.2.0" to false)))

        val fetch = client.listReleases(etag = null, hasEligible = stableIsEligible) as GitHubReleasesClient.ReleasesFetch.Changed

        assertThat(fetch.releases).hasSize(21)
        assertThat(fetch.releases.last().tagName).isEqualTo("v0.2.0")
        // The kept ETag is the first page's: that is the one a later conditional request can be made with.
        assertThat(fetch.etag).isEqualTo("W/\"p1\"")
        assertThat(server.takeRequest().path).isEqualTo("/repos/${GitHubFixtures.OWNER_REPO}/releases?per_page=100")
        val second = server.takeRequest()
        assertThat(second.path).isEqualTo("/page2")
        // Only the first page is conditional; the pages behind it are new to us.
        assertThat(second.getHeader("If-None-Match")).isNull()
    }

    @Test
    fun `an eligible first page is the only page read, and a spent page budget stops the walk`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Link", nextLink("/page2")).setBody(page("v0.2.0" to false)))
        client.listReleases(etag = null, hasEligible = stableIsEligible)
        assertThat(server.requestCount).isEqualTo(1)

        // Nothing installable anywhere: three pages, then it gives up rather than walking the repository.
        repeat(4) { server.enqueue(MockResponse().setHeader("Link", nextLink("/page${it + 2}")).setBody(page("v0.3.0-rc.${it + 1}" to true))) }
        val fetch = client.listReleases(etag = null, hasEligible = stableIsEligible) as GitHubReleasesClient.ReleasesFetch.Changed
        assertThat(server.requestCount).isEqualTo(4)
        assertThat(fetch.releases).hasSize(3)
    }

    @Test
    fun `a next link pointing somewhere else is not followed`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Link", """<https://evil.example/releases?page=2>; rel="next"""")
                .setBody(page("v0.3.0-rc.1" to true)),
        )
        val fetch = client.listReleases(etag = null, hasEligible = stableIsEligible) as GitHubReleasesClient.ReleasesFetch.Changed
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(fetch.releases).hasSize(1)
    }

    @Test
    fun `a 304 for the first page ends the fetch without paging`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304).setHeader("Link", nextLink("/page2")))
        assertThat(client.listReleases(etag = "W/\"p1\"", hasEligible = stableIsEligible))
            .isEqualTo(GitHubReleasesClient.ReleasesFetch.Unchanged)
        assertThat(server.requestCount).isEqualTo(1)
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
        assertThat(progress.last()).isEqualTo(bytes.size.toLong() to bytes.size.toLong())
        assertThat(progress.map { it.first }).isInOrder()
    }

    @Test
    fun `a release that declares an implausible APK size is not downloaded at all`() = runBlocking {
        val target = File(folder.root, "big.apk")
        val error = runCatching {
            client.download(server.url("/dl").toString(), target, expectedBytes = GitHubReleasesClient.MAX_APK_BYTES + 1)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(error!!).hasMessageThat().contains("far larger than this app has ever been")
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `a response that announces more than an APK could be is refused before it is written`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(Buffer().write(ByteArray(1024)))
                .setHeader("Content-Length", GitHubReleasesClient.MAX_APK_BYTES + 1),
        )
        val target = File(folder.root, "big.apk")
        val error = runCatching { client.download(server.url("/dl").toString(), target) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(folder.root.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `a body that keeps coming past the size the release declared is stopped and thrown away`() = runBlocking {
        // Chunked, so nothing announces the length: only the declared size bounds it.
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(400_000)), 16 * 1024))
        val target = File(folder.root, "over.apk")
        val error = runCatching { client.download(server.url("/dl").toString(), target, expectedBytes = 100_000L) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(error!!).hasMessageThat().contains("larger than the release says")
        assertThat(folder.root.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `a body that stops short of the size the release declared is a failure`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(1_000)), 512))
        val target = File(folder.root, "short.apk")
        val error = runCatching { client.download(server.url("/dl").toString(), target, expectedBytes = 100_000L) }.exceptionOrNull()
        assertThat(error!!).hasMessageThat().contains("the release says 100000")
        assertThat(folder.root.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `a device without room for the update says so instead of filling itself`() = runBlocking {
        val cramped = GitHubReleasesClient(
            OkHttpClient(),
            GitHubFixtures.OWNER_REPO,
            apiBaseUrl = server.url("/").toString(),
            now = { now },
            freeSpace = { 40L * 1024 * 1024 },
        )
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1024))))
        val target = File(folder.root, "roomless.apk")
        val error = runCatching { cramped.download(server.url("/dl").toString(), target, expectedBytes = 20L * 1024 * 1024) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(error!!).hasMessageThat().contains("enough free space")
        assertThat(folder.root.listFiles()!!.toList()).isEmpty()
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
