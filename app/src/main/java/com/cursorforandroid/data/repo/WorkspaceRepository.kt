package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.DiffDetailsApi
import com.cursorforandroid.data.api.WorkspaceFilesApi
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.WorkspaceTree
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * How a read of the agent's VM ended: the answer, a refusal with the reason in words, or nothing this mode may ask
 * for. Every state is named so the panel never shows a blank.
 */
sealed interface VmRead<out T> {
    data class Loaded<T>(val value: T) : VmRead<T>

    /** Extended mode is off (or the demo, which has no VM): the named state, and no call was made. */
    data class NotAvailable(val reason: String) : VmRead<Nothing>

    /** The call was made and refused, or failed; [endpointChanged] when Cursor no longer offers the method. */
    data class Failed(val message: String, val endpointChanged: Boolean = false) : VmRead<Nothing>
}

/**
 * The agent's live VM as the panel reads it: the workspace tree (`ListWorkspaceFiles`), one file of it
 * (`ReadBinaryFile`) and the branch's diff against its base (`GetBackgroundComposerDiffDetails`). Every read is an
 * account-service call, so each is gated on its [Capabilities] flag and answers [VmRead.NotAvailable] without a call
 * while the flag is off; the demo has no VM and says so. Listings and diffs are kept per agent for [TTL_MS], file
 * bytes for [FILE_TTL_MS], so reopening the panel is free; [reset] forgets everything (sign-out, mode off).
 */
class WorkspaceRepository(
    private val files: WorkspaceFilesApi,
    private val diffs: DiffDetailsApi,
    private val capabilities: suspend () -> Capabilities,
    private val isDemo: () -> Boolean = { false },
    private val now: () -> Long = AppClock::now,
) {
    private class Cached<T>(val value: T, val at: Long)

    private val trees = HashMap<String, Cached<WorkspaceTree>>()
    private val contents = HashMap<String, Cached<RepoFile>>()
    private val branchDiffs = HashMap<String, Cached<AgentDiff>>()
    private val locks = List(8) { Mutex() }

    private fun lock(key: String) = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    /** Every file of [agentId]'s workspace, by path. */
    suspend fun tree(agentId: String, force: Boolean = false): VmRead<WorkspaceTree> {
        if (isDemo()) return VmRead.NotAvailable(NO_DEMO_WORKSPACE)
        if (!capabilities().workspaceFiles) return VmRead.NotAvailable(NEEDS_EXTENDED_MODE)
        val key = "tree:$agentId"
        if (!force) fresh(trees, key)?.let { return VmRead.Loaded(it) }
        return lock(key).withLock {
            if (!force) fresh(trees, key)?.let { return@withLock VmRead.Loaded(it) }
            read { files.listFiles(agentId) }.also { if (it is VmRead.Loaded) synchronized(trees) { trees[key] = Cached(it.value, now()) } }
        }
    }

    /** The bytes of [path] in [agentId]'s workspace, typed by extension like a repository file so the same viewer shows it. */
    suspend fun file(agentId: String, path: String, force: Boolean = false): VmRead<RepoFile> {
        if (isDemo()) return VmRead.NotAvailable(NO_DEMO_WORKSPACE)
        if (!capabilities().workspaceFiles) return VmRead.NotAvailable(NEEDS_EXTENDED_MODE)
        val clean = path.trim().trimStart('/')
        val key = "file:$agentId:$clean"
        if (!force) fresh(contents, key, FILE_TTL_MS)?.let { return VmRead.Loaded(it) }
        return lock(key).withLock {
            if (!force) fresh(contents, key, FILE_TTL_MS)?.let { return@withLock VmRead.Loaded(it) }
            read {
                val bytes = files.readFile(agentId, clean)
                RepoFile(path = clean, bytes = bytes, sizeBytes = bytes.size.toLong())
            }.also { if (it is VmRead.Loaded) synchronized(contents) { contents[key] = Cached(it.value, now()) } }
        }
    }

    /** The branch's changes against its base, whether or not a pull request exists. */
    suspend fun diff(agentId: String, force: Boolean = false): VmRead<AgentDiff> {
        if (isDemo()) return VmRead.NotAvailable(NO_DEMO_DIFF)
        if (!capabilities().diffDetails) return VmRead.NotAvailable(NEEDS_EXTENDED_MODE)
        val key = "diff:$agentId"
        if (!force) fresh(branchDiffs, key)?.let { return VmRead.Loaded(it) }
        return lock(key).withLock {
            if (!force) fresh(branchDiffs, key)?.let { return@withLock VmRead.Loaded(it) }
            read { diffs.diffDetails(agentId) }.also { if (it is VmRead.Loaded) synchronized(branchDiffs) { branchDiffs[key] = Cached(it.value, now()) } }
        }
    }

    fun reset() {
        synchronized(trees) { trees.clear() }
        synchronized(contents) { contents.clear() }
        synchronized(branchDiffs) { branchDiffs.clear() }
    }

    private suspend fun <T> read(block: suspend () -> T): VmRead<T> = try {
        VmRead.Loaded(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        failure(t)
    }

    private fun <T> fresh(map: HashMap<String, Cached<T>>, key: String, ttl: Long = TTL_MS): T? = synchronized(map) {
        map[key]?.takeIf { now() - it.at < ttl }?.value
    }

    companion object {
        const val TTL_MS = 30_000L
        const val FILE_TTL_MS = 5 * 60_000L

        const val NEEDS_EXTENDED_MODE = "Needs Extended mode: the agent's workspace and its branch diff come from Cursor's account service."
        const val NO_DEMO_WORKSPACE = "The demo has no workspace to browse."
        const val NO_DEMO_DIFF = "The demo's changes are the ones its transcript shows."
        const val ENDPOINT_CHANGED = "Cursor changed a private endpoint; the agent's workspace is unavailable until the app is updated."

        /** The words a failed VM read shows; a method Cursor no longer offers is named as such rather than as an error. */
        fun failure(t: Throwable): VmRead.Failed = when (t) {
            is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) VmRead.Failed(NEEDS_EXTENDED_MODE) else VmRead.Failed(t.message ?: "Cursor couldn't start a session for this key.")
            is ConnectRpcException -> when {
                t.httpCode == 404 || t.code == "unimplemented" -> VmRead.Failed(ENDPOINT_CHANGED, endpointChanged = true)
                t.code == "not_found" -> VmRead.Failed("The agent's VM has no such file any more, or the VM is gone.")
                t.code == "failed_precondition" || t.code == "unavailable" -> VmRead.Failed("The agent's VM isn't running right now (${t.message}).")
                else -> VmRead.Failed("Cursor refused (${t.message}).")
            }
            is IOException -> VmRead.Failed(t.userMessage())
            else -> VmRead.Failed(t.message ?: "Something went wrong.")
        }
    }
}
