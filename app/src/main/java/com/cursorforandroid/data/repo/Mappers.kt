package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.api.dto.McpServerDto
import com.cursorforandroid.data.api.dto.ModelListItemDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpTransport
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelParameter
import com.cursorforandroid.domain.ModelParameterValue
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.RunStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

fun parseIsoMillis(raw: String?, fallback: Long = 0L): Long {
    if (raw.isNullOrBlank()) return fallback
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            fallback
        }
    }
}

fun RunGitDto?.toBranches(): List<GitBranch> = this?.branches?.map { GitBranch(it.repoUrl, it.branch, it.prUrl) } ?: emptyList()

fun ApiKeyInfoDto.toUser(): CursorUser = CursorUser(
    apiKeyName = apiKeyName,
    email = userEmail,
    firstName = userFirstName,
    lastName = userLastName,
    userId = userId,
)

fun ModelListItemDto.toModel(): ModelOption = ModelOption(
    id = id,
    displayName = displayName ?: id,
    description = description,
    variants = variants.orEmpty().map { v ->
        ModelVariant(
            displayName = v.displayName,
            params = v.params.map { ModelParam(it.id, it.value) },
            isDefault = v.isDefault == true,
            description = v.description,
        )
    },
    parameters = parameters.orEmpty().map { p ->
        ModelParameter(id = p.id, displayName = p.displayName, values = p.values.map { ModelParameterValue(it.value, it.displayName) })
    },
)

/** Builds the list-row model from the v1 summary, optionally enriched with the legacy v0 record. */
fun AgentSummaryDto.toAgent(v0: V0AgentDto?, previous: Agent?): Agent =
    toAgent(previous).let { if (v0 != null) it.withLegacy(v0) else it }

/**
 * Builds the list-row model from the v1 summary alone, carrying over what only richer sources knew (repo, branch,
 * summary, model, duration) from the row it replaces — which may have been restored from disk and be hours old.
 * The v1 lifecycle is authoritative for whether anything is running: a remembered run status only survives when it
 * still describes the same run and does not contradict the lifecycle, so a row cached as RUNNING cannot keep
 * spinning after the agent went idle, and a new run started elsewhere shows as running through the lifecycle.
 */
fun AgentSummaryDto.toAgent(previous: Agent?): Agent {
    val lifecycle = AgentLifecycle.parse(status)
    val runId = latestRunId ?: previous?.latestRunId
    val sameRun = previous != null && (latestRunId == null || previous.latestRunId == null || latestRunId == previous.latestRunId)
    val carried = previous?.runStatus?.takeIf { sameRun && !(it.isActive && lifecycle != AgentLifecycle.ACTIVE) }
    return Agent(
        id = id,
        name = name?.ifBlank { null } ?: previous?.name?.takeIf { it != UNTITLED } ?: UNTITLED,
        lifecycle = lifecycle,
        runStatus = carried,
        envType = EnvType.parse(env.type),
        envName = env.name,
        url = url.ifBlank { "https://cursor.com/agents/$id" },
        createdAtMillis = parseIsoMillis(createdAt, previous?.createdAtMillis ?: 0L),
        updatedAtMillis = maxOf(parseIsoMillis(updatedAt, parseIsoMillis(createdAt)), previous?.updatedAtMillis?.takeIf { sameRun } ?: 0L),
        latestRunId = runId,
        repoUrl = previous?.repoUrl,
        startingRef = previous?.startingRef,
        branches = previous?.branches ?: emptyList(),
        summary = previous?.summary,
        autoCreatePr = previous?.autoCreatePr,
        workOnCurrentBranch = previous?.workOnCurrentBranch,
        modelDisplayName = previous?.modelDisplayName,
        durationMs = previous?.durationMs,
    )
}

/**
 * Folds the legacy `GET /v0/agents` record (repo, branch, PR, summary, per-run status) into a v1 row. The v0 list
 * is fetched independently and may be older than the v1 lifecycle, so an active v0 status is ignored for an agent
 * v1 already reports as idle or archived.
 */
