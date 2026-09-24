package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.MachineWorker
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpTransport
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RepoRemote
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID

/**
 * A new chat started on the account service rather than the documented `POST /v1/agents`: what the composer asks for,
 * for the one case the documented request cannot carry — files of any type on the first prompt (Extended mode).
 * [agentId] is the client-minted `bc-…` the chat is shown under, as the desktop mints one before it asks.
 */
data class StartRequest(
    val agentId: String,
    val text: String,
    val images: List<PromptImage> = emptyList(),
    /** Uploaded files: an image among them joins `selected_images[]` by its upload reference, the rest go as `selected_documents[]`. */
    val files: List<UploadedFile> = emptyList(),
    /** The repository, or null for a chat with no repository (the account's personal no-repo environment). */
    val repoUrl: String?,
    /** The branch (or commit) to start from; null leaves it to the repository's default. */
    val ref: String? = null,
    /** A named cloud environment; null for the ordinary cloud. */
    val environmentName: String? = null,
    val modelId: String? = null,
    val modelParams: List<ModelParam> = emptyList(),
    val planMode: Boolean = false,
    val autoCreatePr: Boolean = false,
    val name: String? = null,
    val mcpServers: List<McpServer> = emptyList(),
    /** One of the user's machines to start it on, with [repoUrl] its repository; null for Cursor's cloud. */
    val machine: MachineStart? = null,
)

/**
 * The machine a start goes to: its [name] (the worker's `name` label, as the chat's `env.name` has it), and — when the
 * fleet endpoint has listed it — its [worker], whose registered repository decides whether the request names the
 * machine or only the repository. [ownerUserId] is the account's own id when the worker did not say whose it is.
 */
data class MachineStart(val name: String, val worker: MachineWorker? = null, val ownerUserId: Long? = null)

/** Starting an ordinary chat on the account service, the way the desktop Agents Window does when a prompt carries files. */
fun interface AgentStartApi {
    /** `StartBackgroundComposerFromSnapshot`; returns the account's record of the chat (its id is [StartRequest.agentId] unless the account renamed it). */
    suspend fun start(request: StartRequest): ComposerSnapshot
}

/**
 * The desktop's ordinary cloud-agent start (Cursor 3.20.21 `workbench.glass.main.js`, `CloudAgentRepository._createAgentReal`
 * → `StartBackgroundComposerFromSnapshot`), for the fields this composer has:
 *
 * - `bc_id` client-minted; `snapshot_name_or_id` = `host/owner/repo` (`$dm` → `Hg`), or `env|<public_id>` for a chat
 *   with no repository (`NpS`), whose `devcontainer_starting_point {environment_public_id}` names the account's
 *   personal no-repo environment (`_resolveCloudStartTarget` kind `noRepoEnvironment`); with a repository the
 *   starting point is `{url, ref?, environment_name?}` and the request carries `repo_url`, `base_branch` (`re`, the
 *   branch picked, when one was) and `auto_branch: true` (`le = i.autoBranch ?? true`).
 * - `snapshot_workspace_root_path: "/workspace"`, `return_immediately: true`, `source`, `repository_info {}`, `skills: []`,
 *   `add_initial_message_to_responses: true`, `team_id` left to the account.
 * - The prompt (`A1n(te, mode)`): `conversation_action { user_message_action { user_message: agent.v1.UserMessage
 *   {text, rich_text, message_id, mode, selected_context {selected_images[], selected_documents[]}},
 *   send_to_interaction_listener: true } }`, `starting_message_type: USER_MESSAGE`, and `conversation_history` holding
 *   the same text as one `MESSAGE_TYPE_HUMAN` message with `past_chats_explicitly_set`. The files are what
 *   `_buildSelectedContextForComposer` gets back from `_convertFilesToSelectedImages` (`HKy`) and
 *   `_convertFilesToSelectedDocuments` (`WKy`): `prompt_upload_ref` after a `PresignPromptUpload`, the bytes inline
 *   otherwise — an uploaded image as a `SelectedImage`, anything else as a `SelectedDocument`; pasted images stay inline.
 * - The model (`_buildStartRequestModelFields`): `requested_models: [agent.v1.RequestedModel {model_id, parameters[]}]`,
 *   `default` for Auto — the account refuses a start that names none.
 * - `auto_create_pr` (field 37) when asked for with a repository; `mcp_config_json` (field 95) with the enabled inline
 *   servers in the desktop's `{"mcpServers": {name: {url, headers} | {command, args, env}}}` shape (`csa`); `name` when set.
 *
 * On one of the user's machines ([StartRequest.machine]) the request is the desktop's for a machine picked with its
 * repository (3.21.18: the New Agent submit → `iZS` → `QPl` → `createAgent` → `_createAgentReal` →
 * `startBackgroundComposerFromSnapshot`, the same RPC as a cloud start): the repository the account's own project for
 * it names (see [machineRepository]) in `repo_url`, `snapshot_name_or_id` and the starting point — with the project's
 * environment when it is one — plus `use_private_worker: true`, `selected_private_worker_id` when the worker is known,
 * and `labels` — `repo=<owner/name>` from that URL (`T1t`), then `name=<worker>` and
 * `cursor.private_worker.shared_assignment_allowed=true` when that is the repository the worker registered
 * (`Mel` → `OOa`), with `private_worker_owner_filter {owner_cursor_user_id}` beside the name (`QPl`). No branch goes
 * with it unless one was picked: the desktop's rows for a machine come from a local workspace, which a phone has none
 * of (`ZXS`). A team pool is not started here.
 */
class ConnectAgentStartApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
    /** The account's personal no-repo environment, found or created (see `ConnectProjectCreationApi.noRepoEnvironmentPublicId`). */
    private val noRepoEnvironment: suspend () -> String,
) : AgentStartApi {

    override suspend fun start(request: StartRequest): ComposerSnapshot {
        require(request.agentId.isNotBlank()) { "A start needs the client-minted agent id." }
        val requested = request.repoUrl?.let(ConnectProjectCreationApi::canonicalUrl)?.takeIf { it.isNotEmpty() }
        val onMachineRepo = if (requested != null && request.machine != null) machineRepository(requested) else null
        val repo = onMachineRepo?.url ?: requested
        val ref = request.ref?.trim()?.takeIf { it.isNotEmpty() }
        val environmentName = request.environmentName?.trim()?.takeIf { it.isNotEmpty() }
        val startingPoint: StartingPointDto
        val snapshotName: String
        if (repo != null) {
            startingPoint = if (onMachineRepo != null) {
                StartingPointDto(url = repo, ref = ref, environmentName = onMachineRepo.environmentName, environmentPublicId = onMachineRepo.environmentPublicId)
            } else {
                StartingPointDto(url = repo, ref = ref, environmentName = environmentName)
            }
            snapshotName = ConnectProjectCreationApi.snapshotName(repo)
        } else if (environmentName != null) {
            startingPoint = StartingPointDto(environmentName = environmentName)
            snapshotName = ""
        } else {
            val publicId = noRepoEnvironment()
            startingPoint = StartingPointDto(environmentPublicId = publicId)
            snapshotName = "${ConnectProjectCreationApi.ENVIRONMENT_SNAPSHOT_PREFIX}$publicId"
        }
        val text = request.text.trim()
        val mode = (if (request.planMode) AgentMode.PLAN else AgentMode.AGENT).wireName
        val inlineImages = request.images.map { image ->
            SelectedImageDto(data = image.base64, mimeType = image.mimeType.lowercase(), uuid = UUID.randomUUID().toString())
        }
        val uploadedImages = request.files.filter { it.isImage }.map { file ->
            SelectedImageDto(
                uuid = file.uuid,
                mimeType = file.mimeType.lowercase(),
                promptUploadRef = file.uploadId?.let { PromptUploadRefDto(it) },
                data = if (file.uploadId == null) file.data?.let { Base64.getEncoder().encodeToString(it) } else null,
            )
        }
        val images = (inlineImages + uploadedImages).takeIf { it.isNotEmpty() }
        val documents = request.files.filterNot { it.isImage }.takeIf { it.isNotEmpty() }?.map { file ->
            SelectedDocumentDto(
                uuid = file.uuid,
                filename = file.filename,
                mimeType = file.mimeType.ifBlank { "application/octet-stream" },
                promptUploadRef = file.uploadId?.let { PromptUploadRefDto(it) },
                data = if (file.uploadId == null) file.data?.let { Base64.getEncoder().encodeToString(it) } else null,
            )
        }
        val onMachine = if (repo != null) request.machine?.let { privateWorkerFields(it, repo) } else null
        val dto = StartDto(
            bcId = request.agentId,
            snapshotNameOrId = snapshotName,
            devcontainerStartingPoint = startingPoint,
            snapshotWorkspaceRootPath = ConnectProjectCreationApi.WORKSPACE_ROOT,
            returnImmediately = true,
            repoUrl = repo,
            source = SOURCE,
            autoBranch = true,
            baseBranch = if (repo != null) ref else null,
            conversationAction = ConversationActionDto(
                UserMessageActionDto(
                    userMessage = UserMessageDto(
                        text = text,
                        richText = text,
                        messageId = "msg-${UUID.randomUUID()}",
                        mode = mode,
                        selectedContext = if (images == null && documents == null) null else SelectedContextDto(images, documents),
                    ),
                    sendToInteractionListener = true,
                ),
            ),
            startingMessageType = ConnectProjectCreationApi.STARTING_USER_MESSAGE,
            conversationHistory = listOf(HistoryMessageDto(text = text, richText = text, type = ConnectProjectCreationApi.MESSAGE_HUMAN, pastChatsExplicitlySet = true)),
            addInitialMessageToResponses = true,
            repositoryInfo = RepositoryInfoDto(),
            skills = emptyList(),
            name = request.name?.trim()?.takeIf { it.isNotEmpty() },
            requestedModels = listOf(requestedModel(request.modelId, request.modelParams)),
            autoCreatePr = (request.autoCreatePr && repo != null).takeIf { it },
            mcpConfigJson = mcpConfigJson(request.mcpServers),
            labels = onMachine?.labels,
            privateWorkerOwnerFilter = onMachine?.ownerFilter,
            selectedPrivateWorkerId = onMachine?.workerId,
            usePrivateWorker = onMachine?.let { true },
        )
        val response = call("StartBackgroundComposerFromSnapshot", dto, StartDto.serializer(), StartResponseDto.serializer())
        val record = response.composer?.let { BackgroundComposerApi.snapshot(it) }
        return record ?: ComposerSnapshot(request.agentId, name = request.name, archived = false)
    }

    /** The repository a machine's start names, and the account environment it is Cursor's copy of, when one is. */
    private class MachineRepository(val url: String, val environmentPublicId: String?, val environmentName: String?)

    /**
     * The desktop's `nZS` → `D0t` → `_resolveCloudStartTarget` for a machine picked with [requested]: the account's own
     * project for the repository the machine registered, matched by its host-less `owner/name` (`T1t`, any case). An
     * account environment whose one repository has that label (`ListEnvironments`, `BSS`) gives its URL, its public id
     * (`environment_public_id`) and its name (`environment_name`); without one the repository goes as picked — the
     * catalogue's URL, or the worker's own — named `owner/name` as the desktop names a repository template. Whatever
     * the account cannot say leaves the repository as picked: the start goes out all the same.
     */
    private suspend fun machineRepository(requested: String): MachineRepository {
        val label = RepoRemote.label(requested)
        val environment = label?.let { wanted -> runCatching { environmentFor(wanted) }.getOrNull() }
        if (environment != null) {
            val url = ConnectProjectCreationApi.canonicalUrl(environment.repoUrl).ifEmpty { requested }
            return MachineRepository(url, environment.publicId, environment.name)
        }
        return MachineRepository(requested, environmentPublicId = null, environmentName = label)
    }

    /** The account's single-repository environment for [label], the first the account lists (`qZ` keeps one per repository). */
    private suspend fun environmentFor(label: String): AccountEnvironment? {
        var pageToken: String? = null
        for (page in 0 until ConnectProjectCreationApi.MAX_ENVIRONMENT_PAGES) {
            val listed = call("ListEnvironments", ListEnvironmentsDto(includeEnvironmentJson = false, pageToken = pageToken), ListEnvironmentsDto.serializer(), ListEnvironmentsResponseDto.serializer())
            listed.environments.firstNotNullOfOrNull { entry -> AccountEnvironment.of(entry)?.takeIf { RepoRemote.label(it.repoUrl).equals(label, ignoreCase = true) } }?.let { return it }
            pageToken = listed.nextPageToken?.takeIf { listed.hasMore && it.isNotBlank() } ?: return null
        }
        return null
    }

    private class PrivateWorkerFields(val labels: List<LabelDto>, val ownerFilter: OwnerFilterDto?, val workerId: String?)

    /**
     * `Mel` / `OOa` / `QPl` for [machine] and the repository [repo]: the machine is named — with its owner, without
     * which the desktop does not name one — when [repo] is the one its worker registered, or when no listing has
     * said which that is; a repository picked over the worker's own goes out by its label alone.
     */
    private fun privateWorkerFields(machine: MachineStart, repo: String): PrivateWorkerFields {
        val repoLabel = RepoRemote.label(repo) ?: repo.substringAfter("://")
        val worker = machine.worker
        val owner = worker?.ownerUserId ?: machine.ownerUserId
        val registered = worker?.repoLabel
        val named = owner != null && (registered == null || registered.equals(repoLabel, ignoreCase = true))
        val labels = buildList {
            add(LabelDto(REPO_LABEL, repoLabel))
            if (named) {
                add(LabelDto(NAME_LABEL, worker?.name ?: machine.name))
                add(LabelDto(SHARED_ASSIGNMENT_LABEL, "true"))
            }
        }
        return PrivateWorkerFields(labels, owner?.takeIf { named }?.let(::OwnerFilterDto), worker?.workerId)
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    /** `agent.v1.RequestedModel` for the pick, or `default` (Auto) when none was made; `parameters` only when there are any. */
    private fun requestedModel(modelId: String?, params: List<ModelParam>): RequestedModelDto {
        val id = modelId?.trim()?.takeIf { it.isNotEmpty() } ?: AccountModel.AUTO_ID
        return RequestedModelDto(modelId = id, parameters = params.filter { it.id.isNotBlank() }.map { ModelParameterDto(it.id, it.value) }.takeIf { it.isNotEmpty() })
    }

    @Serializable
    private data class StartDto(
        val bcId: String,
        val snapshotNameOrId: String,
        val devcontainerStartingPoint: StartingPointDto,
        val snapshotWorkspaceRootPath: String,
        val returnImmediately: Boolean,
        val repoUrl: String? = null,
        val source: String,
        val autoBranch: Boolean,
        val baseBranch: String? = null,
        val conversationAction: ConversationActionDto,
        val startingMessageType: String,
        val conversationHistory: List<HistoryMessageDto>,
        val addInitialMessageToResponses: Boolean,
        val repositoryInfo: RepositoryInfoDto,
        val skills: List<String>,
        val name: String? = null,
        val requestedModels: List<RequestedModelDto>,
        val autoCreatePr: Boolean? = null,
        val mcpConfigJson: String? = null,
        /** Field 59, `aiserver.v1.PrivateWorkerLabel {key, value}`. */
        val labels: List<LabelDto>? = null,
        /** Field 97, `aiserver.v1.PrivateWorkerOwnerFilter {owner_cursor_user_id}`. */
        val privateWorkerOwnerFilter: OwnerFilterDto? = null,
        /** Field 112. */
        val selectedPrivateWorkerId: String? = null,
        /** Field 55. */
        val usePrivateWorker: Boolean? = null,
    )

    /** `aiserver.v1.ListEnvironmentsRequest` as `_fetchLogicalEnvironments` sends it: `{include_environment_json: false}`, then the page. */
    @Serializable
    private data class ListEnvironmentsDto(val includeEnvironmentJson: Boolean, val pageToken: String? = null)

    /** Each environment kept as it came and read on its own ([AccountEnvironment.of]): one this build cannot read costs nothing else. */
    @Serializable
    private data class ListEnvironmentsResponseDto(val environments: List<JsonElement> = emptyList(), val hasMore: Boolean = false, val nextPageToken: String? = null)

    /** `aiserver.v1.LogicalEnvironment {public_id, name, repo_config {repos[{repo_url}]}}`, the fields `BSS` keeps. */
    @Serializable
    private data class EnvironmentDto(val publicId: String? = null, val name: String? = null, val repoConfig: EnvironmentRepoConfigDto? = null)

    @Serializable
    private data class EnvironmentRepoConfigDto(val repos: List<EnvironmentRepoDto> = emptyList())

    @Serializable
    private data class EnvironmentRepoDto(val repoUrl: String = "")

    /** An environment of one repository, as `BSS` makes a template of it: several repositories, or none, is no machine's. */
    private class AccountEnvironment(val repoUrl: String, val publicId: String?, val name: String?) {
        companion object {
            fun of(entry: JsonElement): AccountEnvironment? {
                val environment = runCatching { CursorJson.decodeFromJsonElement(EnvironmentDto.serializer(), entry) }.getOrNull() ?: return null
                val urls = environment.repoConfig?.repos.orEmpty().map { it.repoUrl.trim() }.filter { it.isNotEmpty() }
                val url = urls.firstOrNull() ?: return null
                if (urls.map(ConnectProjectCreationApi::snapshotName).distinct().size > 1) return null
                // `LWe`: a public id of digits alone is a legacy row id, which no start names.
                val publicId = environment.publicId?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
                return AccountEnvironment(url, publicId, environment.name?.trim()?.takeIf { it.isNotEmpty() })
            }
        }
    }

    @Serializable
    private data class LabelDto(val key: String, val value: String)

    @Serializable
    private data class OwnerFilterDto(val ownerCursorUserId: Long)

    /** `aiserver.v1.DevcontainerStartingPoint`: `url` (1), `ref` (2), `environment_name` (12) or `environment_public_id` (15). */
    @Serializable
    private data class StartingPointDto(val url: String? = null, val ref: String? = null, val environmentName: String? = null, val environmentPublicId: String? = null)

    @Serializable
    private data class ConversationActionDto(val userMessageAction: UserMessageActionDto)

    @Serializable
    private data class UserMessageActionDto(val userMessage: UserMessageDto, val sendToInteractionListener: Boolean)

    /** `agent.v1.UserMessage {1 text, 8 rich_text, 2 message_id, 4 mode, 3 selected_context}`. */
    @Serializable
    private data class UserMessageDto(val text: String, val richText: String, val messageId: String, val mode: String, val selectedContext: SelectedContextDto? = null)

    @Serializable
    private data class SelectedContextDto(val selectedImages: List<SelectedImageDto>? = null, val selectedDocuments: List<SelectedDocumentDto>? = null)

    /** `agent.v1.SelectedImage`: `data` inline, or `prompt_upload_ref` after an upload (`HKy`). */
    @Serializable
    private data class SelectedImageDto(val uuid: String, val mimeType: String, val data: String? = null, val promptUploadRef: PromptUploadRefDto? = null)

    @Serializable
    private data class SelectedDocumentDto(val uuid: String, val filename: String, val mimeType: String, val promptUploadRef: PromptUploadRefDto? = null, val data: String? = null)

    @Serializable
    private data class PromptUploadRefDto(val uploadId: String)

    /** `aiserver.v1.ConversationMessage` as `A1n` puts the prompt in the history. */
    @Serializable
    private data class HistoryMessageDto(val text: String, val richText: String, val type: String, val pastChats: List<String> = emptyList(), val pastChatsExplicitlySet: Boolean)

    @Serializable
    private class RepositoryInfoDto

    @Serializable
    private data class RequestedModelDto(val modelId: String, val parameters: List<ModelParameterDto>? = null)

    @Serializable
    private data class ModelParameterDto(val id: String, val value: String)

    @Serializable
    private data class StartResponseDto(val composer: BackgroundComposerApi.ComposerDto? = null)

    companion object {
        /** What this app is to the account service: a client on the API, like the SDK's chats (the desktop sends `GLASS`). */
        val SOURCE: String = AgentSource.API.wireName

        const val REPO_LABEL = "repo"
        const val NAME_LABEL = "name"
        /** The desktop's `BOa`. */
        const val SHARED_ASSIGNMENT_LABEL = "cursor.private_worker.shared_assignment_allowed"

        /**
         * The desktop's `csa` / `Yuy`: `{"mcpServers": {name: {url, headers?} | {command, args?, env?}}}` for the enabled
         * servers; null when none is, so the field stays off the request.
         */
        fun mcpConfigJson(servers: List<McpServer>): String? {
            val enabled = servers.filter { it.enabled && it.name.isNotBlank() }
            if (enabled.isEmpty()) return null
            val config = buildJsonObject {
                put(
                    "mcpServers",
                    JsonObject(
                        enabled.associate { server ->
                            server.name to when (server.transport) {
                                McpTransport.Stdio -> buildJsonObject {
                                    put("command", server.command)
                                    if (server.args.isNotEmpty()) put("args", JsonArray(server.args.map(::JsonPrimitive)))
                                    if (server.env.isNotEmpty()) put("env", JsonObject(server.env.mapValues { JsonPrimitive(it.value) }))
                                }
                                McpTransport.Http -> buildJsonObject {
                                    put("url", server.url)
                                    if (server.headers.isNotEmpty()) put("headers", JsonObject(server.headers.mapValues { JsonPrimitive(it.value) }))
                                }
                            }
                        },
                    ),
                )
            }
            return config.toString()
        }
    }
}
