package com.cursorforandroid.domain

/**
 * Every spelling Cursor hands the app for a model, read against the catalog (`GET /v1/models`) into the picker's own
 * entry and parameters: what a chat's record names (`requested_model.model_id`), what a Project's coordinator starts
 * its workers on, what this device remembered or drafted. The rules are the desktop's (Cursor 3.21.18,
 * `workbench.glass.main.js`), applied to the documented catalog, which carries `id`, `aliases`, `parameters` and
 * `variants` but none of the account service's `legacySlugs`, `variantStringRepresentation` or `serverModelName`:
 *
 * - an id or alias names the model, its variant the one nearest the record's parameters (the desktop's `YRS`: name,
 *   then `idAliases`), and `default` is Auto;
 * - `modelId[param=value,...]` is the canonical variant string (`q0h` writes it, `V0h` reads it);
 * - anything else is a model's id or alias followed by parameter words, the legacy slugs (`fgm`):
 *   `claude-opus-5-5-max-fast` is Claude Opus 5.5 at effort Max with Fast on. The words are the model's own parameter
 *   values and names, as its `parameters` define them and its `variants` use them, so no model or value is listed
 *   here. A true/false parameter is on when its name is spelled and off when it is not (`fgm`: `-fast`, `-thinking`);
 *   an unspelled choice keeps the model's default. `5-5` and `5.5` are one version (`aWa` compares ids with `-` read
 *   as `.`), `xhigh` is `extra-high` (`fgm`), and a model's words may come in another order than the catalog's
 *   (`claude-4-6-sonnet` for `claude-sonnet-4.6`).
 *
 * What the catalog cannot place keeps its raw id for requests and is shown by [readableName], never blank.
 */
object ModelSlugs {

    /**
     * The catalog's entry and variant [id] stands for, [params] (a record's explicit parameters) taking precedence over
     * the ones the id spells; null when the catalog cannot place it. An id or alias the catalog lists resolves exactly
     * as it always has, so nothing a record already resolved to moves.
     */
    fun resolve(models: List<ModelOption>, id: String, params: List<ModelParam> = emptyList()): ModelChoice? {
        val raw = id.trim()
        if (raw.isEmpty() || models.isEmpty()) return null
        models.firstOrNull { it.id == raw }?.let { return ModelChoice(it, it.variantNearest(params)) }
        models.firstOrNull { raw in it.aliases }?.let { return ModelChoice(it, it.variantNearest(params)) }
        if (raw == AccountModel.AUTO_ID) return models.autoOption()?.let { ModelChoice(it, it.variantNearest(params)) }
        variantString(raw)?.let { (base, spelled) -> return resolve(models, base, spelled.overriddenBy(params)) }
        return read(models, raw, params)
    }

    /**
     * What to call [id] when the catalog cannot place it: the model its words name, with the parameter words at its
     * end set aside (`claude-opus-6-max-fast` → "Claude Opus 6"), cased as the catalog's names case those words.
     * `default` is Auto; the result is never blank.
     */
    fun readableName(models: List<ModelOption>, id: String): String {
        val raw = id.trim()
        if (raw.isEmpty()) return AccountModel.AUTO_LABEL
        if (raw == AccountModel.AUTO_ID) return AccountModel.AUTO_LABEL
        val (name, _) = split(models, raw)
        val casing = casing(models)
        return name.joinToString(" ") { part -> casing[part.lowercase()] ?: part.titled() }.ifBlank { raw }
    }

    /** The parameters [readableName] set aside, in words ("Max · Fast"); null when the id spells none. */
    fun readableQualifier(models: List<ModelOption>, id: String): String? {
        val raw = id.trim()
        if (raw.isEmpty() || raw == AccountModel.AUTO_ID) return null
        variantString(raw)?.let { (_, params) ->
            return params.takeIf { it.isNotEmpty() }?.joinToString(" · ") { param ->
                when {
                    param.value.equals("true", ignoreCase = true) -> param.id.titled()
                    param.value.equals("false", ignoreCase = true) -> "${param.id.titled()} off"
                    else -> "${param.value.titled()} ${param.id.lowercase()}"
                }
            }
        }
        val (_, words) = split(models, raw)
        if (words.isEmpty()) return null
        val names = valueNames(models)
        return words.joinToString(" · ") { word -> names[word.lowercase()] ?: word.titled() }
    }

