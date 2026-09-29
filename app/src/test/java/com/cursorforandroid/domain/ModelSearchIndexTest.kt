package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.LiveModelCatalog
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * [ModelSearch.Index] finds exactly what the search did before it kept each model's words and the last query's
 * matches: [Reference] is that search as it was, run against the same catalogs, queries and models in use.
 */
class ModelSearchIndexTest {

    private val phrases = listOf(
        "opus 5.5 max fast", "Claude Opus 5", "5 opus", "sonnet 5 thinking", "grok 4.7 low", "gpt-5.6 sol none fast",
        "GPT 5.6 terra xhigh", "composer-latest fast", "cursor-grok", "opus55", "claude-opus-5-thinking", "sol low",
        "fable 5.1 300k max", "muse spark minimal", "codex extra-high", "4.6", "5-5", "gemini flash medium", "opus rewrite",
        "goal fix", "de", "dex", "auto", "claude 4.6 sonnet thinking", "opus 4.5 max", "flash 4.2 high fast", "gpt 6.1",
    )

    private val queries: List<String> = run {
        val prefixes = phrases.flatMap { p -> (0..p.length).map { p.take(it) } }
        val random = Random(7)
        val alphabet = "abcdefghilmnoprstux0123456789.- "
        val noise = List(400) { String(CharArray(1 + random.nextInt(8)) { alphabet[random.nextInt(alphabet.length)] }) }
        prefixes + prefixes.map { "$it " } + prefixes.map { it.uppercase() } + noise
    }

    private val families = listOf("Claude Opus", "Claude Sonnet", "GPT", "Gemini Flash", "Grok", "Composer", "Muse Spark", "Claude Fable")

    /** [n] models of eight variants each (four efforts, fast on and off), versions repeating across the families. */
    private fun synthetic(n: Int): List<ModelOption> = (0 until n).map { i ->
        val family = families[i % families.size]
        val name = "$family ${4 + (i / families.size) % 3}.${(i / (families.size * 3)) % 10}"
        val efforts = listOf("low", "medium", "high", "max")
        ModelOption(
            id = name.lowercase().replace(' ', '-'),
            displayName = name,
            parameters = listOf(
                ModelParameter("effort", "Effort", efforts.map { ModelParameterValue(it, it.replaceFirstChar(Char::uppercase)) }),
                ModelParameter("fast", "Fast", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Fast"))),
            ),
            variants = efforts.flatMap { e -> listOf("false", "true").map { f -> ModelVariant(name, listOf(ModelParam("effort", e), ModelParam("fast", f)), isDefault = e == "high" && f == "false") } },
            aliases = if (i % 5 == 0) listOf("${family.lowercase().replace(' ', '-')}-latest") else emptyList(),
        )
    }

    /** No model in use, and a few in use at a variant of their own or at none. */
    private fun currents(models: List<ModelOption>): List<ModelChoice?> {
        val listed = models.filterNot { it.isAuto }
        val opus = listed.first { "Opus" in it.displayName }
        val sol = listed.firstOrNull { "Sol" in it.displayName } ?: listed.last()
        return listOf(null, ModelChoice(opus, opus.variants.last()), ModelChoice(sol, null), ModelChoice(listed[listed.size / 2], listed[listed.size / 2].variants.first()))
    }

    private fun assertSameAsReference(models: List<ModelOption>) {
        val index = ModelSearch.Index(models)
        val currents = currents(models)
        for (query in queries) {
            // Asked the way the composer asks: whether the phrase names a model, then the popover's rows with the model in use.
            for (current in currents) {
                assertWithMessage("\"$query\" on ${current?.model?.id}").that(index.search(query, current)).isEqualTo(Reference.search(models, query, current))
            }
            assertWithMessage("\"$query\", a fresh index").that(ModelSearch.search(models, query)).isEqualTo(Reference.search(models, query))
        }
    }

    @Test
    fun `the live catalog answers every query as before`() = assertSameAsReference(LiveModelCatalog.models)

    @Test
    fun `a large catalog answers every query as before`() = assertSameAsReference(synthetic(120) + LiveModelCatalog.models)

    /** The search as it stood before [ModelSearch.Index]: every model's words worked out again for every query. */
    private object Reference {
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
                if (extras.last() == terms.last() && model.parameterWords().any { it.startsWith(extras.last()) }) {
                    val settled = extras.dropLast(1)
                    if (settled.isEmpty()) return model.choice(current)
                    variantSpelled(models, model, settled)?.let { return it }
                }
            }
            if (compact.length >= 2 && (runsFromWord(model.displayName, compact) || runsFromWord(model.id, compact))) return model.choice(current)
            return null
        }

        private fun runsFromWord(text: String, compact: String): Boolean {
            val words = ModelSlugs.words(text)
            return words.indices.any { i -> compact(words.drop(i).joinToString("")).startsWith(compact) }
        }

        private fun variantSpelled(models: List<ModelOption>, model: ModelOption, words: List<String>): ModelChoice? {
            val own = model.parameterWords()
            if (!words.all { it in own }) return null
            val placed = ModelSlugs.resolve(models, (listOf(model.id) + words).joinToString("-")) ?: return null
            return placed.takeIf { it.model.id == model.id }
        }

        private fun ModelOption.parameterWords(): Set<String> {
            val definitions = parameters.flatMap { p -> listOfNotNull(p.id, p.displayName) + p.values.flatMap { listOfNotNull(it.value, it.displayName) } }
            val used = variants.flatMap { v -> v.params.flatMap { listOf(it.id, it.value) } }
            return (definitions + used).flatMap(ModelSlugs::words).toSet()
        }

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
}
