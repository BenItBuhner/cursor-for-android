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
            agent("bc-11111111-root", "Cursor for Android") { it.copy(isProject = true, knownScope = AgentScope.PROJECT_ROOT, scopeSignal = LineageSignal.ACCOUNT_RECORD) },
            agent("bc-22222222-created", "New chat creation issue") { it.copy(parent = AgentParent("bc-11111111-root", AgentParentKind.PROJECT_WORKER), knownScope = AgentScope.PROJECT_CHILD, scopeSignal = LineageSignal.ACCOUNT_RECORD, runStatus = RunStatus.RUNNING) },
            agent("bc-33333333-adopted", "Model picker stability") { it.copy(parent = AgentParent("bc-11111111-root", AgentParentKind.PROJECT_WORKER), knownScope = AgentScope.PROJECT_CHILD, scopeSignal = LineageSignal.MEMBERSHIP) },
            agent("bc-44444444-side", "Pricing copy") { it.copy(source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD) },
            agent("bc-55555555-orphan", "Station intelligence v3 rewrite") { it.copy(parent = AgentParent("bc-99999999-gone", AgentParentKind.PROJECT_WORKER), knownScope = AgentScope.PROJECT_CHILD, scopeSignal = LineageSignal.COORDINATOR_TRANSCRIPT) },
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
        assertThat(report).contains("rows=6 primary=1 roots=1 children=4 running=1 archived=1")
        assertThat(report).contains("childrenWithoutLoadedParent=1 parents=…9-gone")
        // The root's line: its signal, its children by kind, and what its last membership pass answered — the notice redacted.
        assertThat(report).contains("…1-root signal=ACCOUNT_RECORD project_worker=2 side_chat=0 subagent=0 sync=workers:ok(2) children:failed notice=\"Cursor refused the Project's memberships (bc-…: \"…\" <url>).\"")
        assertThat(report).contains("…9-gone (not loaded) sync=never")
        // One line per row, in the report's columns.
        assertThat(report).contains("…reated CHILD ACCOUNT_RECORD …1-root PROJECT_WORKER - ACTIVE RUNNING running")
        assertThat(report).contains("…dopted CHILD MEMBERSHIP …1-root PROJECT_WORKER - ACTIVE FINISHED -")
        assertThat(report).contains("…4-side CHILD row.source - - AS_SIDE_CHAT_FROM_CLOUD ACTIVE FINISHED -")
        assertThat(report).contains("…orphan CHILD COORDINATOR_TRANSCRIPT …9-gone PROJECT_WORKER - ACTIVE FINISHED -")
        assertThat(report).contains("…-plain PRIMARY none - - - ARCHIVED FINISHED archived,pinned")
        assertThat(report).contains("…1-root ROOT ACCOUNT_RECORD - - - ACTIVE FINISHED project")
        // Nothing the user wrote or named: no chat names, prompts, summaries, repositories, full ids or URLs.
        for (forbidden in listOf("Cursor for Android\n", "New chat creation issue", "Model picker stability", "Pricing copy", "Station intelligence", "Cesium", "secret", "bennett", "github.com", "bc-11111111-root", "https://x.y/z", "quoted")) {
            assertThat(report).doesNotContain(forbidden)
        }
    }

    @Test
    fun `ids are shortened to a tail that tells rows apart without naming them`() {
        assertThat(ProjectDiagnostics.tail("bc-6c5768e2-379e-5d25-969b-23cb015f0e15")).isEqualTo("…5f0e15")
        assertThat(ProjectDiagnostics.tail("bc-1")).isEqualTo("bc-1")
    }
}
