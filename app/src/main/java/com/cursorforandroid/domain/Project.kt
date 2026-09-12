package com.cursorforandroid.domain

/**
 * How a worker came to belong to its Project's coordinator (`aiserver.v1.ManagerSpawnKind`): the coordinator
 * created it, adopted an existing chat, or created it on its own VM. [UNKNOWN] is a kind this build has not heard of.
 */
enum class WorkerSpawnKind(val number: Int) {
    CREATED(1),
    ADOPTED(2),
    CREATED_SAME_VM(3),
    UNKNOWN(-1),
    ;

    /** The enum's name on the wire (proto3 JSON spells enums by their full name). */
    val wireName: String get() = "$WIRE_PREFIX$name"

    /** "Created", "Adopted", "Created on the same VM": the word the Project screen puts beside a worker. */
    val label: String
        get() = when (this) {
            CREATED -> "Created"
            ADOPTED -> "Adopted"
            CREATED_SAME_VM -> "Created on the coordinator's VM"
            UNKNOWN -> "Worker"
        }

    companion object {
        const val WIRE_PREFIX = "MANAGER_SPAWN_KIND_"

        /** The proto name, the bare name or the number, as the account service may spell it; null for nothing at all. */
        fun parse(raw: String?): WorkerSpawnKind? {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            token.toIntOrNull()?.let { number -> return entries.firstOrNull { it.number == number } ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

/**
 * One worker's place in a Project, as `ListWorkersForManager` reports it: the worker, the coordinator it belongs
 * to, how it came to ([spawnKind]), the coordinator's tool call that made it when there was one, and the
 * membership's status as the account spells it.
 */
data class WorkerMembership(
    val workerId: String,
    val managerId: String,
    val spawnKind: WorkerSpawnKind = WorkerSpawnKind.UNKNOWN,
    val toolCallId: String? = null,
    val status: String? = null,
)

/**
 * What the account says hangs off one chat: its workers (a Project's primaries, with how each was spawned, from
 * `ListWorkersForManager`) and its [children] (side chats and cloud subagents, from `ListBackgroundComposerChildren`,
 * by id and by the kind their own record gives).
 */
data class ProjectLineage(
    val rootId: String,
    val workers: List<WorkerMembership> = emptyList(),
    val children: Map<String, AgentParentKind> = emptyMap(),
) {
    /** Every chat the root owns, by id, and in what capacity; a worker the children list also names is a worker. */
    val members: Map<String, AgentParentKind>
        get() = children + workers.associate { it.workerId to AgentParentKind.PROJECT_WORKER }

    val isEmpty: Boolean get() = workers.isEmpty() && children.isEmpty()
}

/**
 * Whether a side chat can be started for a cloud chat from this account. Cursor's help page says side chats are
 * "not currently available for Cloud Agents, but support for this is coming soon" while the RPC and the source
 * already ship, so the answer is learned from the first attempt: [UNKNOWN] until one is made, [AVAILABLE] once one
 * has gone through, [COMING_TO_CURSOR] once Cursor has refused one as not offered yet — a named state, not an error.
 */
enum class SideChatAvailability { UNKNOWN, AVAILABLE, COMING_TO_CURSOR }

/** What `InjectBackgroundComposerContext` did with a steer: delivered at the next tool call, held for the next turn, or refused. */
enum class SteerOutcome {
    QUEUED,
    QUEUED_FOR_NEXT_TURN,
    REJECTED,
    UNKNOWN,
    ;

    /** The one line the composer shows for it. */
    val message: String
        get() = when (this) {
            QUEUED -> "Steer delivered: the agent reads it at its next step."
            QUEUED_FOR_NEXT_TURN -> "Steer held for the agent's next turn."
            REJECTED -> "Cursor did not take the steer; send it as a follow-up instead."
            UNKNOWN -> "Steer sent."
        }

    companion object {
        /** `aiserver.v1.InjectBackgroundComposerContextResponse.Outcome` spells its values `OUTCOME_QUEUED` and so on. */
        const val WIRE_PREFIX = "OUTCOME_"

        /** The proto name, the bare name or the number (QUEUED 1, QUEUED_FOR_NEXT_TURN 2, REJECTED 3); anything else is [UNKNOWN]. */
        fun parse(raw: String?): SteerOutcome {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
            token.toIntOrNull()?.let { number -> return listOf(QUEUED, QUEUED_FOR_NEXT_TURN, REJECTED).getOrNull(number - 1) ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

/** One primary of a Project as its view lists it: the chat's row, and how it came to belong (null while the account has not said). */
data class ProjectWorker(val agent: Agent, val membership: WorkerMembership? = null) {
    val id: String get() = agent.id
    val spawnKind: WorkerSpawnKind? get() = membership?.spawnKind
}

/** One entry of a Project's shared context (its Agent Store): a file with its size, or a directory. */
data class ContextEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val updatedAtMillis: Long? = null,
) {
    val name: String get() = relativePath.trimEnd('/').substringAfterLast('/')
}

/** A Project's shared context: which store it is, and what the listed directory holds. */
data class ProjectContext(
    val storeId: String,
    val entries: List<ContextEntry> = emptyList(),
    /** The directory [entries] describe; "" for the root. */
    val relativePath: String = "",
)
