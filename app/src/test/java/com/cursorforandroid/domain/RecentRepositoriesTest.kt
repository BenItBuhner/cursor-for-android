package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecentRepositoriesTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun agent(
        id: String,
        repo: String? = "https://github.com/acme/app",
        updatedAgo: Long = 0,
    ) = Agent(
        id = id,
        name = id,
        lifecycle = AgentLifecycle.IDLE,
        runStatus = RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = now - updatedAgo - hour,
        updatedAtMillis = now - updatedAgo,
        latestRunId = "run-$id",
        repoUrl = repo,
        startingRef = "main",
    )

    private fun repos(vararg slugs: String) = slugs.map { Repository("https://github.com/$it") }

    @Test
    fun `only repositories with activity inside the week are pinned, most recent first`() {
        val catalog = repos("acme/old", "acme/app", "acme/web", "acme/idle")
        val agents = listOf(
            agent("web", repo = "https://github.com/acme/web", updatedAgo = 2 * hour),
            agent("app", repo = "https://github.com/acme/app", updatedAgo = hour),
            agent("stale", repo = "https://github.com/acme/old", updatedAgo = 8 * day),
        )
        val split = RecentRepositories.partition(catalog, agents, now)
        assertThat(split.recent.map { it.shortName }).containsExactly("app", "web").inOrder()
        assertThat(split.rest.map { it.shortName }).containsExactly("old", "idle").inOrder()
    }

    @Test
    fun `two repositories worked on for weeks stay two, not a padded ten`() {
        val catalog = repos("acme/a", "acme/b", "acme/c", "acme/d", "acme/e")
        val agents = listOf(
            agent("a-new", repo = "https://github.com/acme/a", updatedAgo = hour),
            agent("a-old", repo = "https://github.com/acme/a", updatedAgo = 20 * day),
            agent("b-new", repo = "https://github.com/acme/b", updatedAgo = 2 * day),
            agent("b-old", repo = "https://github.com/acme/b", updatedAgo = 30 * day),
            agent("c-old", repo = "https://github.com/acme/c", updatedAgo = 10 * day),
        )
        val split = RecentRepositories.partition(catalog, agents, now)
        assertThat(split.recent.map { it.shortName }).containsExactly("a", "b").inOrder()
        assertThat(split.rest.map { it.shortName }).containsExactly("c", "d", "e").inOrder()
    }

    @Test
    fun `more than ten active this week keeps only the ten newest`() {
        val catalog = (1..15).map { Repository("https://github.com/acme/r$it") }
        val agents = (1..15).map { n ->
            agent("r$n", repo = "https://github.com/acme/r$n", updatedAgo = n * hour)
        }
        val split = RecentRepositories.partition(catalog, agents, now)
        assertThat(split.recent.map { it.shortName }).containsExactly(
            "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10",
        ).inOrder()
        assertThat(split.rest.map { it.shortName }).containsExactly("r11", "r12", "r13", "r14", "r15").inOrder()
    }

    @Test
    fun `activity still inside the week is recent, a week or older is exempt`() {
        val catalog = repos("acme/edge", "acme/stale")
        val agents = listOf(
            agent("edge", repo = "https://github.com/acme/edge", updatedAgo = RecentRepositories.RECENCY_WINDOW_MS - 1),
            agent("stale", repo = "https://github.com/acme/stale", updatedAgo = RecentRepositories.RECENCY_WINDOW_MS),
        )
        val split = RecentRepositories.partition(catalog, agents, now)
        assertThat(split.recent.map { it.shortName }).containsExactly("edge")
        assertThat(split.rest.map { it.shortName }).containsExactly("stale")
    }

    @Test
    fun `repositories match on their slug regardless of scheme, suffix and case`() {
        val catalog = listOf(Repository("https://github.com/acme/app"))
        val agents = listOf(agent("a", repo = "https://github.com/Acme/App.git", updatedAgo = hour))
        val split = RecentRepositories.partition(catalog, agents, now)
        assertThat(split.recent.map { it.slug }).containsExactly("acme/app")
        assertThat(split.rest).isEmpty()
    }

    @Test
    fun `no-repo agents and repositories missing from the catalogue contribute nothing`() {
        val catalog = repos("acme/app")
        val agents = listOf(
            agent("none", repo = null),
            agent("blank", repo = "  "),
            agent("elsewhere", repo = "https://github.com/acme/web", updatedAgo = hour),
        )
        val split = RecentRepositories.partition(catalog, agents, now)
        assertThat(split.recent).isEmpty()
        assertThat(split.rest.map { it.shortName }).containsExactly("app")
    }

    @Test
    fun `the latest chat of a repository is what ranks it`() {
        val catalog = repos("acme/app", "acme/web")
        val agents = listOf(
            agent("app-old", repo = "https://github.com/acme/app", updatedAgo = 3 * day),
            agent("app-new", repo = "https://github.com/acme/app", updatedAgo = hour),
            agent("web", repo = "https://github.com/acme/web", updatedAgo = 2 * hour),
        )
        val lastUsed = RecentRepositories.lastUsedBySlug(agents)
        assertThat(lastUsed.getValue("acme/app")).isEqualTo(now - hour)
        val split = RecentRepositories.partition(catalog, agents, now)
        assertThat(split.recent.map { it.shortName }).containsExactly("app", "web").inOrder()
    }

    @Test
    fun `two spellings of one unused repository show once in the remainder`() {
        val catalog = listOf(
            Repository("https://github.com/acme/app.git"),
            Repository("github.com/acme/app"),
            Repository("https://github.com/acme/web"),
        )
        val split = RecentRepositories.partition(catalog, emptyList(), now)
        assertThat(split.recent).isEmpty()
        assertThat(split.rest.map { it.shortName }).containsExactly("app", "web")
    }

    @Test
    fun `an empty catalogue or empty agent list pins nothing`() {
        val catalog = repos("acme/app")
        assertThat(RecentRepositories.partition(emptyList(), listOf(agent("a")), now).recent).isEmpty()
        assertThat(RecentRepositories.partition(catalog, emptyList(), now).recent).isEmpty()
        assertThat(RecentRepositories.partition(catalog, emptyList(), now).rest.map { it.shortName }).containsExactly("app")
    }
}
