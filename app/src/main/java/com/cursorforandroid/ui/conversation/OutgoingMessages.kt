package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.api.UploadedFile
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.AttachmentUploads
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.StagedFollowUp
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where a message sent from the composer stands between the tap and the server filing it, for the bubble the
 * transcript shows it in meanwhile (see [OutgoingMessages]). Null — no status — once the server has it.
 */
sealed interface OutgoingStatus {
    /** Its files are still going up: [done] of [total] are, the one in flight at [progress] (0–1). */
    data class Uploading(val done: Int, val total: Int, val progress: Float) : OutgoingStatus

    /** Every file up, or none to put up; the request is on its way. */
    data object Sending : OutgoingStatus

    /** Did not get through — an upload, or the send itself — with the reason. The bubble offers Retry and Edit. */
    data class Failed(val message: String) : OutgoingStatus
}

/**
 * The messages on their way out of one chat's composer. Bennett's report: a message with files went out, "but the
 * file attachment or attachments will still sit in that chat composer for a while… at least a couple seconds… you
 * don't want that in the chat composer while a message is actively being sent because at that time the user could
 * possibly be trying to draft a new message". The composer used to keep the chips until the send's request had
 * returned — text and images cleared at the tap, files only on success — so at 300–900 ms of latency, and longer
 * under retries, the sent files sat in the composer, their crosses still live (a tap on one aborted an upload the
 * message in flight referenced), and a new draft's chips piled in beside them.
 *
 * The rule now: the tap empties the composer, and from then on the message is the transcript's — the bubble the
 * repository shows ahead of the request ([ConversationRepository.stageFollowUp]) carries its text and attachments,
 * and this carries where its send stands ([statuses], by the bubble's id): its files still going up, the request
 * on its way, or a failure with the reason — the bubble then offers a retry of the send and an edit that hands the
 * draft back to the composer, so nothing is lost without a word. A message may be sent while its files are still
 * uploading; the send waits for them, on the bubble, not in the composer. A draft begun meanwhile is its own: its
 * own chips, its own uploads. Sends go out in the order they were tapped ([Ticket]), whatever their uploads and
 * staging take, so the account sees the conversation in the order it was written.
 *
 * @param stage shows the message in the transcript ahead of its request, and stages its attachments.
 * @param onSent a message the server has taken (or queued); the composer settles what the send consumed (a model override).
 * @param onBusy the documented run request refused the message as busy: it goes to a queue, as the composer would have sent it had it known.
 * @param onReturned an edit: the draft is the composer's again.
 */
