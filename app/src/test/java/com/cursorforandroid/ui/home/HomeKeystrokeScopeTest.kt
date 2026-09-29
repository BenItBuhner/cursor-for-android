package com.cursorforandroid.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.observe
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
import com.cursorforandroid.util.RecomposeScopes
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A keystroke in the New Chat composer recomposes the composer's item and nothing around it: not the pane's body, not
 * the list's layout, and not the wrapper of every visible item, which Compose re-runs whenever the list's content is
 * rebuilt even where the item's own composable then skips (see [HomeTypingRecompositionTest] for those).
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class HomeKeystrokeScopeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private val rec = RecomposeScopes()
    private var data: CompositionData? = null

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

    private fun typeInto(home: NewChatHome, projectsAvailable: Boolean) {
        val list = NewChatHomeFixtures.list()
        compose.setContent {
            val root = currentComposer.composition
            DisposableEffect(root) {
                val handle = root.observe(rec)
                onDispose { handle?.dispose() }
            }
            val d = currentComposer.compositionData
            SideEffect { data = d }
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
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(NewChatHomeCopy.PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val field = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))).onFirst()
        field.performClick()
        // The first keystrokes change more than the text (the placeholder goes, Send lights up); these are not measured.
        repeat(WARM_UP) { field.performTextInput("w"); compose.waitForIdle() }
        rec.observeAll(data!!)
        rec.reset()
        repeat(KEYSTROKES) { i -> field.performTextInput(if (i % 6 == 5) " " else "a"); compose.waitForIdle() }
        println("HomeKeystrokeScopeTest [$home] scopes/keystroke=${"%.1f".format(rec.scopes / KEYSTROKES.toDouble())}\n${rec.top()}")
    }

    private fun assertOnlyTheComposerRecomposes() {
        assertWithMessage("item wrappers re-run:\n${rec.top()}").that(rec.count("LazyLayoutItemContentFactory") + rec.count("LazyListItemProviderImpl\$Item")).isEqualTo(0)
        assertWithMessage("the list's layout re-ran:\n${rec.top()}").that(rec.count("LazyLayoutKt\$LazyLayout")).isEqualTo(0)
        assertWithMessage("the HomeScreen body re-ran:\n${rec.top()}").that(rec.count("HomeScreenKt{AppGraph")).isEqualTo(0)
        assertWithMessage("scopes per keystroke:\n${rec.top()}").that(rec.scopes).isAtMost(MAX_SCOPES_PER_KEYSTROKE * KEYSTROKES)
    }

    @Test
    fun `a keystroke on the recent chats page recomposes the composer alone`() {
        typeInto(NewChatHome.RECENT, projectsAvailable = true)
        assertOnlyTheComposerRecomposes()
    }

    @Test
    fun `a keystroke on the Projects page recomposes the composer alone`() {
        typeInto(NewChatHome.PROJECTS, projectsAvailable = true)
        assertOnlyTheComposerRecomposes()
    }

    private companion object {
        const val WARM_UP = 20
        const val KEYSTROKES = 60
        const val MAX_SCOPES_PER_KEYSTROKE = 6
    }
}
