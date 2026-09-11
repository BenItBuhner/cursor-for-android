package com.cursorforandroid.data.repo

import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.dto.ArtifactDto
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.api.dto.ListArtifactsResponseDto
import com.cursorforandroid.domain.Artifact
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class ArtifactRepositoryTest {

    private class RecordingApi : FakeCursorApi() {
        val requests = mutableListOf<Pair<String, String>>()
        var response: (String) -> DownloadArtifactResponseDto = { path -> DownloadArtifactResponseDto(url = "https://s3.test/$path?sig=1", expiresAt = null) }
        /** When set, the first artifact asked for waits on it, so a second caller arrives while it is in flight. */
        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun artifactUrl(id: String, path: String): DownloadArtifactResponseDto {
            requests += id to path
            if (path.endsWith("a.png")) gate?.await()
            return response(path)
        }
    }

    private var now = Instant.parse("2026-04-13T18:45:00Z").toEpochMilli()
    private val api = RecordingApi()
    private val repo = ArtifactRepository(api = { api }, now = { now })

    @Test
    fun `the same artifact is resolved once until its url nears expiry`() = runBlocking {
        val first = repo.downloadUrl("bc-1", "artifacts/a.png")
        val second = repo.downloadUrl("bc-1", "artifacts/a.png")
        assertThat(first).isEqualTo("https://s3.test/artifacts/a.png?sig=1")
        assertThat(second).isEqualTo(first)
        assertThat(api.requests).hasSize(1)

        // Another agent or path is a different URL.
        repo.downloadUrl("bc-2", "artifacts/a.png")
        repo.downloadUrl("bc-1", "artifacts/b.png")
        assertThat(api.requests).hasSize(3)

        // Without an expiresAt the documented 15-minute lifetime applies; one minute before it a fresh URL is fetched.
        now += ArtifactRepository.DEFAULT_TTL_MS - ArtifactRepository.MIN_REMAINING_MS + 1
        repo.downloadUrl("bc-1", "artifacts/a.png")
        assertThat(api.requests).hasSize(4)
    }

    @Test
    fun `an explicit expiresAt drives the refresh and invalidate forces one`() = runBlocking {
        api.response = { path -> DownloadArtifactResponseDto(url = "https://s3.test/$path?sig=${api.requests.size}", expiresAt = "2026-04-13T18:50:00.000Z") }
        assertThat(repo.downloadUrl("bc-1", "artifacts/a.png")).endsWith("sig=1")
        now += 3 * 60_000 // 18:48 — two minutes left, still fine
        assertThat(repo.downloadUrl("bc-1", "artifacts/a.png")).endsWith("sig=1")
        now += 90_000 // 18:49:30 — under a minute left
        assertThat(repo.downloadUrl("bc-1", "artifacts/a.png")).endsWith("sig=2")

        repo.invalidate("bc-1", "artifacts/a.png")
        assertThat(repo.downloadUrl("bc-1", "artifacts/a.png")).endsWith("sig=3")
    }

    @Test
    fun `the cache is bounded, so browsing many agents' artifacts cannot grow it without limit`() = runBlocking {
        val bounded = ArtifactRepository(api = { api }, now = { now }, maxEntries = 4)
        repeat(4) { bounded.downloadUrl("bc-1", "artifacts/$it.png") }
        assertThat(api.requests).hasSize(4)

        // The four are all still cached; a fifth evicts the one used longest ago, which is then re-resolved.
        repeat(4) { bounded.downloadUrl("bc-1", "artifacts/$it.png") }
        assertThat(api.requests).hasSize(4)
        bounded.downloadUrl("bc-1", "artifacts/4.png")
        bounded.downloadUrl("bc-1", "artifacts/0.png")
        assertThat(api.requests).hasSize(6)
        assertThat(api.requests.last()).isEqualTo("bc-1" to "artifacts/0.png")

        // An entry read after it expired is dropped and resolved again, evicting nothing live in the process.
        now += ArtifactRepository.DEFAULT_TTL_MS
        bounded.downloadUrl("bc-1", "artifacts/0.png")
        repeat(4) { bounded.downloadUrl("bc-1", "artifacts/${it + 5}.png") }
        assertThat(api.requests).hasSize(11)
    }

    @Test
    fun `two loaders asking for the same artifact at once spend one request`() = runBlocking {
        api.gate = CompletableDeferred()
        val both = listOf(
            async { repo.downloadUrl("bc-1", "artifacts/a.png") },
            async { repo.downloadUrl("bc-1", "artifacts/a.png") },
        )
        val other = async { repo.downloadUrl("bc-1", "artifacts/b.png") }
        yield()

        api.gate!!.complete(Unit)
        both.forEach { assertThat(it.await()).isEqualTo("https://s3.test/artifacts/a.png?sig=1") }
        assertThat(other.await()).isEqualTo("https://s3.test/artifacts/b.png?sig=1")
        assertThat(api.requests.count { it.second == "artifacts/a.png" }).isEqualTo(1)
    }

    @Test
    fun `an empty url is an error rather than a blank image request`() {
        api.response = { DownloadArtifactResponseDto(url = "") }
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.downloadUrl("bc-1", "artifacts/a.png") } }
    }

    private class ListingApi : FakeCursorApi() {
        var listings = 0
        var items = listOf(
            ArtifactDto("artifacts/screenshots/home.png", 20_480, "2026-04-13T18:40:00Z"),
            ArtifactDto("artifacts/demo.mp4", 4_194_304, "2026-04-13T18:44:00Z"),
            ArtifactDto("artifacts/notes.md", 512, null),
            ArtifactDto("", 1, null),
        )
        override suspend fun artifacts(id: String): ListArtifactsResponseDto {
            listings++
            return ListArtifactsResponseDto(items)
        }
    }

    @Test
    fun `the listing is typed, newest first, and read once until it ages`() = runBlocking {
        val api = ListingApi()
        val repo = ArtifactRepository(api = { api }, now = { now })

        val listed = repo.list("bc-1")
        assertThat(listed.map { it.name }).containsExactly("demo.mp4", "home.png", "notes.md").inOrder()
        assertThat(listed.map { it.kind }).containsExactly(Artifact.Kind.Video, Artifact.Kind.Image, Artifact.Kind.Markdown).inOrder()
        assertThat(listed[1].sizeBytes).isEqualTo(20_480L)
        assertThat(listed[1].updatedAtMillis).isEqualTo(Instant.parse("2026-04-13T18:40:00Z").toEpochMilli())
        assertThat(listed[1].vmPath).isEqualTo("/opt/cursor/artifacts/screenshots/home.png")
        assertThat(listed[2].updatedAtMillis).isNull()

        repo.list("bc-1")
        assertThat(api.listings).isEqualTo(1)
        assertThat(repo.lastListing("bc-1")).isEqualTo(listed)

        now += ArtifactRepository.LISTING_TTL_MS
        repo.list("bc-1")
        assertThat(api.listings).isEqualTo(2)
        repo.list("bc-1", force = true)
        assertThat(api.listings).isEqualTo(3)
        assertThat(repo.lastListing("bc-2")).isNull()
    }
}
