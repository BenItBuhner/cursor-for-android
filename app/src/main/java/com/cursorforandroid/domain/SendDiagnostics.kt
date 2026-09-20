package com.cursorforandroid.domain

/**
 * The send path's own account of one chat, for the transcript diagnostics' `send:` block: how the chat itself was
 * launched from here, when it was (see [LaunchLine]); the last decision between sending and queueing and every input
 * it read (see [SendGate]), the device's queue as it stands, and the attempts made — when, through which request, and
 * what came of each: accepted (with the run or followup id the server named), refused as busy, failed with the
 * server's words, or lost in transit and settled by asking the server. No message text; the server's words are
 * redacted like every other error in the export.
 */
data class SendDiagnostics(
    val decision: SendGate.Decision?,
    val decidedAtIso: String?,
    val queue: List<QueueLine>,
    val attempts: List<Attempt>,
    /** The ids the server answered a send with — a run's, or a followup's in the account's queue — newest last. */
    val accepted: List<String>,
    /** The launch that started the chat from this device, when it was (see [LaunchLine]); null for a chat started elsewhere or before this process. */
    val launch: LaunchLine? = null,
) {
    data class QueueLine(val idTail: String, val chars: Int, val sending: Boolean, val steered: Boolean, val busyRefusals: Int, val heldForMs: Long?, val error: String?, val needsConfirmation: Boolean)

    /** One request to the server for one queued (or steered) message. */
    data class Attempt(val atIso: String, val idTail: String, val via: String, val outcome: String, val detail: String? = null)

    /**
     * The launch decision and its outcome: [via] which request started the chat — `v1` (`POST /v1/agents`) or `account`
     * (`StartBackgroundComposerFromSnapshot`, the way a prompt with files goes) — [target] what it named as the place to
     * run (a repository, `no-repo(repos:[])`, a named environment, a pool, a machine, the account's personal no-repo
     * environment), how many [files] and [images] it carried, and [outcome]: accepted with the run's id, adopted after a
     * conflict or a lost reply, stood in from the account's record, refused with the server's code, unanswered,
     * failed before anything was sent. [detail] carries the server's words, redacted.
     */
    data class LaunchLine(val atIso: String, val via: String, val target: String, val files: Int, val images: Int, val outcome: String, val detail: String? = null)

    fun render(): String = buildString {
        append("send: decision=")
        val d = decision
        if (d == null) {
            append("none")
        } else {
            val i = d.inputs
            append(if (d.busy) "queue" else "send").append(" by=").append(d.source.name.lowercase())
            append(" at=").append(decidedAtIso ?: "-")
            append(" row=").append(if (!i.rowLoaded) "none" else if (i.rowRunning) "running" else "idle")
            append(" chat=").append(i.chatRunStatus?.name ?: "-")
            append(" streaming=").append(i.chatStreaming).append(" reconnecting=").append(i.chatReconnecting)
            // The account's word as the decision read it — the same word the `status:` line reads, at the decision's
            // instant — with when the account said it and whether the gate let it speak: a word older than the row's
            // activity by more than the slack is discarded (see SendGate.ACCOUNT_SLACK_MS), and says nothing against a
            // fresher `status:` line (Bennett's export, 2026-09-20: account=idle three minutes before accountRunning=true).
            append(" account=").append(if (!i.accountScanned) "unread" else if (i.accountRunning) "running" else "idle")
            if (i.accountAtMillis > 0) append("@").append(java.time.Instant.ofEpochMilli(i.accountAtMillis).toString())
            append(" accountAgeMs=").append(if (i.accountAtMillis > 0 && i.rowUpdatedAtMillis > 0) i.rowUpdatedAtMillis - i.accountAtMillis else "-")
            if (i.accountScanned) append(" accountUsed=").append(d.source == SendGate.Source.Account || (i.accountAtMillis + SendGate.ACCOUNT_SLACK_MS >= i.rowUpdatedAtMillis && d.source != SendGate.Source.Stream))
        }
        appendLine()
        launch?.let { l ->
            appendLine("  launch: at=${l.atIso} via=${l.via} target=${l.target} files=${l.files} images=${l.images} outcome=${l.outcome}" + (l.detail?.let { " \"$it\"" } ?: ""))
        }
        appendLine("  queue: ${queue.size}" + queue.joinToString("") { q ->
            " [${q.idTail} chars=${q.chars}" + (if (q.sending) " sending" else "") + (if (q.steered) " steered" else "") +
                (if (q.busyRefusals > 0) " busyRefusals=${q.busyRefusals}" else "") + (q.heldForMs?.let { " heldFor=${it / 1000}s" } ?: "") +
                (if (q.needsConfirmation) " needsConfirmation" else "") + (q.error?.let { " error=\"$it\"" } ?: "") + "]"
        })
        appendLine("  attempts: ${attempts.size}" + (if (accepted.isNotEmpty()) " accepted=[${accepted.joinToString(",")}]" else ""))
        attempts.forEach { a -> appendLine("    ${a.atIso} ${a.idTail} via=${a.via} ${a.outcome}" + (a.detail?.let { " \"$it\"" } ?: "")) }
    }
}
