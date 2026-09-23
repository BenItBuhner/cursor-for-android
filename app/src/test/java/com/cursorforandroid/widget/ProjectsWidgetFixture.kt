package com.cursorforandroid.widget

import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.KnownRoot
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.ThemeMode

/**
 * An account with a Projects widget's worth of Projects, each in a state the widget draws differently: a coordinator
 * at work, a Project whose chats are, one that failed, one unread under a name too long for the row, one with no
 * chats, one in the default tone and icon, and one the root registry names that the list has not loaded. An archived
 * Project, one still loading under no name, and a chat of the account's own are there to be left out.
 */
internal object ProjectsWidgetFixture {

    const val LONG_NAME = "Revenue Scaling Pipeline Research and Long-Horizon Forecasting"

    /** The Projects the widget lists from [snapshot], in the sidebar's order. */
    val listedIds = listOf("p-android", "p-revenue", "p-visual", "p-market", "p-poly", "p-notes", "p-home")

    fun snapshot(theme: ThemeMode, nowMillis: Long, extendedMode: Boolean = true): WidgetSnapshot {
        val minute = 60_000L
        fun agent(
            id: String,
            name: String,
            ageMinutes: Long,
            repo: String?,
            status: RunStatus = RunStatus.FINISHED,
            lifecycle: AgentLifecycle = AgentLifecycle.IDLE,
            appearance: ProjectAppearance? = null,
            project: Boolean = false,
            parent: String? = null,
        ) = Agent(
            id = id,
            name = name,
            lifecycle = lifecycle,
            runStatus = status,
            envType = EnvType.CLOUD,
            envName = null,
            url = "https://cursor.com/agents/$id",
            createdAtMillis = nowMillis - ageMinutes * minute - 60 * minute,
            updatedAtMillis = nowMillis - ageMinutes * minute,
            latestRunId = "run-$id",
            repoUrl = repo?.let { "https://github.com/bennett/$it" },
            startingRef = "main",
            isProject = project,
            projectAppearance = appearance,
            parent = parent?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) },
        )
        fun project(id: String, name: String, ageMinutes: Long, repo: String?, appearance: ProjectAppearance?, status: RunStatus = RunStatus.FINISHED, lifecycle: AgentLifecycle = AgentLifecycle.IDLE) =
            agent(id, name, ageMinutes, repo, status, lifecycle, appearance, project = true)
        fun worker(id: String, parent: String, ageMinutes: Long, running: Boolean = false) =
            agent(id, "Worker $id", ageMinutes, null, if (running) RunStatus.RUNNING else RunStatus.FINISHED, if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE, parent = parent)

        val agents = listOf(
            project("p-android", "Cursor for Android", 2, "cursor-for-android", ProjectAppearance("rocket", "purple"), RunStatus.RUNNING, AgentLifecycle.ACTIVE),
            worker("w-a1", "p-android", 3, running = true),
            worker("w-a2", "p-android", 20),
            worker("w-a3", "p-android", 60),
            worker("w-a4", "p-android", 90),
            project("p-revenue", LONG_NAME, 10, "cesium", ProjectAppearance("sparkles", "yellow")),
            worker("w-r1", "p-revenue", 12),
            worker("w-r2", "p-revenue", 70),
            project("p-visual", "Visual engine", 15, "visual-engine", ProjectAppearance("palette", "orange")),
            worker("w-v1", "p-visual", 16, running = true),
            worker("w-v2", "p-visual", 18, running = true),
            worker("w-v3", "p-visual", 45),
            project("p-market", "Market replay", 40, "market-replay", ProjectAppearance("database", "green"), RunStatus.ERROR),
            worker("w-m1", "p-market", 41),
            project("p-poly", "Codex Poly Bot", 180, "codex-poly-bot", ProjectAppearance("robot", "cyan")),
            project("p-notes", "Notes", 300, "notes", appearance = null),
            project("p-old", "Old prototype", 400, "prototype", ProjectAppearance("flask", "red"), lifecycle = AgentLifecycle.ARCHIVED),
            agent("c-login", "Fix the login flow", 1, "cursor-for-android", RunStatus.RUNNING, AgentLifecycle.ACTIVE),
        )
        val roots = listOf(
            KnownRoot("p-home", name = "Home automation", appearance = ProjectAppearance("globe", "blue"), signal = LineageSignal.ACCOUNT_RECORD),
            KnownRoot("p-loading", name = null, signal = LineageSignal.ACCOUNT_RECORD),
        )
        // Every row read but the long-named Project's, so its unread badge is the one on the list.
        val read = (agents.map { it.id } + roots.map { it.id } - "p-revenue").associateWith { nowMillis }
        return WidgetSnapshot(
            session = SessionState.Loading,
            hasLoaded = true,
            agents = agents,
            prefs = ListPreferences(),
            local = LocalAgentState(readMarkers = read),
            theme = theme,
            knownRoots = roots,
            extendedMode = extendedMode,
        )
    }

    /** The same account with no Projects in it at all: a list of plain chats. */
    fun withoutProjects(theme: ThemeMode, nowMillis: Long, extendedMode: Boolean): WidgetSnapshot = snapshot(theme, nowMillis, extendedMode).let { full ->
        full.copy(agents = full.agents.filter { !it.isProject && it.parent == null }, knownRoots = emptyList())
    }
}
