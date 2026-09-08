package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelOptionTest {

    private val fast = ModelVariant("Composer 2", listOf(ModelParam("fast", "true")), isDefault = true)
    private val slow = ModelVariant("Composer 2", listOf(ModelParam("fast", "false")), isDefault = false)
    private val composer = ModelOption(
        id = "composer-2",
        displayName = "Composer 2",
        parameters = listOf(ModelParameter("fast", "Fast", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Fast")))),
        variants = listOf(fast, slow),
    )

    @Test
    fun `qualifier reads parameters through the API's display names`() {
        assertThat(composer.qualifier(fast)).isEqualTo("Fast")
        assertThat(composer.qualifier(slow)).isEqualTo("Fast off")
    }

    @Test
    fun `qualifier falls back to humanized ids and values without definitions`() {
        val model = ModelOption(id = "m", displayName = "M")
        val variant = ModelVariant("M", listOf(ModelParam("context_window", "1m"), ModelParam("effort", "max")), isDefault = true)
        assertThat(model.qualifier(variant)).isEqualTo("1m context window · Max effort")
    }

    @Test
    fun `qualifier combines a value name with its parameter name`() {
        val model = ModelOption(
            id = "m", displayName = "M",
            parameters = listOf(ModelParameter("effort", "Effort", listOf(ModelParameterValue("high", "High")))),
        )
        assertThat(model.qualifier(ModelVariant("M", listOf(ModelParam("effort", "high")), isDefault = true))).isEqualTo("High effort")
    }

    @Test
    fun `qualifier is null for a variant without parameters`() {
        val model = ModelOption(id = "m", displayName = "M")
        assertThat(model.qualifier(ModelVariant("M", emptyList(), isDefault = true))).isNull()
    }

    /** The live catalogue's Claude: a `thinking` switch, a context window and an effort, every variant named after the model. */
    private val claude = ModelOption(
        id = "claude-fable-5.1",
        displayName = "Claude Fable 5.1",
        parameters = listOf(
            ModelParameter("thinking", "Thinking", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Thinking"))),
            ModelParameter("context", "Context", listOf(ModelParameterValue("300k", "300K"), ModelParameterValue("1m", "1M"))),
            ModelParameter("effort", "Effort", listOf(ModelParameterValue("high", "High"), ModelParameterValue("max", "Max"))),
        ),
        variants = listOf("300k", "1m").flatMap { context ->
            listOf("high", "max").map { effort ->
                ModelVariant(
                    "Claude Fable 5.1",
                    listOf(ModelParam("thinking", "true"), ModelParam("context", context), ModelParam("effort", effort)),
                    isDefault = context == "1m" && effort == "max",
                )
            }
        } + ModelVariant("Claude Fable 5.1", listOf(ModelParam("thinking", "false"), ModelParam("context", "300k")), isDefault = false),
    )

    private fun ModelOption.variant(vararg params: Pair<String, String>): ModelVariant = variantWithParams(params.toMap())!!

    /**
     * The chip reads the model's name and nothing else. The variant's own name is not it either: the catalogue
     * spells parameters into it ("Claude Fable 5.1 1M Max", "GPT-5.6 High") as often as it repeats the model's name.
     */
    @Test
    fun `a choice is labelled with the model's name alone, whatever its variant`() {
        assertThat(ModelChoice(composer, fast).label).isEqualTo("Composer 2")
        assertThat(ModelChoice(composer, slow).label).isEqualTo("Composer 2")
        assertThat(ModelChoice(composer, null).label).isEqualTo("Composer 2")
        assertThat(ModelChoice(claude, claude.variant("thinking" to "true", "context" to "1m", "effort" to "max")).label).isEqualTo("Claude Fable 5.1")

        val gpt = ModelOption(id = "gpt-5.6", displayName = "GPT-5.6", variants = listOf(ModelVariant("GPT-5.6 High", listOf(ModelParam("effort", "high")), isDefault = true)))
        assertThat(ModelChoice(gpt, gpt.defaultVariant).label).isEqualTo("GPT-5.6")

        val auto = ModelOption(id = "auto-smart", displayName = "Auto")
        assertThat(ModelChoice(auto, null).label).isEqualTo("Auto")
    }

    @Test
    fun `a choice carries the parameters of the variant it stands for`() {
        assertThat(ModelChoice(composer, slow).params).containsExactly(ModelParam("fast", "false"))
        assertThat(ModelChoice(ModelOption(id = "auto-smart", displayName = "Auto"), null).params).isEmpty()
    }

    @Test
    fun `variantWithParams matches exactly, including the parameter-less variant`() {
        val bare = ModelVariant("Sonnet", emptyList(), isDefault = true)
        val sonnet = ModelOption(id = "sonnet", displayName = "Sonnet", variants = listOf(bare))
        assertThat(sonnet.variantWithParams(emptyMap())).isEqualTo(bare)
        assertThat(composer.variantWithParams(mapOf("fast" to "false"))).isEqualTo(slow)
        assertThat(composer.variantWithParams(emptyMap())).isNull()
        assertThat(composer.variantWithParams(mapOf("fast" to "true", "other" to "x"))).isNull()
    }

    @Test
    fun `choiceFor finds the entry a request's model id and params were built from`() {
        val auto = ModelOption(id = "auto-smart", displayName = "Auto")
        val catalog = listOf(composer, auto)
        assertThat(catalog.choiceFor("composer-2", listOf(ModelParam("fast", "false")))).isEqualTo(ModelChoice(composer, slow))
        assertThat(catalog.choiceFor("auto-smart", emptyList())).isEqualTo(ModelChoice(auto, null))
        // A model that is gone, a variant that no longer exists, or parameters a variant-less model never had.
        assertThat(catalog.choiceFor("gone", emptyList())).isNull()
        assertThat(catalog.choiceFor("composer-2", emptyList())).isNull()
        assertThat(catalog.choiceFor("auto-smart", listOf(ModelParam("effort", "high")))).isNull()
    }

    @Test
    fun `choiceLabelled finds the entry a chip label was built from, in today's and in earlier versions' spelling`() {
        val auto = ModelOption(id = "auto-smart", displayName = "Auto")
        val gpt = ModelOption(id = "gpt-5.6", displayName = "GPT-5.6", variants = listOf(ModelVariant("GPT-5.6 High", listOf(ModelParam("effort", "high")), isDefault = true)))
        val catalog = listOf(composer, claude, gpt, auto)
        // The model's name, as recorded now: the default variant, the parameters being unknown.
        assertThat(catalog.choiceLabelled("Composer 2")).isEqualTo(ModelChoice(composer, fast))
        assertThat(catalog.choiceLabelled("Claude Fable 5.1")).isEqualTo(ModelChoice(claude, claude.defaultVariant))
        assertThat(catalog.choiceLabelled("GPT-5.6")).isEqualTo(ModelChoice(gpt, gpt.defaultVariant))
        assertThat(catalog.choiceLabelled("Auto")).isEqualTo(ModelChoice(auto, null))
        // Earlier versions recorded the variant's name, with its parameters appended when siblings shared it.
        assertThat(catalog.choiceLabelled("Composer 2 · Fast off")).isEqualTo(ModelChoice(composer, slow))
        assertThat(catalog.choiceLabelled("GPT-5.6 High")).isEqualTo(ModelChoice(gpt, gpt.defaultVariant))
        assertThat(catalog.choiceLabelled("Claude Fable 5.1 · Thinking · 300K context · High effort"))
            .isEqualTo(ModelChoice(claude, claude.variant("thinking" to "true", "context" to "300k", "effort" to "high")))
        // "Default model" is what a launch without a model was recorded as; it is nobody's name.
        assertThat(catalog.choiceLabelled("Default model")).isNull()
    }

    @Test
    fun `a row's model name is its recorded label less the parameters earlier versions appended`() {
        fun row(label: String?) = Agent(
            id = "bc-1", name = "Chat", lifecycle = AgentLifecycle.IDLE, runStatus = null, envType = EnvType.CLOUD, envName = null,
            url = "", createdAtMillis = 0L, updatedAtMillis = 0L, latestRunId = null, repoUrl = null, startingRef = null, modelDisplayName = label,
        )
        assertThat(row("Claude Fable 5.1").modelName).isEqualTo("Claude Fable 5.1")
        assertThat(row("Claude Fable 5.1 · Thinking · 1M context · Max effort").modelName).isEqualTo("Claude Fable 5.1")
        assertThat(row("Composer 2 · Fast off").modelName).isEqualTo("Composer 2")
        assertThat(row("Default model").modelName).isEqualTo("Default model")
        assertThat(row(null).modelName).isNull()
    }

    @Test
    fun `defaultVariant prefers the API default and falls back to the first`() {
        assertThat(composer.defaultVariant).isEqualTo(fast)
        val none = ModelOption(id = "m", displayName = "M", variants = listOf(slow.copy(isDefault = false), fast.copy(isDefault = false)))
        assertThat(none.defaultVariant).isEqualTo(slow.copy(isDefault = false))
        assertThat(ModelOption(id = "m", displayName = "M").defaultVariant).isNull()
    }

    // ---- axes: the pickers a model gets -------------------------------------------------------------------------

    /** The live catalogue's shape: an effort × fast grid, every variant named after the model. */
    private fun grok(vararg combos: Pair<String, String>, default: Pair<String, String> = "high" to "true") = ModelOption(
        id = "cursor-grok-4.6",
        displayName = "Cursor Grok 4.6",
        parameters = listOf(
            ModelParameter("effort", "Effort", listOf(ModelParameterValue("low", "Low"), ModelParameterValue("medium", "Medium"), ModelParameterValue("high", "High"), ModelParameterValue("xhigh", "Extra High"))),
            ModelParameter("fast", "Fast", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Fast"))),
        ),
        variants = combos.map { (effort, fast) ->
            ModelVariant("Cursor Grok 4.6", listOf(ModelParam("effort", effort), ModelParam("fast", fast)), isDefault = (effort to fast) == default)
        },
    )

    private val grokGrid = grok(
        "low" to "true", "low" to "false", "medium" to "true", "medium" to "false",
        "high" to "true", "high" to "false", "xhigh" to "true", "xhigh" to "false",
    )

    @Test
    fun `axes follow the API's parameter definitions, order and names included`() {
        assertThat(grokGrid.axes.map { it.id }).containsExactly("effort", "fast").inOrder()
        val effort = grokGrid.axes.first()
        assertThat(effort.displayName).isEqualTo("Effort")
        assertThat(effort.values.map { it.displayName }).containsExactly("Low", "Medium", "High", "Extra High").inOrder()
        assertThat(effort.isSwitch).isFalse()
        val fast = grokGrid.axes.last()
        assertThat(fast.displayName).isEqualTo("Fast")
        assertThat(fast.isSwitch).isTrue()
        assertThat(fast.onValue).isEqualTo("true")
        assertThat(fast.offValue).isEqualTo("false")
    }

    @Test
    fun `axes only offer values some variant actually uses`() {
        // Definitions permit four efforts, but the variants only come in high and extra high.
        val partial = grok("high" to "true", "high" to "false", "xhigh" to "true", "xhigh" to "false")
        assertThat(partial.axes.first { it.id == "effort" }.values.map { it.value }).containsExactly("high", "xhigh").inOrder()
    }

    @Test
    fun `a parameter every variant agrees on is not an axis`() {
        // Always fast: nothing to pick, the row's subtitle says "Fast".
        val alwaysFast = grok("low" to "true", "high" to "true")
        assertThat(alwaysFast.axes.map { it.id }).containsExactly("effort")
        val single = ModelOption(id = "gpt", displayName = "GPT", variants = listOf(ModelVariant("GPT High", listOf(ModelParam("effort", "high")), isDefault = true)))
        assertThat(single.axes).isEmpty()
        assertThat(ModelOption(id = "auto", displayName = "Auto").axes).isEmpty()
    }

    @Test
    fun `axes are derived from the variants when the API sends no definitions`() {
        val max = ModelVariant("Claude 1M Max", listOf(ModelParam("context", "1m"), ModelParam("effort", "max")), isDefault = true)
        val high = ModelVariant("Claude 1M High", listOf(ModelParam("context", "1m"), ModelParam("effort", "high")), isDefault = false)
        val claude = ModelOption(id = "claude", displayName = "Claude", variants = listOf(max, high))
        val axes = claude.axes
        // Context is 1M throughout, so effort is the only knob; names fall back to humanized ids and values.
        assertThat(axes.map { it.id }).containsExactly("effort")
        assertThat(axes.single().displayName).isEqualTo("Effort")
        assertThat(axes.single().values.map { it.displayName }).containsExactly("Max", "High").inOrder()
    }

    @Test
    fun `undeclared values the variants use come after the declared ones`() {
        val model = ModelOption(
            id = "m", displayName = "M",
            parameters = listOf(ModelParameter("effort", "Effort", listOf(ModelParameterValue("high", "High")))),
            variants = listOf(
                ModelVariant("M", listOf(ModelParam("effort", "ultra")), isDefault = false),
                ModelVariant("M", listOf(ModelParam("effort", "high")), isDefault = true),
            ),
        )
        assertThat(model.axes.single().values.map { it.displayName }).containsExactly("High", "Ultra").inOrder()
    }

    // ---- variantWith: what a picker change resolves to ---------------------------------------------------------

    @Test
    fun `variantWith changes one parameter and keeps the others`() {
        val current = grokGrid.variantWithParams(mapOf("effort" to "low", "fast" to "false"))!!
        assertThat(grokGrid.variantWith(current, "effort", "xhigh")).isEqualTo(grokGrid.variantWithParams(mapOf("effort" to "xhigh", "fast" to "false")))
        assertThat(grokGrid.variantWith(current, "fast", "true")).isEqualTo(grokGrid.variantWithParams(mapOf("effort" to "low", "fast" to "true")))
        assertThat(composer.variantWith(fast, "fast", "false")).isEqualTo(slow)
    }

    @Test
    fun `variantWith settles for the closest variant when the exact combination is not offered`() {
        // Extra high only comes fast; asking for it from a slow variant switches fast on rather than refusing.
        val partial = grok("low" to "false", "low" to "true", "xhigh" to "true")
        val slowLow = partial.variantWithParams(mapOf("effort" to "low", "fast" to "false"))!!
        assertThat(partial.variantWith(slowLow, "effort", "xhigh")).isEqualTo(partial.variantWithParams(mapOf("effort" to "xhigh", "fast" to "true")))
    }

    @Test
    fun `variantWith prefers the API's default among equally close variants`() {
        // From a variant that sets nothing else, every candidate is equally far; the default wins, not the first.
        val fromNothing = grokGrid.variantWith(ModelVariant("Cursor Grok 4.6", emptyList(), isDefault = false), "fast", "true")
        assertThat(fromNothing).isEqualTo(grokGrid.variantWithParams(mapOf("effort" to "high", "fast" to "true")))
        assertThat(grokGrid.variantWith(null, "fast", "false")).isEqualTo(grokGrid.variantWithParams(mapOf("effort" to "high", "fast" to "false")))
    }

    @Test
    fun `variantWith is null for a value no variant carries`() {
        assertThat(grokGrid.variantWith(grokGrid.defaultVariant, "effort", "max")).isNull()
        assertThat(grokGrid.variantWith(grokGrid.defaultVariant, "context", "1m")).isNull()
    }

    @Test
    fun `parameter and value names come from the definitions, humanized ids and values otherwise`() {
        assertThat(grokGrid.parameterName("effort")).isEqualTo("Effort")
        assertThat(grokGrid.valueName("effort", "xhigh")).isEqualTo("Extra High")
        assertThat(grokGrid.parameterName("context_window")).isEqualTo("Context window")
        assertThat(grokGrid.valueName("effort", "ultra")).isEqualTo("Ultra")
        assertThat(fast.param("fast")).isEqualTo("true")
        assertThat(fast.param("effort")).isNull()
    }
}
