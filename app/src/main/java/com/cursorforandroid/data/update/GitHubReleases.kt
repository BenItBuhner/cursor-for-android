package com.cursorforandroid.data.update

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.repo.parseIsoMillis
import com.cursorforandroid.domain.AppRelease
import com.cursorforandroid.domain.AppVersion
import com.cursorforandroid.domain.ReleaseAsset
import com.cursorforandroid.util.AppClock
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

/**
 * A failed check with a message fit for the Settings row. [retryAfterMillis] is how long GitHub said to wait, when
 * it said so; the client honours it itself, and it is here so a caller can say when rather than guess.
 */
class UpdateCheckException(message: String, val retryAfterMillis: Long? = null) : IOException(message)

/**
 * The releases of one GitHub repository, read anonymously.
 *
 * Conditional requests keep the periodic check cheap: a `304 Not Modified` carries no body to transfer or parse.
 * They are not free, though — GitHub only exempts a conditional request from the rate limit when it is
 * authenticated, and these are not, so an anonymous `304` still spends one of the 60 requests an hour that IP
 * address gets. When the quota runs out GitHub says when it frees up (`X-RateLimit-Reset`, or `Retry-After` for a
 * secondary limit); until then this client fails the check without sending anything, since asking again would only
 * spend the next window too.
 */
