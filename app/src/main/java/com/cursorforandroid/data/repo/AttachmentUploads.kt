package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.UploadedFile
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.UploadRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * One run of one file's upload. An id has at most one at a time — the newest [start] for it. A chip taken off
     * and put back (a [cancel], then a [start] of the same id, the removed chip's upload perhaps still in flight)
     * makes a new one, and the old one, wherever it has got to, writes nothing more under the id: not its progress,
     * not a failure, not even its completion, whose upload it drops instead. Every write a run makes is guarded by
     * "am I still the attempt this id is on", under [lock], so nothing an older run does can reach a newer one's state.
     */
    private inner class Attempt(val id: String, val file: PromptFile) {
        /** Set under [lock] before the lock is released, and read only under it. */
        lateinit var job: Job
    }

    private val lock = Any()
    /**
     * The attempt each id is on while its upload runs. A terminal state — Done, Failed — takes the attempt off here
     * in the same step that writes it (see [settle]), so a [retry] or [start] that meets the state never finds a
     * finished attempt still registered in its way.
     */
    private val attempts = HashMap<String, Attempt>()
    private val files = HashMap<String, PromptFile>()
    /**
     * The running attempts in the order they were attached: the first is the one uploading, the rest wait for it.
     * The order is fixed here, under [lock], when the attempt is made — not by which coroutine happens to reach a
     * mutex first — so "one at a time, in the order attached" holds, and the composer's "Uploading 2 of 3" is right.
     */
    private val queue = ArrayDeque<Attempt>()
    /** The head of [queue]: the attempt whose turn it is. Its run waits here until it is. */
    private val turn = MutableStateFlow<Attempt?>(null)

    /**
     * Tracks [file] under [id] and starts its upload, unless it is tracked already or carries a reference, which
     * makes it done at once. Safe to call again for the same id.
     */
    fun start(id: String, file: PromptFile) {
        synchronized(lock) {
            if (id in attempts || _states.value[id] is Status.Done) return
            files[id] = file
            val ref = file.upload
            if (ref != null) {
                _states.update { it + (id to Status.Done(UploadedFile.of(file, ref))) }
                return
            }
            _states.update { it + (id to Status.Uploading(0f)) }
            val attempt = Attempt(id, file)
            attempts[id] = attempt
            queue.addLast(attempt)
            turn.value = queue.first()
            attempt.job = scope.launch { run(attempt) }
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
            attempts.remove(id)?.let { stop(it) }
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
                attempts.remove(id)?.let { stop(it) }
                files.remove(id)
            }
            _states.update { it - ids.toSet() }
        }
    }

    /**
     * The account is gone (a sign-out): every upload under way is cancelled — its staged parts dropped, as a cancel
     * drops them — and nothing is kept of any file. Completed uploads are the account's to expire; the session that
     * could abort them is gone with it.
     */
    fun resetAll() {
        synchronized(lock) {
            attempts.values.toList().forEach { stop(it) }
            attempts.clear()
            files.clear()
            queue.clear()
            turn.value = null
            _states.value = emptyMap()
        }
    }

    /** Under [lock]: [attempt] is no longer wanted — its run is cancelled and its place in the order given up. */
    private fun stop(attempt: Attempt) {
        attempt.job.cancel()
        leaveQueue(attempt)
    }

    /** Under [lock]: [attempt] is out of the order; the next in line, if any, gets its turn. */
    private fun leaveQueue(attempt: Attempt) {
        if (queue.remove(attempt)) turn.value = queue.firstOrNull()
    }

    /** Under [lock]: [transform] on the states, if [attempt] is still the one its id is on; false, and nothing written, otherwise. */
    private fun write(attempt: Attempt, transform: (Map<String, Status>) -> Map<String, Status>): Boolean {
        if (attempts[attempt.id] !== attempt) return false
        _states.update(transform)
        return true
    }

    /**
     * Under [lock]: [attempt]'s run is over. Its terminal state is written and the attempt taken off its id in the
     * one step — a retry or a start that meets the state finds no attempt registered against it — and it leaves the
     * order either way. False when the attempt had been superseded, in which case nothing is written.
     */
    private fun settle(attempt: Attempt, transform: (Map<String, Status>) -> Map<String, Status>): Boolean {
        val current = write(attempt, transform)
        if (current) attempts.remove(attempt.id)
        leaveQueue(attempt)
        return current
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

    private suspend fun run(attempt: Attempt) {
        val id = attempt.id
        try {
            turn.first { it === attempt }
            val uploaded = uploader().upload(attempt.file) { done, total ->
                val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 1f
                // A late report from a run that has been superseded — the chip taken off, and perhaps put back — writes nothing.
                synchronized(lock) { write(attempt) { it + (id to Status.Uploading(progress)) } }
            }
            val kept = synchronized(lock) { settle(attempt) { it + (id to Status.Done(uploaded)) } }
            // Superseded between the last part and here: the completed upload is dropped as a cancel drops one. The
            // run may well have been cancelled by then, so the abort is made regardless.
            if (!kept) uploaded.ref?.let { ref -> withContext(NonCancellable) { uploader().abort(ref) } }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            synchronized(lock) { settle(attempt) { it + (id to Status.Failed(t.message ?: t.userMessage())) } }
        } finally {
            // A cancelled run: off the order (its id was taken off it by the cancel); a settled one is off already.
            synchronized(lock) {
                if (attempts[id] === attempt) attempts.remove(id)
                leaveQueue(attempt)
            }
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
