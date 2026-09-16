package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.ProjectAppearance
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.UUID

/**
 * What a new Project is created with: its name, its look, and the repositories it owns. [projectId] is minted here,
 * as the desktop mints a `bc-<uuid>` before it asks, so a retry after a lost reply creates nothing twice and the
 * sidebar can hold the row under its final id at once.
 */
data class ProjectDraft(
    val name: String,
    val appearance: ProjectAppearance,
    /** Repository URLs, the first of them primary; empty for a Project with no repository (an empty cloud environment). */
    val repoUrls: List<String>,
    val projectId: String = "bc-${UUID.randomUUID()}",
)

/** Creating and renaming a Project on the account service, the way the desktop Agents Window does it. */
interface ProjectCreationApi {
    /**
     * Creates the Project: the coordinator chat, started on its repositories with the desktop's kickoff. Returns its
     * account record, which carries `projectMetadata` — the flag the root registry admits a Project on.
     */
    suspend fun createProject(draft: ProjectDraft): ComposerSnapshot

    /** `RenameBackgroundComposer`: the Project's (the coordinator chat's) name; returns the name the account now holds. */
    suspend fun renameProject(projectId: String, name: String): String
}

/**
 * The desktop's "Create Project" flow (Cursor 3.20.21 `workbench.glass.main.js`: `CreateProjectDialog` → `createAgent`
 * → `CloudAgentRepository._createAgentReal` → `StartBackgroundComposerFromSnapshot`), replicated field for field:
 *
 * - The dialog collects an icon and colour (a random pair from the catalog when none is chosen — `zri(A_t)`), a name
 *   (`"New Project"` when blank), a Workspace (the repository, or a multi-repository environment) and a model; it
 *   submits the kickoff text `"Start this Project."` as a simulated `PROJECT_KICKOFF` user message.
 * - `_createAgentReal` builds one `StartBackgroundComposerFromSnapshotRequest`: the client-minted `bc_id`;
 *   `snapshot_name_or_id` = the primary repository as `host/owner/repo` (`Hg`); `devcontainer_starting_point {url}`,
 *   with `repo_config {repos[{repo_url, scm_repo_node_id: ""}]}` for more than one repository (`gQp`);
 *   `snapshot_workspace_root_path: "/workspace"`; `return_immediately`; `repo_url`; `source`; `auto_branch`;
 *   `conversation_action { user_message_action { user_message, send_to_interaction_listener } }` with
 *   `starting_message_type: USER_MESSAGE` and `conversation_history` holding the same message (`A1n`);
 *   `add_initial_message_to_responses`; `repository_info {path_encryption_key: "", should_sync_index: false}`;
 *   `skills: []`; `name`; `project_details {name}` (`agent.v1.ProjectDetails`); `project_metadata {appearance {icon,
 *   color_id}}` (`aiserver.v1.ProjectMetadata`). Nothing else marks the chat a Project: the account reads
 *   `project_details` and answers with a record whose `projectMetadata` is set.
 * - A Project with no repository starts in a no-repo cloud environment, the way the desktop's no-repo cloud target
 *   does (`_resolveCloudStartTarget` kind `noRepoEnvironment`; the automations runtime's
 *   `ensureNoRepoAutomationEnvironment`): `ListEnvironments {include_environment_json, repository_scope_repo_urls: []}`
 *   for a personal environment with an empty `repo_config` and a blank `environment_json`, else
 *   `SetPersonalEnvironmentJson {environment_json: "{}", repo_url: "", write_source: DASHBOARD, repo_config {repos: []}}`;
 *   the start request then names it — `snapshot_name_or_id: "env|<public_id>"`, `devcontainer_starting_point
 *   {environment_public_id}` — and carries no repository.
 * - The desktop then opens the created Project (`selectProject(header.id)`); the caller here does the same.
 *
 * Field names are the proto's in Connect JSON's lowerCamelCase; enums go out by their proto names. Every call
 * carries the account session, which Extended mode alone hands out.
 */
class ConnectProjectCreationApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : ProjectCreationApi {

    override suspend fun createProject(draft: ProjectDraft): ComposerSnapshot {
        val name = draft.name.trim().ifEmpty { DEFAULT_NAME }
        val repos = draft.repoUrls.map(::canonicalUrl).filter { it.isNotEmpty() }.distinct()
        val primary = repos.firstOrNull()
        val startingPoint = if (primary != null) {
            StartingPointDto(
                url = primary,
                repoConfig = if (repos.size > 1) RepoConfigDto(repos.map { RepoEntryDto(repoUrl = it) }) else null,
            )
        } else {
            StartingPointDto(environmentPublicId = noRepoEnvironment())
        }
        val messageId = "msg-${UUID.randomUUID()}"
        val request = StartFromSnapshotDto(
            bcId = draft.projectId,
            snapshotNameOrId = primary?.let(::snapshotName) ?: "$ENVIRONMENT_SNAPSHOT_PREFIX${startingPoint.environmentPublicId}",
            devcontainerStartingPoint = startingPoint,
            snapshotWorkspaceRootPath = WORKSPACE_ROOT,
            returnImmediately = true,
            repoUrl = primary,
            source = SOURCE,
            autoBranch = true,
            conversationAction = ConversationActionDto(
                UserMessageActionDto(
                    userMessage = KickoffMessageDto(
                        text = KICKOFF_TEXT,
                        messageId = messageId,
                        mode = MODE_AGENT,
                        isSimulatedMsg = true,
                        simulatedMsgReason = KICKOFF_REASON,
                        simulatedMessageMetadata = EmptyDto(),
                    ),
                    sendToInteractionListener = true,
                ),
            ),
            startingMessageType = STARTING_USER_MESSAGE,
            conversationHistory = listOf(ConversationMessageDto(text = KICKOFF_TEXT, richText = KICKOFF_TEXT, type = MESSAGE_HUMAN, pastChatsExplicitlySet = true)),
            addInitialMessageToResponses = true,
            repositoryInfo = RepositoryInfoDto(),
            skills = emptyList(),
            name = name,
            projectDetails = ProjectDetailsDto(name),
            projectMetadata = ProjectMetadataDto(BackgroundComposerApi.ProjectAppearanceDto(draft.appearance.icon, draft.appearance.colorId)),
        )
        val response = call("StartBackgroundComposerFromSnapshot", request, StartFromSnapshotDto.serializer(), ComposerResponseDto.serializer())
        val record = response.composer?.let { BackgroundComposerApi.snapshot(it) }
        // The account answered without the record: the row is stood in from what was asked for, flagged as the account
        // will flag it, until the account's list carries the record.
        return record ?: ComposerSnapshot(draft.projectId, name = name, archived = false, isProject = true, projectAppearance = draft.appearance, record = RecordFields(projectMetadata = "{}"))
    }

    override suspend fun renameProject(projectId: String, name: String): String {
        val response = call("RenameBackgroundComposer", RenameDto(projectId, name.trim()), RenameDto.serializer(), RenameResponseDto.serializer())
        return response.name?.takeIf { it.isNotBlank() } ?: name.trim()
    }