class GitHubReleasesClient(
    private val client: OkHttpClient,
    /** `owner/name`. */
    private val repo: String,
    private val apiBaseUrl: String = API_BASE_URL,
    private val json: Json = CursorJson,
    private val now: () -> Long = AppClock::now,
    /**
     * Bytes a download may still count on where it is being written. Supplied rather than defaulted so that no
     * caller can end up without the check by accident; the device answer is
     * [com.cursorforandroid.update.allocatableBytes].
     */
    private val freeSpace: (File) -> Long,
) {
    sealed interface ReleasesFetch {
        data class Changed(val releases: List<GitHubReleaseDto>, val etag: String?) : ReleasesFetch
        data object Unchanged : ReleasesFetch
    }

    /** When the spent quota frees up again; 0 while there is no reason to believe it is spent. */
    @Volatile
    private var rateLimitedUntilMs: Long = 0L

    /**
     * The most recent releases, newest first as GitHub orders them. With [etag] from a previous fetch the server
     * answers [ReleasesFetch.Unchanged] when nothing was published, edited or deleted since.
     *
     * Drafts, source-only tags and pre-releases are only recognisable once the caller has mapped them, so it decides
     * when enough has been read: [hasEligible] is asked after every page, and while it says no the `Link` header's
     * next page is followed, up to [MAX_PAGES]. A repository publishing many pre-releases (or screenshot tags) can
     * otherwise push the newest installable stable release off the first page and leave stable users told they are
     * up to date forever.
     */
    suspend fun listReleases(etag: String?, hasEligible: (List<GitHubReleaseDto>) -> Boolean = { true }): ReleasesFetch {
        (rateLimitedUntilMs - now()).takeIf { it > 0 }?.let { throw rateLimitException(it) }
        var url = "${apiBaseUrl}repos/$repo/releases?per_page=$PAGE_SIZE"
        // Only the first page is conditional: its ETag is the one that was kept, and a 304 for it means the head of
        // the list is as it was, which is what the previous decision was made from.
        var conditional = etag
        var page = 0
        var firstEtag: String? = null
        val collected = mutableListOf<GitHubReleaseDto>()
        while (true) {
            page++
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .apply { if (!conditional.isNullOrBlank()) header("If-None-Match", conditional) }
                .build()
            val next = client.newCall(request).await().use { response ->
                if (response.code == 304) return ReleasesFetch.Unchanged
                if (!response.isSuccessful) throw response.toCheckException()
                val body = withContext(Dispatchers.IO) { response.body?.string() } ?: ""
                collected += runCatching { json.decodeFromString(ListSerializer(GitHubReleaseDto.serializer()), body) }
                    .getOrElse { throw UpdateCheckException("GitHub sent a release list the app couldn't read.") }
                if (page == 1) firstEtag = response.header("ETag")
                nextPageUrl(response.header("Link"))
            }
            if (next == null || page >= MAX_PAGES || hasEligible(collected)) break
            url = next
            conditional = null
        }
        return ReleasesFetch.Changed(collected, firstEtag)
    }

    /** The `rel="next"` page of GitHub's `Link` header, ignoring anything that does not belong to the API being read. */
    private fun nextPageUrl(link: String?): String? =
        link?.let { NEXT_LINK.find(it)?.groupValues?.get(1) }?.takeIf { it.startsWith(apiBaseUrl) }

    /** A small text asset such as `SHA256SUMS.txt`. */
    suspend fun text(url: String): String {
        client.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't fetch ${url.substringAfterLast('/')} (${response.code}).")
            return withContext(Dispatchers.IO) { response.body?.string() } ?: ""
        }
    }

    /**
     * Streams [url] into [target] and returns the lowercase hex SHA-256 of the bytes written. [onProgress] receives
     * (bytes so far, total or -1) as chunks land. Cancelling the coroutine cancels the HTTP call and removes the
     * partial file. The caller decides when [target] becomes a file it trusts; nothing here renames anything.
     *
     * The transfer is bounded three ways, because the call timeout is deliberately unlimited and an endpoint that
     * streamed forever would otherwise fill the filesystem: [expectedBytes] (GitHub's own size for the asset, when
     * known) and any `Content-Length` must be plausible for an APK, the bytes written are counted against both, and
     * the device must have room for the file plus headroom before and while it is written.
     */
    suspend fun download(
        url: String,
        target: File,
        expectedBytes: Long = -1L,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): String {
        if (expectedBytes > MAX_APK_BYTES) throw IOException(tooLargeMessage(expectedBytes))
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            // Asked before anything is asked of the network: a download with nowhere to go is not worth starting.
            requireSpaceFor(target, expectedBytes)
        }
        val call = client.newCall(Request.Builder().url(url).build())
        try {
            call.await().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code}).")
                val body = response.body ?: throw IOException("Download failed: empty response.")
                return withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val total = body.contentLength()
                    if (total > MAX_APK_BYTES) throw IOException(tooLargeMessage(total))
                    // What the transfer may write: whichever size is known, else the ceiling on its own.
                    val limit = listOf(expectedBytes, total).filter { it > 0 }.minOrNull() ?: MAX_APK_BYTES
                    requireSpaceFor(target, limit)
                    var read = 0L
                    var spaceCheckedAt = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                read += n
                                // Checked before the bytes are written: an overrun must not reach the disk at all.
                                if (read > limit) throw IOException("The download is larger than the release says it is; it was stopped.")
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                if (read - spaceCheckedAt >= SPACE_CHECK_EVERY_BYTES) {
                                    requireSpaceFor(target, limit - read)
                                    spaceCheckedAt = read
                                }
                                onProgress(read, total)
                            }
                        }
                    }
                    if (total >= 0 && read != total) throw IOException("Download ended early ($read of $total bytes).")
                    if (expectedBytes > 0 && read != expectedBytes) {
                        throw IOException("The download is $read bytes; the release says $expectedBytes.")
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        } catch (t: Throwable) {
            call.cancel()
            target.delete()
            throw t
        }
    }

    /**
     * Room for [bytes] more plus headroom, so a download cannot be the thing that fills the device.
     * `File.getUsableSpace` is the `StatFs` the platform would report for the filesystem the cache lives on, without
     * the Android dependency the tests would then need.
     */
    private fun requireSpaceFor(target: File, bytes: Long) {
        if (bytes <= 0) return
        val free = freeSpace(target.parentFile ?: target)
        // 0 is "cannot tell" (an unreadable path); refusing then would block downloads on nothing.
        if (free > 0 && free < bytes + FREE_SPACE_HEADROOM_BYTES) {
            throw IOException("There isn't enough free space for the update (${mb(bytes + FREE_SPACE_HEADROOM_BYTES)} MB needed, ${mb(free)} MB free).")
        }
    }

    private fun tooLargeMessage(bytes: Long) =
        "That release's APK is ${mb(bytes)} MB, far larger than this app has ever been; it wasn't downloaded."

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    private fun Response.toCheckException(): UpdateCheckException = when {
        // 403 and 429 both mean "too many": the primary quota with its remaining count at zero, or a secondary limit
        // that names a delay. Either way the wait is recorded, so the next check does not spend the following window.
        (code == 403 || code == 429) && (header("X-RateLimit-Remaining") == "0" || rateLimitWaitMillis() != null) -> {
            val wait = rateLimitWaitMillis() ?: DEFAULT_RATE_LIMIT_WAIT_MS
            rateLimitedUntilMs = now() + wait
            rateLimitException(wait)
        }
        code == 404 -> UpdateCheckException("No releases found for $repo on GitHub.")
        else -> UpdateCheckException("GitHub answered with an error ($code).")
    }

    /** `Retry-After` (seconds, a secondary limit) or `X-RateLimit-Reset` (epoch seconds, the primary one). */
    private fun Response.rateLimitWaitMillis(): Long? {
        header("Retry-After")?.trim()?.toLongOrNull()?.let { return (it * 1000L).coerceIn(0L, MAX_RATE_LIMIT_WAIT_MS) }
        val resetAtSeconds = header("X-RateLimit-Reset")?.trim()?.toLongOrNull() ?: return null
        return (resetAtSeconds * 1000L - now()).coerceIn(0L, MAX_RATE_LIMIT_WAIT_MS)
    }

    private fun rateLimitException(waitMillis: Long): UpdateCheckException {
        val minutes = (waitMillis + 59_999L) / 60_000L
        val again = when {
            minutes <= 1L -> "Try again in a minute."
            minutes < 60L -> "Try again in $minutes minutes."
            else -> "Try again in an hour."
        }
        return UpdateCheckException("GitHub's limit for anonymous requests was reached. $again", waitMillis)
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
        /** GitHub's maximum, so one request usually covers every release a check could care about. */
        private const val PAGE_SIZE = 100
        /** A repository with more than 300 releases and nothing installable among them is not worth more requests. */
        private const val MAX_PAGES = 3
        /** What an unspecified anonymous limit costs: GitHub's window is an hour. */
        private const val DEFAULT_RATE_LIMIT_WAIT_MS = 60 * 60 * 1000L
        /** A reset further out than this is a clock disagreement, not a real wait. */
        private const val MAX_RATE_LIMIT_WAIT_MS = DEFAULT_RATE_LIMIT_WAIT_MS
        private val NEXT_LINK = Regex("""<([^>]+)>\s*;\s*rel="next"""")

        /**
         * The most an APK of this app could plausibly be. The universal release build is a few tens of megabytes;
         * anything past this is a mistake or a hostile endpoint, and no `callTimeout` is set to stop it on its own.
         */
        const val MAX_APK_BYTES = 128L * 1024 * 1024

        /** Left free after the download, so the update is never what leaves the device with nothing to work with. */
        private const val FREE_SPACE_HEADROOM_BYTES = 32L * 1024 * 1024

        /** How often the remaining space is re-checked while writing; a `StatFs` per 64 KB chunk would be absurd. */
        private const val SPACE_CHECK_EVERY_BYTES = 8L * 1024 * 1024

        fun releasesPageUrl(repo: String) = "https://github.com/$repo/releases"
    }
}
