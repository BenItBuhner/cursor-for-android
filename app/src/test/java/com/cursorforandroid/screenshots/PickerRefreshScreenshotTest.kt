package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.home.ModelSheet
import com.cursorforandroid.ui.home.RepositorySheet
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The model picker's header with the repository picker's refresh control: the glyph at the end of the title row
 * (`339`), a spinner in its place while the list is fetched with the list shown kept (`340`), and the failed first
 * load, which points at it rather than offering a button of its own (`341`); the repository picker's header beside
 * them for the match (`342`). Written to `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PickerRefreshScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val models = LiveModelCatalog.models
    private val repositories = listOf("app", "billing", "infra", "web", "payments-service").map { Repository("https://github.com/acme/$it") }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) { content() }
            }
        }
    }

    @Composable
    private fun Models(loading: Boolean = false, unavailable: Boolean = false, shown: Boolean = true) {
        val list = if (shown) models else emptyList()
        val selected = list.firstOrNull { it.id == "claude-opus-5.5" } ?: list.firstOrNull()
        ModelSheet(
            models = list,
            selectedModel = selected,
            selectedVariant = selected?.defaultVariant,
            planMode = false,
            autoCreatePr = false,
            loading = loading,
            unavailable = unavailable,
            onPlanMode = {},
            onAutoCreatePr = {},
            onRefresh = {},
            onSelect = { _, _ -> },
            onDismiss = {},
        )
    }

    private fun waitForText(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun modelPickerHeader() {
        show { Models() }
        waitForText("Claude Opus 5.5")
        capture("339_model_picker_header")
    }

    @Test
    fun modelPickerHeaderRefreshing() {
        // The spinner turns for as long as the fetch is out: the frame is taken at a fixed point of its turn.
        compose.mainClock.autoAdvance = false
        show { Models(loading = true) }
        compose.mainClock.advanceTimeBy(2_000)
        waitForText("Claude Opus 5.5")
        captureScreenRoboImage(File(outDir, "340_model_picker_header_refreshing.png").path, RoborazziOptions())
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun modelPickerHeaderAfterAFailedLoad() {
        show { Models(unavailable = true, shown = false) }
        waitForText("Couldn't load the model list")
        capture("341_model_picker_header_unavailable")
    }

    @Test
    fun repositoryPickerHeader() {
        show {
            RepositorySheet(
                repos = repositories,
                recent = emptyList(),
                selected = repositories.first(),
                noRepo = false,
                loading = false,
                unavailable = false,
                onSelect = {},
                onRefresh = {},
                onDismiss = {},
            )
        }
        waitForText("payments-service")
        capture("342_repository_picker_header")
    }
}
