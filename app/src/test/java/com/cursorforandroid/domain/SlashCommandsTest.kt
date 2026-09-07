package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SlashCommandsTest {

    @Test
    fun `adding to an empty or plain prompt leads it`() {
        assertThat(SlashCommands.add("", "multitask")).isEqualTo("/multitask ")
        assertThat(SlashCommands.add("   ", "multitask")).isEqualTo("/multitask ")
        assertThat(SlashCommands.add("fix the flaky test", "multitask")).isEqualTo("/multitask fix the flaky test")
    }

    @Test
    fun `commands stay grouped at the front in the order they were added`() {
        val one = SlashCommands.add("fix it", "multitask")
        val two = SlashCommands.add(one, "review")
        assertThat(two).isEqualTo("/multitask /review fix it")
        assertThat(SlashCommands.add("/multitask", "review")).isEqualTo("/multitask /review ")
        assertThat(SlashCommands.commands(two)).containsExactly("multitask", "review").inOrder()
    }

    @Test
    fun `adding a command that is already present is a no-op`() {
        assertThat(SlashCommands.add("/multitask fix it", "multitask")).isEqualTo("/multitask fix it")
        assertThat(SlashCommands.add("please /review this", "review")).isEqualTo("please /review this")
    }

    @Test
    fun `has matches whole tokens only`() {
        assertThat(SlashCommands.has("/multitask fix", "multitask")).isTrue()
        assertThat(SlashCommands.has("run /review-bugbot now", "review-bugbot")).isTrue()
        assertThat(SlashCommands.has("run /review-bugbot now", "review")).isFalse()
        assertThat(SlashCommands.has("/multitasking is fun", "multitask")).isFalse()
        assertThat(SlashCommands.has("see a/b testing", "b")).isFalse()
    }

    @Test
    fun `removing takes the token and the whitespace it owned`() {
        assertThat(SlashCommands.remove("/multitask fix it", "multitask")).isEqualTo("fix it")
        assertThat(SlashCommands.remove("/multitask ", "multitask")).isEqualTo("")
        assertThat(SlashCommands.remove("/multitask /review fix it", "multitask")).isEqualTo("/review fix it")
        assertThat(SlashCommands.remove("/multitask /review fix it", "review")).isEqualTo("/multitask fix it")
        assertThat(SlashCommands.remove("fix it /review", "review")).isEqualTo("fix it")
        assertThat(SlashCommands.remove("fix it", "review")).isEqualTo("fix it")
    }

    @Test
    fun `toggle flips presence`() {
        val on = SlashCommands.toggle("fix it", SlashCommands.MULTITASK)
        assertThat(on).isEqualTo("/multitask fix it")
        assertThat(SlashCommands.toggle(on, SlashCommands.MULTITASK)).isEqualTo("fix it")
    }

    @Test
    fun `names follow the SKILL md rules`() {
        assertThat(SlashCommands.isValidName("review-bugbot")).isTrue()
        assertThat(SlashCommands.isValidName("sdk2")).isTrue()
        assertThat(SlashCommands.isValidName("Review")).isFalse()
        assertThat(SlashCommands.isValidName("-lead")).isFalse()
        assertThat(SlashCommands.isValidName("has space")).isFalse()
        assertThat(SlashCommands.isValidName("")).isFalse()
        assertThrows(IllegalArgumentException::class.java) { SlashCommands.add("x", "Bad Name") }
    }
}
