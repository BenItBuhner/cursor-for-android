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
    )

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
    fun `active lifecycle without run status counts as running`() {
        val a = agent("x", lifecycle = AgentLifecycle.ACTIVE, runStatus = null)
        assertThat(a.isRunning).isTrue()
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
    fun `source filter uses environment type and locally launched agents`() {
        val agents = listOf(
            agent("cloud"),
            agent("machine", env = EnvType.MACHINE),
            agent("mine"),
        )
        val local = LocalAgentState(launchedHereIds = setOf("mine"))
        fun ids(prefs: ListPreferences) = AgentListOrganizer.organize(agents, prefs, local, nowMillis = now, zone = zone).flatMap { it.rows }.map { it.agent.id }
        assertThat(ids(ListPreferences(sources = setOf(SourceFilter.Machine)))).containsExactly("machine")
        assertThat(ids(ListPreferences(sources = setOf(SourceFilter.Cloud)))).containsExactly("cloud")
        assertThat(ids(ListPreferences(sources = setOf(SourceFilter.Cloud, SourceFilter.ThisDevice)))).containsExactly("cloud", "mine")
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
        assertThat(prefs.summaryFor(FilterKind.Git)).isEqualTo("Open +4")
        assertThat(prefs.copy(git = setOf(GitFilter.Merged, GitFilter.Closed)).summaryFor(FilterKind.Git)).isEqualTo("Merged +1")
        assertThat(prefs.summaryFor(FilterKind.Source)).isEqualTo("Cloud +3")
        assertThat(prefs.copy(repos = setOf("acme/app")).summaryFor(FilterKind.Repo)).isEqualTo("app")
        assertThat(prefs.copy(statuses = emptySet()).summaryFor(FilterKind.Status)).isEqualTo("None")
    }

    @Test
    fun `repo slug parsing`() {
        assertThat(Agent.repoSlugOf("https://github.com/acme/app")).isEqualTo("acme/app")
        assertThat(Agent.repoSlugOf("https://github.com/acme/app.git/")).isEqualTo("acme/app")
        assertThat(Agent.repoSlugOf("github.com/acme/app")).isEqualTo("acme/app")
    }
}
