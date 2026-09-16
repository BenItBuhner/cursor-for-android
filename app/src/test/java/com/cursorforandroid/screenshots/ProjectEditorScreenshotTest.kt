package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.Repository
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
 * The Project editor: the Create Project sheet with a name typed, a tone and an icon chosen and two repositories
 * picked, and the same sheet editing an existing Project, whose repositories are shown as set. Written to
 * `screenshots/` beside the walkthrough; CI compares them pixel for pixel.
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
    private fun Scene(content: @Composable () -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private val repositories = listOf(
        Repository("https://github.com/acme/billing"),
        Repository("https://github.com/acme/web"),
        Repository("https://github.com/acme/infra"),
        Repository("https://github.com/acme/mobile"),
    )

    @Test
    fun createProjectSheet() {
        compose.setContent {
            Scene {
                ProjectEditorSheet(
                    target = ProjectEditorTarget.Create,
                    initialName = "",
                    initialAppearance = null,
                    repositories = repositories,
                    ownedRepoUrls = emptyList(),
                    repositoriesLoading = false,
                    busy = false,
                    error = null,
                    onRefreshRepositories = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Create Project").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New Project").performTextInput("Billing launch")
        compose.onNodeWithContentDescription("Colour Purple").performClick()
        compose.onNodeWithTag("project-icon").performClick()
        compose.onNodeWithText("Search ${com.cursorforandroid.ui.icons.ProjectIcons.ids.size} icons").performTextInput("rocket")
        compose.onNodeWithContentDescription("Icon Rocket").performClick()
        compose.onNodeWithTag("project-editor-list").performScrollToNode(hasText("web"))
        compose.onNodeWithText("web").performClick()
        compose.onNodeWithTag("project-editor-list").performScrollToNode(hasText("billing"))
        compose.onNodeWithText("billing").performClick()
        compose.onNodeWithTag("project-editor-list").performScrollToNode(hasText("Billing launch"))
        capture("76_project_create_sheet")
    }

    @Test
    fun editProjectSheet() {
        compose.setContent {
            Scene {
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Edit Project").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cursor has no way to change a Project's repositories once it is created.").assertExists()
        capture("77_project_edit_sheet")
    }
}
