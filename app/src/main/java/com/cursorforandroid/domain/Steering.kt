package com.cursorforandroid.domain

/**
 * The mode a message goes out under, `agent.v1.AgentMode` as the account service spells it on
 * `ConversationMessage.agent_mode`. The documented API accepts `agent` and `plan` alone (the SDK's `mode` option), so
 * [ASK] and [DEBUG] can only travel on the account's own follow-up (`AddAsyncFollowupBackgroundComposer`), which is
 * why the composer offers them in Extended mode only. [PROJECT], [MULTITASK] and the rest are here for completeness
 * of the wire enum: a Project is started elsewhere, and `/multitask` rides in the prompt's text.
 */
enum class AgentMode(val number: Int) {
    AGENT(1),
    ASK(2),
    PLAN(3),
    DEBUG(4),
    TRIAGE(5),
    PROJECT(6),
    MULTITASK(7),
    CUSTOM(8),
    ;

    /** The enum's name on the wire (proto3 JSON spells enums by their full name). */
    val wireName: String get() = "$WIRE_PREFIX$name"

    /** The documented API's word for it — `agent` or `plan` — or null for a mode only the account service carries. */
    val publicName: String?
        get() = when (this) {
            AGENT -> "agent"
            PLAN -> "plan"
            else -> null
        }

    /** True for a mode the documented run request cannot carry: it needs the account's follow-up RPC. */
    val needsAccountService: Boolean get() = publicName == null

    /** "Ask", "Debug": the word on the composer's pill and in the header. */
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        const val WIRE_PREFIX = "AGENT_MODE_"

        /** The proto name, the bare name or the number, as the account service may spell it; null for anything else. */
        fun parse(raw: String?): AgentMode? {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            token.toIntOrNull()?.let { number -> return entries.firstOrNull { it.number == number } }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name }
        }

        /** The mode the composer's plan flag stands for: null keeps the chat's mode, true is [PLAN], false [AGENT]. */
        fun ofPlanMode(planMode: Boolean?): AgentMode? = planMode?.let { if (it) PLAN else AGENT }
    }
}

/**
 * One follow-up waiting in the account's queue for a chat (`aiserver.v1.PendingFollowup`, from `ListPendingFollowups`):
 * what the desktop, the web and the iOS app show above their composers while a turn is under way, in the order the
 * server will send them. [isEditing] is the flag `MarkFollowupEditing` sets while one client is rewording it.
 */
data class PendingFollowup(
    val id: String,
    val text: String,
    val createdAtMillis: Long? = null,
    /** Where it was queued from, as the account spells the source (see [AgentSource]); null when the record left it out. */
    val source: AgentSource? = null,
    val isEditing: Boolean = false,
    /**
     * What the queued message carries besides its text, read off its `conversation_action`'s `selected_context`: the
     * documents by filename and type, and the images by count — enough for the row to show what will go out with it.
     */
    val files: List<PendingAttachment> = emptyList(),
    val imageCount: Int = 0,
    /**
     * A word of this device's under the row, never the account's: set when the message was put back on the card after
     * the transcript had shown it under a run that ended without it (see [QueuePlacement.returned]).
     */
    val note: String? = null,
) {
    /** The line a card shows: the message, or a word for one that carries only attachments. */
    val previewText: String get() = text.ifBlank { attachmentOnlyText(imageCount, files.size) }
}

/**
 * Where each message the account took into its queue stands between the two places it can be shown — the card above
 * the composer and the transcript — as the transcript's state says it (see `ConversationRepository.expectDelivery`).
 * At any instant a message is in exactly one of them: on the card until the transcript files it under the run the
 * account delivered it on, in the transcript from that moment, whatever the last read of the account's queue said
 * (Bennett's frame of 2026-09-20: his message as a sent bubble with "Starting…" under it and, at the same instant,
 * on the card with the cloud glyph — the card reflecting a queue read from before the run started). Carried on the
 * transcript's state so the screen projects the card from the very frame it draws the transcript from
 * ([ConversationControls.placed]); a card drawn from another frame's word could show both.
 */
