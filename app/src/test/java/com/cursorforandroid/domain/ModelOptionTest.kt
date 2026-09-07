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

    @Test
    fun `labelFor appends the qualifier only when sibling variants share the name`() {
        assertThat(composer.labelFor(fast)).isEqualTo("Composer 2 · Fast")
        assertThat(composer.labelFor(slow)).isEqualTo("Composer 2 · Fast off")
        assertThat(composer.labelFor(null)).isEqualTo("Composer 2")

        val max = ModelVariant("Claude 1M Max", listOf(ModelParam("context", "1m")), isDefault = true)
        val plain = ModelVariant("Claude", listOf(ModelParam("effort", "high")), isDefault = false)
        val claude = ModelOption(id = "claude", displayName = "Claude", variants = listOf(max, plain))
        assertThat(claude.labelFor(max)).isEqualTo("Claude 1M Max")
        assertThat(claude.labelFor(plain)).isEqualTo("Claude")
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
    fun `defaultVariant prefers the API default and falls back to the first`() {
        assertThat(composer.defaultVariant).isEqualTo(fast)
        val none = ModelOption(id = "m", displayName = "M", variants = listOf(slow.copy(isDefault = false), fast.copy(isDefault = false)))
        assertThat(none.defaultVariant).isEqualTo(slow.copy(isDefault = false))
        assertThat(ModelOption(id = "m", displayName = "M").defaultVariant).isNull()
    }
}
