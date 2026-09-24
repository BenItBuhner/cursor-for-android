package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShortcutsTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun agent(id: String, updatedAgo: Long = 0, isProject: Boolean = false, lifecycle: AgentLifecycle = AgentLifecycle.IDLE) = Agent(
        id = id,
        name = "Chat $id",
        lifecycle = lifecycle,
        runStatus = RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = now - updatedAgo - hour,
        updatedAtMillis = now - updatedAgo,
        latestRunId = "run-$id",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        isProject = isProject,
    )

    private fun sections(agents: List<Agent>, pinned: Set<String>) =
        AgentListOrganizer.organize(agents, ListPreferences(), LocalAgentState(pinnedIds = pinned), nowMillis = now)

    @Test
    fun `a target survives the round trip through its stored shape`() {
        for (target in listOf(ShortcutTarget.NewChat, ShortcutTarget.Search, ShortcutTarget.Chat("bc-1", "Fix login"), ShortcutTarget.Project("bc-2", "Android"))) {
            val kind = ShortcutTarget.kindOf(target)
            val name = (target as? ShortcutTarget.Chat)?.name ?: (target as? ShortcutTarget.Project)?.name
            assertThat(ShortcutTarget.parse(kind, target.agentId, name)).isEqualTo(target)
        }
    }

    @Test
    fun `an unknown or incomplete stored target is the default`() {
        assertThat(ShortcutTarget.parse(null, null, null)).isEqualTo(ShortcutTarget.NewChat)
        assertThat(ShortcutTarget.parse("something-later", "bc-1", "x")).isEqualTo(ShortcutTarget.NewChat)
        // A chat kind without an id has nothing to open.
        assertThat(ShortcutTarget.parse("chat", "", "x")).isEqualTo(ShortcutTarget.NewChat)
        assertThat(ShortcutTarget.parse("project", null, "x")).isEqualTo(ShortcutTarget.NewChat)
        // A chat whose name was lost still opens; it is shown under a stand-in label.
        assertThat(ShortcutTarget.parse("chat", "bc-1", null)).isEqualTo(ShortcutTarget.Chat("bc-1", "Chat"))
    }

    @Test
    fun `a widget's settings survive the round trip through JSON, and a record this build cannot read is the default`() {
        val settings = ShortcutWidgetSettings(ShortcutStyle.Glass, appearance = WidgetAppearance(theme = WidgetTheme.Oled, opacity = 70)).withTarget(ShortcutTarget.Project("bc-2", "Android"))
        val decoded = ShortcutWidgetSettings.decode(settings.encode())
        assertThat(decoded).isEqualTo(settings)
        assertThat(decoded.target).isEqualTo(ShortcutTarget.Project("bc-2", "Android"))
        assertThat(ShortcutWidgetSettings.decode(null)).isEqualTo(ShortcutWidgetSettings.Default)
        assertThat(ShortcutWidgetSettings.decode("not json")).isEqualTo(ShortcutWidgetSettings.Default)
        // A style a later build added costs that field its default, nothing else.
        assertThat(ShortcutWidgetSettings.decode("""{"style":"Neon","target_kind":"search"}""")).isEqualTo(ShortcutWidgetSettings(ShortcutStyle.Solid).withTarget(ShortcutTarget.Search))
    }

    @Test
    fun `a style parses by name and falls back to the solid disc`() {
        assertThat(ShortcutStyle.parse("Glass")).isEqualTo(ShortcutStyle.Glass)
        assertThat(ShortcutStyle.parse("neon")).isEqualTo(ShortcutStyle.Solid)
        assertThat(ShortcutStyle.parse(null)).isEqualTo(ShortcutStyle.Solid)
    }

    /** The solid disc was the white one, white in every theme; a widget set to it keeps it, and a build before reads it. */
    @Test
    fun `the solid disc is stored under the white disc's name`() {
        val stored = """{"style":"White","target_kind":"new_chat"}"""
        assertThat(ShortcutWidgetSettings.decode(stored).style).isEqualTo(ShortcutStyle.Solid)
        assertThat(ShortcutWidgetSettings(ShortcutStyle.Solid).encode()).contains("\"White\"")
    }

    @Test
    fun `launcher picks are the Projects and pinned chats, newest activity first, within the cap`() {
        val agents = listOf(
            agent("project-old", updatedAgo = 5 * hour, isProject = true),
            agent("pinned-new", updatedAgo = hour),
            agent("pinned-mid", updatedAgo = 2 * hour),
            agent("project-new", updatedAgo = 0, isProject = true),
            agent("unpinned"),
        )
        val picks = LauncherShortcutPicks.pick(sections(agents, pinned = setOf("pinned-new", "pinned-mid")), max = 3)
        assertThat(picks.map { it.row.agent.id }).containsExactly("project-new", "pinned-new", "pinned-mid").inOrder()
        assertThat(picks[0].target).isEqualTo(ShortcutTarget.Project("project-new", "Chat project-new"))
        assertThat(picks[1].target).isEqualTo(ShortcutTarget.Chat("pinned-new", "Chat pinned-new"))
    }

    @Test
    fun `an unpinned chat is never a launcher pick, and a cap of zero picks nothing`() {
        val agents = listOf(agent("a"), agent("b"))
        assertThat(LauncherShortcutPicks.pick(sections(agents, pinned = emptySet()), max = 3)).isEmpty()
        assertThat(LauncherShortcutPicks.pick(sections(agents, pinned = setOf("a")), max = 0)).isEmpty()
    }

    @Test
    fun `a pinned Project is offered once, as a Project`() {
        val agents = listOf(agent("p", isProject = true), agent("c"))
        val picks = LauncherShortcutPicks.pick(sections(agents, pinned = setOf("p", "c")), max = 5)
        assertThat(picks.map { it.row.agent.id }).containsExactly("p", "c")
        assertThat(picks.first { it.row.agent.id == "p" }.target).isInstanceOf(ShortcutTarget.Project::class.java)
    }
}
