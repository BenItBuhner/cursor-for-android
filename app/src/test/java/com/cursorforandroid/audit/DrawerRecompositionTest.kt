package com.cursorforandroid.audit

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.agents.SidebarTags
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.RecomposeCounter
import com.cursorforandroid.util.pumpSynced
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the phone shell recomposes behind a chat: nothing of the sidebar while the drawer is shut, however often the
 * list changes, and the shell itself not once a frame while the drawer is dragged.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class DrawerRecompositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private var deepLink by mutableStateOf<String?>(null)
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        RecomposeCounter.install()
    }

    @After
    fun tearDown() {
        RecomposeCounter.uninstall()
    }

    private fun launch() {
        graph = AppGraph(context)
        runBlocking { graph.session.enterDemo() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    wide = false,
                    deepLinkAgentId = deepLink,
                    onDeepLinkConsumed = { deepLink = null },
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        compose.waitForIdle()
        deepLink = CHAT_ID
        compose.waitUntil(30_000) { chatShown() && !onScreen(HOME_PLACEHOLDER) }
        settle()
    }

    private val vm: AgentsViewModel get() = ViewModelProvider(compose.activity, AgentsViewModel.Factory(graph))[AgentsViewModel::class.java]

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun chatShown() = compose.onAllNodes(hasTestTag("chat-header") and hasContentDescription(CHAT)).fetchSemanticsNodes().isNotEmpty()

    private fun drawerOpen() = compose.onAllNodes(hasContentDescription("Close navigation menu")).fetchSemanticsNodes().isNotEmpty()

    private fun settle() = repeat(4) { compose.pumpSynced() }

    @Test
    fun `behind a chat with the drawer shut, a change to the list recomposes nothing of the sidebar`() {
        launch()
        val emissions = vm.uiState.value
        RecomposeCounter.reset()
        vm.setSectionCollapsed("no-such-section", true)
        compose.waitUntil(10_000) { vm.uiState.value !== emissions }
        settle()
        val counts = "Sidebar=${RecomposeCounter.count("Sidebar")} AgentRowItem=${RecomposeCounter.count("AgentRowItem")}"
        AuditLog.line("list emission, chat on top, drawer shut -> $counts")
        assertEquals(counts, 0, RecomposeCounter.count("Sidebar"))
        assertEquals(counts, 0, RecomposeCounter.count("AgentRowItem"))
    }

    /** Twenty frames of a finger pulling the drawer out, then let go; the shell's recompositions over those frames. */
    private fun dragOpen(label: String): Int {
        compose.mainClock.autoAdvance = false
        val root = compose.onRoot()
        root.performTouchInput { down(Offset(width * 0.45f, height * 0.6f)) }
        compose.mainClock.advanceTimeByFrame()
        RecomposeCounter.reset()
        val frameMillis = (1..20).map {
            root.performTouchInput { moveBy(Offset(30f, 0f)) }
            val start = System.nanoTime()
            compose.mainClock.advanceTimeByFrame()
            (System.nanoTime() - start) / 100_000 / 10.0
        }
        val shell = RecomposeCounter.count("AppShell")
        AuditLog.line("$label: drag 20 frames -> AppShell=$shell Sidebar=${RecomposeCounter.count("Sidebar")} frame ms=$frameMillis")
        root.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        settle()
        return shell
    }

    private fun closeDrawer() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        settle()
    }

    @Test
    fun `dragging the drawer out over a chat does not recompose the shell every frame`() {
        launch()
        val first = dragOpen("first open")
        assertTrue("the drag opens the drawer", drawerOpen())
        closeDrawer()
        assertTrue("back shuts the drawer", !drawerOpen())
        val second = dragOpen("second open")
        assertTrue("the drag opens the drawer again", drawerOpen())
        assertTrue("AppShell recomposed $first times over the first drag's 20 frames", first <= 2)
        assertTrue("AppShell recomposed $second times over the second drag's 20 frames", second <= 2)
    }

    @Test
    fun `a drawer pulled out after the list changed behind it shows its rows where they belong from its first frame`() {
        launch()
        val sections = vm.uiState.value.sections
        val folded = sections.first().key
        val below = hasText(sections[1].rows.first { it.agent.name != CHAT }.agent.name)
        dragOpen("before the change")
        val before = compose.onNode(below).getBoundsInRoot().top.value
        closeDrawer()
        vm.setSectionCollapsed(folded, true)
        compose.waitUntil(10_000) { folded in vm.uiState.value.collapsedSections }
        settle()

        compose.mainClock.autoAdvance = false
        val root = compose.onRoot()
        root.performTouchInput { down(Offset(width * 0.45f, height * 0.6f)) }
        val tops = mutableListOf<Float>()
        repeat(20) {
            root.performTouchInput { moveBy(Offset(30f, 0f)) }
            compose.mainClock.advanceTimeByFrame()
            if (drawerFraction() > 0f) tops += compose.onNode(below).getBoundsInRoot().top.value
        }
        root.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        settle()
        val settled = compose.onNode(below).getBoundsInRoot().top.value
        AuditLog.line("catch-up: row top before=$before per shown frame=$tops settled=$settled")
        assertTrue("the drawer came out", tops.isNotEmpty())
        assertTrue("the fold moved the row up", settled < before - 10f)
        tops.forEach { assertEquals("the row below the folded group, frame by frame", settled, it, 0.5f) }

        // Open, the drawer's rows move as they always do.
        assertTrue("the drag opens the drawer", drawerOpen())
        compose.mainClock.autoAdvance = false
        vm.setSectionCollapsed(folded, false)
        // The fold is written to the store and read back on the wall clock, not the held frame clock: the frames
        // counted start once it is in the list, with no frame stepped meanwhile, so they see the slide from its start.
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            folded !in vm.uiState.value.collapsedSections
        }
        val moving = (1..40).map {
            compose.pumpSynced()
            compose.mainClock.advanceTimeByFrame()
            compose.onNode(below).getBoundsInRoot().top.value
        }
        compose.mainClock.autoAdvance = true
        settle()
        AuditLog.line("unfolded in the open drawer: row top per frame=$moving")
        assertEquals("the row back where it was", before, moving.last(), 0.5f)
        assertTrue("the row slides back rather than jumping", moving.any { it > settled + 1f && it < before - 1f })
    }

    /** How far the drawer's sheet has come in, by its left edge against its width. */
    private fun drawerFraction(): Float {
        val sheet = compose.onAllNodes(hasTestTag(SidebarTags.ACCOUNT)).fetchSemanticsNodes().firstOrNull() ?: return 0f
        val left = sheet.boundsInRoot.left
        val width = sheet.boundsInRoot.width
        return if (width <= 0f) 0f else (1f + left / width).coerceIn(0f, 1f)
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT = "Cli exploration"
        const val CHAT_ID = "bc-demo-0004"
    }
}
