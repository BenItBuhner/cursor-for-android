package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.LiveModelCatalog
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [ModelSlugs], table-driven over a catalogue shaped like the live one ([LiveModelCatalog]): every model and every
 * parameter combination it offers, in each spelling Cursor uses for it; every slug seen from Cursor; and slugs no
 * catalogue lists. "Resolves to" always means the entry and variant the picker would hold had the model been picked by
 * hand: the model, then each parameter set on it in turn from the model's default ([ModelOption.variantWith]).
 */
class ModelSlugsTest {

    private val catalog = LiveModelCatalog.models

    /** The picker's selection after picking [model] and setting each of [params] on it, as a person would. */
    private fun pickedByHand(model: ModelOption, params: List<ModelParam>): ModelChoice =
        ModelChoice(model, params.fold(model.defaultVariant) { current, p -> model.variantWith(current, p.id, p.value) ?: current })

    /** The switches a variant has on, and the value of every other parameter, as a legacy slug spells them. */
    private fun ModelOption.words(variant: ModelVariant): List<String> {
        val switches = axes.filter { it.isSwitch }.map { it.id }.toSet() + parameters.filter { d -> d.values.map { it.value }.toSet() == setOf("false", "true") }.map { it.id }
        return variant.params.mapNotNull { p -> if (p.id in switches) p.id.takeIf { p.value == "true" } else p.value }
    }

    // -- every model and every combination ------------------------------------------------------------------------

    @Test
    fun `every catalogue model and parameter combination resolves from each of its spellings to the hand-picked entry`() {
        var checked = 0
        for (model in catalog) {
            val variants = model.variants.ifEmpty { listOf(null) }
            for (variant in variants) {
                val params = variant?.params.orEmpty()
                val expected = pickedByHand(model, params)
                assertWithMessage("${model.id} $params by hand").that(expected.variant).isEqualTo(variant)

                // The id with the record's parameters, as `requested_model {model_id, parameters}` carries them.
                assertWithMessage("${model.id} + $params").that(catalog.choiceFor(AccountModel(model.id, params))).isEqualTo(expected)
                // The canonical variant string (`q0h`): `claude-opus-5.5[effort=max,fast=true]`.
                val canonical = "${model.id}[${params.joinToString(",") { "${it.id}=${it.value}" }}]"
                assertWithMessage(canonical).that(ModelSlugs.resolve(catalog, canonical)).isEqualTo(expected)

                // The legacy slug, with the version dotted and dashed, the words in the model's order and reversed. A
                // combination with every switch off and nothing else to spell is the bare id, which is the model's
                // default variant by the API's definition (`isDefault`), checked below.
                val words = variant?.let { model.words(it) }.orEmpty()
                if (words.isEmpty()) continue
                val dashed = model.id.replace('.', '-')
                for (slug in listOf("${model.id}-${words.joinToString("-")}", "$dashed-${words.joinToString("-")}", "$dashed-${words.reversed().joinToString("-")}")) {
                    assertWithMessage(slug).that(ModelSlugs.resolve(catalog, slug)).isEqualTo(expected)
                    assertWithMessage("$slug as a record").that(catalog.choiceFor(AccountModel(slug))).isEqualTo(expected)
                    checked++
                }
            }
        }
        // 97 spellable combinations across the catalogue, three spellings each.
        assertThat(checked).isEqualTo(291)
    }

    @Test
    fun `a bare id, in either version spelling, is the variant Cursor picks for it`() {
        for (model in catalog) {
            val expected = ModelChoice(model, model.defaultVariant)
            assertWithMessage(model.id).that(ModelSlugs.resolve(catalog, model.id)).isEqualTo(expected)
            assertWithMessage(model.id.replace('.', '-')).that(ModelSlugs.resolve(catalog, model.id.replace('.', '-'))).isEqualTo(expected)
            assertWithMessage(model.id.uppercase()).that(ModelSlugs.resolve(catalog, model.id.uppercase())).isEqualTo(expected)
        }
    }

    // -- every slug seen from Cursor ------------------------------------------------------------------------------

    private class Seen(val slug: String, val modelId: String, vararg params: Pair<String, String>) {
        val params: List<ModelParam> = params.map { (id, value) -> ModelParam(id, value) }
    }

