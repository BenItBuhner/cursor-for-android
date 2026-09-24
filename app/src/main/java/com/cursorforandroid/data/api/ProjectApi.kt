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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** A root's own account record, when the source has one to give (see `ComposerRecordApi`); null when not. */
    suspend fun record(id: String): ComposerSnapshot? = null

    /** The root discovery pass over the whole account list (see `RootScanApi`); null when the source has no list to scan. */
    suspend fun scanRoots(maxPages: Int): RootScan? = null

    /** The same pass stopped at the page older than [stopBelowActivityMillis] (see `RootScanApi.scanRoots`); sources that cannot date their pages read as [scanRoots]. */
    suspend fun scanRoots(maxPages: Int, stopBelowActivityMillis: Long?): RootScan? = scanRoots(maxPages)

    /** The chats branched off or spawned by [parentId], as the account's list would describe them. */
    suspend fun children(parentId: String): List<ComposerSnapshot>

    /**
     * Both reads of one root, each on its own: the workers `ListWorkersForManager` names and the side chats and
     * subagents `ListBackgroundComposerChildren` lists. One refusing does not lose the other's answer — a Project
     * whose children call is not offered still has its workers placed — and the result says which read answered.
     */
    suspend fun lineage(rootId: String): ProjectLineage = coroutineScope {
        // Both reads at once: neither waits on the other's round trip.
        val workersRead = async {
            try {
                Result.success(workersForManager(rootId))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        val childrenRead = async {
            try {
                Result.success(children(rootId))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        val workers = workersRead.await().getOrNull()
        val childRecords = childrenRead.await().getOrNull()
        val failure = workersRead.await().exceptionOrNull() ?: childrenRead.await().exceptionOrNull()
        ProjectLineage(
            rootId = rootId,
            workers = workers.orEmpty(),
            children = childRecords.orEmpty().associate { child -> child.id to (child.parent?.kind ?: AgentParentKind.SUBAGENT) },
            workersRead = workers != null,
            childrenRead = childRecords != null,
            failure = failure,
            childRecords = childRecords.orEmpty(),
        )
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

/** A Project's shared context (its Agent Store): its listing and files, and one new file written into it. */
interface AgentStoreApi {
    /** The store id of the store [sourceId] (a Project's coordinator) owns, or null when the account lists none for it. */
    suspend fun storeFor(sourceId: String): String?

    /** `ListAgentStoreEntries`: what [relativePath] ("" for the root) of [storeId] holds. */
    suspend fun entries(storeId: String, relativePath: String): List<ContextEntry>

    /** `ReadAgentStoreFile`: the text of one file. */
    suspend fun readFile(storeId: String, relativePath: String): String

    /**
     * `PresignAgentStoreReads`: a URL the bytes of [relativePath] in the store [target] names can be fetched from
     * for a while. Null when the service answered with no instruction for it.
     */
    suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead?

    /**
     * `PresignAgentStoreWrites`: a URL the bytes of a new file at [relativePath] in [storeId] are to be `PUT` to,
     * with the headers the write must carry, for a while. The file is declared by its [sizeBytes] and its SHA-256
     * ([sha256Hex], lowercase), which the storage checks against what arrives; the write expects no file at the
     * path yet, so it never overwrites. Null when the service answered with no instruction for it. A source that
     * cannot write says so.
     */
    suspend fun presignWrite(storeId: String, relativePath: String, sizeBytes: Long, sha256Hex: String): PresignedStoreWrite? =
        throw UnsupportedOperationException("This source cannot write to a store.")
}

/**
 * Which store `PresignAgentStoreReads` is asked about. Its request carries `agent_id`, `share_id?` and `store_id?`,
 * and the service wants exactly one of them ("Exactly one of share_id, store_id, or legacy agent_id is required"):
 * the store by its id — what `ListAgentStores` names, and what the desktop mounts a Project's context by — or, the
 * legacy path for when no store id is known, the agent whose store it is.
 */
sealed interface StoreReadTarget {
    data class Store(val storeId: String) : StoreReadTarget
    data class Agent(val agentId: String) : StoreReadTarget
}

/** `aiserver.v1.AgentStoreReadInstruction`: where a store file's bytes are, and until when. */
data class PresignedStoreRead(val relativePath: String, val url: String, val expiresAtMillis: Long?)

/**
 * `aiserver.v1.AgentStoreWriteInstruction`: where a store file's bytes go, the headers the `PUT` must carry (the
 * content's length and SHA-256 checksum, `If-None-Match: *` for a file that must not exist yet), until when the URL
 * is good, and whether the storage already refused the precondition (the file exists).
 */
data class PresignedStoreWrite(val relativePath: String, val url: String, val headers: Map<String, String>, val expiresAtMillis: Long?, val preconditionFailed: Boolean)

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
                // The account refuses a start that names no model; the desktop sends `default` (Auto) when none is chosen.
                requestedModels = listOf(RequestedModelDto(launch.modelId?.trim()?.takeIf { it.isNotEmpty() } ?: "default")),
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

    /**
     * The Agents Window's own request: `parent_bc_id`, the optional `name` (left out when blank; the account names
     * the chat "New Side Chat"), `creation_source` — a `BackgroundComposerSource`, the desktop's `GLASS`, this app's
     * `API` — and `creation_id`, a fresh UUID the account hashes into the new chat's `bc_id` (`side-chat:<parent>:<id>`),
     * so a retry with the same id creates nothing twice. The record that comes back names its parent in `sideChatInfo`.
     */
    override suspend fun startSideChat(parentId: String, name: String?): ComposerSnapshot {
        val request = StartSideChatDto(parentId, name?.trim()?.takeIf { it.isNotEmpty() }, SOURCE, UUID.randomUUID().toString())
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
            val response = call("ListAgentStores", ListStoresDto(STORE_PAGE, pageToken), ListStoresDto.serializer(), ListStoresResponseDto.serializer(), ApiThrottle.Lane.MEDIA)
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

    override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead? {
        // Exactly one identifier goes out: CursorJson leaves a null field off the wire.
        val request = when (target) {
            is StoreReadTarget.Store -> PresignReadsDto(relPaths = listOf(relativePath), storeId = target.storeId)
            is StoreReadTarget.Agent -> PresignReadsDto(relPaths = listOf(relativePath), agentId = target.agentId)
        }
        val response = call("PresignAgentStoreReads", request, PresignReadsDto.serializer(), PresignReadsResponseDto.serializer(), ApiThrottle.Lane.MEDIA)
        val instruction = response.instructions.firstOrNull { it.relPath == relativePath } ?: response.instructions.firstOrNull() ?: return null
        val url = instruction.url?.takeIf { it.isNotBlank() } ?: return null
        return PresignedStoreRead(instruction.relPath ?: relativePath, url, instruction.expiresAtMs?.longOrNull)
    }

    /**
     * The request the agents' own store mount makes for a small file (Cursor 3.20.21's `cursor-agent-store-fuse`,
     * seen on the wire): `{storeId, files: [{relPath, sizeBytes, sha, expectAbsent: true}], supportsSignedContentLength: true}`
     * — the store by its id alone, the size as the int64 string proto3 JSON writes, the SHA-256 in hex — answered
     * with `{instructions: [{relPath, url, headers: {Content-Length, x-amz-checksum-sha256, If-None-Match}, expiresAtMs, conflict?}]}`.
     * The `PUT` that follows is the caller's, with those headers; no completion call is needed for a single part.
     */
    override suspend fun presignWrite(storeId: String, relativePath: String, sizeBytes: Long, sha256Hex: String): PresignedStoreWrite? {
        val request = PresignWritesDto(
            storeId = storeId,
            files = listOf(WriteFileEntryDto(relPath = relativePath, sizeBytes = sizeBytes.toString(), sha = sha256Hex, expectAbsent = true)),
            supportsSignedContentLength = true,
        )
        val response = call("PresignAgentStoreWrites", request, PresignWritesDto.serializer(), PresignWritesResponseDto.serializer())
        val instruction = response.instructions.firstOrNull { it.relPath == relativePath } ?: response.instructions.firstOrNull() ?: return null
        val url = instruction.url?.takeIf { it.isNotBlank() } ?: return null
        return PresignedStoreWrite(instruction.relPath ?: relativePath, url, instruction.headers, instruction.expiresAtMs?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }, instruction.primaryPreconditionFailed == true)
    }

    private suspend fun <I, O> call(
        method: String,
        body: I,
        requestSerializer: KSerializer<I>,
        responseSerializer: KSerializer<O>,
        lane: ApiThrottle.Lane = ApiThrottle.Lane.CONTROL,
    ): O = rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer, lane = lane)

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
    private data class UpdateAppearanceResponseDto(val projectMetadata: ProjectMetadataDto? = null)

    @Serializable
    private data class ProjectMetadataDto(val appearance: BackgroundComposerApi.ProjectAppearanceDto? = null)

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

    /** `PresignAgentStoreReadsRequest {agent_id, rel_paths[], share_id?, store_id?}`, one identifier set; the paths are the store's relative ones. */
    @Serializable
    private data class PresignReadsDto(val agentId: String? = null, val relPaths: List<String>, val storeId: String? = null)

    @Serializable
    private data class PresignReadsResponseDto(val instructions: List<ReadInstructionDto> = emptyList())

    /** `AgentStoreReadInstruction {rel_path, url, expires_at_ms}`; the stamp is an int64, a string or a number in Connect JSON. */
    @Serializable
    private data class ReadInstructionDto(val relPath: String? = null, val url: String? = null, val expiresAtMs: JsonPrimitive? = null)

    /** `PresignAgentStoreWritesRequest {agent_id, files[], store_id?, lock_token?, lock_client_uuid?, supports_signed_content_length}`: the store by id, no agent. */
    @Serializable
    private data class PresignWritesDto(val storeId: String, val files: List<WriteFileEntryDto>, val supportsSignedContentLength: Boolean)

    /** `AgentStoreWriteFileEntry {rel_path, size_bytes, sha, base_etag | expect_absent, multipart_parts[]}`; the size an int64 string, no parts for one `PUT`. */
    @Serializable
    private data class WriteFileEntryDto(val relPath: String, val sizeBytes: String, val sha: String, val expectAbsent: Boolean? = null)

    @Serializable
    private data class PresignWritesResponseDto(val instructions: List<WriteInstructionDto> = emptyList())

    /** `AgentStoreWriteInstruction {rel_path, url, headers, expires_at_ms, conflict?, lock_redirect?, multipart?, primary_precondition_failed}`. */
    @Serializable
    private data class WriteInstructionDto(
        val relPath: String? = null,
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val expiresAtMs: JsonPrimitive? = null,
        val primaryPreconditionFailed: Boolean? = null,
    )

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
