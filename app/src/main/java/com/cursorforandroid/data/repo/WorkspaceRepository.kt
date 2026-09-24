package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.DiffDetailsApi
import com.cursorforandroid.data.api.WorkspaceFilesApi
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.MachineUnavailableReason
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

    /**
     * The call was made and refused, or failed; [endpointChanged] when Cursor no longer offers the method. [kind]
     * says what the refusal means for the reader: the machine asleep (wake it and ask again), gone with the chat,
     * the file not there, or anything else. [asked] is the request as sent and the answer as received (see
     * [WorkspaceRepository.askedOf]); null for a failure before any request.
     */
    data class Failed(val message: String, val endpointChanged: Boolean = false, val kind: FailureKind = FailureKind.Other, val asked: String? = null) : VmRead<Nothing>

    enum class FailureKind { MachineAsleep, MachineGone, NotFound, EndpointChanged, NeedsExtendedMode, Other }
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

    /**
     * The bytes of [path] on [agentId]'s VM, typed by extension like a repository file so the same viewer shows it:
     * relative to the workspace root, as `ListWorkspaceFiles` names files, or — with [absolute] — a path anywhere on
     * the machine (`/tmp/frame.png`), sent as it is, since the VM's own read takes a path and no root.
     */
    suspend fun file(agentId: String, path: String, force: Boolean = false, absolute: Boolean = false): VmRead<RepoFile> {
        if (isDemo()) return VmRead.NotAvailable(NO_DEMO_WORKSPACE)
        if (!capabilities().workspaceFiles) return VmRead.NotAvailable(NEEDS_EXTENDED_MODE)
        val clean = path.trim().let { if (absolute) it else it.trimStart('/') }
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
        const val MACHINE_ASLEEP = "The agent's machine is asleep. Wake it, then try again."
        const val MACHINE_GONE = "The agent's machine is gone: the chat expired or was archived, and its VM went with it."
        const val NO_SUCH_FILE = "The agent's machine has no such file (any more)."

        /**
         * The words a failed VM read shows, and what they mean. Connect answers `not_found` with HTTP 404 too — the
         * Agents Window's own `cursor-worker` reader turns `ReadBinaryFile`'s `NotFound` into "File not found" — so
         * the code decides before the status: only `unimplemented`, a 404 with no code, or the server saying the
         * method "has been removed" (the words of the record read's two removals) is a method Cursor no longer
         * offers. A machine the account says is over (`cursorServerUrlReason=AGENT_EXPIRED` and the like, the reasons
         * `GetMachine` names) is gone; one that is not running, or still coming up, is asleep.
         */
        fun failure(t: Throwable): VmRead.Failed = when (t) {
            is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) VmRead.Failed(NEEDS_EXTENDED_MODE, kind = VmRead.FailureKind.NeedsExtendedMode) else VmRead.Failed(t.message ?: "Cursor couldn't start a session for this key.")
            is ConnectRpcException -> {
                val reason = MachineUnavailableReason.parse(t.message)
                val asked = askedOf(t)
                when {
                    reason?.isExpired == true -> VmRead.Failed(MACHINE_GONE, kind = VmRead.FailureKind.MachineGone, asked = asked)
                    reason?.isPreparing == true || reason == MachineUnavailableReason.MACHINE_NOT_PROVISIONED -> VmRead.Failed(MACHINE_ASLEEP, kind = VmRead.FailureKind.MachineAsleep, asked = asked)
                    t.isRemoved -> VmRead.Failed(ENDPOINT_CHANGED, endpointChanged = true, kind = VmRead.FailureKind.EndpointChanged, asked = asked)
                    t.code == "not_found" -> VmRead.Failed(NO_SUCH_FILE, kind = VmRead.FailureKind.NotFound, asked = asked)
                    t.code in ASLEEP_CODES -> VmRead.Failed(MACHINE_ASLEEP, kind = VmRead.FailureKind.MachineAsleep, asked = asked)
                    else -> VmRead.Failed("Cursor refused (${t.message}).", asked = asked)
                }
            }
            is IOException -> VmRead.Failed(t.userMessage())
            else -> VmRead.Failed(t.message ?: "Something went wrong.")
        }

        /** The method itself is gone, as opposed to this call refused. */
        private val ConnectRpcException.isRemoved: Boolean
            get() = code == "unimplemented" || (httpCode == 404 && code == null) || message?.contains("has been removed", ignoreCase = true) == true

        /**
         * The one line that settles which call it was and what came back, as the transcript's record notice prints
         * it: `POST /aiserver.v1.BackgroundComposerService/ReadBinaryFile → HTTP 404 not_found "File not found"`.
         */
        fun askedOf(t: ConnectRpcException): String =
            "POST ${t.path ?: "(no request)"} → HTTP ${t.httpCode}" + (t.code?.let { " $it" } ?: "") + (t.message?.takeIf { it.isNotBlank() }?.let { " \"${it.take(120)}\"" } ?: "")

        /** The Connect codes a read of a machine that is not running comes back with. */
        private val ASLEEP_CODES = setOf("failed_precondition", "unavailable", "deadline_exceeded", "aborted")
    }
}
