package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.WorkspaceTree
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.util.Base64

/** The agent's live workspace on the account service. An interface so the repositories can be faked. */
interface WorkspaceFilesApi {
    /** `ListWorkspaceFiles {bcId}`: every file of the workspace by path, relative to its root. */
    suspend fun listFiles(agentId: String): WorkspaceTree

    /** `ReadBinaryFile {bcId, path}`: the bytes of one file of the workspace, as it is on the VM right now. */
    suspend fun readFile(agentId: String, path: String): ByteArray
}

/** The branch's changes against its base, from the account service. */
fun interface DiffDetailsApi {
    /** `GetBackgroundComposerDiffDetails {bcId}`: every changed file with its hunks, and the contents before and after. */
    suspend fun diffDetails(agentId: String): AgentDiff
}

/**
 * The corner of `aiserver.v1.BackgroundComposerService` (see [BackgroundComposerApi]) that reads the agent's VM:
 * `ListWorkspaceFiles` (what the Agents Window's quick-open lists), `ReadBinaryFile` (the bytes behind it) and
 * `GetBackgroundComposerDiffDetails` (the branch's diff against its base, hunk by hunk, whether or not a pull request
 * exists). Field names are the proto's in Connect JSON's lowerCamelCase; `bytes` fields arrive base64-encoded. Every
 * call carries the account session from [SessionTokenProvider], which Extended mode alone hands out.
 */
class AgentFilesApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : WorkspaceFilesApi, DiffDetailsApi {

    override suspend fun listFiles(agentId: String): WorkspaceTree {
        val response = call("ListWorkspaceFiles", BcIdDto(agentId), BcIdDto.serializer(), ListWorkspaceFilesResponseDto.serializer())
        return WorkspaceTree(response.relativePaths.map { it.trim().trimStart('/') }.filter { it.isNotEmpty() }.distinct())
    }

    override suspend fun readFile(agentId: String, path: String): ByteArray {
        val response = call("ReadBinaryFile", ReadBinaryFileDto(agentId, path), ReadBinaryFileDto.serializer(), ReadBinaryFileResponseDto.serializer())
        return decodeBytes(response.content)
    }

