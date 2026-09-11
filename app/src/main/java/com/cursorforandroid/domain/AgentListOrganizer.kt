package com.cursorforandroid.domain

import com.cursorforandroid.util.AppClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One row in the sidebar / agent list. */
data class AgentRow(
    val agent: Agent,
    val indicator: AgentIndicator,
    val isPinned: Boolean,
    val isUnread: Boolean,
    val launchedFromThisDevice: Boolean,
    /** Where the agent's pull request stands; null for an agent without one, or one whose state is not known (yet). */
    val pullRequest: PullRequestState? = null,
    /** True while this device is silencing the chat; independent of archive. */
    val isSnoozed: Boolean = false,
    /** When the snooze began; used so later updates do not jump the chat up the list. */
    val snoozedAtMillis: Long? = null,
    /**
     * The chats the sidebar nests under this one — the workers a Project's coordinator delegated to, side chats,
     * cloud subagents — in the list's order, each with children of its own; empty for a chat on its own (see
     * [AgentListOrganizer.nest]).
     */
    val children: List<AgentRow> = emptyList(),
) {
    /** This row's children, their children and so on, depth first — what a collapsed parent stands for. */
    fun descendants(): List<AgentRow> = children.flatMap { listOf(it) + it.descendants() }

    /** A turn is going somewhere in the subtree: the working glyph on the collapsed parent's count. */
    val hasRunningDescendant: Boolean get() = children.any { it.indicator == AgentIndicator.Running || it.hasRunningDescendant }
}

data class AgentSection(
    val key: String,
    val title: String,
    val rows: List<AgentRow>,
)

/** One line of the sidebar's tree: the row and how many parents it sits under (0 for a chat of its own). */
data class NestedRow(val row: AgentRow, val depth: Int)

/**
 * What this device knows about agents beyond the public API: pins, read markers, which agents were launched here,
 * snoozes, and where their pull requests stand as last read from GitHub.
 */
data class LocalAgentState(
    val pinnedIds: Set<String> = emptySet(),
    /** agentId -> updatedAt (epoch millis) at the moment the user last opened it. */
    val readMarkers: Map<String, Long> = emptyMap(),
    val launchedHereIds: Set<String> = emptySet(),
    /** prUrl (as the API reports it) -> state; a pull request GitHub would not or has not yet answered for is absent. */
    val pullRequests: Map<String, PullRequestState> = emptyMap(),
    /** agentId -> epoch millis the snooze lifts; [SnoozeDuration.FOREVER] stays quiet until unsnoozed. */
    val snoozedUntil: Map<String, Long> = emptyMap(),
    /** agentId -> epoch millis the user snoozed it; later agent updates do not move this. */
    val snoozedAt: Map<String, Long> = emptyMap(),
) {
    fun isSnoozed(agentId: String, nowMillis: Long): Boolean {
        val until = snoozedUntil[agentId] ?: return false
        return until == SnoozeDuration.FOREVER || until > nowMillis
    }

    fun quietIds(nowMillis: Long): Set<String> = snoozedUntil.keys.filter { isSnoozed(it, nowMillis) }.toSet()

    /** The soonest timed snooze that will expire after [nowMillis], or null when none is pending. */
    fun nextSnoozeExpiry(nowMillis: Long): Long? =
        snoozedUntil.values.filter { it in (nowMillis + 1) until SnoozeDuration.FOREVER }.minOrNull()
}

/**
 * Pure grouping / filtering / sorting of agents. Everything the Customize sheet and the sidebar search field
 * can do is expressed here so it can be unit-tested without Android. Search is a find-in-rail: [organize]
 * takes [query], [recentRows] does not.
 */
object AgentListOrganizer {

    fun indicatorFor(agent: Agent, local: LocalAgentState, nowMillis: Long = AppClock.now()): AgentIndicator = when {
        agent.isArchived -> AgentIndicator.Archived
        local.isSnoozed(agent.id, nowMillis) -> AgentIndicator.Snoozed
        agent.isRunning -> AgentIndicator.Running
        agent.isError -> AgentIndicator.Error
        isUnread(agent, local, nowMillis) -> AgentIndicator.Unread
        else -> AgentIndicator.Read
    }

    fun isUnread(agent: Agent, local: LocalAgentState, nowMillis: Long = AppClock.now()): Boolean {
        if (agent.isArchived || agent.isRunning || local.isSnoozed(agent.id, nowMillis)) return false
        val marker = local.readMarkers[agent.id] ?: return true
        return agent.updatedAtMillis > marker
    }

    fun toRow(agent: Agent, local: LocalAgentState, nowMillis: Long = AppClock.now()): AgentRow = AgentRow(
        agent = agent,
        indicator = indicatorFor(agent, local, nowMillis),
        isPinned = agent.id in local.pinnedIds,
        isUnread = isUnread(agent, local, nowMillis),
        launchedFromThisDevice = agent.id in local.launchedHereIds,
        pullRequest = agent.prUrl?.let { local.pullRequests[it] },
        isSnoozed = local.isSnoozed(agent.id, nowMillis),
        snoozedAtMillis = local.snoozedAt[agent.id]?.takeIf { local.isSnoozed(agent.id, nowMillis) },
    )

