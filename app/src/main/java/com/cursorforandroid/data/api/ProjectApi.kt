package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.ProjectLineage
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

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

/** What a new primary is started with (the corner of the private launch request a phone fills in). */
data class WorkerLaunch(
    val prompt: String,
    val name: String? = null,
    val repoUrl: String? = null,
    val baseBranch: String? = null,
    val modelId: String? = null,
    val autoCreatePr: Boolean = false,
    /** Client-minted, so a retry after a lost reply creates nothing twice; the account keeps it as the worker's id. */
    val workerId: String = "bc-${UUID.randomUUID()}",
)

/** The coordinator's actions on the account service: spawn, adopt and release primaries, re-parent, restyle, side chats, steer and hold. */
interface ProjectActionsApi {
    /** `CreateProjectWorker`: a new primary under [managerId]; returns the worker's record. */
    suspend fun createWorker(managerId: String, launch: WorkerLaunch): ComposerSnapshot

    /** `SetWorkerManager`: an existing chat becomes [managerId]'s primary (adopted, unless said otherwise). */
    suspend fun setWorkerManager(workerId: String, managerId: String, spawnKind: WorkerSpawnKind = WorkerSpawnKind.ADOPTED)

    /** `ClearWorkerManager`: the worker is a chat of its own again. */
    suspend fun clearWorkerManager(workerId: String)

    /** `ReparentBackgroundComposer`: [agentId] becomes a cloud subagent of [parentId]. */
    suspend fun reparent(agentId: String, parentId: String, subagentType: String? = null)

    /** `UpdateProjectAppearance`: the Project's icon and colour; returns what the account now records. */
    suspend fun updateAppearance(projectId: String, appearance: ProjectAppearance): ProjectAppearance?

    /** `StartSideChatBackgroundComposer`: a side chat branched off [parentId]; returns its record. */
    suspend fun startSideChat(parentId: String, name: String?): ComposerSnapshot

    /** `InjectBackgroundComposerContext`: a steer into the running turn of [agentId]. */
    suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome

    /** `PauseBackgroundComposer` / `ResumeBackgroundComposer`. */
    suspend fun pause(agentId: String, runId: String?)
    suspend fun resume(agentId: String)
}

/** A Project's shared context (its Agent Store), read-only. */
interface AgentStoreApi {
    /** The store id of the store [sourceId] (a Project's coordinator) owns, or null when the account lists none for it. */
    suspend fun storeFor(sourceId: String): String?

    /** `ListAgentStoreEntries`: what [relativePath] ("" for the root) of [storeId] holds. */
    suspend fun entries(storeId: String, relativePath: String): List<ContextEntry>

    /** `ReadAgentStoreFile`: the text of one file. */
    suspend fun readFile(storeId: String, relativePath: String): String
}

/**
 * The Cursor Projects corner of `aiserver.v1.BackgroundComposerService` (see [BackgroundComposerApi] for the
 * service and its transport): the lineage reads behind the Project tree, the coordinator's actions and the
 * Project's shared context. Field names are the proto's in Connect JSON's lowerCamelCase; enums go out by their
 * proto names. Every call carries the account session from [SessionTokenProvider], which Extended mode alone hands out.
 */
class ProjectApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : ProjectLineageApi, ProjectActionsApi, AgentStoreApi {

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

    // ---- actions ----------------------------------------------------------------------------------------------------

    override suspend fun createWorker(managerId: String, launch: WorkerLaunch): ComposerSnapshot {
        val request = CreateProjectWorkerDto(
            managerBcId = managerId,
            creationId = launch.workerId,
            startRequest = StartRequestDto(
                prompt = launch.prompt,
                bcId = launch.workerId,
                source = SOURCE,
                repoUrl = launch.repoUrl?.takeIf { it.isNotBlank() },
                baseBranch = launch.baseBranch?.takeIf { it.isNotBlank() },
                autoCreatePr = launch.autoCreatePr.takeIf { it && launch.repoUrl != null },
                requestedModels = launch.modelId?.takeIf { it.isNotBlank() }?.let { listOf(RequestedModelDto(it)) },
                returnImmediately = true,
            ),
        )
        val response = call("CreateProjectWorker", request, CreateProjectWorkerDto.serializer(), ComposerResponseDto.serializer())
        return response.composer?.let { BackgroundComposerApi.snapshot(it) }
            ?: ComposerSnapshot(launch.workerId, name = launch.name)
    }

