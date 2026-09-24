package com.cursorforandroid.ui.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Project editor sheet: the desktop's Create Project dialog on a phone as four steps — the name, the
 * repositories (at least one, confirmed in their own picker), the icon and colour with a preview of the sidebar row,
 * the model — and Create held back, with the reason beside it, until the first two are set. The same sheet edits an
 * existing Project, whose repositories are listed but cannot be changed.
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

    private val auto = ModelOption("default", "Auto")
    private val sonnet = ModelOption("claude-4.5-sonnet", "Claude 4.5 Sonnet", variants = listOf(ModelVariant("Default", emptyList(), isDefault = true), ModelVariant("High effort", listOf(ModelParam("effort", "high")), isDefault = false)))
    private val gpt = ModelOption("gpt-5.6", "GPT-5.6")
    private val catalog = listOf(auto, sonnet, gpt)
    private val suggested = ProjectAppearance("code", "blue")

    private fun show(
        target: ProjectEditorTarget,
        name: String = "",
        appearance: ProjectAppearance? = if (target is ProjectEditorTarget.Create) suggested else null,
        owned: List<String> = emptyList(),
        busy: Boolean = false,
        error: String? = null,
        loading: Boolean = false,
        models: List<ModelOption> = emptyList(),
        defaultModel: ModelChoice? = null,
    ) {
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
                    models = models,
                    defaultModel = defaultModel,
                )
            }
        }
        waitFor(if (target is ProjectEditorTarget.Create) "New Project" else "Edit Project")
    }

    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitGone(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun reason() = compose.onNodeWithTag("project-create-reason")

    private fun create() = compose.onNode(hasText("Create") and hasClickAction())

    /** A repository's row in the picker, not the step's list of confirmed ones behind it. */
    private fun choice(name: String) = compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("repo-picker-list")))

    private fun choices(name: String) = compose.onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag("repo-picker-list"))).fetchSemanticsNodes()

    /** Opens the repository picker, taps [names] in order and confirms them with Done. */
    private fun confirmRepositories(vararg names: String) {
        compose.onNode(hasText("Choose repositories") and hasClickAction()).performScrollTo().performClick()
        waitFor("Search repositories")
        names.forEach { choice(it).performClick() }
        compose.onNodeWithTag("repo-picker-done").performClick()
        waitGone("Search repositories")
    }

    @Test
    fun `Create waits for a name and a repository, and says which is missing`() {
        show(ProjectEditorTarget.Create)
        compose.onNodeWithText("One chat that plans the work and runs agents to do it.").assertIsDisplayed()
        create().assertIsNotEnabled()
        reason().assertTextEquals("Add a name and a repository")

        compose.onNodeWithContentDescription("Project name").performTextInput("Billing launch")
        create().assertIsNotEnabled()
        reason().assertTextEquals("Choose a repository to continue")

        compose.onNodeWithContentDescription("Project name").performTextClearance()
        confirmRepositories("web")
        create().assertIsNotEnabled()
        reason().assertTextEquals("Add a name to continue")

        // Blank is not a name.
        compose.onNodeWithContentDescription("Project name").performTextInput("   ")
        create().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Project name").performTextClearance()

        compose.onNodeWithContentDescription("Project name").performTextInput("Billing launch")
        create().assertIsEnabled()
        reason().assertTextEquals("")
        create().performClick()
        assertThat(results).containsExactly(ProjectEditorResult("Billing launch", suggested, listOf("https://github.com/acme/web")))
    }

    @Test
    fun `repositories are picked in their own sheet, and only Done confirms them`() {
        show(ProjectEditorTarget.Create, name = "Billing launch")
        compose.onNodeWithText("Required").assertIsDisplayed()

        // Picked, then cancelled: nothing is kept, and Create stays off.
        compose.onNode(hasText("Choose repositories") and hasClickAction()).performScrollTo().performClick()
        waitFor("Search repositories")
        compose.onNodeWithText("Pick at least one").assertIsDisplayed()
        compose.onNodeWithTag("repo-picker-done").assertIsNotEnabled()
        choice("infra").performClick()
        choice("infra").assertIsSelected()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag("repo-picker-done").assertIsEnabled()
        compose.onNodeWithTag("repo-picker-cancel").performClick()
        waitGone("Search repositories")
        create().assertIsNotEnabled()
        assertThat(compose.onAllNodesWithText("infra").fetchSemanticsNodes()).isEmpty()

        // Picked in order and confirmed: listed on the step, counted, and sent in that order.
        confirmRepositories("web", "billing")
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithText("web").assertIsDisplayed()
        compose.onNodeWithText("billing").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Required").fetchSemanticsNodes()).isEmpty()
        create().performClick()
        assertThat(results.single().repoUrls).containsExactly("https://github.com/acme/web", "https://github.com/acme/billing").inOrder()

        // Reopened, the picker starts on what was confirmed; the list searches, refreshes, and unpicks.
        compose.onNode(hasText("Change repositories") and hasClickAction()).performScrollTo().performClick()
        waitFor("Search repositories")
        choice("web").assertIsSelected()
        choice("infra").assertIsNotSelected()
        compose.onNodeWithContentDescription("Refresh repositories").performClick()
        assertThat(refreshes).isEqualTo(1)
        compose.onNode(hasSetTextAction() and hasText("Search repositories")).performTextInput("bill")
        choice("billing").performClick()
        assertThat(choices("web")).isEmpty()
        compose.onNode(hasSetTextAction() and hasText("bill")).performTextInput("ions")
        compose.onNodeWithText("No repositories match \u201Cbillions\u201D").assertIsDisplayed()
        compose.onNodeWithTag("repo-picker-done").performClick()
        waitGone("Search repositories")
        create().performClick()
        assertThat(results.last().repoUrls).containsExactly("https://github.com/acme/web")
    }

    @Test
    fun `the look opens on the suggested one, the preview follows each pick, and the catalog finds any icon`() {
        show(ProjectEditorTarget.Create, name = "Billing launch")
        compose.onNodeWithContentDescription("Preview: Code icon in Blue").assertExists()
        compose.onNodeWithContentDescription("Colour Blue").assertIsSelected()
        compose.onNodeWithContentDescription("Icon Code").assertIsSelected()

        compose.onNodeWithContentDescription("Colour Purple").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Colour Purple").assertIsSelected()
        compose.onNodeWithContentDescription("Icon Terminal").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Preview: Terminal icon in Purple").assertExists()

        // Not among the suggestions: found in the catalog, and then shown first among them, chosen.
        assertThat(compose.onAllNodes(hasContentDescription("Icon Rocket")).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag("browse-icons").performScrollTo().performClick()
        waitFor("All icons")
        compose.onNodeWithText("Search ${ProjectIcons.ids.size} icons").performTextInput("rocket")
        compose.onNodeWithContentDescription("Icon Rocket").performClick()
        waitGone("All icons")
        compose.onNodeWithContentDescription("Icon Rocket").assertIsSelected()
        compose.onNodeWithContentDescription("Preview: Rocket icon in Purple").assertExists()

        confirmRepositories("web")
        create().performClick()
        assertThat(results.single().appearance).isEqualTo(ProjectAppearance("rocket", "purple"))
    }

    @Test
    fun `an existing Project edits its name and look, and lists its repositories as fixed`() {
        show(ProjectEditorTarget.Edit("bc-p"), name = "Cesium billing", appearance = ProjectAppearance("flag", "green"), owned = listOf("https://github.com/acme/billing"))
        compose.onNodeWithText("Changes also show on desktop and on cursor.com.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview: Flag icon in Green").assertExists()
        compose.onNodeWithContentDescription("Colour Green").assertIsSelected()
        compose.onNodeWithText("Repositories can't be changed after a Project is created.").assertIsDisplayed()
        compose.onNodeWithText("billing").assertIsDisplayed()
        // No picker and no model on an existing Project: the name is the one text field.
        assertThat(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodes(hasText("Change repositories")).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("Model")).fetchSemanticsNodes()).isEmpty()

        val save = compose.onNode(hasText("Save") and hasClickAction())
        save.assertIsNotEnabled()
        reason().assertTextEquals("No changes yet")
        compose.onNodeWithContentDescription("Project name").performTextClearance()
        reason().assertTextEquals("Add a name to save")
        compose.onNodeWithContentDescription("Project name").performTextInput("Cesium billing v2")
        compose.onNodeWithContentDescription("Colour Brand").performScrollTo().performClick()
        save.assertIsEnabled()
        save.performClick()

        assertThat(results).containsExactly(ProjectEditorResult("Cesium billing v2", ProjectAppearance("flag", "brand"), listOf("https://github.com/acme/billing")))
    }

    @Test
    fun `a rename alone leaves the look as it is`() {
        show(ProjectEditorTarget.Edit("bc-p"), name = "Cesium billing", appearance = ProjectAppearance("flag", "green"), owned = listOf("https://github.com/acme/billing"))
        compose.onNodeWithContentDescription("Project name").performTextClearance()
        compose.onNodeWithContentDescription("Project name").performTextInput("Cesium billing v2")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        assertThat(results.single().appearance).isNull()
    }

    /**
     * The desktop dialog's Model picker, on a phone: the row opens on the composer's default (the model this device
     * last used, else the account's newest, else Auto), the composer's own picker changes it — without Plan mode or
     * auto-PR, which a coordinator has not — and the choice goes out with the Project, Auto as the account's `default`.
     */
    @Test
    fun `the Model row opens on the composer's default and the picker changes it, Auto included`() {
        show(ProjectEditorTarget.Create, name = "Billing launch", models = catalog, defaultModel = ModelChoice(sonnet, sonnet.variants.first()))
        confirmRepositories("web")
        compose.onNodeWithTag("project-model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Claude 4.5 Sonnet").assertIsDisplayed()

        compose.onNodeWithTag("project-model").performClick()
        waitFor("Models")
        assertThat(compose.onAllNodes(hasText("Plan mode")).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("Auto-create PR")).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("GPT-5.6").performClick()
        create().performClick()
        assertThat(results.last().model).isEqualTo(ModelChoice(gpt, null))
        assertThat(results.last().accountModel).isEqualTo(AccountModel("gpt-5.6"))

        // Auto is a row like any other, and goes out as the desktop's `default`.
        compose.onNodeWithTag("project-model").performClick()
        waitFor("Models")
        compose.onNodeWithText("Auto").performClick()
        create().performClick()
        assertThat(results.last().model?.model).isEqualTo(auto)
        assertThat(results.last().accountModel).isEqualTo(AccountModel("default"))
        assertThat(results.last().accountModel?.isAuto).isTrue()
    }

    @Test
    fun `with no model list the row says Auto and the Project is created with none named, which the account reads as Auto`() {
        show(ProjectEditorTarget.Create, name = "Billing launch")
        confirmRepositories("web")
        compose.onNodeWithTag("project-model").performScrollTo()
        compose.onNodeWithText("Auto").assertIsDisplayed()
        compose.onNodeWithText("Cursor picks the model for each task").assertIsDisplayed()
        create().performClick()
        assertThat(results.single().model).isNull()
        assertThat(results.single().accountModel).isNull()
    }

    @Test
    fun `a refusal is shown and the fields kept, in the account's words`() {
        show(ProjectEditorTarget.Edit("bc-p"), name = "Cesium billing", appearance = ProjectAppearance("flag", "green"), error = "Project creation is not available for this workspace.")
        compose.onNodeWithTag("project-editor-error").assertIsDisplayed()
        compose.onNodeWithText("Cursor didn't save the Project").assertIsDisplayed()
        compose.onNodeWithText("Project creation is not available for this workspace.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()

        // The account's refusal, whole, with what it sent besides the words.
        assertThat(refusalText(ConnectRpcException(400, "invalid_argument", "At least one model details is required"))).isEqualTo("At least one model details is required (Cursor: invalid_argument, HTTP 400)")
        assertThat(refusalText(ConnectRpcException(200, null, "Cursor started no Project."))).isEqualTo("Cursor started no Project.")
        assertThat(refusalText(IllegalStateException("This Project isn't loaded."))).isEqualTo("This Project isn't loaded.")
        assertThat(refusalText(RuntimeException())).isEqualTo("Cursor didn't answer.")
    }

    @Test
    fun `while busy the action gives way to the spinner`() {
        show(ProjectEditorTarget.Create, busy = true)
        assertThat(compose.onAllNodes(hasText("Create")).fetchSemanticsNodes()).isEmpty()
    }
}