    // -- the variant string --------------------------------------------------------------------------------------

    /** `modelId[a=b,c=d]` as the desktop's `V0h` reads it, or null when [raw] is not one. */
    internal fun variantString(raw: String): Pair<String, List<ModelParam>>? {
        val open = raw.indexOf('[')
        val close = raw.lastIndexOf(']')
        if (open <= 0 || close != raw.length - 1 || close <= open) return null
        val base = raw.substring(0, open)
        val body = raw.substring(open + 1, close)
        if (body.isEmpty()) return base to emptyList()
        val params = body.split(',').map { segment ->
            val eq = segment.indexOf('=')
            if (eq <= 0 || eq != segment.lastIndexOf('=') || eq == segment.length - 1) return null
            ModelParam(segment.substring(0, eq), segment.substring(eq + 1))
        }
        return base to params
    }

    /** [this] with [explicit]'s values in place of its own, and [explicit]'s other parameters added. */
    private fun List<ModelParam>.overriddenBy(explicit: List<ModelParam>): List<ModelParam> {
        val ids = explicit.mapTo(HashSet()) { it.id }
        return filterNot { it.id in ids } + explicit
    }

    // -- the legacy slug -----------------------------------------------------------------------------------------

    /** One way of reading the slug: which model, which parameters, and how well the words fitted. */
    private class Reading(
        val model: ModelOption,
        val variant: ModelVariant?,
        val inOrder: Boolean,
        /** Parameter words the model has no use for (a `-fast` on a model without speeds). */
        val dropped: Int,
        /** Parameters the model's variants cannot all combine; the nearest variant stood in. */
        val misfits: Int,
        val nameLength: Int,
        val order: Int,
    )

    private val better: Comparator<Reading> = compareByDescending<Reading> { it.inOrder }
        .thenBy { it.dropped + it.misfits }
        .thenByDescending { it.nameLength }
        .thenBy { it.order }

    private fun read(models: List<ModelOption>, raw: String, explicit: List<ModelParam>): ModelChoice? {
        val slug = words(raw)
        if (slug.isEmpty()) return null
        val droppable = droppableWords(models)
        val readings = models.withIndex().flatMap { (order, model) ->
            val vocabulary = vocabulary(model)
            model.names().mapNotNull { name ->
                val nameWords = words(name).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val inOrder = slug.startsWith(nameWords, 0)
                val rest = if (inOrder) slug.drop(nameWords.size) else slug.without(nameWords) ?: return@mapNotNull null
                val spelled = vocabulary.read(rest, droppable) ?: return@mapNotNull null
                val (variant, misfits) = model.variantFor(spelled.values.overriddenBy(explicit), vocabulary.switches.takeIf { rest.isNotEmpty() }.orEmpty())
                Reading(model, variant, inOrder, spelled.dropped, misfits, nameWords.size, order)
            }
        }
        val best = readings.minWithOrNull(better) ?: return null
        return ModelChoice(best.model, best.variant)
    }

    /** A model's names, most exact first: its id, its aliases, and the name it is shown under. */
    private fun ModelOption.names(): List<String> = (listOf(id) + aliases + displayName).distinct()

    /** A phrase of parameter words and what it sets: [value] null is the parameter's own name, which sets nothing. */
    private data class Phrase(val words: List<String>, val parameterId: String, val value: String?)

    private class Vocabulary(val phrases: List<Phrase>, val switches: Map<String, String>) {
        /** The words [rest] spell, or null when one of them means nothing here nor anywhere in the catalog. */
        fun read(rest: List<String>, droppable: Set<String>): Spelled? {
            val set = LinkedHashMap<String, String>()
            var dropped = 0
            var i = 0
            while (i < rest.size) {
                val phrase = phrases.firstOrNull { p -> rest.startsWith(p.words, i) && (p.value == null || p.parameterId !in set) }
                when {
                    phrase != null -> {
                        phrase.value?.let { set[phrase.parameterId] = it }
                        i += phrase.words.size
                    }
                    rest[i] in droppable -> {
                        dropped++
                        i++
                    }
                    else -> return null
                }
            }
            return Spelled(set.map { (id, value) -> ModelParam(id, value) }, dropped)
        }
    }

