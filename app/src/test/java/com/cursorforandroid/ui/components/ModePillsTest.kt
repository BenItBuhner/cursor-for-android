package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.cursorforandroid.ui.components.ModePills.Pill
import com.cursorforandroid.ui.components.ModePills.Presented
import com.cursorforandroid.ui.components.ModePills.Typed
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The split between the prompt the owner sends and the text the field shows beside its pills. */
class ModePillsTest {

    @Test
    fun `multitask is taken out of the shown text and put back in front for the owner`() {
        val presented = ModePills.present("/multitask fix the flaky test")
        assertThat(presented).isEqualTo(Presented("fix the flaky test", multitask = true))
        assertThat(ModePills.compose(presented.text, presented.multitask)).isEqualTo("/multitask fix the flaky test")
        assertThat(ModePills.compose("fix the flaky test", multitask = false)).isEqualTo("fix the flaky test")
    }

    @Test
    fun `the round trip keeps the shown text exactly, whitespace included`() {
        for (text in listOf("", " ", "  two spaces", "trailing ", "line\nbreak", "/review fix it", "a/b testing")) {
            assertThat(ModePills.present(ModePills.compose(text, multitask = true))).isEqualTo(Presented(text, multitask = true))
            assertThat(ModePills.present(ModePills.compose(text, multitask = false))).isEqualTo(Presented(text, multitask = false))
        }
    }

    @Test
    fun `a closed token anywhere in the prompt is the pill, and takes the space that closed it`() {
        assertThat(ModePills.present("/review /multitask fix it")).isEqualTo(Presented("/review fix it", multitask = true))
        assertThat(ModePills.present("fix it /multitask now")).isEqualTo(Presented("fix it now", multitask = true))
        assertThat(ModePills.present("/multitask ")).isEqualTo(Presented("", multitask = true))
        assertThat(ModePills.present("/multitask\nfix it")).isEqualTo(Presented("fix it", multitask = true))
        assertThat(ModePills.present("/multitasking is fun")).isEqualTo(Presented("/multitasking is fun", multitask = false))
        // Plan mode is never in the prompt: a `/plan` the owner holds is text, not the pill.
        assertThat(ModePills.present("/plan fix it")).isEqualTo(Presented("/plan fix it", multitask = false))
    }

    @Test
    fun `a token ending the text is still being typed and stays text`() {
        assertThat(ModePills.present("/multitask")).isEqualTo(Presented("/multitask", multitask = false))
        assertThat(ModePills.present("fix it /multitask")).isEqualTo(Presented("fix it /multitask", multitask = false))
        assertThat(ModePills.present("/mult")).isEqualTo(Presented("/mult", multitask = false))
    }

    @Test
    fun `only multitask and plan become pills, and plan only where plan mode can be set`() {
        assertThat(ModePills.pillFor("multitask", planEnabled = false)).isEqualTo(Pill.Multitask)
        assertThat(ModePills.pillFor("plan", planEnabled = true)).isEqualTo(Pill.Plan)
        assertThat(ModePills.pillFor("plan", planEnabled = false)).isNull()
        assertThat(ModePills.pillFor("goal", planEnabled = true)).isNull()
        assertThat(ModePills.pillFor("review", planEnabled = true)).isNull()
    }

    @Test
    fun `a command closed with a space leaves the typed text, the caret staying on its characters`() {
        assertThat(ModePills.consumeTyped("/multitask ", TextRange(11), planEnabled = true))
            .isEqualTo(Typed("", TextRange(0), listOf(Pill.Multitask)))
        assertThat(ModePills.consumeTyped("fix /plan the bug", TextRange(10), planEnabled = true))
            .isEqualTo(Typed("fix the bug", TextRange(4), listOf(Pill.Plan)))
        assertThat(ModePills.consumeTyped("/plan fix", TextRange(0), planEnabled = true).selection).isEqualTo(TextRange(0))
        // A caret inside the token lands where the token stood.
        assertThat(ModePills.consumeTyped("/plan\nfix", TextRange(3), planEnabled = true))
            .isEqualTo(Typed("fix", TextRange(0), listOf(Pill.Plan)))
    }

    @Test
    fun `both commands at once come out in text order, and the last is the one that stays on`() {
        val both = ModePills.consumeTyped("/multitask /plan fix", TextRange(20), planEnabled = true)
        assertThat(both).isEqualTo(Typed("fix", TextRange(3), listOf(Pill.Multitask, Pill.Plan)))
        assertThat(both.turnedOn).isEqualTo(Pill.Plan)
        assertThat(ModePills.consumeTyped("/plan /multitask fix", TextRange(20), planEnabled = true).turnedOn).isEqualTo(Pill.Multitask)
        assertThat(ModePills.consumeTyped("fix it", TextRange(6), planEnabled = true).turnedOn).isNull()
    }

