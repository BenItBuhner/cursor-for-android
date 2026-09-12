package com.cursorforandroid.data.api

import com.cursorforandroid.domain.ChangedFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.PullRequestDetails
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoEntry
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.ScmHost
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

/** `ownerSlug/repoName` of a repository on Origin, from `https://origin.cursor.com/{owner}/{repo}(.git)` (and the legacy `/git/` form). */
data class OriginRepo(val owner: String, val name: String) {
    companion object {
        private val URL = Regex("""^(?:https?://)?origin\.cursor\.com/(?:git/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)

        fun parse(url: String?): OriginRepo? {
            val match = URL.matchEntire(url?.trim().orEmpty()) ?: return null
            val (owner, name) = match.destructured
            return OriginRepo(owner, name)
        }
    }
}

/** One pull request on Origin, from its web URL (`https://origin.cursor.com/{owner}/{repo}/pulls/{n}`, `pull/{n}` accepted too). */
data class OriginPullRequestRef(val repo: OriginRepo, val number: Int) {
    companion object {
        private val URL = Regex("""^(?:https?://)?origin\.cursor\.com/(?:git/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pulls?/(\d+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

        fun parse(url: String?): OriginPullRequestRef? {
            val match = URL.matchEntire(url?.trim().orEmpty()) ?: return null
            val (owner, name, number) = match.destructured
            return OriginPullRequestRef(OriginRepo(owner, name), number.toIntOrNull() ?: return null)
        }
    }
}

/** Origin answered with an error; [httpCode] 401 or 403 means the token was not accepted. */
class OriginApiException(val httpCode: Int, message: String) : IOException(message) {
    val isUnauthorized: Boolean get() = httpCode == 401 || httpCode == 403
    val isNotFound: Boolean get() = httpCode == 404
}

/** The app holds no Origin user access token, so nothing on Origin can be read (see [OriginApi]). */
class OriginTokenMissingException : IOException("Origin needs a user access token this app does not hold.")

/**
 * The documented Origin API, read side: the pull request verbatim, its files with patches, and repository contents
 * at a ref (`https://api.cursor.com/v1/origin`). Origin does not accept the Cloud Agents API key — "Cursor API keys
 * are not Origin Bearer tokens" — only the short-lived user access token its CLI exchanges the key for, on an
 * endpoint the reference does not document. [tokenProvider] is where such a token would come from; today nothing
 * in the app can mint one, so it answers null and every read fails with [OriginTokenMissingException], which the
 * panel turns into "open in browser". The client is complete so the day a token exists, nothing else changes.
 */
class OriginApi(
    private val client: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
    private val baseUrl: String = BASE_URL,
) {
    suspend fun pullRequest(ref: OriginPullRequestRef): PullRequestDetails {
        val dto = get("repos/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}", PullDto.serializer())
        return PullRequestDetails(
            url = "https://origin.cursor.com/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}",
            host = ScmHost.Origin,
            number = dto.number?.toIntOrNull() ?: ref.number,
            title = dto.title,
            body = dto.body.orEmpty(),
            state = when {
                dto.merged -> PullRequestState.Merged
                dto.state.equals("closed", ignoreCase = true) -> PullRequestState.Closed
                dto.draft -> PullRequestState.Draft
                else -> PullRequestState.Open
            },
            author = dto.author?.user?.let { it.displayName ?: it.handle ?: it.email } ?: dto.author?.app?.displayName,
            headRef = dto.head?.ref?.removePrefix("refs/heads/"),
            baseRef = dto.base?.ref?.removePrefix("refs/heads/"),
            headSha = dto.head?.sha,
            additions = dto.additions,
            deletions = dto.deletions,
            changedFiles = dto.changedFiles,
            createdAtMillis = millis(dto.createdAt),
            updatedAtMillis = millis(dto.updatedAt),
            mergedAtMillis = millis(dto.mergedAt),
            labels = dto.labels.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } },
        )
    }

    /** The first page of the pull request's files with their patches; Origin caps a page at 100. */
    suspend fun pullRequestFiles(ref: OriginPullRequestRef): List<ChangedFile> =
        get("repos/${ref.repo.owner}/${ref.repo.name}/pulls/${ref.number}/files?pageSize=$PAGE_SIZE", FilesDto.serializer()).files
            .filter { it.filename.isNotBlank() }
            .map { ChangedFile(it.filename, ChangedFileStatus.parse(it.status), it.additions, it.deletions, it.patch, it.previousFilename) }

