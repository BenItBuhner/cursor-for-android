package com.cursorforandroid.domain

import java.time.Instant
import java.time.ZoneId

/**
 * The desktop Agents Window's list composition, replicated predicate for predicate from Cursor 3.20.21
 * (`workbench.glass.main.js`). The desktop's names are kept beside each rule so the two can be read against each
 * other; nothing here guesses — every rule reads the record's own fields, as the desktop does.
 *
 * What the desktop does with a cloud agent's record (`CloudAgentRepository._makeAgentHeader` and the header builder):
 *
 *  - `subagentParentId = cloudSubagentParent.parentAgentId?.trim() || sideChatInfo.parentBcId || managerAgentId`
 *    — the one parent link, in that order of precedence. A `MEMBERSHIP_CHANGED` stream event with a `manager_bc_id`
 *    and an adoption made in the window stamp `managerAgentId` and `subagentParentId` onto the listed record
 *    (`_stampListedCloudAgentManager`, which also sets `isProject` false); a `ListWorkersForManager` answer seeds a
 *    header with `managerAgentId` for a worker the list does not hold and fetches it by id (`_setProjectWorkerStatusSeed`,
 *    `hydrateWorker`), and patches only the status of a worker the list holds.
 *  - `isProject = projectMetadata !== undefined` (the message present at all; `startedAsNewProject` is never read).
 *
 * What the sidebar does with the headers (`$1v`, the sidebar model):
 *
 *  - `PJr(h)` / `iUm` / `UZ`: a header with a non-empty `subagentParentId` is a child. `mQa(headers)` drops every
 *    child: **only headers without a parent link are top level**, whatever the parent is (a Project, a Multitask
 *    chat that spawned cloud subagents, an ordinary chat with side chats), whether the parent is loaded, archived
 *    or pinned. A child is drawn under its parent's row (`row-with-children`) and nowhere else; a child whose parent
 *    has no row is not drawn (the desktop fetches Project roots the list did not return by id,
 *    `_fetchProjectRootCloudAgents`, and workers a membership names, so the parent row comes).
 *  - `kf(h)`: `(source local|cloud) && !subagentParentId && isProject` — the Project roots, the `__projects__`
 *    section, sorted by `MJr(a, "updated")`. A Project that is itself somebody's child is a child.
 *  - `VuC`: the Pinned section (`__pinned_agents__`) is the pinned ids among the **top-level, non-Project** headers,
 *    sorted the same way. A pinned Project stays in Projects; a pinned child stays under its parent.
 *  - `Nlc`: the filters. A pinned row passes them all (`o?.has(C.id)`); an archived row is listed only under the
 *    Archived filter; a Project row (`kf`) is exempt from the git filter only; a child goes with its parent's row.
 *  - `pNg` / `f3v`: the remaining top-level rows grouped by time — Today, Yesterday, Last 7 Days, Last 30 Days,
 *    Older — and `rUm` orders every section by `lastUpdatedAt` descending, id ascending.
 *  - `ee = [...metaAgents, projects, pinned, ...grouped]`: the section order. (`__cloud_meta_agents__`, the
 *    `CLOUD_META_AGENT`-sourced chats in an "Agents" section of their own, sits behind the `cloudMetaAgentsEnabled`
 *    policy; with it off they are ordinary top-level chats, which is how they are read here.)
 */
object AgentsWindowList {

    /**
     * The desktop's `subagentParentId` for a row: the one parent link, or null. [Agent.parent] is derived from the
     * record with the desktop's precedence (`BackgroundComposerApi.snapshot`), or stamped the way the desktop
     * stamps it (a membership answer, an action taken here, a coordinator's `create_agent` in default mode). A link
     * to itself is no link, as in `ZOl`.
     */
    fun subagentParentId(agent: Agent): String? = agent.parent?.id?.trim()?.takeIf { it.isNotEmpty() && it != agent.id }

    /** `PJr` / `iUm` / `UZ`: a header with a parent link is a child. */
    fun isChild(agent: Agent): Boolean = subagentParentId(agent) != null

    /** `mQa`: the top-level headers — everything that is not a child. */
    fun topLevel(agents: List<Agent>): List<Agent> = agents.filterNot(::isChild)

    /**
     * `ZOl`: the parent links of [agents], applied in the list's order, child id to parent id. A link to the chat
     * itself, or one that would close a cycle of links, is no link — the desktop refuses it when it builds the
     * topology, so no chat is lost to a loop: the row whose link is refused stands at the top level with the loop's
     * other rows under it.
     */
    fun parentLinks(agents: List<Agent>): Map<String, String> {
        val links = HashMap<String, String>()
        for (agent in agents) {
            val parentId = subagentParentId(agent) ?: continue
            if (agent.id in links) continue
            var cursor: String? = parentId
            val seen = HashSet<String>()
            var closesCycle = false
            while (cursor != null && cursor !in seen) {
                if (cursor == agent.id) {
                    closesCycle = true
                    break
                }
                seen += cursor
                cursor = links[cursor]
            }
            if (!closesCycle) links[agent.id] = parentId
        }
        return links
    }

    /** `kf`: a top-level header the record flags as a Project. */
    fun isProjectRoot(agent: Agent): Boolean = !isChild(agent) && agent.isProject