    /**
     * The models Cursor offers its agents for subagents and Project workers, as it spells them, and the worker model
     * on record for this project (`claude-opus-5-5-xhigh`). Bennett's report is `claude-opus-5-5-max-fast`: Claude Opus
     * 5.5, effort Max, Fast on.
     */
    private val seen = listOf(
        Seen("claude-fable-5-1-thinking-high", "claude-fable-5.1-thinking", "context" to "1m", "effort" to "high"),
        Seen("claude-fable-5-1-thinking-low", "claude-fable-5.1-thinking", "context" to "1m", "effort" to "low"),
        Seen("claude-fable-5-1-thinking-max", "claude-fable-5.1-thinking", "context" to "1m", "effort" to "max"),
        Seen("claude-fable-5-1-thinking-medium", "claude-fable-5.1-thinking", "context" to "1m", "effort" to "medium"),
        Seen("claude-fable-5-1-thinking-xhigh", "claude-fable-5.1-thinking", "context" to "1m", "effort" to "xhigh"),
        Seen("claude-opus-5-5-high", "claude-opus-5.5", "effort" to "high", "fast" to "false"),
        Seen("claude-opus-5-5-high-fast", "claude-opus-5.5", "effort" to "high", "fast" to "true"),
        Seen("claude-opus-5-5-low", "claude-opus-5.5", "effort" to "low", "fast" to "false"),
        Seen("claude-opus-5-5-low-fast", "claude-opus-5.5", "effort" to "low", "fast" to "true"),
        Seen("claude-opus-5-5-max", "claude-opus-5.5", "effort" to "max", "fast" to "false"),
        Seen("claude-opus-5-5-max-fast", "claude-opus-5.5", "effort" to "max", "fast" to "true"),
        Seen("claude-opus-5-5-medium", "claude-opus-5.5", "effort" to "medium", "fast" to "false"),
        Seen("claude-opus-5-5-medium-fast", "claude-opus-5.5", "effort" to "medium", "fast" to "true"),
        Seen("claude-opus-5-5-xhigh", "claude-opus-5.5", "effort" to "xhigh", "fast" to "false"),
        Seen("claude-opus-5-5-xhigh-fast", "claude-opus-5.5", "effort" to "xhigh", "fast" to "true"),
        Seen("claude-opus-5-thinking-high", "claude-opus-5-thinking", "effort" to "high", "fast" to "false"),
        Seen("claude-opus-5-thinking-high-fast", "claude-opus-5-thinking", "effort" to "high", "fast" to "true"),
        Seen("claude-opus-5-thinking-low", "claude-opus-5-thinking", "effort" to "low", "fast" to "false"),
        Seen("claude-opus-5-thinking-low-fast", "claude-opus-5-thinking", "effort" to "low", "fast" to "true"),
        Seen("claude-opus-5-thinking-max", "claude-opus-5-thinking", "effort" to "max", "fast" to "false"),
        Seen("claude-opus-5-thinking-max-fast", "claude-opus-5-thinking", "effort" to "max", "fast" to "true"),
        Seen("claude-opus-5-thinking-medium", "claude-opus-5-thinking", "effort" to "medium", "fast" to "false"),
        Seen("claude-opus-5-thinking-medium-fast", "claude-opus-5-thinking", "effort" to "medium", "fast" to "true"),
        Seen("claude-opus-5-thinking-xhigh", "claude-opus-5-thinking", "effort" to "xhigh", "fast" to "false"),
        Seen("claude-opus-5-thinking-xhigh-fast", "claude-opus-5-thinking", "effort" to "xhigh", "fast" to "true"),
        Seen("claude-sonnet-5-thinking-high", "claude-sonnet-5", "thinking" to "true", "effort" to "high"),
        Seen("claude-sonnet-5-thinking-low", "claude-sonnet-5", "thinking" to "true", "effort" to "low"),
        Seen("claude-sonnet-5-thinking-max", "claude-sonnet-5", "thinking" to "true", "effort" to "max"),
        Seen("claude-sonnet-5-thinking-medium", "claude-sonnet-5", "thinking" to "true", "effort" to "medium"),
        Seen("claude-sonnet-5-thinking-xhigh", "claude-sonnet-5", "thinking" to "true", "effort" to "xhigh"),
        Seen("cursor-grok-4.6-high", "cursor-grok-4.6", "effort" to "high", "fast" to "false"),
        Seen("cursor-grok-4.6-high-fast", "cursor-grok-4.6", "effort" to "high", "fast" to "true"),
        Seen("cursor-grok-4.6-low", "cursor-grok-4.6", "effort" to "low", "fast" to "false"),
        Seen("cursor-grok-4.6-low-fast", "cursor-grok-4.6", "effort" to "low", "fast" to "true"),
        Seen("cursor-grok-4.6-medium", "cursor-grok-4.6", "effort" to "medium", "fast" to "false"),
        Seen("cursor-grok-4.6-medium-fast", "cursor-grok-4.6", "effort" to "medium", "fast" to "true"),
        Seen("cursor-grok-4.6-xhigh", "cursor-grok-4.6", "effort" to "xhigh", "fast" to "false"),
        Seen("cursor-grok-4.6-xhigh-fast", "cursor-grok-4.6", "effort" to "xhigh", "fast" to "true"),
        Seen("gemini-3.8-flash-high", "gemini-3.8-flash", "effort" to "high"),
        Seen("gemini-3.8-flash-low", "gemini-3.8-flash", "effort" to "low"),
        Seen("gemini-3.8-flash-medium", "gemini-3.8-flash", "effort" to "medium"),
        Seen("gpt-5.6-sol-high", "gpt-5.6-sol", "effort" to "high", "fast" to "false"),
        Seen("gpt-5.6-sol-high-fast", "gpt-5.6-sol", "effort" to "high", "fast" to "true"),
        Seen("gpt-5.6-sol-low", "gpt-5.6-sol", "effort" to "low", "fast" to "false"),
        Seen("gpt-5.6-sol-low-fast", "gpt-5.6-sol", "effort" to "low", "fast" to "true"),
        Seen("gpt-5.6-sol-max", "gpt-5.6-sol", "effort" to "max", "fast" to "false"),
        Seen("gpt-5.6-sol-max-fast", "gpt-5.6-sol", "effort" to "max", "fast" to "true"),
        Seen("gpt-5.6-sol-medium", "gpt-5.6-sol", "effort" to "medium", "fast" to "false"),
        Seen("gpt-5.6-sol-medium-fast", "gpt-5.6-sol", "effort" to "medium", "fast" to "true"),
        Seen("gpt-5.6-sol-none", "gpt-5.6-sol", "effort" to "none", "fast" to "false"),
        Seen("gpt-5.6-sol-none-fast", "gpt-5.6-sol", "effort" to "none", "fast" to "true"),
        Seen("gpt-5.6-sol-xhigh", "gpt-5.6-sol", "effort" to "xhigh", "fast" to "false"),
        Seen("gpt-5.6-sol-xhigh-fast", "gpt-5.6-sol", "effort" to "xhigh", "fast" to "true"),
        Seen("gpt-5.6-terra-high", "gpt-5.6-terra", "effort" to "high", "fast" to "false"),
        Seen("gpt-5.6-terra-high-fast", "gpt-5.6-terra", "effort" to "high", "fast" to "true"),
        Seen("gpt-5.6-terra-low", "gpt-5.6-terra", "effort" to "low", "fast" to "false"),
        Seen("gpt-5.6-terra-low-fast", "gpt-5.6-terra", "effort" to "low", "fast" to "true"),
        Seen("gpt-5.6-terra-max", "gpt-5.6-terra", "effort" to "max", "fast" to "false"),
        Seen("gpt-5.6-terra-max-fast", "gpt-5.6-terra", "effort" to "max", "fast" to "true"),
        Seen("gpt-5.6-terra-medium", "gpt-5.6-terra", "effort" to "medium", "fast" to "false"),
        Seen("gpt-5.6-terra-medium-fast", "gpt-5.6-terra", "effort" to "medium", "fast" to "true"),
        Seen("gpt-5.6-terra-none", "gpt-5.6-terra", "effort" to "none", "fast" to "false"),
        Seen("gpt-5.6-terra-none-fast", "gpt-5.6-terra", "effort" to "none", "fast" to "true"),
        Seen("gpt-5.6-terra-xhigh", "gpt-5.6-terra", "effort" to "xhigh", "fast" to "false"),
        Seen("gpt-5.6-terra-xhigh-fast", "gpt-5.6-terra", "effort" to "xhigh", "fast" to "true"),
        Seen("grok-4.7-high", "grok-4.7", "effort" to "high", "fast" to "false"),
        Seen("grok-4.7-high-fast", "grok-4.7", "effort" to "high", "fast" to "true"),
        Seen("grok-4.7-low", "grok-4.7", "effort" to "low", "fast" to "false"),
        Seen("grok-4.7-low-fast", "grok-4.7", "effort" to "low", "fast" to "true"),
        Seen("grok-4.7-medium", "grok-4.7", "effort" to "medium", "fast" to "false"),
        Seen("grok-4.7-medium-fast", "grok-4.7", "effort" to "medium", "fast" to "true"),
        Seen("grok-4.7-xhigh", "grok-4.7", "effort" to "xhigh", "fast" to "false"),
        Seen("grok-4.7-xhigh-fast", "grok-4.7", "effort" to "xhigh", "fast" to "true"),
        Seen("muse-spark-1.3-high", "muse-spark-1.3", "effort" to "high"),
        Seen("muse-spark-1.3-low", "muse-spark-1.3", "effort" to "low"),
        Seen("muse-spark-1.3-max", "muse-spark-1.3", "effort" to "max"),
        Seen("muse-spark-1.3-medium", "muse-spark-1.3", "effort" to "medium"),
        Seen("muse-spark-1.3-minimal", "muse-spark-1.3", "effort" to "minimal"),
        Seen("muse-spark-1.3-xhigh", "muse-spark-1.3", "effort" to "xhigh"),
        Seen("composer-2.5-fast", "composer-2.5", "fast" to "true"),
        // The bare id: the variant Cursor picks for `composer-2.5` without parameters, Fast on.
        Seen("composer-2.5", "composer-2.5", "fast" to "true"),
    )

