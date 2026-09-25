package com.cursorforandroid.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The New Chat pane under each New chat page setting: Projects pinned as shortcuts that open their Project, the notes
 * in their place with the one action each offers, the recent chats as before, and a change of setting laid out at once.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NewChatHomeScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private val opened = mutableListOf<String>()
    private var newProject = 0
    private var openSettings = 0
    private var home by mutableStateOf<NewChatHome?>(NewChatHome.RECENT)

    @Before
    fun setUp() {
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
        }
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun show(initial: NewChatHome, list: AgentListUiState = NewChatHomeFixtures.list(), projectsAvailable: Boolean = true) {
        home = initial
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                HomeScreen(
                    graph = graph,
                    listState = list,
                    onOpenSidebar = {},
                    onOpenAgent = { row: AgentRow -> opened += row.agent.id },
                    onLaunchOpen = {},
                    rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                    home = home,
                    projectsAvailable = projectsAvailable,
                    onNewProject = { newProject++ },
                    onOpenSettings = { openSettings++ },
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun shortcuts() = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes().size

    @Test
    fun `Projects pins every Project with what is working in it, and a tap opens that Project`() {
        show(NewChatHome.PROJECTS)
        compose.waitUntil(10_000) { shortcuts() == 5 }
        listOf(NewChatHomeFixtures.BILLING_NAME, NewChatHomeFixtures.SHIPYARD_NAME, "Design system", "Cursor for Android", NewChatHomeFixtures.PIPELINE_NAME)
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNode(hasText(NewChatHomeFixtures.BILLING_NAME) and hasContentDescription("1 agent working")).assertExists()
        compose.onNode(hasText(NewChatHomeFixtures.SHIPYARD_NAME) and hasContentDescription("2 agents working")).assertExists()
        listOf("working", "chats", "Idle").forEach { compose.onAllNodes(hasText(it, substring = true)).assertCountEquals(0) }
        // The account's own chats are the other layout's.
        compose.onAllNodes(hasText(NewChatHomeFixtures.NEWEST_CHAT)).assertCountEquals(0)

        compose.onNodeWithText(NewChatHomeFixtures.BILLING_NAME).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(opened).containsExactly(NewChatHomeFixtures.BILLING)
    }

    @Test
    fun `Recent lists the recent chats as it always has, and no shortcut`() {
        show(NewChatHome.RECENT)
        compose.waitUntil(10_000) { onScreen(NewChatHomeFixtures.NEWEST_CHAT) }
        assertThat(shortcuts()).isEqualTo(0)
        compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECTS_NOTE)).assertCountEquals(0)
    }

    @Test
    fun `with Extended mode off the Projects layout says so, offers Settings, and keeps the recent chats below`() {
        show(NewChatHome.PROJECTS, projectsAvailable = false)
        compose.waitUntil(10_000) { onScreen(NewChatHomeCopy.NEEDS_EXTENDED_TITLE) }
        assertThat(shortcuts()).isEqualTo(0)
        compose.onNodeWithText(NewChatHomeFixtures.NEWEST_CHAT).assertExists()
        compose.onNodeWithText(NewChatHomeCopy.OPEN_SETTINGS).performClick()
        compose.waitForIdle()
        assertThat(openSettings).isEqualTo(1)
    }

    @Test
    fun `with no Projects yet the Projects layout offers a first one over the recent chats`() {
        show(NewChatHome.PROJECTS, list = NewChatHomeFixtures.withoutProjects())
        compose.waitUntil(10_000) { onScreen(NewChatHomeCopy.NO_PROJECTS_TITLE) }
        compose.onNodeWithText(NewChatHomeFixtures.NEWEST_CHAT).assertExists()
        compose.onNodeWithText(NewChatHomeCopy.NEW_PROJECT).performClick()
        compose.waitForIdle()
        assertThat(newProject).isEqualTo(1)
    }

    @Test
    fun `a change of setting lays the pane out again at once`() {
        show(NewChatHome.RECENT)
        compose.waitUntil(10_000) { onScreen(NewChatHomeFixtures.NEWEST_CHAT) }
        home = NewChatHome.PROJECTS
        compose.waitUntil(10_000) { shortcuts() == 5 && !onScreen(NewChatHomeFixtures.NEWEST_CHAT) }
        home = NewChatHome.RECENT
        compose.waitUntil(10_000) { shortcuts() == 0 && onScreen(NewChatHomeFixtures.NEWEST_CHAT) }
    }

    @Test
    fun `nothing is listed while the setting is still being read`() {
        show(NewChatHome.RECENT)
        home = null
        compose.waitForIdle()
        assertThat(onScreen(NewChatHomeFixtures.NEWEST_CHAT)).isFalse()
        assertThat(shortcuts()).isEqualTo(0)
    }
}