    /**
     * The status a snoozed chat still is — running, read, and so on — so the default Status filter keeps it
     * visible. [AgentIndicator.Snoozed] is the glyph, not a hiding place.
     */
    fun listedAs(row: AgentRow): AgentIndicator = when {
        row.agent.isArchived -> AgentIndicator.Archived
        row.indicator != AgentIndicator.Snoozed -> row.indicator
        row.agent.isRunning -> AgentIndicator.Running
        row.agent.isError -> AgentIndicator.Error
        row.isUnread -> AgentIndicator.Unread
        else -> AgentIndicator.Read
    }

    fun matchesFilters(row: AgentRow, prefs: ListPreferences): Boolean {
        val agent = row.agent
        prefs.repos?.let { repos -> if (agent.repoSlug !in repos) return false }

        val listed = listedAs(row)
        val statusOk = when (listed) {
            AgentIndicator.Running -> StatusFilter.Running in prefs.statuses
            AgentIndicator.Unread -> StatusFilter.Unread in prefs.statuses
            AgentIndicator.Error -> StatusFilter.Error in prefs.statuses
            AgentIndicator.Read -> StatusFilter.Read in prefs.statuses
            AgentIndicator.Archived -> StatusFilter.Archived in prefs.statuses
            AgentIndicator.Snoozed -> false
        } || (row.isSnoozed && StatusFilter.Snoozed in prefs.statuses)
        if (!statusOk) return false

        // A pull request whose state is not known (not read yet, or a private repository without a GitHub token) is
        // hidden only when every state is unchecked: whichever state it is in, the user has not asked to hide it.
        val gitOk = when {
            !agent.hasPullRequest -> GitFilter.NoPullRequest in prefs.git
            row.pullRequest != null -> GitFilter.of(row.pullRequest) in prefs.git
            else -> GitFilter.pullRequestStates.any { it in prefs.git }
        }
        if (!gitOk) return false

        // One exclusive bucket per page, so either page's checkboxes can be picked apart on their own: where the chat
        // was started from, and what it runs on. A chat launched from this app is the account's API chat like any
        // other; the launch this device remembers is what makes it "This device" rather than "API".
        val source = if (row.launchedFromThisDevice) SourceFilter.ThisDevice else SourceFilter.of(agent.source)
        if (source !in prefs.sources) return false

        return EnvironmentFilter.of(agent.envType) in prefs.environments
    }

    fun matchesQuery(agent: Agent, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return agent.name.contains(q, ignoreCase = true) ||
            agent.repoSlug?.contains(q, ignoreCase = true) == true ||
            agent.branchName?.contains(q, ignoreCase = true) == true ||
            agent.summary?.contains(q, ignoreCase = true) == true
    }

    fun recencyMillis(row: AgentRow): Long = row.snoozedAtMillis ?: row.agent.updatedAtMillis

    fun sort(rows: List<AgentRow>, order: SortOrder): List<AgentRow> = when (order) {
        SortOrder.Updated -> rows.sortedByDescending { recencyMillis(it) }
        SortOrder.Created -> rows.sortedByDescending { it.agent.createdAtMillis }
        SortOrder.Name -> rows.sortedBy { it.agent.name.lowercase() }
    }

