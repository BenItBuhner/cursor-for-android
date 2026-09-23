package com.cursorforandroid.ui.home

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import java.time.ZoneOffset

/**
 * An account for the New Chat pane's two layouts, organized as [com.cursorforandroid.ui.agents.AgentsViewModel]
 * organizes the list: five Projects — two at work, two with chats and one without — and the account's own chats.
 */
internal object NewChatHomeFixtures {
    /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock. */
    const val NOW = 1_736_949_600_000L

    const val BILLING = "bc-billing"
    const val BILLING_NAME = "Cesium billing launch"
    const val SHIPYARD_NAME = "Shipyard"
    const val PIPELINE_NAME = "Data pipeline"
    const val NEWEST_CHAT = "Cli exploration"

    private fun agent(
        id: String,
        name: String,
        ageMinutes: Long,
        repo: String = "techlitnow/cesium",
        running: Boolean = false,
        pr: Int? = null,
        appearance: ProjectAppearance? = null,
        parent: AgentParent? = null,
    ) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = NOW - (ageMinutes + 40) * 60_000L,
        updatedAtMillis = NOW - ageMinutes * 60_000L,
        latestRunId = null,
        repoUrl = "https://github.com/$repo",
        startingRef = "main",
        branches = if (pr != null) listOf(GitBranch("github.com/$repo", "cursor/${id.removePrefix("bc-")}", "https://github.com/$repo/pull/$pr")) else emptyList(),
        isProject = appearance != null,
        projectAppearance = appearance,
        parent = parent,
    )

    private fun worker(project: String) = AgentParent(project, AgentParentKind.PROJECT_WORKER)

    val agents: List<Agent> = listOf(
        agent(BILLING, BILLING_NAME, 65, appearance = ProjectAppearance("rocket", "purple")),
        agent("bc-usage", "Usage events aggregation", 80, pr = 215, parent = worker(BILLING)),
        agent("bc-stripe", "Stripe webhook handler", 95, running = true, parent = worker(BILLING)),
        agent("bc-pricing", "Pricing page copy", 110, parent = AgentParent(BILLING, AgentParentKind.SIDE_CHAT)),
        agent("bc-shipyard", SHIPYARD_NAME, 12, repo = "bennett/shipyard", running = true, appearance = ProjectAppearance("logo-github", "blue")),
        agent("bc-ship-1", "Release notes generator", 14, repo = "bennett/shipyard", pr = 38, parent = worker("bc-shipyard")),
        agent("bc-ship-2", "Signed APK pipeline", 30, repo = "bennett/shipyard", running = true, parent = worker("bc-shipyard")),
        agent("bc-ship-3", "Changelog backfill", 55, repo = "bennett/shipyard", pr = 36, parent = worker("bc-shipyard")),
        agent("bc-design", "Design system", 3 * 60, repo = "bennett/visual-engine", appearance = ProjectAppearance("logo-figma", "magenta")),
        agent("bc-tokens", "Colour tokens from Figma", 3 * 60 + 20, repo = "bennett/visual-engine", parent = worker("bc-design")),
        agent("bc-type", "Type scale audit", 4 * 60, repo = "bennett/visual-engine", parent = worker("bc-design")),
        agent("bc-android", "Cursor for Android", 5 * 60, repo = "bennett/cursor-for-android", appearance = ProjectAppearance("cursor-logo", "red")),
        agent("bc-and-1", "Settings picker", 5 * 60 + 10, repo = "bennett/cursor-for-android", parent = worker("bc-android")),
        agent("bc-and-2", "Tablet rail motion", 5 * 60 + 30, repo = "bennett/cursor-for-android", parent = worker("bc-android")),
        agent("bc-and-3", "Widget refresh", 6 * 60, repo = "bennett/cursor-for-android", parent = worker("bc-android")),
        agent("bc-pipeline", PIPELINE_NAME, 26 * 60, repo = "bennett/etl", appearance = ProjectAppearance("logo-python", "yellow")),
        agent("bc-cli", NEWEST_CHAT, 19, repo = "bennett/cursor-for-android"),
        agent("bc-codex", "Codex-Poly-Bot Scaling", 34, repo = "bennett/codex-poly-bot", running = true),
        agent("bc-revenue", "Revenue Scaling Pipeline Research", 2 * 60, pr = 209),
        agent("bc-release", "Latest release process", 3 * 60, repo = "bennett/cursor-for-android", pr = 3),
        agent("bc-onboarding", "Onboarding copy pass", 5 * 60, pr = 201),
        agent("bc-deps", "Weekly dependency bump", 7 * 60, repo = "bennett/cursor-for-android", pr = 1),
        agent("bc-strategy", "Cesium Revenue Strategy", 26 * 60, running = true),
    )

    fun list(agents: List<Agent> = this.agents): AgentListUiState {
        val prefs = ListPreferences()
        val local = LocalAgentState()
        val sections = AgentListOrganizer.organize(agents, prefs, local, nowMillis = NOW, zone = ZoneOffset.UTC)
        return AgentListUiState(
            sections = sections,
            recentRows = AgentListOrganizer.recentRows(sections),
            projectRows = AgentListOrganizer.projectRows(sections),
            allAgents = agents,
            prefs = prefs,
            local = local,
            hasLoaded = true,
            nowMillis = NOW,
        )
    }

    /** The same account before its first Project: the chats of its own alone. */
    fun withoutProjects(): AgentListUiState = list(agents.filter { !it.isProject && it.parent == null })
}
