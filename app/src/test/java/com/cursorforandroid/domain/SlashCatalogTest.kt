package com.cursorforandroid.domain

import com.cursorforandroid.domain.SlashCommand.Kind
import com.cursorforandroid.domain.SlashCommand.Origin
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlashCatalogTest {

    private val builtIn = SlashCatalog.BUILT_IN

    @Test
    fun `an empty query lists recent custom skills ahead of the built-ins, commands first`() {
        val results = builtIn.search("", recent = listOf("deploy-web", "review"))
        assertThat(results.first()).isEqualTo(SlashCommand("deploy-web", kind = Kind.Skill, origin = Origin.Recent))
        // "review" is a built-in, so it is not duplicated as a recent entry.
        assertThat(results.count { it.name == "review" }).isEqualTo(1)
        assertThat(results.drop(1)).isEqualTo(BuiltInSlashCommands.commands + BuiltInSlashCommands.skills)
        assertThat(results.drop(1).first().name).isEqualTo("goal")
    }

    @Test
    fun `names that start with the query come first, then names containing it, then descriptions`() {
        val names = builtIn.search("re", recent = emptyList()).map { it.name }
        assertThat(names.take(3)).containsExactly("review", "review-bugbot", "review-security").inOrder()
        // "create-skill" & co. contain "re"; "autopilot" only mentions it in its description ("address").
        assertThat(names.indexOf("create-skill")).isGreaterThan(names.indexOf("review-security"))
        assertThat(names.indexOf("autopilot")).isGreaterThan(names.indexOf("create-hook"))
        assertThat(builtIn.search("pull request", recent = emptyList()).map { it.name }).containsExactly("autopilot", "split-to-prs")
    }

    @Test
    fun `the query works with or without the slash and against the description of a plugin skill`() {
        val catalog = builtIn.mergedWith(
            SlashCatalog(listOf(SlashCommand("chat-sdk", "Vercel Chat SDK expert guidance. Use when building bots for Slack, Telegram, Google Chat", origin = Origin.Plugin))),
        )
        // What the official composer shows for "/go": the goal command, then the skill whose description mentions Google.
        assertThat(catalog.search("/go").map { it.name }).containsExactly("goal", "chat-sdk").inOrder()
        assertThat(catalog.search("go").map { it.name }).containsExactly("goal", "chat-sdk").inOrder()
    }

    @Test
    fun `a valid unknown name is offered as a project skill only when nothing listed starts with it`() {
        val typed = builtIn.search("/land-it", recent = emptyList())
        assertThat(typed).containsExactly(SlashCommand("land-it", kind = Kind.Skill, origin = Origin.Recent))
        assertThat(builtIn.search("land-it", recent = listOf("land-it")).count { it.name == "land-it" }).isEqualTo(1)
        // "rev" is the start of the review skills: completing those is the point, not a skill called "rev".
        assertThat(builtIn.search("rev").map { it.name }).doesNotContain("rev")
        // Invalid names are not offered and nothing matches them.
        assertThat(builtIn.search("Land It", recent = emptyList())).isEmpty()
    }

    @Test
    fun `merging takes the server's entry over the built-in but keeps the built-in text where the server has none`() {
        val server = SlashCatalog(
            listOf(
                SlashCommand("review", "", origin = Origin.BuiltIn, sourcePath = "/cursor/skills-cursor/review/SKILL.md"),
                SlashCommand("goal", "Set a goal that Cursor will pursue", kind = Kind.Command, argumentHint = "<goal>"),
                SlashCommand("deploy", "Deploy the app", origin = Origin.Project, sourcePath = ".cursor/skills/deploy/SKILL.md"),
            ),
            pending = true,
        )
        val merged = builtIn.mergedWith(server)
        assertThat(merged.pending).isTrue()
        assertThat(merged.byName("review")).isEqualTo(BuiltInSlashCommands.byName("review")!!.copy(sourcePath = "/cursor/skills-cursor/review/SKILL.md"))
        assertThat(merged.byName("goal")!!.description).isEqualTo("Set a goal that Cursor will pursue")
        assertThat(merged.byName("goal")!!.origin).isEqualTo(Origin.BuiltIn)
        assertThat(merged.entries.last().name).isEqualTo("deploy")
        assertThat(merged.entries.map { it.name }).containsNoDuplicates()
    }

    @Test
    fun `each entry appears once in the first group it matches`() {
        val catalog = SlashCatalog(
            listOf(
                SlashCommand("review", "A review skill", origin = Origin.Project),
                SlashCommand("revamp", "Revamp the docs", origin = Origin.Project),
            ),
        )
        assertThat(catalog.search("rev").map { it.name }).containsExactly("review", "revamp").inOrder()
        assertThat(catalog.search("review").map { it.name }).containsExactly("review")
    }

    @Test
    fun `the origin is read off the source path`() {
        assertThat(SlashCommand.originOf("/home/ubuntu/.cursor/skills-cursor/review/SKILL.md")).isEqualTo(Origin.BuiltIn)
        assertThat(SlashCommand.originOf("/home/ubuntu/.cursor/plugins/cache/vercel/skills/chat-sdk/SKILL.md")).isEqualTo(Origin.Plugin)
        assertThat(SlashCommand.originOf("/home/ubuntu/.cursor/skills/notes/SKILL.md")).isEqualTo(Origin.Personal)
        assertThat(SlashCommand.originOf(".cursor/skills/deploy/SKILL.md")).isEqualTo(Origin.Project)
        assertThat(SlashCommand.originOf("/workspace/.cursor/commands/release.md")).isEqualTo(Origin.Project)
        assertThat(SlashCommand.originOf(null)).isEqualTo(Origin.Unknown)
    }

    @Test
    fun `a summary falls back to the origin when the description is blank`() {
        assertThat(SlashCommand("release", kind = Kind.Command, origin = Origin.Project).summary).isEqualTo("Project command")
        assertThat(SlashCommand("chat-sdk", "Vercel Chat SDK", origin = Origin.Plugin).summary).isEqualTo("Vercel Chat SDK")
        assertThat(SlashCommand("land-it", origin = Origin.Recent).summary).isEqualTo("Project or synced skill")
    }

    @Test
    fun `built-in list stays cloud-relevant`() {
        val names = builtIn.entries.map { it.name }
        assertThat(names).containsNoneOf("canvas", "statusline", "update-cli-config", "update-cursor-settings")
        assertThat(names).containsAtLeast("goal", "multitask", "plan", "autopilot", "review", "subscribe", "split-to-prs")
        assertThat(names).containsNoDuplicates()
        names.forEach { assertThat(SlashCommands.isValidName(it)).isTrue() }
        assertThat(builtIn.commands.map { it.name }).containsExactly("goal", "multitask", "plan").inOrder()
    }
}
