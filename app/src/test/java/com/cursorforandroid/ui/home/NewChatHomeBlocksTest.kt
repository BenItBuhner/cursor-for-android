package com.cursorforandroid.ui.home

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What the New Chat pane lists under its composer for each New chat page setting, and what a Project shortcut says. */
class NewChatHomeBlocksTest {

    private fun agent(id: String, running: Boolean = false, appearance: ProjectAppearance? = null, parent: AgentParent? = null) = Agent(
        id = id,
        name = id,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
        latestRunId = null,
        repoUrl = "https://github.com/acme/$id",
        startingRef = "main",
        isProject = appearance != null,
        projectAppearance = appearance,
        parent = parent,
    )

    private fun row(agent: Agent, children: List<AgentRow> = emptyList()) = AgentRow(
        agent = agent,
        indicator = if (agent.runStatus == RunStatus.RUNNING) AgentIndicator.Running else AgentIndicator.Read,
        isPinned = false,
        isUnread = false,
        launchedFromThisDevice = false,
        children = children,
    )

    private val recents = listOf(row(agent("cli")), row(agent("codex", running = true)))
    private val projects = listOf(row(agent("billing", appearance = ProjectAppearance("rocket", "purple"))))
    private val loaded = AgentListUiState(recentRows = recents, projectRows = projects, hasLoaded = true)

    private fun chats(blocks: List<HomeBlock>) = blocks.filterIsInstance<HomeBlock.Chat>().map { it.row.agent.id }

    @Test
    fun `Recent lists the recent chats and never the Projects`() {
        val blocks = homeBlocks(NewChatHome.RECENT, loaded, projectsAvailable = true)
        assertThat(chats(blocks)).containsExactly("cli", "codex").inOrder()
        assertThat(blocks.filterIsInstance<HomeBlock.Projects>()).isEmpty()
    }

    @Test
    fun `Recent with nothing to list says so once the list has loaded, and nothing before`() {
        assertThat(homeBlocks(NewChatHome.RECENT, AgentListUiState(hasLoaded = true), projectsAvailable = true)).containsExactly(HomeBlock.Empty(null))
        assertThat(homeBlocks(NewChatHome.RECENT, AgentListUiState(hasLoaded = true, error = "Offline"), projectsAvailable = true)).containsExactly(HomeBlock.Empty("Offline"))
        assertThat(homeBlocks(NewChatHome.RECENT, AgentListUiState(), projectsAvailable = true)).isEmpty()
    }

    @Test
    fun `Projects pins the Projects alone`() {
        assertThat(homeBlocks(NewChatHome.PROJECTS, loaded, projectsAvailable = true)).containsExactly(HomeBlock.Projects(projects))
    }

    @Test
    fun `Projects with Extended mode off says so over the recent chats, whatever the list holds`() {
        val blocks = homeBlocks(NewChatHome.PROJECTS, loaded, projectsAvailable = false)
        assertThat(blocks.first()).isEqualTo(HomeBlock.Note(ProjectsNote.NeedsExtendedMode))
        assertThat(chats(blocks)).containsExactly("cli", "codex").inOrder()
        assertThat(blocks.filterIsInstance<HomeBlock.Projects>()).isEmpty()
    }

    @Test
    fun `Projects with none yet offers a first one over the recent chats`() {
        val blocks = homeBlocks(NewChatHome.PROJECTS, loaded.copy(projectRows = emptyList()), projectsAvailable = true)
        assertThat(blocks.first()).isEqualTo(HomeBlock.Note(ProjectsNote.NoProjects))
        assertThat(chats(blocks)).containsExactly("cli", "codex").inOrder()
    }

    @Test
    fun `Projects says nothing about none before the list has loaded, and shows a failed read as the error`() {
        assertThat(homeBlocks(NewChatHome.PROJECTS, AgentListUiState(), projectsAvailable = true)).isEmpty()
        assertThat(homeBlocks(NewChatHome.PROJECTS, AgentListUiState(hasLoaded = true, error = "Offline"), projectsAvailable = true))
            .containsExactly(HomeBlock.Empty("Offline"))
    }

    @Test
    fun `Composer only lists nothing, whatever the list holds or however it was read`() {
        for (list in listOf(loaded, AgentListUiState(), AgentListUiState(hasLoaded = true, error = "Offline"))) {
            for (available in listOf(true, false)) assertThat(homeBlocks(NewChatHome.COMPOSER, list, projectsAvailable = available)).isEmpty()
        }
    }

    @Test
    fun `nothing is listed until the setting has been read`() {
        assertThat(homeBlocks(null, loaded, projectsAvailable = true)).isEmpty()
    }

    @Test
    fun `every block has a key of its own, so the pane's lazy list can hold them together`() {
        val blocks = homeBlocks(NewChatHome.PROJECTS, loaded.copy(projectRows = emptyList()), projectsAvailable = true)
        assertThat(blocks.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `a shortcut counts its working chats beside the glyph from two on, the glyph alone meaning one`() {
        assertThat(NewChatHomeCopy.workingCount(0)).isNull()
        assertThat(NewChatHomeCopy.workingCount(1)).isNull()
        assertThat(NewChatHomeCopy.workingCount(2)).isEqualTo("2")
        assertThat(NewChatHomeCopy.workingCount(12)).isEqualTo("12")
    }

    @Test
    fun `a shortcut's working glyph tells accessibility services how many agents are working`() {
        assertThat(NewChatHomeCopy.workingLabel(1)).isEqualTo("1 agent working")
        assertThat(NewChatHomeCopy.workingLabel(3)).isEqualTo("3 agents working")
    }

    @Test
    fun `what a shortcut counts is every chat at work under its Project, the coordinator's own turn included`() {
        val worker = AgentParent("billing", AgentParentKind.PROJECT_WORKER)
        fun project(running: Boolean, vararg children: AgentRow) = row(agent("billing", running = running, appearance = ProjectAppearance("rocket", "purple")), children.toList())
        assertThat(project(false, row(agent("w1", running = true, parent = worker)), row(agent("w2", running = true, parent = worker))).workingCount).isEqualTo(2)
        assertThat(project(true, row(agent("w1", running = true, parent = worker)), row(agent("w2", parent = worker))).workingCount).isEqualTo(2)
        assertThat(project(false, row(agent("w1", parent = worker))).workingCount).isEqualTo(0)
    }
}
