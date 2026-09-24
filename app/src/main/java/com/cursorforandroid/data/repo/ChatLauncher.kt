package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.userMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * A launch that did not go through — rejected by the server, or stopped from the chat before it had answered — handed
 * back for the composer to take up again: the draft as it went out, the nonce it went out under (an unchanged retry
 * then keeps the same id, and adopts an agent the first attempt may have created after all), and the reason, null
 * when the chat was stopped on purpose. [asked] names the call the account refused, for a start it was asked for (see
 * [MachineStartRefusedException]).
 */
data class FailedLaunch(val agentId: String, val request: LaunchRequest, val nonce: String, val reason: String?, val asked: String? = null)

/**
 * Sees a new chat's launch through on the composer's behalf. The composer hands a draft to [launch] and is free the
 * moment the chat is on screen — cleared for the next chat, whatever the server has yet to say about this one. The
 * request completes here, in a scope no screen owns, so leaving the chat, coming back to the composer or starting
 * another chat meanwhile never meets, nor takes down, a launch still waiting for its answer. Nothing is needed from
 * the composer once the server accepts; what it refuses, or what is stopped from the chat, comes back as a
 * [FailedLaunch] on [failures], for whichever composer is showing to take up.
 */
class ChatLauncher(
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    // No replay: a failure with no composer left to come back to (the session was signed out) is rightly dropped.
    private val _failures = MutableSharedFlow<FailedLaunch>(extraBufferCapacity = 16)

    /** Launches that did not go through, as they fail. The draft comes back (see `NewChatDrafts`); the shell leaves the chat. */
    val failures: SharedFlow<FailedLaunch> = _failures.asSharedFlow()

    private val _accepted = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The chats the server has created, by the id they were launched under: their drafts are spent. */
    val accepted: SharedFlow<String> = _accepted.asSharedFlow()

    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** True while [agentId]'s launch is waiting for the server's answer. */
    fun isLaunching(agentId: String): Boolean = agentId in inFlight

    /**
     * Starts the chat and returns once it is on screen under its prompt — before the server has answered — or once the
     * launch was refused before anything was shown. The request completes in this launcher's own scope from there:
     * the caller is free to go, and its cancellation does not reach the request.
     */
    suspend fun launch(request: LaunchRequest, modelDisplayName: String?, nonce: String) {
        val staged = CompletableDeferred<Unit>()
        start(request, modelDisplayName, nonce, onStaged = { staged.complete(Unit) }).invokeOnCompletion {
            // Refused before the chat was shown (the same chat is already being started), or the scope is gone: either
            // way the caller is not left waiting.
            staged.complete(Unit)
        }
        staged.await()
    }

    /**
     * [launch], waited out: returns once the server has answered — the chat created, or the launch refused, stopped or
     * failed — rather than once the chat is on screen. For a composer with no screen of the app behind it to hand the
     * chat to (the quick composer over the launcher), which opens the app on the chat only once there is one, and
     * otherwise stays up with the reason. A failure comes back to the caller alone, not on [failures]: the composer
     * that waited never gave the draft up, so there is nothing for another composer to take back, and no chat was
     * shown for the shell to leave. Cancelling the caller does not reach the request, which completes in this
     * launcher's scope regardless.
     */
    suspend fun launchAndAwait(request: LaunchRequest, modelDisplayName: String?, nonce: String): Result<Unit> {
        val outcome = CompletableDeferred<Result<Unit>>()
        val job = start(request, modelDisplayName, nonce, onStaged = {}, broadcastFailure = false, onDone = { outcome.complete(it) })
        job.invokeOnCompletion { cause ->
            // The scope went away before the request could say: not a success, and not the server's word either.
            if (!outcome.isCompleted) outcome.complete(Result.failure(cause ?: IllegalStateException("The launch did not complete.")))
        }
        return outcome.await()
    }

    /**
     * Runs the request in this launcher's scope, [isLaunching] until it answers; an acceptance is put on [accepted]
     * and, with [broadcastFailure], a failure on [failures] before [onDone] hears of it.
     */
    private fun start(
        request: LaunchRequest,
        modelDisplayName: String?,
        nonce: String,
        onStaged: () -> Unit,
        broadcastFailure: Boolean = true,
        onDone: (Result<Unit>) -> Unit = {},
    ): Job {
        val agentId = requireNotNull(request.agentId) { "A launch needs the client-minted agent id the chat is shown under." }
        inFlight += agentId
        return scope.launch {
            val result = conversations.launch(request, modelDisplayName, onStaged = onStaged)
            inFlight -= agentId
            result.onSuccess { _accepted.emit(agentId) }
            if (broadcastFailure) {
                result.onFailure { t ->
                    _failures.emit(FailedLaunch(agentId, request, nonce, if (t is LaunchCancelledException) null else t.userMessage(), (t as? MachineStartRefusedException)?.asked))
                }
            }
            onDone(result.map { })
        }.also { job -> job.invokeOnCompletion { inFlight -= agentId } }
    }
}
