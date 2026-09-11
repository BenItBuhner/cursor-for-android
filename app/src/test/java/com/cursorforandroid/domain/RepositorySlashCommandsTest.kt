package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Project skills and commands read off a repository's file tree, the way a cloud agent finds them. */
class RepositorySlashCommandsTest {

    @Test
    fun `skills are the SKILL_md directories and commands the markdown files, commands first, each sorted by name`() {
        val catalog = RepositorySlashCommands.fromPaths(
            listOf(
                ".cursor/skills/zeta/SKILL.md",
                ".cursor/skills/alpha/SKILL.md",
                ".cursor/skills/alpha/reference.md",
                ".cursor/skills/no-skill-file/README.md",
                ".cursor/commands/review.md",
                ".cursor/commands/deploy.md",
                ".cursor/commands/nested/too-deep.md",
                ".cursor/rules/style.mdc",
                "docs/SKILL.md",
            ),
        )

        assertThat(catalog.entries.map { it.name }).containsExactly("deploy", "review", "alpha", "zeta").inOrder()
        assertThat(catalog.commands.map { it.name }).containsExactly("deploy", "review").inOrder()
        assertThat(catalog.skills.map { it.name }).containsExactly("alpha", "zeta").inOrder()
        assertThat(catalog.byName("alpha")).isEqualTo(SlashCommand("alpha", kind = SlashCommand.Kind.Skill, origin = SlashCommand.Origin.Project, sourcePath = ".cursor/skills/alpha/SKILL.md"))
        assertThat(catalog.pending).isFalse()
    }

    @Test
    fun `names Cursor would not accept are left out, a name used twice is kept once, and odd path spellings are tolerated`() {
        val catalog = RepositorySlashCommands.fromPaths(
            listOf(
                "/.cursor/skills/ok-name/SKILL.md",
                ".cursor\\skills\\windows\\SKILL.md",
                ".cursor/skills/Not Valid/SKILL.md",
                ".cursor/skills/-leading/SKILL.md",
                ".cursor/commands/ok-name.md",
            ),
        )

        assertThat(catalog.entries.map { it.name }).containsExactly("ok-name", "windows").inOrder()
        assertThat(catalog.byName("ok-name")?.kind).isEqualTo(SlashCommand.Kind.Command)
    }

    @Test
    fun `a tree without a cursor directory is an empty catalog, which merges to the built-ins`() {
        val catalog = RepositorySlashCommands.fromPaths(listOf("src/main.kt", "README.md"))

        assertThat(catalog.entries).isEmpty()
        assertThat(SlashCatalog.BUILT_IN.mergedWith(catalog)).isEqualTo(SlashCatalog.BUILT_IN)
    }
}
