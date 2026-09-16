package com.cursorforandroid.domain

import com.cursorforandroid.data.api.CursorJson
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AgentListOrganizerTest {

    private val zone = ZoneOffset.UTC
    private val now = 1_800_000_000_000L // 2027-01-15T08:00:00Z
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun agent(
        id: String,
        name: String = id,
        updatedAgo: Long = 0,
        lifecycle: AgentLifecycle = AgentLifecycle.IDLE,
        runStatus: RunStatus? = RunStatus.FINISHED,
        repo: String? = "https://github.com/acme/app",
        branch: String? = null,
        pr: String? = null,
        env: EnvType = EnvType.CLOUD,
        source: AgentSource? = null,
        isProject: Boolean = false,
        parent: String? = null,
        parentKind: AgentParentKind = AgentParentKind.PROJECT_WORKER,
        scopeSignal: LineageSignal? = null,
    ) = Agent(
        id = id,
        name = name,
        lifecycle = lifecycle,
        runStatus = runStatus,
        envType = env,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = now - updatedAgo - hour,
        updatedAtMillis = now - updatedAgo,
        latestRunId = "run-$id",
        repoUrl = repo,
        startingRef = "main",
        branches = if (branch != null || pr != null) listOf(GitBranch("github.com/acme/app", branch, pr)) else emptyList(),
        source = source,
        isProject = isProject,
        parent = parent?.let { AgentParent(it, parentKind) },
        scopeSignal = scopeSignal,
    )

    private fun List<AgentSection>.ids(key: String) = first { it.key == key }.rows.map { it.agent.id }

    @Test
    fun `Projects lead the sidebar, pinned or not, and only a Project at the top of its tree is one`() {
        val agents = listOf(
            agent("plain", updatedAgo = 0),
            agent("project", updatedAgo = 2 * hour, isProject = true),
            agent("pinned-project", updatedAgo = 3 * hour, isProject = true),
            agent("pinned", updatedAgo = 4 * hour),
            // A Project spawned as another chat's subagent is listed where its parent is, not among the Projects.
            agent("nested-project", updatedAgo = 5 * hour, isProject = true, parent = "plain", parentKind = AgentParentKind.SUBAGENT),
        )
        val local = LocalAgentState(pinnedIds = setOf("pinned", "pinned-project"))
        val sections = AgentListOrganizer.organize(agents, ListPreferences(), local, nowMillis = now, zone = zone)
        assertThat(sections.map { it.title }).containsExactly("Projects", "Pinned", "Today").inOrder()
        assertThat(sections.ids(AgentListOrganizer.PROJECTS_KEY)).containsExactly("project", "pinned-project").inOrder()
        assertThat(sections.ids(AgentListOrganizer.PINNED_KEY)).containsExactly("pinned")
        assertThat(sections.ids("date:Today")).containsExactly("plain")
        assertThat(sections.first { it.key == "date:Today" }.rows.single().children.map { it.agent.id }).containsExactly("nested-project")
        // With nothing grouped, the rows after the Projects are "Others", as they are after the Pinned.
        val flat = AgentListOrganizer.organize(listOf(agent("a"), agent("p", isProject = true)), ListPreferences(groupBy = GroupBy.None), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(flat.map { it.title }).containsExactly("Projects", "Others").inOrder()
    }

    @Test
    fun `chats nest under their parent's row, a child of an unloaded parent is not drawn, and a pinned child stays under its parent`() {
        val agents = listOf(
            agent("project", updatedAgo = 3 * hour, isProject = true),
            agent("worker-new", updatedAgo = 0, parent = "project"),
            agent("worker-old", updatedAgo = hour, parent = "project"),
            agent("side", updatedAgo = 2 * hour, parent = "project", parentKind = AgentParentKind.SIDE_CHAT),
            agent("grandchild", updatedAgo = 30 * 60_000L, parent = "worker-old", parentKind = AgentParentKind.SUBAGENT),
            agent("orphan", updatedAgo = 4 * hour, parent = "bc-gone"),
            agent("pinned-worker", updatedAgo = 5 * hour, parent = "project"),
            agent("plain", updatedAgo = 6 * hour),
        )
        val local = LocalAgentState(pinnedIds = setOf("pinned-worker"))
        val sections = AgentListOrganizer.organize(agents, ListPreferences(), local, nowMillis = now, zone = zone)
        // VuC pins top-level headers only: the pinned worker stays under its Project, and there is no Pinned section.
        assertThat(sections.map { it.title }).containsExactly("Projects", "Today").inOrder()
        val projects = sections.first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows
        val project = projects.single()
        // Children in the list's order (newest first), with children of their own beneath them.
        assertThat(project.children.map { it.agent.id }).containsExactly("worker-new", "worker-old", "side", "pinned-worker").inOrder()
        assertThat(project.children[1].children.map { it.agent.id }).containsExactly("grandchild")
        assertThat(project.descendants().map { it.agent.id }).containsExactly("worker-new", "worker-old", "grandchild", "side", "pinned-worker").inOrder()
        // PJr: the child of a parent the list has not loaded is a child all the same — never a top-level row — and
        // is not drawn until the parent's row is (the repository fetches it by id, as the desktop hydrates it).
        assertThat(sections.flatMap { it.rows }.flatMap { listOf(it) + it.descendants() }.map { it.agent.id }).doesNotContain("orphan")
        assertThat(sections.ids("date:Today")).containsExactly("plain")
        // The recent surface is for the account's own chats: no Project, no worker — not even a pinned one.
        assertThat(AgentListOrganizer.recentRows(sections).map { it.agent.id }).containsExactly("plain")
        // A Project's row answers the archive filter alone: it is listed with its whole tree; the plain chat answers to the filter as ever.
        val running = AgentListOrganizer.organize(agents.map { if (it.id == "worker-new") it.copy(runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE) else it }, ListPreferences(statuses = setOf(StatusFilter.Running)), local, nowMillis = now, zone = zone)
        assertThat(running.map { it.key }).containsExactly(AgentListOrganizer.PROJECTS_KEY)
        assertThat(running.first().rows.map { it.agent.id }).containsExactly("project")
        assertThat(running.first().rows.first().descendants().map { it.agent.id }).containsExactly("worker-new", "worker-old", "grandchild", "side", "pinned-worker").inOrder()
        // A filter that drops a chat of the account's own hides its children with it; they never stand on their own.
        val plainTree = listOf(agent("plain", updatedAgo = hour), agent("sub", parent = "plain", parentKind = AgentParentKind.SUBAGENT, runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE))
        assertThat(AgentListOrganizer.organize(plainTree, ListPreferences(statuses = setOf(StatusFilter.Running)), LocalAgentState(), nowMillis = now, zone = zone)).isEmpty()
        val plainListed = AgentListOrganizer.organize(plainTree, ListPreferences(statuses = setOf(StatusFilter.Unread)), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(plainListed.single().rows.single().children.map { it.agent.id }).containsExactly("sub")
        // A search finds a child where it sits: under its parent, with its siblings that do not match left out.
        val searched = AgentListOrganizer.organize(agents, ListPreferences(), local, query = "side", nowMillis = now, zone = zone)
        val found = searched.single().rows.single()
        assertThat(found.agent.id).isEqualTo("project")
        assertThat(found.children.map { it.agent.id }).containsExactly("side")
        // A match on the parent keeps its whole tree.
        val byProject = AgentListOrganizer.organize(agents, ListPreferences(), local, query = "project", nowMillis = now, zone = zone)
        assertThat(byProject.single().rows.single().descendants().map { it.agent.id }).containsExactly("worker-new", "worker-old", "grandchild", "side", "pinned-worker").inOrder()
    }

    // ---- the isolation rule, one case per way a worker used to leak into the chat list -------------------------------

    private fun primaryIds(agents: List<Agent>, prefs: ListPreferences = ListPreferences(), local: LocalAgentState = LocalAgentState(), query: String = ""): List<String> =
        AgentListOrganizer.organize(agents, prefs, local, query = query, nowMillis = now, zone = zone).flatMap { it.rows }.map { it.agent.id }

    @Test
    fun `a chat with a parent link is never a top-level row, whatever became of its parent`() {
        val project = agent("project", isProject = true)
        val worker = agent("worker", parent = "project")
        val side = agent("side", parent = "project", parentKind = AgentParentKind.SIDE_CHAT)
        val subagent = agent("sub", parent = "plain", parentKind = AgentParentKind.SUBAGENT)
        val plain = agent("plain")

        // Cause 3: the parent is archived and the default Status filter hides it: its children go with its row.
        val archived = project.copy(lifecycle = AgentLifecycle.ARCHIVED)
        assertThat(primaryIds(listOf(archived, worker, side, plain, subagent))).containsExactly("plain")
        // Cause 3, as it was: the parent filtered out by repository while its workers keep their repositories. The
        // Project stands whatever the Repo filter says, and its workers sit under it — never among the chats.
        val noRepo = project.copy(repoUrl = null)
        val byRepo = AgentListOrganizer.organize(listOf(noRepo, worker, side, plain), ListPreferences(repos = setOf("acme/app")), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(byRepo.map { it.key }).containsExactly(AgentListOrganizer.PROJECTS_KEY, "date:Today").inOrder()
        assertThat(byRepo.ids(AgentListOrganizer.PROJECTS_KEY)).containsExactly("project")
        assertThat(byRepo.first().rows.single().children.map { it.agent.id }).containsExactly("worker", "side")
        assertThat(byRepo.ids("date:Today")).containsExactly("plain")
        // Cause 3: the parent is searched away.
        assertThat(primaryIds(listOf(project, worker, side, plain), query = "plain")).containsExactly("plain")
        // Cause 3 and 4: the parent is beyond the listing window, or in the gap between the two windows: PJr makes
        // children of the worker and the side chat all the same, and neither is drawn until the parent's row is.
        val sections = AgentListOrganizer.organize(listOf(worker, side, plain), ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(sections.map { it.key }).containsExactly("date:Today")
        assertThat(sections.ids("date:Today")).containsExactly("plain")
        assertThat(sections.flatMap { it.rows }.flatMap { listOf(it) + it.descendants() }.map { it.agent.id }).containsNoneOf("worker", "side")
        // Once the server has refused the parent's row, its children wait under a stand-in that says so — an
        // ordinary row, not a Project (the desktop draws neither; the stand-in keeps the chats reachable).
        val refused = AgentListOrganizer.organize(listOf(worker, side, plain), ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone, unavailableProjects = setOf("project"))
        assertThat(refused.map { it.key }).containsExactly("date:Today")
        val standIn = refused.single().rows.first { it.agent.id == "project" }
        assertThat(standIn.isPlaceholder && standIn.agent.name == AgentListOrganizer.UNAVAILABLE_NAME).isTrue()
        assertThat(standIn.agent.isProjectRoot).isFalse()
        assertThat(standIn.children.map { it.agent.id }).containsExactly("worker", "side")
        assertThat(AgentListOrganizer.organize(listOf(worker, side, plain), ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone, unavailableProjects = setOf("other")).single().rows.map { it.agent.id }).containsExactly("plain")
        // The stand-in is not a recent chat, and nothing under it is.
        assertThat(AgentListOrganizer.recentRows(refused).map { it.agent.id }).containsExactly("plain")
        // Which parents to fetch: the ones named but not held, once each.
        assertThat(AgentListOrganizer.missingParentIds(listOf(worker, side, plain, subagent))).containsExactly("project")
        assertThat(AgentListOrganizer.missingParentIds(listOf(worker, side, subagent))).containsExactly("project", "plain").inOrder()
        assertThat(AgentListOrganizer.missingParentIds(listOf(project, worker, plain, subagent))).isEmpty()
    }

    @Test
    fun `the desktop's two predicates decide the scope - the parent link and the Project flag - and nothing else`() {
        // A source says how a chat was started, never where it belongs: the desktop reads the parent link alone, so
        // a side chat or subagent whose record names no parent is a chat of the account's own (as it is there).
        val sideBySource = agent("side", source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD)
        val subBySource = agent("sub", source = AgentSource.AS_SUBAGENT_FROM_CLOUD)
        assertThat(sideBySource.scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(primaryIds(listOf(sideBySource, subBySource, agent("plain")))).containsExactly("plain", "side", "sub")
        // A chat started as a cloud meta agent is a chat of the account's own unless its record flags it a Project.
        val meta = agent("meta", source = AgentSource.CLOUD_META_AGENT)
        assertThat(meta.scope).isEqualTo(AgentScope.PRIMARY)
        val withMeta = AgentListOrganizer.organize(listOf(meta, agent("plain")), ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(withMeta.none { it.key == AgentListOrganizer.PROJECTS_KEY }).isTrue()
        assertThat(withMeta.flatMap { it.rows }.map { it.agent.id }).containsExactly("meta", "plain")
        // A coordinator without the flag — a Multitask chat, a chat that manages workers a membership names — is an
        // ordinary top-level row with its workers nested under it (the desktop's row-with-children), not a Project.
        val coordinator = agent("coord", scopeSignal = LineageSignal.MEMBERSHIP)
        assertThat(coordinator.isProjectRoot).isFalse()
        assertThat(coordinator.looksLikeProject).isFalse()
        val sections = AgentListOrganizer.organize(listOf(coordinator, agent("w", parent = "coord", scopeSignal = LineageSignal.MEMBERSHIP)), ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(sections.single().key).isEqualTo("date:Today")
        assertThat(sections.single().rows.single().agent.id).isEqualTo("coord")
        assertThat(sections.single().rows.single().children.map { it.agent.id }).containsExactly("w")
        // PJr before kf: a parent link makes a child of a flagged Project too; the flag alone makes a root; the
        // source changes nothing either way.
        assertThat(agent("x", isProject = true).scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(agent("x", isProject = true, parent = "y").scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(agent("x", isProject = true, parent = "y").looksLikeProject).isFalse()
        assertThat(agent("x", isProject = true, source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD).scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(agent("x").scope).isEqualTo(AgentScope.PRIMARY)
        assertThat(agent("x", parent = "y").scope).isEqualTo(AgentScope.PROJECT_CHILD)
        // The desktop's rule for each, as the export prints it.
        assertThat(AgentsWindowList.place(agent("x", isProject = true), setOf("x"), emptySet()).rule).startsWith("kf top-level Project")
        assertThat(AgentsWindowList.place(agent("x", isProject = true, parent = "y"), setOf("x", "y"), emptySet()).rule).startsWith("PJr child of y via managerAgentId")
        assertThat(AgentsWindowList.place(agent("x", parent = "y", parentKind = AgentParentKind.SIDE_CHAT), setOf("x"), emptySet()).rule).contains("via sideChatInfo.parentBcId")
        assertThat(AgentsWindowList.place(agent("x", parent = "y", parentKind = AgentParentKind.SIDE_CHAT), setOf("x"), emptySet()).rule).contains("parent row not loaded")
        assertThat(AgentsWindowList.place(agent("x"), setOf("x"), setOf("x")).rule).endsWith("→ Pinned (VuC)")
        assertThat(AgentsWindowList.place(agent("x"), setOf("x"), emptySet()).rule).endsWith("→ time section (f3v)")
    }

    @Test
    fun `a pinned worker stays under its Project with its own children, and no primary surface lists it`() {
        val agents = listOf(
            agent("project", isProject = true),
            agent("worker", parent = "project"),
            agent("deep", parent = "worker", parentKind = AgentParentKind.SUBAGENT),
        )
        val local = LocalAgentState(pinnedIds = setOf("worker"))
        val sections = AgentListOrganizer.organize(agents, ListPreferences(), local, nowMillis = now, zone = zone)
        // VuC picks pins from the top-level headers: a pinned child is nested like any other, and there is no Pinned section.
        assertThat(sections.map { it.key }).containsExactly(AgentListOrganizer.PROJECTS_KEY)
        val project = sections.single().rows.single()
        assertThat(project.agent.id).isEqualTo("project")
        assertThat(project.children.map { it.agent.id }).containsExactly("worker")
        assertThat(project.children.single().isPinned).isTrue()
        assertThat(project.children.single().children.map { it.agent.id }).containsExactly("deep")
        // The sidebar's word is the sidebar's: the recents and the widget list nothing of a Project, pinned or not.
        assertThat(AgentListOrganizer.recentRows(sections)).isEmpty()
        val running = agents.map { if (it.id == "deep") it.copy(runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE) else it }
        assertThat(WidgetList.rows(WidgetMode.Running, running, ListPreferences(), local, nowMillis = now, zone = zone)).isEmpty()
        val pinnedRunning = running.map { if (it.id == "worker" || it.id == "project") it.copy(runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE) else it }
        assertThat(WidgetList.rows(WidgetMode.Running, pinnedRunning, ListPreferences(), local, nowMillis = now, zone = zone)).isEmpty()
        assertThat(WidgetList.rows(WidgetMode.Pinned, agents, ListPreferences(), local, nowMillis = now, zone = zone)).isEmpty()
        assertThat(WidgetList.rows(WidgetMode.Recent, agents, ListPreferences(), local, nowMillis = now, zone = zone)).isEmpty()
        val withPlain = agents + agent("plain")
        assertThat(WidgetList.rows(WidgetMode.Recent, withPlain, ListPreferences(), local, nowMillis = now, zone = zone).map { it.agent.id }).containsExactly("plain")
    }

    // ---- the Chats filters and Projects: a Project's row stands whatever they say ----------------------------------

    private val mergedPr = "https://github.com/acme/app/pull/1"
    private val openPr = "https://github.com/acme/app/pull/2"

    /**
     * Three Projects and three chats of the account's own, alike but for being Projects: one with a merged pull
     * request, one with an open one (on another repository, on the user's machine), one with none (running). The
     * first Project's workers carry a merged and an open pull request of their own.
     */
    private val projectsAndChats = listOf(
        agent("p-merged", isProject = true, branch = "cursor/p1", pr = mergedPr, source = AgentSource.WEBSITE),
        agent("p-open", isProject = true, branch = "cursor/p2", pr = openPr, source = AgentSource.WEBSITE, env = EnvType.MACHINE, repo = "https://github.com/acme/web"),
        agent("p-none", isProject = true, repo = null, runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE),
        agent("w-merged", parent = "p-merged", branch = "cursor/w1", pr = mergedPr),
        agent("w-open", parent = "p-merged", branch = "cursor/w2", pr = openPr),
        agent("w-none", parent = "p-open"),
        agent("c-merged", branch = "cursor/c1", pr = mergedPr),
        agent("c-open", branch = "cursor/c2", pr = openPr),
        agent("c-none"),
    )
    private val projectsAndChatsLocal = LocalAgentState(pullRequests = mapOf(mergedPr to PullRequestState.Merged, openPr to PullRequestState.Open))

    /** Every Project is in the Projects group with its whole tree, and the chats listed on their own are exactly [chats]. */
    private fun assertProjectsKept(prefs: ListPreferences, vararg chats: String) {
        val sections = AgentListOrganizer.organize(projectsAndChats, prefs, projectsAndChatsLocal, nowMillis = now, zone = zone)
        // rUm: the same last activity, so the ids order them.
        assertWithMessage("Projects under $prefs").that(sections.ids(AgentListOrganizer.PROJECTS_KEY)).containsExactly("p-merged", "p-none", "p-open").inOrder()
        val projects = sections.first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows.associateBy { it.agent.id }
        // The counts are the Projects' membership, whatever the filter says about the workers' own pull requests.
        assertWithMessage("p-merged's tree under $prefs").that(projects.getValue("p-merged").children.map { it.agent.id }).containsExactly("w-merged", "w-open").inOrder()
        assertWithMessage("p-open's tree under $prefs").that(projects.getValue("p-open").children.map { it.agent.id }).containsExactly("w-none")
        assertThat(projects.getValue("p-none").children).isEmpty()
        assertThat(projects.getValue("p-merged").pullRequest).isEqualTo(PullRequestState.Merged)
        val others = sections.filterNot { it.key == AgentListOrganizer.PROJECTS_KEY }.flatMap { it.rows }
        assertWithMessage("chats under $prefs").that(others.map { it.agent.id }).containsExactlyElementsIn(chats.toList())
        // Nothing of a Project is a recent chat, filtered or not.
        assertThat(AgentListOrganizer.recentRows(sections).map { it.agent.id }).containsExactlyElementsIn(chats.toList())
    }

    @Test
    fun `the Git filter never takes a Project off the list, whatever its coordinator's pull request`() {
        // Bennett's case: merged pull requests excluded, and a Project whose coordinator has one disappeared with them.
        assertProjectsKept(ListPreferences(git = GitFilter.entries.toSet() - GitFilter.Merged), "c-open", "c-none")
        assertProjectsKept(ListPreferences(git = setOf(GitFilter.Merged)), "c-merged")
        assertProjectsKept(ListPreferences(git = setOf(GitFilter.Open)), "c-open")
        assertProjectsKept(ListPreferences(git = setOf(GitFilter.Draft)))
        assertProjectsKept(ListPreferences(git = setOf(GitFilter.Closed)))
        assertProjectsKept(ListPreferences(git = setOf(GitFilter.NoPullRequest)), "c-none")
        assertProjectsKept(ListPreferences(git = setOf(GitFilter.Open, GitFilter.NoPullRequest)), "c-open", "c-none")
        assertProjectsKept(ListPreferences(git = emptySet()))
        assertProjectsKept(ListPreferences(), "c-merged", "c-open", "c-none")
    }

    @Test
    fun `the Status, Repo, Source and Environment filters never take a Project off the list either`() {
        // Status: none of the three chats is running, in error or snoozed, and all are unread (nothing read yet).
        assertProjectsKept(ListPreferences(statuses = setOf(StatusFilter.Running)))
        assertProjectsKept(ListPreferences(statuses = setOf(StatusFilter.Unread)), "c-merged", "c-open", "c-none")
        assertProjectsKept(ListPreferences(statuses = setOf(StatusFilter.Read)))
        assertProjectsKept(ListPreferences(statuses = setOf(StatusFilter.Error)))
        assertProjectsKept(ListPreferences(statuses = setOf(StatusFilter.Snoozed)))
        assertProjectsKept(ListPreferences(statuses = setOf(StatusFilter.Archived)))
        assertProjectsKept(ListPreferences(statuses = emptySet()))
        // Repo: a Project spans repositories, or names none.
        assertProjectsKept(ListPreferences(repos = setOf("acme/app")), "c-merged", "c-open", "c-none")
        assertProjectsKept(ListPreferences(repos = setOf("acme/web")))
        assertProjectsKept(ListPreferences(repos = emptySet()))
        // Source: the chats have not been asked where they were started ("Other"); the Projects were started on the web.
        assertProjectsKept(ListPreferences(sources = setOf(SourceFilter.Other)), "c-merged", "c-open", "c-none")
        assertProjectsKept(ListPreferences(sources = setOf(SourceFilter.Slack)))
        assertProjectsKept(ListPreferences(sources = emptySet()))
        // Environment.
        assertProjectsKept(ListPreferences(environments = setOf(EnvironmentFilter.Cloud)), "c-merged", "c-open", "c-none")
        assertProjectsKept(ListPreferences(environments = setOf(EnvironmentFilter.Machine)))
        assertProjectsKept(ListPreferences(environments = emptySet()))
        // All of them at once, each excluding something a Project has.
        assertProjectsKept(ListPreferences(git = setOf(GitFilter.Draft), statuses = setOf(StatusFilter.Error), repos = setOf("acme/other"), sources = setOf(SourceFilter.ThisDevice), environments = setOf(EnvironmentFilter.Pool)))
        // Grouping and sort order leave the Projects group where it is.
        for (groupBy in GroupBy.entries) for (order in SortOrder.entries) {
            val sections = AgentListOrganizer.organize(projectsAndChats, ListPreferences(groupBy = groupBy, sortOrder = order, git = setOf(GitFilter.Open)), projectsAndChatsLocal, nowMillis = now, zone = zone)
            assertThat(sections.first().key).isEqualTo(AgentListOrganizer.PROJECTS_KEY)
            assertThat(sections.first().rows.map { it.agent.id }).containsExactly("p-merged", "p-open", "p-none")
        }
        // The Chats filters still cut the Running widget and the recents as before: nothing of a Project, and the
        // filters honoured for the account's chats.
        assertThat(WidgetList.rows(WidgetMode.Recent, projectsAndChats, ListPreferences(git = GitFilter.entries.toSet() - GitFilter.Merged), projectsAndChatsLocal, nowMillis = now, zone = zone).map { it.agent.id }).containsExactly("c-open", "c-none")
        assertThat(WidgetList.rows(WidgetMode.Running, projectsAndChats, ListPreferences(), projectsAndChatsLocal, nowMillis = now, zone = zone)).isEmpty()
    }

    @Test
    fun `the search still finds a Project by name while the filters would have cut its coordinator`() {
        val prefs = ListPreferences(git = GitFilter.entries.toSet() - GitFilter.Merged)
        val byName = AgentListOrganizer.organize(projectsAndChats, prefs, projectsAndChatsLocal, query = "p-merged", nowMillis = now, zone = zone)
        assertThat(byName.single().key).isEqualTo(AgentListOrganizer.PROJECTS_KEY)
        val found = byName.single().rows.single()
        assertThat(found.agent.id).isEqualTo("p-merged")
        assertThat(found.children.map { it.agent.id }).containsExactly("w-merged", "w-open").inOrder()
        // A worker is found under its Project, and a chat's own name still finds the chat.
        val byWorker = AgentListOrganizer.organize(projectsAndChats, prefs, projectsAndChatsLocal, query = "w-open", nowMillis = now, zone = zone)
        assertThat(byWorker.single().rows.single().let { it.agent.id to it.children.map { c -> c.agent.id } }).isEqualTo("p-merged" to listOf("w-open"))
        assertThat(AgentListOrganizer.organize(projectsAndChats, prefs, projectsAndChatsLocal, query = "c-open", nowMillis = now, zone = zone).single().rows.map { it.agent.id }).containsExactly("c-open")
        assertThat(AgentListOrganizer.organize(projectsAndChats, prefs, projectsAndChatsLocal, query = "nothing here", nowMillis = now, zone = zone)).isEmpty()
    }

    @Test
    fun `the archive is the one filter a Project and its members answer to`() {
        val agents = listOf(
            agent("live", isProject = true, branch = "cursor/l", pr = mergedPr),
            agent("live-worker", parent = "live"),
            agent("live-archived-worker", parent = "live", lifecycle = AgentLifecycle.ARCHIVED),
            agent("gone", isProject = true, lifecycle = AgentLifecycle.ARCHIVED),
            agent("gone-worker", parent = "gone"),
            agent("plain"),
        )
        val local = LocalAgentState(pullRequests = mapOf(mergedPr to PullRequestState.Merged))
        // By default an archived Project is put away like an archived chat, its workers with it; so is an archived
        // worker of a live Project, and the count follows.
        val defaults = AgentListOrganizer.organize(agents, ListPreferences(git = GitFilter.entries.toSet() - GitFilter.Merged), local, nowMillis = now, zone = zone)
        assertThat(defaults.ids(AgentListOrganizer.PROJECTS_KEY)).containsExactly("live")
        assertThat(defaults.first().rows.single().descendants().map { it.agent.id }).containsExactly("live-worker")
        assertThat(defaults.flatMap { it.rows }.flatMap { listOf(it) + it.descendants() }.map { it.agent.id }).containsExactly("live", "live-worker", "plain")
        // With Archived checked, the archived Project is back with its worker, and so is the live Project's archived worker.
        val archivedToo = AgentListOrganizer.organize(agents, ListPreferences(statuses = StatusFilter.entries.toSet()), local, nowMillis = now, zone = zone)
        // rUm orders rows of the same last activity by id.
        assertThat(archivedToo.ids(AgentListOrganizer.PROJECTS_KEY)).containsExactly("gone", "live").inOrder()
        val rows = archivedToo.first().rows.associateBy { it.agent.id }
        assertThat(rows.getValue("live").children.map { it.agent.id }).containsExactly("live-archived-worker", "live-worker").inOrder()
        assertThat(rows.getValue("gone").children.map { it.agent.id }).containsExactly("gone-worker")
        // Archived alone lists the archived chats — and every live Project too, since nothing else decides about one.
        val onlyArchived = AgentListOrganizer.organize(agents, ListPreferences(statuses = setOf(StatusFilter.Archived)), local, nowMillis = now, zone = zone)
        assertThat(onlyArchived.ids(AgentListOrganizer.PROJECTS_KEY)).containsExactly("gone", "live").inOrder()
        assertThat(onlyArchived.filterNot { it.key == AgentListOrganizer.PROJECTS_KEY }.flatMap { it.rows }).isEmpty()
        // The rule itself, row by row.
        val liveRow = AgentListOrganizer.toRow(agents.first { it.id == "live" }, local, now)
        val goneRow = AgentListOrganizer.toRow(agents.first { it.id == "gone" }, local, now)
        assertThat(AgentListOrganizer.isListed(liveRow, ListPreferences(git = emptySet()))).isTrue()
        assertThat(AgentListOrganizer.matchesFilters(liveRow, ListPreferences(git = emptySet()))).isFalse()
        assertThat(AgentListOrganizer.isListed(goneRow, ListPreferences())).isFalse()
        assertThat(AgentListOrganizer.isListed(goneRow, ListPreferences(statuses = setOf(StatusFilter.Archived)))).isTrue()
    }

    @Test
    fun `a running child shows through its collapsed parent, and the tree flattens to what is expanded`() {
        val agents = listOf(
            agent("project", isProject = true),
            agent("worker", parent = "project", runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE),
            agent("quiet", parent = "project"),
            agent("deep", parent = "quiet"),
        )
        val sections = AgentListOrganizer.organize(agents, ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone)
        val project = sections.single().rows.single()
        assertThat(project.hasRunningDescendant).isTrue()
        assertThat(project.children.first { it.agent.id == "quiet" }.hasRunningDescendant).isFalse()

        assertThat(AgentListOrganizer.flatten(sections.single().rows, emptySet()).map { it.row.agent.id to it.depth }).containsExactly("project" to 0)
        assertThat(AgentListOrganizer.flatten(sections.single().rows, setOf("project")).map { it.row.agent.id to it.depth })
            .containsExactly("project" to 0, "quiet" to 1, "worker" to 1).inOrder()
        assertThat(AgentListOrganizer.flatten(sections.single().rows, setOf("project", "quiet")).map { it.row.agent.id to it.depth })
            .containsExactly("project" to 0, "quiet" to 1, "deep" to 2, "worker" to 1).inOrder()
        // Expanding a hidden parent shows nothing until its own parent is open.
        assertThat(AgentListOrganizer.flatten(sections.single().rows, setOf("quiet")).map { it.row.agent.id }).containsExactly("project")
    }

    @Test
    fun `parent links that loop, or point at the chat itself, never lose a chat`() {
        // ZOl: the links are applied in the list's order, and the one that would close the loop is no link — so b,
        // whose link back to a is refused, stands at the top level with a under it; a link to itself is no link.
        val loop = listOf(agent("a", parent = "b"), agent("b", parent = "a"), agent("self", parent = "self"))
        val rows = AgentListOrganizer.nest(loop.map { AgentListOrganizer.toRow(it, LocalAgentState(), now) })
        assertThat(rows.flatMap { listOf(it) + it.descendants() }.map { it.agent.id }).containsExactly("self", "a", "b")
        assertThat(rows.map { it.agent.id }).containsExactly("b", "self")
        assertThat(rows.first { it.agent.id == "b" }.children.map { it.agent.id }).containsExactly("a")
        assertThat(AgentsWindowList.parentLinks(loop)).containsExactly("a", "b")
    }

    @Test
    fun `indicator reflects lifecycle, run status and read markers`() {
        val local = LocalAgentState(readMarkers = mapOf("read" to now))
        assertThat(AgentListOrganizer.indicatorFor(agent("running", runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE), local)).isEqualTo(AgentIndicator.Running)
        assertThat(AgentListOrganizer.indicatorFor(agent("err", runStatus = RunStatus.ERROR), local)).isEqualTo(AgentIndicator.Error)
        assertThat(AgentListOrganizer.indicatorFor(agent("read"), local)).isEqualTo(AgentIndicator.Read)
        assertThat(AgentListOrganizer.indicatorFor(agent("unread"), local)).isEqualTo(AgentIndicator.Unread)
        assertThat(AgentListOrganizer.indicatorFor(agent("arch", lifecycle = AgentLifecycle.ARCHIVED), local)).isEqualTo(AgentIndicator.Archived)
    }

    @Test
    fun `agent becomes unread again when updated after the read marker`() {
        val local = LocalAgentState(readMarkers = mapOf("a" to now - hour))
        assertThat(AgentListOrganizer.isUnread(agent("a", updatedAgo = 0), local)).isTrue()
        assertThat(AgentListOrganizer.isUnread(agent("a", updatedAgo = 2 * hour), local)).isFalse()
    }

    @Test
    fun `active lifecycle without run status is at rest, not running`() {
        // The server reports ACTIVE for every unarchived agent, finished or not; only a run status can say "running".
        val a = agent("x", lifecycle = AgentLifecycle.ACTIVE, runStatus = null)
        assertThat(a.isRunning).isFalse()
        assertThat(AgentListOrganizer.indicatorFor(a, LocalAgentState())).isEqualTo(AgentIndicator.Unread)
        assertThat(AgentListOrganizer.indicatorFor(a, LocalAgentState(readMarkers = mapOf("x" to now)))).isEqualTo(AgentIndicator.Read)
    }

    @Test
    fun `the recent list is the sidebar's rows newest first, pins and groups aside, however the sidebar is arranged`() {
        val agents = listOf(
            agent("today", updatedAgo = 2 * hour),
            agent("yesterday", updatedAgo = day + hour),
            agent("pinned", updatedAgo = 3 * day),
            agent("newest", updatedAgo = 0),
            agent("arch", lifecycle = AgentLifecycle.ARCHIVED),
        )
        val local = LocalAgentState(pinnedIds = setOf("pinned"))
        val sections = AgentListOrganizer.organize(agents, ListPreferences(), local, nowMillis = now, zone = zone)
        assertThat(sections.map { it.title }).containsExactly("Pinned", "Today", "Yesterday").inOrder()
        // The same rows (the archived one is filtered out on both surfaces), by recency, the pinned one in its place.
        val recent = AgentListOrganizer.recentRows(sections)
        assertThat(recent.map { it.agent.id }).containsExactly("newest", "today", "yesterday", "pinned").inOrder()
        assertThat(recent).isEqualTo(AgentListOrganizer.recentRows(agents, ListPreferences(), local, nowMillis = now, zone = zone))
        // Grouping and sort order arrange the sidebar only; the recents stay newest first.
        val byName = ListPreferences(groupBy = GroupBy.Repo, sortOrder = SortOrder.Name)
        assertThat(AgentListOrganizer.recentRows(AgentListOrganizer.organize(agents, byName, local, nowMillis = now, zone = zone))).isEqualTo(recent)
        // A filter narrows both surfaces alike — a pinned chat excepted, which stays on both by the user's word.
        val onlyRunning = AgentListOrganizer.organize(agents, ListPreferences(statuses = setOf(StatusFilter.Running)), local, nowMillis = now, zone = zone)
        assertThat(AgentListOrganizer.recentRows(onlyRunning).map { it.agent.id }).containsExactly("pinned")
    }

    @Test
    fun `date grouping buckets by updated time with pinned first`() {
        val agents = listOf(
            agent("today", updatedAgo = 2 * hour),
            agent("yesterday", updatedAgo = day + hour),
            agent("week", updatedAgo = 4 * day),
            agent("old", updatedAgo = 40 * day),
            agent("pinned", updatedAgo = 3 * day),
        )
        val sections = AgentListOrganizer.organize(agents, ListPreferences(), LocalAgentState(pinnedIds = setOf("pinned")), nowMillis = now, zone = zone)
        assertThat(sections.map { it.title }).containsExactly("Pinned", "Today", "Yesterday", "Last 7 Days", "Older").inOrder()
        assertThat(sections.first().rows.single().agent.id).isEqualTo("pinned")
        assertThat(sections.flatMap { it.rows }.map { it.agent.id }).containsNoDuplicates()
    }

    /**
     * The section and the order read the desktop's field (`rUm` orders by the header's `lastUpdatedAt`, `f3v`
     * buckets by it; the header takes it from the record's `lastMessageActivityAtMs ?? updatedAtMs`), never the public
     * row's `updatedAt` once the record has spoken: a month-old chat whose row the account keeps touching stays in
     * Older, below a chat with newer activity and an older row stamp, and its read marker is judged by the same time.
     */
    @Test
    fun `the record's activity dates a chat, not the public row's updatedAt`() {
        val stale = agent("stale", updatedAgo = 0).copy(activityAtMillis = now - 40 * day)
        val fresh = agent("fresh", updatedAgo = 3 * day).copy(activityAtMillis = now - hour)
        val undated = agent("undated", updatedAgo = 2 * day)
        val sections = AgentListOrganizer.organize(listOf(stale, fresh, undated), ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(sections.map { it.title to it.rows.map { r -> r.agent.id } }).containsExactly(
            "Today" to listOf("fresh"),
            "Last 7 Days" to listOf("undated"),
            "Older" to listOf("stale"),
        ).inOrder()
        assertThat(AgentListOrganizer.sort(listOf(stale, undated, fresh).map { AgentListOrganizer.toRow(it, LocalAgentState(), now) }, SortOrder.Updated).map { it.agent.id })
            .containsExactly("fresh", "undated", "stale").inOrder()
        assertThat(stale.listedAtMillis).isEqualTo(now - 40 * day)
        assertThat(undated.listedAtMillis).isEqualTo(undated.updatedAtMillis)
        // Read by the same time: a marker at the record's activity reads it; the row stamp moving does not unread it.
        val readAtActivity = LocalAgentState(readMarkers = mapOf("stale" to stale.listedAtMillis))
        assertThat(AgentListOrganizer.isUnread(stale, readAtActivity, now)).isFalse()
        assertThat(AgentListOrganizer.isUnread(stale.copy(updatedAtMillis = now + hour), readAtActivity, now)).isFalse()
        assertThat(AgentListOrganizer.isUnread(stale.copy(activityAtMillis = now - day), readAtActivity, now)).isTrue()
        // A send from this device moves the listed time forward and never back.
        assertThat(stale.touched(now).listedAtMillis).isEqualTo(now)
        assertThat(fresh.touched(now - 2 * day).listedAtMillis).isEqualTo(now - hour)
    }

    @Test
    fun `date bucket boundaries`() {
        val today = LocalDate.of(2027, 1, 15)
        assertThat(AgentListOrganizer.dateBucket(today, today)).isEqualTo("Today")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(1), today)).isEqualTo("Yesterday")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(6), today)).isEqualTo("Last 7 Days")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(7), today)).isEqualTo("Last 30 Days")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(29), today)).isEqualTo("Last 30 Days")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(30), today)).isEqualTo("Older")
        assertThat(AgentListOrganizer.dateBucket(today.minusMonths(2), today)).isEqualTo("Older")
    }

    @Test
    fun `snoozed chats stay on the list, keep a clock, and only the Snoozed filter isolates them`() {
        val agents = listOf(agent("kept"), agent("later"), agent("gone"))
        val local = LocalAgentState(
            snoozedUntil = mapOf(
                "later" to now + hour,
                "gone" to SnoozeDuration.FOREVER,
            ),
        )
        val defaults = AgentListOrganizer.organize(agents, ListPreferences(), local, nowMillis = now, zone = zone)
        assertThat(defaults.flatMap { it.rows }.map { it.agent.id }).containsExactly("kept", "later", "gone")
        assertThat(defaults.flatMap { it.rows }.filter { it.isSnoozed }.map { it.agent.id }).containsExactly("later", "gone")
        assertThat(AgentListOrganizer.indicatorFor(agent("later"), local, now)).isEqualTo(AgentIndicator.Snoozed)
        assertThat(AgentListOrganizer.indicatorFor(agent("later"), local, now + hour)).isEqualTo(AgentIndicator.Unread)
        assertThat(AgentListOrganizer.indicatorFor(agent("gone"), local, now + hour)).isEqualTo(AgentIndicator.Snoozed)

        val onlySnoozed = AgentListOrganizer.organize(
            agents,
            ListPreferences(statuses = setOf(StatusFilter.Snoozed)),
            local,
            nowMillis = now,
            zone = zone,
        )
        assertThat(onlySnoozed.flatMap { it.rows }.map { it.agent.id }).containsExactly("later", "gone")
        assertThat(onlySnoozed.flatMap { it.rows }.all { it.isSnoozed }).isTrue()
    }

    @Test
    fun `a snoozed chat keeps its place when it keeps updating`() {
        val quiet = agent("quiet", updatedAgo = 0)
        val kept = agent("kept", updatedAgo = hour / 2)
        val local = LocalAgentState(
            snoozedUntil = mapOf("quiet" to now + hour),
            snoozedAt = mapOf("quiet" to now - 2 * hour),
        )
        val rows = AgentListOrganizer.organize(listOf(quiet, kept), ListPreferences(), local, nowMillis = now, zone = zone)
            .flatMap { it.rows }
            .map { it.agent.id }
        assertThat(rows).containsExactly("kept", "quiet").inOrder()
    }

    @Test
    fun `archive beats snooze, and a snoozed chat is not unread`() {
        val archived = agent("arch", lifecycle = AgentLifecycle.ARCHIVED)
        val local = LocalAgentState(snoozedUntil = mapOf("arch" to SnoozeDuration.FOREVER, "quiet" to now + hour))
        assertThat(AgentListOrganizer.indicatorFor(archived, local, now)).isEqualTo(AgentIndicator.Archived)
        assertThat(AgentListOrganizer.isUnread(agent("quiet"), local, now)).isFalse()
        assertThat(local.nextSnoozeExpiry(now)).isEqualTo(now + hour)
        assertThat(local.nextSnoozeExpiry(now + hour)).isNull()
    }

    @Test
    fun `status filter hides archived by default and can show only running`() {
        val agents = listOf(
            agent("run", runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE),
            agent("done"),
            agent("arch", lifecycle = AgentLifecycle.ARCHIVED),
        )
        val defaults = AgentListOrganizer.organize(agents, ListPreferences(), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(defaults.flatMap { it.rows }.map { it.agent.id }).containsExactly("run", "done")

        val onlyRunning = AgentListOrganizer.organize(agents, ListPreferences(statuses = setOf(StatusFilter.Running)), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(onlyRunning.flatMap { it.rows }.map { it.agent.id }).containsExactly("run")

        val archivedToo = AgentListOrganizer.organize(agents, ListPreferences(statuses = StatusFilter.entries.toSet()), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(archivedToo.flatMap { it.rows }.map { it.agent.id }).containsExactly("run", "done", "arch")
    }

    @Test
    fun `git filter goes by the pull request state, with branches and no changes as no pull request`() {
        val agents = listOf(
            agent("open", branch = "cursor/a", pr = "https://github.com/acme/app/pull/1"),
            agent("draft", branch = "cursor/b", pr = "https://github.com/acme/app/pull/2"),
            agent("merged", branch = "cursor/c", pr = "https://github.com/acme/app/pull/3"),
            agent("closed", branch = "cursor/d", pr = "https://github.com/acme/app/pull/4"),
            agent("branch", branch = "cursor/e"),
            agent("none"),
        )
        val local = LocalAgentState(
            pullRequests = mapOf(
                "https://github.com/acme/app/pull/1" to PullRequestState.Open,
                "https://github.com/acme/app/pull/2" to PullRequestState.Draft,
                "https://github.com/acme/app/pull/3" to PullRequestState.Merged,
                "https://github.com/acme/app/pull/4" to PullRequestState.Closed,
            ),
        )
        fun ids(vararg git: GitFilter) =
            AgentListOrganizer.organize(agents, ListPreferences(git = git.toSet()), local, nowMillis = now, zone = zone).flatMap { it.rows }.map { it.agent.id }
        assertThat(ids(GitFilter.Open)).containsExactly("open")
        assertThat(ids(GitFilter.Draft)).containsExactly("draft")
        assertThat(ids(GitFilter.Merged)).containsExactly("merged")
        assertThat(ids(GitFilter.Closed)).containsExactly("closed")
        assertThat(ids(GitFilter.NoPullRequest)).containsExactly("branch", "none")
        assertThat(ids(GitFilter.Open, GitFilter.Draft)).containsExactly("open", "draft")
        assertThat(ids(*GitFilter.entries.toTypedArray())).hasSize(6)
        assertThat(ids()).isEmpty()

        val rows = AgentListOrganizer.organize(agents, ListPreferences(), local, nowMillis = now, zone = zone).flatMap { it.rows }.associateBy { it.agent.id }
        assertThat(rows.getValue("merged").pullRequest).isEqualTo(PullRequestState.Merged)
        assertThat(rows.getValue("branch").pullRequest).isNull()
    }

    @Test
    fun `a pull request whose state is not known is hidden only when every state is unchecked`() {
        val agents = listOf(
            agent("unknown", branch = "cursor/a", pr = "https://github.com/acme/app/pull/9"),
            agent("none"),
        )
        fun ids(vararg git: GitFilter) =
            AgentListOrganizer.organize(agents, ListPreferences(git = git.toSet()), LocalAgentState(), nowMillis = now, zone = zone).flatMap { it.rows }.map { it.agent.id }
        assertThat(ids(GitFilter.Closed)).containsExactly("unknown")
        assertThat(ids(GitFilter.Open, GitFilter.NoPullRequest)).containsExactly("unknown", "none")
        assertThat(ids(GitFilter.NoPullRequest)).containsExactly("none")
        assertThat(ids()).isEmpty()
    }

    @Test
    fun `git filter saved by an earlier version is read into the states it stood for`() {
        val json = """{"groupBy":"Repo","git":["Branch","PullRequest","NoChanges"]}"""
        val all = CursorJson.decodeFromString(ListPreferences.serializer(), json)
        assertThat(all.groupBy).isEqualTo(GroupBy.Repo)
        assertThat(all.git).containsExactlyElementsIn(GitFilter.entries)

        // "Pull request" alone meant every pull request; a branch or nothing both meant no pull request.
        val prOnly = CursorJson.decodeFromString(ListPreferences.serializer(), """{"git":["PullRequest"]}""")
        assertThat(prOnly.git).containsExactlyElementsIn(GitFilter.pullRequestStates)
        val branchOnly = CursorJson.decodeFromString(ListPreferences.serializer(), """{"git":["Branch"]}""")
        assertThat(branchOnly.git).containsExactly(GitFilter.NoPullRequest)

        // A name nobody knows is dropped rather than failing the record, which would reset every other setting with it.
        val odd = CursorJson.decodeFromString(ListPreferences.serializer(), """{"sortOrder":"Name","git":["Merged","Rebased"]}""")
        assertThat(odd.sortOrder).isEqualTo(SortOrder.Name)
        assertThat(odd.git).containsExactly(GitFilter.Merged)

        val roundTrip = ListPreferences(git = setOf(GitFilter.Draft, GitFilter.NoPullRequest))
        assertThat(CursorJson.decodeFromString(ListPreferences.serializer(), CursorJson.encodeToString(ListPreferences.serializer(), roundTrip))).isEqualTo(roundTrip)
        assertThat(all.isDefault).isFalse()
        assertThat(CursorJson.decodeFromString(ListPreferences.serializer(), """{"git":["Branch","PullRequest","NoChanges"]}""").isDefault).isTrue()
    }

    @Test
    fun `repo filter and repo grouping`() {
        val agents = listOf(
            agent("a", repo = "https://github.com/acme/app"),
            agent("b", repo = "https://github.com/acme/web"),
            agent("c", repo = null),
        )
        val filtered = AgentListOrganizer.organize(agents, ListPreferences(repos = setOf("acme/web")), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(filtered.flatMap { it.rows }.map { it.agent.id }).containsExactly("b")

        val grouped = AgentListOrganizer.organize(agents, ListPreferences(groupBy = GroupBy.Repo), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(grouped.map { it.title }).containsExactly("acme/app", "acme/web", "No repository").inOrder()
    }

    @Test
    fun `environment filter uses the environment type, unreported counting as cloud`() {
        val agents = listOf(
            agent("cloud"),
            agent("machine", env = EnvType.MACHINE),
            agent("pool", env = EnvType.POOL),
            agent("unknown", env = EnvType.UNKNOWN),
        )
        fun ids(vararg environments: EnvironmentFilter) =
            AgentListOrganizer.organize(agents, ListPreferences(environments = environments.toSet()), LocalAgentState(), nowMillis = now, zone = zone).flatMap { it.rows }.map { it.agent.id }
        assertThat(ids(EnvironmentFilter.Machine)).containsExactly("machine")
        assertThat(ids(EnvironmentFilter.Pool)).containsExactly("pool")
        assertThat(ids(EnvironmentFilter.Cloud)).containsExactly("cloud", "unknown")
        assertThat(ids()).isEmpty()
    }

    @Test
    fun `source filter uses where the account says the chat was started, this device's launches first`() {
        val agents = listOf(
            agent("web", source = AgentSource.WEBSITE),
            agent("slack", source = AgentSource.SLACK),
            agent("api", source = AgentSource.API),
            agent("mine", source = AgentSource.API),
            agent("sdk", source = AgentSource.SDK),
            agent("grok", source = AgentSource.GROK_BOT),
            // Cursor's own coding subagent; a cloud subagent or side chat by source is a Project's child and not listed here at all.
            agent("subagent", source = AgentSource.SAND_CODING_SUBAGENT),
            agent("unasked", source = null),
        )
        val local = LocalAgentState(launchedHereIds = setOf("mine"))
        fun ids(vararg sources: SourceFilter) =
            AgentListOrganizer.organize(agents, ListPreferences(sources = sources.toSet()), local, nowMillis = now, zone = zone).flatMap { it.rows }.map { it.agent.id }
        assertThat(ids(SourceFilter.Web)).containsExactly("web")
        assertThat(ids(SourceFilter.Slack)).containsExactly("slack")
        // A chat launched here is the account's API chat too, but shows under "This device" and not under "API".
        assertThat(ids(SourceFilter.Api)).containsExactly("api")
        assertThat(ids(SourceFilter.ThisDevice)).containsExactly("mine")
        assertThat(ids(SourceFilter.Sdk, SourceFilter.GrokBot)).containsExactly("sdk", "grok")
        // Cursor's internals and a chat the account has not been asked about yet are "Other".
        assertThat(ids(SourceFilter.Other)).containsExactly("subagent", "unasked")
        assertThat(ids()).isEmpty()
        assertThat(ids(*SourceFilter.entries.toTypedArray())).hasSize(agents.size)
    }

    @Test
    fun `every source the account can report lands in one filter entry`() {
        // The apps
        assertThat(SourceFilter.of(AgentSource.EDITOR)).isEqualTo(SourceFilter.Desktop)
        assertThat(SourceFilter.of(AgentSource.LOCAL)).isEqualTo(SourceFilter.Desktop)
        assertThat(SourceFilter.of(AgentSource.WEBSITE)).isEqualTo(SourceFilter.Web)
        assertThat(SourceFilter.of(AgentSource.IOS_APP)).isEqualTo(SourceFilter.Mobile)
        assertThat(SourceFilter.of(AgentSource.CLI)).isEqualTo(SourceFilter.Cli)
        // The integrations
        assertThat(SourceFilter.of(AgentSource.TEAMS)).isEqualTo(SourceFilter.Teams)
        assertThat(SourceFilter.of(AgentSource.LINEAR)).isEqualTo(SourceFilter.Linear)
        assertThat(SourceFilter.of(AgentSource.JIRA)).isEqualTo(SourceFilter.Jira)
        for (scm in listOf(AgentSource.GITHUB, AgentSource.GITLAB, AgentSource.BITBUCKET, AgentSource.ORIGIN)) {
            assertThat(SourceFilter.of(scm)).isEqualTo(SourceFilter.SourceControl)
        }
        // Programmatic and Cursor's bots
        assertThat(SourceFilter.of(AgentSource.AUTOMATIONS)).isEqualTo(SourceFilter.Automations)
        assertThat(SourceFilter.of(AgentSource.BUGBOT_AUTOFIX)).isEqualTo(SourceFilter.Bugbot)
        assertThat(SourceFilter.of(AgentSource.GITHUB_CI_AUTOFIX)).isEqualTo(SourceFilter.Bugbot)
        assertThat(SourceFilter.of(AgentSource.UNSPECIFIED)).isEqualTo(SourceFilter.Other)
        assertThat(SourceFilter.of(AgentSource.UNKNOWN)).isEqualTo(SourceFilter.Other)
        // Nothing the proto names is left without a home (the `when` is exhaustive, so this guards the enum itself).
        assertThat(AgentSource.entries.map { SourceFilter.of(it) }.toSet()).containsAtLeastElementsIn(SourceFilter.entries - SourceFilter.ThisDevice)
    }

    @Test
    fun `sources are read as the account service spells them`() {
        assertThat(AgentSource.parse("BACKGROUND_COMPOSER_SOURCE_SLACK")).isEqualTo(AgentSource.SLACK)
        assertThat(AgentSource.parse("grok_bot")).isEqualTo(AgentSource.GROK_BOT)
        assertThat(AgentSource.parse("21")).isEqualTo(AgentSource.SDK)
        assertThat(AgentSource.parse("0")).isEqualTo(AgentSource.UNSPECIFIED)
        // A value this build has not heard of is still an answer; nothing at all is not.
        assertThat(AgentSource.parse("BACKGROUND_COMPOSER_SOURCE_HOLOGRAM")).isEqualTo(AgentSource.UNKNOWN)
        assertThat(AgentSource.parse("99")).isEqualTo(AgentSource.UNKNOWN)
        assertThat(AgentSource.parse(null)).isNull()
        assertThat(AgentSource.parse(" ")).isNull()
        assertThat(AgentSource.SDK.wireName).isEqualTo("BACKGROUND_COMPOSER_SOURCE_SDK")
    }

    @Test
    fun `source filter saved before the Environment filter existed moves its environments over`() {
        // Every entry checked, as the default was: everything stays on.
        val all = ListPreferences.decode(CursorJson, """{"sources":["Cloud","Pool","Machine","ThisDevice"]}""")
        assertThat(all).isEqualTo(ListPreferences())
        assertThat(all.isDefault).isTrue()

        // My machine and this device's chats hidden: the machines stay hidden (now under Environment), this device's too.
        val narrowed = ListPreferences.decode(CursorJson, """{"groupBy":"Repo","sources":["Cloud","Pool"]}""")
        assertThat(narrowed.groupBy).isEqualTo(GroupBy.Repo)
        assertThat(narrowed.environments).containsExactly(EnvironmentFilter.Cloud, EnvironmentFilter.Pool)
        assertThat(narrowed.sources).containsExactlyElementsIn(SourceFilter.entries - SourceFilter.ThisDevice)

        // A record from this version is read as it is, unknown names dropped rather than failing everything.
        val current = ListPreferences.decode(CursorJson, """{"sources":["Slack","ThisDevice","Telegram"],"environments":["Machine","Orbit"]}""")
        assertThat(current.sources).containsExactly(SourceFilter.Slack, SourceFilter.ThisDevice)
        assertThat(current.environments).containsExactly(EnvironmentFilter.Machine)

        val roundTrip = ListPreferences(sources = setOf(SourceFilter.Api, SourceFilter.GrokBot), environments = setOf(EnvironmentFilter.Pool))
        assertThat(ListPreferences.decode(CursorJson, CursorJson.encodeToString(ListPreferences.serializer(), roundTrip))).isEqualTo(roundTrip)
        assertThat(ListPreferences.decode(CursorJson, "not json")).isEqualTo(ListPreferences())
    }

    @Test
    fun `the source and environment pages pick agents apart on their own`() {
        val agents = listOf(
            agent("cloud", source = AgentSource.WEBSITE),
            agent("mine-on-machine", source = AgentSource.API, env = EnvType.MACHINE),
            agent("theirs-on-machine", source = AgentSource.WEBSITE, env = EnvType.MACHINE),
        )
        val local = LocalAgentState(launchedHereIds = setOf("mine-on-machine"))
        fun ids(prefs: ListPreferences) =
            AgentListOrganizer.organize(agents, prefs, local, nowMillis = now, zone = zone).flatMap { it.rows }.map { it.agent.id }
        // "This device" is where a chat was started, so it holds the one launched here whatever that one runs on...
        assertThat(ids(ListPreferences(sources = setOf(SourceFilter.ThisDevice)))).containsExactly("mine-on-machine")
        // ...while the environment page is what a chat runs on, this device's launches included.
        assertThat(ids(ListPreferences(environments = setOf(EnvironmentFilter.Machine)))).containsExactly("mine-on-machine", "theirs-on-machine")
        // Either page narrows what the other left, so each can be picked apart on its own.
        assertThat(ids(ListPreferences(sources = setOf(SourceFilter.Web), environments = setOf(EnvironmentFilter.Machine)))).containsExactly("theirs-on-machine")
    }

    @Test
    fun `search matches name, repo and branch case-insensitively`() {
        val a = agent("a", name = "Fix login bug", branch = "cursor/login-fix")
        assertThat(AgentListOrganizer.matchesQuery(a, "LOGIN")).isTrue()
        assertThat(AgentListOrganizer.matchesQuery(a, "acme/app")).isTrue()
        assertThat(AgentListOrganizer.matchesQuery(a, "cursor/login")).isTrue()
        assertThat(AgentListOrganizer.matchesQuery(a, "payments")).isFalse()
    }

    @Test
    fun `recent rows ignore search and still honour Chats filters`() {
        val agents = listOf(
            agent("login", name = "Fix login", updatedAgo = hour),
            agent("billing", name = "Billing pipeline"),
            agent("gone", name = "Login leftover", lifecycle = AgentLifecycle.ARCHIVED),
        )
        val prefs = ListPreferences()
        val local = LocalAgentState()
        val searched = AgentListOrganizer.organize(agents, prefs, local, query = "login", nowMillis = now, zone = zone)
        assertThat(searched.flatMap { it.rows }.map { it.agent.id }).containsExactly("login")

        val recent = AgentListOrganizer.recentRows(agents, prefs, local, nowMillis = now, zone = zone)
        assertThat(recent.map { it.agent.id }).containsExactly("billing", "login").inOrder()
    }

    @Test
    fun `sorting by name and by created`() {
        val agents = listOf(agent("b", name = "Beta", updatedAgo = 0), agent("a", name = "alpha", updatedAgo = hour))
        val byName = AgentListOrganizer.organize(agents, ListPreferences(groupBy = GroupBy.None, sortOrder = SortOrder.Name), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(byName.single().rows.map { it.agent.id }).containsExactly("a", "b").inOrder()
        val byUpdated = AgentListOrganizer.organize(agents, ListPreferences(groupBy = GroupBy.None), LocalAgentState(), nowMillis = now, zone = zone)
        assertThat(byUpdated.single().rows.map { it.agent.id }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `filter summaries mirror the Customize sheet`() {
        val prefs = ListPreferences()
        assertThat(prefs.summaryFor(FilterKind.Repo)).isEqualTo("All")
        assertThat(prefs.summaryFor(FilterKind.Status)).isEqualTo("Read +3")
        // Every entry checked reads "All", whatever the filter; the default Status leaves Archived and Snoozed out.
        assertThat(prefs.summaryFor(FilterKind.Git)).isEqualTo("All")
        assertThat(prefs.copy(git = setOf(GitFilter.Merged, GitFilter.Closed)).summaryFor(FilterKind.Git)).isEqualTo("Merged +1")
        assertThat(prefs.summaryFor(FilterKind.Source)).isEqualTo("All")
        assertThat(prefs.copy(sources = setOf(SourceFilter.Slack, SourceFilter.Api, SourceFilter.Sdk)).summaryFor(FilterKind.Source)).isEqualTo("Slack +2")
        assertThat(prefs.summaryFor(FilterKind.Environment)).isEqualTo("All")
        assertThat(prefs.copy(environments = setOf(EnvironmentFilter.Machine)).summaryFor(FilterKind.Environment)).isEqualTo("My machine")
        assertThat(prefs.copy(repos = setOf("acme/app")).summaryFor(FilterKind.Repo)).isEqualTo("app")
        assertThat(prefs.copy(statuses = emptySet()).summaryFor(FilterKind.Status)).isEqualTo("None")
        assertThat(prefs.copy(statuses = StatusFilter.entries.toSet()).summaryFor(FilterKind.Status)).isEqualTo("All")
        val oddStatus = CursorJson.decodeFromString(ListPreferences.serializer(), """{"sortOrder":"Name","statuses":["Read","Snoozed","Muted"]}""")
        assertThat(oddStatus.sortOrder).isEqualTo(SortOrder.Name)
        assertThat(oddStatus.statuses).containsExactly(StatusFilter.Read, StatusFilter.Snoozed)
    }

    @Test
    fun `repo slug parsing`() {
        assertThat(Agent.repoSlugOf("https://github.com/acme/app")).isEqualTo("acme/app")
        assertThat(Agent.repoSlugOf("https://github.com/acme/app.git/")).isEqualTo("acme/app")
        assertThat(Agent.repoSlugOf("github.com/acme/app")).isEqualTo("acme/app")
    }
}