    override suspend fun setWorkerManager(workerId: String, managerId: String, spawnKind: WorkerSpawnKind) {
        call("SetWorkerManager", SetWorkerManagerDto(workerId, managerId, spawnKind.wireName), SetWorkerManagerDto.serializer(), EmptyDto.serializer())
    }

    override suspend fun clearWorkerManager(workerId: String) {
        call("ClearWorkerManager", WorkerBcIdDto(workerId), WorkerBcIdDto.serializer(), EmptyDto.serializer())
    }

    override suspend fun reparent(agentId: String, parentId: String, subagentType: String?) {
        call("ReparentBackgroundComposer", ReparentDto(agentId, parentId, PARENT_TYPE_CLOUD, subagentType), ReparentDto.serializer(), EmptyDto.serializer())
    }

    override suspend fun updateAppearance(projectId: String, appearance: ProjectAppearance): ProjectAppearance? {
        val response = call(
            "UpdateProjectAppearance",
            UpdateAppearanceDto(projectId, BackgroundComposerApi.ProjectAppearanceDto(appearance.icon, appearance.colorId)),
            UpdateAppearanceDto.serializer(),
            UpdateAppearanceResponseDto.serializer(),
        )
        return response.projectMetadata?.appearance?.takeIf { it.icon.isNotBlank() && it.colorId.isNotBlank() }?.let { ProjectAppearance(it.icon, it.colorId) }
    }

    override suspend fun startSideChat(parentId: String, name: String?): ComposerSnapshot {
        val request = StartSideChatDto(parentId, name?.takeIf { it.isNotBlank() }, SOURCE, "sc-${UUID.randomUUID()}")
        val response = call("StartSideChatBackgroundComposer", request, StartSideChatDto.serializer(), ComposerResponseDto.serializer())
        return response.composer?.let { BackgroundComposerApi.snapshot(it) } ?: throw ConnectRpcException(200, null, "Cursor started no side chat.")
    }

    override suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome {
        val request = InjectContextDto(
            bcId = agentId,
            injectContextAction = InjectContextActionDto(
                injectionId = "inj-${UUID.randomUUID()}",
                expectedRunId = expectedRunId,
                userContext = UserContextDto(UserMessageDto(text = text, messageId = "msg-${UUID.randomUUID()}")),
            ),
            source = SOURCE,
        )
        val response = call("InjectBackgroundComposerContext", request, InjectContextDto.serializer(), InjectContextResponseDto.serializer())
        return SteerOutcome.parse(response.outcome?.contentOrNull)
    }

    override suspend fun pause(agentId: String, runId: String?) {
        call("PauseBackgroundComposer", PauseDto(agentId, SOURCE, runId), PauseDto.serializer(), EmptyDto.serializer())
    }

    override suspend fun resume(agentId: String) {
        call("ResumeBackgroundComposer", BcIdDto(agentId), BcIdDto.serializer(), EmptyDto.serializer())
    }

    // ---- shared context ---------------------------------------------------------------------------------------------

    override suspend fun storeFor(sourceId: String): String? {
        var pageToken: String? = null
        repeat(MAX_STORE_PAGES) {
            val response = call("ListAgentStores", ListStoresDto(STORE_PAGE, pageToken), ListStoresDto.serializer(), ListStoresResponseDto.serializer())
            response.stores.firstOrNull { it.source?.sourceId == sourceId || it.sourceId == sourceId }?.storeId?.takeIf { it.isNotBlank() }?.let { return it }
            pageToken = response.nextPageToken?.takeIf { it.isNotBlank() && response.hasMore } ?: return null
        }
        return null
    }