    @Test
    fun `every slug seen from Cursor resolves to its model and the parameters it spells`() {
        for (row in seen) {
            val model = LiveModelCatalog.model(row.modelId)
            val expected = pickedByHand(model, row.params)
            assertWithMessage(row.slug).that(expected.params.toSet()).isEqualTo(row.params.toSet())
            assertWithMessage(row.slug).that(ModelSlugs.resolve(catalog, row.slug)).isEqualTo(expected)
            // As a Project worker's record names it: the chip reads the catalogue's name, never the slug.
            val current = ModelResolution.forChat(agent(AccountModel(row.slug)), catalog)
            assertWithMessage(row.slug).that(current.choice).isEqualTo(expected)
            assertWithMessage(row.slug).that(current.label).isEqualTo(model.displayName)
        }
        assertThat(seen.size).isEqualTo(81)
    }

    @Test
    fun `Bennett's claude-opus-5-5-max-fast is Claude Opus 5-5 at effort Max with Fast on`() {
        val opus = LiveModelCatalog.model("claude-opus-5.5")
        val choice = ModelSlugs.resolve(catalog, "claude-opus-5-5-max-fast")!!
        assertThat(choice.model).isEqualTo(opus)
        assertThat(choice.label).isEqualTo("Claude Opus 5.5")
        assertThat(choice.variant!!.param("effort")).isEqualTo("max")
        assertThat(choice.variant!!.param("fast")).isEqualTo("true")
        // Exactly the picker's state after choosing Opus 5.5, Max and Fast by hand.
        val byHand = opus.variantWith(opus.variantWith(opus.defaultVariant, "effort", "max"), "fast", "true")
        assertThat(choice).isEqualTo(ModelChoice(opus, byHand))
        // Other spellings of the same thing.
        for (spelling in listOf("claude-opus-5.5-max-fast", "Claude-Opus-5.5-MAX-Fast", "claude_opus_5_5_max_fast", "claude-opus-5-5-fast-max", "claude-opus-5.5[effort=max,fast=true]")) {
            assertWithMessage(spelling).that(ModelSlugs.resolve(catalog, spelling)).isEqualTo(choice)
        }
        // A catalogue that spells the id with dashes reads it the same way.
        val dashed = catalog.map { if (it.id == "claude-opus-5.5") it.copy(id = "claude-opus-5-5") else it }
        assertThat(ModelSlugs.resolve(dashed, "claude-opus-5-5-max-fast")?.params).isEqualTo(choice.params)
        assertThat(ModelSlugs.resolve(dashed, "claude-opus-5.5-max-fast")?.model?.id).isEqualTo("claude-opus-5-5")
    }

