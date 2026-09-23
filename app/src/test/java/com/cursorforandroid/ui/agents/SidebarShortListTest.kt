package com.cursorforandroid.ui.agents

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The five-row cut of a long Projects or Pinned group, and the per-visit memory of which groups are listed in full. */
class SidebarShortListTest {

    private val projects = (1..12).map { row("p$it") }

    private fun ids(cut: SidebarShortList.Cut) = cut.rows.map { it.agent.id }

    @Test
    fun `a group of five or fewer is not cut`() {
        listOf(0, 1, 5).forEach { size ->
            val rows = projects.take(size)
            val cut = SidebarShortList.cut(rows, selectedId = null)
            assertThat(cut.rows).isEqualTo(rows)
            assertThat(cut.hidden).isEqualTo(0)
        }
    }

    @Test
    fun `a longer group shows its first five rows in order and counts the rest`() {
        val cut = SidebarShortList.cut(projects, selectedId = null)
        assertThat(ids(cut)).containsExactly("p1", "p2", "p3", "p4", "p5").inOrder()
        assertThat(cut.hidden).isEqualTo(7)

        assertThat(SidebarShortList.cut(projects.take(6), selectedId = null).hidden).isEqualTo(1)
    }

    @Test
    fun `the open row past the fifth is kept as the sixth, and one within the five changes nothing`() {
        val past = SidebarShortList.cut(projects, selectedId = "p9")
        assertThat(ids(past)).containsExactly("p1", "p2", "p3", "p4", "p5", "p9").inOrder()
        assertThat(past.hidden).isEqualTo(6)

        val within = SidebarShortList.cut(projects, selectedId = "p3")
        assertThat(ids(within)).containsExactly("p1", "p2", "p3", "p4", "p5").inOrder()
        assertThat(within.hidden).isEqualTo(7)

        // Nothing is left to hold back when the kept row was the only one past the cut.
        assertThat(SidebarShortList.cut(projects.take(6), selectedId = "p6").hidden).isEqualTo(0)
        // An id the group does not hold keeps nothing.
        assertThat(ids(SidebarShortList.cut(projects, selectedId = "elsewhere"))).hasSize(5)
    }

    @Test
    fun `a chat open under a Project past the cut keeps that Project`() {
        val worker = row("p10-worker", children = listOf(row("p10-worker-sub")))
        val rows = projects.map { if (it.agent.id == "p10") it.copy(children = listOf(worker)) else it }
        assertThat(ids(SidebarShortList.cut(rows, selectedId = "p10-worker"))).containsExactly("p1", "p2", "p3", "p4", "p5", "p10").inOrder()
        assertThat(ids(SidebarShortList.cut(rows, selectedId = "p10-worker-sub")).last()).isEqualTo("p10")
    }

    @Test
    fun `only the Projects and Pinned groups are cut`() {
        assertThat(SidebarShortList.KEYS).containsExactly(AgentListOrganizer.PROJECTS_KEY, AgentListOrganizer.PINNED_KEY)
    }

    @Test
    fun `the row reads Show N more, then Show less`() {
        assertThat(SidebarShortList.showMore(7)).isEqualTo("Show 7 more")
        assertThat(SidebarShortList.SHOW_LESS).isEqualTo("Show less")
    }

    @Test
    fun `each group is listed in full on its own, and a reset cuts every one back`() {
        val lists = SidebarShortLists()
        assertThat(lists.isExpanded(AgentListOrganizer.PROJECTS_KEY)).isFalse()

        lists.expand(AgentListOrganizer.PROJECTS_KEY)
        assertThat(lists.isExpanded(AgentListOrganizer.PROJECTS_KEY)).isTrue()
        assertThat(lists.isExpanded(AgentListOrganizer.PINNED_KEY)).isFalse()

        lists.expand(AgentListOrganizer.PINNED_KEY)
        lists.collapse(AgentListOrganizer.PROJECTS_KEY)
        assertThat(lists.isExpanded(AgentListOrganizer.PROJECTS_KEY)).isFalse()
        assertThat(lists.isExpanded(AgentListOrganizer.PINNED_KEY)).isTrue()

        lists.expand(AgentListOrganizer.PROJECTS_KEY)
        lists.reset()
        assertThat(lists.isExpanded(AgentListOrganizer.PROJECTS_KEY)).isFalse()
        assertThat(lists.isExpanded(AgentListOrganizer.PINNED_KEY)).isFalse()
    }

    private fun row(id: String, children: List<AgentRow> = emptyList()) = AgentRow(
        agent = Agent(
            id = id,
            name = id,
            lifecycle = AgentLifecycle.IDLE,
            runStatus = RunStatus.FINISHED,
            envType = EnvType.CLOUD,
            envName = null,
            url = "https://cursor.com/agents/$id",
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
            latestRunId = null,
            repoUrl = null,
            startingRef = null,
            isProject = true,
        ),
        indicator = AgentIndicator.Read,
        isPinned = false,
        isUnread = false,
        launchedFromThisDevice = false,
        children = children,
    )
}
