package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.isTransientFailure
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.DraftFile
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpComposerState
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.ProjectDiagnostics
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.RunningScan
import com.cursorforandroid.domain.SendDiagnostics
import com.cursorforandroid.domain.SendGate
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The follow-ups of each chat that have not reached the server: the composer's draft, and the queue.
 *
 * The Cloud Agents API runs one turn per agent at a time — `POST /v1/agents/{id}/runs` answers `409 agent_busy`
 * while a run is `CREATING` or `RUNNING`, and there is no server-side queue or steering for cloud runs (the SDK's
 * `steer` resolves `revert_to_followup` for them). So the queue lives here, like the desktop's: a follow-up sent
 * mid-turn is kept, shown above the composer, and goes out by itself the moment the turn ends, in order. Each queued
 * message carries the model pick and plan-mode flag that were in force when it was sent. "Steering" — sending a
 * queued message now — cancels the turn under way and sends it first.
 *
 * Whether the agent is free is read from its row in the agent list: [AgentRepository.followUp] marks it running,
 * the [LiveRunHub] marks it idle when the run it follows ends, a cancel marks it cancelled, and the list refresh
 * settles whatever happened elsewhere. While something is queued and the row says running, the run is followed
 * through the hub — the same shared stream the chat and the notification use — so the end of the turn is seen even
 * when no screen and no service is watching. The chat's own live state, while it is streaming, has the last word:
 * the row can be a poll behind.
 *
 * The draft is the composer's text and images as typed. Both draft and queue are written to the [FollowUpStore] so
 * leaving the chat, or the app, loses nothing; the demo backend's chats are not written, like its transcripts.
 */
