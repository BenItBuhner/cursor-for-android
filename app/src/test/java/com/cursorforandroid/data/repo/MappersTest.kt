package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
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

/**
 * Reconciling a remembered row (possibly hours old, from disk) with what the list endpoints say now. The v1 `status`
 * is a lifecycle that reads `ACTIVE` for finished agents too, so nothing here may take it for "running".
 */
class MappersTest {

    private val listUpdatedAt = 1_776_106_800_000L // 2026-04-13T19:00:00Z, the summary's updatedAt

    private fun summary(id: String = "bc-1", status: String = "ACTIVE", latestRunId: String? = "run-1", name: String? = "Agent", updatedAt: String = "2026-04-13T19:00:00.000Z") =
        AgentSummaryDto(id = id, name = name, status = status, createdAt = "2026-04-13T18:30:00.000Z", updatedAt = updatedAt, latestRunId = latestRunId)

    private fun previous(runStatus: RunStatus?, lifecycle: AgentLifecycle = AgentLifecycle.ACTIVE, latestRunId: String? = "run-1", updatedAtMillis: Long = 2L) = Agent(
        id = "bc-1",
        name = "Agent",
        lifecycle = lifecycle,
        runStatus = runStatus,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/bc-1",
        createdAtMillis = 1L,
        updatedAtMillis = updatedAtMillis,
        latestRunId = latestRunId,
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        branches = listOf(GitBranch("https://github.com/acme/app", "cursor/x", null)),
        summary = "Remembered",
        modelDisplayName = "Claude",
        durationMs = 9_000,
    )

