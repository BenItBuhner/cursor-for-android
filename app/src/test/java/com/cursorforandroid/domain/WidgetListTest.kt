package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneOffset

class WidgetListTest {

    private val zone = ZoneOffset.UTC
    private val now = 1_800_000_000_000L // 2027-01-15T08:00:00Z
    private val hour = 3_600_000L

    private fun agent(
        id: String,
        updatedAgo: Long = 0,
        lifecycle: AgentLifecycle = AgentLifecycle.IDLE,
        runStatus: RunStatus? = RunStatus.FINISHED,
        repo: String? = "https://github.com/acme/app",
    ) = Agent(
        id = id,
        name = id,
        lifecycle = lifecycle,
        runStatus = runStatus,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = now - updatedAgo - hour,
        updatedAtMillis = now - updatedAgo,
        latestRunId = "run-$id",
        repoUrl = repo,
        startingRef = "main",
    )

    private fun running(id: String, updatedAgo: Long = 0, repo: String? = "https://github.com/acme/app") =
        agent(id, updatedAgo, lifecycle = AgentLifecycle.ACTIVE, runStatus = RunStatus.RUNNING, repo = repo)

    private fun rows(mode: WidgetMode, agents: List<Agent>, prefs: ListPreferences = ListPreferences(), local: LocalAgentState = LocalAgentState()) =
        WidgetList.rows(mode, agents, prefs, local, nowMillis = now, zone = zone).map { it.agent.id }

    @Test
    fun `recent is every visible chat newest first, pinned or not`() {
        val agents = listOf(agent("older", updatedAgo = 3 * hour), agent("newest"), agent("pinned", updatedAgo = hour), running("working", updatedAgo = 2 * hour))
        assertThat(rows(WidgetMode.Recent, agents, local = LocalAgentState(pinnedIds = setOf("pinned"))))
            .containsExactly("newest", "pinned", "working", "older").inOrder()
    }

    @Test
    fun `recent honours the Chats filters like the New Chat pane does`() {
        val agents = listOf(agent("kept"), agent("archived", lifecycle = AgentLifecycle.ARCHIVED), agent("other-repo", repo = "https://github.com/acme/other"))
        // Archived is off by default; the repo filter narrows further.
        assertThat(rows(WidgetMode.Recent, agents)).containsExactly("kept", "other-repo").inOrder()
        assertThat(rows(WidgetMode.Recent, agents, prefs = ListPreferences(repos = setOf("acme/app")))).containsExactly("kept")
        assertThat(rows(WidgetMode.Recent, agents, prefs = ListPreferences(statuses = StatusFilter.entries.toSet()))).contains("archived")
    }

    @Test
    fun `pinned is the sidebar's Pinned group in the sidebar's order`() {
        val agents = listOf(agent("a", updatedAgo = 2 * hour), agent("b"), agent("c", updatedAgo = hour), agent("unpinned"))
        val local = LocalAgentState(pinnedIds = setOf("a", "c"))
        assertThat(rows(WidgetMode.Pinned, agents, local = local)).containsExactly("c", "a").inOrder()
        assertThat(rows(WidgetMode.Pinned, agents, prefs = ListPreferences(sortOrder = SortOrder.Name), local = local)).containsExactly("a", "c").inOrder()
        assertThat(rows(WidgetMode.Pinned, agents)).isEmpty()
    }

    @Test
    fun `running lists working agents even when the Status filter hides them`() {
        val agents = listOf(running("w1", updatedAgo = hour), running("w2"), agent("done"), agent("failed", runStatus = RunStatus.ERROR))
        val hidden = ListPreferences(statuses = setOf(StatusFilter.Read))
        assertThat(rows(WidgetMode.Running, agents, prefs = hidden)).containsExactly("w2", "w1").inOrder()
        // The other filters still apply.
        assertThat(rows(WidgetMode.Running, agents + running("elsewhere", repo = "https://github.com/acme/other"), prefs = ListPreferences(repos = setOf("acme/app"))))
            .containsExactly("w2", "w1").inOrder()
    }

    @Test
    fun `a snoozed agent stays on Recent and off Running`() {
        val agents = listOf(agent("kept"), running("working"), agent("quiet"))
        val local = LocalAgentState(
            snoozedUntil = mapOf("quiet" to now + hour, "working" to now + hour),
            snoozedAt = mapOf("quiet" to now, "working" to now),
        )
        assertThat(rows(WidgetMode.Recent, agents, local = local)).containsExactly("kept", "working", "quiet")
        assertThat(rows(WidgetMode.Running, agents, local = local)).isEmpty()
    }

    @Test
    fun `an archived agent is never running`() {
        val archived = agent("gone", lifecycle = AgentLifecycle.ARCHIVED, runStatus = RunStatus.RUNNING)
        assertThat(rows(WidgetMode.Running, listOf(archived))).isEmpty()
    }

    @Test
    fun `lists are capped`() {
        val many = (0 until 50).map { agent("a$it", updatedAgo = it * hour) }
        assertThat(rows(WidgetMode.Recent, many)).hasSize(WidgetList.MAX_ROWS)
        assertThat(rows(WidgetMode.Recent, many).first()).isEqualTo("a0")
    }

    @Test
    fun `mode parsing falls back to the default`() {
        assertThat(WidgetMode.parse("Pinned")).isEqualTo(WidgetMode.Pinned)
        assertThat(WidgetMode.parse(null)).isEqualTo(WidgetMode.Recent)
        assertThat(WidgetMode.parse("nonsense")).isEqualTo(WidgetMode.Recent)
    }
}
