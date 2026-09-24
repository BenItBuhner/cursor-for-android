package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.dto.ModelListItemDto
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelParameterDefinitionDto
import com.cursorforandroid.data.api.dto.ModelParameterValueDto
import com.cursorforandroid.data.api.dto.ModelVariantDto
import com.cursorforandroid.data.repo.toModel
import com.cursorforandroid.domain.ModelOption

/**
 * A `GET /v1/models` answer shaped like the live one, with a model behind every slug family Cursor has been seen
 * writing (the Project coordinators' worker models: `claude-opus-5-5-max-fast`, `gpt-5.6-sol-none-fast`,
 * `muse-spark-1.3-minimal`, …): each model's `variants` enumerate every parameter combination it accepts and its
 * `parameters` name them, as the documented shape has it (`ModelListItem`, `ModelParameterDefinition`,
 * `ModelVariant`). Two shapes from the desktop's catalog ride along: a `reasoning` scale ending in `extra-high`, and
 * a vendor-first id (`claude-4.6-sonnet-thinking`, the docs' example) without parameters. `gpt-5.6` sits beside
 * `gpt-5.6-sol` so a slug has to find the longer name.
 */
object LiveModelCatalog {

    private val EFFORTS = listOf("low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra High", "max" to "Max")
    private val FAST = ModelParameterDefinitionDto("fast", "Fast", listOf(ModelParameterValueDto("false"), ModelParameterValueDto("true", "Fast")))

    private fun effort(values: List<Pair<String, String>>, id: String = "effort", name: String = "Effort") =
        ModelParameterDefinitionDto(id, name, values.map { (v, n) -> ModelParameterValueDto(v, n) })

    /** Every combination of [parameters]' values as a variant named [name], [default] the one Cursor picks for a bare id. */
    private fun grid(id: String, name: String, parameters: List<ModelParameterDefinitionDto>, default: Map<String, String>, aliases: List<String>? = null): ModelListItemDto {
        val combinations = parameters.fold(listOf(emptyList<ModelParamDto>())) { acc, p -> acc.flatMap { prefix -> p.values.map { prefix + ModelParamDto(p.id, it.value) } } }
        return ModelListItemDto(
            id = id,
            displayName = name,
            aliases = aliases,
            parameters = parameters,
            variants = combinations.map { params -> ModelVariantDto(params = params, displayName = name, isDefault = params.associate { it.id to it.value } == default) },
        )
    }

    val items: List<ModelListItemDto> = listOf(
        ModelListItemDto(id = "auto-smart", displayName = "Auto", description = "Cursor Router picks the model.", aliases = listOf("auto")),
        grid("claude-opus-5.5", "Claude Opus 5.5", listOf(effort(EFFORTS), FAST), mapOf("effort" to "high", "fast" to "false")),
        grid("claude-opus-5-thinking", "Claude Opus 5", listOf(effort(EFFORTS), FAST), mapOf("effort" to "high", "fast" to "false")),
        grid(
            "claude-fable-5.1-thinking", "Claude Fable 5.1",
            listOf(ModelParameterDefinitionDto("context", "Context", listOf(ModelParameterValueDto("300k", "300K"), ModelParameterValueDto("1m", "1M"))), effort(EFFORTS)),
            mapOf("context" to "1m", "effort" to "max"),
        ),
        grid(
            "claude-sonnet-5", "Claude Sonnet 5",
            listOf(ModelParameterDefinitionDto("thinking", "Thinking", listOf(ModelParameterValueDto("false"), ModelParameterValueDto("true", "Thinking"))), effort(EFFORTS)),
            mapOf("thinking" to "true", "effort" to "high"),
        ),
        grid("composer-2.5", "Composer 2.5", listOf(FAST), mapOf("fast" to "true"), aliases = listOf("composer-latest", "composer")),
        grid("cursor-grok-4.6", "Cursor Grok 4.6", listOf(effort(EFFORTS.dropLast(1)), FAST), mapOf("effort" to "high", "fast" to "true")),
        grid("grok-4.7", "Grok 4.7", listOf(effort(EFFORTS.dropLast(1)), FAST), mapOf("effort" to "medium", "fast" to "false")),
        grid("gemini-3.8-flash", "Gemini 3.8 Flash", listOf(effort(EFFORTS.take(3))), mapOf("effort" to "medium")),
        grid("gpt-5.6", "GPT-5.6", listOf(effort(EFFORTS.take(3))), mapOf("effort" to "high")),
        grid("gpt-5.6-sol", "GPT-5.6 Sol", listOf(effort(listOf("none" to "None") + EFFORTS), FAST), mapOf("effort" to "medium", "fast" to "false")),
        grid("gpt-5.6-terra", "GPT-5.6 Terra", listOf(effort(listOf("none" to "None") + EFFORTS), FAST), mapOf("effort" to "medium", "fast" to "false")),
        grid("muse-spark-1.3", "Muse Spark 1.3", listOf(effort(listOf("minimal" to "Minimal") + EFFORTS)), mapOf("effort" to "medium")),
        grid(
            "gpt-5.3-codex", "GPT-5.3 Codex",
            listOf(effort(listOf("low" to "Low", "medium" to "Medium", "high" to "High", "extra-high" to "Extra High"), id = "reasoning", name = "Reasoning")),
            mapOf("reasoning" to "medium"),
        ),
        ModelListItemDto(
            id = "claude-4.6-sonnet-thinking",
            displayName = "Claude 4.6 Sonnet (Thinking)",
            variants = listOf(ModelVariantDto(params = emptyList(), displayName = "Claude 4.6 Sonnet (Thinking)", isDefault = true)),
        ),
    )

    /** [items] as the app holds them once `GET /v1/models` has answered. */
    val models: List<ModelOption> get() = items.map { it.toModel() }

    fun model(id: String): ModelOption = models.first { it.id == id }
}
