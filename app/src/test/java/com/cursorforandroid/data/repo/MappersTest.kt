package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.AgentEnvDto
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

class MappersTest {

    private fun summary(
        id: String = "a1",
        status: String = "IDLE",
        updatedAt: String = "2026-01-15T12:00:00Z",
        latestRunId: String? = "run-1",
    ) = AgentSummaryDto(
        id = id,
        name = "Agent",
        status = status,
        env = AgentEnvDto(type = "cloud"),
        url = "https://cursor.com/agents/$id",
        createdAt = "2026-01-15T11:00:00Z",
        updatedAt = updatedAt,
        latestRunId = latestRunId,
    )

    private fun v0(
        status: String? = "FINISHED",
        branch: String? = "cursor/x",
        prUrl: String? = "https://github.com/acme/app/pull/1",
        repo: String? = "https://github.com/acme/app",
    ) = V0AgentDto(
        id = "a1",
        name = "Agent",
        status = status,
        source = V0SourceDto(repository = repo, ref = "main"),
        target = V0TargetDto(branchName = branch, prUrl = prUrl),
        summary = "done",
        createdAt = "2026-01-15T11:00:00Z",
    )

    private fun previous(
        runStatus: RunStatus? = RunStatus.FINISHED,
        lifecycle: AgentLifecycle = AgentLifecycle.IDLE,
        updatedAtMillis: Long = 0L,
        branches: List<GitBranch> = listOf(GitBranch("https://github.com/acme/app", "cursor/old", null)),
    ) = Agent(
        id = "a1",
        name = "Agent",
        lifecycle = lifecycle,
        runStatus = runStatus,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/a1",
        createdAtMillis = 0L,
        updatedAtMillis = updatedAtMillis,
        latestRunId = "run-0",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        branches = branches,
    )

    @Test
    fun `active v1 keeps live running when v0 status still says finished`() {
        val prev = previous(runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE, updatedAtMillis = 2_000L)
        val agent = summary(status = "ACTIVE", updatedAt = "2026-01-15T12:00:00Z").toAgent(v0(status = "FINISHED"), prev)
        assertThat(agent.lifecycle).isEqualTo(AgentLifecycle.ACTIVE)
        assertThat(agent.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(agent.isRunning).isTrue()
        assertThat(agent.hasPullRequest).isTrue()
    }

    @Test
    fun `active v1 without prior run still reports running despite finished v0`() {
        val agent = summary(status = "ACTIVE").toAgent(v0(status = "FINISHED"), previous = null)
        assertThat(agent.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(agent.isRunning).isTrue()
    }

    @Test
    fun `idle refresh adopts v0 finished and does not keep stale local running`() {
        val prev = previous(runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE, updatedAtMillis = 1_000L)
        // summary updatedAt is later than previous, so the list wins.
        val agent = summary(status = "IDLE", updatedAt = "2026-01-15T12:00:01Z").toAgent(v0(status = "FINISHED"), prev)
        assertThat(agent.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(agent.isRunning).isFalse()
    }

    @Test
    fun `fresher local running survives an older idle summary`() {
        val summaryMillis = parseIsoMillis("2026-01-15T12:00:00Z")
        val prev = previous(runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE, updatedAtMillis = summaryMillis + 5_000L)
        val agent = summary(status = "IDLE", updatedAt = "2026-01-15T12:00:00Z").toAgent(v0(status = "FINISHED"), prev)
        assertThat(agent.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(agent.isRunning).isTrue()
    }

    @Test
    fun `v0 pull request updates a previous branch-only row`() {
        val prev = previous(branches = listOf(GitBranch("https://github.com/acme/app", "cursor/old", null)))
        val agent = summary().toAgent(v0(branch = "cursor/new", prUrl = "https://github.com/acme/app/pull/9"), prev)
        assertThat(agent.branchName).isEqualTo("cursor/new")
        assertThat(agent.prUrl).isEqualTo("https://github.com/acme/app/pull/9")
    }

    @Test
    fun `missing v0 pr keeps previous pr url`() {
        val prev = previous(branches = listOf(GitBranch("https://github.com/acme/app", "cursor/x", "https://github.com/acme/app/pull/1")))
        val agent = summary().toAgent(v0(branch = "cursor/x", prUrl = null), prev)
        assertThat(agent.prUrl).isEqualTo("https://github.com/acme/app/pull/1")
    }

    @Test
    fun `resolveListRunStatus helpers`() {
        assertThat(resolveListRunStatus(AgentLifecycle.ACTIVE, RunStatus.FINISHED, null, 0L)).isEqualTo(RunStatus.RUNNING)
        assertThat(
            resolveListRunStatus(
                AgentLifecycle.IDLE,
                RunStatus.FINISHED,
                previous(runStatus = RunStatus.RUNNING, updatedAtMillis = 10L),
                summaryUpdatedAtMillis = 5L,
            ),
        ).isEqualTo(RunStatus.RUNNING)
        assertThat(mergeBranches(null, null, null, listOf(GitBranch("r", "b", null)))).hasSize(1)
    }
}
