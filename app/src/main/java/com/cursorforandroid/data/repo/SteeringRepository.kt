package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.FollowupQueueApi
import com.cursorforandroid.data.api.InteractionApi
import com.cursorforandroid.data.api.RunControlApi
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.InteractionResolution
import com.cursorforandroid.domain.QueueLoad
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.ToolPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
    /** Runs after an action the transcript should reflect (an answer, a hold): the conversation's revalidation. */
    private val afterAction: suspend (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    /** Whether the account's calls may be made (Extended mode). Everything, for tests of the calls themselves. */
    private val capabilities: suspend () -> Capabilities = { Capabilities.EXTENDED },
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<ConversationControls>>()
    private val attached = ConcurrentHashMap<String, Int>()
    private val pollers = ConcurrentHashMap<String, Job>()

    init {
        scope.launch { session.backend.drop(1).collect { reset() } }
    }

    private fun flow(agentId: String): MutableStateFlow<ConversationControls> = states.getOrPut(agentId) { MutableStateFlow(ConversationControls.EMPTY) }

    /** What the account has said about one chat's controls, kept current while a screen is attached. */
    fun state(agentId: String): StateFlow<ConversationControls> = flow(agentId).asStateFlow()

    /** What the surfaces may do right now, for a screen deciding what to offer. */
    suspend fun capabilities(): Capabilities = capabilities.invoke()

    /**
     * A chat's screen is up: its queue is read now and every [pollIntervalMs] while it is (the account's list is
     * edited from every client, and `StreamBackgroundComposerUpdates` would replace the poll once a Connect streaming
     * client exists). Balanced by [detach].
     */
    fun attach(agentId: String) {
        val count = attached.merge(agentId, 1, Int::plus) ?: 1
        if (count > 1) return
        pollers[agentId]?.cancel()
        pollers[agentId] = scope.launch {
            while (isActive) {
                refreshQueue(agentId)
                delay(pollIntervalMs)
            }
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
        try {
            val pending = api.listPending(agentId)
            f.update { it.copy(queue = pending, queueLoad = QueueLoad.Loaded) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            f.update { it.copy(queueLoad = QueueLoad.Unavailable(describe(t))) }
        }
    }

    /**
     * Files [followup] with the account. Behind a turn under way it joins the account's queue; on a free agent the
     * account starts its run and names it, which is returned. [now] sends it in place of the turn under way instead.
     */
    suspend fun sendFollowup(agentId: String, followup: AccountFollowup, now: Boolean = false): Result<String?> =
        action(agentId, "send", needs = { it.accountQueue || it.agentModes }, api = { queueApi }) { api ->
            api.addFollowup(agentId, followup, synchronous = now).also { refreshQueue(agentId) }
        }

    suspend fun updatePending(agentId: String, followupId: String, text: String): Result<Unit> =
        queueEdit(agentId, followupId) { it.updatePending(agentId, followupId, text.trim()) }

    suspend fun deletePending(agentId: String, followupId: String): Result<Unit> =
        queueEdit(agentId, followupId) { it.deletePending(agentId, followupId) }

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

    /** Delivers a queued message into the turn under way as a steer (`InjectBackgroundComposerContext` with `promoteFollowupId`). */
    suspend fun promotePending(agentId: String, followupId: String): Result<SteerOutcome> =
        action(agentId, ConversationControls.QUEUE_ACTION_PREFIX + followupId, needs = { it.steering && it.accountQueue }, api = { runs }) { api ->
            api.promoteFollowup(agentId, followupId, expectedRunId(agentId)).also { outcome ->
                flow(agentId).update { it.copy(lastSteer = outcome) }
                refreshQueue(agentId)
            }
        }

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
        private const val LOCAL_RUN_PREFIX = "local-"

        /** Why a control is refused with Extended mode off; the screen names it instead of the control. */
        const val NEEDS_EXTENDED_MODE = "Needs Extended mode: answering, the account's queue, steering, pausing and stopping a tool use Cursor's account service."
        const val NOT_IN_DEMO = "Not available in the demo."
        const val ENDPOINT_CHANGED = "Cursor changed a private endpoint; this control is unavailable until the app is updated."
        private const val NOT_WIRED = "The chat's controls are not wired to the account service in this build."
        private const val NO_SESSION = "Cursor couldn't start a session for this key."
    }
}