    fun organize(
        agents: List<Agent>,
        prefs: ListPreferences,
        local: LocalAgentState,
        query: String = "",
        nowMillis: Long = AppClock.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AgentSection> {
        val rows = agents
            .filter { matchesQuery(it, query) }
            .map { toRow(it, local, nowMillis) }
            .filter { matchesFilters(it, prefs) }
        val sorted = nest(sort(rows, prefs.sortOrder))

        // Projects lead, as they do in the official apps' navigation; a pinned Project is listed there, not twice.
        val projects = sorted.filter { it.agent.isProjectRoot }
        val pinned = sorted.filter { it.isPinned && !it.agent.isProjectRoot }
        val rest = sorted.filterNot { it.isPinned || it.agent.isProjectRoot }
        val sections = mutableListOf<AgentSection>()
        if (projects.isNotEmpty()) sections += AgentSection(PROJECTS_KEY, "Projects", projects)
        if (pinned.isNotEmpty()) sections += AgentSection(PINNED_KEY, "Pinned", pinned)

        when (prefs.groupBy) {
            GroupBy.None -> if (rest.isNotEmpty()) sections += AgentSection("all", if (pinned.isEmpty() && projects.isEmpty()) "Agents" else "Others", rest)
            GroupBy.Date -> sections += groupByDate(rest, nowMillis, zone)
            GroupBy.Repo -> sections += rest
                .groupBy { it.agent.repoSlug ?: "" }
                .toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
                .map { (slug, list) -> AgentSection("repo:$slug", slug.ifEmpty { "No repository" }, list) }
            GroupBy.Status -> sections += AgentIndicator.entries
                .mapNotNull { indicator ->
                    val list = rest.filter { it.indicator == indicator }
                    if (list.isEmpty()) null else AgentSection("status:${indicator.name}", indicator.title(), list)
                }
        }
        return sections
    }

    /**
     * The New Chat pane's recent list: Chats filters apply, sidebar search does not. Pinned chats are included
     * once, newest first, matching the home-screen widget's Recent mode. Built from [organize] rather than from the
     * agents again, so the two surfaces can never disagree about which chats the filters let through.
     */
    fun recentRows(
        agents: List<Agent>,
        prefs: ListPreferences,
        local: LocalAgentState,
        nowMillis: Long = AppClock.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AgentRow> = recentRows(organize(agents, prefs, local, query = "", nowMillis = nowMillis, zone = zone))

    /** [recentRows] for sections already organized without a search query: every row once, nested ones included, newest first. */
    fun recentRows(sections: List<AgentSection>): List<AgentRow> =
        sections.flatMap { it.rows }.flatMap { listOf(it) + it.descendants() }.distinctBy { it.agent.id }.sortedByDescending { recencyMillis(it) }

    /**
     * Nests each row under its parent chat when the parent is listed too (see [Agent.parent]), the way the Agents
     * Window hangs a Project's workers, side chats and subagents under the chat they belong to. A row whose parent
     * is not in [rows] — filtered out, archived, or beyond the listing window — stands on its own, as does a pinned
     * row: a pin is the user's word that the chat belongs in the Pinned group, not under something. Returns the
     * top-level rows in [rows]' order, each with its children in that order too (and theirs, and so on). A cycle
     * in the parent links is never expected; should the account report one, its rows stand on their own rather
     * than vanish.
     */
    fun nest(rows: List<AgentRow>): List<AgentRow> {
        val ids = rows.mapTo(HashSet()) { it.agent.id }
        fun parentOf(row: AgentRow): String? = row.agent.parent?.id?.takeIf { !row.isPinned && it in ids && it != row.agent.id }
        val childrenOf = rows.filter { parentOf(it) != null }.groupBy { parentOf(it)!! }
        if (childrenOf.isEmpty()) return rows
        val placed = HashSet<String>()
        fun build(row: AgentRow): AgentRow {
            placed += row.agent.id
            val children = buildList { childrenOf[row.agent.id].orEmpty().forEach { if (it.agent.id !in placed) add(build(it)) } }
            return if (children.isEmpty()) row else row.copy(children = children)
        }
        val top = buildList { rows.forEach { if (parentOf(it) == null) add(build(it)) } }
        val stranded = buildList { rows.forEach { if (it.agent.id !in placed) add(build(it)) } }
        return top + stranded
    }

    /**
     * A section's rows as the sidebar lists them: each top-level row, then — while its id is in [expandedIds] — its
     * children beneath it one level deeper, and theirs in turn. A parent not in [expandedIds] stands for its subtree.
     */
    fun flatten(rows: List<AgentRow>, expandedIds: Set<String>): List<NestedRow> = buildList {
        fun place(row: AgentRow, depth: Int) {
            add(NestedRow(row, depth))
            if (row.agent.id in expandedIds) row.children.forEach { place(it, depth + 1) }
        }
        rows.forEach { place(it, 0) }
    }

    /** The section key of the Projects group: the Project chats, ahead of everything else. */
    const val PROJECTS_KEY = "projects"

    /** The section key of the Pinned group. */
    const val PINNED_KEY = "pinned"

    private fun AgentIndicator.title(): String = when (this) {
        AgentIndicator.Running -> "Running"
        AgentIndicator.Unread -> "Unread"
        AgentIndicator.Error -> "Needs attention"
        AgentIndicator.Read -> "Completed"
        AgentIndicator.Archived -> "Archived"
        AgentIndicator.Snoozed -> "Snoozed"
    }

    fun groupByDate(rows: List<AgentRow>, nowMillis: Long, zone: ZoneId): List<AgentSection> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val buckets = linkedMapOf<String, MutableList<AgentRow>>()
        rows.forEach { row ->
            val label = dateBucket(Instant.ofEpochMilli(recencyMillis(row)).atZone(zone).toLocalDate(), today)
            buckets.getOrPut(label) { mutableListOf() } += row
        }
        return DATE_BUCKET_ORDER.filter { it in buckets }.map { AgentSection("date:$it", it, buckets.getValue(it)) } +
            buckets.keys.filterNot { it in DATE_BUCKET_ORDER }.map { AgentSection("date:$it", it, buckets.getValue(it)) }
    }

    private val DATE_BUCKET_ORDER = listOf("Today", "Yesterday", "This week", "Last week", "This month", "Older")

    fun dateBucket(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days <= 0 -> "Today"
            days == 1L -> "Yesterday"
            days < 7 -> "This week"
            days < 14 -> "Last week"
            date.year == today.year && date.month == today.month -> "This month"
            else -> "Older"
        }
    }
}
