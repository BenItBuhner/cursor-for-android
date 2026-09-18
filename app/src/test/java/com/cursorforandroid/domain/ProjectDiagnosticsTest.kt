package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The redacted dump Settings › Advanced exports: every row's placement and its signal, nothing of the user's text. */
class ProjectDiagnosticsTest {

    private fun agent(id: String, name: String, edit: (Agent) -> Agent = { it }): Agent {
        val base = Agent(
            id = id, name = name, lifecycle = AgentLifecycle.ACTIVE, runStatus = RunStatus.FINISHED, envType = EnvType.CLOUD, envName = null,
            url = "https://cursor.com/agents/$id", createdAtMillis = 1L, updatedAtMillis = 2L, latestRunId = "run-$id",
            repoUrl = "https://github.com/bennett/secret-repo", startingRef = "main", summary = "The secret summary of the chat.",
        )
        return edit(base)
    }

    @Test
    fun `the report names every row by its id's tail, scope and signal, and carries no text of the account's`() {
        val rows = listOf(
            agent("bc-11111111-root", "Cursor for Android") { it.copy(isProject = true, scopeSignal = LineageSignal.ACCOUNT_RECORD, record = com.cursorforandroid.data.api.RecordFields(projectMetadata = "{}")) },
            agent("bc-22222222-created", "New chat creation issue") { it.copy(parent = AgentParent("bc-11111111-root", AgentParentKind.PROJECT_WORKER), scopeSignal = LineageSignal.ACCOUNT_RECORD, runStatus = RunStatus.RUNNING, record = com.cursorforandroid.data.api.RecordFields(managerAgentId = "bc-11111111-root")) },
            agent("bc-33333333-adopted", "Model picker stability") { it.copy(parent = AgentParent("bc-11111111-root", AgentParentKind.PROJECT_WORKER), scopeSignal = LineageSignal.MEMBERSHIP) },
            agent("bc-44444444-side", "Pricing copy") { it.copy(source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD) },
            agent("bc-55555555-orphan", "Station intelligence v3 rewrite") { it.copy(parent = AgentParent("bc-99999999-gone", AgentParentKind.PROJECT_WORKER), scopeSignal = LineageSignal.COORDINATOR_CREATED) },
            agent("bc-66666666-plain", "Cesium") { it.copy(lifecycle = AgentLifecycle.ARCHIVED) },
        )
        val report = ProjectDiagnostics.render(
            ProjectDiagnostics.Input(
                appVersion = "0.3.0",
                nowIso = "2026-09-13T04:50:00Z",
                extendedMode = true,
                projectsCapability = true,
                accountSession = true,
                listFromCache = false,
                lastRefreshedIso = "2026-09-13T04:49:30Z",
                agents = rows,
                placementOf = { id -> if (id == "bc-33333333-adopted") AgentParent("bc-11111111-root", AgentParentKind.PROJECT_WORKER) to LineageSignal.MEMBERSHIP else null },
                rootSyncs = mapOf("bc-11111111-root" to ProjectDiagnostics.RootSync(workersRead = true, childrenRead = false, workerCount = 2, childCount = 0, notice = "Cursor refused the Project's memberships (bc-11111111-root: \"quoted\" https://x.y/z).")),
                pinnedIds = setOf("bc-66666666-plain"),
            ),
        )

        assertThat(report).startsWith("Cursor for Android 0.3.0 · Project diagnostics · 2026-09-13T04:50:00Z")
        assertThat(report).contains("mode=extended projects=true accountSession=true listFromCache=false lastRefreshed=2026-09-13T04:49:30Z")
        // A side chat whose record names no parent is a chat of the account's own (the desktop reads the parent link alone).
        assertThat(report).contains("rows=6 primary=2 roots=1 children=3 running=1 archived=1")
        assertThat(report).contains("childrenWithoutLoadedParent=1 parents=…9-gone")
        // The root's line: its signal, its children by kind, and what its last membership pass answered — the notice redacted.
        assertThat(report).contains("…1-root signal=ACCOUNT_RECORD project_worker=2 side_chat=0 subagent=0 sync=workers:ok(2) children:failed notice=\"Cursor refused the Project's memberships (bc-…: \"…\" <url>).\"")
        assertThat(report).contains("…9-gone (not loaded) sync=never")
        // One line per row, in the report's columns, then the desktop rule that placed it and the record's raw fields.
        assertThat(report).contains("…reated CHILD ACCOUNT_RECORD …1-root PROJECT_WORKER - CLOUD ACTIVE RUNNING running")
        assertThat(report).contains("    rule: PJr child of …1-root via managerAgentId → nested under the parent's row")
        assertThat(report).contains("    record: project_metadata=absent manager_agent_id=…1-root cloud_subagent_parent=- side_chat_parent=- started_as_new_project=false source=-")
        assertThat(report).contains("…dopted CHILD MEMBERSHIP …1-root PROJECT_WORKER - CLOUD ACTIVE FINISHED stamp=MEMBERSHIP")
        assertThat(report).contains("    rule: PJr child of …1-root via ListWorkersForManager (the desktop's seeded managerAgentId) → nested under the parent's row")
        assertThat(report).contains("…4-side PRIMARY none - - AS_SIDE_CHAT_FROM_CLOUD CLOUD ACTIVE FINISHED -")
        assertThat(report).contains("    rule: mQa top-level: no account record read yet: the public API's row alone → time section (f3v)")
        assertThat(report).contains("…orphan CHILD COORDINATOR_CREATED …9-gone PROJECT_WORKER - CLOUD ACTIVE FINISHED -")
        assertThat(report).contains("    rule: PJr child of …9-gone via create_agent in the coordinator's transcript (default mode) → parent row not loaded: not drawn until the parent is fetched by id (the desktop hydrates it)")
        assertThat(report).contains("…-plain PRIMARY none - - - CLOUD ARCHIVED FINISHED archived,pinned")
        assertThat(report).contains("    rule: mQa top-level: no account record read yet: the public API's row alone → Pinned (VuC)")
        assertThat(report).contains("…1-root ROOT ACCOUNT_RECORD - - - CLOUD ACTIVE FINISHED project")
        assertThat(report).contains("    rule: kf top-level Project: no subagentParentId, projectMetadata present → Projects")
        assertThat(report).contains("desktop rules (Cursor 3.20.21 workbench.glass.main.js)")
        // The pins, the running set and what placed each row are in it too.
        assertThat(report).contains("pinned (id · resolution · scope · env):")
        assertThat(report).contains("…-plain shown PRIMARY CLOUD")
        assertThat(report).contains("running: list=1 counted=1 excludedByEvidence=0 scan=never")
        // Nothing the user wrote or named: no chat names, prompts, summaries, repositories, full ids or URLs.
        for (forbidden in listOf("Cursor for Android\n", "New chat creation issue", "Model picker stability", "Pricing copy", "Station intelligence", "Cesium", "secret", "bennett", "github.com", "bc-11111111-root", "https://x.y/z", "quoted")) {
            assertThat(report).doesNotContain(forbidden)
        }
    }

