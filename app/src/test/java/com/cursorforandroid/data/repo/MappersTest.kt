package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0SourceDto
import com.cursorforandroid.data.api.dto.V0TargetDto
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Reconciling a remembered row (possibly hours old, from disk) with what the list endpoints say now. */
class MappersTest {

    private fun summary(id: String = "bc-1", status: String = "IDLE", latestRunId: String? = "run-1", name: String? = "Agent") =
        AgentSummaryDto(id = id, name = name, status = status, createdAt = "2026-04-13T18:30:00.000Z", updatedAt = "2026-04-13T19:00:00.000Z", latestRunId = latestRunId)

    private fun previous(runStatus: RunStatus?, lifecycle: AgentLifecycle = AgentLifecycle.ACTIVE, latestRunId: String? = "run-1") = Agent(
        id = "bc-1",
        name = "Agent",
        lifecycle = lifecycle,
        runStatus = runStatus,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/bc-1",
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
        latestRunId = latestRunId,
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        branches = listOf(GitBranch("https://github.com/acme/app", "cursor/x", null)),
        summary = "Remembered",
        modelDisplayName = "Claude",
        durationMs = 9_000,
    )

    @Test
    fun `a remembered active status cannot outlive an idle lifecycle`() {
        val row = summary(status = "IDLE").toAgent(previous(RunStatus.RUNNING))
        assertThat(row.runStatus).isNull()
        assertThat(row.isRunning).isFalse()
        // Enrichment only richer sources knew about is carried over regardless.
        assertThat(row.repoUrl).isEqualTo("https://github.com/acme/app")
        assertThat(row.branches.single().branch).isEqualTo("cursor/x")
        assertThat(row.summary).isEqualTo("Remembered")
        assertThat(row.modelDisplayName).isEqualTo("Claude")
        assertThat(row.durationMs).isEqualTo(9_000)
    }

    @Test
    fun `the same run's status is kept while the lifecycle agrees`() {
        assertThat(summary(status = "ACTIVE").toAgent(previous(RunStatus.RUNNING)).runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(summary(status = "IDLE").toAgent(previous(RunStatus.ERROR)).runStatus).isEqualTo(RunStatus.ERROR)
        assertThat(summary(status = "IDLE").toAgent(previous(RunStatus.ERROR)).isError).isTrue()
    }

    @Test
    fun `a new run started elsewhere resets the remembered status and shows as running via the lifecycle`() {
        val row = summary(status = "ACTIVE", latestRunId = "run-2").toAgent(previous(RunStatus.FINISHED))
        assertThat(row.runStatus).isNull()
        assertThat(row.latestRunId).isEqualTo("run-2")
        assertThat(row.isRunning).isTrue()
    }

    @Test
    fun `the legacy record fills the gaps but its active status yields to an idle lifecycle`() {
        val v0 = V0AgentDto(
            id = "bc-1",
            name = "Legacy name",
            status = "RUNNING",
            source = V0SourceDto("https://github.com/acme/app", "develop"),
            target = V0TargetDto(branchName = "cursor/y", prUrl = "https://github.com/acme/app/pull/1", autoCreatePr = true),
            summary = "Legacy summary",
        )
        val idle = summary(status = "IDLE", name = null).toAgent(null).withLegacy(v0)
        assertThat(idle.name).isEqualTo("Legacy name")
        assertThat(idle.runStatus).isNull()
        assertThat(idle.isRunning).isFalse()
        assertThat(idle.repoUrl).isEqualTo("https://github.com/acme/app")
        assertThat(idle.startingRef).isEqualTo("develop")
        assertThat(idle.branchName).isEqualTo("cursor/y")
        assertThat(idle.prUrl).isEqualTo("https://github.com/acme/app/pull/1")
        assertThat(idle.summary).isEqualTo("Legacy summary")
        assertThat(idle.autoCreatePr).isTrue()

        val active = summary(status = "ACTIVE").toAgent(null).withLegacy(v0)
        assertThat(active.name).isEqualTo("Agent")
        assertThat(active.runStatus).isEqualTo(RunStatus.RUNNING)

        // Branches learned from a run (richer: several repos, live PR) beat the legacy single branch.
        val enriched = summary(status = "IDLE").toAgent(previous(RunStatus.FINISHED)).withLegacy(v0)
        assertThat(enriched.branchName).isEqualTo("cursor/x")
        assertThat(enriched.summary).isEqualTo("Legacy summary")
    }

    @Test
    fun `the combined mapper matches applying both steps`() {
        val v0 = V0AgentDto(id = "bc-1", status = "FINISHED", summary = "S")
        val combined = summary().toAgent(v0, previous(RunStatus.RUNNING))
        val stepwise = summary().toAgent(previous(RunStatus.RUNNING)).withLegacy(v0)
        assertThat(combined).isEqualTo(stepwise)
        assertThat(combined.runStatus).isEqualTo(RunStatus.FINISHED)
    }

    @Test
    fun `the full record without its run applies the same lifecycle guard`() {
        val dto = AgentDto(id = "bc-1", name = "Agent", status = "IDLE", createdAt = "2026-04-13T18:30:00.000Z", updatedAt = "2026-04-13T19:00:00.000Z", latestRunId = "run-1")
        assertThat(dto.mergeInto(previous(RunStatus.RUNNING), latestRun = null).isRunning).isFalse()
        assertThat(dto.copy(status = "ACTIVE").mergeInto(previous(RunStatus.RUNNING), latestRun = null).isRunning).isTrue()
    }
}