    // -- the grammar ----------------------------------------------------------------------------------------------

    @Test
    fun `a switch the slug leaves out is off, a choice it leaves out keeps the model's default`() {
        // Cursor Grok 4.6 defaults to Fast on: a slug without `-fast` is the slow variant (the desktop's `fgm`).
        val grok = ModelSlugs.resolve(catalog, "cursor-grok-4-6-low")!!
        assertThat(grok.params.toSet()).isEqualTo(setOf(ModelParam("effort", "low"), ModelParam("fast", "false")))
        // Fable's context is not spelled: its default, 1M.
        val fable = ModelSlugs.resolve(catalog, "claude-fable-5-1-thinking-low")!!
        assertThat(fable.params.toSet()).isEqualTo(setOf(ModelParam("context", "1m"), ModelParam("effort", "low")))
        // A context window spelled is set, in its value's or its display name's words.
        assertThat(ModelSlugs.resolve(catalog, "claude-fable-5-1-thinking-300k-low")!!.variant!!.param("context")).isEqualTo("300k")
        assertThat(ModelSlugs.resolve(catalog, "claude-fable-5-1-thinking-300K-low")!!.variant!!.param("context")).isEqualTo("300k")
        // A true/false `thinking` parameter is on when spelled and off when not.
        assertThat(ModelSlugs.resolve(catalog, "claude-sonnet-5-thinking-high")!!.variant!!.param("thinking")).isEqualTo("true")
        assertThat(ModelSlugs.resolve(catalog, "claude-sonnet-5-high")!!.variant!!.param("thinking")).isEqualTo("false")
    }

