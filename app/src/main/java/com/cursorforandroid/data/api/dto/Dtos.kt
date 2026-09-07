package com.cursorforandroid.data.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---- v1 ---------------------------------------------------------------------------------------------------

@Serializable
data class AgentEnvDto(
    val type: String = "cloud",
    val name: String? = null,
)

@Serializable
data class RepoConfigDto(
    val url: String,
    val startingRef: String? = null,
    val prUrl: String? = null,
)

@Serializable
data class AgentSummaryDto(
    val id: String,
    val name: String? = null,
    val status: String = "ACTIVE",
    val env: AgentEnvDto = AgentEnvDto(),
    val url: String = "",
    val createdAt: String,
    val updatedAt: String,
    val latestRunId: String? = null,
)

@Serializable
data class AgentDto(
    val id: String,
    val name: String? = null,
    val status: String = "ACTIVE",
    val env: AgentEnvDto = AgentEnvDto(),
    val url: String = "",
    val createdAt: String,
    val updatedAt: String,
    val latestRunId: String? = null,
    val repos: List<RepoConfigDto> = emptyList(),
    val workOnCurrentBranch: Boolean? = null,
    val autoCreatePR: Boolean? = null,
    val skipReviewerRequest: Boolean? = null,
)

@Serializable
data class ListAgentsResponseDto(
    val items: List<AgentSummaryDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class RunGitBranchDto(
    val repoUrl: String,
    val branch: String? = null,
    val prUrl: String? = null,
)

@Serializable
data class RunGitDto(val branches: List<RunGitBranchDto> = emptyList())

@Serializable
data class RunDto(
    val id: String,
    val agentId: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val durationMs: Long? = null,
    val result: String? = null,
    val git: RunGitDto? = null,
)

@Serializable
data class ListRunsResponseDto(
    val items: List<RunDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ImageDto(
    val data: String? = null,
    val mimeType: String? = null,
    val url: String? = null,
)

@Serializable
data class PromptDto(
    val text: String,
    val images: List<ImageDto>? = null,
)

@Serializable
data class ModelParamDto(val id: String, val value: String)

@Serializable
data class ModelRefDto(
    val id: String,
    val params: List<ModelParamDto>? = null,
)

/**
 * Inline MCP server definition (`mcpServers[]`). Remote servers carry `url` (+ `headers`); stdio servers carry
 * `command` (+ `args`, `env`) and start inside the cloud VM.
 */
@Serializable
data class McpServerDto(
    val name: String,
    val type: String? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
)

@Serializable
data class CreateAgentRequestDto(
    val prompt: PromptDto,
    /**
     * Client-minted `bc-<uuid>`. Re-posting it returns `409 agent_id_conflict` instead of a second agent, which
     * is what makes retrying a launch whose reply never arrived safe.
     */
    val agentId: String? = null,
    val model: ModelRefDto? = null,
    val name: String? = null,
    val env: AgentEnvDto? = null,
    val repos: List<RepoConfigDto>? = null,
    val workOnCurrentBranch: Boolean? = null,
    val autoCreatePR: Boolean? = null,
    val skipReviewerRequest: Boolean? = null,
    val mcpServers: List<McpServerDto>? = null,
    val mode: String? = null,
)

@Serializable
data class CreateAgentResponseDto(
    val agent: AgentDto,
    val run: RunDto,
)

@Serializable
data class CreateRunRequestDto(
    val prompt: PromptDto,
    /** Replaces the agent's create-time inline servers for this run; omitted, the agent keeps its configuration. */
    val mcpServers: List<McpServerDto>? = null,
    /**
     * Switches the agent to this model, for this run and the ones after it (the SDK's `agent.send({ model })`:
     * the override is sticky); omitted, the agent keeps the model it has been running on.
     */
    val model: ModelRefDto? = null,
    val mode: String? = null,
)

@Serializable
data class CreateRunResponseDto(val run: RunDto)

@Serializable
data class IdResponseDto(val id: String)

@Serializable
data class ApiKeyInfoDto(
    val apiKeyName: String = "API key",
    val createdAt: String? = null,
    val userId: Long? = null,
    val userEmail: String? = null,
    val userFirstName: String? = null,
    val userLastName: String? = null,
)

@Serializable
data class ModelParameterValueDto(val value: String, val displayName: String? = null)

@Serializable
data class ModelParameterDefinitionDto(
    val id: String,
    val displayName: String? = null,
    val values: List<ModelParameterValueDto> = emptyList(),
)

@Serializable
data class ModelVariantDto(
    val params: List<ModelParamDto> = emptyList(),
    val displayName: String,
    val description: String? = null,
    val isDefault: Boolean? = null,
)

@Serializable
data class ModelListItemDto(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
    val aliases: List<String>? = null,
    val parameters: List<ModelParameterDefinitionDto>? = null,
    val variants: List<ModelVariantDto>? = null,
)

@Serializable
data class ListModelsResponseDto(val items: List<ModelListItemDto> = emptyList())

@Serializable
data class RepositoryDto(val url: String)

@Serializable
data class ListRepositoriesResponseDto(val items: List<RepositoryDto> = emptyList())

@Serializable
data class UsageTokensDto(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0,
)

@Serializable
data class RunUsageDto(val id: String, val usage: UsageTokensDto = UsageTokensDto())

@Serializable
data class AgentUsageResponseDto(
    val totalUsage: UsageTokensDto = UsageTokensDto(),
    val runs: List<RunUsageDto> = emptyList(),
)

@Serializable
data class ArtifactDto(val path: String, val sizeBytes: Long = 0, val updatedAt: String? = null)

@Serializable
data class ListArtifactsResponseDto(val items: List<ArtifactDto> = emptyList())

@Serializable
data class DownloadArtifactResponseDto(val url: String, val expiresAt: String? = null)

@Serializable
data class ApiErrorBodyDto(val error: ApiErrorDto? = null)

@Serializable
data class ApiErrorDto(
    val code: String = "unknown",
    val message: String = "",
    val helpUrl: String? = null,
)

// ---- SSE payloads ------------------------------------------------------------------------------------------

@Serializable
data class SseStatusDto(val runId: String? = null, val status: String)

@Serializable
data class SseTextDto(val text: String = "")

@Serializable
data class SseToolCallDto(
    val callId: String,
    val name: String,
    val status: String,
    val args: JsonElement? = null,
    val result: JsonElement? = null,
)

@Serializable
data class SseResultDto(
    val runId: String? = null,
    val status: String,
    val text: String? = null,
    val durationMs: Long? = null,
    val git: RunGitDto? = null,
)

@Serializable
data class SseErrorDto(val code: String = "stream_error", val message: String = "")

// ---- v0 (legacy, still served) -----------------------------------------------------------------------------

@Serializable
data class V0SourceDto(val repository: String? = null, val ref: String? = null)

@Serializable
data class V0TargetDto(
    val branchName: String? = null,
    val url: String? = null,
    val prUrl: String? = null,
    val autoCreatePr: Boolean? = null,
)

@Serializable
data class V0AgentDto(
    val id: String,
    val name: String? = null,
    val status: String? = null,
    val source: V0SourceDto? = null,
    val target: V0TargetDto? = null,
    val summary: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class V0ListAgentsResponseDto(
    val agents: List<V0AgentDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class V0ConversationMessageDto(
    val id: String,
    val type: String,
    val text: String = "",
)

@Serializable
data class V0ConversationResponseDto(
    val id: String,
    val messages: List<V0ConversationMessageDto> = emptyList(),
)
