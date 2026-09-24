package com.cursorforandroid.domain

import com.cursorforandroid.util.AppClock
import java.time.LocalDate
import java.time.ZoneId

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
    /**
     * How many chats the account says a Project holds (its membership and children answers), when the list does
     * not hold them all: the count the Project's row shows is the larger of this and the loaded subtree.
     */
    val memberCount: Int? = null,
    /**
     * A Project the root registry names and the list holds no row for yet (see [KnownRoot]): drawn from the
     * registry's name and look, listed after the loaded Projects, replaced by the row once it has been fetched.
     */
    val isStandIn: Boolean = false,
) {
    /** This row's children, their children and so on, depth first — what a collapsed parent stands for. */
    fun descendants(): List<AgentRow> = children.flatMap { listOf(it) + it.descendants() }

    /** What the Project's row counts: the chats loaded under it, or the account's own count when that is more. */
    val shownCount: Int get() = maxOf(descendants().size, memberCount ?: 0)

    /** A turn is going somewhere in the subtree: the working glyph on the collapsed parent's count. */
    val hasRunningDescendant: Boolean get() = children.any { it.indicator == AgentIndicator.Running || it.hasRunningDescendant }

    /** The chats in this subtree with a turn going, this one included: what a Project's shortcut says is working. */
    val workingCount: Int get() = (if (indicator == AgentIndicator.Running) 1 else 0) + children.sumOf { it.workingCount }
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
    /** The chats this phone has started or opened (bounded; see `PreferencesStore.markTouchedHere`). */
    val touchedHereIds: Set<String> = emptySet(),
    /**
     * Settings › "Unread only for chats from this phone": a chat not in [touchedHereIds] never reads as unread —
     * no dot, no part of a folded group's, a Project's or the widget's unread marks. Display only: nothing is marked
     * read or unread anywhere because of it. Off here, as the neutral state; the setting itself defaults on.
     */
    val unreadOnlyTouchedHere: Boolean = false,
    /**
     * The Projects as the reader arranged them on the New Chat page, first to last; empty until they have been. The
     * sidebar's Projects group and the New Chat shortcuts both follow it (see [AgentListOrganizer.arranged]).
     */
    val projectOrder: List<String> = emptyList(),
) {
    /** Whether [agentId] may read as unread on this phone at all; its read marker decides whether it does. */
    fun mayShowUnread(agentId: String): Boolean = !unreadOnlyTouchedHere || agentId in touchedHereIds

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
        if (!local.mayShowUnread(agent.id)) return false
        val marker = local.readMarkers[agent.id] ?: return true
        return agent.listedAtMillis > marker
    }

    /**
     * Whether a pin means anything for [agent]. A Project's does not: `VuC` keeps a pinned Project in Projects, which
     * already lead the list in the reader's own order ([LocalAgentState.projectOrder]), so no menu offers it and a pin
     * one carries from elsewhere is read as none.
     */
    fun canPin(agent: Agent): Boolean = !agent.isProjectRoot

    fun toRow(agent: Agent, local: LocalAgentState, nowMillis: Long = AppClock.now()): AgentRow = AgentRow(
        agent = agent,
        indicator = indicatorFor(agent, local, nowMillis),
        isPinned = agent.id in local.pinnedIds && canPin(agent),
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

    /**
     * Whether a top-level row is listed under [prefs] — the desktop's `Nlc`: a pinned row passes every filter (a pin
     * is the user's word that the chat is shown, `o?.has(C.id)`); an archived row is listed only under the Archived
     * filter; every chat of the account's own answers to [matchesFilters] in full. A Project's row (`kf`) answers
     * the archive filter alone: the desktop exempts a Project from its pull-request filter (`g && !kf(C) &&
     * !d.has($uC(C))`) because a Project has many pull requests under it and no one state of its own, and the
     * Chats filters here are all of that kind — a chat's status, repository, source, environment — none of which
     * says anything about a body of work with many chats under it. A child never comes here: it goes with its
     * parent's row (see [organize]).
     */
    fun isListed(row: AgentRow, prefs: ListPreferences): Boolean = when {
        row.isPinned -> true
        row.agent.isProjectRoot -> passesArchive(row, prefs)
        else -> matchesFilters(row, prefs)
    }

    /**
     * The one filter a child answers to (`Nlc` on its row): an archived chat is listed only while Archived is checked.
     */
    fun passesArchive(row: AgentRow, prefs: ListPreferences): Boolean = !row.agent.isArchived || StatusFilter.Archived in prefs.statuses

    /** The Chats filters, in full, as a chat of the account's own answers to them; see [isListed] for what does not. */
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

    /**
     * The time a row is ordered and bucketed by: its snooze, else the desktop's `lastUpdatedAt` (see [Agent.listedAtMillis]) —
     * the record's last message activity, never the public row's `updatedAt` once the record has been read.
     */
    fun recencyMillis(row: AgentRow): Long = row.snoozedAtMillis ?: row.agent.listedAtMillis

    /** The desktop's `rUm` for the default order: last activity descending, id ascending; the other orders are this app's. */
    fun sort(rows: List<AgentRow>, order: SortOrder): List<AgentRow> = when (order) {
        SortOrder.Updated -> rows.sortedWith(compareByDescending<AgentRow> { recencyMillis(it) }.thenBy { it.agent.id })
        SortOrder.Created -> rows.sortedWith(compareByDescending<AgentRow> { it.agent.createdAtMillis }.thenBy { it.agent.id })
        SortOrder.Name -> rows.sortedWith(compareBy<AgentRow> { it.agent.name.lowercase() }.thenBy { it.agent.id })
    }

    /**
     * The sidebar's sections, composed the way the desktop Agents Window composes its list (see [AgentsWindowList]):
     *
     *  1. `mQa` / `PJr`: a row with a parent link is a child, whatever the parent is — a Project, a Multitask chat
     *     that spawned it, a chat it branched from — and is drawn under the parent's row and nowhere else; only rows
     *     without a parent link are top level. A child whose parent has no row is not drawn until the parent is
     *     (the repository fetches it by id, as the desktop hydrates it); a child of a parent the server refused
     *     stands under a stand-in that says so ([unavailableProjects]), so the chat stays reachable.
     *  2. `Nlc`: the filters pick the top-level rows — a pin passes them all, a Project skips the git filter — and a
     *     child goes with its parent, put away only when archived (as any archived chat is). The search finds a chat
     *     wherever it sits in the tree, and shows it under its parent; a match on the parent keeps its subtree.
     *  3. `kf`: the top-level rows the record flags are the Projects section, sorted by activity; the registry's roots
     *     the list holds no row for stand in after them ([knownRoots]).
     *  4. `VuC`: the pinned top-level non-Project rows are the Pinned section; a pinned Project stays in Projects, a
     *     pinned child under its parent — or, when the parent's row is not drawn, in Pinned, since a pin is the
     *     user's word that the chat is shown.
     *  5. The rest are grouped by [prefs] — `f3v`'s time sections by default — and every section is in `rUm`'s order.
     */
    fun organize(
        agents: List<Agent>,
        prefs: ListPreferences,
        local: LocalAgentState,
        query: String = "",
        nowMillis: Long = AppClock.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        unavailableProjects: Set<String> = emptySet(),
        /**
         * Every Project root the registry knows (see [KnownRoot]): a root the list holds no row for is listed all the
         * same, as a stand-in carrying the registry's name and look, so the Projects group is the account's Projects
         * and not the ones the loaded pages happen to hold (the desktop fetches such roots by id, `_fetchProjectRootCloudAgents`).
         */
        knownRoots: Collection<KnownRoot> = emptyList(),
        /** The account's member count per Project, from its membership answers, for the rows' counts. */
        memberCounts: Map<String, Int> = emptyMap(),
    ): List<AgentSection> {
        val known = agents.mapTo(HashSet(agents.size)) { it.id }
        val standIns = knownRoots.filter { it.id !in known && it.id.isNotBlank() }.map { rootStandIn(it, nowMillis) }
        val rows = sort((agents + standIns).map { toRow(it, local, nowMillis) }, prefs.sortOrder)
        // PJr / mQa: children and top level, by the parent link alone (ZOl: a link closing a loop is no link).
        val links = AgentsWindowList.parentLinks(rows.map { it.agent })
        val (children, topLevel) = rows.partition { it.agent.id in links }
        // Nlc on the top level; a child goes with its parent, put away only when archived.
        val listed = topLevel.filter { isListed(it, prefs) }
        val shownChildren = children.filter { passesArchive(it, prefs) }
        val standInIds = standIns.mapTo(HashSet()) { it.id }
        val nested = nest(listed, shownChildren, links)
        // A pinned child stays under its parent (VuC pins top-level headers only); when the parent's row is not
        // drawn — not loaded, archived, filtered out — the pin is the user's word that the chat is shown, so it
        // stands in Pinned rather than nowhere (the desktop never lets a pinned header's parent go unloaded).
        val drawn = HashSet<String>().also { fun walk(row: AgentRow) { it += row.agent.id; row.children.forEach(::walk) }; nested.forEach(::walk) }
        val pinnedOrphans = nest(shownChildren.filter { it.isPinned && it.agent.id !in drawn }, shownChildren.filter { it.agent.id !in drawn && !it.isPinned }, links)
        val tree = (nested + pinnedOrphans).map { row ->
            val count = memberCounts[row.agent.id]
            when {
                row.agent.id in standInIds -> row.copy(isPlaceholder = row.agent.name == PLACEHOLDER_NAME, isStandIn = true, memberCount = count)
                count != null && row.agent.isProjectRoot -> row.copy(memberCount = count)
                else -> row
            }
        } + placeholders(shownChildren, known + standInIds, nowMillis, unavailableProjects)
        val sorted = tree.mapNotNull { it.matching(query) }

        // kf: Projects lead; the loaded ones in the list's order, the registry's stand-ins after them by name — or as
        // the reader arranged them, once they have.
        val projects = arranged(
            sorted.filter { it.agent.isProjectRoot }.sortedWith(compareBy<AgentRow> { it.isStandIn }.thenBy { if (it.isStandIn) it.agent.name.lowercase() else "" }),
            local.projectOrder,
        )
        // VuC: the pinned top-level rows that are not Projects.
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
     * [recentRows] for sections already organized without a search query: the account's own chats once, newest first.
     * Nothing of a Project is among them — not its coordinator, not its workers, side chats or subagents, pinned or
     * not: they belong to the Project's own surface, and the recents, like the widget, are a primary surface.
     */
    fun recentRows(sections: List<AgentSection>): List<AgentRow> =
        sections.flatMap { it.rows }.filterNot { it.isPlaceholder || it.agent.isProjectScopedByEvidence }.distinctBy { it.agent.id }.sortedByDescending { recencyMillis(it) }

    /**
     * The New Chat pane's Project shortcuts, from sections organized without a search query: the Projects group in its
     * own order, less the "Project (loading)" stand-ins — a registry root named and drawn from its last record stays,
     * since it opens by its id like any Project.
     */
    fun projectRows(sections: List<AgentSection>): List<AgentRow> =
        sections.firstOrNull { it.key == PROJECTS_KEY }?.rows.orEmpty().filterNot { it.isPlaceholder }

    /**
     * [projects] in the order the reader arranged them ([order], ids first to last; see [LocalAgentState.projectOrder]).
     * A Project the order does not name — made since, or never arranged — keeps its place ahead of the arranged ones,
     * in the order it came in, so a new Project is never pushed behind the sidebar's "Show N more"; a registry
     * stand-in the order does not name stays last. An empty order leaves [projects] as they are.
     */
    fun arranged(projects: List<AgentRow>, order: List<String>): List<AgentRow> {
        if (order.isEmpty()) return projects
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
        return projects.sortedBy { rank[it.agent.id] ?: if (it.isStandIn) Int.MAX_VALUE else -1 }
    }

    /**
     * The desktop's tree: each of the [primary] (top-level) rows with the rows of [nested] that hang off it beneath
     * it, and theirs, and so on, in the lists' order — `row-with-children` under any parent, a Project or not. A
     * nested row is placed under its parent or not at all: one whose parent is neither top level nor placed — a
     * chat whose parent is archived, filtered out or not loaded — is left out here rather than stranded among the
     * top-level rows (the desktop draws no such row either), as is one that names itself or sits on a cycle of
     * parent links. A pinned child is nested like any other (`VuC` pins top-level headers only).
     */
    fun nest(primary: List<AgentRow>, nested: List<AgentRow>, links: Map<String, String> = AgentsWindowList.parentLinks((primary + nested).map { it.agent })): List<AgentRow> {
        if (nested.isEmpty()) return primary
        val childrenOf = nested.filter { it.agent.id in links }.groupBy { links.getValue(it.agent.id) }
        val placed = HashSet<String>()
        fun build(row: AgentRow): AgentRow {
            placed += row.agent.id
            val children = buildList { childrenOf[row.agent.id].orEmpty().forEach { if (it.agent.id !in placed) add(build(it)) } }
            return if (children.isEmpty()) row else row.copy(children = children)
        }
        return primary.map(::build)
    }

    /** [nest] over rows that are all top level or children of each other, for a list built without the filters. */
    fun nest(rows: List<AgentRow>): List<AgentRow> {
        val links = AgentsWindowList.parentLinks(rows.map { it.agent })
        val (children, topLevel) = rows.partition { it.agent.id in links }
        return nest(topLevel, children, links)
    }

    /**
     * Stand-ins for the parents the server refused to give ([unavailable]: a deleted chat, another account's) that
     * rows of [nested] still name — so those rows stay reachable, under a row that says the parent is unavailable
     * rather than vanishing for good. A parent merely not loaded yet gets no stand-in: its rows wait unseen until
     * the repository's fetch by id lands its row, as they do in the desktop.
     */
    private fun placeholders(nested: List<AgentRow>, known: Set<String>, nowMillis: Long, unavailable: Set<String>): List<AgentRow> {
        if (unavailable.isEmpty()) return emptyList()
        val orphans = nested.filter { row ->
            val parent = row.agent.parent ?: return@filter false
            parent.id != row.agent.id && parent.id !in known && parent.id in unavailable
        }
        if (orphans.isEmpty()) return emptyList()
        return orphans.groupBy { it.agent.parent!!.id }.map { (parentId, rows) -> placeholder(parentId, rows, nowMillis, unavailable = true) }
    }

    /**
     * A stand-in row for Project [projectId], which the list has not loaded, over the [workers] that belong to it.
     * It reads "Project (loading)" — or "Project (unavailable)" once the server has refused the row ([unavailable]),
     * rather than load for ever — and is as recent as its newest worker, so it sits where the Project will.
     */
    fun placeholder(projectId: String, workers: List<AgentRow>, nowMillis: Long = AppClock.now(), unavailable: Boolean = false): AgentRow {
        val newest = workers.maxOfOrNull { it.agent.listedAtMillis } ?: nowMillis
        val agent = Agent(
            id = projectId,
            name = if (unavailable) UNAVAILABLE_NAME else PLACEHOLDER_NAME,
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
        )
        return AgentRow(agent = agent, indicator = AgentIndicator.Read, isPinned = false, isUnread = false, launchedFromThisDevice = false, children = workers, isPlaceholder = true)
    }

    /**
     * The row of a Project the registry knows but the list holds no row for (see [organize]): named and drawn as its
     * record last showed it, "Project (loading)" until a record has been seen, archived when the record said so, and
     * as recent as the registry last saw it. It opens the Project's view like any Project's row, by its id.
     */
    fun rootStandIn(root: KnownRoot, nowMillis: Long = AppClock.now()): Agent = Agent(
        id = root.id,
        name = root.name?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER_NAME,
        lifecycle = if (root.archived) AgentLifecycle.ARCHIVED else AgentLifecycle.UNKNOWN,
        runStatus = null,
        envType = EnvType.UNKNOWN,
        envName = null,
        url = "https://cursor.com/agents/${root.id}",
        createdAtMillis = root.lastSeenMillis.takeIf { it > 0 } ?: nowMillis,
        updatedAtMillis = root.lastSeenMillis.takeIf { it > 0 } ?: nowMillis,
        latestRunId = null,
        repoUrl = null,
        startingRef = null,
        isProject = true,
        projectAppearance = root.appearance,
        scopeSignal = root.signal,
    )

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

    /** What a [placeholder] row is called while the parent it stands for is being fetched (the registry's stand-ins use it before a name is known). */
    const val PLACEHOLDER_NAME = "Project (loading)"

    /** What a [placeholder] row is called once the server has refused the parent's row (a deleted chat, another account's). */
    const val UNAVAILABLE_NAME = "Parent chat (unavailable)"

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

    /** `pNg` / `f3v`: the rows in the desktop's time sections, in their order, empty ones left out. */
    fun groupByDate(rows: List<AgentRow>, nowMillis: Long, zone: ZoneId): List<AgentSection> {
        val buckets = rows.groupBy { AgentsWindowList.timeBucket(recencyMillis(it), nowMillis, zone) }
        return AgentsWindowList.TimeBucket.entries.mapNotNull { bucket -> buckets[bucket]?.let { AgentSection("date:${bucket.label}", bucket.label, it) } }
    }

    /** The desktop's time section for a row last active on [date], seen from [today] (see [AgentsWindowList.timeBucket]). */
    fun dateBucket(date: LocalDate, today: LocalDate): String {
        val zone = ZoneId.of("UTC")
        val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return AgentsWindowList.timeBucket(at, now, zone).label
    }
}
