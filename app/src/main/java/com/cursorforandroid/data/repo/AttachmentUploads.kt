package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.UploadedFile
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.UploadRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The uploads of the files attached to the composers, run the moment a file is attached rather than when its prompt
 * is sent. The desktop (Cursor 3.20.21) stages a file at submit — `registerCloudDocumentFile` only keeps the picked
 * `File` in a map, and `_sendFollowupRequest` / `_createAgentReal` run `PresignPromptUpload` → `PUT` → `CompletePromptUpload`
 * inside the request — which is why a phone's send used to sit greyed out for the length of the transfer. Here the
 * transfer starts on attach and runs in a scope no screen owns, so it goes on while the user types and across the
 * composer being left and come back to; the chip follows it, the send is held only while something is still going up,
 * and once every file carries its reference the prompt goes out at once, no upload in the way.
 *
 * Files go up one at a time in the order they were attached (the desktop's `WKy` loops its files the same way), so a
 * chip's ring reads as one progression and the composer can say "Uploading 2 of 3". A file taken off the composer
 * has its upload cancelled — the parts staged so far dropped with `AbortPromptUpload`, as the desktop's `imi` tracker
 * drops them — and a completed one aborted the same way. A file that did not get up keeps its place, marked, for a
 * retry of the upload alone. A file attached with a reference already (a draft restored after a restart) is done
 * from the start: nothing is uploaded twice.
 */
class AttachmentUploads(
    private val uploader: suspend () -> PromptUploader,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Where one attached file's upload stands. */
    sealed interface Status {
        /** Waiting its turn (0) or going up: the share of its bytes that are. */
        data class Uploading(val progress: Float) : Status

        /** Up — or carried inline, for an account with no upload path — as the prompt will name it. */
        data class Done(val file: UploadedFile) : Status

        /** Did not get up; [message] is the reason, worded for the composer. */
        data class Failed(val message: String) : Status
    }

    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Every tracked file's status by its attachment id; a file that was cancelled or forgotten has none. */
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val files = HashMap<String, PromptFile>()
    /** One file at a time, in the order attached. */
    private val turn = Mutex()

    /**
     * Tracks [file] under [id] and starts its upload, unless it is tracked already or carries a reference, which
     * makes it done at once. Safe to call again for the same id.
     */
    fun start(id: String, file: PromptFile) {
        synchronized(lock) {
            if (id in jobs || _states.value[id] is Status.Done) return
            files[id] = file
            val ref = file.upload
            if (ref != null) {
                _states.update { it + (id to Status.Done(UploadedFile.of(file, ref))) }
                return
            }
            _states.update { it + (id to Status.Uploading(0f)) }
            jobs[id] = scope.launch { run(id, file) }
        }
    }

    /** Starts a failed upload again; nothing for a file that is not failed. */
    fun retry(id: String) {
        synchronized(lock) {
            val file = files[id] ?: return
            if (_states.value[id] !is Status.Failed) return
            _states.update { it - id }
            start(id, file)
        }
    }

    /**
     * The file has left the composer: its upload stops, what was staged is dropped — including a completed upload the
     * prompt will now never reference — and nothing more is kept of it.
     */
    fun cancel(id: String) {
        val done: UploadRef?
        synchronized(lock) {
            jobs.remove(id)?.cancel()
            files.remove(id)
            done = (_states.value[id] as? Status.Done)?.file?.ref
            _states.update { it - id }
        }
        if (done != null) scope.launch { uploader().abort(done) }
    }

    /** The files went out with their prompt (or into its queue, carrying their references): nothing more is kept of them here. */
    fun forget(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            for (id in ids) {
                jobs.remove(id)?.cancel()
                files.remove(id)
            }
            _states.update { it - ids.toSet() }
        }
    }

    /** What the prompt names [id] as, once its upload is done; null while it is going up, failed, or unknown. */
    fun uploaded(id: String): UploadedFile? = (_states.value[id] as? Status.Done)?.file

    /** The reference [id]'s completed upload settled on; null while it is going up, failed, inline, or unknown. */
    fun ref(id: String): UploadRef? = uploaded(id)?.ref

    /**
     * What the prompt names each of [items] as, waiting for any still going up and trying a failed one again first:
     * with every file done — the case a send meets, since the send is held while one is not — it returns at once.
     * A file the account would not take throws its [PromptUploadException], the chip left marked for a retry.
     */
    suspend fun awaitAll(items: List<Pair<String, PromptFile>>): List<UploadedFile> = items.map { (id, file) -> await(id, file) }

    suspend fun await(id: String, file: PromptFile): UploadedFile {
        synchronized(lock) {
            if (_states.value[id] is Status.Failed) retry(id) else start(id, file)
        }
        val settled = _states.map { it[id] }.first { it !is Status.Uploading }
        return when (settled) {
            is Status.Done -> settled.file
            is Status.Failed -> throw PromptUploadException(file.name, settled.message)
            else -> throw PromptUploadException(file.name, "The file was taken off the message before it was sent.")
        }
    }

    private suspend fun run(id: String, file: PromptFile) {
        try {
            val uploaded = turn.withLock {
                uploader().upload(file) { done, total ->
                    val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 1f
                    // A file taken off the composer meanwhile is not put back by a late report of its bytes.
                    _states.update { if (id in it) it + (id to Status.Uploading(progress)) else it }
                }
            }
            val kept = synchronized(lock) {
                val tracked = id in files
                if (tracked) _states.update { it + (id to Status.Done(uploaded)) }
                tracked
            }
            // Taken off between the last part and here: the completed upload is dropped as a cancel drops one.
            if (!kept) uploaded.ref?.let { uploader().abort(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            synchronized(lock) {
                if (id in files) _states.update { it + (id to Status.Failed(t.message ?: t.userMessage())) }
            }
        } finally {
            synchronized(lock) { jobs.remove(id) }
        }
    }

    companion object {
        /**
         * The composer's word while something is still going up — `Uploading 2 of 3…`, the one in flight counted
         * among [ids] in the order attached — or null when nothing is.
         */
        fun hint(states: Map<String, Status>, ids: List<String>): String? {
            if (ids.isEmpty()) return null
            val statuses = ids.map { states[it] }
            val uploading = statuses.count { it is Status.Uploading }
            if (uploading == 0) return null
            val current = statuses.indexOfFirst { it is Status.Uploading } + 1
            return if (ids.size == 1) "Uploading…" else "Uploading $current of ${ids.size}…"
        }

        /** True while any of [ids] is still going up: the send waits on it. */
        fun isUploading(states: Map<String, Status>, ids: List<String>): Boolean = ids.any { states[it] is Status.Uploading }
    }
}