    /** `rUm`: `lastUpdatedAt` descending, id ascending. */
    val BY_UPDATED: Comparator<Agent> = compareByDescending<Agent> { it.updatedAtMillis }.thenBy { it.id }

    /** The time sections the desktop groups the remaining rows into (`time-buckets.js`), in their order. */
    enum class TimeBucket(val key: String, val label: String) {
        TODAY("today", "Today"),
        YESTERDAY("yesterday", "Yesterday"),
        LAST_7_DAYS("last_7_days", "Last 7 Days"),
        LAST_30_DAYS("last_30_days", "Last 30 Days"),
        OLDER("older", "Older"),
    }

    /**
     * `f3v({updatedAt, now})`: the same calendar day is Today, the previous calendar day Yesterday, then less than
     * seven days of elapsed time Last 7 Days, less than thirty Last 30 Days, Older beyond. The desktop reads the
     * calendar in the window's local time; [zone] is that here.
     */
    fun timeBucket(updatedAtMillis: Long, nowMillis: Long, zone: ZoneId): TimeBucket {
        val elapsedDays = (nowMillis - updatedAtMillis) / DAY_MS.toDouble()
        val then = Instant.ofEpochMilli(updatedAtMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when {
            then == today -> TimeBucket.TODAY
            then == today.minusDays(1) -> TimeBucket.YESTERDAY
            elapsedDays < 7 -> TimeBucket.LAST_7_DAYS
            elapsedDays < 30 -> TimeBucket.LAST_30_DAYS
            else -> TimeBucket.OLDER
        }
    }

    /** Where the desktop's rules put a row, and why, in the desktop's own terms. */
    data class Placement(val kind: Kind, val parentId: String?, val rule: String) {
        enum class Kind {
            /** `kf`: a top-level Project — the Projects section. */
            PROJECT,
            /** `mQa`: a top-level chat — Pinned or a time section. */
            TOP_LEVEL,
            /** `PJr`: a child — under its parent's row. */
            CHILD,
        }
    }

    /**
     * How the desktop's rules place [agent] among [loaded] (the ids the list holds) — the desktop rule that placed the
     * row, spelled out for the diagnostics export: which predicate matched, from which field or stamp the parent link
     * came, and where that leaves the row (the section, or the parent it hangs under, or that it is not drawn until
     * its parent is).
     */
    fun place(agent: Agent, loaded: Set<String>, pinned: Set<String>, archived: Set<String> = emptySet()): Placement {
        val parentId = subagentParentId(agent)
        if (parentId != null) {
            val via = parentLinkSource(agent)
            val where = when {
                parentId !in loaded -> "parent row not loaded: not drawn until the parent is fetched by id (the desktop hydrates it)"
                parentId in archived -> "parent archived: goes with the parent's row (Archived filter)"
                else -> "nested under the parent's row"
            }
            val projectNote = if (agent.isProject) "; projectMetadata present but kf needs no subagentParentId: a child, not a Project" else ""
            val pinNote = if (agent.id in pinned) "; pinned, but VuC pins only top-level headers: stays under the parent" else ""
            return Placement(Placement.Kind.CHILD, parentId, "PJr child of ${ProjectDiagnostics.tail(parentId)} via $via$projectNote$pinNote → $where")
        }
        if (agent.isProject) {
            val pinNote = if (agent.id in pinned) " (pinned: stays in Projects, not Pinned)" else ""
            return Placement(Placement.Kind.PROJECT, null, "kf top-level Project: no subagentParentId, projectMetadata present → Projects$pinNote")
        }
        val section = if (agent.id in pinned) "Pinned (VuC)" else "time section (f3v)"
        val recordNote = when {
            agent.record != null -> "record read, no parent link, no projectMetadata"
            agent.scopeSignal != null -> "no parent link"
            else -> "no account record read yet: the public API's row alone"
        }
        return Placement(Placement.Kind.TOP_LEVEL, null, "mQa top-level: $recordNote → $section")
    }

    /** Which field or stamp the row's parent link came from, in the desktop's terms. */
    fun parentLinkSource(agent: Agent): String {
        val parent = agent.parent ?: return "-"
        val record = agent.record
        if (record != null) {
            when (parent.id) {
                record.subagentParentId -> return "cloudSubagentParent.parentAgentId"
                record.sideChatParentId -> return "sideChatInfo.parentBcId"
                record.managerAgentId -> return "managerAgentId"
            }
        }
        return when (agent.scopeSignal) {
            LineageSignal.MEMBERSHIP -> "ListWorkersForManager (the desktop's seeded managerAgentId)"
            LineageSignal.CHILDREN_LIST -> "ListBackgroundComposerChildren"
            LineageSignal.ACTION -> "an action here (the desktop's _stampListedCloudAgentManager)"
            LineageSignal.COORDINATOR_CREATED -> "create_agent in the coordinator's transcript (default mode)"
            // The record's own field, named by the kind it gave the link.
            else -> when (parent.kind) {
                AgentParentKind.SUBAGENT -> "cloudSubagentParent.parentAgentId"
                AgentParentKind.SIDE_CHAT -> "sideChatInfo.parentBcId"
                AgentParentKind.PROJECT_WORKER -> "managerAgentId"
            }
        }
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
