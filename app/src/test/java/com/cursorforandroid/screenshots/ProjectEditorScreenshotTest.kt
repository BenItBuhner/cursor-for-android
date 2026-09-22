package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.projects.ProjectEditorSheet
import com.cursorforandroid.ui.projects.ProjectEditorTarget
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Project editor in both themes: the New Project sheet as it opens, with a name but no repository (Create off,
 * the reason beside it), and ready, a colour and an icon from the full catalog picked and two repositories confirmed
 * in the picker (itself a frame); and the same sheet editing an existing Project, whose repositories are fixed.
 * Written to `screenshots/` beside the walkthrough; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectEditorScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Scene(mode: ThemeMode, content: @Composable () -> Unit) {
        CursorTheme(mode = mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) { content() }
            }
        }
    }

    private val repositories = listOf(
        Repository("https://github.com/acme/billing"),
        Repository("https://github.com/acme/web"),
        Repository("https://github.com/acme/infra"),
        Repository("https://github.com/acme/mobile"),
    )

    private val auto = ModelOption("default", "Auto")
    private val sonnet = ModelOption("claude-4.5-sonnet", "Claude 4.5 Sonnet", variants = listOf(ModelVariant("Default", emptyList(), isDefault = true), ModelVariant("High effort", listOf(ModelParam("effort", "high")), isDefault = false)))
    private val models = listOf(auto, sonnet, ModelOption("gpt-5.6", "GPT-5.6"))

    private fun showCreate(mode: ThemeMode) {
        compose.setContent {
            Scene(mode) {
                ProjectEditorSheet(
                    target = ProjectEditorTarget.Create,
                    initialName = "",
                    // The look the host draws at random, fixed here so the frame is.
                    initialAppearance = ProjectAppearance("code", "blue"),
                    repositories = repositories,
                    ownedRepoUrls = emptyList(),
                    repositoriesLoading = false,
                    busy = false,
                    error = null,
                    onRefreshRepositories = {},
                    onConfirm = {},
                    onDismiss = {},
                    models = models,
                    // The composer's default: the model this device last launched with, as the row opens on it.
                    defaultModel = ModelChoice(sonnet, sonnet.variants.last()),
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("New Project").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitGone(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    /** The sheet as it opens, then with a name but no repository, then ready; [picker] also takes the repository picker. */
    private fun createFlow(mode: ThemeMode, empty: String, needsRepository: String, ready: String, picker: String? = null) {
        showCreate(mode)
        capture(empty)

        compose.onNodeWithContentDescription("Project name").performTextInput("Billing launch")
        waitFor("Choose a repository to continue")
        capture(needsRepository)

        compose.onNodeWithContentDescription("Colour Purple").performScrollTo().performClick()
        compose.onNodeWithTag("browse-icons").performScrollTo().performClick()
        waitFor("All icons")
        compose.onNodeWithText("Search ${ProjectIcons.ids.size} icons").performTextInput("rocket")
        compose.onNodeWithContentDescription("Icon Rocket").performClick()
        waitGone("All icons")

        compose.onNodeWithText("Choose repositories").performScrollTo().performClick()
        waitFor("Search repositories")
        compose.onNodeWithText("web").performClick()
        compose.onNodeWithText("billing").performClick()
        waitFor("2 selected")
        picker?.let(::capture)
        compose.onNodeWithText("Done").performClick()
        waitGone("Search repositories")
        compose.onNodeWithContentDescription("Project name").performScrollTo()
        capture(ready)
    }

    @Test
    fun createProjectSheetDark() = createFlow(ThemeMode.Dark, "147_project_create_empty", "148_project_create_needs_repository", "76_project_create_sheet", picker = "149_project_repository_picker")

    @Test
    fun createProjectSheetLight() = createFlow(ThemeMode.Light, "150_project_create_empty_light", "151_project_create_needs_repository_light", "152_project_create_sheet_light")

    @Test
    fun iconCatalog() {
        showCreate(ThemeMode.Dark)
        compose.onNodeWithTag("browse-icons").performScrollTo().performClick()
        waitFor("All icons")
        capture("153_project_icon_catalog")
    }

    private fun edit(mode: ThemeMode, name: String) {
        compose.setContent {
            Scene(mode) {
                ProjectEditorSheet(
                    target = ProjectEditorTarget.Edit("bc-cesium"),
                    initialName = "Cesium billing launch",
                    initialAppearance = ProjectAppearance("rocket", "brand"),
                    repositories = repositories,
                    ownedRepoUrls = listOf("https://github.com/acme/billing"),
                    repositoriesLoading = false,
                    busy = false,
                    error = null,
                    onRefreshRepositories = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        waitFor("Edit Project")
        compose.onNodeWithText("Repositories can't be changed after a Project is created.").assertExists()
        capture(name)
    }

    @Test
    fun editProjectSheetDark() = edit(ThemeMode.Dark, "77_project_edit_sheet")

    @Test
    fun editProjectSheetLight() = edit(ThemeMode.Light, "154_project_edit_sheet_light")
}
