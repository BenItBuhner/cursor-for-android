package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.ProjectLineage
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Who belongs to whom on the account service, the way the Agents Window learns it: `ListWorkersForManager` for a
 * coordinator's workers, `ListBackgroundComposerChildren` for a chat's side chats and cloud subagents. An interface
 * so the repositories can be faked.
 */
interface ProjectLineageApi {
    suspend fun workersForManager(managerId: String): List<WorkerMembership>

    /** The chats branched off or spawned by [parentId], as the account's list would describe them. */
    suspend fun children(parentId: String): List<ComposerSnapshot>

    /** Both reads for one root, folded into what the root owns. */
    suspend fun lineage(rootId: String): ProjectLineage {
        val workers = workersForManager(rootId)
        val children = children(rootId).associate { child -> child.id to (child.parent?.kind ?: AgentParentKind.SUBAGENT) }
        return ProjectLineage(rootId, workers, children)
    }
}

/**
 * The Cursor Projects corner of `aiserver.v1.BackgroundComposerService` (see [BackgroundComposerApi] for the
 * service and its transport): the lineage reads behind the Project tree. Every call carries the account session
 * from [SessionTokenProvider], which Extended mode alone hands out.
 */
class ProjectApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : ProjectLineageApi {

    override suspend fun workersForManager(managerId: String): List<WorkerMembership> {
        val response = call("ListWorkersForManager", ManagerBcIdDto(managerId), ManagerBcIdDto.serializer(), WorkersForManagerResponseDto.serializer())
        return response.memberships.mapNotNull { membership ->
            val worker = membership.workerBcId.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            WorkerMembership(
                workerId = worker,
                managerId = membership.managerBcId?.trim()?.takeIf { it.isNotEmpty() } ?: managerId,
                spawnKind = WorkerSpawnKind.parse(membership.spawnKind?.contentOrNull) ?: WorkerSpawnKind.UNKNOWN,
                toolCallId = membership.toolCallId?.takeIf { it.isNotBlank() },
                status = membership.status?.contentOrNull?.takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun children(parentId: String): List<ComposerSnapshot> {
        val response = call("ListBackgroundComposerChildren", ParentBcIdDto(parentId), ParentBcIdDto.serializer(), ChildrenResponseDto.serializer())
        return response.composers.mapNotNull { BackgroundComposerApi.snapshot(it) }
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    @Serializable
    private data class ManagerBcIdDto(val managerBcId: String)

    @Serializable
    private data class ParentBcIdDto(val parentBcId: String)

    @Serializable
    private data class WorkersForManagerResponseDto(val memberships: List<WorkerMembershipDto> = emptyList())

    /** `aiserver.v1.WorkerMembership` as `ListWorkersForManager` reports it; enums by name or by number. */
    @Serializable
    internal data class WorkerMembershipDto(
        val workerBcId: String = "",
        val managerBcId: String? = null,
        val spawnKind: JsonPrimitive? = null,
        val toolCallId: String? = null,
        val status: JsonPrimitive? = null,
    )

    @Serializable
    private data class ChildrenResponseDto(val composers: List<BackgroundComposerApi.ComposerDto> = emptyList())
}
