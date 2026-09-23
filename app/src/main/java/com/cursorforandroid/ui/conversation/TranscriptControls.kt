package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.compositionLocalOf
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.FileOpenRequest
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
    /**
     * Throws the chat's copies away and reads it again from the server (see `ConversationRepository.reloadTranscript`):
     * what a coordinator's message shown without its text offers, since the run's log or the record read afresh is
     * where the text would come from. Null where the rows are rendered without a chat behind them.
     */
    val onReloadTranscript: (() -> Unit)? = null,
    /**
     * Where each message sent from this chat's composer and not yet filed by the server stands, by its bubble's id
     * (see [OutgoingStatus]): the bubble draws its files still going up, or a failure with the reason and its two
     * ways on — [onRetryOutgoing] sends it again, [onEditOutgoing] hands the draft back to the composer. Empty where
     * the rows are rendered without a composer behind them.
     */
    val outgoing: Map<String, OutgoingStatus> = emptyMap(),
    val onRetryOutgoing: ((id: String) -> Unit)? = null,
    val onEditOutgoing: ((id: String) -> Unit)? = null,
    /**
     * The reader closes a notice among the rows that offers it (`NoticeCard.dismissKey`): every notice with that key
     * in the chat goes, and stays gone for the chat (see `NoticeDismissals`). Null where the rows are rendered
     * without a chat behind them, and the notices show no X.
     */
    val onDismissNotice: ((key: String) -> Unit)? = null,
    /**
     * A file path a tool call names was tapped — a read, an edit, a write, a search hit — and it is not a picture, a
     * recording or a sound (those open the media viewer from the row itself): the full-file viewer opens on it. Null
     * where the rows are rendered without a chat behind them, and the paths are plain text.
     */
    val onOpenFile: ((FileOpenRequest) -> Unit)? = null,
    /**
     * A picture a tool call read is outside the agent's workspace and no source this chat carries has it, so the
     * reader asked the agent to copy it in (see `MediaProblem.OutsideWorkspace`): the composer is filled with a
     * follow-up asking for the copy, not sent, so the reader can send it. [path] is the file to copy. Null where no
     * composer stands behind the rows.
     */
    val onAskToCopyFile: ((path: String) -> Unit)? = null,
)

/** The transcript's controls, provided by the conversation screen around its list; the defaults where it is rendered alone. */
val LocalTranscriptControls = compositionLocalOf { TranscriptControls() }
