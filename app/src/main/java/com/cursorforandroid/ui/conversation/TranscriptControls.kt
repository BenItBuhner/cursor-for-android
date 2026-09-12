package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.compositionLocalOf
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.ToolPayload

/**
 * What the transcript's rows may do on the account (Extended mode): answer the question a card shows, stop the tool
 * call a line shows. A null hand means the surface is off, and the row stays what it is in default mode — a read-only
 * question, a plain line. [state] is what the account has said so far: which questions were answered from here,
 * which steps a stop was asked for, what is in flight.
 */
data class TranscriptControls(
    val state: ConversationControls = ConversationControls.EMPTY,
    val onAnswer: ((callId: String, answers: List<ToolPayload.Question.Answer>) -> Unit)? = null,
    val onCancelToolCall: ((callId: String) -> Unit)? = null,
)

/** The transcript's controls, provided by the conversation screen around its list; the defaults where it is rendered alone. */
val LocalTranscriptControls = compositionLocalOf { TranscriptControls() }
