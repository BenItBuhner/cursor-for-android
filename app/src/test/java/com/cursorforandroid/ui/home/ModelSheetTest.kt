package com.cursorforandroid.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelParameter
import com.cursorforandroid.domain.ModelParameterValue
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ModelSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The documented `GET /v1/models` example: one model, two variants, both named "Composer 2". */
    private val composer = ModelOption(
        id = "composer-2",
        displayName = "Composer 2",
        parameters = listOf(
            ModelParameter("fast", "Fast", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Fast"))),
        ),
        variants = listOf(
            ModelVariant(displayName = "Composer 2", params = listOf(ModelParam("fast", "true")), isDefault = true),
            ModelVariant(displayName = "Composer 2", params = listOf(ModelParam("fast", "false")), isDefault = false),
        ),
    )
    private val sonnet = ModelOption(
        id = "claude-4.6-sonnet-thinking",
        displayName = "Claude 4.6 Sonnet",
        variants = listOf(ModelVariant(displayName = "Claude 4.6 Sonnet", params = emptyList(), isDefault = true)),
    )

    /** The live catalogue's effort × fast grid: four variants, every one named after the model. */
    private val grok = ModelOption(
        id = "cursor-grok-4.6",
        displayName = "Cursor Grok 4.6",
        parameters = listOf(
            ModelParameter("effort", "Effort", listOf(ModelParameterValue("low", "Low"), ModelParameterValue("high", "High"))),
            ModelParameter("fast", "Fast", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Fast"))),
        ),
        variants = listOf("low", "high").flatMap { effort ->
            listOf("true", "false").map { fast ->
                ModelVariant("Cursor Grok 4.6", listOf(ModelParam("effort", effort), ModelParam("fast", fast)), isDefault = effort == "high" && fast == "true")
            }
        },
    )

    private fun ModelOption.variant(vararg params: Pair<String, String>): ModelVariant = variantWithParams(params.toMap())!!

    private var picked: Pair<ModelOption?, ModelVariant?>? = null
    private var dismissed = false
    private var pinned: List<String> = emptyList()

    private fun show(
        models: List<ModelOption>,
        selected: ModelOption? = models.firstOrNull(),
        loading: Boolean = false,
        unavailable: Boolean = false,
        onRetry: () -> Unit = {},
        onAutoCreatePr: ((Boolean) -> Unit)? = {},
        pinnedIds: List<String> = emptyList(),
        noModelRow: NoModelRow? = null,
    ) {
        compose.setContent {
            // The host applies what the sheet reports; mirror that so the sheet re-renders against the new selection.
            var selectedModel by remember { mutableStateOf(selected) }
            var selectedVariant by remember { mutableStateOf(selected?.defaultVariant) }
            var pins by remember { mutableStateOf(pinnedIds) }
            CursorTheme(mode = ThemeMode.Dark) {
                ModelSheet(
                    models = models,
                    selectedModel = selectedModel,
                    selectedVariant = selectedVariant,
                    planMode = false,
                    autoCreatePr = false,
                    loading = loading,
                    unavailable = unavailable,
                    onPlanMode = {},
                    onAutoCreatePr = onAutoCreatePr,
                    onRetry = onRetry,
                    onSelect = { model, variant ->
                        picked = model to variant
                        selectedModel = model
                        selectedVariant = variant
                    },
                    onDismiss = { dismissed = true },
                    pinnedIds = pins,
                    onTogglePin = { id ->
                        pins = if (id in pins) pins - id else listOf(id) + pins
                        pinned = pins
                    },
                    noModelRow = noModelRow,
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Model")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertAbsent(text: String) = assertThat(compose.onAllNodes(hasText(text)).fetchSemanticsNodes()).isEmpty()

    /** The "Fast" picker row under Composer 2 — not a model subtitle, which the list no longer shows. */
    private val fastPicker get() = compose.onNode(hasText("Fast") and !hasText("Composer 2"))

    /**
     * The API identifies a variant only by `id`+`params` and reuses the model's display name for each one. Listing
     * every variant as a row of its own showed "Composer 2" twice (and a model with an effort × fast grid eight
     * times); the parameters are pickers under the selected model instead, and the row itself is just the name.
     */
    @Test
    fun `each model is one row, and the selected model's parameters unfold as pickers`() {
        show(listOf(composer, sonnet))
        compose.onAllNodesWithText("Composer 2").assertCountEquals(1)
        compose.onAllNodesWithText("Fast off").assertCountEquals(0)
        compose.onAllNodesWithText("High effort").assertCountEquals(0)
        fastPicker.assertIsDisplayed()
        compose.onNodeWithText("Claude 4.6 Sonnet").assertIsDisplayed()
    }

    @Test
    fun `the list never shows variant state on the model rows`() {
        show(listOf(composer, grok), selected = grok)
        assertAbsent("Fast off")
        assertAbsent("High effort")
        assertAbsent("Low effort · Fast")
        assertAbsent("1M context")
        compose.onNodeWithText("Cursor Grok 4.6").assertIsDisplayed()
        compose.onNodeWithText("High").assertIsSelected()
    }

    @Test
    fun `flipping a toggle reports the model with the matching variant and keeps the sheet open`() {
        show(listOf(composer))
        fastPicker.performClick()
        compose.waitForIdle()
        assertThat(picked).isEqualTo(composer to composer.variant("fast" to "false"))
        assertThat(dismissed).isFalse()
        // The row stays a clean name; the toggle is what shows the new value.
        assertAbsent("Fast off")
        compose.onNodeWithText("Composer 2").assertIsDisplayed()
    }

    @Test
    fun `tapping a model row selects it at its default variant, lifts it, and keeps the sheet open`() {
        show(listOf(composer, sonnet, grok))
        compose.onNodeWithText("Cursor Grok 4.6").performClick()
        compose.waitForIdle()
        assertThat(picked).isEqualTo(grok to grok.defaultVariant)
        assertThat(dismissed).isFalse()
        compose.onNodeWithText("High").assertIsSelected()
        compose.onNodeWithText("Low").assertIsDisplayed()
        // The selected model is now first among the model names.
        val grokTop = compose.onNodeWithText("Cursor Grok 4.6").fetchSemanticsNode().boundsInRoot.top
        val composerTop = compose.onNodeWithText("Composer 2").fetchSemanticsNode().boundsInRoot.top
        val sonnetTop = compose.onNodeWithText("Claude 4.6 Sonnet").fetchSemanticsNode().boundsInRoot.top
        assertThat(grokTop).isLessThan(composerTop)
        assertThat(grokTop).isLessThan(sonnetTop)
    }

    @Test
    fun `another model's row unfolds its pickers by selecting it, and a choice there keeps that model`() {
        show(listOf(composer, grok))
        compose.onAllNodesWithText("Low").assertCountEquals(0)
        compose.onNodeWithText("Cursor Grok 4.6").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("High").assertIsSelected()
        compose.onNodeWithText("Low").performClick()
        compose.waitForIdle()
        // Effort changed, the default's fast stayed: the closest variant with the value asked for.
        assertThat(picked).isEqualTo(grok to grok.variant("effort" to "low", "fast" to "true"))
        assertThat(dismissed).isFalse()
        compose.onNodeWithText("Low").assertIsSelected()
        assertAbsent("Low effort · Fast")
    }

    @Test
    fun `a model whose variants leave nothing to choose has no pickers of its own`() {
        show(listOf(sonnet, composer), selected = null)
        compose.onAllNodes(hasContentDescription("Show Claude 4.6 Sonnet options")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Show Composer 2 options")).assertCountEquals(0)
        compose.onNodeWithContentDescription("Pin Claude 4.6 Sonnet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pin Composer 2").assertIsDisplayed()
        compose.onAllNodes(hasText("Fast") and !hasText("Composer 2")).assertCountEquals(0)
    }

    @Test
    fun `pinning a model reports it and an already-pinned model can be unpinned`() {
        show(listOf(composer, grok), pinnedIds = listOf(composer.id))
        compose.onNodeWithContentDescription("Unpin Composer 2").performClick()
        compose.waitForIdle()
        assertThat(pinned).isEmpty()
        compose.onNodeWithContentDescription("Pin Cursor Grok 4.6").performClick()
        compose.waitForIdle()
        assertThat(pinned).containsExactly(grok.id)
        compose.onNodeWithContentDescription("Unpin Cursor Grok 4.6").assertIsDisplayed()
    }

    @Test
    fun `a pinned model that is not selected still sits under the selection`() {
        show(listOf(composer, sonnet, grok), selected = composer, pinnedIds = listOf(grok.id))
        val composerTop = compose.onNodeWithText("Composer 2").fetchSemanticsNode().boundsInRoot.top
        val grokTop = compose.onNodeWithText("Cursor Grok 4.6").fetchSemanticsNode().boundsInRoot.top
        val sonnetTop = compose.onNodeWithText("Claude 4.6 Sonnet").fetchSemanticsNode().boundsInRoot.top
        assertThat(composerTop).isLessThan(grokTop)
        assertThat(grokTop).isLessThan(sonnetTop)
    }

    @Test
    fun `a failed catalogue load offers a retry instead of loading forever`() {
        var retries = 0
        show(emptyList(), unavailable = true, onRetry = { retries++ })
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertThat(retries).isEqualTo(1)
        assertThat(compose.onAllNodes(hasText("Loading models", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertAbsent("Default still works")
    }

    @Test
    fun `an empty catalogue shows the loading row while the request is in flight`() {
        show(emptyList(), loading = true)
        compose.onNodeWithText("Loading models…").assertIsDisplayed()
    }

    @Test
    fun `the picker has no Default row, and both agent options are still offered`() {
        show(listOf(sonnet))
        assertAbsent("Default")
        assertAbsent("Your Cursor default model")
        compose.onNodeWithText("Plan mode").assertIsDisplayed()
        compose.onNodeWithText("Auto-create PR").assertIsDisplayed()
    }

    /** On a follow-up the row stands for the chat's current model, and picking it reports no model at all. */
    @Test
    fun `the no-model row reads as the caller says and still reports no model`() {
        show(listOf(sonnet), noModelRow = NoModelRow("Current model", "Keep the model this chat has been using"))
        compose.onNodeWithText("Keep the model this chat has been using").assertIsDisplayed()
        assertAbsent("Default")
        compose.onNodeWithText("Current model").performClick()
        compose.waitUntil(10_000) { dismissed }
        assertThat(picked).isEqualTo(null to null)
    }

    /** A follow-up cannot change an agent's auto-PR setting, and a chat whose model the catalog lists needs no extra row. */
    @Test
    fun `without an auto-PR handler or a no-model row neither is shown`() {
        show(listOf(sonnet), onAutoCreatePr = null, noModelRow = null)
        compose.onNodeWithText("Plan mode").assertIsDisplayed()
        compose.onNodeWithText("Claude 4.6 Sonnet").assertIsDisplayed()
        assertAbsent("Auto-create PR")
        assertAbsent("Default")
        assertAbsent("Current model")
    }
}
