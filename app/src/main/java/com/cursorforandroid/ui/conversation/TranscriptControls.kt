package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.compositionLocalOf
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.ToolPayload

/**
 * What the transcript's rows may do on the account (Extended mode): answer the question a card shows, stop the tool
 * call a line shows. A null hand means the surface is off, and the row stays what it is in default mode — a read-only
 * question, a plain line. [state] is what the account has said so far: which questions were answered from here,
 * which steps a stop was asked for, what is in flight.
 *
 * Also what a Project coordinator's rows need of the rest of the app, in either mode: [onOpenAgent] takes the reader
 * to a worker's conversation from its card, and [agentById] is the list's live row for a cloud agent — the worker's
 * current name and state — or null when the list has not loaded it.
 */
data class TranscriptControls(
    val state: ConversationControls = ConversationControls.EMPTY,
    val onAnswer: ((callId: String, answers: List<ToolPayload.Question.Answer>) -> Unit)? = null,
    val onCancelToolCall: ((callId: String) -> Unit)? = null,
    val onOpenAgent: ((agentId: String) -> Unit)? = null,
    val agentById: (agentId: String) -> Agent? = { null },
    /**
     * The chat is a Project coordinator's. Cursor's own client reads such a chat the other way round from an
     * agent's: what the coordinator said to the user through its SendMessage tool is the message, and the plain
     * text it produced between tool calls is its working notes, folded away under "Background" unless opened.
     */
    val coordinatorMode: Boolean = false,
)

/** The transcript's controls, provided by the conversation screen around its list; the defaults where it is rendered alone. */
val LocalTranscriptControls = compositionLocalOf { TranscriptControls() }
