package com.cursorforandroid.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › New chat page: the three layouts side by side as radio buttons, on a phone as on a tablet, Recent agents
 * chosen until another is tapped, the tap written at once; the miniatures say nothing of their own; and, with no
 * Projects to pin, the note of what the Projects layout shows meanwhile.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NewChatHomePickerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
    }

    private fun composeSettings(isDemo: Boolean, list: AgentListUiState = AgentListUiState()) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SettingsScreen(graph, USER, isDemo = isDemo, onOpenSidebar = null, onBack = {}, newChatList = list)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.RECENT) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun option(home: NewChatHome) = compose.onNodeWithTag(NewChatHomePickerTags.of(home))

    private fun saved(): NewChatHome = runBlocking { graph.prefs.newChatHome.first() }

    @Test
    fun `the three layouts sit side by side as radio buttons, each read as its name in full`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        threeSideBySide()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun `on a tablet the three still share one row`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        threeSideBySide()
    }

    /** One row, in the order they are offered, none cut short: each option's name fits its third. */
    private fun threeSideBySide() {
        val nodes = NewChatHome.entries.map { option(it).performScrollTo().fetchSemanticsNode() }
        nodes.zipWithNext { left, right ->
            assertThat(right.positionInRoot.y).isEqualTo(left.positionInRoot.y)
            assertThat(right.positionInRoot.x).isGreaterThan(left.positionInRoot.x + left.size.width - 1)
        }
        for (home in NewChatHome.entries) {
            val label = NewChatHomePickerCopy.label(home)
            option(home)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(label))))
            val text = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(NewChatHomePickerTags.of(home))), useUnmergedTree = true).fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>().also { text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.single()
            assertWithMessage(label).that(layout.getLineEnd(0)).isEqualTo(label.length)
            assertWithMessage(label).that(layout.isLineEllipsized(0)).isFalse()
        }
    }

    @Test
    fun `Recent agents is chosen until another is tapped, and each tap is written at once`() {
        composeSettings(isDemo = true)
        assertThat(saved()).isEqualTo(NewChatHome.RECENT)
        option(NewChatHome.RECENT).assertIsSelected()
        option(NewChatHome.PROJECTS).assertIsNotSelected()
        option(NewChatHome.COMPOSER).assertIsNotSelected()

        option(NewChatHome.COMPOSER).performScrollTo().performClick()
        compose.waitUntil(10_000) { saved() == NewChatHome.COMPOSER }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.COMPOSER) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        option(NewChatHome.RECENT).assertIsNotSelected()
        option(NewChatHome.PROJECTS).assertIsNotSelected()

        option(NewChatHome.PROJECTS).performScrollTo().performClick()
        compose.waitUntil(10_000) { saved() == NewChatHome.PROJECTS }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.PROJECTS) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        option(NewChatHome.RECENT).assertIsNotSelected()

        option(NewChatHome.COMPOSER).assertIsNotSelected()

        option(NewChatHome.RECENT).performClick()
        compose.waitUntil(10_000) { saved() == NewChatHome.RECENT }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.RECENT) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        option(NewChatHome.PROJECTS).assertIsNotSelected()
    }

    /**
     * A tap on the picture itself — where a miniature draws a recent chat's row or a Project's shortcut, each a
     * control on the real page — chooses the layout, rather than being taken by the row drawn there.
     */
    @Test
    fun `a tap anywhere on a miniature chooses its layout, over the rows drawn in it`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        option(NewChatHome.PROJECTS).performScrollTo().performTouchInput { click(Offset(centerX, height * 0.6f)) }
        compose.waitUntil(10_000) { saved() == NewChatHome.PROJECTS }
        option(NewChatHome.RECENT).performTouchInput { click(Offset(centerX, height * 0.6f)) }
        compose.waitUntil(10_000) { saved() == NewChatHome.RECENT }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.RECENT) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the miniatures are the page, yet nothing in them is read out or can be reached`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        compose.onAllNodes(hasText(NewChatHomeCopy.PLACEHOLDER, substring = true)).assertCountEquals(0)
        // The miniature's composer is a real text field; Settings has none of its own.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun `without Extended mode Projects can still be chosen, and the card says what it shows until the mode is on`() {
        composeSettings(isDemo = false)
        compose.onNodeWithTag(NewChatHomePickerTags.PROJECTS).performScrollTo()
        compose.onAllNodes(hasText(NewChatHomePickerCopy.NEEDS_MODE)).assertCountEquals(1)
        option(NewChatHome.PROJECTS).performClick()
        compose.waitUntil(10_000) { saved() == NewChatHome.PROJECTS }
    }

    @Test
    fun `with Projects to pin there is no note`() {
        composeSettings(isDemo = true)
        compose.onAllNodes(hasText(NewChatHomePickerCopy.NEEDS_MODE)).assertCountEquals(0)
    }

    private companion object {
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)
    }
}
