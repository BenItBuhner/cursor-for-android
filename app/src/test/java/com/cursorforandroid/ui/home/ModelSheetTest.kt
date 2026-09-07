package com.cursorforandroid.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    private fun show(
        models: List<ModelOption>,
        loading: Boolean = false,
        unavailable: Boolean = false,
        onSelect: (ModelOption?, ModelVariant?) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ModelSheet(
                    models = models,
                    selectedModel = models.firstOrNull(),
                    selectedVariant = models.firstOrNull()?.variants?.firstOrNull(),
                    planMode = false,
                    autoCreatePr = false,
                    loading = loading,
                    unavailable = unavailable,
                    onPlanMode = {},
                    onAutoCreatePr = {},
                    onRetry = onRetry,
                    onSelect = onSelect,
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Model")).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * The API identifies a variant only by `id`+`params` and reuses the model's display name for each one, so a
     * list keyed on names aborted the composition ("Key composer-2:Composer 2 was already used") every time the
     * picker opened against the live catalogue.
     */
    @Test
    fun `variants sharing a display name render and are told apart by their parameters`() {
        show(listOf(composer, sonnet))
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Composer 2")).fetchSemanticsNodes().size >= 3 }
        compose.onNodeWithText("Fast").assertIsDisplayed()
        compose.onNodeWithText("Fast off").assertIsDisplayed()
        compose.onNodeWithText("Claude 4.6 Sonnet (Thinking)").assertIsDisplayed()
    }

    @Test
    fun `picking a variant reports the model with that exact variant`() {
        var picked: Pair<ModelOption?, ModelVariant?>? = null
        show(listOf(composer), onSelect = { m, v -> picked = m to v })
        compose.onNodeWithText("Fast off").performClick()
        compose.waitForIdle()
        assertThat(picked).isEqualTo(composer to composer.variants[1])
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
}
