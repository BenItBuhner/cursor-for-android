package com.cursorforandroid.domain

import com.cursorforandroid.util.AppClock
import java.time.ZoneId

/** What one instance of the home-screen widget lists. Chosen when the widget is placed; changeable from its header. */
enum class WidgetMode(
    /** Header label, in the sidebar's group-label voice ("Pinned", "Today"). */
    val title: String,
    /** Picker row in the configuration screen. */
    val label: String,
    /** Detail line under the picker row. */
    val detail: String,
    /** Shown in the widget when the list is empty. */
    val emptyText: String,
) {
    Recent("Recent", "Recent chats", "Every chat, newest first", "No chats yet"),
    Running("Running", "Running agents", "Only the agents working right now", "No agents running"),
    Pinned("Pinned", "Pinned chats", "The chats you pinned in the sidebar", "No pinned chats"),
    /** Every Project as a shortcut: one row each, in its icon and colour, that opens the Project's coordinator. */
    Projects("Projects", "All Projects", "Every Project, one tap from its coordinator", "No Projects yet"),
    /** One Project's chats: its coordinator first, then the workers and side chats nested under it. */
    Project("Project", "One Project", "A Project's coordinator and its chats", "No chats in this Project yet");

    /** The choice this mode is made under: one "Projects" choice holds both the Projects list and one Project's. */
    val choice: WidgetMode get() = if (this == Project) Projects else this

    companion object {
        val Default = Recent

        /** What the configuration screen offers, one segment each; see [choice]. */
        val choices: List<WidgetMode> = listOf(Recent, Running, Pinned, Projects)

        /** The stored name, tolerating anything a previous build may have written. */
        fun parse(raw: String?): WidgetMode = entries.firstOrNull { it.name == raw } ?: Default
    }
}

/**
 * The rows a widget shows, derived from the same list, filters and local state the app renders, so the widget is
 * the sidebar on the home screen rather than a second opinion about it.
 */
object WidgetList {

    /** Rows beyond this are not worth a RemoteViews each; the app has the rest. */
    const val MAX_ROWS = 30

    fun rows(
        mode: WidgetMode,
        agents: List<Agent>,
        prefs: ListPreferences,
        local: LocalAgentState,
        nowMillis: Long = AppClock.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        /** The Project a [WidgetMode.Project] widget lists; ignored by the other modes. */
        projectId: String? = null,
        /** The registry's Project roots (see [KnownRoot]), which [WidgetMode.Projects] lists as the sidebar does. */
        knownRoots: Collection<KnownRoot> = emptyList(),
        /** The account's member count per Project, which [WidgetMode.Projects] counts by as the sidebar does. */
        memberCounts: Map<String, Int> = emptyMap(),
    ): List<AgentRow> = when (mode) {
        // The New Chat pane's recent list: every row the Chats filters let through, newest first. Sidebar search
        // is a find-in-rail, not a second filter on this list.
        WidgetMode.Recent -> AgentListOrganizer.recentRows(agents, prefs, local, nowMillis = nowMillis, zone = zone)
        // The sidebar's "Pinned" group, in the sidebar's order — less a Project's own chats, which the widget never lists.
        WidgetMode.Pinned -> AgentListOrganizer.organize(agents, prefs, local, nowMillis = nowMillis, zone = zone)
            .firstOrNull { it.key == AgentListOrganizer.PINNED_KEY }?.rows.orEmpty()
            .filterNot { it.agent.isProjectScopedByEvidence }
        // Agents at work, whatever the Status filter says (a "Running" list with Running filtered out would only
        // ever be empty); the Repo / Git / Source filters still apply. Nothing of a Project — its coordinator, its
        // workers, side chats and subagents — belongs to a primary list like this one.
        WidgetMode.Running -> agents
            .filter { !it.isProjectScopedByEvidence }
            .map { AgentListOrganizer.toRow(it, local, nowMillis) }
            .filter { it.indicator == AgentIndicator.Running && AgentListOrganizer.matchesFilters(it, prefs.copy(statuses = prefs.statuses + StatusFilter.Running)) }
            .sortedByDescending { it.agent.listedAtMillis }
        // The Project's subtree as the sidebar nests it: the coordinator, then its workers and side chats, each with
        // its own children, in the sidebar's order. The Project's own row leads so the widget is that Project's, and
        // the Status filter does not apply inside it (a Project's chats are shown under it whatever their state,
        // as in the sidebar), only the archive rule does.
        WidgetMode.Project -> projectRows(projectId, agents, prefs, local, nowMillis, zone)
        WidgetMode.Projects -> projectShortcuts(agents, prefs, local, nowMillis, zone, knownRoots, memberCounts)
    }.take(MAX_ROWS)

    /**
     * The sidebar's Projects group, a shortcut each: every live Project — the registry's stand-ins for the ones the
     * list holds no row for among them — in the group's order, each row carrying its subtree so it can count its chats
     * and say whether any of them is working. The group answers to the archive rule alone (see
     * [AgentListOrganizer.isListed]), and so does this list, whatever the Archived filter says: an archived Project, or
     * a Project still loading under no name, is no shortcut, and an archived chat is not counted under its Project.
     */
    private fun projectShortcuts(
        agents: List<Agent>,
        prefs: ListPreferences,
        local: LocalAgentState,
        nowMillis: Long,
        zone: ZoneId,
        knownRoots: Collection<KnownRoot>,
        memberCounts: Map<String, Int>,
    ): List<AgentRow> {
        val live = prefs.copy(statuses = StatusFilter.entries.toSet() - StatusFilter.Archived)
        return AgentListOrganizer.organize(agents, live, local, nowMillis = nowMillis, zone = zone, knownRoots = knownRoots, memberCounts = memberCounts)
            .firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()
            .filterNot { it.isPlaceholder || it.agent.isArchived }
    }

    private fun projectRows(projectId: String?, agents: List<Agent>, prefs: ListPreferences, local: LocalAgentState, nowMillis: Long, zone: ZoneId): List<AgentRow> {
        if (projectId == null) return emptyList()
        // Every Project the sidebar would draw, with the archive rule off so the chosen one is found even when its
        // coordinator is archived (the widget was set to it; a blank widget says less than an archived one).
        val everything = prefs.copy(statuses = StatusFilter.entries.toSet(), repos = null, git = GitFilter.entries.toSet(), sources = SourceFilter.entries.toSet(), environments = EnvironmentFilter.entries.toSet())
        val root = AgentListOrganizer.organize(agents, everything, local, nowMillis = nowMillis, zone = zone)
            .firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()
            .firstOrNull { it.agent.id == projectId } ?: return emptyList()
        val flat = ArrayList<AgentRow>()
        fun walk(row: AgentRow) {
            if (!row.isPlaceholder) flat += row
            row.children.forEach(::walk)
        }
        walk(root)
        return flat
    }

    /** The Projects a widget can be set to: every root the list holds a row for, by name. */
    fun projects(agents: List<Agent>): List<Agent> = agents.filter { it.isProjectRoot && !it.isArchived }.sortedBy { it.name.lowercase() }
}