    @Test
    fun `the lifecycle alone never makes a row running`() {
        // ACTIVE is what the server says about every unarchived agent, finished or not.
        val fresh = summary(status = "ACTIVE").toAgent(null)
        assertThat(fresh.runStatus).isNull()
        assertThat(fresh.isRunning).isFalse()
        assertThat(fresh.runStatusUnknown).isTrue()
        assertThat(previous(RunStatus.UNKNOWN, lifecycle = AgentLifecycle.ACTIVE).isRunning).isFalse()
        assertThat(previous(null, lifecycle = AgentLifecycle.ACTIVE).isRunning).isFalse()
        // Only a run-level status does.
        assertThat(previous(RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE).isRunning).isTrue()
        assertThat(previous(RunStatus.CREATING, lifecycle = AgentLifecycle.ACTIVE).isRunning).isTrue()
        assertThat(previous(RunStatus.RUNNING, lifecycle = AgentLifecycle.ARCHIVED).isRunning).isFalse()
    }

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
    fun `the same run's status is kept whatever the lifecycle reads`() {
        assertThat(summary(status = "ACTIVE").toAgent(previous(RunStatus.RUNNING)).runStatus).isEqualTo(RunStatus.RUNNING)
        // The one that used to go wrong: a finished run stays finished although the lifecycle still says ACTIVE.
        val finished = summary(status = "ACTIVE").toAgent(previous(RunStatus.FINISHED, lifecycle = AgentLifecycle.IDLE))
        assertThat(finished.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(finished.isRunning).isFalse()
        assertThat(summary(status = "IDLE").toAgent(previous(RunStatus.ERROR)).runStatus).isEqualTo(RunStatus.ERROR)
        assertThat(summary(status = "IDLE").toAgent(previous(RunStatus.ERROR)).isError).isTrue()
    }

    @Test
    fun `a remembered finish survives newer activity on the same run but not on an unnamed one`() {
        // The server bumped the agent's updatedAt after the finish (a PR opened, say) without a new run: still finished.
        val bumped = summary(status = "ACTIVE").toAgent(previous(RunStatus.FINISHED, lifecycle = AgentLifecycle.IDLE, updatedAtMillis = listUpdatedAt - 60_000))
        assertThat(bumped.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(bumped.isRunning).isFalse()
        // Neither side names its run: newer activity than the row knows may be a new turn, so the finish is not assumed.
        val unnamed = summary(status = "ACTIVE", latestRunId = null).toAgent(previous(RunStatus.FINISHED, latestRunId = null, updatedAtMillis = listUpdatedAt - 60_000))
        assertThat(unnamed.runStatus).isNull()
        assertThat(unnamed.isRunning).isFalse()
        // ...but a list answer older than the finish the row watched is stale, not news.
        val stale = summary(status = "ACTIVE", latestRunId = null).toAgent(previous(RunStatus.FINISHED, latestRunId = null, updatedAtMillis = listUpdatedAt + 5_000))
        assertThat(stale.runStatus).isEqualTo(RunStatus.FINISHED)
    }

    @Test
    fun `a new run started elsewhere resets the remembered status and waits for a run-level source`() {
        val row = summary(status = "ACTIVE", latestRunId = "run-2").toAgent(previous(RunStatus.FINISHED))
        assertThat(row.runStatus).isNull()
        assertThat(row.latestRunId).isEqualTo("run-2")
        assertThat(row.runStatusUnknown).isTrue()
        // Not called running on the lifecycle's word: the legacy list, the record or the repository's verification say.
        assertThat(row.isRunning).isFalse()
        assertThat(row.withLegacy(V0AgentDto(id = "bc-1", status = "RUNNING")).isRunning).isTrue()
    }

    @Test
    fun `the legacy record fills the gaps and its terminal status lands whatever the lifecycle reads`() {
        val v0 = V0AgentDto(
            id = "bc-1",
            name = "Legacy name",
            status = "FINISHED",
            source = V0SourceDto("https://github.com/acme/app", "develop"),
            target = V0TargetDto(branchName = "cursor/y", prUrl = "https://github.com/acme/app/pull/1", autoCreatePr = true),
            summary = "Legacy summary",
        )
        // ACTIVE lifecycle, finished v0 status: the everyday shape of a finished agent on v1.
        val active = summary(status = "ACTIVE", name = null).toAgent(null).withLegacy(v0)
        assertThat(active.name).isEqualTo("Legacy name")
        assertThat(active.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(active.isRunning).isFalse()
        assertThat(active.repoUrl).isEqualTo("https://github.com/acme/app")
        assertThat(active.startingRef).isEqualTo("develop")
        assertThat(active.branchName).isEqualTo("cursor/y")
        assertThat(active.prUrl).isEqualTo("https://github.com/acme/app/pull/1")
        assertThat(active.summary).isEqualTo("Legacy summary")
        assertThat(active.autoCreatePr).isTrue()
        assertThat(summary(status = "IDLE").toAgent(null).withLegacy(v0).runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(summary(status = "ACTIVE").toAgent(null).withLegacy(v0.copy(status = "ERROR")).isError).isTrue()

        // Branches learned from a run (richer: several repos, live PR) beat the legacy single branch.
        val enriched = summary(status = "IDLE").toAgent(previous(RunStatus.FINISHED)).withLegacy(v0)
        assertThat(enriched.branchName).isEqualTo("cursor/x")
        assertThat(enriched.summary).isEqualTo("Legacy summary")
    }

    @Test
    fun `an active legacy status yields to an idle or archived lifecycle only`() {
        val running = V0AgentDto(id = "bc-1", status = "RUNNING")
        assertThat(summary(status = "ACTIVE").toAgent(null).withLegacy(running).isRunning).isTrue()
        assertThat(summary(status = "IDLE").toAgent(null).withLegacy(running).runStatus).isNull()
        assertThat(summary(status = "ARCHIVED").toAgent(null).withLegacy(running).isRunning).isFalse()
    }

    @Test
    fun `the combined mapper matches applying both steps`() {
        val v0 = V0AgentDto(id = "bc-1", status = "FINISHED", summary = "S")
        val combined = summary().toAgent(v0, previous(null))
        val stepwise = summary().toAgent(previous(null)).withLegacy(v0)
        assertThat(combined).isEqualTo(stepwise)
        assertThat(combined.runStatus).isEqualTo(RunStatus.FINISHED)
    }

    @Test
    fun `the full record without its run applies the same rules`() {
        val dto = AgentDto(id = "bc-1", name = "Agent", status = "IDLE", createdAt = "2026-04-13T18:30:00.000Z", updatedAt = "2026-04-13T19:00:00.000Z", latestRunId = "run-1")
        assertThat(dto.mergeInto(previous(RunStatus.RUNNING), latestRun = null).isRunning).isFalse()
        assertThat(dto.copy(status = "ACTIVE").mergeInto(previous(RunStatus.RUNNING), latestRun = null).isRunning).isTrue()
        assertThat(dto.copy(status = "ACTIVE").mergeInto(previous(RunStatus.FINISHED), latestRun = null).runStatus).isEqualTo(RunStatus.FINISHED)
        // A status remembered for another run says nothing about the one the record names.
        val moved = dto.copy(status = "ACTIVE", latestRunId = "run-2").mergeInto(previous(RunStatus.FINISHED, lifecycle = AgentLifecycle.IDLE), latestRun = null)
        assertThat(moved.runStatus).isNull()
        assertThat(moved.isRunning).isFalse()
        // The run itself, when it comes along, is the authority.
        val run = RunDto(id = "run-2", agentId = "bc-1", status = "RUNNING", createdAt = "2026-04-13T19:00:00.000Z", updatedAt = "2026-04-13T19:00:00.000Z")
        assertThat(dto.copy(status = "ACTIVE", latestRunId = "run-2").mergeInto(previous(RunStatus.FINISHED), latestRun = run).isRunning).isTrue()
    }

    @Test
    fun `the legacy status only fills a gap and cannot overwrite what a run-level source reported`() {
        val v0 = V0AgentDto(id = "bc-1", status = "FINISHED")
        val row = summary(status = "IDLE").toAgent(previous(RunStatus.ERROR, lifecycle = AgentLifecycle.IDLE)).withLegacy(v0)
        assertThat(row.runStatus).isEqualTo(RunStatus.ERROR)
        assertThat(row.isError).isTrue()
        assertThat(row.isRunning).isFalse()
        // A run the row holds as active keeps running here; the repository settles it against the run record, so a
        // legacy list that lags the row does not flip a live follow-up to finished and back.
        assertThat(summary(status = "ACTIVE").toAgent(previous(RunStatus.RUNNING)).withLegacy(v0).isRunning).isTrue()
        // An unrecognised status is a gap too.
        assertThat(summary(status = "IDLE").toAgent(previous(RunStatus.UNKNOWN, lifecycle = AgentLifecycle.IDLE)).withLegacy(v0).runStatus).isEqualTo(RunStatus.FINISHED)
    }

    @Test
    fun `the server's updatedAt is the row's activity time and a local stamp survives only while barely ahead`() {
        // A row whose activity time was stamped locally long after the server's (the old "updated just now" bug) is put
        // back where the server has it, instead of carrying the mistake forward for good.
        val inflated = summary().toAgent(previous(RunStatus.FINISHED, updatedAtMillis = listUpdatedAt + 3 * 24 * 3_600_000L))
        assertThat(inflated.updatedAtMillis).isEqualTo(listUpdatedAt)
        // A follow-up sent here a moment ago that the list has not caught up with yet keeps its place.
        val justSent = summary().toAgent(previous(RunStatus.RUNNING, updatedAtMillis = listUpdatedAt + 90_000))
        assertThat(justSent.updatedAtMillis).isEqualTo(listUpdatedAt + 90_000)
        // Older activity than the server's is simply superseded.
        assertThat(summary().toAgent(previous(RunStatus.FINISHED, updatedAtMillis = listUpdatedAt - 60_000)).updatedAtMillis).isEqualTo(listUpdatedAt)
        // Nothing from the server: whatever was known stands.
        assertThat(reconcileUpdatedAt(0L, 42L)).isEqualTo(42L)
        assertThat(reconcileUpdatedAt(0L, null)).isEqualTo(0L)
        assertThat(reconcileUpdatedAt(listUpdatedAt, listUpdatedAt + LOCAL_ACTIVITY_GRACE_MS)).isEqualTo(listUpdatedAt + LOCAL_ACTIVITY_GRACE_MS)
        assertThat(reconcileUpdatedAt(listUpdatedAt, listUpdatedAt + LOCAL_ACTIVITY_GRACE_MS + 1)).isEqualTo(listUpdatedAt)

        // The full record follows the same rule, with its run's updatedAt counting as server activity too.
        val dto = AgentDto(id = "bc-1", name = "Agent", status = "ACTIVE", createdAt = "2026-04-13T18:30:00.000Z", updatedAt = "2026-04-13T19:00:00.000Z", latestRunId = "run-1")
        assertThat(dto.mergeInto(previous(RunStatus.FINISHED, updatedAtMillis = listUpdatedAt + 3_600_000L), latestRun = null).updatedAtMillis).isEqualTo(listUpdatedAt)
        val laterRun = RunDto(id = "run-1", agentId = "bc-1", status = "FINISHED", createdAt = "2026-04-13T18:30:00.000Z", updatedAt = "2026-04-13T19:10:00.000Z")
        assertThat(dto.mergeInto(previous(RunStatus.FINISHED), latestRun = laterRun).updatedAtMillis).isEqualTo(listUpdatedAt + 10 * 60_000L)
    }

    @Test
    fun `a run read from the server is folded into the row only while it is the row's latest`() {
        val row = previous(RunStatus.FINISHED, lifecycle = AgentLifecycle.IDLE)
        val errored = RunDto(id = "run-1", agentId = "bc-1", status = "ERROR", createdAt = "2026-04-13T18:30:00.000Z", updatedAt = "2026-04-13T18:40:00.000Z", durationMs = 600_000, result = "Build failed")
        val updated = row.withLatestRun(errored)
        assertThat(updated.runStatus).isEqualTo(RunStatus.ERROR)
        assertThat(updated.isError).isTrue()
        assertThat(updated.durationMs).isEqualTo(600_000)
        assertThat(updated.summary).isEqualTo("Build failed")
        assertThat(updated.branches).isEqualTo(row.branches)
        // Reading a record is not activity: the row's place in the list does not move.
        assertThat(updated.updatedAtMillis).isEqualTo(row.updatedAtMillis)

        // An older run of the same agent changes nothing; a row that does not know its latest run adopts the one it is given.
        assertThat(row.withLatestRun(errored.copy(id = "run-0"))).isEqualTo(row)
        val adopted = row.copy(latestRunId = null).withLatestRun(errored.copy(status = "RUNNING"))
        assertThat(adopted.latestRunId).isEqualTo("run-1")
        assertThat(adopted.isRunning).isTrue()
    }

    @Test
    fun `one record missing its timestamps does not cost the rest of the list`() {
        val body = """
            {"items":[
              {"id":"bc-1","name":"Good","status":"ACTIVE","createdAt":"2026-04-13T18:30:00.000Z","updatedAt":"2026-04-13T19:00:00.000Z","latestRunId":"run-1"},
              {"id":"bc-2","name":"Odd"}
            ]}
        """.trimIndent()

        val decoded = CursorJson.decodeFromString(ListAgentsResponseDto.serializer(), body)

        assertThat(decoded.items.map { it.id }).containsExactly("bc-1", "bc-2").inOrder()
        val odd = decoded.items.last().toAgent(previous = null)
        assertThat(odd.name).isEqualTo("Odd")
        assertThat(odd.createdAtMillis).isEqualTo(0L)
        assertThat(odd.updatedAtMillis).isEqualTo(0L)

        // The same for a run: an unrecognised status reads as UNKNOWN, which is at rest.
        val run = CursorJson.decodeFromString(RunDto.serializer(), """{"id":"run-9","agentId":"bc-2"}""")
        assertThat(RunStatus.parse(run.status)).isEqualTo(RunStatus.UNKNOWN)
    }

    @Test
    fun `an unrecognised run status is at rest whatever the lifecycle`() {
        assertThat(previous(RunStatus.UNKNOWN, lifecycle = AgentLifecycle.ACTIVE).isRunning).isFalse()
        assertThat(previous(RunStatus.UNKNOWN, lifecycle = AgentLifecycle.IDLE).isRunning).isFalse()
        assertThat(previous(RunStatus.UNKNOWN, lifecycle = AgentLifecycle.IDLE).isError).isFalse()
        assertThat(previous(RunStatus.UNKNOWN).runStatusUnknown).isTrue()
        assertThat(previous(RunStatus.FINISHED).runStatusUnknown).isFalse()
    }
}