    @Test
    fun `xhigh and extra-high are one value, whichever the catalogue uses`() {
        for (slug in listOf("gpt-5-3-codex-xhigh", "gpt-5.3-codex-extra-high")) {
            assertWithMessage(slug).that(ModelSlugs.resolve(catalog, slug)?.variant?.param("reasoning")).isEqualTo("extra-high")
        }
        assertThat(ModelSlugs.resolve(catalog, "claude-opus-5-5-extra-high")?.variant?.param("effort")).isEqualTo("xhigh")
    }

    @Test
    fun `the longest name wins, and a word that names no parameter never lands on a shorter model`() {
        assertThat(ModelSlugs.resolve(catalog, "gpt-5.6-sol-high")?.model?.id).isEqualTo("gpt-5.6-sol")
        assertThat(ModelSlugs.resolve(catalog, "gpt-5.6-high")?.model?.id).isEqualTo("gpt-5.6")
        assertThat(ModelSlugs.resolve(catalog, "claude-opus-5-thinking-max")?.model?.id).isEqualTo("claude-opus-5-thinking")
        // `luna` is no parameter of GPT-5.6 nor anything else: a different model, not GPT-5.6 High.
        assertThat(ModelSlugs.resolve(catalog, "gpt-5.6-luna-high")).isNull()
        // Nor is `5` Opus 5.5's version.
        assertThat(ModelSlugs.resolve(catalog.filterNot { it.id == "claude-opus-5-thinking" }, "claude-opus-5-max")).isNull()
    }

    @Test
    fun `aliases name the model under a slug too, and legacy word orders find it`() {
        assertThat(ModelSlugs.resolve(catalog, "composer-fast")).isEqualTo(ModelChoice(LiveModelCatalog.model("composer-2.5"), LiveModelCatalog.model("composer-2.5").defaultVariant))
        assertThat(ModelSlugs.resolve(catalog, "composer-latest")?.model?.id).isEqualTo("composer-2.5")
        assertThat(ModelSlugs.resolve(catalog, "auto")?.model?.id).isEqualTo("auto-smart")
        assertThat(ModelSlugs.resolve(catalog, AccountModel.AUTO_ID)?.model?.id).isEqualTo("auto-smart")
        // The docs' vendor-first id, in today's order.
        assertThat(ModelSlugs.resolve(catalog, "claude-sonnet-4-6-thinking")?.model?.id).isEqualTo("claude-4.6-sonnet-thinking")
    }

    @Test
    fun `a parameter word the model has no use for is set aside rather than failing the slug`() {
        // Gemini 3.8 Flash has no speeds: `-fast` does not make it another model.
        assertThat(ModelSlugs.resolve(catalog, "gemini-3.8-flash-high-fast")?.params).isEqualTo(listOf(ModelParam("effort", "high")))
        // Nor does `-thinking` on a model that always thinks.
        assertThat(ModelSlugs.resolve(catalog, "claude-opus-5-5-thinking-max")?.params?.toSet()).isEqualTo(setOf(ModelParam("effort", "max"), ModelParam("fast", "false")))
    }

    @Test
    fun `a record's own parameters outrank the ones its id spells`() {
        val choice = catalog.choiceFor(AccountModel("claude-opus-5-5-max-fast", listOf(ModelParam("effort", "low"))))!!
        assertThat(choice.params.toSet()).isEqualTo(setOf(ModelParam("effort", "low"), ModelParam("fast", "true")))
        val canonical = ModelSlugs.resolve(catalog, "claude-opus-5.5[effort=max,fast=true]", listOf(ModelParam("fast", "false")))!!
        assertThat(canonical.params.toSet()).isEqualTo(setOf(ModelParam("effort", "max"), ModelParam("fast", "false")))
    }

