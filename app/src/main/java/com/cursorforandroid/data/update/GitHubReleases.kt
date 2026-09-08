package com.cursorforandroid.data.update

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.repo.parseIsoMillis
import com.cursorforandroid.domain.AppRelease
import com.cursorforandroid.domain.AppVersion
import com.cursorforandroid.domain.ReleaseAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------------------------------------------------
// GitHub REST shapes (https://docs.github.com/rest/releases/releases#list-releases); only what the updater reads.
// ---------------------------------------------------------------------------------------------------------------------

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val body: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
data class GitHubAssetDto(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("content_type") val contentType: String? = null,
    /** `sha256:<hex>`, computed by GitHub when the asset was uploaded. */
    val digest: String? = null,
)

/** Turns GitHub's release list into the app's releases and picks the one worth offering. Pure functions. */
object ReleaseCatalog {

    /** The APK an installable release must carry; anything else (AAB, mapping, checksums) is not an update. */
    private const val APK_SUFFIX = ".apk"
    private const val CHECKSUMS_ASSET = "SHA256SUMS.txt"
    private val DIGEST = Regex("""^sha256:([0-9a-fA-F]{64})$""")
    /** The row the release workflow writes: `| Signing certificate (SHA-256) | \`ab12…\` |`. */
    private val SIGNING_ROW = Regex("""Signing certificate \(SHA-256\)\s*\|\s*`?([0-9a-fA-F:]{64,95})`?""")
    private val CHECKSUM_LINE = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

    /** Null for a draft, a tag that is not a version, or a release without an APK. */
    fun toRelease(dto: GitHubReleaseDto): AppRelease? {
        if (dto.draft) return null
        val version = AppVersion.parse(dto.tagName) ?: return null
        val apk = dto.assets.filter { it.name.endsWith(APK_SUFFIX, ignoreCase = true) }
            // A release may one day carry several APKs; the plain one named after the version is the universal build.
            .let { apks -> apks.firstOrNull { it.name.equals("cursor-for-android-$version$APK_SUFFIX", ignoreCase = true) } ?: apks.firstOrNull() }
            ?: return null
        return AppRelease(
            tagName = dto.tagName,
            version = version,
            // The tag decides the version; GitHub's flag decides the channel, so a stable tag published as a
            // pre-release stays out of the stable channel until it is promoted.
            isPreRelease = dto.prerelease || version.isPreRelease,
            publishedAtMs = parseIsoMillis(dto.publishedAt),
            htmlUrl = dto.htmlUrl ?: "",
            apk = ReleaseAsset(apk.name, apk.browserDownloadUrl, apk.size, sha256 = apk.digest?.let(::digestHex)),
            checksumsUrl = dto.assets.firstOrNull { it.name.equals(CHECKSUMS_ASSET, ignoreCase = true) }?.browserDownloadUrl,
            signingCertSha256 = signingCertFromNotes(dto.body),
        )
    }

    /**
     * The newest release the installed build should move to, or null when it is current. Pre-releases only count
     * when asked for; the comparison is by versionCode, so a `-rc.1` tag never beats its final build.
     */
    fun newest(releases: List<AppRelease>, installedVersionCode: Int, includePreReleases: Boolean): AppRelease? =
        releases.asSequence()
            .filter { includePreReleases || !it.isPreRelease }
            .filter { it.versionCode > installedVersionCode }
            .maxWithOrNull(compareBy<AppRelease> { it.versionCode }.thenBy { it.publishedAtMs })

    /** `SHA256SUMS.txt` (`sha256sum` output) as file name -> lowercase hex. */
    fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line -> CHECKSUM_LINE.matchEntire(line.trim())?.let { it.groupValues[2].trim() to it.groupValues[1].lowercase() } }
        .toMap()

    /** The signing certificate digest named in the release notes header, normalised to lowercase hex. */
    fun signingCertFromNotes(body: String?): String? {
        val raw = body?.let { SIGNING_ROW.find(it)?.groupValues?.get(1) } ?: return null
        return raw.replace(":", "").lowercase().takeIf { it.length == 64 }
    }

    private fun digestHex(digest: String): String? = DIGEST.matchEntire(digest.trim())?.groupValues?.get(1)?.lowercase()
}

