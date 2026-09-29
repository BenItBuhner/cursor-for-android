package com.cursorforandroid.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * The models the composer's `/` popover lists for what follows the slash, as the desktop's slash menu lists every
 * model but Auto under "Models" (3.21.18: one `glass-model-<id>` row per enabled model other than `default`, named by
 * its display name; picking it sets the composer's model with its parameters). What was typed narrows them fuzzily:
 * each word must begin a word of the model's display name, id or an alias, in any order and case — "opus 4",
 * "Opus 4.7", "5.5", "gpt-5.5", `5-5` read as `5.5` ([ModelSlugs.words]) — or the whole of it, spaces and dots
 * aside, must run on from one of the name's words ("opus47"). Words the name has no use for are read as the model's
 * own parameter words ("opus 4.7 max fast"), through the same normaliser the rest of the app places model ids with
 * ([ModelSlugs.resolve]), and pick the variant they spell; the last one may still be half typed. At least one word
 * has to name the model.
 */
object ModelSearch {
    /**
     * The models [query] finds, best first, each with the variant a pick sets: the one its words spell, else the
     * [current] variant for the model in use, else the model's default. An empty query lists them all, the one in use
     * first.
     */
    fun search(models: List<ModelOption>, query: String, current: ModelChoice? = null): List<ModelChoice> =
        Index(models).search(query, current)

    /**
     * [models] read once for searching them as the composer does, on every keystroke: each model's words are worked
     * out when the index is built rather than per query, and the last query's matches are kept, so asking whether a
     * phrase names a model and then listing the models for it search the catalog once between them.
     */
    class Index(private val models: List<ModelOption>) {
        private val listed = models.filterNot { it.isAuto }.map { Entry(it) }
        @Volatile private var last: Matches? = null
        /** What [ModelSlugs.resolve] placed each slug a query spelled at, by slug: the word typed next usually spells it again. */
        private val placed = ConcurrentHashMap<String, Placed>()

        /** [ModelSearch.search] over this index's models. */
        fun search(query: String, current: ModelChoice? = null): List<ModelChoice> {
            val terms = ModelSlugs.words(query)
            if (terms.isEmpty()) {
                val first = current?.let { c -> listed.firstOrNull { it.model.id == c.model.id } }
                return (listOfNotNull(first) + listed.filter { it.model.id != first?.model?.id }).map { it.model.choice(current) }
            }
            return matches(terms, compact(query)).map { it.spelled ?: it.model.choice(current) }
        }

        private fun matches(terms: List<String>, compact: String): List<Hit> {
            last?.takeIf { it.terms == terms && it.compact == compact }?.let { return it.hits }
            val hits = listed.withIndex()
                .mapNotNull { (order, entry) -> entry.match(terms, compact)?.let { Found(it, entry.rank(terms, compact), order) } }
                .sortedWith(compareBy<Found> { it.rank }.thenBy { it.order })
                .map { it.hit }
            last = Matches(terms, compact, hits)
            return hits
        }

        /** One model's words, as the queries read them. */
        private inner class Entry(val model: ModelOption) {
            private val names: Set<String> =
                (ModelSlugs.words(model.displayName) + ModelSlugs.words(model.id) + model.aliases.flatMap(ModelSlugs::words)).toSet()

            /** The words the model's parameters answer to: their ids and names, and their values and the values' names. */
            private val parameterWords: Set<String> by lazy {
                val definitions = model.parameters.flatMap { p -> listOfNotNull(p.id, p.displayName) + p.values.flatMap { listOfNotNull(it.value, it.displayName) } }
                val used = model.variants.flatMap { v -> v.params.flatMap { listOf(it.id, it.value) } }
                (definitions + used).flatMap(ModelSlugs::words).toSet()
            }

            private val compactName = compact(model.displayName)

            /** The name and the id from each of their words on, run together: "opus55" runs on from "Opus" in "Claude Opus 5.5", "de" from none of "Claude". */
            private val runs: List<String> by lazy { runs(model.displayName) + runs(model.id) }

            /** The model at the variant a pick sets for [terms]: [Hit.spelled] when the words spell one, else null. */
            fun match(terms: List<String>, compact: String): Hit? {
                val extras = terms.filter { term -> names.none { it.startsWith(term) } }
                if (extras.isEmpty()) return Hit(model, null)
                if (extras.size < terms.size) {
                    spelled(extras)?.let { return Hit(model, it) }
                    // The word being typed may be a parameter's first letters ("max" as "ma"): the rest decide meanwhile.
                    if (extras.last() == terms.last() && parameterWords.any { it.startsWith(extras.last()) }) {
                        val settled = extras.dropLast(1)
                        if (settled.isEmpty()) return Hit(model, null)
                        spelled(settled)?.let { return Hit(model, it) }
                    }
                }
                if (compact.length >= 2 && runs.any { it.startsWith(compact) }) return Hit(model, null)
                return null
            }

            /**
             * The model at the variant [words] spell as parameter words, or null when they are not all words of its
             * own: the normaliser reads past a suffix no parameter means (`-thinking` on a model without one), a
             * search must not.
             */
            private fun spelled(words: List<String>): ModelChoice? {
                if (!words.all { it in parameterWords }) return null
                val slug = (listOf(model.id) + words).joinToString("-")
                val placed = placed.getOrPut(slug) { Placed(ModelSlugs.resolve(models, slug)) }.choice ?: return null
                return placed.takeIf { it.model.id == model.id }
            }

            /** Where a match stands: the display name begun by the query first, then every word a whole word of the name, then the rest. */
            fun rank(terms: List<String>, compact: String): Int = when {
                compactName.startsWith(compact) -> 0
                terms.all { it in names } -> 1
                else -> 2
            }
        }
    }

    /** A model a query finds, at the variant its words spell, or null for the variant in use or the default. */
    private class Hit(val model: ModelOption, val spelled: ModelChoice?)

    private class Placed(val choice: ModelChoice?)

    private class Found(val hit: Hit, val rank: Int, val order: Int)

    private class Matches(val terms: List<String>, val compact: String, val hits: List<Hit>)

    private fun runs(text: String): List<String> {
        val words = ModelSlugs.words(text)
        return words.indices.map { i -> compact(words.drop(i).joinToString("")) }
    }

    private fun ModelOption.choice(current: ModelChoice?): ModelChoice =
        if (current != null && current.model.id == id) ModelChoice(this, current.variant ?: defaultVariant) else ModelChoice(this, defaultVariant)

    private fun compact(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)
}