    private class Spelled(val values: List<ModelParam>, val dropped: Int)

    /**
     * The words a model's parameters answer to, from its `parameters` definitions and the values its `variants` use:
     * a value, or its display name, sets it (`max`, `extra-high`, `1m`); a true/false parameter is set on by its name
     * or its on-value's name (`fast`), and kept in [Vocabulary.switches] with its off-value; any other parameter's
     * name is a word that sets nothing (`thinking` before an effort).
     */
    private fun vocabulary(model: ModelOption): Vocabulary {
        val ids = (model.parameters.map { it.id } + model.variants.flatMap { v -> v.params.map { it.id } }).distinct()
        val phrases = ArrayList<Phrase>()
        val switches = LinkedHashMap<String, String>()
        for (id in ids) {
            val definition = model.parameters.firstOrNull { it.id == id }
            val values = (definition?.values.orEmpty().map { it.value } + model.variants.mapNotNull { it.param(id) }).distinct()
            fun nameOf(value: String) = definition?.values?.firstOrNull { it.value == value }?.displayName
            val names = listOfNotNull(id, definition?.displayName)
            val on = values.firstOrNull { it.equals("true", ignoreCase = true) }
            val off = values.firstOrNull { it.equals("false", ignoreCase = true) }
            if (on != null && values.all { it == on || it == off }) {
                switches[id] = off ?: "false"
                (names + listOfNotNull(nameOf(on))).forEach { phrases += Phrase(words(it), id, on) }
            } else {
                values.forEach { value -> listOfNotNull(value, nameOf(value)).flatMap(::spellings).forEach { phrases += Phrase(it, id, value) } }
                names.forEach { phrases += Phrase(words(it), id, null) }
            }
        }
        val ordered = phrases.filter { it.words.isNotEmpty() }.distinct()
            .sortedWith(compareByDescending<Phrase> { it.words.size }.thenBy { it.value == null })
        return Vocabulary(ordered, switches)
    }

    /** A value's words, and — as the desktop reads `-xhigh` and `extra-high` alike — its other spelling. */
    private fun spellings(text: String): List<List<String>> {
        val w = words(text)
        return when {
            w.size == 2 && w[0] == "extra" -> listOf(w, listOf("x${w[1]}"))
            w.size == 1 && w[0].length > 2 && w[0][0] == 'x' && w[0][1].isLetter() -> listOf(w, listOf("extra", w[0].drop(1)))
            else -> listOf(w)
        }
    }

    /**
     * The variant [set] names on this model: the one carrying every value set, with each of [switches] off unless it
     * is set (a legacy slug spells every switch that is on), the model's default when it qualifies, else the nearest
     * to it. When no variant combines them all, the nearest one, and how many values it misses.
     */
    private fun ModelOption.variantFor(set: List<ModelParam>, switches: Map<String, String>): Pair<ModelVariant?, Int> {
        val wanted = set.associate { it.id to it.value }
        val off = switches.filterKeys { it !in wanted }
        if (variants.isEmpty()) return (if (set.isEmpty()) null else ModelVariant(displayName, set, isDefault = false)) to 0
        fun ModelVariant.fits() = wanted.all { (id, value) -> param(id) == value } && off.all { (id, value) -> param(id)?.let { it == value } ?: true }
        val preferred = defaultVariant?.params?.toSet().orEmpty()
        fun ModelVariant.fromDefault() = (params.toSet() - preferred).size
        val fitting = variants.filter { it.fits() }
        if (fitting.isNotEmpty()) return (fitting.firstOrNull { it.isDefault } ?: fitting.minBy { it.fromDefault() }) to 0
        val target = wanted + off
        fun ModelVariant.misses() = target.count { (id, value) -> param(id) != value }
        val nearest = variants.minWith(compareBy<ModelVariant> { it.misses() }.thenBy { !it.isDefault }.thenBy { it.fromDefault() })
        return nearest to nearest.misses()
    }

