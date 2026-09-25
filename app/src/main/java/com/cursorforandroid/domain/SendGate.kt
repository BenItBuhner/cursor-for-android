package com.cursorforandroid.domain

/**
 * Whether a follow-up may go out now — the one decision behind "send" against "queue" — read from one source at a
 * time, the freshest that has spoken, never from a status this device made up.
 *
 * Until 0.3.34 the decision was three readings or'd together (the chat's status, its stream, the row's status) and a
 * `409 agent_busy` answer patched the row to running: a stale `RUNNING` run record kept a chat "reconnecting" and
 * busy after its turn was long over, a busy answer to a send made the row say running until the record was read
 * again ten seconds later, and a message the server refused while its previous turn wound down went round that loop
 * for as long as the server took — queued, sending, queued, sending (Bennett, v0.3.33).
 *
 * The order here:
 *  1. A stream delivering the events of a run the chat still has as active: the turn is under way, whatever the
 *     row says (the list lags the stream). A stream that is only trying to reconnect says nothing — its run may be
 *     long over with a record that never caught up — and neither does one still open on a run the chat has seen
 *     end (cancelled for a steer, say): the connection outlives the turn.
 *  2. The account's word (Extended mode): what `ListBackgroundComposers`, or the chat's own record, last said of
 *     this composer, when the row has not moved since (a send from here marks the row's activity newer than that
 *     reading; the reading is a poll behind it). A word about other composers says nothing about this one.
 *  3. The agent's row: the `/v1` agent record's run status, as the list or a detail read gave it.
 *  4. Without a row (the list not loaded yet), the chat's own status, which is the run record's.
 * A status this device invented — a row patched to running because a send was refused — is no longer written
 * anywhere, so none of the sources above can carry one.
 */
object SendGate {

    /** Everything the decision reads, as of one instant. */
    data class Inputs(
        val rowLoaded: Boolean,
        val rowRunning: Boolean,
        /** The row's activity stamp (the record's `updatedAt`, or the moment a send from here touched it). */
        val rowUpdatedAtMillis: Long,
        val chatRunStatus: RunStatus?,
        val chatStreaming: Boolean,
        val chatReconnecting: Boolean,
        /** The account has named this composer's status (Extended mode). */
        val accountScanned: Boolean,
        val accountRunning: Boolean,
        /** When the account last named this composer's status; 0 when never. */
        val accountAtMillis: Long,
    )

    /** What was decided and which source decided it. */
    data class Decision(val busy: Boolean, val source: Source, val inputs: Inputs) {
        val idle: Boolean get() = !busy
    }

    enum class Source { Stream, Account, Row, Record, Screen, None }

    /**
     * The composer's send: [decide], and busy besides whenever the chat itself shows a turn under way — its run
     * record active or its stream open, the very reading behind the Stop button and the "queues" placeholder. What the
     * reader sees at the tap is what the tap does, with nothing asked of the server first.
     *
     * The sources [decide] prefers can lag the chat by a poll: a Project's coordinator starts turns of its own (a
     * worker's report wakes it), which the chat follows at once while the account's word and the row still say idle.
     * Deciding idle then sent the message into a bubble with the send animation, the run request came back
     * `409 agent_busy`, and a round trip or two later the message popped off the bubble onto the card (Bennett,
     * 0.4.1). Erring busy costs nothing: the account starts the message's run at once when the turn has ended, and
     * this device's queue sends it the moment [decide] reads idle.
     */
    fun decideAtSend(inputs: Inputs): Decision {
        val decision = decide(inputs)
        if (decision.busy) return decision
        if (inputs.chatRunStatus?.isActive == true || inputs.chatStreaming) return Decision(busy = true, Source.Screen, inputs)
        return decision
    }

    fun decide(inputs: Inputs): Decision {
        if (inputs.chatStreaming && !inputs.chatReconnecting && inputs.chatRunStatus?.isActive == true) return Decision(busy = true, Source.Stream, inputs)
        if (inputs.accountScanned && inputs.accountAtMillis + ACCOUNT_SLACK_MS >= inputs.rowUpdatedAtMillis) {
            return Decision(inputs.accountRunning, Source.Account, inputs)
        }
        if (inputs.rowLoaded) return Decision(inputs.rowRunning, Source.Row, inputs)
        if (inputs.chatRunStatus != null) return Decision(inputs.chatRunStatus.isActive, Source.Record, inputs)
        return Decision(busy = false, Source.None, inputs)
    }

    /**
     * How much older than the row's last activity the account's reading may be and still speak for it: the two
     * are the same server's clock, read a poll apart.
     */
    const val ACCOUNT_SLACK_MS = 5_000L
}