class FollowUpRepository(
    private val conversations: ConversationRepository,
    private val agents: AgentRepository,
    private val hub: LiveRunHub,
    private val mcpServers: suspend () -> List<McpServer>,
    /**
     * Sends a queued message that carries files through the account's follow-up (Extended mode) — the documented run
     * request cannot take a file — and answers with the run the account named, if any. Null when there is no account
     * to send through; such a message then fails with [AgentRepository.FILES_NEED_EXTENDED] and waits for the user.
     */
    private val accountSend: (suspend (agentId: String, item: QueuedFollowUp) -> String?)? = null,
    /**
     * Files a queued message with the account's queue (Extended mode, `AddAsyncFollowupBackgroundComposer`) when the
     * server refuses the documented run request as busy while nothing here calls the agent busy: the account then
     * sends it the moment the agent is free, as the desktop's does, and the card above the composer gives way to
     * the account's row. Answers with the run the account started, or null when it queued the message. Null (default
     * mode, or the surface off) leaves the message waiting here, tried again with a growing pause (see [dispatch]).
     */
    private val accountQueue: (suspend (agentId: String, item: QueuedFollowUp) -> String?)? = null,
    /** Whether the account's queue may take a refused message right now (Extended mode on, not the demo). */
    private val accountQueueAvailable: suspend () -> Boolean = { false },
    private val store: FollowUpStore? = null,
    /** False (the demo) keeps everything in memory. */
    private val persist: () -> Boolean = { true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Typing is written this long after the last keystroke, not on every one. */
    private val draftSaveDelayMs: Long = DRAFT_SAVE_DELAY_MS,
    /** How long a message waits on a row that says the agent is busy before the record is read to settle the row. */
    private val idleSettleMs: Long = IDLE_SETTLE_MS,
    /** The first pause before a cancel or a send that failed for a passing reason is tried again; doubles each time. */
    private val retryBaseMs: Long = RETRY_BASE_MS,
) {
    private inner class Entry(val agentId: String) {
        val state = MutableStateFlow(FollowUpComposerState(restored = store == null || !persist()))
        var restoreJob: Job? = null
        var saveJob: Job? = null
        var dispatcher: Job? = null
        /** The last decision between sending and queueing made for this chat, and when (see [SendGate]). */
        @Volatile var lastDecision: SendGate.Decision? = null
        @Volatile var lastDecisionAt: Long = 0L
        /** The requests made for this chat's messages, newest last, the last [MAX_ATTEMPTS_KEPT] kept. */
        val attempts = ArrayDeque<SendDiagnostics.Attempt>()
        /** The ids the server answered a send with, newest last. */
        val accepted = ArrayDeque<String>()
        /** The pause the dispatcher has already waited out: the head's id and its [QueuedFollowUp.notBeforeMillis]. */
        @Volatile var pausedFor: Pair<String, Long>? = null

        fun attempt(item: QueuedFollowUp, via: String, outcome: String, detail: String? = null) = synchronized(attempts) {
            attempts.addLast(SendDiagnostics.Attempt(java.time.Instant.ofEpochMilli(AppClock.now()).toString(), ProjectDiagnostics.tail(item.id), via, outcome, detail?.let(::redact)))
            while (attempts.size > MAX_ATTEMPTS_KEPT) attempts.removeFirst()
        }

        fun acceptedId(id: String) = synchronized(attempts) {
            accepted.addLast(ProjectDiagnostics.tail(id))
            while (accepted.size > MAX_ATTEMPTS_KEPT) accepted.removeFirst()
        }
    }

    /** An error's words without any id, URL or quoted text, as the diagnostics export prints them. */
    private fun redact(text: String): String =
        text.replace(Regex("""bc-[A-Za-z0-9-]+"""), "bc-…").replace(Regex("""https?://[^\s)"]+"""), "<url>").replace(Regex("\"[^\"]*\""), "\"…\"").take(160)

    /** The send path's account of [agentId] for the diagnostics export (see [SendDiagnostics]); null for a chat never touched. */
    fun sendDiagnostics(agentId: String): SendDiagnostics? {
        val e = synchronized(entries) { entries[agentId] } ?: return null
        val now = AppClock.now()
        val queue = e.state.value.queue.map { q ->
            SendDiagnostics.QueueLine(ProjectDiagnostics.tail(q.id), q.text.length, q.isSending, q.isSteered, q.busyRefusals, q.heldSinceMillis?.let { now - it }, q.error?.let(::redact), q.needsConfirmation)
        }
        val (attempts, accepted) = synchronized(e.attempts) { e.attempts.toList() to e.accepted.toList() }
        return SendDiagnostics(e.lastDecision, e.lastDecisionAt.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() }, queue, attempts, accepted)
    }

    /**
     * The decision between sending now and queueing, for the composer's send (see [SendGate]): the same reading the
     * dispatcher makes, so the two never disagree, recorded for the diagnostics.
     */
    fun decide(agentId: String): SendGate.Decision {
        val decision = gate(agents.agent(agentId), conversations.state(agentId).value, agents.runningScan.value)
        val e = entry(agentId)
        e.lastDecision = decision
        e.lastDecisionAt = AppClock.now()
        return decision
    }

    private val entries = HashMap<String, Entry>()

    /**
     * The account everything held here belongs to. Captured when an operation starts and checked again before it
     * sends anything or writes anything to disk, so a send or a save that was in flight across a sign-out cannot
     * reach the next account's server or re-create the previous one's files behind [FollowUpStore.clear].
     */
    private val generation = AtomicInteger()

    /** Everything this repository has in flight, so [resetAll] can cancel all of it at once. */
    @Volatile private var work: CoroutineScope = workScope()

    private fun workScope(): CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private fun entry(agentId: String): Entry = synchronized(entries) {
        evictIdleEntries()
        entries.getOrPut(agentId) { Entry(agentId).also { restore(it) } }
    }

    /** Drops empty entries nobody is using, like [ConversationRepository.evictIdleEntries]. */
    private fun evictIdleEntries() {
        if (entries.size < MAX_ENTRIES) return
        entries.values.filter { e ->
            val s = e.state.value
            s.restored && s.draft.isEmpty && s.queue.isEmpty()
        }.take(entries.size - MAX_ENTRIES + 1).forEach { victim ->
            victim.dispatcher?.cancel()
            victim.saveJob?.cancel()
            victim.restoreJob?.cancel()
            entries.remove(victim.agentId)
        }
    }

    fun state(agentId: String): StateFlow<FollowUpComposerState> = entry(agentId).state.asStateFlow()

    // -- draft -------------------------------------------------------------------------------------------------------

    fun setDraftText(agentId: String, text: String) {
        val e = entry(agentId)
        e.update { copy(draft = draft.copy(text = text)) }
        e.scheduleSave(delayMs = draftSaveDelayMs)
    }

    fun setDraftImages(agentId: String, images: List<DraftImage>) {
        val e = entry(agentId)
        e.update { copy(draft = draft.copy(images = images)) }
        e.scheduleSave()
    }

    fun setDraftFiles(agentId: String, files: List<DraftFile>) {
        val e = entry(agentId)
        e.update { copy(draft = draft.copy(files = files)) }
        e.scheduleSave()
    }

    fun clearDraft(agentId: String) {
        val e = entry(agentId)
        e.update { copy(draft = FollowUpDraft.EMPTY) }
        e.scheduleSave()
    }

    /** Writes a draft still waiting for its debounce: called when the composer's screen goes away. */
    fun flush(agentId: String) {
        val e = synchronized(entries) { entries[agentId] } ?: return
        if (e.saveJob?.isActive == true) e.scheduleSave()
    }

    // -- queue -------------------------------------------------------------------------------------------------------

    /** Queues a follow-up behind whatever is already waiting; it goes out once the agent is free. */
    fun enqueue(
        agentId: String,
        text: String,
        images: List<DraftImage> = emptyList(),
        planMode: Boolean? = null,
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
        files: List<DraftFile> = emptyList(),
        /**
         * The composer's own send was just refused as busy while nothing here called the agent busy: the message
         * arrives already waiting — the card says so from its first frame, and the first attempt from here is a
         * pause away rather than at once, the server having just said no.
         */
        refusedAsBusy: Boolean = false,
    ): QueuedFollowUp {
        val now = AppClock.now()
        val item = QueuedFollowUp(
            id = "queued-" + UUID.randomUUID(),
            text = text.trim(),
            images = images,
            files = files,
            queuedAtMillis = now,
            planMode = planMode,
            modelId = modelId,
            modelParams = modelParams,
            modelDisplayName = modelDisplayName,
            heldSinceMillis = if (refusedAsBusy) now else null,
            busyRefusals = if (refusedAsBusy) 1 else 0,
            notBeforeMillis = if (refusedAsBusy) now + retryBaseMs else null,
        )
        val e = entry(agentId)
        synchronized(e) {
            e.update { copy(queue = queue + item) }
            e.ensureDispatcher()
        }
        e.scheduleSave()
        return item
    }

    /** Takes a queued follow-up away. One in flight, or steered, stays until the server has answered. */
    fun remove(agentId: String, id: String) {
        val e = entry(agentId)
        synchronized(e) {
            e.update { copy(queue = queue.filterNot { it.id == id && !it.isSending && !it.isSteered }) }
        }
        e.scheduleSave()
    }

    /**
     * Hands a queued follow-up back to the composer to be reworked: it leaves the queue, and whatever the composer
     * held takes its place in line so nothing typed is lost. Returns null when the message is gone or in flight.
     */
    fun takeForEdit(agentId: String, id: String): QueuedFollowUp? {
        val e = entry(agentId)
        var taken: QueuedFollowUp? = null
        synchronized(e) {
            e.update {
                val item = queue.firstOrNull { it.id == id && !it.isSending && !it.isSteered } ?: return@update this
                taken = item
                val displaced = draft.takeUnless { it.isEmpty }?.let { d ->
                    item.copy(id = "queued-" + UUID.randomUUID(), text = d.text.trim(), images = d.images, files = d.files, queuedAtMillis = AppClock.now(), error = null)
                }
                copy(
                    draft = FollowUpDraft(item.text, item.images, item.files),
                    queue = queue.flatMap { q -> if (q.id != id) listOf(q) else listOfNotNull(displaced) },
                )
            }
        }
        e.scheduleSave()
        return taken
    }

    /** Clears a failed follow-up's error so the dispatcher tries it again. */
    fun retry(agentId: String, id: String) {
        val e = entry(agentId)
        synchronized(e) {
            e.update {
                copy(queue = queue.map { if (it.id == id) it.copy(error = null, needsConfirmation = false, sendStartedAtMillis = null, heldSinceMillis = null, busyRefusals = 0, serverReason = null, notBeforeMillis = null) else it })
            }
            e.ensureDispatcher()
        }
    }

    /**
     * Steers: sends a queued follow-up now rather than in its turn. The message leaves the cards at once and shows in
     * the transcript as a pending prompt, faded, as if sent; when the agent is on a turn that turn is cancelled — the
     * API has no way to hand a message to a run in progress — and the request goes out the moment the agent is free,
     * ahead of everything else queued. The bubble comes up to full strength once the server has filed the run. Should
     * the cancel or the send not go through, the message returns to the cards with the reason. All of it runs in the
     * repository's own scope, so neither the screen's thread nor its lifetime is involved. False when [id] is not a
     * queued message that could be steered.
     */
    fun sendNow(agentId: String, id: String): Boolean {
        val startedIn = generation.get()
        val e = entry(agentId)
        val item = synchronized(e) {
            val found = e.state.value.queue.firstOrNull { it.id == id && !it.isSending && !it.isSteered } ?: return@synchronized null
            val steered = found.copy(error = null, isSteered = true)
            e.update { copy(queue = listOf(steered) + queue.filterNot { it.id == id }) }
            e.ensureDispatcher()
            steered
        } ?: return false
        e.scheduleSave()
        work.launch { steer(e, item, startedIn) }
        return true
    }

    private suspend fun steer(e: Entry, item: QueuedFollowUp, startedIn: Int) {
        if (generation.get() != startedIn) return
        val staged = conversations.stageFollowUp(e.agentId, item.previewText, item.images.map { it.image }, item.files.map { it.file })
        stopTurn(e).exceptionOrNull()?.let { t ->
            if (t is CancellationException) throw t
            unsteer(e, item, staged, t)
            return
        }
        sendSteered(e, item, staged, startedIn)
    }

    /**
     * Stops the turn under way, if there is one, so the steered message goes next. The run cancelled is the one the
     * server is on as far as this device knows ([ConversationRepository.runToCancel]); a chat still being created has
     * none, and is left to start — the message waits for its first turn like any other. A cancel the server refuses
     * because that run is over already (`run_not_cancellable`, or a run it no longer finds) is no failure of the
     * steer: the row was behind, so it is settled against the record, and should another turn be under way by then
     * that one is stopped in turn — until the agent is free, or the only run known is one the server has said is
     * over, which [sendSteered] then waits out. A cancel that fails for a passing reason — the server slow or down —
     * is asked again a few times, with a pause that doubles each time, before the steer is given up with the reason.
     */
    private suspend fun stopTurn(e: Entry): Result<Unit> {
        val over = HashSet<String>()
        var retries = 0
        while (true) {
            if (isIdleNow(e.agentId)) return Result.success(Unit)
            val target = conversations.runToCancel(e.agentId)
            if (target == null || target in over) {
                // Nothing left to stop that the server would recognise: the row is behind, or has no run to name yet.
                settleRow(e.agentId)
                val next = conversations.runToCancel(e.agentId)
                if (next == null || next in over || isIdleNow(e.agentId)) return Result.success(Unit)
                continue
            }
            val t = conversations.cancelRun(e.agentId, target).exceptionOrNull() ?: return Result.success(Unit)
            if (t is CancellationException) throw t
            val error = t.toCursorError()
            when {
                error?.code == RUN_NOT_CANCELLABLE || error?.httpCode == 404 -> {
                    // The turn ended by itself while the cancel was on its way: the message goes out all the same, so
                    // that is not a failure to report. The chat is brought up to date so it shows the turn as finished.
                    over += target
                    conversations.revalidate(e.agentId)
                }
                t.isTransientFailure() && retries < MAX_CANCEL_RETRIES -> delay(retryBaseMs shl retries++)
                else -> return Result.failure(t)
            }
        }
    }

    /** A steer that did not go through: the bubble comes down and the message is back among the cards, with the reason. */
    private suspend fun unsteer(e: Entry, item: QueuedFollowUp, staged: StagedFollowUp, cause: Throwable) {
        conversations.discardStaged(e.agentId, staged)
        e.update { copy(queue = queue.map { if (it.id == item.id) it.copy(isSteered = false, isSending = false, error = cause.userMessage()) else it }) }
    }

    /**
     * Sends a steered message once the agent is free, waiting out any turn the server still reports; see [sendNow].
     * A send that fails for a passing reason — the agent still winding down the turn that was cancelled for it, the
     * server slow — is tried again a few times, with a pause that doubles each time, before the steer is given up.
     */
    private suspend fun sendSteered(e: Entry, item: QueuedFollowUp, staged: StagedFollowUp, startedIn: Int) {
        var retries = 0
        var busyStreak = 0
        while (true) {
            awaitIdle(e.agentId)
            // The account this steer belongs to has been signed out; the message is not the next one's to send.
            if (generation.get() != startedIn) return
            val startedAt = AppClock.now()
            e.update { copy(queue = queue.map { if (it.id == item.id) it.copy(sendStartedAtMillis = startedAt) else it }) }
            e.scheduleSave()
            val result = sendStagedItem(e, staged, item)
            val t = result.exceptionOrNull()
            when {
                t == null -> {
                    e.attempt(item, VIA_STEER, "accepted", result.getOrNull())
                    result.getOrNull()?.let { e.acceptedId(it) }
                    e.update { copy(queue = queue.filterNot { it.id == item.id }) }
                    e.scheduleSave()
                    return
                }
                t is CancellationException -> throw t
                t.toCursorError()?.code == AGENT_BUSY -> {
                    e.attempt(item, VIA_STEER, "busy", t.toCursorError()?.message)
                    awaitBusyTurn(e.agentId, busyStreak++)
                }
                // A message with files went through the account's follow-up, whose failure has already taken the
                // bubble down (see [ConversationRepository.sendStagedVia]): it is not sent again under a bubble that is gone.
                t.isTransientFailure() && retries < MAX_SEND_RETRIES && item.files.isEmpty() -> {
                    // The answer never came: the server may have the message. A message it took is never sent again.
                    if (conversations.wasSentSince(e.agentId, item.previewText, startedAt).getOrNull() == true) {
                        e.attempt(item, VIA_STEER, "accepted-confirmed", "the transcript holds it")
                        e.update { copy(queue = queue.filterNot { it.id == item.id }) }
                        e.scheduleSave()
                        conversations.revalidate(e.agentId)
                        return
                    }
                    e.attempt(item, VIA_STEER, "retry", t.userMessage())
                    delay(retryBaseMs shl retries++)
                }
                else -> {
                    e.attempt(item, VIA_STEER, "failed", t.userMessage())
                    unsteer(e, item, staged, t)
                    return
                }
            }
        }
    }

    /**
     * Sends a staged queued message: the documented run request, or — for one that carries files — the account's
     * follow-up through [accountSend], which alone can take them.
     */
    private suspend fun sendStagedItem(e: Entry, staged: StagedFollowUp, item: QueuedFollowUp): Result<String?> {
        if (item.files.isEmpty()) {
            return conversations.sendStaged(
                e.agentId,
                staged,
                item.images.map { it.image },
                mcpServers = mcpServers(),
                planMode = item.planMode,
                modelId = item.modelId,
                modelParams = item.modelParams,
                modelDisplayName = item.modelDisplayName,
            ).map { it.id }
        }
        val send = accountSend ?: return conversations.sendStagedVia(e.agentId, staged) { throw IllegalStateException(AgentRepository.FILES_NEED_EXTENDED) }.map { null }
        var named: String? = null
        return conversations.sendStagedVia(e.agentId, staged, item.modelId, item.modelParams, item.modelDisplayName) { send(e.agentId, item).also { named = it } }.map { named }
    }

    /**
     * Waits for the agent to be free. The row is normally corrected the moment the turn ends — by the run's stream
     * through the hub, or by the list's next refresh — but a wait that drags on reads the record and settles the row
     * from it, so a row a poll behind can never hold a message until a refresh happens by.
     */
    private suspend fun awaitIdle(agentId: String) {
        while (true) {
            if (withTimeoutOrNull(idleSettleMs) { isIdle(agentId).first { it } } == null) {
                settleRow(agentId)
            } else if (isIdleNow(agentId)) {
                return
            }
        }
    }

    /**
     * Puts the row where the server has the agent — its record and its latest run, read now — and the open chat with
     * it. Best effort. A run the hub has seen end is over whatever the record read says: the record can be a moment
     * behind the stream, and a row put back to running by it would stay so, since the hub reports a run's end once.
     */
    private suspend fun settleRow(agentId: String) {
        if (settleFromHub(agentId)) return
        agents.loadDetail(agentId)
        settleFromHub(agentId)
        conversations.revalidate(agentId)
    }

    /** The row says running about a run the hub saw end: the row is put right from what the hub knows. True when it was. */
    private fun settleFromHub(agentId: String): Boolean {
        val row = agents.agent(agentId)?.takeIf { it.isRunning } ?: return false
        val runId = row.latestRunId ?: return false
        val ended = hub.current(agentId, runId)?.takeIf { it.finished } ?: return false
        agents.patch(agentId) { if (it.latestRunId == runId && it.isRunning) it.copy(runStatus = ended.status, lifecycle = AgentLifecycle.IDLE) else it }
        return true
    }

    /**
     * The row said idle and the server disagreed. Until 0.3.34 the row was patched to running on the server's word
     * and read again ten seconds later — a status this device made up, which the next read of the record undid, and
     * the message went round that loop for as long as the server kept refusing: queued, sending, queued (Bennett,
     * v0.3.33). Now nothing is written to the row: a steered message waits [busyPause] for its [busyStreak], then
     * the record and the agent's detail are read so the row is where the server has it, and it is tried again. (A
     * queued message's pause is [QueuedFollowUp.notBeforeMillis], which [dispatchLoop] waits out the same way.)
     * Nor does the record put the row back to running for the run this device stopped (see
     * `AgentRepository.cancelRun`): a steer's send refused while the cancelled turn winds down is paced by this pause
     * alone, cut short by the turn's end when the hub sees it.
     */
    private suspend fun awaitBusyTurn(agentId: String, busyStreak: Int = 0) {
        awaitPauseOrFinish(agentId, busyPause(busyStreak))
        settleRow(agentId)
    }

    /**
     * Waits [pauseMs], or less: a run of the chat ending meanwhile — the hub reports the finish of every run it
     * follows live, the turn a steer cancelled among them — is the moment the server is most likely free, and is not
     * waited past. A pause cut short is still a pause taken: the next refusal's is longer.
     */
    private suspend fun awaitPauseOrFinish(agentId: String, pauseMs: Long) {
        if (pauseMs <= 0) return
        withTimeoutOrNull(pauseMs) { hub.finishes.first { it.agentId == agentId } }
    }

    /** The pause before the attempt after [busyStreak] refusals in a row: [retryBaseMs] doubled each time, to [MAX_BUSY_PAUSE_MS]. */
    private fun busyPause(busyStreak: Int): Long = (retryBaseMs shl busyStreak.coerceIn(0, MAX_BUSY_BACKOFF_STEPS)).coerceAtMost(MAX_BUSY_PAUSE_MS)

    /** Drops everything held for an agent, on disk too; used when it is deleted. */
    fun forget(agentId: String) {
        val e = synchronized(entries) { entries.remove(agentId) } ?: return
        e.dispatcher?.cancel()
        e.saveJob?.cancel()
        e.restoreJob?.cancel()
        work.launch { store?.remove(agentId) }
    }

    /**
     * Signing out: nothing of the account stays in memory. Every dispatcher, save, restore and steer is cancelled
     * together — a steer is launched on the same scope rather than tracked one job at a time — and the generation is
     * bumped first, so work that is already past a cancellation point cannot send into the account signing in or
     * write the previous one's draft back behind the [FollowUpStore.clear] the caller does next.
     */
    fun resetAll() {
        generation.incrementAndGet()
        synchronized(entries) { entries.clear() }
        work.cancel()
        work = workScope()
    }

    // -- persistence -------------------------------------------------------------------------------------------------

    private inline fun Entry.update(transform: FollowUpComposerState.() -> FollowUpComposerState) = state.update { it.transform() }

    /**
     * Folds the disk copy in. The user may have typed, or queued, before the file was read: what is in memory is
     * newer and stays; the saved draft only fills an empty composer, and the saved queue goes ahead of anything
     * queued since. Nothing is written back before this has run, so an early save cannot wipe the file.
     */
    private fun restore(e: Entry) {
        val store = store?.takeIf { persist() } ?: return
        val startedIn = generation.get()
        e.restoreJob = work.launch {
            val saved = runCatching { store.read(e.agentId) }.getOrNull()
            if (generation.get() != startedIn) return@launch
            synchronized(e) {
                e.update {
                    val known = queue.mapTo(HashSet()) { it.id }
                    copy(
                        draft = if (draft.isEmpty && saved != null) saved.draft else draft,
                        queue = saved?.queue?.filter { it.id !in known }.orEmpty() + queue,
                        restored = true,
                    )
                }
            }
            reconcileInflight(e, startedIn)
            synchronized(e) {
                if (e.state.value.queue.any { it.error == null && !it.needsConfirmation }) e.ensureDispatcher()
            }
            // Whatever changed while the file was being read is now written on top of it (a no-op when nothing did).
            e.scheduleSave()
        }
    }

    /**
     * A send marker on disk means the request may have got through before the process died. Ask the server before
     * anything goes out again; drop the item when the answer is yes, flag it when the answer is no or unknown.
     */
    private suspend fun reconcileInflight(e: Entry, startedIn: Int) {
        val pending = synchronized(e) { e.state.value.queue.filter { it.sendStartedAtMillis != null } }
        for (item in pending) {
            if (generation.get() != startedIn) return
            val since = item.sendStartedAtMillis ?: continue
            val sent = conversations.wasSentSince(e.agentId, item.previewText, since)
            synchronized(e) {
                if (generation.get() != startedIn) return
                when {
                    sent.getOrNull() == true -> e.update { copy(queue = queue.filterNot { it.id == item.id }) }
                    else -> e.update {
                        copy(queue = queue.map { if (it.id == item.id) it.copy(isSending = false, needsConfirmation = true) else it })
                    }
                }
            }
        }
    }

    private fun Entry.scheduleSave(delayMs: Long = 0L) {
        val store = store?.takeIf { persist() } ?: return
        val startedIn = generation.get()
        saveJob?.cancel()
        saveJob = work.launch {
            if (delayMs > 0) delay(delayMs)
            val snapshot = state.first { it.restored }
            // The file this would write belongs to an account that has been signed out, and whose directory the
            // sign-out has already deleted.
            if (generation.get() != startedIn) return@launch
            runCatching { store.write(agentId, snapshot.draft, snapshot.queue) }.onFailure { if (it is CancellationException) throw it }
        }
    }

    // -- dispatch ----------------------------------------------------------------------------------------------------

    /**
     * Whether a follow-up can go out now. The row is the arbiter; the chat's live state overrules it only while it
     * is actually streaming the run — a row a poll behind says idle then, and the chat knows better.
     */
    private fun isIdle(agentId: String): Flow<Boolean> = combine(
        agents.state.map { s -> s.agents.firstOrNull { it.id == agentId } }.distinctUntilChanged(),
        conversations.state(agentId),
        agents.runningScan,
    ) { row, chat, scan -> gate(row, chat, scan).idle }.distinctUntilChanged()

    /**
     * [isIdle] as of this instant. A flow's answer can be a step behind the state it is built from — a send that was
     * just accepted marks the row running before the queue moves on, and the two reach a collector in either order —
     * so the moment before a request goes out is checked against the values themselves.
     */
    private fun isIdleNow(agentId: String): Boolean = decideNow(agentId).idle

    private fun decideNow(agentId: String): SendGate.Decision {
        val decision = gate(agents.agent(agentId), conversations.state(agentId).value, agents.runningScan.value)
        synchronized(entries) { entries[agentId] }?.let { it.lastDecision = decision; it.lastDecisionAt = AppClock.now() }
        return decision
    }

    /** One reading of every source the decision has (see [SendGate]). */
    private fun gate(row: Agent?, chat: ConversationState, scan: RunningScan): SendGate.Decision = SendGate.decide(
        SendGate.Inputs(
            rowLoaded = row != null,
            rowRunning = row?.isRunning == true,
            rowUpdatedAtMillis = row?.updatedAtMillis ?: 0L,
            chatRunStatus = chat.runStatus,
            chatStreaming = chat.isStreaming,
            chatReconnecting = chat.isReconnecting,
            accountScanned = row != null && scan.accountWord[row.id] != null,
            accountRunning = row != null && scan.accountWord[row.id]?.running == true,
            accountAtMillis = row?.let { scan.accountWord[it.id]?.atMillis } ?: 0L,
        ),
    )

    /** The run to follow while something is queued: the row's, while it is running. Never a prompt's local placeholder. */
    private fun runToFollow(agentId: String): Flow<String?> = agents.state
        .map { s -> s.agents.firstOrNull { it.id == agentId }?.takeIf { it.isRunning }?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) } }
        .distinctUntilChanged()

    /** Called under the entry's monitor. */
    private fun Entry.ensureDispatcher() {
        if (dispatcher?.isActive == true) return
        val startedIn = generation.get()
        dispatcher = work.launch { dispatchLoop(this@ensureDispatcher, startedIn) }
    }

    private suspend fun dispatchLoop(e: Entry, startedIn: Int) = coroutineScope {
        // Following the active run is what turns the end of the turn into an idle row when nothing else is watching.
        val follower = launch {
            runToFollow(e.agentId).collectLatest { runId ->
                if (runId != null) {
                    runCatching {
                        hub.snapshots(e.agentId, runId).transformWhile { emit(it); !it.finished }.collect { }
                    }.onFailure { if (it is CancellationException) throw it }
                }
            }
        }
        try {
            while (isActive) {
                val done = synchronized(e) {
                    val s = e.state.value
                    if (s.restored && (s.queue.isEmpty() || s.queue.all { it.error != null || it.needsConfirmation })) {
                        e.dispatcher = null
                        true
                    } else {
                        false
                    }
                }
                if (done) break
                // The head can go once it is restored, free of a failure, not already out, and the agent is free.
                // A steered message at the head is on its own way out (see [sendNow]); the rest wait behind it.
                val ready = combine(e.state, isIdle(e.agentId)) { s, idle -> s.restored && idle && s.headMayGo }
                if (withTimeoutOrNull(idleSettleMs) { ready.first { it } } == null) {
                    // Only the agent stands in the way: make sure the row is not behind the server about that.
                    if (e.state.value.let { it.restored && it.headMayGo }) settleRow(e.agentId)
                    continue
                }
                // A message the server refused as busy waits out its pause first — the card saying so the whole
                // time — and the row is put where the server has the agent before it is asked again. The pause is
                // waited once per refusal, as the wall clock has it, not re-read from the app's clock: a test's
                // clock stands still.
                val pending = e.state.value.queue.firstOrNull()?.let { h -> h.notBeforeMillis?.let { nb -> h.id to nb } }
                if (pending != null && e.pausedFor != pending) {
                    awaitPauseOrFinish(e.agentId, (pending.second - AppClock.now()).coerceIn(0L, MAX_BUSY_PAUSE_MS))
                    e.pausedFor = pending
                    settleRow(e.agentId)
                    continue
                }
                // Claiming the head and marking it sending are one step, under the monitor every other queue
                // operation takes: a steer, an edit or a removal either gets there first and this finds nothing to
                // claim, or arrives to a message that already reads as in flight and leaves it alone. The agent's
                // idleness is read as of now too, not as of the emission: a steered message that was just accepted
                // has marked the row running, and the next in line must not follow it out on a stale reading.
                val head = synchronized(e) {
                    val h = e.state.value.takeIf { it.restored && it.headMayGo && isIdleNow(e.agentId) }?.queue?.firstOrNull()
                        ?: return@synchronized null
                    val startedAt = AppClock.now()
                    e.update {
                        copy(queue = queue.map { if (it.id == h.id) it.copy(isSending = true, sendStartedAtMillis = startedAt) else it })
                    }
                    h.copy(isSending = true, sendStartedAtMillis = startedAt)
                } ?: continue
                e.scheduleSave()
                dispatch(e, head, startedIn)
            }
        } finally {
            follower.cancel()
        }
    }

    /** True when the message at the head is one the dispatcher may send: not failed, not already out, not steered, not awaiting the user's word. */
    private val FollowUpComposerState.headMayGo: Boolean
        get() = queue.firstOrNull()?.let { it.error == null && !it.isSending && !it.isSteered && !it.needsConfirmation } == true

    /**
     * Sends [item], which [dispatchLoop] has already claimed and marked sending.
     *
     * The prompt is not shown in the transcript ahead of the request — the card above the composer is what the
     * reader sees of a queued message — so a refusal takes nothing down; it appears, filed under its run, when the
     * server accepts. The outcomes:
     *  - accepted: the message leaves the queue; the run the server named is kept (see [SendDiagnostics]).
     *  - refused as busy while nothing here calls the agent busy — the server still winding down the last turn, or
     *    a server that says busy of an idle agent: in Extended mode the account's queue takes the message, once, and
     *    sends it when the agent is free; otherwise the message waits here, its card saying so, tried again after a
     *    pause that doubles with each refusal (to [MAX_BUSY_PAUSE_MS]), the server's own words on the card from the
     *    third refusal on.
     *  - lost in transit (the connection, the server slow): the server is asked whether the message arrived before
     *    anything is decided — a message it took is never sent again — and only then is the failure shown.
     *  - refused for any other reason: shown in the server's words; nothing behind it goes until the user decides.
     */
    private suspend fun dispatch(e: Entry, item: QueuedFollowUp, startedIn: Int) {
        if (generation.get() != startedIn) return
        val via = if (item.files.isEmpty()) VIA_RUN else VIA_ACCOUNT
        val result: Result<String?> = if (item.files.isEmpty()) {
            conversations.sendFollowUp(
                e.agentId,
                item.previewText,
                item.images.map { it.image },
                mcpServers = mcpServers(),
                planMode = item.planMode,
                modelId = item.modelId,
                modelParams = item.modelParams,
                modelDisplayName = item.modelDisplayName,
                showEcho = false,
            ).map { it.id }
        } else {
            val staged = conversations.stageFollowUp(e.agentId, item.previewText, item.images.map { it.image }, item.files.map { it.file }, show = false)
            sendStagedItem(e, staged, item)
        }
        if (generation.get() != startedIn) return
        result.fold(
            onSuccess = { runId ->
                e.attempt(item, via, "accepted", runId)
                runId?.let { e.acceptedId(it) }
                e.update { copy(queue = queue.filterNot { it.id == item.id }) }
                e.scheduleSave()
            },
            onFailure = { t ->
                if (t is CancellationException) throw t
                val error = t.toCursorError()
                when {
                    error?.code == AGENT_BUSY -> {
                        e.attempt(item, via, "busy", error.message)
                        if (accountQueue != null && accountQueueAvailable()) {
                            // The account's queue takes it, once: the row it shows above the composer replaces this card.
                            val handed = runCatching { accountQueue(e.agentId, item) }
                            if (generation.get() != startedIn) return
                            handed.fold(
                                onSuccess = { runId ->
                                    e.attempt(item, VIA_ACCOUNT, if (runId == null) "queued-on-account" else "accepted", runId)
                                    runId?.let { e.acceptedId(it) }
                                    e.update { copy(queue = queue.filterNot { it.id == item.id }) }
                                    e.scheduleSave()
                                    if (runId == null) conversations.reload(e.agentId)
                                    return
                                },
                                onFailure = { handoff ->
                                    if (handoff is CancellationException) throw handoff
                                    e.attempt(item, VIA_ACCOUNT, "failed", handoff.userMessage())
                                    // The account would not take it either: the message waits here as it would without one.
                                },
                            )
                        }
                        val refusals = item.busyRefusals + 1
                        val now = AppClock.now()
                        e.update {
                            copy(queue = queue.map {
                                if (it.id == item.id) {
                                    it.copy(
                                        isSending = false,
                                        sendStartedAtMillis = null,
                                        busyRefusals = refusals,
                                        heldSinceMillis = it.heldSinceMillis ?: now,
                                        notBeforeMillis = now + busyPause(item.busyRefusals),
                                        // The server's own reason, once it has refused three times: what the card says under the wait.
                                        serverReason = if (refusals >= REFUSALS_BEFORE_REASON) (error.message?.takeIf { m -> m.isNotBlank() } ?: it.serverReason) else it.serverReason,
                                    )
                                } else it
                            })
                        }
                        e.scheduleSave()
                    }
                    t.isTransientFailure() -> {
                        // The answer never came: the server may have the message all the same. Ask before deciding.
                        val since = item.sendStartedAtMillis ?: AppClock.now()
                        val sent = conversations.wasSentSince(e.agentId, item.previewText, since)
                        if (sent.getOrNull() == true) {
                            e.attempt(item, via, "accepted-confirmed", "the transcript holds it")
                            e.update { copy(queue = queue.filterNot { it.id == item.id }) }
                            e.scheduleSave()
                            conversations.revalidate(e.agentId)
                        } else {
                            e.attempt(item, via, if (sent.isFailure) "unknown" else "failed", t.userMessage())
                            e.update {
                                copy(queue = queue.map {
                                    if (it.id == item.id) it.copy(isSending = false, sendStartedAtMillis = null, error = t.userMessage(), needsConfirmation = sent.isFailure) else it
                                })
                            }
                            e.scheduleSave()
                        }
                    }
                    else -> {
                        e.attempt(item, via, "failed", t.userMessage())
                        e.update {
                            copy(queue = queue.map {
                                if (it.id == item.id) it.copy(isSending = false, sendStartedAtMillis = null, error = t.userMessage()) else it
                            })
                        }
                        e.scheduleSave()
                    }
                }
            },
        )
    }

    private companion object {
        const val MAX_ENTRIES = 24
        const val DRAFT_SAVE_DELAY_MS = 400L
        const val IDLE_SETTLE_MS = 10_000L
        /** The longest pause between two attempts the server keeps refusing as busy (see [awaitBusyTurn]). */
        const val MAX_BUSY_PAUSE_MS = 60_000L
        /** From this many refusals in a row the card carries the server's own reason under the wait. */
        const val REFUSALS_BEFORE_REASON = 3
        /** Attempts kept per chat for the diagnostics. */
        const val MAX_ATTEMPTS_KEPT = 24
        const val VIA_RUN = "runs"
        const val VIA_ACCOUNT = "account"
        const val VIA_STEER = "steer"
        const val RETRY_BASE_MS = 1_000L
        /** A cancel that failed for a passing reason is asked this many more times (1 s, then 2 s later). */
        const val MAX_CANCEL_RETRIES = 2
        /** A steered send that failed for a passing reason is tried this many more times (1 s, 2 s, then 4 s later). */
        const val MAX_SEND_RETRIES = 3
        /** How many doublings the pause between refused attempts takes before [MAX_BUSY_PAUSE_MS] caps it. */
        const val MAX_BUSY_BACKOFF_STEPS = 6
        const val AGENT_BUSY = "agent_busy"
        const val RUN_NOT_CANCELLABLE = "run_not_cancellable"
        /** The ids of the placeholder runs prompts sent from here are shown under until the server answers (see [ConversationRepository]). */
        const val LOCAL_RUN_PREFIX = "local-"
    }
}
