package com.cursorforandroid.data.update

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.AppVersion
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test

class ReleaseCatalogTest {

    private fun releases(json: String) = CursorJson.decodeFromString(ListSerializer(GitHubReleaseDto.serializer()), json).mapNotNull(ReleaseCatalog::toRelease)

    @Test
    fun `a GitHub release maps to the APK asset, its digest, the checksums file and the signing certificate`() {
        val release = releases(GitHubFixtures.releasesJson).first { it.tagName == "v0.2.0" }
        assertThat(release.version).isEqualTo(AppVersion(0, 2, 0))
        assertThat(release.versionCode).isEqualTo(20099)
        assertThat(release.isPreRelease).isFalse()
        assertThat(release.publishedAtMs).isEqualTo(1_788_800_000_000L)
        assertThat(release.htmlUrl).isEqualTo("https://github.com/BenItBuhner/cursor-for-android/releases/tag/v0.2.0")
        assertThat(release.apk.name).isEqualTo("cursor-for-android-0.2.0.apk")
        assertThat(release.apk.url).isEqualTo("https://github.com/BenItBuhner/cursor-for-android/releases/download/v0.2.0/cursor-for-android-0.2.0.apk")
        assertThat(release.apk.sizeBytes).isEqualTo(2_300_000L)
        assertThat(release.apk.sha256).isEqualTo(GitHubFixtures.APK_020_SHA256)
        assertThat(release.checksumsUrl).isEqualTo("https://github.com/BenItBuhner/cursor-for-android/releases/download/v0.2.0/SHA256SUMS.txt")
        assertThat(release.signingCertSha256).isEqualTo(GitHubFixtures.RELEASE_CERT_SHA256)
    }

    @Test
    fun `drafts, non-version tags and releases without an APK are skipped`() {
        val mapped = releases(GitHubFixtures.releasesJson)
        assertThat(mapped.map { it.tagName }).containsExactly("v0.3.0-rc.1", "v0.2.0", "v0.1.0").inOrder()
        // The debug-signed v0.1.0 release has no certificate row; the rc has no digest on its asset.
        assertThat(mapped.first { it.tagName == "v0.1.0" }.signingCertSha256).isNull()
        assertThat(mapped.first { it.tagName == "v0.3.0-rc.1" }.apk.sha256).isNull()
        assertThat(mapped.first { it.tagName == "v0.3.0-rc.1" }.isPreRelease).isTrue()
    }

    @Test
    fun `the newest release above the installed build wins, pre-releases only when asked for`() {
        val mapped = releases(GitHubFixtures.releasesJson)
        val installed010 = AppVersion.parse("0.1.0")!!.versionCode
        assertThat(ReleaseCatalog.newest(mapped, installed010, includePreReleases = false)?.tagName).isEqualTo("v0.2.0")
        assertThat(ReleaseCatalog.newest(mapped, installed010, includePreReleases = true)?.tagName).isEqualTo("v0.3.0-rc.1")
        // Already on 0.2.0: nothing stable is newer, only the rc.
        val installed020 = AppVersion.parse("0.2.0")!!.versionCode
        assertThat(ReleaseCatalog.newest(mapped, installed020, includePreReleases = false)).isNull()
        assertThat(ReleaseCatalog.newest(mapped, installed020, includePreReleases = true)?.tagName).isEqualTo("v0.3.0-rc.1")
        // A dev build of 0.2.0 sits below the 0.2.0 release and is offered it.
        val dev = AppVersion.parse("0.2.0-dev.7+gdeadbee")!!.versionCode
        assertThat(ReleaseCatalog.newest(mapped, dev, includePreReleases = false)?.tagName).isEqualTo("v0.2.0")
        // Beyond everything published.
        assertThat(ReleaseCatalog.newest(mapped, AppVersion.parse("0.3.0")!!.versionCode, includePreReleases = true)).isNull()
    }

    @Test
    fun `a stable tag published as a GitHub pre-release stays out of the stable channel`() {
        val json = """[{"tag_name":"v0.9.0","prerelease":true,"assets":[{"name":"cursor-for-android-0.9.0.apk","size":1,"browser_download_url":"https://x/a.apk"}]}]"""
        val release = releases(json).single()
        assertThat(release.isPreRelease).isTrue()
        assertThat(ReleaseCatalog.newest(listOf(release), 0, includePreReleases = false)).isNull()
        assertThat(ReleaseCatalog.newest(listOf(release), 0, includePreReleases = true)).isEqualTo(release)
    }

    @Test
    fun `among several APKs the one named after the version is preferred`() {
        val json = """[{"tag_name":"v1.0.0","assets":[
            {"name":"cursor-for-android-1.0.0-arm64.apk","size":1,"browser_download_url":"https://x/arm64.apk"},
            {"name":"cursor-for-android-1.0.0.apk","size":2,"browser_download_url":"https://x/universal.apk"}]}]"""
        assertThat(releases(json).single().apk.url).isEqualTo("https://x/universal.apk")
    }

    @Test
    fun `SHA256SUMS lines parse in sha256sum's format`() {
        val sums = ReleaseCatalog.parseChecksums(
            """
            |${GitHubFixtures.APK_020_SHA256.uppercase()}  cursor-for-android-0.2.0.apk
            |0000000000000000000000000000000000000000000000000000000000000000 *cursor-for-android-0.2.0.aab
            |not a checksum line
            |
            """.trimMargin(),
        )
        assertThat(sums).containsExactly(
            "cursor-for-android-0.2.0.apk", GitHubFixtures.APK_020_SHA256,
            "cursor-for-android-0.2.0.aab", "0".repeat(64),
        )
    }

    @Test
    fun `the signing certificate row is read with or without colons`() {
        val colons = (0 until 32).joinToString(":") { "%02X".format(it) }
        assertThat(ReleaseCatalog.signingCertFromNotes("| Signing certificate (SHA-256) | `$colons` |"))
            .isEqualTo((0 until 32).joinToString("") { "%02x".format(it) })
        assertThat(ReleaseCatalog.signingCertFromNotes("| Signing | **Debug key** - release signing secrets were not configured |")).isNull()
        assertThat(ReleaseCatalog.signingCertFromNotes(null)).isNull()
    }
}
