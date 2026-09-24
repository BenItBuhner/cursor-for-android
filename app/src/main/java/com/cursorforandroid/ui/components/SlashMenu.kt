package com.cursorforandroid.ui.components

import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelSearch
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand

/**
 * A group of the `/` popover's rows under its small header. With nothing typed after the slash they come in the
 * desktop's order (3.21.18 slash menu: skills and commands, then Modes, then Models), each showing its first [shown]
 * rows and a "Show N more" row, as the desktop's sections do (three rows, four for Modes); a query orders them by
 * their best match ([SlashMenu.items]).
 */
enum class SlashSection(val title: String, val shown: Int) {
    Commands("Commands", 3),
    Modes("Modes", 4),
    Models("Models", 3),
}

/** One row of the `/` popover. */
sealed interface SlashItem {
    val section: SlashSection

    /** A command or skill from the catalog, completed into the text as `/name `. */
    data class Command(val command: SlashCommand) : SlashItem {
        override val section: SlashSection get() = SlashSection.Commands
    }

    /** A mode, worn as its pill once picked; picking the one [on] takes it off, as the desktop's mode rows do. */
    data class Mode(val pill: ModePills.Pill, val on: Boolean) : SlashItem {
        override val section: SlashSection get() = SlashSection.Modes
    }

    /** A model of the catalog at the variant a pick sets; [current] is the one the composer is on. */
    data class Model(val choice: ModelChoice, val current: Boolean) : SlashItem {
        override val section: SlashSection get() = SlashSection.Models
    }

    /** The rest of a [section] cut short with nothing typed: picked, the section lists them all. */
    data class ShowMore(override val section: SlashSection, val remaining: Int) : SlashItem
}

/**
 * What a composer offers in its `/` popover beside its catalog: the [modes] it can wear, the one [worn], and the
 * [models] it can switch to, [current] among them. [models] is empty where a pick could not set the model.
 */
data class SlashOffer(
    val modes: List<ModePills.Pill> = emptyList(),
    val worn: ModePills.Pill? = null,
    val models: List<ModelOption> = emptyList(),
    val current: ModelChoice? = null,
) {
    companion object {
        val None = SlashOffer()
    }
}

object SlashMenu {
    /**
     * The popover's rows for [query] (what follows the slash): the catalog's commands and skills as [SlashCatalog.search]
     * finds them, less the mode commands [offer] lists as modes; the modes whose name the query begins, or from three
     * letters on a word of whose line; and the models [ModelSearch] finds. A name the catalog does not know is only offered as a skill
     * while nothing else answers to it. With an empty query each section is cut to its [SlashSection.shown] rows
     * unless it is [expanded].
     */
    fun items(query: String, catalog: SlashCatalog, recent: List<String>, offer: SlashOffer, expanded: Set<SlashSection> = emptySet()): List<SlashItem> {
        val q = query.trim().removePrefix("/").lowercase()
        val models = if (offer.models.isEmpty()) emptyList() else ModelSearch.search(offer.models, q, offer.current)
            .map { SlashItem.Model(it, current = it.model.id == offer.current?.model?.id) }
        val modes = modes(q, offer)
        val asModes = offer.modes.mapTo(HashSet()) { it.command }
        val guessed = models.isNotEmpty() || modes.isNotEmpty()
        val commands = catalog.search(q, recent)
            .filterNot { it.kind == SlashCommand.Kind.Command && it.name in asModes }
            .filterNot { guessed && it.isGuess(q, catalog, recent) }
            .map(SlashItem::Command)
        if (q.isEmpty()) return listOf(commands, modes, models).flatMap { rows -> rows.cut(expanded) }
        // Searching, the desktop ranks one list by how well each row matches; here the sections keep their headers
        // and go in the order of their best row, so the row Enter picks is still the best match ("/pl" is Plan, not
        // /split-to-prs). A name the query begins beats one it is inside, which beats a description. On a tie the
        // modes lead, as the mode commands led the catalog's skills before they were rows of their own, then the
        // commands, then the models ("/g" is /goal before GPT).
        val commandTier = commands.minOfOrNull { row -> row.command.name.let { if (it.startsWith(q)) 0 else if (it.contains(q)) 1 else 2 } } ?: Int.MAX_VALUE
        val modeTier = if (modes.any { it.pill.command.startsWith(q) || it.pill.label.lowercase().startsWith(q) }) 0 else 2
        return listOf(modeTier to modes, commandTier to commands, 0 to models)
            .withIndex()
            .sortedWith(compareBy({ it.value.first }, { it.index }))
            .flatMap { it.value.second }
    }

    private fun modes(q: String, offer: SlashOffer): List<SlashItem.Mode> {
        val offered = ModePills.Pill.desktopOrder.filter { it in offer.modes }
        val found = if (q.isEmpty()) offered else {
            val named = offered.filter { it.command.startsWith(q) || it.label.lowercase().startsWith(q) }
            val described = if (q.length < DESCRIBED_MIN) emptyList() else offered.filter { it !in named && it.description.lowercase().split(' ').any { word -> word.startsWith(q) } }
            named + described
        }
        return found.map { SlashItem.Mode(it, on = it == offer.worn) }
    }

    /** The entry [SlashCatalog.search] makes up for a name it does not list, rather than one it lists or remembers. */
    private fun SlashCommand.isGuess(q: String, catalog: SlashCatalog, recent: List<String>): Boolean =
        origin == SlashCommand.Origin.Recent && name == q && catalog.byName(q) == null && q !in recent

    /** How much has to be typed before a mode's line answers for it: "an" and "p" begin words of every line. */
    private const val DESCRIBED_MIN = 3

    private fun List<SlashItem>.cut(expanded: Set<SlashSection>): List<SlashItem> {
        val section = firstOrNull()?.section ?: return this
        if (section in expanded || size <= section.shown) return this
        return take(section.shown) + SlashItem.ShowMore(section, size - section.shown)
    }
}
