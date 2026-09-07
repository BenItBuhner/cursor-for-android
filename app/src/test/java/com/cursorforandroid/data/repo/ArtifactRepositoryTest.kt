package com.cursorforandroid.data.repo

import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class ArtifactRepositoryTest {

    private class RecordingApi : FakeCursorApi() {
        val requests = mutableListOf<Pair<String, String>>()
        var response: (String) -> DownloadArtifactResponseDto = { path -> DownloadArtifactResponseDto(url = "https://s3.test/$path?sig=1", expiresAt = null) }

        override suspend fun artifactUrl(id: String, path: String): DownloadArtifactResponseDto {
            requests += id to path
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
    fun `an empty url is an error rather than a blank image request`() {
        api.response = { DownloadArtifactResponseDto(url = "") }
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.downloadUrl("bc-1", "artifacts/a.png") } }
    }
}
