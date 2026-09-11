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
 * The run status a row keeps when a record arrives without run-level state (the v1 list, or the detail without its
 * run). Runs only move forward, so a status remembered for the run the record names still describes it: a finished
 * run stays finished however long the lifecycle reads `ACTIVE` (it always does, see [AgentLifecycle]), and a running
 * one keeps running until a run-level source — the legacy list, the record, its stream — says how it ended (the
 * repository verifies every active row against its record after a refresh, so a stale cache cannot spin for long). A
 * record that names a different run is a turn the row has not seen; its state is unknown until one of those sources
 * reports it. A lifecycle that rules out running (`IDLE`, `ARCHIVED`) ends an active status at once, and how the
 * turn ended is left to those sources too. When either side does not name its run, activity newer than the row
 * knows is taken as a new turn.
 */
private fun carriedRunStatus(previous: Agent?, lifecycle: AgentLifecycle, latestRunId: String?, updatedAtMillis: Long): RunStatus? {
    val remembered = previous?.runStatus ?: return null
    if (remembered.isActive && lifecycle.excludesRunning) return null
    val previousRunId = previous.latestRunId
    return when {
        latestRunId != null && previousRunId != null -> remembered.takeIf { latestRunId == previousRunId }
        remembered.isTerminal && updatedAtMillis > previous.updatedAtMillis -> null
        else -> remembered
    }
}

/**
 * The row's activity time once the server has spoken: the server's `updatedAt`, which advances while a turn works
 * and goes quiet when it is over. A value this device stamped itself — a follow-up sent, a finish watched — stays
 * only while it is barely ahead of the server (a list answer that has not caught up with it yet, or clock skew),
 * never when it is hours or days ahead: that is what a row wrongly marked "updated just now" looks like, and the
 * next refresh must put it back where it belongs rather than carry the mistake forward for good.
 */
internal fun reconcileUpdatedAt(serverMillis: Long, previousMillis: Long?): Long {
    if (serverMillis <= 0L) return previousMillis ?: 0L
    if (previousMillis == null || previousMillis <= serverMillis) return serverMillis
    return if (previousMillis - serverMillis <= LOCAL_ACTIVITY_GRACE_MS) previousMillis else serverMillis
}

/** How far ahead of the server a locally stamped activity time may run before the server's value replaces it. */
internal const val LOCAL_ACTIVITY_GRACE_MS = 5 * 60_000L

/**
 * Builds the list-row model from the v1 summary alone, carrying over what only richer sources knew (repo, branch,
 * summary, model, duration, where the chat was started, its place among Projects) from the row it replaces — which
 * may have been restored from disk and be hours old.
 * The remembered run status is subject to [carriedRunStatus]; the activity time to [reconcileUpdatedAt].
 */
fun AgentSummaryDto.toAgent(previous: Agent?): Agent {
    val lifecycle = AgentLifecycle.parse(status)
    val runId = latestRunId ?: previous?.latestRunId
    val serverUpdatedAt = parseIsoMillis(updatedAt, parseIsoMillis(createdAt))
    return Agent(
        id = id,
        name = name?.ifBlank { null } ?: previous?.name?.takeIf { it != UNTITLED } ?: UNTITLED,
        lifecycle = lifecycle,
        runStatus = carriedRunStatus(previous, lifecycle, latestRunId, serverUpdatedAt),
        envType = EnvType.parse(env.type),
        envName = env.name,
        url = url.ifBlank { "https://cursor.com/agents/$id" },
        createdAtMillis = parseIsoMillis(createdAt, previous?.createdAtMillis ?: 0L),
        updatedAtMillis = reconcileUpdatedAt(serverUpdatedAt, previous?.updatedAtMillis),
        latestRunId = runId,
        repoUrl = previous?.repoUrl,
        startingRef = previous?.startingRef,
        branches = previous?.branches ?: emptyList(),
        summary = previous?.summary,
        autoCreatePr = previous?.autoCreatePr,
        workOnCurrentBranch = previous?.workOnCurrentBranch,
        modelDisplayName = previous?.modelDisplayName,
        modelId = previous?.modelId,
        modelParams = previous?.modelParams.orEmpty(),
        durationMs = previous?.durationMs,
        source = previous?.source,
        isProject = previous?.isProject ?: false,
        projectAppearance = previous?.projectAppearance,
        parent = previous?.parent,
        knownScope = previous?.knownScope,
    )
}

