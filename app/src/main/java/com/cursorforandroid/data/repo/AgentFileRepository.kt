package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.GitHubRepo
import com.cursorforandroid.data.api.OriginRepo
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.WorkspaceTree
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

/** How a read of a file the agent named ended: the file and where it came from, or why not, with where it can be seen instead. */
sealed interface FileRead {
    data class Loaded(val file: RepoFile, val source: Source) : FileRead

    /** Nothing this mode may ask holds the file (default mode, no repository this app reads): named, no retry. */
    data class NotReadable(val reason: String, val webUrl: String?) : FileRead

    /** The reads were made and none had the file, or one failed: [retryable] unless asking again would say the same. */
    data class Failed(val message: String, val webUrl: String?, val retryable: Boolean = true) : FileRead

    enum class Source(val label: String) { Workspace("From the agent's workspace"), Repository("From the repository at the agent's branch") }
}

/**
 * Reads a file by the path an agent names it with — in a tool call, or in a reply's `<img src>` — from wherever this
 * app can: the agent's live workspace (`ReadBinaryFile`, Extended mode; the desktop Agents Window reads the same
 * files through it, `cursor-worker://{bcId}/{relativePath}`), else the repository at the agent's branch (GitHub's or
 * Origin's contents read, what the panel's Repository tab shows). Agents name files absolutely on their VM
 * (`/workspace/app/src/Main.kt`) while both reads take a path relative to the workspace root, which is the
 * repository's: the workspace's own listing says which suffix of the path is the relative one, and without it the
 * VM's usual root is taken off.
 */
class AgentFileRepository(
    private val workspace: WorkspaceRepository,
    private val repository: suspend (repoUrl: String, ref: String?, path: String) -> Result<RepoContents>,
    private val agent: (String) -> Agent?,
) {
    suspend fun read(agentId: String, path: String, force: Boolean = false): FileRead {
        val row = agent(agentId)
        val webUrl = webUrl(row, relativeGuess(path))
        var workspaceFailure: String? = null
        var workspaceNotReadable: String? = null
        when (val tree = workspace.tree(agentId)) {
            is VmRead.Loaded -> {
                val relative = relativePath(path, tree.value)
                when (val read = workspace.file(agentId, relative, force)) {
                    is VmRead.Loaded -> return FileRead.Loaded(read.value, FileRead.Source.Workspace)
                    is VmRead.NotAvailable -> workspaceNotReadable = read.reason
                    is VmRead.Failed -> workspaceFailure = read.message
                }
            }
            is VmRead.NotAvailable -> workspaceNotReadable = tree.reason
            // The listing failed but the file may still be read by the usual relative form (a VM that lists slowly).
            is VmRead.Failed -> when (val read = workspace.file(agentId, relativeGuess(path), force)) {
                is VmRead.Loaded -> return FileRead.Loaded(read.value, FileRead.Source.Workspace)
                is VmRead.NotAvailable -> workspaceNotReadable = read.reason
                is VmRead.Failed -> workspaceFailure = read.message
            }
        }
        val repoUrl = row?.repoUrl
        val readable = repoUrl != null && (GitHubRepo.parse(repoUrl) != null || OriginRepo.parse(repoUrl) != null)
        if (readable && repoUrl != null) {
            val result = try {
                repository(repoUrl, row.branchName ?: row.startingRef, relativeGuess(path))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            val contents = result.getOrNull()
            if (contents is RepoContents.File) return FileRead.Loaded(contents.file, FileRead.Source.Repository)
            if (contents is RepoContents.Directory) return FileRead.Failed("${relativeGuess(path)} is a directory.", webUrl, retryable = false)
            val repoWords = result.exceptionOrNull()?.message
            return when {
                workspaceFailure != null -> FileRead.Failed(workspaceFailure, webUrl)
                workspaceNotReadable != null -> FileRead.Failed(listOfNotNull("The repository at the agent's branch doesn't have it${repoWords?.let { " ($it)" }.orEmpty()}.", workspaceNotReadable).joinToString(" "), webUrl, retryable = true)
                else -> FileRead.Failed(repoWords ?: "The repository doesn't have this file.", webUrl)
            }
        }
        return when {
            workspaceFailure != null -> FileRead.Failed(workspaceFailure, webUrl)
            else -> FileRead.NotReadable(workspaceNotReadable ?: WorkspaceRepository.NEEDS_EXTENDED_MODE, webUrl)
        }
    }

    /** The file on the repository's host at the agent's branch, for the browser; null when the host is not one this app links to. */
    fun webUrl(agentId: String, path: String): String? = webUrl(agent(agentId), relativeGuess(path))

    private fun webUrl(row: Agent?, relative: String): String? {
        val repoUrl = row?.repoUrl ?: return null
        val ref = (row.branchName ?: row.startingRef)?.takeIf { it.isNotBlank() }
        val encoded = relative.split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        GitHubRepo.parse(repoUrl)?.let { repo -> return "https://github.com/${repo.owner}/${repo.name}/blob/${ref ?: "HEAD"}/$encoded" }
        OriginRepo.parse(repoUrl)?.let { repo -> return "https://origin.cursor.com/${repo.owner}/${repo.name}/blob/${ref ?: "HEAD"}/$encoded" }
        return null
    }

    companion object {
        /** Where a cloud agent's repository is checked out on its VM. */
        private val WORKSPACE_ROOTS = listOf("/workspace/", "/home/ubuntu/workspace/")

        /**
         * [path] relative to the workspace root, as [tree] names it: the longest listed path the absolute one ends
         * with, so `/workspace/app/Main.kt` is `app/Main.kt` whatever the root is called. A path the tree does not
         * hold falls back to [relativeGuess].
         */
        fun relativePath(path: String, tree: WorkspaceTree?): String {
            val clean = path.trim().replace('\\', '/')
            if (!clean.startsWith("/")) return relativeGuess(clean)
            val match = tree?.paths?.asSequence()?.map { it.trim('/') }?.filter { it.isNotEmpty() && (clean == "/$it" || clean.endsWith("/$it")) }?.maxByOrNull { it.length }
            return match ?: relativeGuess(clean)
        }

        /** The usual relative form: a leading `./` or the VM's workspace root taken off, else the leading slash. */
        fun relativeGuess(path: String): String {
            val clean = path.trim().replace('\\', '/')
            WORKSPACE_ROOTS.firstOrNull { clean.startsWith(it) }?.let { return clean.removePrefix(it).trimStart('/') }
            return clean.removePrefix("./").trimStart('/')
        }
    }
}
