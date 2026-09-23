package com.cursorforandroid.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › New chat page: the two layouts side by side as radio buttons, Recent agents chosen until Projects is
 * tapped, the tap written at once; the miniatures say nothing of their own; and, with no Projects to pin, the note
 * of what the Projects layout shows meanwhile.
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
    fun `both layouts sit side by side as radio buttons, each read as its name`() {
        composeSettings(isDemo = true)
        val recent = option(NewChatHome.RECENT).performScrollTo().fetchSemanticsNode()
        val projects = option(NewChatHome.PROJECTS).fetchSemanticsNode()
        assertThat(projects.positionInRoot.y).isEqualTo(recent.positionInRoot.y)
        assertThat(projects.positionInRoot.x).isGreaterThan(recent.positionInRoot.x + recent.size.width - 1)
        for ((home, label) in listOf(NewChatHome.RECENT to NewChatHomePickerCopy.RECENT, NewChatHome.PROJECTS to NewChatHomePickerCopy.PROJECTS)) {
            option(home)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(label))))
        }
    }

    @Test
    fun `Recent agents is chosen until Projects is tapped, and each tap is written at once`() {
        composeSettings(isDemo = true)
        assertThat(saved()).isEqualTo(NewChatHome.RECENT)
        option(NewChatHome.RECENT).assertIsSelected()
        option(NewChatHome.PROJECTS).assertIsNotSelected()

        option(NewChatHome.PROJECTS).performScrollTo().performClick()
        compose.waitUntil(10_000) { saved() == NewChatHome.PROJECTS }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.PROJECTS) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        option(NewChatHome.RECENT).assertIsNotSelected()

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
