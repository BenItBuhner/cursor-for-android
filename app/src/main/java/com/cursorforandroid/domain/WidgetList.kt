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
    Pinned("Pinned", "Pinned chats", "The chats you pinned in the sidebar", "No pinned chats");

    companion object {
        val Default = Recent

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
    ): List<AgentRow> = when (mode) {
        // The New Chat pane's recent list: every row the Chats filters let through, newest first.
        WidgetMode.Recent -> AgentListOrganizer.organize(agents, prefs, local, nowMillis = nowMillis, zone = zone)
            .flatMap { it.rows }
            .distinctBy { it.agent.id }
            .sortedByDescending { it.agent.updatedAtMillis }
        // The sidebar's "Pinned" group, in the sidebar's order.
        WidgetMode.Pinned -> AgentListOrganizer.organize(agents, prefs, local, nowMillis = nowMillis, zone = zone)
            .firstOrNull { it.key == "pinned" }?.rows.orEmpty()
        // Agents at work, whatever the Status filter says (a "Running" list with Running filtered out would only
        // ever be empty); the Repo / Git / Source filters still apply.
        WidgetMode.Running -> agents
            .map { AgentListOrganizer.toRow(it, local) }
            .filter { it.indicator == AgentIndicator.Running && AgentListOrganizer.matchesFilters(it, prefs.copy(statuses = prefs.statuses + StatusFilter.Running)) }
            .sortedByDescending { it.agent.updatedAtMillis }
    }.take(MAX_ROWS)
}