    /** What sits at [path] of the repository at [ref]: a directory's entries or a file's bytes (base64, 1 MiB cap). */
    suspend fun contents(repo: OriginRepo, path: String, ref: String?): RepoContents {
        val clean = path.trim().trim('/')
        val query = listOfNotNull(
            clean.takeIf { it.isNotEmpty() }?.let { "path=${encode(it)}" },
            ref?.trim()?.takeIf { it.isNotEmpty() }?.let { "ref=${encode(it)}" },
        ).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        val dto = get("repos/${repo.owner}/${repo.name}/contents$query", ContentsDto.serializer())
        if (dto.type.equals("dir", ignoreCase = true) || dto.type.equals("directory", ignoreCase = true) || dto.entries.isNotEmpty() && dto.content == null) {
            return RepoContents.Directory(
                clean,
                dto.entries.filter { it.name.isNotBlank() }
                    .map { RepoEntry(it.name, it.path.ifBlank { listOf(clean, it.name).filter { s -> s.isNotEmpty() }.joinToString("/") }, isDirectory = it.type.equals("dir", ignoreCase = true) || it.type.equals("directory", ignoreCase = true), sizeBytes = it.size?.toLongOrNull()) }
                    .sortedWith(compareByDescending<RepoEntry> { it.isDirectory }.thenBy { it.name.lowercase() }),
            )
        }
        val encoded = dto.content?.takeIf { it.isNotBlank() } ?: throw OriginApiException(413, "${dto.name.ifBlank { "This file" }} is too large for Origin to send inline.")
        val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrElse { throw IOException("Origin sent a file that could not be decoded.", it) }
        return RepoContents.File(RepoFile(dto.path.ifBlank { clean }, bytes, dto.size?.toLongOrNull() ?: bytes.size.toLong(), dto.sha))
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: throw OriginTokenMissingException()
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().resolve(path) ?: throw IOException("Bad Origin path: $path"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw OriginApiException(response.code, "Origin answered HTTP ${response.code}.")
            val text = response.body?.string().orEmpty()
            return runCatching { CursorJson.decodeFromString(serializer, text) }.getOrElse { throw IOException("Origin sent an answer that could not be read.", it) }
        }
    }

    private fun millis(iso: String?): Long? = iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    @Serializable
    private data class RefDto(val ref: String? = null, val sha: String? = null)

    @Serializable
    private data class UserDto(val id: String? = null, val email: String? = null, val displayName: String? = null, val handle: String? = null)

    @Serializable
    private data class AppDto(val id: String? = null, val displayName: String? = null)

    @Serializable
    private data class ActorDto(val user: UserDto? = null, val app: AppDto? = null)

    @Serializable
    private data class LabelDto(val name: String? = null)

    /** Origin encodes 64-bit integers as strings (`number`), and keeps zero-valued fields present. */
    @Serializable
    private data class PullDto(
        val number: String? = null,
        val state: String = "",
        val draft: Boolean = false,
        val merged: Boolean = false,
        val title: String = "",
        val body: String? = null,
        val head: RefDto? = null,
        val base: RefDto? = null,
        val author: ActorDto? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
        val mergedAt: String? = null,
        val additions: Int? = null,
        val deletions: Int? = null,
        val changedFiles: Int? = null,
        val labels: List<LabelDto> = emptyList(),
    )

    @Serializable
    private data class FileDto(
        val filename: String = "",
        val status: String? = null,
        val additions: Int = 0,
        val deletions: Int = 0,
        val patch: String? = null,
        val previousFilename: String? = null,
    )

    @Serializable
    private data class FilesDto(val files: List<FileDto> = emptyList(), val nextPageToken: String? = null)

    @Serializable
    private data class ContentsDto(
        val type: String = "file",
        val encoding: String? = null,
        val size: String? = null,
        val name: String = "",
        val path: String = "",
        val sha: String? = null,
        val content: String? = null,
        val entries: List<ContentsDto> = emptyList(),
    )

    companion object {
        const val BASE_URL = "https://api.cursor.com/v1/origin/"
        const val PAGE_SIZE = 100
    }
}