/** A failed check with a message fit for the Settings row. */
class UpdateCheckException(message: String) : IOException(message)

/**
 * The releases of one GitHub repository, read anonymously. Conditional requests keep the periodic check almost
 * free: a `304 Not Modified` carries no body and does not count against GitHub's 60-per-hour anonymous limit.
 */
class GitHubReleasesClient(
    private val client: OkHttpClient,
    /** `owner/name`. */
    private val repo: String,
    private val apiBaseUrl: String = API_BASE_URL,
    private val json: Json = CursorJson,
) {
    sealed interface ReleasesFetch {
        data class Changed(val releases: List<GitHubReleaseDto>, val etag: String?) : ReleasesFetch
        data object Unchanged : ReleasesFetch
    }

    /**
     * The most recent releases, newest first as GitHub orders them. With [etag] from a previous fetch the server
     * answers [ReleasesFetch.Unchanged] when nothing was published, edited or deleted since.
     */
    suspend fun listReleases(etag: String?): ReleasesFetch {
        val request = Request.Builder()
            .url("${apiBaseUrl}repos/$repo/releases?per_page=$PAGE_SIZE")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()
        client.newCall(request).await().use { response ->
            if (response.code == 304) return ReleasesFetch.Unchanged
            if (!response.isSuccessful) throw response.toCheckException()
            val body = withContext(Dispatchers.IO) { response.body?.string() } ?: ""
            val releases = runCatching { json.decodeFromString(ListSerializer(GitHubReleaseDto.serializer()), body) }
                .getOrElse { throw UpdateCheckException("GitHub sent a release list the app couldn't read.") }
            return ReleasesFetch.Changed(releases, response.header("ETag"))
        }
    }

    /** A small text asset such as `SHA256SUMS.txt`. */
    suspend fun text(url: String): String {
        client.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't fetch ${url.substringAfterLast('/')} (${response.code}).")
            return withContext(Dispatchers.IO) { response.body?.string() } ?: ""
        }
    }

    /**
     * Streams [url] into [target] (through a sibling temp file, moved into place only when complete) and returns the
     * lowercase hex SHA-256 of the bytes written. [onProgress] receives (bytes so far, total or -1) as chunks land.
     * Cancelling the coroutine cancels the HTTP call and removes the partial file.
     */
    suspend fun download(url: String, target: File, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }): String {
        val tmp = File(target.parentFile, target.name + ".part")
        val call = client.newCall(Request.Builder().url(url).build())
        try {
            call.await().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code}).")
                val body = response.body ?: throw IOException("Download failed: empty response.")
                return withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val total = body.contentLength()
                    var read = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                read += n
                                onProgress(read, total)
                            }
                        }
                    }
                    if (total >= 0 && read != total) throw IOException("Download ended early ($read of $total bytes).")
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        if (!tmp.renameTo(target)) throw IOException("Couldn't save the download.")
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        } catch (t: Throwable) {
            call.cancel()
            tmp.delete()
            throw t
        }
    }

    private fun Response.toCheckException(): UpdateCheckException = when {
        // GitHub answers 403 or 429 with the remaining quota at zero when the anonymous limit is used up.
        (code == 403 || code == 429) && header("X-RateLimit-Remaining") == "0" ->
            UpdateCheckException("GitHub's limit for anonymous requests was reached. Try again in an hour.")
        code == 404 -> UpdateCheckException("No releases found for $repo on GitHub.")
        else -> UpdateCheckException("GitHub answered with an error ($code).")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            },
        )
        continuation.invokeOnCancellation { cancel() }
    }

    companion object {
        const val API_BASE_URL = "https://api.github.com/"
        private const val API_VERSION = "2022-11-28"
        /** Enough to reach the newest stable release past a run of pre-releases. */
        private const val PAGE_SIZE = 20

        fun releasesPageUrl(repo: String) = "https://github.com/$repo/releases"
    }
}