    @Test
    fun `a token still being typed, or one that is not a pill, stays as text`() {
        assertThat(ModePills.consumeTyped("/multitask", TextRange(10), planEnabled = true))
            .isEqualTo(Typed("/multitask", TextRange(10), emptyList()))
        assertThat(ModePills.consumeTyped("/plan ", TextRange(6), planEnabled = false))
            .isEqualTo(Typed("/plan ", TextRange(6), emptyList()))
        assertThat(ModePills.consumeTyped("/goal ship it", TextRange(13), planEnabled = true).pills).isEmpty()
        assertThat(ModePills.consumeTyped("/multitasking ", TextRange(14), planEnabled = true).pills).isEmpty()
        assertThat(ModePills.consumeTyped("a/plan ", TextRange(7), planEnabled = true).pills).isEmpty()
    }

    @Test
    fun `a token picked from the popover goes with the space after it and leaves the caret in its place`() {
        val lead = SlashTokens.at("/mult fix", 5)!!
        assertThat(ModePills.consumeToken("/mult fix", lead)).isEqualTo(TextFieldValue("fix", TextRange(0)))
        val tail = SlashTokens.at("fix /pl", 7)!!
        assertThat(ModePills.consumeToken("fix /pl", tail)).isEqualTo(TextFieldValue("fix", TextRange(3)))
        val alone = SlashTokens.at("/plan", 5)!!
        assertThat(ModePills.consumeToken("/plan", alone)).isEqualTo(TextFieldValue("", TextRange(0)))
    }

    /** Where Shift+Tab goes from [start], pressed [times] times, over the modes [available]. */
    private fun cycle(start: Pill?, times: Int, available: Set<Pill>): List<Pill?> {
        var at = start
        return List(times) {
            at = ModePills.next(at) { pill -> pill in available }
            at
        }
    }

    @Test
    fun `Shift+Tab steps from no mode through the desktop's order and back, over the modes this composer can wear`() {
        assertThat(Pill.desktopOrder).containsExactly(Pill.Plan, Pill.Debug, Pill.Multitask, Pill.Ask).inOrder()
        val all = Pill.entries.toSet()
        assertThat(cycle(null, 5, all)).containsExactly(Pill.Plan, Pill.Debug, Pill.Multitask, Pill.Ask, null).inOrder()
        // Without Extended mode: Plan and Multitask alone.
        assertThat(cycle(null, 3, setOf(Pill.Plan, Pill.Multitask))).containsExactly(Pill.Plan, Pill.Multitask, null).inOrder()
        // Without a mode to set (no owner for it): Multitask and back.
        assertThat(ModePills.next(null) { it == Pill.Multitask }).isEqualTo(Pill.Multitask)
        assertThat(ModePills.next(Pill.Multitask) { it == Pill.Multitask }).isNull()
    }

    @Test
    fun `each mode wears the desktop glass colour its modeConfig names, in both themes, and Ask is green`() {
        assertThat(Pill.entries.associate { it to it.colorId })
            .containsExactly(Pill.Multitask, "purple", Pill.Plan, "yellow", Pill.Ask, "green", Pill.Debug, "red")
        val dark = Pill.entries.associateWith { pillTint(it, dark = true) }
        val light = Pill.entries.associateWith { pillTint(it, dark = false) }
        assertThat(dark).containsExactly(
            Pill.Multitask, Color(0xFF9386F2), Pill.Plan, Color(0xFFF1B467), Pill.Ask, Color(0xFF3FA266), Pill.Debug, Color(0xFFFC6B83),
        )
        assertThat(light).containsExactly(
            Pill.Multitask, Color(0xFF7565CC), Pill.Plan, Color(0xFFA46701), Pill.Ask, Color(0xFF007041), Pill.Debug, Color(0xFFBE1744),
        )
        // The blue Ask once wore (#82AAFF, #2456B8) is nobody's.
        assertThat(dark.values + light.values).containsNoneOf(Color(0xFF82AAFF), Color(0xFF2456B8))
    }

    @Test
    fun `a worn mode outside the cycle starts it over, and with nothing to cycle there is no next`() {
        assertThat(ModePills.next(Pill.Ask) { it == Pill.Plan || it == Pill.Multitask }).isEqualTo(Pill.Plan)
        assertThat(ModePills.next(null) { false }).isNull()
        assertThat(ModePills.next(Pill.Plan) { false }).isNull()
    }
}
