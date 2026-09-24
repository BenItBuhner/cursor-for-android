package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.LiveModelCatalog
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The `/` popover's models for what follows the slash: fuzzy on names, ids and version segments, placed through [ModelSlugs]. */
class ModelSearchTest {

    private val catalog = LiveModelCatalog.models

    private fun ids(query: String, current: ModelChoice? = null) = ModelSearch.search(catalog, query, current).map { it.model.id }

    private fun params(choice: ModelChoice) = choice.params.associate { it.id to it.value }

    @Test
    fun `words match the words of a display name in any case, the whole word ahead of a version it begins`() {
        assertThat(ids("Opus 5")).containsExactly("claude-opus-5-thinking", "claude-opus-5.5").inOrder()
        assertThat(ids("opus")).containsExactly("claude-opus-5.5", "claude-opus-5-thinking").inOrder()
        assertThat(ids("5 opus")).containsExactly("claude-opus-5-thinking", "claude-opus-5.5").inOrder()
        assertThat(ids("sonnet 5")).containsExactly("claude-sonnet-5")
        assertThat(ids("Grok")).containsExactly("grok-4.7", "cursor-grok-4.6").inOrder()
    }

    @Test
    fun `a version segment on its own, dashed or dotted, finds the models carrying it`() {
        assertThat(ids("5.5")).containsExactly("claude-opus-5.5")
        assertThat(ids("5-5")).containsExactly("claude-opus-5.5")
        assertThat(ids("4.6")).containsExactly("cursor-grok-4.6", "claude-4.6-sonnet-thinking")
        assertThat(ids("gpt-5.6")).containsExactly("gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra").inOrder()
        assertThat(ids("GPT 5.6 sol")).containsExactly("gpt-5.6-sol")
    }

    @Test
    fun `ids and aliases name their model, and the name typed without spaces or dots still finds it`() {
        assertThat(ids("composer-latest")).containsExactly("composer-2.5")
        assertThat(ids("cursor-grok")).containsExactly("cursor-grok-4.6")
        assertThat(ids("opus55")).containsExactly("claude-opus-5.5")
        assertThat(ids("claude-opus-5-thinking")).containsExactly("claude-opus-5-thinking")
    }

    @Test
    fun `parameter words after the name pick the variant they spell, through the normaliser, and a half-typed one waits`() {
        val maxFast = ModelSearch.search(catalog, "opus 5.5 max fast").single()
        assertThat(maxFast.model.id).isEqualTo("claude-opus-5.5")
        assertThat(params(maxFast)).containsExactly("effort", "max", "fast", "true")

        val low = ModelSearch.search(catalog, "sol low").single()
        assertThat(low.model.id).isEqualTo("gpt-5.6-sol")
        assertThat(params(low)).containsExactly("effort", "low", "fast", "false")

        // "ma" is Max being typed: the model stays listed at its default until the word is whole.
        val typing = ModelSearch.search(catalog, "opus 5.5 ma").single()
        assertThat(params(typing)).containsExactly("effort", "high", "fast", "false")
    }

    @Test
    fun `a word that names nothing on a model leaves it out, and Auto is never listed`() {
        assertThat(ids("opus rewrite")).isEmpty()
        assertThat(ids("goal fix")).isEmpty()
        assertThat(ids("auto")).isEmpty()
        assertThat(ids("")).doesNotContain("auto-smart")
    }

    @Test
    fun `with nothing typed every model is listed, the one in use first and at its own variant`() {
        val sol = LiveModelCatalog.model("gpt-5.6-sol")
        val current = ModelChoice(sol, sol.variants.first { v -> v.params.any { it.id == "effort" && it.value == "xhigh" } })
        val listed = ModelSearch.search(catalog, "", current)
        assertThat(listed.map { it.model.id }).hasSize(catalog.size - 1)
        assertThat(listed.first().model.id).isEqualTo("gpt-5.6-sol")
        assertThat(params(listed.first())).containsExactly("effort", "xhigh", "fast", "false")
        assertThat(params(listed.first { it.model.id == "claude-opus-5.5" })).containsExactly("effort", "high", "fast", "false")
        // Found by a query, the model in use keeps its variant too.
        assertThat(params(ModelSearch.search(catalog, "sol", current).single())).containsExactly("effort", "xhigh", "fast", "false")
    }
}
