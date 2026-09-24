package com.cursorforandroid.ui.home

import android.app.Application
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.NewChatHome
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The Project shortcuts' long press on the New Chat page: a hold opens the Project's menu from the sidebar, a hold twice
 * as long lifts the shortcut to be arranged, a drag moves it and the drop keeps the order; a tap anywhere, back, or a
 * drag cut short end the arranging. Each threshold is checked against the haptic the phone was asked for.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectShortcutGridTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private lateinit var view: View
    private var longPress = 0L
    private var list by mutableStateOf(NewChatHomeFixtures.list())
    private val opened = mutableListOf<String>()
    private val edited = mutableListOf<String>()
    private val reorders = mutableListOf<List<String>>()

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

    /** The Projects page; with [followOrder], each drop is written back into the list as the app's organizer would. */
    private fun show(followOrder: Boolean = false) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                view = LocalView.current
                longPress = LocalViewConfiguration.current.longPressTimeoutMillis
                HomeScreen(
                    graph = graph,
                    listState = list,
                    onOpenSidebar = {},
                    onOpenAgent = { opened += it.agent.id },
                    onLaunchOpen = {},
                    rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, onEditProject = { edited += it.agent.id }),
                    home = NewChatHome.PROJECTS,
                    projectsAvailable = true,
                    onReorderProjects = { ids ->
                        reorders += ids
                        if (followOrder) list = NewChatHomeFixtures.list(local = LocalAgentState(projectOrder = ids))
                    },
                )
            }
        }
        compose.waitUntil(30_000) { shortcuts() == 5 }
        compose.mainClock.autoAdvance = false
    }

    private fun shortcuts() = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes().size

    private fun page() = compose.onNode(hasScrollToIndexAction())

    /** The centre of [name]'s shortcut in the page's coordinates, where the touches are sent. */
    private fun at(name: String): Offset =
        compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.center - page().fetchSemanticsNode().boundsInRoot.topLeft

    private fun press(name: String) {
        val point = at(name)
        page().performTouchInput { down(point) }
    }

    private fun release() = page().performTouchInput { up() }

    /**
     * Lets [millis] pass a frame at a time: with the clock paused, the page is laid out only as the main looper idles, and
     * a shortcut's glide into its new slot starts from that layout, so each frame is given its idle.
     */
    private fun hold(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, FRAME_MILLIS)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle()
            left -= step
        }
    }

    private fun lastHaptic() = shadowOf(view).lastHapticFeedbackPerformed()

    private fun menuShown() = compose.onAllNodes(hasText(EDIT_PROJECT)).fetchSemanticsNodes().isNotEmpty()

    private fun arranging() =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ProjectShortcutCopy.ARRANGING)).fetchSemanticsNodes().size == 5

    /** The shortcuts' names as they are laid out, row by row; by their centres, which the lifted one's scale leaves be. */
    private fun order(): List<String> = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes()
        .sortedWith(compareBy({ it.boundsInRoot.center.y.roundToInt() }, { it.boundsInRoot.center.x }))
        .map { it.config[SemanticsProperties.Text].first().text }

    private fun enterArranging(name: String) {
        press(name)
        hold(2 * longPress + 100)
        release()
        hold(500)
        assertThat(arranging()).isTrue()
    }

    @Test
    fun `a short hold opens the Project's own menu, twice as long arranges, and each threshold is felt`() {
        show()
        press(NewChatHomeFixtures.SHIPYARD_NAME)
        hold(longPress - 50)
        assertThat(menuShown()).isFalse()

        hold(100)
        listOf(EDIT_PROJECT, "Open on cursor.com", "Copy link").forEach { compose.onNodeWithText(it).assertExists() }
        assertThat(lastHaptic()).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
        assertThat(arranging()).isFalse()

        hold(longPress + 250)
        compose.onAllNodes(hasText(EDIT_PROJECT)).assertCountEquals(0)
        assertThat(lastHaptic()).isEqualTo(HapticFeedbackConstants.DRAG_START)
        assertThat(arranging()).isTrue()

        // Let go where it was lifted: nothing moved, so the arranging goes on and nothing is written or opened.
        release()
        hold(500)
        assertThat(arranging()).isTrue()
        assertThat(reorders).isEmpty()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `a hold released at the menu leaves the menu open, and its Edit Project edits that Project`() {
        show()
        press(NewChatHomeFixtures.SHIPYARD_NAME)
        hold(longPress + 100)
        release()
        hold(longPress * 2)
        assertThat(menuShown()).isTrue()
        assertThat(arranging()).isFalse()
        compose.onNodeWithText(EDIT_PROJECT).performClick()
        hold(500)
        assertThat(edited).containsExactly("bc-shipyard")
        assertThat(menuShown()).isFalse()
    }

    @Test
    fun `a lifted shortcut dragged over another takes its place, and the drop keeps the order and ends the arranging`() {
        show(followOrder = true)
        assertThat(order()).containsExactly(*NAMES.toTypedArray()).inOrder()
        val start = at(NewChatHomeFixtures.SHIPYARD_NAME)
        val target = at("Cursor for Android")

        press(NewChatHomeFixtures.SHIPYARD_NAME)
        hold(2 * longPress + 100)
        page().performTouchInput { for (step in 1..6) moveTo(lerp(start, target, step / 6f)) }
        assertThat(lastHaptic()).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        hold(1_000)
        // Mid-drag: the others have made room, the lifted one is still under the finger.
        assertThat(order()).containsExactly(NewChatHomeFixtures.BILLING_NAME, "Design system", "Cursor for Android", NewChatHomeFixtures.SHIPYARD_NAME, NewChatHomeFixtures.PIPELINE_NAME).inOrder()
        assertThat(reorders).isEmpty()

        release()
        assertThat(lastHaptic()).isEqualTo(HapticFeedbackConstants.GESTURE_END)
        hold(1_000)
        assertThat(reorders).containsExactly(listOf(NewChatHomeFixtures.BILLING, "bc-design", "bc-android", "bc-shipyard", "bc-pipeline"))
        assertThat(arranging()).isFalse()
        // The list came back in the arranged order, and the page shows it as its own.
        assertThat(list.projectRows.map { it.agent.id }).containsExactly(NewChatHomeFixtures.BILLING, "bc-design", "bc-android", "bc-shipyard", "bc-pipeline").inOrder()
        assertThat(order()).containsExactly(NewChatHomeFixtures.BILLING_NAME, "Design system", "Cursor for Android", NewChatHomeFixtures.SHIPYARD_NAME, NewChatHomeFixtures.PIPELINE_NAME).inOrder()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `while arranging, a drag moves a shortcut at once and its drop ends the arranging`() {
        show()
        enterArranging(NewChatHomeFixtures.PIPELINE_NAME)
        val start = at(NewChatHomeFixtures.PIPELINE_NAME)
        val target = at(NewChatHomeFixtures.SHIPYARD_NAME)
        page().performTouchInput {
            down(start)
            for (step in 1..6) moveTo(lerp(start, target, step / 6f))
            up()
        }
        hold(1_000)
        assertThat(reorders).containsExactly(listOf("bc-pipeline", "bc-shipyard", NewChatHomeFixtures.BILLING, "bc-design", "bc-android"))
        assertThat(order().first()).isEqualTo(NewChatHomeFixtures.PIPELINE_NAME)
        assertThat(arranging()).isFalse()
    }

    @Test
    fun `while arranging, a tap on the page or on a shortcut ends it and opens nothing`() {
        show()
        enterArranging(NewChatHomeFixtures.SHIPYARD_NAME)
        page().performTouchInput { click(Offset(centerX, bottom - 40f)) }
        hold(300)
        assertThat(arranging()).isFalse()

        enterArranging(NewChatHomeFixtures.SHIPYARD_NAME)
        val billing = at(NewChatHomeFixtures.BILLING_NAME)
        page().performTouchInput { click(billing) }
        hold(300)
        assertThat(arranging()).isFalse()
        assertThat(opened).isEmpty()
        assertThat(reorders).isEmpty()

        // Out of it, a tap opens the Project as it always has.
        page().performTouchInput { click(billing) }
        hold(300)
        assertThat(opened).containsExactly(NewChatHomeFixtures.BILLING)
    }

    @Test
    fun `back ends the arranging, and a drag it cuts short leaves the order as it was`() {
        show()
        enterArranging(NewChatHomeFixtures.SHIPYARD_NAME)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        hold(300)
        assertThat(arranging()).isFalse()
        assertThat(compose.activity.isFinishing).isFalse()

        val target = at("Design system")
        press(NewChatHomeFixtures.SHIPYARD_NAME)
        hold(2 * longPress + 100)
        page().performTouchInput { moveTo(target) }
        hold(300)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        release()
        hold(1_000)
        assertThat(arranging()).isFalse()
        assertThat(reorders).isEmpty()
        assertThat(order()).containsExactly(*NAMES.toTypedArray()).inOrder()
    }

    @Test
    fun `a finger that moves before the hold is up is not a hold`() {
        show()
        val start = at(NewChatHomeFixtures.SHIPYARD_NAME)
        page().performTouchInput {
            down(start)
            moveTo(start + Offset(60f, 0f))
        }
        hold(2 * longPress + 100)
        assertThat(menuShown()).isFalse()
        assertThat(arranging()).isFalse()
        release()
        hold(300)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `accessibility services move a shortcut earlier or later and open its menu as its long click`() {
        show()
        val shipyard = compose.onNodeWithText(NewChatHomeFixtures.SHIPYARD_NAME)
        val actions = shipyard.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertThat(actions).containsExactly(ProjectShortcutCopy.MOVE_LATER)
        compose.runOnUiThread {
            shipyard.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == ProjectShortcutCopy.MOVE_LATER }.action()
        }
        hold(1_000)
        assertThat(reorders).containsExactly(listOf(NewChatHomeFixtures.BILLING, "bc-shipyard", "bc-design", "bc-android", "bc-pipeline"))
        assertThat(order().take(2)).containsExactly(NewChatHomeFixtures.BILLING_NAME, NewChatHomeFixtures.SHIPYARD_NAME).inOrder()
        assertThat(compose.onNodeWithText(NewChatHomeFixtures.SHIPYARD_NAME).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label })
            .containsExactly(ProjectShortcutCopy.MOVE_EARLIER, ProjectShortcutCopy.MOVE_LATER).inOrder()

        compose.onNodeWithText(NewChatHomeFixtures.SHIPYARD_NAME).performSemanticsAction(SemanticsActions.OnLongClick)
        hold(300)
        assertThat(menuShown()).isTrue()
    }

    /** A grid only shown, as Settings' miniature shows it, recomposed with new rows: it settles, and does not go on recomposing. */
    @Test
    fun `a shown grid given new rows settles once they are drawn`() {
        var rows by mutableStateOf(NewChatHomeFixtures.list().projectRows)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { ProjectShortcutGrid(rows) }
        }
        compose.waitUntil(30_000) { shortcuts() == 5 }
        rows = rows.take(3)
        compose.waitForIdle()
        assertThat(shortcuts()).isEqualTo(3)
        rows = NewChatHomeFixtures.list().projectRows
        compose.waitForIdle()
        assertThat(order()).containsExactly(*NAMES.toTypedArray()).inOrder()
    }

    private companion object {
        const val EDIT_PROJECT = "Edit Project"
        const val FRAME_MILLIS = 16L
        val NAMES = listOf(NewChatHomeFixtures.SHIPYARD_NAME, NewChatHomeFixtures.BILLING_NAME, "Design system", "Cursor for Android", NewChatHomeFixtures.PIPELINE_NAME)
    }
}
