package com.cursorforandroid.domain

import com.cursorforandroid.data.api.CursorJson
import com.google.common.truth.Truth.assertThat
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
    fun `chats nest under a listed parent, stand alone under an unlisted one, and a pin keeps a chat out of the tree`() {
        val agents = listOf(
            agent("project", updatedAgo = 3 * hour, isProject = true),
            agent("worker-new", updatedAgo = 0, parent = "project"),
            agent("worker-old", updatedAgo = hour, parent = "project"),
            agent("side", updatedAgo = 2 * hour, parent = "project", parentKind = AgentParentKind.SIDE_CHAT),
            agent("grandchild", updatedAgo = 30 * 60_000L, parent = "worker-old", parentKind = AgentParentKind.SUBAGENT),
            agent("orphan", updatedAgo = 4 * hour, parent = "bc-gone"),
            agent("pinned-worker", updatedAgo = 5 * hour, parent = "project"),
        )
        val local = LocalAgentState(pinnedIds = setOf("pinned-worker"))
        val sections = AgentListOrganizer.organize(agents, ListPreferences(), local, nowMillis = now, zone = zone)
        assertThat(sections.map { it.title }).containsExactly("Projects", "Pinned", "Today").inOrder()
        val project = sections.first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows.single()
        // Children in the list's order (newest first), with children of their own beneath them.
        assertThat(project.children.map { it.agent.id }).containsExactly("worker-new", "worker-old", "side").inOrder()
        assertThat(project.children[1].children.map { it.agent.id }).containsExactly("grandchild")
        assertThat(project.descendants().map { it.agent.id }).containsExactly("worker-new", "worker-old", "grandchild", "side").inOrder()
        assertThat(sections.ids(AgentListOrganizer.PINNED_KEY)).containsExactly("pinned-worker")
        assertThat(sections.ids("date:Today")).containsExactly("orphan")
        // Every chat once on the recent surface, nested ones included, newest first.
        assertThat(AgentListOrganizer.recentRows(sections).map { it.agent.id })
            .containsExactly("worker-new", "grandchild", "worker-old", "side", "project", "orphan", "pinned-worker").inOrder()
        // A filter that drops the parent leaves its children on their own; a search does the same.
        val running = AgentListOrganizer.organize(agents.map { if (it.id == "worker-new") it.copy(runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE) else it }, ListPreferences(statuses = setOf(StatusFilter.Running)), local, nowMillis = now, zone = zone)
        assertThat(running.flatMap { it.rows }.map { it.agent.id }).containsExactly("worker-new")
        val searched = AgentListOrganizer.organize(agents, ListPreferences(), local, query = "side", nowMillis = now, zone = zone)
        assertThat(searched.single().rows.single().agent.id).isEqualTo("side")
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
            .containsExactly("project" to 0, "worker" to 1, "quiet" to 1).inOrder()
        assertThat(AgentListOrganizer.flatten(sections.single().rows, setOf("project", "quiet")).map { it.row.agent.id to it.depth })
            .containsExactly("project" to 0, "worker" to 1, "quiet" to 1, "deep" to 2).inOrder()
        // Expanding a hidden parent shows nothing until its own parent is open.
        assertThat(AgentListOrganizer.flatten(sections.single().rows, setOf("quiet")).map { it.row.agent.id }).containsExactly("project")
    }

    @Test
    fun `parent links that loop, or point at the chat itself, never lose a chat`() {
        val loop = listOf(agent("a", parent = "b"), agent("b", parent = "a"), agent("self", parent = "self"))
        val rows = AgentListOrganizer.nest(loop.map { AgentListOrganizer.toRow(it, LocalAgentState(), now) })
        assertThat(rows.flatMap { listOf(it) + it.descendants() }.map { it.agent.id }).containsExactly("self", "a", "b")
        assertThat(rows.map { it.agent.id }).containsExactly("self", "a")
        assertThat(rows.first { it.agent.id == "a" }.children.map { it.agent.id }).containsExactly("b")
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
        // A filter narrows both surfaces alike.
        val onlyRunning = AgentListOrganizer.organize(agents, ListPreferences(statuses = setOf(StatusFilter.Running)), local, nowMillis = now, zone = zone)
        assertThat(AgentListOrganizer.recentRows(onlyRunning)).isEmpty()
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
        assertThat(sections.map { it.title }).containsExactly("Pinned", "Today", "Yesterday", "This week", "Older").inOrder()
        assertThat(sections.first().rows.single().agent.id).isEqualTo("pinned")
        assertThat(sections.flatMap { it.rows }.map { it.agent.id }).containsNoDuplicates()
    }

    @Test
    fun `date bucket boundaries`() {
        val today = LocalDate.of(2027, 1, 15)
        assertThat(AgentListOrganizer.dateBucket(today, today)).isEqualTo("Today")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(1), today)).isEqualTo("Yesterday")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(6), today)).isEqualTo("This week")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(7), today)).isEqualTo("Last week")
        assertThat(AgentListOrganizer.dateBucket(today.minusDays(14), today)).isEqualTo("This month")
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
            agent("subagent", source = AgentSource.AS_SUBAGENT_FROM_CLOUD),
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
