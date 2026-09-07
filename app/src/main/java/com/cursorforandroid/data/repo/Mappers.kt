package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.api.dto.ModelListItemDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
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
        )
    },
)

/** Builds the list-row model from the v1 summary, optionally enriched with the legacy v0 record. */
fun AgentSummaryDto.toAgent(v0: V0AgentDto?, previous: Agent?): Agent {
    val lifecycle = AgentLifecycle.parse(status)
    val updatedAtMillis = parseIsoMillis(updatedAt, parseIsoMillis(createdAt))
    val v0Status = v0?.status?.let { RunStatus.parse(it) }?.takeIf { it != RunStatus.UNKNOWN }
    val v0Branch = v0?.target?.branchName
    val v0Pr = v0?.target?.prUrl
    val v0Repo = v0?.source?.repository
    return Agent(
        id = id,
        name = name?.ifBlank { null } ?: v0?.name?.ifBlank { null } ?: "Untitled agent",
        lifecycle = lifecycle,
        runStatus = resolveListRunStatus(lifecycle, v0Status, previous, updatedAtMillis),
        envType = EnvType.parse(env.type),
        envName = env.name,
        url = url.ifBlank { "https://cursor.com/agents/$id" },
        createdAtMillis = parseIsoMillis(createdAt, parseIsoMillis(v0?.createdAt)),
        updatedAtMillis = maxOf(updatedAtMillis, previous?.updatedAtMillis ?: 0L),
        latestRunId = latestRunId ?: previous?.latestRunId,
        repoUrl = v0Repo ?: previous?.repoUrl,
        startingRef = v0?.source?.ref ?: previous?.startingRef,
        branches = mergeBranches(v0Repo, v0Branch, v0Pr, previous?.branches.orEmpty()),
        summary = v0?.summary ?: previous?.summary,
        autoCreatePr = v0?.target?.autoCreatePr ?: previous?.autoCreatePr,
        workOnCurrentBranch = previous?.workOnCurrentBranch,
        modelDisplayName = previous?.modelDisplayName,
        durationMs = previous?.durationMs,
    )
}

/**
 * Merges list-refresh run status without letting a lagging v0 FINISHED clobber a live follow-up, and
 * without letting a stale local RUNNING survive after v1 has already gone idle.
 */
internal fun resolveListRunStatus(
    lifecycle: AgentLifecycle,
    v0Status: RunStatus?,
    previous: Agent?,
    summaryUpdatedAtMillis: Long,
): RunStatus? {
    val previousStatus = previous?.runStatus
    if (lifecycle == AgentLifecycle.ACTIVE) {
        return when {
            v0Status?.isActive == true -> v0Status
            previousStatus?.isActive == true -> previousStatus
            v0Status != null && !v0Status.isTerminal -> v0Status
            else -> previousStatus?.takeIf { it.isActive } ?: RunStatus.RUNNING
        }
    }
    // Live hub / follow-up may have marked the row running more recently than this summary.
    if (previousStatus?.isActive == true && (previous?.updatedAtMillis ?: 0L) > summaryUpdatedAtMillis) {
        return previousStatus
    }
    return v0Status ?: previousStatus
}

/** Prefers fresh v0 branch/PR fields, keeps prior PR/branch when the legacy payload briefly omits them. */
internal fun mergeBranches(
    v0Repo: String?,
    v0Branch: String?,
    v0Pr: String?,
    previous: List<GitBranch>,
): List<GitBranch> {
    if (v0Branch == null && v0Pr == null) return previous
    val prior = previous.firstOrNull()
    return listOf(
        GitBranch(
            repoUrl = v0Repo?.takeIf { it.isNotBlank() } ?: prior?.repoUrl.orEmpty(),
            branch = v0Branch ?: prior?.branch,
            prUrl = v0Pr ?: prior?.prUrl,
        ),
    )
}

/** Merges the full `GET /v1/agents/{id}` record and its latest run into an existing list row. */
fun AgentDto.mergeInto(previous: Agent?, latestRun: RunDto?): Agent {
    val repo = repos.firstOrNull()
    val runBranches = latestRun?.git.toBranches()
    return Agent(
        id = id,
        name = name?.ifBlank { null } ?: previous?.name ?: "Untitled agent",
        lifecycle = AgentLifecycle.parse(status),
        runStatus = latestRun?.let { RunStatus.parse(it.status) } ?: previous?.runStatus,
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