    @Test
    fun `an id or alias the catalogue lists resolves exactly as before`() {
        // The nearest variant to the record's parameters, a stale one included; nothing a record resolved to moves.
        val opus = LiveModelCatalog.model("claude-opus-5.5")
        assertThat(catalog.choiceFor("claude-opus-5.5", listOf(ModelParam("effort", "ultra"), ModelParam("fast", "true"))))
            .isEqualTo(ModelChoice(opus, opus.variantNearest(listOf(ModelParam("effort", "ultra"), ModelParam("fast", "true")))))
        assertThat(catalog.choiceFor(AccountModel("composer", listOf(ModelParam("fast", "false"))))?.variant?.param("fast")).isEqualTo("false")
    }

    // -- slugs no catalogue lists ---------------------------------------------------------------------------------

    private class Unknown(val slug: String, val name: String, val qualifier: String?)

    private val unknown = listOf(
        Unknown("claude-opus-6-max-fast", "Claude Opus 6", "Max · Fast"),
        Unknown("gpt-5.7-sol-xhigh-fast", "GPT 5.7 Sol", "Extra High · Fast"),
        Unknown("gpt-5.6-luna-high", "GPT 5.6 Luna", "High"),
        Unknown("claude-9-preview", "Claude 9 Preview", null),
        Unknown("mystery-model", "Mystery Model", null),
        Unknown("kimi-k3-thinking", "Kimi K3", "Thinking"),
        Unknown("unknown-model[effort=max,fast=true]", "Unknown Model", "Max effort · Fast"),
    )

    @Test
    fun `a slug no catalogue lists shows its best readable name, keeps its raw id, and is never blank`() {
        for (row in unknown) {
            assertWithMessage(row.slug).that(ModelSlugs.resolve(catalog, row.slug)).isNull()
            assertWithMessage(row.slug).that(ModelSlugs.readableName(catalog, row.slug)).isEqualTo(row.name)
            assertWithMessage(row.slug).that(ModelSlugs.readableQualifier(catalog, row.slug)).isEqualTo(row.qualifier)
            val current = ModelResolution.forChat(agent(AccountModel(row.slug)), catalog)
            assertWithMessage(row.slug).that(current.choice).isNull()
            assertWithMessage(row.slug).that(current.label).isEqualTo(row.name)
            // The picker's current-model row spells out what the id says and the id the chat keeps running on.
            assertWithMessage(row.slug).that(current.detail).isEqualTo(listOfNotNull(row.qualifier, row.slug).joinToString(" · "))
        }
        assertThat(ModelSlugs.readableName(catalog, "")).isEqualTo("Auto")
        assertThat(ModelSlugs.readableName(catalog, "default")).isEqualTo("Auto")
        assertThat(ModelSlugs.readableName(catalog, "max")).isEqualTo("Max")
    }

    @Test
    fun `before the catalogue answers a slug still reads as a name`() {
        assertThat(ModelSlugs.resolve(emptyList(), "claude-opus-5-5-max-fast")).isNull()
        assertThat(ModelSlugs.readableName(emptyList(), "claude-opus-5-5-max-fast")).isEqualTo("Claude Opus 5.5")
        assertThat(ModelSlugs.readableName(emptyList(), "gpt-5.6-sol-none-fast")).isEqualTo("GPT 5.6 Sol")
        assertThat(ModelSlugs.readableName(emptyList(), "muse-spark-1.3-minimal")).isEqualTo("Muse Spark 1.3")
        assertThat(AccountModel("claude-opus-5-5-max-fast").fallbackLabel).isEqualTo("Claude Opus 5.5")
        assertThat(ModelResolution.forChat(agent(AccountModel("claude-opus-5-5-max-fast")), emptyList()).label).isEqualTo("Claude Opus 5.5")
    }

    private fun agent(model: AccountModel) = Agent(
        id = "bc-worker",
        name = "Worker",
        lifecycle = AgentLifecycle.IDLE,
        runStatus = RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/bc-worker",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_001L,
        latestRunId = null,
        repoUrl = null,
        startingRef = null,
        accountModel = model,
        parent = AgentParent("bc-project", AgentParentKind.PROJECT_WORKER),
    )
}