    override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> {
        val response = call("ListAgentStoreEntries", StoreEntriesDto(storeId, relativePath), StoreEntriesDto.serializer(), StoreEntriesResponseDto.serializer())
        return response.entries.mapNotNull { entry ->
            val path = entry.relativePath?.takeIf { it.isNotBlank() } ?: entry.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ContextEntry(
                relativePath = path,
                isDirectory = entry.kind?.contentOrNull?.let { it.uppercase().endsWith("DIRECTORY") || it == "1" } == true,
                sizeBytes = entry.sizeBytes?.longOrNull,
                updatedAtMillis = entry.updatedAtMs?.longOrNull,
            )
        }.sortedWith(compareByDescending<ContextEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun readFile(storeId: String, relativePath: String): String =
        call("ReadAgentStoreFile", ReadStoreFileDto(storeId, relativePath), ReadStoreFileDto.serializer(), ReadStoreFileResponseDto.serializer()).content

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    // Request fields carry no defaults on purpose where the wire needs them: CursorJson does not encode defaults.

    @Serializable
    private data class ManagerBcIdDto(val managerBcId: String)

    @Serializable
    private data class ParentBcIdDto(val parentBcId: String)

    @Serializable
    private data class BcIdDto(val bcId: String)

    @Serializable
    private data class WorkerBcIdDto(val workerBcId: String)

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

    @Serializable
    private data class ComposerResponseDto(val composer: BackgroundComposerApi.ComposerDto? = null)

    @Serializable
    private data class CreateProjectWorkerDto(val managerBcId: String, val creationId: String, val startRequest: StartRequestDto)

    /** The corner of `StartBackgroundComposerFromSnapshotRequest` a worker started from here fills in. */
    @Serializable
    private data class StartRequestDto(
        val prompt: String,
        val bcId: String,
        val source: String,
        val repoUrl: String? = null,
        val baseBranch: String? = null,
        val autoCreatePr: Boolean? = null,
        val requestedModels: List<RequestedModelDto>? = null,
        val returnImmediately: Boolean,
    )

    @Serializable
    private data class RequestedModelDto(val modelId: String)

    @Serializable
    private data class SetWorkerManagerDto(val workerBcId: String, val managerBcId: String, val spawnKind: String)

    @Serializable
    private data class ReparentDto(val bcId: String, val parentAgentId: String, val parentAgentType: String, val subagentType: String? = null)

    @Serializable
    private data class UpdateAppearanceDto(val bcId: String, val appearance: BackgroundComposerApi.ProjectAppearanceDto)

    @Serializable
    private data class UpdateAppearanceResponseDto(val projectMetadata: BackgroundComposerApi.ProjectMetadataDto? = null)

    @Serializable
    private data class StartSideChatDto(val parentBcId: String, val name: String? = null, val creationSource: String, val creationId: String)

    @Serializable
    private data class InjectContextDto(val bcId: String, val injectContextAction: InjectContextActionDto, val source: String)

    @Serializable
    private data class InjectContextActionDto(val injectionId: String, val expectedRunId: String? = null, val userContext: UserContextDto)

    @Serializable
    private data class UserContextDto(val userMessage: UserMessageDto)

    @Serializable
    private data class UserMessageDto(val text: String, val messageId: String)

    @Serializable
    private data class InjectContextResponseDto(val outcome: JsonPrimitive? = null)

    @Serializable
    private data class PauseDto(val bcId: String, val source: String, val runId: String? = null)

    @Serializable
    private data class ListStoresDto(val n: Int, val pageToken: String? = null)

    @Serializable
    private data class ListStoresResponseDto(val stores: List<StoreDto> = emptyList(), val hasMore: Boolean = false, val nextPageToken: String? = null)

    @Serializable
    private data class StoreDto(val storeId: String = "", val sourceId: String? = null, val source: StoreSourceDto? = null)

    @Serializable
    private data class StoreSourceDto(val sourceId: String? = null)

    @Serializable
    private data class StoreEntriesDto(val storeId: String, val relativePath: String)

    @Serializable
    private data class StoreEntriesResponseDto(val entries: List<StoreEntryDto> = emptyList())

    @Serializable
    private data class StoreEntryDto(
        val name: String? = null,
        val relativePath: String? = null,
        val kind: JsonPrimitive? = null,
        val sizeBytes: JsonPrimitive? = null,
        val updatedAtMs: JsonPrimitive? = null,
    )

    @Serializable
    private data class ReadStoreFileDto(val storeId: String, val relativePath: String)

    @Serializable
    private data class ReadStoreFileResponseDto(val content: String = "")

    @Serializable
    private class EmptyDto

    private companion object {
        /** What this app is to the account service: a client on the API, like the SDK's chats. */
        val SOURCE = AgentSource.API.wireName
        /** `aiserver.v1.CloudSubagentParentAgentType`: the parent of a re-parented chat is a cloud agent. */
        const val PARENT_TYPE_CLOUD = "CLOUD_SUBAGENT_PARENT_AGENT_TYPE_CLOUD"
        const val STORE_PAGE = 50
        const val MAX_STORE_PAGES = 4
    }
}
