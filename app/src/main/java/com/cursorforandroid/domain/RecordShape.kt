package com.cursorforandroid.domain

/**
 * The shape of one step of the account's record (`HeadlessAgenticComposerResponse`), its values left out: the keys
 * it carries with the types of their values ([keys], see `RecordShapes` for the grammar), and which of this build's
 * parser branches read it ([branch]). What the transcript diagnostics carry for a chat whose coordinator's messages
 * are not on screen: the shape says whether the record has them at all, under what key, and what the parser made of
 * them — without a word of what anyone wrote.
 */
data class StepShape(
    /** The step's index in the record (`start_index` + its place in the page). */
    val index: Int,
    /**
     * The parser branch that consumed the step: `user_message`, `human_message`, `text`, `thinking`, `error`,
     * `final_tool_result`, `tool_call[…]` or `streamed_back_tool_call[…]` — the bracket saying where the call's id,
     * name and arguments were read from (`id=toolCallId name=name args=json`) — else `blank(status)`,
     * `blank(message_done)` or `blank` for a step nothing is read from.
     */
    val branch: String,
    /** The step's keys with their value types, e.g. `{toolCall:{toolCallId:str(24),name:SendMessage,rawArgs:str(212),isStreaming:true}}`. */
    val keys: String,
)

/**
 * One tool call of a record turn as the replay resolved it: which steps it was in, how its arguments came together
 * — `json` (a step carried them whole), `params` (from the typed member), `joined(n)` (n streamed pieces that read
 * as JSON once joined), `recovered(method,n)` (n pieces that never did, the body read leniently), `partial(n)` (n
 * pieces and nothing readable), `none` — and whether a result was recorded for it.
 */
data class CallShape(
    val idTail: String,
    val name: String,
    val steps: Int,
    val args: String,
    val result: Boolean,
)

/** One of the record's newest turns as the diagnostics dump it: its steps' shapes and its calls' resolutions. */
data class TurnShape(
    /** The record index of the turn's first step (its prompt). */
    val stepIndex: Int,
    /** What started the turn: `user` (a prompt), `injected` (a turn Cursor injected), `none`. */
    val prompt: String,
    val projectMode: Boolean,
    val steps: List<StepShape>,
    val calls: List<CallShape>,
)
