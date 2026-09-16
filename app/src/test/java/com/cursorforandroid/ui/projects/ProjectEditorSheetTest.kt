package com.cursorforandroid.ui.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Project editor sheet: the desktop's Create Project dialog on a phone — name, icon and colour, the repositories
 * the Project owns — and the same sheet for an existing Project, where the repositories are shown but not editable.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectEditorSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val results = mutableListOf<ProjectEditorResult>()
    private var refreshes = 0

    private val repos = listOf(Repository("https://github.com/acme/billing"), Repository("https://github.com/acme/web"), Repository("https://github.com/acme/infra"))

    private fun show(target: ProjectEditorTarget, name: String = "", appearance: ProjectAppearance? = null, owned: List<String> = emptyList(), busy: Boolean = false, error: String? = null, loading: Boolean = false) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ProjectEditorSheet(
                    target = target,
                    initialName = name,
                    initialAppearance = appearance,
                    repositories = repos,
                    ownedRepoUrls = owned,
                    repositoriesLoading = loading,
                    busy = busy,
                    error = error,
                    onRefreshRepositories = { refreshes++ },
                    onConfirm = { results += it },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(if (target is ProjectEditorTarget.Create) "Create Project" else "Edit Project").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun list() = compose.onNodeWithTag("project-editor-list")

    @Test
    fun `a new Project is named, given a look and repositories, and created with them`() {
        show(ProjectEditorTarget.Create)
        compose.onNodeWithText("Create a focused chat where Agents coordinate work").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose an icon").assertIsDisplayed()
        compose.onNodeWithText("Icon and colour: chosen for you unless you pick").assertIsDisplayed()
        // The catalog is closed until asked for, so the repositories are in reach.
        assertThat(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(2)

        compose.onNodeWithContentDescription("New Project").performTextInput("Billing launch")
        compose.onNodeWithContentDescription("Colour Purple").performClick()
        compose.onNodeWithContentDescription("Colour Purple").assertIsSelected()
        // The icon opens the catalog; the search narrows it; the pick closes it and shows in the header.
        compose.onNodeWithTag("project-icon").performClick()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("rocket")
        compose.onNodeWithContentDescription("Icon Rocket").performClick()
        compose.onNodeWithContentDescription("Chosen icon Rocket").assertIsDisplayed()
        compose.onNodeWithText("Rocket \u00B7 Purple").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasContentDescription("Icon Rocket")).fetchSemanticsNodes()).isEmpty()

        // Two repositories, the second picked first, so it is the primary one.
        list().performScrollToNode(hasText("web"))
        compose.onNodeWithText("web").performClick()
        list().performScrollToNode(hasText("billing"))
        compose.onNodeWithText("billing").performClick()
        list().performScrollToNode(hasText("2 chosen \u00B7 the first is the primary repository."))
        compose.onNodeWithText("2 chosen \u00B7 the first is the primary repository.").assertIsDisplayed()
        compose.onNodeWithText("Create").performClick()

        assertThat(results).containsExactly(ProjectEditorResult("Billing launch", ProjectAppearance("rocket", "purple"), listOf("https://github.com/acme/web", "https://github.com/acme/billing")))
    }

    @Test
    fun `nothing chosen creates a Project the desktop's way, named and styled by the account`() {
        show(ProjectEditorTarget.Create)
        list().performScrollToNode(hasText("Optional: the Project's agents work in the repositories it owns. With none, it starts in an empty cloud environment."))
        compose.onNodeWithText("Optional: the Project's agents work in the repositories it owns. With none, it starts in an empty cloud environment.").assertIsDisplayed()
        compose.onNodeWithText("Create").performClick()
        assertThat(results).containsExactly(ProjectEditorResult("", null, emptyList()))
    }

    @Test
    fun `the repository list filters, refreshes and unpicks`() {
        show(ProjectEditorTarget.Create)
        list().performScrollToNode(hasText("infra"))
        compose.onNodeWithText("infra").performClick()
        compose.onNodeWithContentDescription("Refresh repositories").performClick()
        assertThat(refreshes).isEqualTo(1)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("bill")
        compose.onNodeWithText("billing").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasText("web")).fetchSemanticsNodes()).isEmpty()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("ions")
        compose.onNodeWithText("No repositories match \u201Cbillions\u201D").assertIsDisplayed()
        compose.onNodeWithText("Create").performClick()
        assertThat(results.single().repoUrls).containsExactly("https://github.com/acme/infra")
    }

    @Test
    fun `an existing Project shows its name, look and repositories, edits the first two, and saves`() {
        show(ProjectEditorTarget.Edit("bc-p"), name = "Cesium billing", appearance = ProjectAppearance("flag", "green"), owned = listOf("https://github.com/acme/billing"))
        compose.onNodeWithText("The name and look show on desktop and cursor.com as well as here.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Chosen icon Flag").assertIsDisplayed()
        compose.onNodeWithContentDescription("Colour Green").assertIsSelected()
        compose.onNodeWithText("Repositories \u00B7 set when the Project was created").assertIsDisplayed()
        compose.onNodeWithText("billing").assertIsDisplayed()
        compose.onNodeWithText("Cursor has no way to change a Project's repositories once it is created.").assertIsDisplayed()
        // No picker for repositories on an existing Project, and the catalog closed: the name is the one text field.
        assertThat(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodes(hasContentDescription("Refresh repositories")).fetchSemanticsNodes()).isEmpty()

        compose.onNodeWithContentDescription("New Project").performTextClearance()
        compose.onNodeWithContentDescription("New Project").performTextInput("Cesium billing v2")
        compose.onNodeWithContentDescription("Colour Brand").performClick()
        compose.onNodeWithText("Save").performClick()

        assertThat(results).containsExactly(ProjectEditorResult("Cesium billing v2", ProjectAppearance("flag", "brand"), listOf("https://github.com/acme/billing")))
    }

    @Test
    fun `a refusal is shown and the fields kept`() {
        show(ProjectEditorTarget.Edit("bc-p"), name = "Cesium billing", appearance = ProjectAppearance("flag", "green"), error = "Project creation is not available for this workspace.")
        compose.onNodeWithTag("project-editor-error").assertIsDisplayed()
        compose.onNodeWithText("Project creation is not available for this workspace.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun `while busy the action gives way to the spinner`() {
        show(ProjectEditorTarget.Create, busy = true)
        assertThat(compose.onAllNodes(hasText("Create")).fetchSemanticsNodes()).isEmpty()
    }
}
