package com.cursorforandroid.domain

/**
 * The models the composer's `/` popover lists for what follows the slash, as the desktop's slash menu lists every
 * model but Auto under "Models" (3.21.18: one `glass-model-<id>` row per enabled model other than `default`, named by
 * its display name; picking it sets the composer's model with its parameters). What was typed narrows them fuzzily:
 * each word must begin a word of the model's display name, id or an alias, in any order and case — "opus 4",
 * "Opus 4.7", "5.5", "gpt-5.5", `5-5` read as `5.5` ([ModelSlugs.words]) — or the whole of it, spaces and dots
 * aside, must stand in the name ("opus47"). Words the name has no use for are read as the model's own parameter
 * words ("opus 4.7 max fast"), through the same normaliser the rest of the app places model ids with
 * ([ModelSlugs.resolve]), and pick the variant they spell; the last one may still be half typed. At least one word
 * has to name the model.
 */
object ModelSearch {
    /**
     * The models [query] finds, best first, each with the variant a pick sets: the one its words spell, else the
     * [current] variant for the model in use, else the model's default. An empty query lists them all, the one in use
     * first.
     */
    fun search(models: List<ModelOption>, query: String, current: ModelChoice? = null): List<ModelChoice> {
        val listed = models.filterNot { it.isAuto }
        val terms = ModelSlugs.words(query)
        if (terms.isEmpty()) {
            val first = current?.let { c -> listed.firstOrNull { it.id == c.model.id } }
            return (listOfNotNull(first) + listed.filter { it.id != first?.id }).map { it.choice(current) }
        }
        val compact = compact(query)
        return listed.withIndex()
            .mapNotNull { (order, model) -> match(models, model, terms, compact, current)?.let { Found(it, rank(model, terms, compact), order) } }
            .sortedWith(compareBy<Found> { it.rank }.thenBy { it.order })
            .map { it.choice }
    }

    private class Found(val choice: ModelChoice, val rank: Int, val order: Int)

    private fun match(models: List<ModelOption>, model: ModelOption, terms: List<String>, compact: String, current: ModelChoice?): ModelChoice? {
        val names = model.nameWords()
        val extras = terms.filter { term -> names.none { it.startsWith(term) } }
        if (extras.isEmpty()) return model.choice(current)
        if (extras.size < terms.size) {
            variantSpelled(models, model, extras)?.let { return it }
            // The word being typed may be a parameter's first letters ("max" as "ma"): the rest decide meanwhile.
            if (extras.last() == terms.last() && model.parameterWords().any { it.startsWith(extras.last()) }) {
                val settled = extras.dropLast(1)
                if (settled.isEmpty()) return model.choice(current)
                variantSpelled(models, model, settled)?.let { return it }
            }
        }
        if (compact.length >= 2 && (compact(model.displayName).contains(compact) || compact(model.id).contains(compact))) return model.choice(current)
        return null
    }

    /**
     * [model] at the variant [words] spell as parameter words, or null when they are not all words of its own: the
     * normaliser reads past a suffix no parameter means (`-thinking` on a model without one), a search must not.
     */
    private fun variantSpelled(models: List<ModelOption>, model: ModelOption, words: List<String>): ModelChoice? {
        val own = model.parameterWords()
        if (!words.all { it in own }) return null
        val placed = ModelSlugs.resolve(models, (listOf(model.id) + words).joinToString("-")) ?: return null
        return placed.takeIf { it.model.id == model.id }
    }

    /** The words [model]'s parameters answer to: their ids and names, and their values and the values' names. */
    private fun ModelOption.parameterWords(): Set<String> {
        val definitions = parameters.flatMap { p -> listOfNotNull(p.id, p.displayName) + p.values.flatMap { listOfNotNull(it.value, it.displayName) } }
        val used = variants.flatMap { v -> v.params.flatMap { listOf(it.id, it.value) } }
        return (definitions + used).flatMap(ModelSlugs::words).toSet()
    }

    /** Where a match stands: the display name begun by the query first, then every word a whole word of the name, then the rest. */
    private fun rank(model: ModelOption, terms: List<String>, compact: String): Int {
        val names = model.nameWords()
        return when {
            compact(model.displayName).startsWith(compact) -> 0
            terms.all { it in names } -> 1
            else -> 2
        }
    }

    private fun ModelOption.nameWords(): Set<String> =
        (ModelSlugs.words(displayName) + ModelSlugs.words(id) + aliases.flatMap(ModelSlugs::words)).toSet()

    private fun ModelOption.choice(current: ModelChoice?): ModelChoice =
        if (current != null && current.model.id == id) ModelChoice(this, current.variant ?: defaultVariant) else ModelChoice(this, defaultVariant)

    private fun compact(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)
}
