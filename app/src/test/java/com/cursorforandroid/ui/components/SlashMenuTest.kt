package com.cursorforandroid.ui.components

import com.cursorforandroid.domain.BuiltInSlashCommands
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.components.ModePills.Pill
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What the `/` popover lists: commands, then the modes, then the models, as the desktop's slash menu groups them. */
class SlashMenuTest {

    private val catalog = SlashCatalog(BuiltInSlashCommands.commands + BuiltInSlashCommands.extendedModes + BuiltInSlashCommands.skills)
    private val models = LiveModelCatalog.models
    private val opus = LiveModelCatalog.model("claude-opus-5.5")
    private val offer = SlashOffer(
        modes = Pill.entries,
        worn = Pill.Debug,
        models = models,
        current = ModelChoice(opus, opus.defaultVariant),
    )

    private fun items(query: String, offer: SlashOffer = this.offer, expanded: Set<SlashSection> = emptySet()) =
        SlashMenu.items(query, catalog, recent = emptyList(), offer = offer, expanded = expanded)

    private fun List<SlashItem>.labels() = map { item ->
        when (item) {
            is SlashItem.Command -> item.command.command
            is SlashItem.Mode -> item.pill.label + if (item.on) " (on)" else ""
            is SlashItem.Model -> item.choice.label + if (item.current) " (current)" else ""
            is SlashItem.ShowMore -> "Show ${item.remaining} more"
        }
    }

    @Test
    fun `with nothing typed each section shows its first rows and the count of the rest, modes in the desktop's order`() {
        val listed = items("")
        assertThat(listed.labels()).containsExactly(
            "/goal", "/autopilot", "/review", "Show 14 more",
            "Plan", "Debug (on)", "Multitask", "Ask",
            "Claude Opus 5.5 (current)", "Claude Opus 5", "Claude Fable 5.1", "Show ${models.size - 4} more",
        ).inOrder()
        assertThat(listed.map { it.section }.distinct()).containsExactly(SlashSection.Commands, SlashSection.Modes, SlashSection.Models).inOrder()
    }

    @Test
    fun `a section a Show more row opened lists everything`() {
        val listed = items("", expanded = setOf(SlashSection.Models))
        val shown = listed.filterIsInstance<SlashItem.Model>()
        assertThat(shown).hasSize(models.size - 1)
        assertThat(listed.filterIsInstance<SlashItem.ShowMore>().map { it.section }).containsExactly(SlashSection.Commands)
    }

    @Test
    fun `the mode commands are rows of Modes where they are modes here, and commands where they are not`() {
        assertThat(items("").labels()).containsNoneOf("/plan", "/multitask", "/ask", "/debug")
        val planOnly = SlashOffer(modes = listOf(Pill.Plan, Pill.Multitask))
        val listed = items("", planOnly, expanded = setOf(SlashSection.Commands))
        assertThat(listed.labels()).containsAtLeast("/ask", "/debug", "Plan", "Multitask")
        assertThat(listed.labels()).containsNoneOf("/plan", "/multitask", "Ask", "Debug")
    }

    @Test
    fun `a query narrows every section, with no Show more, and a phrase names only models`() {
        assertThat(items("Opus 5").labels()).containsExactly("Claude Opus 5", "Claude Opus 5.5 (current)").inOrder()
        assertThat(items("gpt-5.6").labels()).containsExactly("GPT-5.6", "GPT-5.6 Sol", "GPT-5.6 Terra").inOrder()
        // Commands first, as the desktop groups them: "/re" leads with the reviews, the mode after them.
        val re = items("re").labels()
        assertThat(re.take(3)).containsExactly("/review", "/review-bugbot", "/review-security").inOrder()
        assertThat(items("re").none { it is SlashItem.ShowMore }).isTrue()
        assertThat(items("de").filterIsInstance<SlashItem.Mode>().map { it.pill }).containsExactly(Pill.Debug)
    }

    @Test
    fun `searching, the section with the best match leads, so Enter picks what the query names`() {
        // Plan's name begins "pl"; /split-to-prs only holds it.
        assertThat(items("pl").labels().first()).isEqualTo("Plan")
        // A tie: the mode first, as /multitask and /ask led the skills when they were commands.
        assertThat(items("m").labels().first()).isEqualTo("Multitask")
        assertThat(items("a").labels().first()).isEqualTo("Ask")
        // A tie between a command and models: the command first.
        assertThat(items("g").labels().first()).isEqualTo("/goal")
        assertThat(items("g").labels()).contains("GPT-5.6")
        // Nothing's name begins "co" but Composer's; the commands only describe it.
        assertThat(items("co").labels().first()).isEqualTo("Composer 2.5")
        assertThat(items("co").map { it.section }.distinct()).containsExactly(SlashSection.Models, SlashSection.Commands).inOrder()
    }

    private fun modes(query: String) = items(query).filterIsInstance<SlashItem.Mode>().map { it.pill }

    @Test
    fun `a mode answers to its name, and from three letters on to the start of a word of its line`() {
        assertThat(modes("p")).containsExactly(Pill.Plan)
        assertThat(modes("a")).containsExactly(Pill.Ask)
        assertThat(modes("mul")).containsExactly(Pill.Multitask)
        assertThat(modes("sub")).containsExactly(Pill.Multitask)
        assertThat(modes("root")).containsExactly(Pill.Debug)
        assertThat(modes("ans")).containsExactly(Pill.Ask)
        assertThat(modes("an")).isEmpty()
    }

    @Test
    fun `a name the catalog does not know is offered as a skill only while no model or mode answers to it`() {
        assertThat(items("sonnet").labels()).containsExactly("Claude Sonnet 5", "Claude 4.6 Sonnet (Thinking)")
        assertThat(items("deploy").labels()).containsExactly("/deploy")
        assertThat(items("sonnet", SlashOffer.None).labels()).containsExactly("/sonnet")
    }

    @Test
    fun `a composer that cannot set the model lists none`() {
        assertThat(items("", offer.copy(models = emptyList())).none { it.section == SlashSection.Models }).isTrue()
    }
}