data class QueuePlacement(
    /** The account's followup ids filed in the transcript: the card leaves them out until the account's list no longer names them. */
    val deliveredIds: Set<String> = emptySet(),
    /** The same for messages the transcript knows by their words alone (a message queued without an id of the account's), as [textKey]s. */
    val deliveredTexts: Set<String> = emptySet(),
    /**
     * Messages put back on the card, by followup id, with the card's word for it: the transcript had filed them under
     * a run that ended while the account still listed them as waiting, so the run did not carry them after all.
     */
    val returned: Map<String, String> = emptyMap(),
    /**
     * Messages this device queued on the account that the transcript does not show yet: the card shows them from the
     * moment they were queued — before the account's list has been read again to name them, and on after the
     * account has consumed one and dropped it from the list while the transcript is still filing it — so a message
     * is never in neither place. A message the list names too is shown once, the list's row standing.
     */
    val waiting: List<PendingFollowup> = emptyList(),
    /**
     * Messages the transcript shows as bubbles ahead of their requests — the composer's, from the tap until the send
     * is answered (see `ConversationRepository.sendStagedVia`) — by the account's followup id when the send minted
     * one, and by their [textKey]s: the account may already list one, its send's reply still on its way back, before
     * the bubble has come down for the card to take it. The bubble is its place; the card leaves the row out.
     */
    val shownIds: Set<String> = emptySet(),
    val shownTexts: Set<String> = emptySet(),
    /**
     * The [waiting] rows whose request is still out: queued on the card from the tap, while the account has not yet
     * said it holds them. The card shows them in flight, their actions held until the account knows their id.
     */
    val sendingIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = deliveredIds.isEmpty() && deliveredTexts.isEmpty() && returned.isEmpty() && waiting.isEmpty() && shownIds.isEmpty() && shownTexts.isEmpty()

    /** Whether [followup] is in the transcript now — filed under its run, or standing as the composer's bubble — and so not on the card. */
    fun holds(followup: PendingFollowup): Boolean =
        followup.id in deliveredIds || followup.id in shownIds || textKey(followup.text).let { it in deliveredTexts || it in shownTexts }

    companion object {
        val NONE = QueuePlacement()
        /** The id a [waiting] row carries for a message queued without an id of the account's: its staged copy's own, so prefixed. */
        const val LOCAL_ID_PREFIX = "local:"

        /** The words of a message as they compare across the card, the transcript and the account: whitespace folded. */
        fun textKey(text: String): String = text.replace(WHITESPACE, " ").trim()

        private val WHITESPACE = Regex("\\s+")

        /** What the card says under a message put back on it (see [returned]). */
        const val RETURNED_NOTE = "Still queued on your account: the last turn ended without it."
        /** What the card says under a message the account has taken from its list and the transcript is about to show (see [waiting]). */
        const val DELIVERING_NOTE = "Being delivered to the agent."
    }
}

/** A file on a queued follow-up as the account describes it (an `agent.v1.SelectedDocument`'s `filename` and `mime_type`); the bytes stay on the server. */
data class PendingAttachment(val name: String, val mimeType: String) {
    val kind: PromptFileKind get() = PromptFileKind.of(name, mimeType)
}

/**
 * What `SubmitInteractionResponseBackgroundComposer` did with an answer: woke the paused run with it, found the run
 * not waiting on anything (answered already, or moved on), or filed it under the query's id for the run to pick up.
 */
enum class InteractionResolution {
    SIGNAL_ENQUEUED,
    NOT_PAUSED,
    QUERY_ID_SIGNAL_ENQUEUED,
    UNKNOWN,
    ;

    /** True when the answer reached the run. */
    val delivered: Boolean get() = this != NOT_PAUSED

    /** The one line the card shows for it. */
    val message: String
        get() = when (this) {
            SIGNAL_ENQUEUED, QUERY_ID_SIGNAL_ENQUEUED -> "Answer sent; the agent picks it up now."
            NOT_PAUSED -> "The agent was no longer waiting on this question."
            UNKNOWN -> "Answer sent."
        }

