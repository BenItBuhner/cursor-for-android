package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorServer
import com.cursorforandroid.data.api.CursorServerApi
import com.cursorforandroid.data.api.CursorServerFiles
import com.cursorforandroid.data.api.CursorServerReadException
import com.cursorforandroid.data.api.GitHubRepo
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.api.OriginRepo
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.WorkspaceTree
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** How a read of a file the agent named ended: the file and where it came from, or why not, with where it can be seen instead. */
sealed interface FileRead {
    data class Loaded(val file: RepoFile, val source: Source) : FileRead

    /** Nothing this mode may ask holds the file (default mode, no repository this app reads): named, no retry. */
    data class NotReadable(val reason: String, val webUrl: String?) : FileRead

    /**
     * The reads were made and none had the file, or one failed: [retryable] unless asking again would say the same;
     * [reason] what the failure means — the machine asleep (wake it), gone, the file missing.
     */
    data class Failed(val message: String, val webUrl: String?, val retryable: Boolean = true, val reason: Reason = Reason.Other, val asked: String? = null) : FileRead

    enum class Source(val label: String) { Workspace("From the agent's workspace"), Machine("From the agent's machine"), Repository("From the repository at the agent's branch") }

    /**
     * [OutsideWorkspace]: the file is on the machine outside its workspace and nothing that may be asked here gave it
     * — `ReadBinaryFile` stops at the workspace, and the machine's cursor-server did not answer with it.
     */
    enum class Reason { MachineAsleep, MachineGone, NotFound, OutsideWorkspace, Other }
}

/**
 * One read of a file by the path an agent named, as the transcript diagnostics print it: what was asked of the VM
 * and of the repository, what each answered, and how the read ended.
 */
data class FileReadAttempt(
    val atMillis: Long,
    val agentId: String,
    val path: String,
    /** How `ListWorkspaceFiles` answered, which decides the path the VM is asked for. */
    val listing: String,
    val vm: String,
    val repository: String,
    val result: String,
) {
    val text: String get() = "open $path list=$listing vm=$vm repo=$repository result=$result"
}

/**
 * Reads a file by the path an agent names it with — in a tool call, or in a reply's `<img src>` — from wherever this
 * app can: the agent's own machine (`ReadBinaryFile`, Extended mode; the desktop Agents Window reads the workspace's
 * files through it, `cursor-worker://{bcId}/{relativePath}`), else, for a file of the repository, the repository at
 * the agent's branch (GitHub's or Origin's contents read, what the panel's Repository tab shows). Agents name files
 * absolutely on their VM: under the workspace root (`/workspace/app/src/Main.kt`) the read takes the path relative to
 * it — the workspace's own listing says which suffix that is. `ReadBinaryFile` stops there: a path outside the
 * workspace (`/tmp/frame.png`) is refused, `invalid_argument "File path must stay within the workspace."` (Bennett's
 * 2026-09-22 frame). Such a file is read the way Cursor's own client reads it, off the machine's cursor-server
 * ([CursorServerFiles]: `GetCursorServerUrl`, then its remote-resource route). A file outside the workspace is no
 * file of the repository, and the repository is not asked for it.
 */
