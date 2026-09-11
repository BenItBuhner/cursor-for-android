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