fun Agent.withLegacy(v0: V0AgentDto): Agent {
    val v0Branch = v0.target?.branchName
    val v0Pr = v0.target?.prUrl
    val v0Repo = v0.source?.repository
    val v0Status = v0.status?.let { RunStatus.parse(it) }
        ?.takeIf { it != RunStatus.UNKNOWN }
        ?.takeIf { !(it.isActive && lifecycle != AgentLifecycle.ACTIVE) }
    return copy(
        name = if (name == UNTITLED) v0.name?.ifBlank { null } ?: name else name,
        runStatus = v0Status ?: runStatus,
        createdAtMillis = if (createdAtMillis == 0L) parseIsoMillis(v0.createdAt) else createdAtMillis,
        repoUrl = v0Repo ?: repoUrl,
        startingRef = v0.source?.ref ?: startingRef,
        branches = when {
            branches.isNotEmpty() -> branches
            v0Branch != null || v0Pr != null -> listOf(GitBranch(v0Repo ?: "", v0Branch, v0Pr))
            else -> emptyList()
        },
        summary = v0.summary ?: summary,
        autoCreatePr = v0.target?.autoCreatePr ?: autoCreatePr,
    )
}

private const val UNTITLED = "Untitled agent"

/** Merges the full `GET /v1/agents/{id}` record and its latest run into an existing list row. */
fun AgentDto.mergeInto(previous: Agent?, latestRun: RunDto?): Agent {
    val repo = repos.firstOrNull()
    val runBranches = latestRun?.git.toBranches()
    val lifecycle = AgentLifecycle.parse(status)
    return Agent(
        id = id,
        name = name?.ifBlank { null } ?: previous?.name ?: UNTITLED,
        lifecycle = lifecycle,
        runStatus = latestRun?.let { RunStatus.parse(it.status) }
            ?: previous?.runStatus?.takeIf { !(it.isActive && lifecycle != AgentLifecycle.ACTIVE) },
        envType = EnvType.parse(env.type),
        envName = env.name,
        url = url.ifBlank { previous?.url ?: "https://cursor.com/agents/$id" },
        createdAtMillis = parseIsoMillis(createdAt, previous?.createdAtMillis ?: 0L),
        updatedAtMillis = maxOf(parseIsoMillis(updatedAt), latestRun?.let { parseIsoMillis(it.updatedAt) } ?: 0L, previous?.updatedAtMillis ?: 0L),
        latestRunId = latestRunId ?: latestRun?.id ?: previous?.latestRunId,
        repoUrl = repo?.url ?: previous?.repoUrl,
        startingRef = repo?.startingRef ?: previous?.startingRef,
        branches = if (runBranches.isNotEmpty()) runBranches else previous?.branches ?: emptyList(),
        summary = latestRun?.result?.takeIf { it.isNotBlank() } ?: previous?.summary,
        autoCreatePr = autoCreatePR ?: previous?.autoCreatePr,
        workOnCurrentBranch = workOnCurrentBranch ?: previous?.workOnCurrentBranch,
        modelDisplayName = previous?.modelDisplayName,
        durationMs = latestRun?.durationMs ?: previous?.durationMs,
    )
}

fun RunDto.statusEnum(): RunStatus = RunStatus.parse(status)

/** The inline `mcpServers[]` entry: only the fields of the server's transport, empty maps and lists omitted. */
fun McpServer.toDto(): McpServerDto = when (transport) {
    McpTransport.Http -> McpServerDto(
        name = name.trim(),
        type = transport.wire,
        url = url.trim(),
        headers = headers.takeIf { it.isNotEmpty() },
    )
    McpTransport.Stdio -> McpServerDto(
        name = name.trim(),
        type = transport.wire,
        command = command.trim(),
        args = args.takeIf { it.isNotEmpty() },
        env = env.takeIf { it.isNotEmpty() },
    )
}

/** Enabled servers as the request field, or null so the field is omitted and the agent keeps its configuration. */
fun List<McpServer>.toInlineServers(): List<McpServerDto>? = filter { it.enabled }.map { it.toDto() }.takeIf { it.isNotEmpty() }