    // -- words ---------------------------------------------------------------------------------------------------

    /**
     * [text] as lower-case words, any run of non-alphanumerics a separator, and a run of short numbers one version:
     * `claude-opus-5-5`, `claude-opus-5.5` and "Claude Opus 5.5" are all `claude opus 5.5`.
     */
    internal fun words(text: String): List<String> = parts(text).map { it.lowercase() }

    /** [text]'s parts as written, a run of short numbers joined into one dotted version (`5-5` → `5.5`). */
    private fun parts(text: String): List<String> {
        val raw = text.split(SEPARATOR).filter { it.isNotEmpty() }
        val out = ArrayList<String>(raw.size)
        var i = 0
        while (i < raw.size) {
            if (raw[i].isVersionPart()) {
                var j = i
                while (j < raw.size && raw[j].isVersionPart()) j++
                out += raw.subList(i, j).joinToString(".")
                i = j
            } else {
                out += raw[i++]
            }
        }
        return out
    }

    private fun String.isVersionPart(): Boolean = length in 1..2 && all(Char::isDigit)

    private fun List<String>.startsWith(prefix: List<String>, at: Int): Boolean =
        at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }

    /** [this] less one of each of [taken]'s words, in its own order; null when it lacks one of them. */
    private fun List<String>.without(taken: List<String>): List<String>? {
        val rest = toMutableList()
        for (word in taken) if (!rest.remove(word)) return null
        return rest
    }

    /**
     * Words a slug may carry that its model has no use for: any model's parameter words, and the suffixes Cursor's
     * slugs are built from whatever the catalog says (see [SUFFIX_WORDS]), which name no model.
     */
    private fun droppableWords(models: List<ModelOption>): Set<String> =
        models.flatMapTo(HashSet(SUFFIX_WORDS)) { model -> vocabulary(model).phrases.flatMap { it.words } }

    /** [raw]'s model name as written, and the parameter words at its end. */
    private fun split(models: List<ModelOption>, raw: String): Pair<List<String>, List<String>> {
        val base = variantString(raw)?.first ?: raw
        val parts = parts(base)
        val droppable = droppableWords(models)
        var end = parts.size
        while (end > 1 && parts[end - 1].lowercase() in droppable) end--
        return parts.take(end) to parts.drop(end)
    }

    /** How the catalog's names spell their words ("GPT", "Claude"), keyed by the word in lower case. */
    private fun casing(models: List<ModelOption>): Map<String, String> =
        models.flatMap { parts(it.displayName) }.filter { it.any(Char::isUpperCase) }.associateBy { it.lowercase() }

    /** A parameter word's name as the catalog shows it ("Extra High", "1M"), keyed by the word. */
    private fun valueNames(models: List<ModelOption>): Map<String, String> = buildMap {
        for (model in models) {
            for (definition in model.parameters) {
                for (value in definition.values) {
                    val name = value.displayName?.takeIf { it.isNotBlank() } ?: continue
                    spellings(value.value).filter { it.size == 1 }.forEach { putIfAbsent(it.single(), name) }
                }
            }
        }
    }

    /** A word as a name: a version or a word with capitals as it is, a word without vowels in capitals (`GPT`), else capitalized. */
    private fun String.titled(): String = when {
        any(Char::isUpperCase) || first().isDigit() -> this
        length <= 4 && none { it in VOWELS } -> uppercase()
        else -> replaceFirstChar { it.uppercaseChar() }
    }

    private val SEPARATOR = Regex("[^A-Za-z0-9]+")
    private const val VOWELS = "aeiouy"
    /**
     * The desktop's own suffixes (`modelNameDisplayLookup`: `-thinking`, `-text`, `-fast`, `-xhigh`, `-high`, `-low`,
     * `-medium`; `fgm`: `-max`) and the effort scale's two ends Cursor's slugs also end in (`gpt-5.6-sol-none`,
     * `muse-spark-1.3-minimal`): enough to name a slug readably before the catalog has answered.
     */
    private val SUFFIX_WORDS = setOf("thinking", "text", "fast", "xhigh", "high", "low", "medium", "max", "none", "minimal")
}
