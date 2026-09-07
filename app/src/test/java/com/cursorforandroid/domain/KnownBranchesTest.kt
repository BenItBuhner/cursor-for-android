package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KnownBranchesTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun agent(
        id: String,
        repo: String? = "https://github.com/acme/app",
        ref: String? = "main",
        updatedAgo: Long = 0,
        vararg pushed: GitBranch,
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
        startingRef = ref,
        branches = pushed.toList(),
    )

    private fun pushed(branch: String, repo: String = "github.com/acme/app") = GitBranch(repo, branch, null)

    @Test
    fun `starting refs and pushed branches of the repository are listed, most recently active first`() {
        val agents = listOf(
            agent("newest", updatedAgo = 0, pushed = arrayOf(pushed("cursor/newest-1a2b"))),
            agent("older", ref = "develop", updatedAgo = 2 * hour, pushed = arrayOf(pushed("cursor/older-3c4d"))),
        )
        val names = KnownBranches.forRepository(agents, "https://github.com/acme/app").map { it.name }
        assertThat(names).containsExactly("main", "cursor/newest-1a2b", "develop", "cursor/older-3c4d").inOrder()
    }

    @Test
    fun `a branch describes where the app learned about it`() {
        val agents = listOf(
            agent("Fix the build", updatedAgo = 0, pushed = arrayOf(pushed("cursor/fix-build-9f8e"))),
            agent("second", updatedAgo = hour),
            agent("third", ref = "release/1.2", updatedAgo = 2 * hour),
        )
        val byName = KnownBranches.forRepository(agents, "https://github.com/acme/app").associateBy { it.name }
        assertThat(byName.getValue("cursor/fix-build-9f8e").description).isEqualTo("Pushed by Fix the build")
        assertThat(byName.getValue("main").description).isEqualTo("Starting point of 2 agents")
        assertThat(byName.getValue("release/1.2").description).isEqualTo("Starting point of 1 agent")
    }

    @Test
    fun `the most recent pusher of a shared branch is named`() {
        val agents = listOf(
            agent("later", updatedAgo = 0, pushed = arrayOf(pushed("cursor/shared-0000"))),
            agent("earlier", updatedAgo = hour, pushed = arrayOf(pushed("cursor/shared-0000"))),
        )
        val shared = KnownBranches.forRepository(agents, "https://github.com/acme/app").single { it.name == "cursor/shared-0000" }
        assertThat(shared.pushedBy).isEqualTo("later")
        assertThat(shared.startedAgents).isEqualTo(0)
    }

    @Test
    fun `other repositories, no-repo agents and blank refs contribute nothing`() {
        val agents = listOf(
            agent("elsewhere", repo = "https://github.com/acme/web", ref = "trunk", pushed = arrayOf(pushed("cursor/web-1111", "github.com/acme/web"))),
            agent("no-repo", repo = null, ref = "main"),
            agent("blank", ref = "  "),
            agent("null-ref", ref = null, pushed = arrayOf(GitBranch("github.com/acme/app", null, "https://github.com/acme/app/pull/1"))),
        )
        assertThat(KnownBranches.forRepository(agents, "https://github.com/acme/app")).isEmpty()
        assertThat(KnownBranches.forRepository(agents, "https://github.com/acme/web").map { it.name }).containsExactly("trunk", "cursor/web-1111")
    }

    @Test
    fun `repositories match on their slug regardless of scheme, suffix and case`() {
        val agents = listOf(
            agent("a", repo = "https://github.com/Acme/App.git", pushed = arrayOf(pushed("cursor/a-1111", "github.com/acme/app"))),
            agent("b", repo = "github.com/acme/app", ref = "develop"),
        )
        val names = KnownBranches.forRepository(agents, "https://github.com/acme/app/").map { it.name }
        assertThat(names).containsExactly("main", "cursor/a-1111", "develop")
    }

    @Test
    fun `a pushed branch without a repository URL belongs to the agent's repository`() {
        // The legacy list only knows the agent's repository, so its branch entries carry a blank URL.
        val agents = listOf(agent("legacy", pushed = arrayOf(GitBranch("", "cursor/legacy-2222", null))))
        assertThat(KnownBranches.forRepository(agents, "https://github.com/acme/app").map { it.name }).containsExactly("main", "cursor/legacy-2222")
        assertThat(KnownBranches.forRepository(agents, "https://github.com/acme/web")).isEmpty()
    }

    @Test
    fun `a branch that is both a starting point and pushed appears once`() {
        val agents = listOf(
            agent("follow-up", ref = "cursor/base-3333", updatedAgo = 0),
            agent("base", updatedAgo = hour, pushed = arrayOf(pushed("cursor/base-3333"))),
        )
        val options = KnownBranches.forRepository(agents, "https://github.com/acme/app")
        val base = options.single { it.name == "cursor/base-3333" }
        assertThat(base.pushedBy).isEqualTo("base")
        assertThat(base.startedAgents).isEqualTo(1)
        assertThat(base.lastUsedAtMillis).isEqualTo(now)
    }

    @Test
    fun `an unparseable repository yields nothing`() {
        assertThat(KnownBranches.forRepository(listOf(agent("a")), "")).isEmpty()
    }
}
