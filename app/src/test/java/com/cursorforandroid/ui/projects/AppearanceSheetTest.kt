package com.cursorforandroid.ui.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ProjectPalette
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The icon and colour picker: the whole catalog in sections, searched like the desktop, saved as one appearance. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AppearanceSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val picked = mutableListOf<ProjectAppearance>()

    private fun show(current: ProjectAppearance?) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppearanceSheet(current = current, onPick = { picked += it }, onDismiss = {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Icon and colour").fetchSemanticsNodes().isNotEmpty() }
    }

    private val search: SemanticsNodeInteraction get() = compose.onAllNodes(hasSetTextAction()).onFirst()

    private fun grid(): SemanticsNodeInteraction = compose.onNodeWithContentDescription("Icons")

    @Test
    fun `the sheet opens on the Project's current icon and colour, with every tone and every section`() {
        show(ProjectAppearance("logo-notion", "purple"))

        compose.onNodeWithContentDescription("Chosen icon").assertIsDisplayed()
        compose.onNodeWithText("Notion").assertIsDisplayed()
        compose.onNodeWithText("Purple").assertIsDisplayed()
        ProjectPalette.tones.forEach { compose.onNodeWithContentDescription("Colour ${it.label}").assertIsDisplayed() }
        compose.onNodeWithContentDescription("Colour Purple").assertIsSelected()
        compose.onNodeWithContentDescription("Colour Green").assertIsNotSelected()
        compose.onNodeWithText("Search ${ProjectIcons.ids.size} icons").assertIsDisplayed()

        // Every section is reachable, and the chosen icon's cell is marked in its own section.
        ProjectIcons.groups.forEach { group ->
            grid().performScrollToNode(hasText(group.label))
            compose.onNodeWithText(group.label).assertIsDisplayed()
        }
        grid().performScrollToNode(hasContentDescription("Icon Notion"))
        compose.onNodeWithContentDescription("Icon Notion").assertIsSelected()
        compose.onNodeWithContentDescription("Icon GitHub").assertIsNotSelected()
    }

    @Test
    fun `search narrows the grid to the matching sections and cells, and a miss says so`() {
        show(ProjectAppearance("rocket", "green"))

        search.performTextInput("notion")
        compose.onNodeWithContentDescription("Icon Notion").assertIsDisplayed()
        compose.onNodeWithText("Brands & tools").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasText("Arrows")).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasContentDescription("Icon Rocket")).fetchSemanticsNodes()).isEmpty()

        search.performTextInput(" nothing")
        compose.onNodeWithText("No icons match \u201Cnotion nothing\u201D").assertIsDisplayed()
    }

    @Test
    fun `picking a brand icon and a tone saves both as the account spells them`() {
        show(ProjectAppearance("rocket", "green"))

        search.performTextInput("slack")
        compose.onNodeWithContentDescription("Icon Slack").performClick()
        compose.onNodeWithContentDescription("Icon Slack").assertIsSelected()
        compose.onNodeWithText("Slack").assertIsDisplayed()
        compose.onNodeWithContentDescription("Colour Brand").performClick()
        compose.onNodeWithText("Brand").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()

        assertThat(picked).containsExactly(ProjectAppearance("logo-slack", "brand"))
    }

    @Test
    fun `an alias the account recorded reads and saves as its picker id`() {
        show(ProjectAppearance("mark-github", "cyan"))
        compose.onNodeWithText("GitHub").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertThat(picked).containsExactly(ProjectAppearance("github", "cyan"))
    }

    @Test
    fun `an icon this build cannot draw is kept as the desktop set it while the colour changes`() {
        show(ProjectAppearance("hologram-from-cursor-4", "orange"))
        compose.onNodeWithText("Orange \u00B7 as set on desktop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Colour Red").performClick()
        compose.onNodeWithText("Save").performClick()
        assertThat(picked).containsExactly(ProjectAppearance("hologram-from-cursor-4", "red"))
    }

    @Test
    fun `a Project without a look starts on the picker's defaults, lightning in the default tone`() {
        show(null)
        compose.onNodeWithText("Lightning").assertIsDisplayed()
        compose.onNodeWithContentDescription("Colour Default").assertIsSelected()
        compose.onNodeWithText("Save").performClick()
        assertThat(picked).containsExactly(ProjectAppearance("lightning", "default"))
    }

    @Test
    fun `cancel saves nothing`() {
        show(ProjectAppearance("rocket", "green"))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertThat(picked).isEmpty()
    }
}