/**
 * Folds the legacy `GET /v0/agents` record (repo, branch, PR, summary, agent-level status) into a v1 row. The v0
 * status is the agent's execution state — one value per agent, for its current turn, in a run's vocabulary — and
 * the only source of it the list can afford for every row at once. It fills in a status the row does not know from a
 * richer source (the run itself, or its stream), and the v1 lifecycle does not get to overrule it: `ACTIVE` is what
 * v1 says about finished agents too, so a terminal v0 status must land whatever the lifecycle reads — refusing it is
 * what left finished chats without a status and, from there, "running". The one veto the lifecycle keeps is against
 * an active v0 status for an agent v1 reports idle or archived. A status the row already holds for its current run
 * stands: a terminal one came from a run-level source the v0 list cannot contradict, and an active one is settled
 * by the repository against the run record rather than flipped by a list that may lag it.
 */
fun Agent.withLegacy(v0: V0AgentDto): Agent {
    val v0Branch = v0.target?.branchName
    val v0Pr = v0.target?.prUrl
    val v0Repo = v0.source?.repository
    val v0Status = v0.status?.let { RunStatus.parse(it) }
        ?.takeIf { it != RunStatus.UNKNOWN }
        ?.takeIf { !(it.isActive && lifecycle.excludesRunning) }
    return copy(
        name = if (name == UNTITLED) v0.name?.ifBlank { null } ?: name else name,
        runStatus = runStatus?.takeIf { it != RunStatus.UNKNOWN } ?: v0Status,
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

/**
 * Merges the full `GET /v1/agents/{id}` record and its latest run into an existing list row. The run is the
 * authority on execution state; without it the remembered status is subject to [carriedRunStatus].
 */
fun AgentDto.mergeInto(previous: Agent?, latestRun: RunDto?): Agent {
    val repo = repos.firstOrNull()
    val runBranches = latestRun?.git.toBranches()
    val lifecycle = AgentLifecycle.parse(status)
    val runId = latestRunId ?: latestRun?.id
    val serverUpdatedAt = maxOf(parseIsoMillis(updatedAt), latestRun?.let { parseIsoMillis(it.updatedAt) } ?: 0L)
    return Agent(
        id = id,
        name = name?.ifBlank { null } ?: previous?.name ?: UNTITLED,
        lifecycle = lifecycle,
        runStatus = latestRun?.statusEnum() ?: carriedRunStatus(previous, lifecycle, runId, serverUpdatedAt),
        envType = EnvType.parse(env.type),
        envName = env.name,
        url = url.ifBlank { previous?.url ?: "https://cursor.com/agents/$id" },
        createdAtMillis = parseIsoMillis(createdAt, previous?.createdAtMillis ?: 0L),
        updatedAtMillis = reconcileUpdatedAt(serverUpdatedAt, previous?.updatedAtMillis),
        latestRunId = runId ?: previous?.latestRunId,
        repoUrl = repo?.url ?: previous?.repoUrl,
        startingRef = repo?.startingRef ?: previous?.startingRef,
        branches = if (runBranches.isNotEmpty()) runBranches else previous?.branches ?: emptyList(),
        summary = latestRun?.result?.takeIf { it.isNotBlank() } ?: previous?.summary,
        autoCreatePr = autoCreatePR ?: previous?.autoCreatePr,
        workOnCurrentBranch = workOnCurrentBranch ?: previous?.workOnCurrentBranch,
        modelDisplayName = previous?.modelDisplayName,
        modelId = previous?.modelId,
        modelParams = previous?.modelParams.orEmpty(),
        durationMs = latestRun?.durationMs ?: previous?.durationMs,
        source = previous?.source,
        isProject = previous?.isProject ?: false,
        projectAppearance = previous?.projectAppearance,
        parent = previous?.parent,
        knownScope = previous?.knownScope,
    )
}

fun RunDto.statusEnum(): RunStatus = RunStatus.parse(status)

/**
 * Folds a run the caller has just read from the server into the row, when it is the row's latest run (or the row
 * does not know its latest run yet). Runs are the authority on execution state, so the status is taken as is —
 * this is how a row learns that a turn ended in an error, which the v1 list reports as a plain idle lifecycle — and
 * the terminal fields (branches, final reply, duration) fill in what the list never carries. A run that is no
 * longer the row's latest changes nothing.
 */
fun Agent.withLatestRun(run: RunDto): Agent {
    if (latestRunId != null && latestRunId != run.id) return this
    return copy(
        runStatus = run.statusEnum(),
        latestRunId = run.id,
        branches = run.git.toBranches().ifEmpty { branches },
        summary = run.result?.takeIf { it.isNotBlank() } ?: summary,
        durationMs = run.durationMs ?: durationMs,
    )
}

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