    override suspend fun diffDetails(agentId: String): AgentDiff {
        val response = call("GetBackgroundComposerDiffDetails", BcIdDto(agentId), BcIdDto.serializer(), DiffDetailsResponseDto.serializer())
        val files = response.diffs.mapNotNull { fileOf(it) }
        // A record with hunks but no per-file entries (older servers put everything in `gitDiffs`) still lists its files.
        val fromHunksOnly = if (files.isEmpty()) response.gitDiffs.flatMap { it.diffs }.mapNotNull { fileOf(it) } else emptyList()
        return AgentDiff(
            branchName = response.branchName?.takeIf { it.isNotBlank() },
            baseBranch = response.baseBranch?.takeIf { it.isNotBlank() },
            files = files + fromHunksOnly,
        )
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    @Serializable
    private data class BcIdDto(val bcId: String)

    @Serializable
    private data class ReadBinaryFileDto(val bcId: String, val path: String)

    @Serializable
    private data class ListWorkspaceFilesResponseDto(val relativePaths: List<String> = emptyList())

    @Serializable
    private data class ReadBinaryFileResponseDto(val content: String? = null)

    @Serializable
    private data class DiffDetailsResponseDto(
        val branchName: String? = null,
        val baseBranch: String? = null,
        val diffs: List<FullDiffDto> = emptyList(),
        val gitDiffs: List<GitDiffDto> = emptyList(),
    )

    /** `aiserver.v1.BackgroundComposerFullDiff`: one changed file, whole, with its hunks. */
    @Serializable
    internal data class FullDiffDto(
        val path: String? = null,
        val originalContent: String? = null,
        val modifiedContent: String? = null,
        val gitDiff: GitDiffDto? = null,
        val fullPath: String? = null,
        val baseRef: String? = null,
    )

    /** `aiserver.v1.GitDiff`. */
    @Serializable
    internal data class GitDiffDto(val diffs: List<FileDiffDto> = emptyList())

    /** `aiserver.v1.FileDiff`: git's `from` / `to` (`/dev/null` for a file created or deleted), the counts, the hunks. */
    @Serializable
    internal data class FileDiffDto(
        val from: String? = null,
        val to: String? = null,
        val added: Int = 0,
        val removed: Int = 0,
        val chunks: List<ChunkDto> = emptyList(),
        val beforeFileContents: String? = null,
        val afterFileContents: String? = null,
        val isGenerated: Boolean? = null,
    )

    /** `aiserver.v1.FileDiff.Chunk`: the `@@` header as [content], then each line with its `+`, `-` or space. */
    @Serializable
    internal data class ChunkDto(
        val content: String? = null,
        val lines: List<String> = emptyList(),
        val oldStart: Int = 0,
        val oldLines: Int = 0,
        val newStart: Int = 0,
        val newLines: Int = 0,
    )

    internal companion object {
        /** Proto3 JSON spells `bytes` as standard base64; the URL-safe alphabet is accepted too, padding or not. */
        fun decodeBytes(encoded: String?): ByteArray {
            val text = encoded?.trim().orEmpty()
            if (text.isEmpty()) return ByteArray(0)
            return runCatching { Base64.getDecoder().decode(text) }
                .recoverCatching { Base64.getUrlDecoder().decode(text) }
                .recoverCatching { Base64.getMimeDecoder().decode(text) }
                .getOrElse { text.toByteArray(Charsets.UTF_8) }
        }

        /** One file of the diff from its whole-file record; the hunks come from its `gitDiff` when present. */
        fun fileOf(dto: FullDiffDto): AgentDiffFile? {
            val hunks = dto.gitDiff?.diffs.orEmpty()
            val path = dto.path?.trim()?.takeIf { it.isNotEmpty() }
                ?: hunks.firstNotNullOfOrNull { pathOf(it) }
                ?: return null
            val combined = hunks.filter { pathOf(it) == null || pathOf(it) == path }.ifEmpty { hunks }
            val patch = combined.mapNotNull { patchOf(it) }.filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }
            val status = when {
                combined.any { it.from.isDevNull() } && combined.none { it.to.isDevNull() } -> ChangedFileStatus.Added
                combined.any { it.to.isDevNull() } -> ChangedFileStatus.Removed
                combined.any { it.from != null && it.to != null && !it.from.isDevNull() && !it.to.isDevNull() && cleanGitPath(it.from) != cleanGitPath(it.to) } -> ChangedFileStatus.Renamed
                dto.originalContent.isNullOrEmpty() && !dto.modifiedContent.isNullOrEmpty() && hunks.isEmpty() -> ChangedFileStatus.Added
                !dto.originalContent.isNullOrEmpty() && dto.modifiedContent.isNullOrEmpty() && hunks.isEmpty() -> ChangedFileStatus.Removed
                else -> ChangedFileStatus.Modified
            }
            val previous = if (status == ChangedFileStatus.Renamed) combined.firstNotNullOfOrNull { it.from }?.let(::cleanGitPath) else null
            return AgentDiffFile(
                path = path,
                status = status,
                additions = combined.sumOf { it.added }.takeIf { it > 0 } ?: countLines(patch, '+'),
                deletions = combined.sumOf { it.removed }.takeIf { it > 0 } ?: countLines(patch, '-'),
                patch = patch,
                originalContent = dto.originalContent?.takeIf { it.isNotEmpty() } ?: combined.firstNotNullOfOrNull { it.beforeFileContents },
                modifiedContent = dto.modifiedContent?.takeIf { it.isNotEmpty() } ?: combined.firstNotNullOfOrNull { it.afterFileContents },
                previousPath = previous?.takeIf { it != path },
            )
        }

        /** A file known only by its hunks. */
        fun fileOf(diff: FileDiffDto): AgentDiffFile? {
            val path = pathOf(diff) ?: return null
            return fileOf(FullDiffDto(path = path, gitDiff = GitDiffDto(listOf(diff))))
        }

        /** The file a `FileDiff` is about: its `to`, else its `from` (a deletion), without git's `a/` `b/` prefixes. */
        fun pathOf(diff: FileDiffDto): String? {
            val to = diff.to?.takeUnless { it.isDevNull() }?.let(::cleanGitPath)?.takeIf { it.isNotEmpty() }
            val from = diff.from?.takeUnless { it.isDevNull() }?.let(::cleanGitPath)?.takeIf { it.isNotEmpty() }
            return to ?: from
        }

        /** The hunks of one `FileDiff` as unified-diff text: each `@@` header, then its lines as sent. */
        fun patchOf(diff: FileDiffDto): String? {
            if (diff.chunks.isEmpty()) return null
            return diff.chunks.joinToString("\n") { chunk ->
                val header = chunk.content?.trimEnd()?.takeIf { it.isNotBlank() }
                    ?: "@@ -${chunk.oldStart},${chunk.oldLines} +${chunk.newStart},${chunk.newLines} @@"
                (listOf(header) + chunk.lines.map { it.trimEnd('\n', '\r') }).joinToString("\n")
            }
        }

        private fun cleanGitPath(raw: String): String = raw.trim().removePrefix("a/").removePrefix("b/")

        private fun String?.isDevNull(): Boolean = this != null && this.trim().removePrefix("a/").removePrefix("b/") == "/dev/null"

        private fun countLines(patch: String?, sign: Char): Int =
            patch?.lineSequence()?.count { it.startsWith(sign) && !it.startsWith("$sign$sign$sign") } ?: 0
    }
}
