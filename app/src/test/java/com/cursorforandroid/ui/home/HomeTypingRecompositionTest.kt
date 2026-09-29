package com.cursorforandroid.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.RecomposeCounter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Typing in the New Chat composer recomposes the composer alone: the cards under it skip. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class HomeTypingRecompositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
        }
        RecomposeCounter.install()
    }

    @After
    fun tearDown() {
        RecomposeCounter.uninstall()
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun typeInto(home: NewChatHome, projectsAvailable: Boolean, text: String) {
        val list = NewChatHomeFixtures.list()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                HomeScreen(
                    graph = graph,
                    listState = list,
                    onOpenSidebar = {},
                    onOpenAgent = {},
                    onLaunchOpen = {},
                    rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                    home = home,
                    projectsAvailable = projectsAvailable,
                    onNewProject = {},
                    onOpenSettings = {},
                    onReorderProjects = {},
                )
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(NewChatHomeFixtures.NEWEST_CHAT)).fetchSemanticsNodes().isNotEmpty() }
        val field = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))).onFirst()
        field.performClick()
        compose.waitForIdle()
        RecomposeCounter.reset()
        text.forEach { c ->
            field.performTextInput(c.toString())
            compose.waitForIdle()
        }
        assertThat(RecomposeCounter.count("ComposerBox")).isAtLeast(text.length)
    }

    @Test
    fun `typing leaves the recent chats' cards alone`() {
        typeInto(NewChatHome.RECENT, projectsAvailable = true, text = "hello")
        assertThat(RecomposeCounter.count("HomeBlockView")).isEqualTo(0)
        assertThat(RecomposeCounter.count("RecentChatRow")).isEqualTo(0)
    }

    @Test
    fun `typing leaves the Projects note and the chats under it alone`() {
        typeInto(NewChatHome.PROJECTS, projectsAvailable = false, text = "hello")
        assertThat(RecomposeCounter.count("HomeBlockView")).isEqualTo(0)
        assertThat(RecomposeCounter.count("RecentChatRow")).isEqualTo(0)
        assertThat(RecomposeCounter.count("ProjectsNoteCard")).isEqualTo(0)
    }
}
