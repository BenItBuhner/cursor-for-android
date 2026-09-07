package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkillsTest {

    @Test
    fun `an empty query lists recent custom skills ahead of the built-ins`() {
        val results = BuiltInSkills.search("", recent = listOf("deploy-web", "review"))
        assertThat(results.first()).isEqualTo(Skill("deploy-web", BuiltInSkills.CUSTOM_DESCRIPTION))
        // "review" is a built-in, so it is not duplicated as a custom entry.
        assertThat(results.count { it.name == "review" }).isEqualTo(1)
        assertThat(results.drop(1)).isEqualTo(BuiltInSkills.cloud)
    }

    @Test
    fun `the query filters by name and description`() {
        val names = BuiltInSkills.search("review", recent = emptyList()).map { it.name }
        assertThat(names).containsExactly("review", "review-bugbot", "review-security").inOrder()
        assertThat(BuiltInSkills.search("pull request", recent = emptyList()).map { it.name }).containsExactly("autopilot", "split-to-prs")
    }

    @Test
    fun `a valid unknown name is offered as a project skill, with or without the slash`() {
        val typed = BuiltInSkills.search("/land-it", recent = emptyList())
        assertThat(typed.first()).isEqualTo(Skill("land-it", BuiltInSkills.CUSTOM_DESCRIPTION))
        assertThat(BuiltInSkills.search("land-it", recent = listOf("land-it")).count { it.name == "land-it" }).isEqualTo(1)
        // Invalid names are not offered and nothing matches them.
        assertThat(BuiltInSkills.search("Land It", recent = emptyList())).isEmpty()
    }

    @Test
    fun `built-in list stays cloud-relevant`() {
        val names = BuiltInSkills.cloud.map { it.name }
        assertThat(names).containsNoneOf("canvas", "statusline", "update-cli-config", "update-cursor-settings")
        assertThat(names).containsAtLeast("autopilot", "review", "subscribe", "split-to-prs")
        assertThat(names).containsNoDuplicates()
        names.forEach { assertThat(SlashCommands.isValidName(it)).isTrue() }
    }
}
