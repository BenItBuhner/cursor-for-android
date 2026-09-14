package com.cursorforandroid.domain

/**
 * Settings › Notifications, the Project half: what the live "agents running" notification counts, and which chats'
 * finishes and questions are announced as cards. The live count is every running agent by default — a Project's
 * coordinator, its workers and side chats, and the account's own chats alike — because it answers "how much is
 * running", and Bennett watching three Projects work wants the number to be the number. The cards stay off for
 * Project-scoped chats by default: a Project with a hundred workers finishing turns is the Project view's business,
 * not a hundred cards'. Each is a switch of its own.
 */
data class ProjectNotificationPrefs(
    /** The live notification's count and lines include a Project's coordinator and the agents inside it. */
    val countProjectAgentsInLive: Boolean = true,
    /** A card when a Project's coordinator finishes a turn or asks a question. */
    val notifyProjectCoordinators: Boolean = false,
    /** A card when a worker, side chat or subagent inside a Project finishes a turn or asks a question. */
    val notifyProjectMembers: Boolean = false,
) {
    /** Whether [agent]'s finish or question is announced as a card (the snooze and the app-wide switch apart). */
    fun announces(agent: Agent): Boolean = when {
        !agent.isProjectScopedByEvidence -> true
        agent.isProjectRoot -> notifyProjectCoordinators
        else -> notifyProjectMembers
    }

    /** Whether the live notification counts [agent] among the running. */
    fun countsLive(agent: Agent): Boolean = countProjectAgentsInLive || !agent.isProjectScopedByEvidence

    companion object {
        val DEFAULT = ProjectNotificationPrefs()
    }
}

/**
 * The running set the live notification, the run monitor and the finish watchdog work from: the status scan's
 * word ([RunningScan], `/v0/agents` read newest first for a few pages, plus the account list's statuses in Extended
 * mode) reconciled with the rows — a row is fresher than the scan (its stream has said "finished" since), so a row
 * that says the run is over takes its agent off the set, and a row that says running is on it whether or not the
 * scan reached it. Independent of how far the sidebar has paged: an agent the scan named and no page holds counts
 * (its row is fetched by id meanwhile), so the number in the notification is the account's running agents, not the
 * loaded pages'. [prefs] decides whether the Projects' agents are counted; [quietIds] are snoozed chats.
 */
object LiveRunning {
    fun ids(
        agents: List<Agent>,
        scan: RunningScan,
        prefs: ProjectNotificationPrefs = ProjectNotificationPrefs.DEFAULT,
        quietIds: Set<String> = emptySet(),
    ): Set<String> {
        val rows = agents.associateBy { it.id }
        val set = LinkedHashSet<String>()
        agents.forEach { if (it.isRunning && prefs.countsLive(it)) set += it.id }
        scan.all.forEach { id ->
            val row = rows[id]
            when {
                // The row has the fresher word: running (already in), or over.
                row != null -> Unit
                // No row yet: counted unless the Projects' agents are not, in which case its place is not known.
                prefs.countProjectAgentsInLive -> set += id
            }
        }
        set.removeAll(quietIds)
        return set
    }
}