    /** The account's personal no-repo environment — found among its environments, or created empty — by its public id. */
    private suspend fun noRepoEnvironment(): String {
        val listed = call("ListEnvironments", ListEnvironmentsDto(includeEnvironmentJson = true, repositoryScopeRepoUrls = emptyList()), ListEnvironmentsDto.serializer(), ListEnvironmentsResponseDto.serializer())
        listed.environments.firstOrNull { it.isPersonalNoRepo }?.publicId?.takeIf { it.isNotBlank() }?.let { return it }
        val created = call(
            "SetPersonalEnvironmentJson",
            SetPersonalEnvironmentDto(environmentJson = "{}", repoUrl = "", writeSource = WRITE_SOURCE_DASHBOARD, repoConfig = RepoConfigDto(emptyList())),
            SetPersonalEnvironmentDto.serializer(),
            SetPersonalEnvironmentResponseDto.serializer(),
        )
        return created.environment?.publicId?.takeIf { it.isNotBlank() } ?: throw ConnectRpcException(200, null, "Cursor created no environment for a Project without a repository.")
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    @Serializable
    private data class StartFromSnapshotDto(
        val bcId: String,
        val snapshotNameOrId: String,
        val devcontainerStartingPoint: StartingPointDto,
        val snapshotWorkspaceRootPath: String,
        val returnImmediately: Boolean,
        val repoUrl: String? = null,
        val source: String,
        val autoBranch: Boolean,
        val conversationAction: ConversationActionDto,
        val startingMessageType: String,
        val conversationHistory: List<ConversationMessageDto>,
        val addInitialMessageToResponses: Boolean,
        val repositoryInfo: RepositoryInfoDto,
        val skills: List<String>,
        val name: String,
        val projectDetails: ProjectDetailsDto,
        val projectMetadata: ProjectMetadataDto,
    )

    /** `aiserver.v1.DevcontainerStartingPoint`: the repository (`url`, and `repo_config` for several), or the environment. */
    @Serializable
    private data class StartingPointDto(val url: String? = null, val repoConfig: RepoConfigDto? = null, val environmentPublicId: String? = null)

    /** `aiserver.v1.EnvironmentRepoConfig {repos[]}`. */
    @Serializable
    private data class RepoConfigDto(val repos: List<RepoEntryDto>)

    /** `aiserver.v1.EnvironmentRepoEntry {repo_url, scm_repo_node_id}`; the desktop sends the node id empty. */
    @Serializable
    private data class RepoEntryDto(val repoUrl: String, val scmRepoNodeId: String = "")

    /** `agent.v1.ConversationAction { user_message_action }`. */
    @Serializable
    private data class ConversationActionDto(val userMessageAction: UserMessageActionDto)

    /** `agent.v1.UserMessageAction {user_message, send_to_interaction_listener}`. */
    @Serializable
    private data class UserMessageActionDto(val userMessage: KickoffMessageDto, val sendToInteractionListener: Boolean)

    /** `agent.v1.UserMessage`, the corner the kickoff fills: the text, its id, the mode, and the simulated-message marks (`Kse`). */
    @Serializable
    private data class KickoffMessageDto(
        val text: String,
        val messageId: String,
        val mode: String,
        val isSimulatedMsg: Boolean,
        val simulatedMsgReason: String,
        val simulatedMessageMetadata: EmptyDto,
    )

    /** `aiserver.v1.ConversationMessage` as `A1n` puts the kickoff in the history. */
    @Serializable
    private data class ConversationMessageDto(val text: String, val richText: String, val type: String, val pastChats: List<String> = emptyList(), val pastChatsExplicitlySet: Boolean)

    /** `aiserver.v1.HeadlessAgenticComposerRepositoryInfo {path_encryption_key: "", should_sync_index: false}`: both the proto's defaults, so the message goes out empty, as the desktop's does. */
    @Serializable
    private class RepositoryInfoDto

    /** `agent.v1.ProjectDetails {name}`. */
    @Serializable
    private data class ProjectDetailsDto(val name: String)

    /** `aiserver.v1.ProjectMetadata {appearance}`. */
    @Serializable
    private data class ProjectMetadataDto(val appearance: BackgroundComposerApi.ProjectAppearanceDto)

    @Serializable
    private data class ComposerResponseDto(val composer: BackgroundComposerApi.ComposerDto? = null)

    @Serializable
    private data class RenameDto(val bcId: String, val newName: String)

    @Serializable
    private data class RenameResponseDto(val name: String? = null)

    @Serializable
    private data class ListEnvironmentsDto(val includeEnvironmentJson: Boolean, val repositoryScopeRepoUrls: List<String>)

    @Serializable
    private data class ListEnvironmentsResponseDto(val environments: List<EnvironmentDto> = emptyList())

    /** `aiserver.v1.LogicalEnvironment`, the fields the no-repo search reads (`findReusableNoRepoAutomationEnvironment`). */
    @Serializable
    private data class EnvironmentDto(
        val publicId: String? = null,
        val scope: JsonPrimitive? = null,
        val repoConfig: RepoConfigDto? = null,
        val environmentJson: String? = null,
    ) {
        /** `scope === PERSONAL && hasNoRepoConfigIdentity(repoConfig) && hasBlankEnvironmentJson(env)`. */
        val isPersonalNoRepo: Boolean
            get() {
                val personal = scope?.contentOrNull.let { it == SCOPE_PERSONAL || it == "1" }
                val noRepo = repoConfig != null && repoConfig.repos.isEmpty()
                val blank = environmentJson.isNullOrBlank() || environmentJson.trim() == "{}"
                return personal && noRepo && blank
            }
    }

    @Serializable
    private data class SetPersonalEnvironmentDto(val environmentJson: String, val repoUrl: String, val writeSource: String, val repoConfig: RepoConfigDto)

    @Serializable
    private data class SetPersonalEnvironmentResponseDto(val environment: EnvironmentDto? = null)

    @Serializable
    private class EmptyDto

    companion object {
        /** The desktop's kickoff (`new-project-kickoff.js`): the one message a fresh Project is started with. */
        const val KICKOFF_TEXT = "Start this Project."
        /** `SimulatedMsgReason.PROJECT_KICKOFF` (32): how the kickoff is marked, so the coordinator reads it as the Project's start. */
        const val KICKOFF_REASON = "SIMULATED_MSG_REASON_PROJECT_KICKOFF"
        /** What the dialog names a Project when the name is left blank. */
        const val DEFAULT_NAME = "New Project"
        const val WORKSPACE_ROOT = "/workspace"
        /** `NpS(publicId)`: the snapshot name of an environment-backed start. */
        const val ENVIRONMENT_SNAPSHOT_PREFIX = "env|"
        /** The kickoff's mode: the dialog submits with no unified mode, which the desktop reads as `AGENT` (`X0e(undefined)`). */
        const val MODE_AGENT = "AGENT_MODE_AGENT"
        const val STARTING_USER_MESSAGE = "STARTING_MESSAGE_TYPE_USER_MESSAGE"
        const val MESSAGE_HUMAN = "MESSAGE_TYPE_HUMAN"
        const val WRITE_SOURCE_DASHBOARD = "ENVIRONMENT_WRITE_SOURCE_DASHBOARD"
        const val SCOPE_PERSONAL = "LOGICAL_ENVIRONMENT_SCOPE_PERSONAL"
        /** What this app is to the account service: a client on the API, like the SDK's chats (the desktop sends `GLASS`). */
        val SOURCE = AgentSource.API.wireName

        /**
         * The desktop's `Hg`: `host/owner/repo`, lowercased, without the scheme, credentials, a trailing slash or
         * `.git` — what it names the snapshot after.
         */
        fun snapshotName(url: String): String {
            val canonical = canonicalUrl(url)
            if (canonical.isEmpty()) return ""
            return canonical.substringAfter("://").lowercase()
        }

        /**
         * The desktop's `wF`: the repository as `https://host/owner/repo` — the scheme kept when it is http(s), the
         * host lowercased, the path without a trailing slash or `.git`; an `ssh` or `git@` spelling is read the same.
         * Empty when the text names no repository.
         */
        fun canonicalUrl(url: String): String {
            val text = url.trim()
            if (text.isEmpty()) return ""
            val withScheme = when {
                text.startsWith("git@") -> "ssh://" + text.removePrefix("git@").replaceFirst(':', '/')
                Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text) -> text
                else -> "https://$text"
            }
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return ""
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ""
            val path = uri.path.orEmpty().trimEnd('/').removeSuffix(".git").removeSuffix(".GIT")
            if (path.isEmpty() || path == "/") return ""
            val scheme = if (uri.scheme.equals("http", ignoreCase = true)) "http" else "https"
            return "$scheme://$host$path"
        }
    }
}