class AgentFileRepository(
    private val workspace: WorkspaceRepository,
    private val repository: suspend (repoUrl: String, ref: String?, path: String) -> Result<RepoContents>,
    private val agent: (String) -> Agent?,
    /** `WakeBackgroundComposer`: asks the account to start the chat's machine; false where nothing can be asked. */
    private val wakeMachine: suspend (agentId: String) -> Boolean = { false },
    /** The machine's cursor-server, for a file outside the workspace (Extended mode); null where none is wired. */
    private val cursorServer: CursorServerFiles? = null,
    private val mintToken: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = AppClock::now,
    private val wakeWaitMs: Long = WAKE_WAIT_MS,
    private val wakePollMs: Long = WAKE_POLL_MS,
) {
    private val log = ArrayDeque<FileReadAttempt>()
    /** Per chat: the connection token minted for its cursor-server, as the desktop keeps one per agent and build. */
    private val serverTokens = ConcurrentHashMap<String, String>()
    /** Per chat: the server `GetCursorServerUrl` named, and when; asked again once stale or refused. */
    private val servers = ConcurrentHashMap<String, Pair<CursorServer, Long>>()

    /** The reads made for [agentId], oldest first, for the transcript diagnostics. */
    fun attempts(agentId: String): List<FileReadAttempt> = synchronized(log) { log.filter { it.agentId == agentId } }

    /**
     * The bytes of [path]. With [wake] the machine is asked to start first when it is asleep, and the read is asked
     * again while it comes up, for as long as a machine takes to.
     */
    suspend fun read(agentId: String, path: String, force: Boolean = false, wake: Boolean = false): FileRead {
        var read = readOnce(agentId, path, force)
        if (!wake || (read as? FileRead.Failed)?.reason != FileRead.Reason.MachineAsleep) return read
        val woken = try {
            wakeMachine(agentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        if (!woken) return read
        val deadline = now() + wakeWaitMs
        while ((read as? FileRead.Failed)?.reason == FileRead.Reason.MachineAsleep && now() < deadline) {
            delay(wakePollMs)
            read = readOnce(agentId, path, force = true)
        }
        return read
    }

    private suspend fun readOnce(agentId: String, path: String, force: Boolean): FileRead {
        val row = agent(agentId)
        var listNote = "not asked"
        var vmNote = "not asked"
        var repoNote = "not asked"
        fun done(result: FileRead): FileRead {
            record(FileReadAttempt(now(), agentId, path.trim(), listNote, vmNote, repoNote, describe(result)))
            return result
        }

        var workspaceFailure: VmRead.Failed? = null
        var workspaceNotReadable: String? = null
        val tree = workspace.tree(agentId)
        val listed = (tree as? VmRead.Loaded)?.value
        listNote = when (tree) {
            is VmRead.Loaded -> "loaded(${tree.value.paths.size})"
            is VmRead.NotAvailable -> "not available"
            is VmRead.Failed -> tree.kind.name + (tree.asked?.let { " [$it]" } ?: " \"${tree.message.take(80)}\"")
        }
        val inWorkspace = isInWorkspace(path, listed)
        val vmPath = if (inWorkspace) relativePath(path, listed) else path.trim()
        val webUrl = if (inWorkspace) webUrl(row, relativeGuess(path)) else null
        when {
            tree is VmRead.NotAvailable -> {
                workspaceNotReadable = tree.reason
                vmNote = "not available (${tree.reason.substringBefore(':')})"
            }
            // The listing refused because the machine is asleep or gone: the file read would say the same.
            tree is VmRead.Failed && (tree.kind == VmRead.FailureKind.MachineAsleep || tree.kind == VmRead.FailureKind.MachineGone) -> {
                workspaceFailure = tree
                vmNote = "$vmPath→${tree.kind.name} (listing)" + (tree.asked?.let { " [$it]" } ?: "")
            }
            // ReadBinaryFile refuses a path outside the workspace: the cursor-server reads it, below.
            !inWorkspace -> vmNote = "ReadBinaryFile skipped (outside the workspace)"
            else -> when (val read = workspace.file(agentId, vmPath, force)) {
                is VmRead.Loaded -> {
                    vmNote = "$vmPath→loaded"
                    return done(FileRead.Loaded(read.value, if (inWorkspace) FileRead.Source.Workspace else FileRead.Source.Machine))
                }
                is VmRead.NotAvailable -> {
                    workspaceNotReadable = read.reason
                    vmNote = "not available"
                }
                is VmRead.Failed -> {
                    workspaceFailure = read
                    vmNote = "$vmPath→${read.kind.name}" + (read.asked?.let { " [$it]" } ?: " \"${read.message.take(80)}\"")
                }
            }
        }

        val repoUrl = row?.repoUrl
        val hostReadable = repoUrl != null && (GitHubRepo.parse(repoUrl) != null || OriginRepo.parse(repoUrl) != null)
        val machineGone = workspaceFailure?.kind == VmRead.FailureKind.MachineGone || row?.runStatus == RunStatus.EXPIRED || row?.isArchived == true
        if (!inWorkspace) {
            repoNote = "skipped (outside the workspace)"
            if (workspaceNotReadable != null) return done(FileRead.NotReadable(outsideNeedsExtended(path), null))
            if (workspaceFailure != null && (workspaceFailure.kind == VmRead.FailureKind.MachineAsleep || workspaceFailure.kind == VmRead.FailureKind.MachineGone)) {
                return done(failedOf(workspaceFailure, null, machineGone))
            }
            val server = cursorServer ?: return done(FileRead.Failed(OUTSIDE_WORKSPACE, null, retryable = false, reason = FileRead.Reason.OutsideWorkspace))
            return when (val read = readOffMachine(server, agentId, path.trim(), force)) {
                is MachineRead.Loaded -> {
                    vmNote = "cursor-server ${read.asked}→loaded"
                    done(FileRead.Loaded(RepoFile(path = path.trim(), bytes = read.bytes, sizeBytes = read.bytes.size.toLong()), FileRead.Source.Machine))
                }
                is MachineRead.Failed -> {
                    vmNote = "cursor-server→${read.failure.kind.name}" + (read.failure.asked?.let { " [$it]" } ?: " \"${read.failure.message.take(80)}\"")
                    done(
                        when (read.failure.kind) {
                            VmRead.FailureKind.MachineAsleep, VmRead.FailureKind.MachineGone -> failedOf(read.failure, null, machineGone)
                            VmRead.FailureKind.NotFound -> FileRead.Failed(WorkspaceRepository.NO_SUCH_FILE, null, reason = FileRead.Reason.NotFound, asked = read.failure.asked)
                            VmRead.FailureKind.NeedsExtendedMode -> FileRead.NotReadable(outsideNeedsExtended(path), null)
                            else -> FileRead.Failed(OUTSIDE_WORKSPACE, null, reason = FileRead.Reason.OutsideWorkspace, asked = read.failure.asked)
                        },
                    )
                }
            }
        }
        if (hostReadable && repoUrl != null) {
            val repoPath = relativeGuess(path)
            val result = try {
                repository(repoUrl, row.branchName ?: row.startingRef, repoPath)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            val contents = result.getOrNull()
            repoNote = "$repoPath→" + when (contents) {
                is RepoContents.File -> "loaded"
                is RepoContents.Directory -> "directory"
                null -> "failed \"${result.exceptionOrNull()?.message?.take(80)}\""
            }
            if (contents is RepoContents.File) return done(FileRead.Loaded(contents.file, FileRead.Source.Repository))
            if (contents is RepoContents.Directory) return done(FileRead.Failed("$repoPath is a directory.", webUrl, retryable = false))
            val repoWords = result.exceptionOrNull()?.message
            return done(
                when {
                    workspaceFailure != null -> failedOf(workspaceFailure, webUrl, machineGone)
                    workspaceNotReadable != null -> FileRead.Failed(listOfNotNull("The repository at the agent's branch doesn't have it${repoWords?.let { " ($it)" }.orEmpty()}.", workspaceNotReadable).joinToString(" "), webUrl, retryable = true)
                    else -> FileRead.Failed(repoWords ?: "The repository doesn't have this file.", webUrl)
                },
            )
        }
        repoNote = if (repoUrl == null) "none (no repository)" else "none (a host this app does not read)"
        return done(
            when {
                workspaceFailure != null -> failedOf(workspaceFailure, webUrl, machineGone)
                else -> FileRead.NotReadable(workspaceNotReadable ?: WorkspaceRepository.NEEDS_EXTENDED_MODE, webUrl)
            },
        )
    }

    private sealed interface MachineRead {
        class Loaded(val bytes: ByteArray, val asked: String) : MachineRead
        class Failed(val failure: VmRead.Failed) : MachineRead
    }

    /**
     * [path] off [agentId]'s machine through its cursor-server: the server `GetCursorServerUrl` names for the token
     * minted for the chat (kept a while, as the desktop keeps it), then the file from its remote-resource route. A
     * refused or unreachable server is asked for again once, fresh, before the read is given up on.
     */
    private suspend fun readOffMachine(api: CursorServerFiles, agentId: String, path: String, force: Boolean): MachineRead {
        var fresh = force
        repeat(2) { attempt ->
            val server = try {
                cachedServer(agentId, fresh) ?: api.server(agentId, CursorServerApi.DESKTOP_COMMIT, serverTokens.getOrPut(agentId, mintToken)).also { servers[agentId] = it to now() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return MachineRead.Failed(WorkspaceRepository.failure(t))
            }
            try {
                return MachineRead.Loaded(api.read(server, path), server.describe(path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CursorServerReadException) {
                servers.remove(agentId)
                if (e.httpCode == 404) return MachineRead.Failed(VmRead.Failed(WorkspaceRepository.NO_SUCH_FILE, kind = VmRead.FailureKind.NotFound, asked = e.asked))
                if (attempt == 1 || (e.httpCode != 401 && e.httpCode != 403)) return MachineRead.Failed(VmRead.Failed(e.message ?: "The agent's machine refused.", asked = e.asked))
            } catch (e: IOException) {
                servers.remove(agentId)
                if (attempt == 1) return MachineRead.Failed(VmRead.Failed(e.userMessage(), asked = "${server.describe(path)} → ${e.javaClass.simpleName}: ${e.message?.take(100)}"))
            }
            fresh = true
        }
        return MachineRead.Failed(VmRead.Failed("The agent's machine could not be reached."))
    }

    private fun cachedServer(agentId: String, fresh: Boolean): CursorServer? {
        if (fresh) {
            servers.remove(agentId)
            return null
        }
        val (server, at) = servers[agentId] ?: return null
        return server.takeIf { now() - at < SERVER_TTL_MS }
    }

    /** The VM's refusal in the reader's words, the chat's own state deciding a machine that is gone from one asleep. */
    private fun failedOf(failure: VmRead.Failed, webUrl: String?, machineGone: Boolean): FileRead.Failed = when {
        failure.kind == VmRead.FailureKind.MachineGone || (failure.kind == VmRead.FailureKind.MachineAsleep && machineGone) ->
            FileRead.Failed(WorkspaceRepository.MACHINE_GONE, webUrl, retryable = false, reason = FileRead.Reason.MachineGone, asked = failure.asked)
        failure.kind == VmRead.FailureKind.MachineAsleep -> FileRead.Failed(WorkspaceRepository.MACHINE_ASLEEP, webUrl, reason = FileRead.Reason.MachineAsleep, asked = failure.asked)
        failure.kind == VmRead.FailureKind.NotFound -> FileRead.Failed(WorkspaceRepository.NO_SUCH_FILE, webUrl, reason = FileRead.Reason.NotFound, asked = failure.asked)
        else -> FileRead.Failed(failure.message, webUrl, asked = failure.asked)
    }

    private fun describe(result: FileRead): String = when (result) {
        is FileRead.Loaded -> "loaded(${result.source.name.lowercase()},${result.file.sizeBytes}B)"
        is FileRead.NotReadable -> "not-readable \"${result.reason.take(80)}\""
        is FileRead.Failed -> "${result.reason.name} \"${result.message.take(80)}\""
    }

    private fun record(attempt: FileReadAttempt) = synchronized(log) {
        log.addLast(attempt)
        while (log.size > MAX_ATTEMPTS) log.removeFirst()
    }

    /** The file on the repository's host at the agent's branch, for the browser; null when the host is not one this app links to or the file is not the repository's. */
    fun webUrl(agentId: String, path: String): String? = if (isInWorkspace(path, null)) webUrl(agent(agentId), relativeGuess(path)) else null

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
        /** The machine's own directories: nothing under them is a file of the repository. */
        private val MACHINE_DIRS = listOf("/tmp/", "/var/", "/opt/", "/etc/", "/usr/", "/proc/", "/dev/", "/sys/", "/run/", "/root/", "/mnt/", "/srv/", "/cursor/")
        private const val MAX_ATTEMPTS = 40
        /** How long a woken machine is given to come up before the read is given up on; the Agents Window polls a preparing one about this long. */
        const val WAKE_WAIT_MS = 60_000L
        const val WAKE_POLL_MS = 3_000L
        /** How long a server `GetCursorServerUrl` named is used before it is asked for again. */
        const val SERVER_TTL_MS = 10 * 60_000L
        const val OUTSIDE_WORKSPACE = "The picture was saved outside the agent's workspace, and Cursor only lets apps read files inside it."

        /** What default mode says of a file outside the repository: only the agent's machine holds it. */
        fun outsideNeedsExtended(path: String): String = "${path.trim()} is on the agent's machine, outside the repository; reading it needs Extended mode."

        /**
         * Whether [path] is a file of the workspace, and so of the repository: a relative path, one under the VM's
         * usual root, or an absolute one that ends with a path [tree] lists. `/tmp/…`, `/opt/…`, a home directory's
         * files are the machine's alone.
         */
        fun isInWorkspace(path: String, tree: WorkspaceTree?): Boolean {
            val clean = path.trim().replace('\\', '/')
            if (!clean.startsWith("/")) return true
            if (WORKSPACE_ROOTS.any { clean.startsWith(it) }) return true
            // `/tmp/frame.png` is the machine's even when the repository has a `frame.png` of its own.
            if (MACHINE_DIRS.any { clean.startsWith(it) }) return false
            return tree?.paths?.any { listed -> listed.trim('/').let { it.isNotEmpty() && clean.endsWith("/$it") } } == true
        }

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