class OutgoingMessages(
    private val agentId: String,
    private val scope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val uploads: AttachmentUploads,
    private val onSent: (Outgoing) -> Unit = {},
    private val onBusy: (Draft) -> Unit = {},
    private val onReturned: (Draft) -> Unit = {},
) {
    /** What the composer held when send was tapped. [text] is what the message says; [typed] what the user wrote (an attachment-only message says what it carries). */
    class Draft(
        val text: String,
        val typed: String,
        val images: List<PendingAttachment>,
        val files: List<PendingFile>,
        val options: FollowUpModelState,
    )

    /** How the message reaches the server. */
    sealed interface Route {
        /**
         * The account's follow-up (`AddAsyncFollowupBackgroundComposer`): what carries files, a mode, or a message
         * for a busy agent's queue. [rpc] is given the files as uploaded and returns the run the account started —
         * null when it queued the message behind a turn, in which case the bubble comes down and the queue's card
         * shows it (see [ConversationRepository.sendStagedVia]).
         */
        class Account(val rpc: suspend (uploaded: List<UploadedFile>) -> String?) : Route

        /** The documented run request (`POST /v1/agents/{id}/followup`), with the MCP servers enabled when it goes out. */
        class Documented(val mcpServers: suspend () -> List<McpServer>) : Route
    }

    /** One message on its way: the draft it was, its route, and the bubble ([staged]) that stands for it. */
    class Outgoing internal constructor(val id: String, val draft: Draft, val route: Route, internal val staged: StagedFollowUp)

    /** Each message's turn: its request goes out once the one tapped before it has returned, whatever their uploads took. */
    private class Ticket(val previous: Deferred<Unit>?, val done: CompletableDeferred<Unit> = CompletableDeferred())

    private val _statuses = MutableStateFlow<Map<String, OutgoingStatus>>(emptyMap())

    /** Where each message not yet filed by the server stands, by its bubble's id. */
    val statuses: StateFlow<Map<String, OutgoingStatus>> = _statuses.asStateFlow()

    private val lock = Any()
    private val outgoing = HashMap<String, Outgoing>()
    private var tail: Deferred<Unit>? = null

    /** True while a message is going up or out (a failed one, waiting on the reader, does not count). */
    fun inFlight(statuses: Map<String, OutgoingStatus> = _statuses.value): Boolean = statuses.values.any { it !is OutgoingStatus.Failed }

    /**
     * Sends [draft] by [route]. Its place in the order is taken here, at the tap; the bubble follows as soon as the
     * attachments are staged, and the request once the message before it has returned and its files are up.
     */
    fun send(draft: Draft, route: Route): Job {
        val ticket = take()
        return scope.launch {
            val staged = try {
                conversations.stageFollowUp(agentId, draft.text, draft.images.map { it.image }, draft.files.map { it.file })
            } catch (e: CancellationException) {
                ticket.done.complete(Unit)
                throw e
            }
            val message = Outgoing(staged.localId, draft, route, staged)
            synchronized(lock) { outgoing[message.id] = message }
            run(message, ticket)
        }
    }

    /** Sends a failed message again — its uploads first, where one failed — in the same bubble. */
    fun retry(id: String) {
        val message = synchronized(lock) { outgoing[id] } ?: return
        if (_statuses.value[id] !is OutgoingStatus.Failed) return
        val ticket = take()
        scope.launch { run(message, ticket) }
    }

    /** Takes a failed message back: its bubble comes down and the draft is the composer's again ([onReturned]). */
    fun edit(id: String) {
        val message = synchronized(lock) { outgoing.remove(id) } ?: return
        if (_statuses.value[id] !is OutgoingStatus.Failed) {
            synchronized(lock) { outgoing[id] = message }
            return
        }
        _statuses.update { it - id }
        scope.launch { conversations.discardStaged(agentId, message.staged) }
        onReturned(message.draft)
    }

    private fun take(): Ticket = synchronized(lock) { Ticket(tail).also { tail = it.done } }

    private suspend fun run(message: Outgoing, ticket: Ticket) {
        val id = message.id
        val fileIds = message.draft.files.map { it.id }
        try {
            setStatus(id, if (fileIds.isEmpty()) OutgoingStatus.Sending else uploadingStatus(fileIds))
            ticket.previous?.join()
            val result = when (val route = message.route) {
                is Route.Account -> conversations.sendStagedVia(
                    agentId,
                    message.staged,
                    message.draft.options.override?.model?.id,
                    message.draft.options.override?.params.orEmpty(),
                    message.draft.options.override?.label,
                    discardOnFailure = false,
                ) {
                    val uploaded = awaitUploads(message)
                    setStatus(id, OutgoingStatus.Sending)
                    route.rpc(uploaded)
                }
                is Route.Documented -> conversations.sendStaged(
                    agentId,
                    message.staged,
                    message.draft.images.map { it.image },
                    route.mcpServers(),
                    planMode = message.draft.options.planMode,
                    modelId = message.draft.options.override?.model?.id,
                    modelParams = message.draft.options.override?.params.orEmpty(),
                    modelDisplayName = message.draft.options.override?.label,
                ).map { }
            }
            result.fold(
                onSuccess = {
                    synchronized(lock) { outgoing.remove(id) }
                    _statuses.update { it - id }
                    uploads.forget(fileIds)
                    onSent(message)
                },
                onFailure = { t ->
                    if (message.route is Route.Documented && t.toCursorError()?.code == AGENT_BUSY) {
                        // Not lost, and nothing to show: the message goes where the composer would have put it had it known.
                        synchronized(lock) { outgoing.remove(id) }
                        _statuses.update { it - id }
                        conversations.discardStaged(agentId, message.staged)
                        onBusy(message.draft)
                    } else {
                        setStatus(id, OutgoingStatus.Failed(t.userMessage()))
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } finally {
            ticket.done.complete(Unit)
        }
    }

    /** The files as the request names them, the bubble's status following their bytes meanwhile. */
    private suspend fun awaitUploads(message: Outgoing): List<UploadedFile> {
        val files = message.draft.files
        if (files.isEmpty()) return emptyList()
        val ids = files.map { it.id }
        val watcher = scope.launch { uploads.states.collect { setStatus(message.id, uploadingStatus(ids, it)) } }
        try {
            return uploads.awaitAll(files.map { it.id to it.file })
        } finally {
            watcher.cancel()
        }
    }

    private fun uploadingStatus(ids: List<String>, states: Map<String, AttachmentUploads.Status> = uploads.states.value): OutgoingStatus {
        val statuses = ids.map { states[it] }
        val done = statuses.count { it is AttachmentUploads.Status.Done }
        if (done == ids.size) return OutgoingStatus.Sending
        val current = statuses.firstOrNull { it is AttachmentUploads.Status.Uploading } as? AttachmentUploads.Status.Uploading
        return OutgoingStatus.Uploading(done, ids.size, current?.progress ?: 0f)
    }

    private fun setStatus(id: String, status: OutgoingStatus) {
        // A message taken back (edited) meanwhile is not put back by a late report.
        if (synchronized(lock) { id !in outgoing }) return
        _statuses.update { it + (id to status) }
    }

    private companion object {
        /** `POST /v1/agents/{id}/followup` while a run is under way: the message is queued rather than shown failed. */
        const val AGENT_BUSY = "agent_busy"
    }
}
