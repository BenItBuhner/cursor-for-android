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
)

data class AgentSection(
    val key: String,
    val title: String,
    val rows: List<AgentRow>,
)

/**
 * What this device knows about agents beyond the public API: pins, read markers, which agents were launched here,
 * and where their pull requests stand as last read from GitHub.
 */
data class LocalAgentState(
    val pinnedIds: Set<String> = emptySet(),
    /** agentId -> updatedAt (epoch millis) at the moment the user last opened it. */
    val readMarkers: Map<String, Long> = emptyMap(),
    val launchedHereIds: Set<String> = emptySet(),
    /** prUrl (as the API reports it) -> state; a pull request GitHub would not or has not yet answered for is absent. */
    val pullRequests: Map<String, PullRequestState> = emptyMap(),
)

/**
 * Pure grouping / filtering / sorting of agents. Everything the Customize sheet and the sidebar search field
 * can do is expressed here so it can be unit-tested without Android. Search is a find-in-rail: [organize]
 * takes [query], [recentRows] does not.
 */
object AgentListOrganizer {

    fun indicatorFor(agent: Agent, local: LocalAgentState): AgentIndicator = when {
        agent.isArchived -> AgentIndicator.Archived
        agent.isRunning -> AgentIndicator.Running
        agent.isError -> AgentIndicator.Error
        isUnread(agent, local) -> AgentIndicator.Unread
        else -> AgentIndicator.Read
    }

    fun isUnread(agent: Agent, local: LocalAgentState): Boolean {
        if (agent.isArchived || agent.isRunning) return false
        val marker = local.readMarkers[agent.id] ?: return true
        return agent.updatedAtMillis > marker
    }

    fun toRow(agent: Agent, local: LocalAgentState): AgentRow = AgentRow(
        agent = agent,
        indicator = indicatorFor(agent, local),
        isPinned = agent.id in local.pinnedIds,
        isUnread = isUnread(agent, local),
        launchedFromThisDevice = agent.id in local.launchedHereIds,
        pullRequest = agent.prUrl?.let { local.pullRequests[it] },
    )

    fun matchesFilters(row: AgentRow, prefs: ListPreferences): Boolean {
        val agent = row.agent
        prefs.repos?.let { repos -> if (agent.repoSlug !in repos) return false }

        val statusOk = when (row.indicator) {
            AgentIndicator.Running -> StatusFilter.Running in prefs.statuses
            AgentIndicator.Unread -> StatusFilter.Unread in prefs.statuses
            AgentIndicator.Error -> StatusFilter.Error in prefs.statuses
            AgentIndicator.Read -> StatusFilter.Read in prefs.statuses
            AgentIndicator.Archived -> StatusFilter.Archived in prefs.statuses
        }
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

    fun sort(rows: List<AgentRow>, order: SortOrder): List<AgentRow> = when (order) {
        SortOrder.Updated -> rows.sortedByDescending { it.agent.updatedAtMillis }
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
            .map { toRow(it, local) }
            .filter { matchesFilters(it, prefs) }
        val sorted = sort(rows, prefs.sortOrder)

        val pinned = sorted.filter { it.isPinned }
        val rest = sorted.filterNot { it.isPinned }
        val sections = mutableListOf<AgentSection>()
        if (pinned.isNotEmpty()) sections += AgentSection("pinned", "Pinned", pinned)

        when (prefs.groupBy) {
            GroupBy.None -> if (rest.isNotEmpty()) sections += AgentSection("all", if (pinned.isEmpty()) "Agents" else "Others", rest)
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

    /** [recentRows] for sections already organized without a search query: every row once, newest first. */
    fun recentRows(sections: List<AgentSection>): List<AgentRow> =
        sections.flatMap { it.rows }.distinctBy { it.agent.id }.sortedByDescending { it.agent.updatedAtMillis }

    private fun AgentIndicator.title(): String = when (this) {
        AgentIndicator.Running -> "Running"
        AgentIndicator.Unread -> "Unread"
        AgentIndicator.Error -> "Needs attention"
        AgentIndicator.Read -> "Completed"
        AgentIndicator.Archived -> "Archived"
    }

    fun groupByDate(rows: List<AgentRow>, nowMillis: Long, zone: ZoneId): List<AgentSection> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val buckets = linkedMapOf<String, MutableList<AgentRow>>()
        rows.forEach { row ->
            val label = dateBucket(Instant.ofEpochMilli(row.agent.updatedAtMillis).atZone(zone).toLocalDate(), today)
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
