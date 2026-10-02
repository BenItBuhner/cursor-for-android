package com.cursorforandroid.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.NewChatPageSnapshot
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.NewChatHomeChoice
import com.cursorforandroid.domain.ProjectArrangement
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The New Chat page on a launch after one that drew Projects: the shortcuts are on its very first frame, where they
 * stay, rather than an empty page the Projects slide into once the settings and the list have been read. The first
 * launch is the demo's shell left to load and write its page down; the second is a shell built from nothing over it —
 * a new process's graph reading the file, or a new activity over the live process. The page stacking the Projects
 * over the recent chats opens on its Projects the same way.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NewChatPageFirstFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var shown by mutableStateOf<Launch?>(null)

    private class Launch(val graph: AppGraph, val user: CursorUser) {
        /** A fresh activity's store: the list's view model is built again rather than handed over. */
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val tag = "launch-${System.identityHashCode(this)}"
    }

    private fun demoLaunch(graph: AppGraph = AppGraph(app)): Launch {
        runBlocking { graph.session.enterDemo() }
        val user = (graph.session.state.value as SessionState.SignedIn).user
        return Launch(graph, user)
    }

    private fun tiles(): List<SemanticsNode> = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes()

    private fun composerTop(): Float = compose.onNode(hasTestTag(NewChatHomeTags.COMPOSER)).fetchSemanticsNode().positionInRoot.y

    private val pageFile: File get() = File(app.cacheDir, "cursor/newchat/page.json")

    /** The first launch: the demo's page shown until its Projects are in and written down. Their count. */
    private fun firstLaunch(launch: Launch): Int {
        pageFile.delete()
        shown = launch
        host()
        compose.waitUntil(30_000) { tiles().isNotEmpty() }
        compose.waitUntil(30_000) { pageFile.isFile }
        compose.waitForIdle()
        return tiles().size
    }

    private fun host() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val current = shown ?: return@CursorTheme
                key(current) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides current.owner) {
                        Box(Modifier.fillMaxSize().testTag(current.tag)) {
                            AppShell(graph = current.graph, user = current.user, isDemo = true, wide = false, deepLinkAgentId = null, onDeepLinkConsumed = {})
                        }
                    }
                }
            }
        }
    }

    /** Swaps in [next]'s shell with the clock held, up to the first frame it is drawn in. */
    private fun swapTo(next: Launch) {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { shown = next }
        var frames = 0
        while (compose.onAllNodes(hasTestTag(next.tag)).fetchSemanticsNodes().isEmpty()) {
            check(++frames <= 5) { "the second launch's shell was never composed" }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    /** Swaps in [next]'s shell, and checks the frames from its first. */
    private fun secondLaunch(next: Launch, projects: Int) {
        swapTo(next)
        assertWithMessage("Project shortcuts on the first frame").that(tiles()).hasSize(projects)
        File("build/outputs/roborazzi").mkdirs()
        captureScreenRoboImage("build/outputs/roborazzi/new_chat_first_frame.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
        val first = composerTop()
        repeat(60) {
            compose.mainClock.advanceTimeBy(FRAME_MILLIS)
            compose.waitForIdle()
            assertWithMessage("shortcuts, frame ${it + 2}").that(tiles()).hasSize(projects)
            assertWithMessage("the composer, frame ${it + 2}").that(composerTop()).isWithin(1f).of(first)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(tiles()).hasSize(projects)
        assertThat(composerTop()).isWithin(1f).of(first)
    }

    @Test
    fun `a new process draws the Projects on its first frame, from the page the last one wrote down`() {
        val projects = firstLaunch(demoLaunch())
        val next = demoLaunch()
        next.graph.caches.newChatPage.warm()
        secondLaunch(next, projects)
    }

    @Test
    fun `an activity built again over the live process opens on the Projects too`() {
        val first = demoLaunch()
        val projects = firstLaunch(first)
        secondLaunch(Launch(first.graph, first.user), projects)
    }

    @Test
    fun `the Projects hidden from the page are not on its first frame`() {
        val rows = NewChatHomeFixtures.list().projectRows
        val hidden = setOf(rows[0].agent.id, rows[2].agent.id)
        val writer = demoLaunch()
        runBlocking {
            writer.graph.caches.newChatPage.save(NewChatPageSnapshot.of(writer.user, NewChatHomeChoice(null), extendedMode = true, projects = rows, hidden = hidden))
        }
        host()
        val next = demoLaunch()
        next.graph.caches.newChatPage.warm()
        swapTo(next)

        assertThat(tiles()).hasSize(rows.size - hidden.size)
        rows.forEach { row -> assertWithMessage(row.agent.name).that(drawn(row.agent.name)).isEqualTo(row.agent.id !in hidden) }
    }

    /** The demo's one Project hidden in the settings: the page writes it down hidden, and the next process opens on the page's "hidden" row and never draws it. */
    @Test
    fun `a Project hidden in the settings is written down hidden, and the next process never draws it`() {
        val first = demoLaunch()
        firstLaunch(first)
        val project = first.graph.agents.state.value.agents.single { it.isProject }
        runBlocking { first.graph.prefs.setProjectArrangement(ProjectArrangement(order = listOf(project.id), hidden = setOf(project.id))) }
        val written = Regex("\"hiddenProjectIds\":\\[[^\\]]*\"${Regex.escape(project.id)}\"")
        compose.waitUntil(30_000) { tiles().isEmpty() && allHidden() }
        compose.waitUntil(30_000) { written.containsMatchIn(pageFile.readText()) }

        val next = demoLaunch()
        next.graph.caches.newChatPage.warm()
        swapTo(next)
        val composer = composerTop()
        repeat(60) {
            assertWithMessage("${project.name}, frame ${it + 1}").that(drawn(project.name)).isFalse()
            assertWithMessage("the hidden row, frame ${it + 1}").that(allHidden()).isTrue()
            assertWithMessage("the composer, frame ${it + 1}").that(composerTop()).isWithin(1f).of(composer)
            compose.mainClock.advanceTimeBy(FRAME_MILLIS)
            compose.waitForIdle()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(drawn(project.name)).isFalse()
        assertThat(allHidden()).isTrue()
        assertThat(composerTop()).isWithin(1f).of(composer)
    }

    /**
     * Projects + Recent chosen: the next launch opens on its Projects from the very first frame, as the Projects
     * layout does — the page written down carries the choice and the shortcuts — and the recent chats, which the page
     * does not write down, take their place under the shortcuts once the list has been read, never over or among them.
     */
    @Test
    fun `a page stacking Projects over the recent chats opens on its Projects, the chats following under them`() {
        val first = demoLaunch()
        runBlocking { first.graph.prefs.setNewChatHome(NewChatHome.PROJECTS_RECENT) }
        val projects = firstLaunch(first)
        compose.waitUntil(30_000) { recents().isNotEmpty() }
        compose.waitUntil(30_000) { pageFile.readText().contains("\"chosenHome\":\"${NewChatHome.PROJECTS_RECENT.key}\"") }
        chatsUnderShortcuts()

        val next = demoLaunch()
        next.graph.caches.newChatPage.warm()
        swapTo(next)
        assertWithMessage("Project shortcuts on the first frame").that(tiles()).hasSize(projects)
        repeat(60) {
            compose.mainClock.advanceTimeBy(FRAME_MILLIS)
            compose.waitForIdle()
            assertWithMessage("shortcuts, frame ${it + 2}").that(tiles()).hasSize(projects)
            chatsUnderShortcuts()
        }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(30_000) { recents().isNotEmpty() }
        assertThat(tiles()).hasSize(projects)
        chatsUnderShortcuts()
    }

    private fun recents(): List<SemanticsNode> = compose.onAllNodes(hasTestTag(NewChatHomeTags.RECENT_CHAT)).fetchSemanticsNodes()

    /** Whatever recent chats are drawn start below the lowest shortcut. */
    private fun chatsUnderShortcuts() {
        val shortcutsEnd = tiles().maxOf { it.boundsInRoot.bottom }
        recents().forEach { assertWithMessage("a recent chat under the shortcuts").that(it.boundsInRoot.top).isAtLeast(shortcutsEnd) }
    }

    private fun allHidden() = compose.onAllNodes(hasTestTag(NewChatHomeTags.ALL_HIDDEN)).fetchSemanticsNodes().isNotEmpty()

    private fun drawn(name: String) = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT) and hasText(name, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
