package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.FollowupQueueApi
import com.cursorforandroid.data.api.GoalStateApi
import com.cursorforandroid.data.api.InteractionApi
import com.cursorforandroid.data.api.RunControlApi
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.InteractionResolution
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueueLoad
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.SteerPhase
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A chat's controls on the account service — what the desktop, the web and the iOS app can do to a running cloud
 * agent that the documented API cannot: answer the question it is waiting on, keep follow-ups in the account's queue
 * (reordered, reworded, sent now), steer the turn under way, hold and resume it, stop one tool call, wake the machine.
 *
 * Every one of them is an `api2` call, so each reads [Capabilities] first: with the surface off it answers the named
 * refusal [NEEDS_EXTENDED_MODE] and makes no call, the demo says [NOT_IN_DEMO], and a private endpoint Cursor has
 * changed underneath us degrades to [ENDPOINT_CHANGED] rather than a crash. What the account said is kept per chat in
 * [state] — the queue as last read, the last steer's outcome, what was answered or held from here — for the
 * conversation and its panel to show; the queue is re-read every [pollIntervalMs] while a screen is [attach]ed, and
 * after every edit made here, since the same queue is edited from every other client too.
 */
class SteeringRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val interactions: InteractionApi? = null,
    private val queueApi: FollowupQueueApi? = null,
    private val runs: RunControlApi? = null,
    /** The goal the account keeps on a chat, read with the queue on every poll; null leaves the goal to the transcript. */
    private val goals: GoalStateApi? = null,
    /** Runs after an action the transcript should reflect (an answer, a hold): the conversation's revalidation. */
    private val afterAction: suspend (String) -> Unit = {},
    /**
     * Hands every read of the account's queue to the transcript (`ConversationRepository.noteAccountQueue`) — what of
     * it stands (see [QueueReads]) — with when the read began: the transcript files the messages the account has
     * delivered and confirms the ones it has let go.
     */
    private val onQueueRead: suspend (agentId: String, pending: List<PendingFollowup>, readAtMillis: Long) -> Unit = { _, _, _ -> },
    /** A queued message the reader deleted from the card, once the account has taken it off its queue (`ConversationRepository.queuedDeleted`). */
    private val onQueuedDeleted: (agentId: String, followupId: String) -> Unit = { _, _ -> },
    /** A queued message the reader edited on the card, once the account holds the new words (`ConversationRepository.queuedEdited`). */
    private val onQueuedEdited: (agentId: String, followupId: String, text: String) -> Unit = { _, _, _ -> },
    /** A word under a queued message's row the account kept rather than steering it (`ConversationRepository.noteQueuedNote`). */
    private val onQueuedNote: (agentId: String, followupId: String, note: String) -> Unit = { _, _, _ -> },
    /**
     * Where the transcript says each queued message stands (`ConversationRepository.queuePlacement`): a message it has
     * just filed under a run is the moment the account's queue is read again, whatever the poll's clock says (see
     * [QueuePlacement]). Null leaves the poll to its interval.
     */
    private val placement: ((agentId: String) -> StateFlow<QueuePlacement>)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    /** Whether the account's calls may be made (Extended mode). Everything, for tests of the calls themselves. */
    private val capabilities: suspend () -> Capabilities = { Capabilities.EXTENDED },
    /** A clock that only moves forward, for how long ago the goal was read. */
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<ConversationControls>>()
    private val attached = ConcurrentHashMap<String, Int>()
    private val pollers = ConcurrentHashMap<String, Job>()
    /** Per chat: when the goal was last asked for, by [monotonicMillis]. */
    private val goalReadAt = ConcurrentHashMap<String, Long>()
    /** Per chat: its reads of the queue, and what they have told this device (see [QueueReads]). */
    private val queueReads = ConcurrentHashMap<String, QueueReads>()

    init {
        scope.launch { session.backend.drop(1).collect { reset() } }
    }

    private fun flow(agentId: String): MutableStateFlow<ConversationControls> = states.getOrPut(agentId) { MutableStateFlow(ConversationControls.EMPTY) }

    /** What the account has said about one chat's controls, kept current while a screen is attached. */
    fun state(agentId: String): StateFlow<ConversationControls> = flow(agentId).asStateFlow()

    /** What the surfaces may do right now, for a screen deciding what to offer. */
    suspend fun capabilities(): Capabilities = capabilities.invoke()

    /**
     * A chat's screen is on screen (started, not merely alive on the back stack or behind a backgrounded app): its
     * queue is read now and every [pollIntervalMs] while it is (the account's list is edited from every client, and
     * `StreamBackgroundComposerUpdates` would replace the poll once a Connect streaming client exists); its goal now
     * too unless read within the goal's own interval. Balanced by [detach] when the screen stops.
     */
    fun attach(agentId: String) {
        val count = attached.merge(agentId, 1, Int::plus) ?: 1
        if (count > 1) return
        pollers[agentId]?.cancel()
        pollers[agentId] = scope.launch {
            // A message the transcript files under its run has left the card in that very frame; the account's queue
            // is read again at once, so the card's word and the account's agree without waiting on the poll.
            placement?.let { placed ->
                launch {
                    placed(agentId).map { it.deliveredIds + it.deliveredTexts }.distinctUntilChanged().drop(1)
                        .collect { delivered -> if (delivered.isNotEmpty()) refreshQueue(agentId) }
                }
            }
            while (isActive) {
                refreshQueue(agentId)
                // The goal moves slowly and its record is the conversation's whole state structure: read once it is
                // [GOAL_POLL_EVERY] polls old — on the first poll unless a screen that just stopped read it — the
                // transcript's own reading carrying the strip in between.
                val lastGoal = goalReadAt[agentId]
                val now = monotonicMillis()
                // Half a poll short of the interval, so the poll that lands on it is never skipped for a rounded millisecond.
                if (lastGoal == null || now - lastGoal >= GOAL_POLL_EVERY * pollIntervalMs - pollIntervalMs / 2) {
                    goalReadAt[agentId] = now
                    refreshGoal(agentId)
                }
                delay(pollIntervalMs)
            }
        }
    }

    // ---- the goal ---------------------------------------------------------------------------------------------------

    /**
     * One read of the goal the account keeps on [agentId] (`GetLatestAgentConversationState`), Extended mode only:
     * with the surface off, in the demo, or when the account would not answer, nothing is recorded and the strip goes
     * by the chat's own transcript. Once the account has answered, its word stands — a goal, or none.
     */
    suspend fun refreshGoal(agentId: String) {
        if (session.isDemo || !capabilities().accountGoal) return
        val api = goals ?: return
        val f = flow(agentId)
        try {
            val goal = api.goal(agentId)
            f.update { it.copy(goal = goal, goalKnown = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A refusal or a changed endpoint leaves the transcript's reading in place; the next poll asks again.
        }
    }

    fun detach(agentId: String) {
        val count = attached.merge(agentId, -1, Int::plus) ?: 0
        if (count > 0) return
        attached.remove(agentId)
        pollers.remove(agentId)?.cancel()
    }

    // ---- the queue --------------------------------------------------------------------------------------------------

    /** One read of the account's queue for [agentId]; with the surface off the state says so and nothing is called. */
    suspend fun refreshQueue(agentId: String) {
        val f = flow(agentId)
        if (session.isDemo) {
            f.update { it.copy(queue = emptyList(), queueLoad = QueueLoad.Unavailable(NOT_IN_DEMO)) }
            return
        }
        if (!capabilities().accountQueue) {
            f.update { it.copy(queue = emptyList(), queueLoad = QueueLoad.Unavailable(NEEDS_EXTENDED_MODE)) }
            return
        }
        val api = queueApi ?: run {
            f.update { it.copy(queue = emptyList(), queueLoad = QueueLoad.Unavailable(NOT_WIRED)) }
            return
        }
        f.update { it.copy(queueLoad = QueueLoad.Loading) }
        // When the read began, by the app's clock: the transcript reads the answer against what happened meanwhile.
        val readAt = AppClock.now()
        val reads = queueReads(agentId)
        val read = reads.begun.incrementAndGet()
        try {
            val pending = api.listPending(agentId)
            val shown = reads.land(read, pending) { standing ->
                // A steer is kept while its row is anywhere on the card — listed, or waiting on the transcript to show
                // it — and let go by the second read in a row to find it nowhere: a screen may still be drawing the
                // frame before the transcript filed it, and the row it draws there reads as a steer until the next.
                val waiting = placement?.invoke(agentId)?.value?.waiting.orEmpty()
                val nowhere = f.value.steers.keys.filterTo(HashSet()) { id -> standing.none { it.id == id } && waiting.none { it.id == id } }
                val gone = reads.steersNowhere(nowhere)
                f.update { c -> c.copy(queue = standing, queueLoad = QueueLoad.Loaded, steers = if (gone.isEmpty()) c.steers else c.steers - gone) }
            } ?: return
            onQueueRead(agentId, shown, readAt)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            reads.fail(read) { f.update { it.copy(queueLoad = QueueLoad.Unavailable(describe(t))) } }
        }
    }

    private fun queueReads(agentId: String): QueueReads = queueReads.getOrPut(agentId) { QueueReads() }

    /**
     * One chat's reads of its queue. They overlap — the poll, the one a filing asks for, the one after an edit — and the
     * account answers them in any order, its answers carrying nothing to order them by: an older answer landing last
     * put a message the account had let go of, and the transcript had taken, back on the card beside it.
     *
     * What this device can be sure of is what had landed before a read began. An answer to a read begun before the
     * newest one applied is dropped. A message the account was known to hold — listed by an answer that had landed, or
     * sent from here — that an answer to a read begun after that no longer lists has been let go of; an answer to a
     * read begun before that one landed may have been made before the account let go, and does not bring it back. An
     * answer to a read begun after was made after: its word stands, a message listed again with it.
     */
    private class QueueReads {
        /** How many reads have begun: a read's number is the count as it begins, and an answer lands at the count then. */
        val begun = AtomicLong()
        private var applied = 0L
        /** By followup id, the messages the account is known to hold, and how many reads had begun once it was known. */
        private val held = HashMap<String, Long>()
        /** By followup id, the messages the account has let go of, and how many reads had begun when the answer saying so landed. */
        private val letGo = HashMap<String, Long>()

        /** The answer to read [read]: what of [pending] stands, [publish]ed and returned; null when a read begun after it has been applied already. */
        @Synchronized
        fun land(read: Long, pending: List<PendingFollowup>, publish: (List<PendingFollowup>) -> Unit): List<PendingFollowup>? {
            if (read < applied) return null
            applied = read
            val landed = begun.get()
            val listed = pending.mapTo(HashSet()) { it.id }
            val shown = pending.filter { p -> letGo[p.id]?.let { read > it } ?: true }
            held.filter { (id, since) -> since < read && id !in listed }.keys.forEach { id ->
                held.remove(id)
                letGo[id] = landed
            }
            shown.forEach { p ->
                letGo.remove(p.id)
                held.putIfAbsent(p.id, landed)
            }
            // An answer to a read begun no later than this one is dropped from now on: nothing it could bring back needs keeping out.
            letGo.values.removeAll { it <= read }
            publish(shown)
            return shown
        }

        /** Read [read] failed: [publish]ed unless a read begun after it has been applied already. */
        @Synchronized
        fun fail(read: Long, publish: () -> Unit) {
            if (read < applied) return
            applied = read
            publish()
        }

        /** Steered rows the last applied read found nowhere on the card. */
        private var steersNowhere: Set<String> = emptySet()

        /** The steered rows [nowhere] this read finds nowhere: those the read before found nowhere too are returned, to be let go. */
        @Synchronized
        fun steersNowhere(nowhere: Set<String>): Set<String> {
            val gone = nowhere.filterTo(HashSet()) { it in steersNowhere }
            steersNowhere = nowhere - gone
            return gone
        }

        /** The account took [followupId] from here: an answer to a read begun after this that does not list it has been let go of. */
        @Synchronized
        fun sent(followupId: String) {
            letGo.remove(followupId)
            held.putIfAbsent(followupId, begun.get())
        }
    }

    /**
     * Files [followup] with the account. Behind a turn under way it joins the account's queue; on a free agent the
     * account starts its run and names it, which is returned. [now] sends it in place of the turn under way instead.
     */
    /**
     * Files [followup]; the queue is read again after, unless the caller asks to [refresh] it itself — the composer's
     * send does, once the transcript has taken the message's bubble down, so the card and the bubble never show the
     * message at the same instant (see `OutgoingMessages`, `QueuePlacement`).
     */
    suspend fun sendFollowup(agentId: String, followup: AccountFollowup, now: Boolean = false, refresh: Boolean = true): Result<String?> =
        action(agentId, "send", needs = { it.accountQueue || it.agentModes }, api = { queueApi }) { api ->
            api.addFollowup(agentId, followup, synchronous = now).also {
                queueReads(agentId).sent(followup.followupId)
                if (refresh) refreshQueue(agentId)
            }
        }

    suspend fun updatePending(agentId: String, followupId: String, text: String): Result<Unit> =
        queueEdit(agentId, followupId) { it.updatePending(agentId, followupId, text.trim()); onQueuedEdited(agentId, followupId, text.trim()) }

    suspend fun deletePending(agentId: String, followupId: String): Result<Unit> =
        queueEdit(agentId, followupId) { it.deletePending(agentId, followupId); onQueuedDeleted(agentId, followupId) }

    /** Moves a queued message one place up (earlier) or down (later); nothing happens at either end. */
    suspend fun movePending(agentId: String, followupId: String, up: Boolean): Result<Unit> {
        val queue = flow(agentId).value.queue
        val index = queue.indexOfFirst { it.id == followupId }
        if (index < 0) return Result.failure(IllegalStateException("That message is no longer queued."))
        val target = queue.getOrNull(if (up) index - 1 else index + 1) ?: return Result.success(Unit)
        return queueEdit(agentId, followupId) { it.reorderPending(agentId, followupId, target.id, insertAfter = !up) }
    }

    /** Sends a queued message now, in place of the turn under way (`SubmitPendingFollowupNow`). */
    suspend fun submitPendingNow(agentId: String, followupId: String): Result<Unit> =
        queueEdit(agentId, followupId) { it.submitPendingNow(agentId, followupId) }

    suspend fun markEditing(agentId: String, followupId: String, editing: Boolean): Result<Unit> =
        queueEdit(agentId, followupId, refresh = false) { it.markEditing(agentId, followupId, editing) }

    private suspend fun queueEdit(agentId: String, followupId: String, refresh: Boolean = true, block: suspend (FollowupQueueApi) -> Unit): Result<Unit> =
        action(agentId, ConversationControls.QUEUE_ACTION_PREFIX + followupId, needs = { it.accountQueue }, api = { queueApi }) { api ->
            block(api)
            if (refresh) refreshQueue(agentId)
        }

    // ---- the run ----------------------------------------------------------------------------------------------------

    /** Steers the turn under way of [agentId] (`InjectBackgroundComposerContext`); the outcome is the line to show. */
    suspend fun steer(agentId: String, text: String): Result<SteerOutcome> =
        action(agentId, "steer", needs = { it.steering }, api = { runs }) { api ->
            api.steer(agentId, text.trim(), expectedRunId(agentId)).also { outcome -> flow(agentId).update { it.copy(lastSteer = outcome) } }
        }

    /**
     * Delivers a queued message into the turn under way as a steer (`InjectBackgroundComposerContext` with
     * `promoteFollowupId`). Its row reads "steering" from the call on and "steered" once the account took it
     * ([ConversationControls.steers]), staying where it is until the transcript shows the message. An outcome that
     * leaves the message waiting — held for the next turn, or refused ([SteerRefusedException]) — puts the row back as
     * it was, saying so under it; a refusal is this call's failure too, so a caller never takes it for a steer.
     */
    suspend fun promotePending(agentId: String, followupId: String): Result<SteerOutcome> =
        action(agentId, ConversationControls.QUEUE_ACTION_PREFIX + followupId, needs = { it.steering && it.accountQueue }, api = { runs }) { api ->
            val f = flow(agentId)
            f.update { it.copy(steers = it.steers + (followupId to SteerPhase.STEERING)) }
            val outcome = try {
                api.promoteFollowup(agentId, followupId, expectedRunId(agentId))
            } catch (t: Throwable) {
                f.update { it.copy(steers = it.steers - followupId) }
                throw t
            }
            when (outcome) {
                SteerOutcome.QUEUED, SteerOutcome.UNKNOWN -> f.update { it.copy(lastSteer = outcome, steers = it.steers + (followupId to SteerPhase.STEERED)) }
                SteerOutcome.QUEUED_FOR_NEXT_TURN, SteerOutcome.REJECTED -> {
                    f.update { it.copy(lastSteer = outcome, steers = it.steers - followupId) }
                    onQueuedNote(agentId, followupId, keptNote(outcome))
                }
            }
            refreshQueue(agentId)
            if (outcome == SteerOutcome.REJECTED) throw SteerRefusedException(outcome.message)
            outcome
        }

    private fun keptNote(outcome: SteerOutcome): String =
        if (outcome == SteerOutcome.REJECTED) STEER_REFUSED_NOTE else STEER_HELD_NOTE

    suspend fun pause(agentId: String): Result<Unit> = action(agentId, "pause", needs = { it.steering }, api = { runs }) { api ->
        api.pause(agentId, expectedRunId(agentId))
        flow(agentId).update { it.copy(isPaused = true) }
        afterAction(agentId)
    }

    suspend fun resume(agentId: String): Result<Unit> = action(agentId, "resume", needs = { it.steering }, api = { runs }) { api ->
        api.resume(agentId)
        flow(agentId).update { it.copy(isPaused = false) }
        afterAction(agentId)
    }

    /** Stops one tool call; false when the account would not (the call had ended already). */
    suspend fun cancelToolCall(agentId: String, toolCallId: String): Result<Boolean> =
        action(agentId, "tool:$toolCallId", needs = { it.steering }, api = { runs }) { api ->
            api.cancelToolCall(agentId, toolCallId).also { accepted -> if (accepted) flow(agentId).update { it.copy(cancelledCallIds = it.cancelledCallIds + toolCallId) } }
        }

    /** Wakes the chat's machine ahead of a follow-up; false when the account had nothing to wake. */
    suspend fun wake(agentId: String): Result<Boolean> = action(agentId, "wake", needs = { it.steering }, api = { runs }) { api -> api.wake(agentId) }

    // ---- the question -----------------------------------------------------------------------------------------------

    /** Answers the `ask_question` call [toolCallId] the agent is waiting on; the card shows the answer as sent until the stream confirms. */
    suspend fun answerQuestion(agentId: String, toolCallId: String, answers: List<ToolPayload.Question.Answer>): Result<InteractionResolution> {
        if (answers.isEmpty()) return Result.failure(IllegalArgumentException("Pick or type an answer first."))
        return action(agentId, "answer:$toolCallId", needs = { it.interactions }, api = { interactions }) { api ->
            api.answerQuestion(agentId, toolCallId, answers).also { resolution ->
                if (resolution.delivered) flow(agentId).update { it.copy(answeredCallIds = it.answeredCallIds + toolCallId) }
                afterAction(agentId)
            }
        }
    }

    /** On sign-out or a backend switch: nothing the previous account said counts for the next. */
    fun reset() {
        states.clear()
        pollers.values.forEach { it.cancel() }
        pollers.clear()
        attached.clear()
        goalReadAt.clear()
        queueReads.clear()
    }

    /** The run the account is on as far as this device knows; never a prompt's local placeholder. */
    private fun expectedRunId(agentId: String): String? = agents.agent(agentId)?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }

    /**
     * One account-service call, gated: with the surface off it is refused with [NEEDS_EXTENDED_MODE] and nothing is
     * called; the demo has no account and says so; [key] is marked in flight for the screens meanwhile; a failure is
     * turned into the words the screen shows.
     */
    private suspend fun <A : Any, T> action(agentId: String, key: String, needs: (Capabilities) -> Boolean, api: () -> A?, block: suspend (A) -> T): Result<T> {
        if (session.isDemo) return Result.failure(IllegalStateException(NOT_IN_DEMO))
        if (!needs(capabilities())) return Result.failure(IllegalStateException(NEEDS_EXTENDED_MODE))
        val target = api() ?: return Result.failure(IllegalStateException(NOT_WIRED))
        val f = flow(agentId)
        f.update { it.copy(inFlight = it.inFlight + key) }
        return try {
            Result.success(block(target))
        } catch (e: CancellationException) {
            throw e
        } catch (t: SteerRefusedException) {
            Result.failure(t)
        } catch (t: Throwable) {
            Result.failure(IOException(describe(t), t))
        } finally {
            f.update { it.copy(inFlight = it.inFlight - key) }
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) NEEDS_EXTENDED_MODE else t.message ?: NO_SESSION
        is ConnectRpcException -> if (t.isNotOffered) ENDPOINT_CHANGED else "Cursor refused (${t.message})."
        is IOException -> t.userMessage()
        else -> t.message ?: "Something went wrong."
    }

    /** A refusal that says the method is not offered (gone, gated or not for this account) rather than that the request was wrong. */
    private val ConnectRpcException.isNotOffered: Boolean
        get() = httpCode == 404 || code == "unimplemented" || code == "failed_precondition" || code == "permission_denied"

    companion object {
        private const val POLL_INTERVAL_MS = 10_000L
        /** The account's goal is read on one queue poll in this many: every 30 s at the default interval. */
        private const val GOAL_POLL_EVERY = 3
        private const val LOCAL_RUN_PREFIX = "local-"

        /** Why a control is refused with Extended mode off; the screen names it instead of the control. */
        const val NEEDS_EXTENDED_MODE = "Needs Extended mode: answering, the account's queue, steering, pausing and stopping a tool use Cursor's account service."
        const val NOT_IN_DEMO = "Not available in the demo."
        const val ENDPOINT_CHANGED = "Cursor changed a private endpoint; this control is unavailable until the app is updated."
        private const val NOT_WIRED = "The chat's controls are not wired to the account service in this build."
        private const val NO_SESSION = "Cursor couldn't start a session for this key."
        /** Under a row whose steer the account refused, the message still in its queue. */
        const val STEER_REFUSED_NOTE = "Not steered: Cursor kept it queued for when this turn ends."
        /** Under a row whose steer the account held for the next turn. */
        const val STEER_HELD_NOTE = "Held for the agent's next turn."
    }
}

/** The account answered a steer with `OUTCOME_REJECTED`: the message was not delivered, and waits in its queue. */
class SteerRefusedException(message: String) : IOException(message)
