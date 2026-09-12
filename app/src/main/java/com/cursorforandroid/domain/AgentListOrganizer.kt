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
    /**
     * A stand-in for a Project the list has not loaded yet, holding the workers that name it (see
     * [AgentListOrganizer.placeholder]): drawn as "Project (loading)", with no row actions, until the row itself
     * has been fetched and takes its place.
     */
    val isPlaceholder: Boolean = false,
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

    /**
     * The sidebar's sections. Classify, then nest: every row is placed by its own [Agent.scope] — a Project's
     * worker, side chat or subagent belongs inside its parent's tree and nowhere else, so it is never listed among
     * the primary rows whatever has become of its parent (filtered out, archived, searched away, not loaded yet).
     * A pinned child is the one exception, by the user's word. A child whose Project the list does not hold at all
     * sits under a stand-in row for that Project (see [placeholder]) until the row has been fetched.
     *
     * The filters apply to every row; the search finds a chat wherever it sits in the tree, and shows it under its
     * parent — a match on the parent keeps its whole subtree.
     */
    fun organize(
        agents: List<Agent>,
        prefs: ListPreferences,
        local: LocalAgentState,
        query: String = "",
        nowMillis: Long = AppClock.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AgentSection> {
        val rows = sort(agents.map { toRow(it, local, nowMillis) }.filter { matchesFilters(it, prefs) }, prefs.sortOrder)
        val (nested, primary) = rows.partition { it.agent.isProjectChild && !it.isPinned }
        val known = agents.mapTo(HashSet(agents.size)) { it.id }
        val tree = nest(primary, nested) + placeholders(nested, known, nowMillis)
        val sorted = tree.mapNotNull { it.matching(query) }

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

    /**
     * [recentRows] for sections already organized without a search query: the primary rows once, newest first. A
     * Project's workers, side chats and subagents are not among them — they belong to the Project's own surface,
     * and the recents, like the widget, are a primary surface (a pinned child is listed, as it is in the sidebar).
     */
    fun recentRows(sections: List<AgentSection>): List<AgentRow> =
        sections.flatMap { it.rows }.filterNot { it.isPlaceholder }.distinctBy { it.agent.id }.sortedByDescending { recencyMillis(it) }

    /**
     * Nests each row under its parent chat when the parent is listed too (see [Agent.parent]), the way the Agents
     * Window hangs a Project's workers, side chats and subagents under the chat they belong to. A row whose parent
     * is not in [rows] — filtered out, archived, or beyond the listing window — stands on its own, as does a pinned
     * row: a pin is the user's word that the chat belongs in the Pinned group, not under something. Returns the
     * top-level rows in [rows]' order, each with its children in that order too (and theirs, and so on). A cycle
     * in the parent links is never expected; should the account report one, its rows stand on their own rather
     * than vanish.
     *
     * This is the tree of rows that are all listed on their own account; [organize] goes through [nest] with the
     * project-scoped rows set apart, so that those never stand on their own.
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
     * The tree of the [primary] rows, each with the rows of [nested] that hang off it beneath it (and theirs, and so
     * on), in the lists' order. A nested row is placed under its parent or not at all: one whose parent is neither
     * primary nor placed — a worker whose Project is archived, filtered out or not loaded — is left out here rather
     * than stranded among the primary rows, which is what let workers leak into the chat list. A nested row that
     * names itself, or sits on a cycle of parent links, is left out the same way.
     */
    fun nest(primary: List<AgentRow>, nested: List<AgentRow>): List<AgentRow> {
        if (nested.isEmpty()) return primary
        val childrenOf = nested.filter { it.agent.parent != null && it.agent.parent.id != it.agent.id }.groupBy { it.agent.parent!!.id }
        val placed = HashSet<String>()
        fun build(row: AgentRow): AgentRow {
            placed += row.agent.id
            val children = buildList { childrenOf[row.agent.id].orEmpty().forEach { if (it.agent.id !in placed) add(build(it)) } }
            return if (children.isEmpty()) row else row.copy(children = children)
        }
        return primary.map(::build)
    }

    /**
     * The Projects the list does not hold at all, each standing in for the workers that name it as their manager:
     * a Project chat beyond the listing window, or not yet fetched, whose workers must not go into the primary rows
     * meanwhile. Side chats and subagents of an unloaded chat wait unseen instead: their parent is not necessarily a
     * Project. Rows already [placed] under a listed parent need no stand-in.
     */
    private fun placeholders(nested: List<AgentRow>, known: Set<String>, nowMillis: Long): List<AgentRow> {
        val orphans = nested.filter { row ->
            val parent = row.agent.parent ?: return@filter false
            parent.kind == AgentParentKind.PROJECT_WORKER && parent.id != row.agent.id && parent.id !in known
        }
        if (orphans.isEmpty()) return emptyList()
        return orphans.groupBy { it.agent.parent!!.id }.map { (projectId, workers) -> placeholder(projectId, workers, nowMillis) }
    }

    /**
     * A stand-in row for Project [projectId], which the list has not loaded, over the [workers] that belong to it.
     * It reads "Project (loading)" and is as recent as its newest worker, so it sits where the Project will.
     */
    fun placeholder(projectId: String, workers: List<AgentRow>, nowMillis: Long = AppClock.now()): AgentRow {
        val newest = workers.maxOfOrNull { it.agent.updatedAtMillis } ?: nowMillis
        val agent = Agent(
            id = projectId,
            name = PLACEHOLDER_NAME,
            lifecycle = AgentLifecycle.UNKNOWN,
            runStatus = null,
            envType = EnvType.UNKNOWN,
            envName = null,
            url = "https://cursor.com/agents/$projectId",
            createdAtMillis = newest,
            updatedAtMillis = newest,
            latestRunId = null,
            repoUrl = null,
            startingRef = null,
            knownScope = AgentScope.PROJECT_ROOT,
        )
        return AgentRow(agent = agent, indicator = AgentIndicator.Read, isPinned = false, isUnread = false, launchedFromThisDevice = false, children = workers, isPlaceholder = true)
    }

    /**
     * The ids of the parents that [agents] name but do not hold — Projects and chats beyond the listing window whose
     * workers, side chats or subagents are in the list. What the repository fetches by id so that each of them can
     * take its place at the head of its tree.
     */
    fun missingParentIds(agents: List<Agent>): Set<String> {
        val known = agents.mapTo(HashSet(agents.size)) { it.id }
        return agents.mapNotNullTo(LinkedHashSet()) { agent -> agent.parent?.id?.takeIf { it != agent.id && it !in known } }
    }

    /**
     * The row when it or a chat under it matches [query] (blank matches everything), with its children narrowed to
     * the matches unless the row itself matched — a search finds a worker under its Project, and a Project by its
     * name with everything under it; null when nothing in the subtree matches.
     */
    private fun AgentRow.matching(query: String): AgentRow? {
        if (query.isBlank()) return this
        if (!isPlaceholder && matchesQuery(agent, query)) return this
        val kept = children.mapNotNull { it.matching(query) }
        return if (kept.isEmpty()) null else copy(children = kept)
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

    /** What a [placeholder] row is called until the Project it stands for has been fetched. */
    const val PLACEHOLDER_NAME = "Project (loading)"

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
