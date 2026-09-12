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
) {
    /** The line a card shows: the message, or a word for one that carries only attachments. */
    val previewText: String get() = text.ifBlank { QueuedFollowUp.IMAGE_ONLY_TEXT }
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
) {
    val isQueueAvailable: Boolean get() = queueLoad is QueueLoad.Loaded || queueLoad is QueueLoad.Loading && queue.isNotEmpty()

    fun isBusy(action: String): Boolean = action in inFlight

    /** The queued messages an edit, a removal or a send is out for, by id. */
    val inFlightQueueIds: Set<String> get() = inFlight.filter { it.startsWith(QUEUE_ACTION_PREFIX) }.mapTo(HashSet()) { it.removePrefix(QUEUE_ACTION_PREFIX) }

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
