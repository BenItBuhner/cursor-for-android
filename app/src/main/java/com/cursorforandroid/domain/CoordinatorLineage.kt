package com.cursorforandroid.domain

/**
 * What a Project's own transcript says about its workers. The coordinator drives them with tools — `create_agent`,
 * `send_to_agent`, `get_agent_status`, `stop_agent`, `read_agent_transcript` (`agent/v1/coordinator_tools`) — whose
 * calls are in its transcript on the documented stream like any other tool's, and whose arguments and results name
 * the workers by id (`agent_id`, `worker_bc_id`, `agent_ids[]`, `workers[].bc_id`). Without Cursor's account service
 * that is the one lineage signal there is, so a chat whose transcript carries these calls is taken for a Project's
 * coordinator and the ids in them for its workers; the account's list, when it is read, has the last word.
 */
object CoordinatorLineage {

    /** The coordinator's tools, as the stream names them. */
    val TOOLS: Set<String> = setOf("create_agent", "send_to_agent", "get_agent_status", "stop_agent", "read_agent_transcript")

    fun isCoordinatorTool(name: String): Boolean = name.trim().lowercase().removeSuffix("_tool_call") in TOOLS

    /**
     * The coordinator's tool for speaking to the user (`SendMessage` on the stream, `send_to_user` in the proto and
     * older streams): names no worker, but only a Project's coordinator has it.
     */
    fun isUserMessageTool(name: String): Boolean = ToolNames.coordinatorTool(name) == "send_to_user"

    /** True when [items] hold a coordinator's tool call: the chat they belong to runs a Project. */
    fun isCoordinator(items: List<TimelineItem>): Boolean = calls(items).any { isCoordinatorTool(it.name) || isUserMessageTool(it.name) }

    /** The ids of the workers the coordinator's calls in [items] name, in the order they were first named. */
    fun workerIds(items: List<TimelineItem>): Set<String> =
        calls(items).filter { isCoordinatorTool(it.name) }.flatMapTo(LinkedHashSet()) { it.linkedAgentIds }

    /**
     * The ids the coordinator's own tools returned as its workers: the one `create_agent` made, and the ones
     * `get_agent_status` listed (see [ToolPayload.WorkerAction.reported]). Positive evidence, where a mere mention —
     * a chat messaged, stopped or read — is a hint (see `LineageSignal`).
     */
    fun createdWorkerIds(items: List<TimelineItem>): Set<String> =
        calls(items).filter { isCoordinatorTool(it.name) }.flatMapTo(LinkedHashSet()) { call ->
            val action = call.payload as? ToolPayload.WorkerAction
            when {
                action != null && action.reported && (action.kind == ToolPayload.WorkerAction.Kind.Created || action.kind == ToolPayload.WorkerAction.Kind.Status) ->
                    action.workers.mapNotNull { it.agentId }
                // A trace kept before the payloads: a `create_agent` call names only the worker it made.
                action == null && call.name.trim().lowercase().removeSuffix("_tool_call") == "create_agent" -> call.linkedAgentIds
                else -> emptyList()
            }
        }

    private fun calls(items: List<TimelineItem>): Sequence<ToolCall> =
        items.asSequence().filterIsInstance<ActivityGroup>().flatMap { it.calls.asSequence() }
}