    /** The `refresh:` block: what the last pull cost, stage by stage, the spinner's release and the settle, so a slow refresh can be read back to its stage. */
    @Test
    fun `the report says what the last refresh cost, stage by stage`() {
        var clock = 1_000_000L
        val stats = RefreshStats(now = { clock })
        stats.begin()
        clock += 60
        stats.stage("v1 pages", calls = 2, startedAtMillis = 1_000_000L, endedAtMillis = clock, note = "180 rows")
        stats.spinnerReleased()
        clock += 240
        stats.stage("memberships (workers + children per root)", calls = 12, startedAtMillis = 1_000_100L, endedAtMillis = clock, note = "6 roots read, 2 unchanged and skipped")
        stats.settled()
        val report = ProjectDiagnostics.render(
            ProjectDiagnostics.Input(
                appVersion = "0.3.30", nowIso = "2026-09-18T15:00:00Z", extendedMode = true, projectsCapability = true, accountSession = true,
                listFromCache = false, lastRefreshedIso = null, agents = emptyList(), placementOf = { null }, rootSyncs = emptyMap(),
                refresh = stats.snapshot.value,
            ),
        )
        assertThat(report).contains("refresh:")
        assertThat(report).contains("spinnerReleased=+60ms settled=+300ms calls=14")
        assertThat(report).contains("+0ms → +60ms (60ms)  2 × v1 pages · 180 rows")
        assertThat(report).contains("+100ms → +300ms (200ms)  12 × memberships (workers + children per root) · 6 roots read, 2 unchanged and skipped")
        // Before any refresh this process, the block says so rather than inventing one.
        val none = ProjectDiagnostics.render(
            ProjectDiagnostics.Input(
                appVersion = "0.3.30", nowIso = "2026-09-18T15:00:00Z", extendedMode = true, projectsCapability = true, accountSession = true,
                listFromCache = true, lastRefreshedIso = null, agents = emptyList(), placementOf = { null }, rootSyncs = emptyMap(),
            ),
        )
        assertThat(none).contains("refresh:\n  none this process")
    }

    @Test
    fun `ids are shortened to a tail that tells rows apart without naming them`() {
        assertThat(ProjectDiagnostics.tail("bc-6c5768e2-379e-5d25-969b-23cb015f0e15")).isEqualTo("…5f0e15")
        assertThat(ProjectDiagnostics.tail("bc-1")).isEqualTo("bc-1")
    }
}
