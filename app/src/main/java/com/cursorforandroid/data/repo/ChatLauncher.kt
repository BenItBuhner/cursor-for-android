package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.userMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * A launch that did not go through — rejected by the server, or stopped from the chat before it had answered — handed
 * back for the composer to take up again: the draft as it went out, the nonce it went out under (an unchanged retry
 * then keeps the same id, and adopts an agent the first attempt may have created after all), and the reason, null
 * when the chat was stopped on purpose.
 */
data class FailedLaunch(val agentId: String, val request: LaunchRequest, val nonce: String, val reason: String?)

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

    /** Launches that did not go through, as they fail. The composer takes the draft back; the shell leaves the chat. */
    val failures: SharedFlow<FailedLaunch> = _failures.asSharedFlow()

    /**
     * Starts the chat and returns once it is on screen under its prompt — before the server has answered — or once the
     * launch was refused before anything was shown. The request completes in this launcher's own scope from there:
     * the caller is free to go, and its cancellation does not reach the request.
     */
    suspend fun launch(request: LaunchRequest, modelDisplayName: String?, nonce: String) {
        val agentId = requireNotNull(request.agentId) { "A launch needs the client-minted agent id the chat is shown under." }
        val staged = CompletableDeferred<Unit>()
        scope.launch {
            conversations.launch(request, modelDisplayName, onStaged = { staged.complete(Unit) }).onFailure { t ->
                _failures.emit(FailedLaunch(agentId, request, nonce, if (t is LaunchCancelledException) null else t.userMessage()))
            }
        }.invokeOnCompletion {
            // Refused before the chat was shown (the same chat is already being started), or the scope is gone: either
            // way the caller is not left waiting.
            staged.complete(Unit)
        }
        staged.await()
    }
}
