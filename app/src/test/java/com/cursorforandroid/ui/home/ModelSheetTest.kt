package com.cursorforandroid.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
        displayName = "Claude 4.6 Sonnet (Thinking)",
        variants = listOf(ModelVariant(displayName = "Claude 4.6 Sonnet (Thinking)", params = emptyList(), isDefault = true)),
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

    private fun show(
        models: List<ModelOption>,
        selected: ModelOption? = models.firstOrNull(),
        loading: Boolean = false,
        unavailable: Boolean = false,
        onRetry: () -> Unit = {},
        onAutoCreatePr: ((Boolean) -> Unit)? = {},
        noModelRow: NoModelRow? = NoModelRow.Default,
        referenceLine: Boolean = false,
    ) {
        compose.setContent {
            // The host applies what the sheet reports; mirror that so the sheet re-renders against the new selection.
            var selectedModel by remember { mutableStateOf(selected) }
            var selectedVariant by remember { mutableStateOf(selected?.defaultVariant) }
            CursorTheme(mode = ThemeMode.Dark) {
                // One unconstrained line of a chip's own style: what its label asks for at this font scale.
                if (referenceLine) {
                    Text("Reference line", style = CursorTheme.typography.base, maxLines = 1, modifier = Modifier.testTag("line"))
                }
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
                    noModelRow = noModelRow,
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Model")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertAbsent(text: String) = assertThat(compose.onAllNodes(hasText(text)).fetchSemanticsNodes()).isEmpty()

    /** The "Fast" picker row under Composer 2 — not the model row, whose subtitle also reads "Fast". */
    private val fastPicker get() = compose.onNode(hasText("Fast") and !hasText("Composer 2"))

    /**
     * The API identifies a variant only by `id`+`params` and reuses the model's display name for each one. Listing
     * every variant as a row of its own showed "Composer 2" twice (and a model with an effort × fast grid eight
     * times); the parameters are pickers under the model instead.
     */
    @Test
    fun `each model is one row, and the selected model's parameters unfold as pickers`() {
        show(listOf(composer, sonnet))
        compose.onAllNodesWithText("Composer 2").assertCountEquals(1)
        compose.onAllNodesWithText("Fast off").assertCountEquals(0)
        fastPicker.assertIsDisplayed()
        compose.onNodeWithText("Claude 4.6 Sonnet (Thinking)").assertIsDisplayed()
    }

    @Test
    fun `flipping a toggle reports the model with the matching variant and keeps the sheet open`() {
        show(listOf(composer))
        fastPicker.performClick()
        compose.waitForIdle()
        assertThat(picked).isEqualTo(composer to composer.variant("fast" to "false"))
        assertThat(dismissed).isFalse()
        // The row now describes the variant in force.
        compose.onNodeWithText("Fast off").assertIsDisplayed()
    }

    @Test
    fun `tapping a model row selects it at its default variant and closes the sheet`() {
        show(listOf(composer, sonnet))
        compose.onNodeWithText("Claude 4.6 Sonnet (Thinking)").performClick()
        compose.waitUntil(10_000) { dismissed }
        assertThat(picked).isEqualTo(sonnet to sonnet.variants.single())
    }

    @Test
    fun `another model's chevron unfolds its pickers, and a choice there selects that model`() {
        show(listOf(composer, grok))
        compose.onAllNodesWithText("Low").assertCountEquals(0)
        compose.onNodeWithContentDescription("Show Cursor Grok 4.6 options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("High").assertIsSelected()
        compose.onNodeWithText("Low").performClick()
        compose.waitForIdle()
        // Effort changed, the default's fast stayed: the closest variant with the value asked for.
        assertThat(picked).isEqualTo(grok to grok.variant("effort" to "low", "fast" to "true"))
        assertThat(dismissed).isFalse()
        compose.onNodeWithText("Low").assertIsSelected()
        compose.onNodeWithText("Low effort · Fast").assertIsDisplayed()
    }

    @Test
    fun `a model whose variants leave nothing to choose has no chevron`() {
        show(listOf(sonnet, composer), selected = null)
        compose.onAllNodes(hasContentDescription("Show Claude 4.6 Sonnet (Thinking) options")).assertCountEquals(0)
        compose.onNodeWithContentDescription("Show Composer 2 options").assertIsDisplayed()
        // Nothing is selected, so nothing is unfolded either.
        compose.onAllNodes(hasText("Fast") and !hasText("Composer 2")).assertCountEquals(0)
    }

    @Test
    fun `a failed catalogue load offers a retry instead of loading forever`() {
        var retries = 0
        show(emptyList(), unavailable = true, onRetry = { retries++ })
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertThat(retries).isEqualTo(1)
        assertThat(compose.onAllNodes(hasText("Loading models", substring = true)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `an empty catalogue shows the loading row while the request is in flight`() {
        show(emptyList(), loading = true)
        compose.onNodeWithText("Loading models…").assertIsDisplayed()
    }

    @Test
    fun `on a new chat the no-model row is Cursor's default and both options are offered`() {
        show(listOf(sonnet))
        compose.onNodeWithText("Default").assertIsDisplayed()
        compose.onNodeWithText("Your Cursor default model").assertIsDisplayed()
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
        compose.onNodeWithText("Claude 4.6 Sonnet (Thinking)").assertIsDisplayed()
        assertAbsent("Auto-create PR")
        assertAbsent("Default")
        assertAbsent("Current model")
    }

    private fun chipHeight(): Float =
        compose.onNodeWithText("High").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    private fun lineHeight(): Float =
        compose.onNodeWithTag("line").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    /**
     * A chip is a 28dp tap target around an sp label, and that label's line outgrows 28dp at the system's largest
     * font — so 28dp has to be the chip's minimum rather than its height, or the value is cut off. The scale is set
     * on the configuration because the sheet's content lives in a dialog window of its own, which a locally provided
     * density would not reach; the line is measured rather than assumed so the chip is held to its own content.
     */
    @Test
    @Config(fontScale = 2f)
    fun `a parameter chip grows at the largest system font`() {
        show(listOf(grok), referenceLine = true)
        assertThat(lineHeight()).isGreaterThan(28f)
        assertThat(chipHeight()).isWithin(0.5f).of(lineHeight())
    }

    @Test
    fun `a parameter chip keeps its designed height at the default font`() {
        show(listOf(grok), referenceLine = true)
        assertThat(lineHeight()).isLessThan(28f)
        assertThat(chipHeight()).isWithin(1f).of(28f)
    }
}
