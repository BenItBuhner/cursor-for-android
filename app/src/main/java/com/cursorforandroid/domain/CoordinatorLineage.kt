package com.cursorforandroid.domain

/**
 * What a Project's own transcript says about its workers. The coordinator drives them with tools — `create_agent`,
 * `send_to_agent`, `get_agent_status`, `stop_agent`, `read_agent_transcript` (`agent/v1/coordinator_tools`), spelled
 * `createAgent`, `sendToAgent`, … on the documented stream — whose calls are in its transcript like any other tool's,
 * and whose arguments and results name the workers by id (`agent_id` / `agentId`, `worker_bc_id` / `workerBcId`,
 * `agent_ids[]`, `workers[].bc_id` / `bcId`). Without Cursor's account service that is the one lineage signal there
 * is: the ids are the workers' places (hints, or evidence when the coordinator's own tools made them); a chat is a
 * Project's root on the record's word alone (see `LineageSignal.isRootEvidence`), never on its transcript's.
 */
object CoordinatorLineage {

    /** The coordinator's tools that name its workers, under the proto's names; the streams spell them several ways. */
    val TOOLS: Set<String> = setOf("create_agent", "send_to_agent", "get_agent_status", "stop_agent", "read_agent_transcript")

    /**
     * Whether [name] is one of the coordinator's worker-naming tools, under any spelling the wire uses: the proto's
     * `create_agent`, the SDK vocabulary of the documented stream (`createAgent`, `sendToAgent`, `getAgentStatus`,
     * `stopAgent`, `readAgentTranscript`), the desktop's model-facing table (`CreateAgent`), with or without a
     * `ToolCall` / `_tool_call` suffix. Until 0.3.7 only the snake case matched, so a live stream's `sendToAgent`
     * and `createAgent` named no worker and default mode learned no lineage from a coordinator's transcript.
     */
    fun isCoordinatorTool(name: String): Boolean = ToolNames.coordinatorTool(name) in TOOLS

    /**
     * The coordinator's tool for speaking to the user (`SendMessage` / `sendMessage` on the stream, `send_to_user`
     * in the older proto): names no worker, but only a Project's coordinator has it.
     */
    fun isUserMessageTool(name: String): Boolean = ToolNames.coordinatorTool(name) == ToolNames.USER_MESSAGE_TOOL

    /**
     * Whether [call] is a coordinator's worker-naming call by name or by kind: a trace kept by an older build filed a
     * coordinator's call under the coordinator kind with the call's payload, and that is as good as the name.
     */
    fun isWorkerCall(call: ToolCall): Boolean = isCoordinatorTool(call.name) || (call.kind == ToolKind.Coordinator && call.payload is ToolPayload.WorkerAction)

    /** True when [items] hold a coordinator's tool call: the chat they belong to runs a Project. */
    fun isCoordinator(items: List<TimelineItem>): Boolean = calls(items).any { isWorkerCall(it) || isUserMessageTool(it.name) || it.payload is ToolPayload.CoordinatorMessage }

    /** The ids of the workers the coordinator's calls in [items] name, in the order they were first named. */
    fun workerIds(items: List<TimelineItem>): Set<String> =
        calls(items).filter { isWorkerCall(it) }.flatMapTo(LinkedHashSet()) { call ->
            call.linkedAgentIds.ifEmpty { (call.payload as? ToolPayload.WorkerAction)?.workers?.mapNotNull { it.agentId }.orEmpty() }
        }

    /**
     * The ids the coordinator's own tools returned as its workers: the one `create_agent` made, and the ones
     * `get_agent_status` listed (see [ToolPayload.WorkerAction.reported]). Positive evidence, where a mere mention —
     * a chat messaged, stopped or read — is a hint (see `LineageSignal`).
     */
    fun createdWorkerIds(items: List<TimelineItem>): Set<String> =
        calls(items).filter { isWorkerCall(it) }.flatMapTo(LinkedHashSet()) { call ->
            val action = call.payload as? ToolPayload.WorkerAction
            when {
                action != null && action.reported && (action.kind == ToolPayload.WorkerAction.Kind.Created || action.kind == ToolPayload.WorkerAction.Kind.Status) ->
                    action.workers.mapNotNull { it.agentId }
                // A trace kept before the payloads: a `create_agent` call names only the worker it made.
                action == null && ToolNames.coordinatorTool(call.name) == "create_agent" -> call.linkedAgentIds
                else -> emptyList()
            }
        }

    private fun calls(items: List<TimelineItem>): Sequence<ToolCall> =
        items.asSequence().filterIsInstance<ActivityGroup>().flatMap { it.calls.asSequence() }
}