    companion object {
        /** `aiserver.v1.SubmitInteractionResponseBackgroundComposerResponse.ResolutionOutcome` spells its values `RESOLUTION_OUTCOME_…`. */
        const val WIRE_PREFIX = "RESOLUTION_OUTCOME_"

        /** The proto name, the bare name or the number (SIGNAL_ENQUEUED 1, NOT_PAUSED 2, QUERY_ID_SIGNAL_ENQUEUED 3); anything else is [UNKNOWN]. */
        fun parse(raw: String?): InteractionResolution {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
            token.toIntOrNull()?.let { number -> return listOf(SIGNAL_ENQUEUED, NOT_PAUSED, QUERY_ID_SIGNAL_ENQUEUED).getOrNull(number - 1) ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

/**
 * The account's word on one chat's controls, as the conversation and its panel show it: the server-side queue, the
 * last steer's outcome, what has been answered or held from here, and which actions are in flight.
 */
data class ConversationControls(
    /** The account's queue for this chat, oldest first; empty until read. */
    val queue: List<PendingFollowup> = emptyList(),
    /** Where the queue read stands. */
    val queueLoad: QueueLoad = QueueLoad.Idle,
    /** What the last steer sent from here came back as; null before one was sent. */
    val lastSteer: SteerOutcome? = null,
    /** True after a pause from here that no resume has followed; null when nothing was asked. */
    val isPaused: Boolean? = null,
    /** The `ask_question` calls answered from here, by tool call id, until the stream shows them answered. */
    val answeredCallIds: Set<String> = emptySet(),
    /** The tool calls a cancel was asked for from here, by id, until the stream shows them over. */
    val cancelledCallIds: Set<String> = emptySet(),
    /** Actions under way, by name ("pause", "steer", "queue:<id>"), so a control shows busy rather than taking a second tap. */
    val inFlight: Set<String> = emptySet(),
    /**
     * The goal the account keeps on the chat (`GetLatestAgentConversationState`), as last read; null when it has none,
     * or when it has not been read. [goalKnown] tells the two apart: once the account has answered, its word — a goal
     * or none — stands over what the transcript alone would say.
     */
    val goal: Goal? = null,
    val goalKnown: Boolean = false,
) {
    val isQueueAvailable: Boolean get() = queueLoad is QueueLoad.Loaded || queueLoad is QueueLoad.Loading && queue.isNotEmpty()

    fun isBusy(action: String): Boolean = action in inFlight

    /** The queued messages an edit, a removal or a send is out for, by id. */
    val inFlightQueueIds: Set<String> get() = inFlight.filter { it.startsWith(QUEUE_ACTION_PREFIX) }.mapTo(HashSet()) { it.removePrefix(QUEUE_ACTION_PREFIX) }

    /**
     * The queue as the card shows it against the transcript's frame [placement] belongs to: a message the transcript
     * has filed under its run is left out, whatever the account's last read said of it, and one put back on the card
     * carries the word for it (see [QueuePlacement]). The same controls when nothing is placed.
     */
    fun placed(placement: QueuePlacement): ConversationControls {
        if (placement.isEmpty) return this
        var changed = false
        val shown = queue.mapNotNull { item ->
            when {
                placement.holds(item) -> { changed = true; null }
                placement.returned[item.id] != null -> { changed = true; item.copy(note = placement.returned[item.id]) }
                else -> item
            }
        }
        // This device's queued messages the list does not name (yet, or any more): kept on the card until the transcript
        // shows them. By id; by words only for one queued without an id of the account's (the list's row for it is the
        // account's own name for the same message) — the same words are other queued messages' too.
        val kept = placement.waiting.filter { w -> shown.none { it.id == w.id || (w.id.startsWith(QueuePlacement.LOCAL_ID_PREFIX) && QueuePlacement.textKey(it.text) == QueuePlacement.textKey(w.text)) } }
        if (kept.isNotEmpty()) changed = true
        val sending = placement.sendingIds.mapTo(HashSet()) { QUEUE_ACTION_PREFIX + it }
        return when {
            sending.isNotEmpty() -> copy(queue = shown + kept, inFlight = inFlight + sending)
            changed -> copy(queue = shown + kept)
            else -> this
        }
    }

    companion object {
        val EMPTY = ConversationControls()
        /** The in-flight key of an action on one queued message is `queue:<followupId>`. */
        const val QUEUE_ACTION_PREFIX = "queue:"
    }
}

/** Where the read of the account's queue stands: not asked, in flight, answered, or impossible from here. */
sealed interface QueueLoad {
    data object Idle : QueueLoad
    data object Loading : QueueLoad
    data object Loaded : QueueLoad
    /** Extended mode is off, the demo has no account, or the account service would not answer: [reason] is the named state. */
    data class Unavailable(val reason: String) : QueueLoad
}
