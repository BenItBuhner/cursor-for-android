package com.cursorforandroid.ui.home

import android.app.Application
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.ProjectArrangement
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
    private val arrangements = mutableListOf<ProjectArrangement>()

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

    /**
     * The Projects page with [local]'s order and hidden Projects; with [followOrder], each drop is written back into the
     * list as the app's organizer would, or — with [onArrange] — handed to that instead.
     */
    private fun show(
        followOrder: Boolean = false,
        local: LocalAgentState = LocalAgentState(),
        agents: List<Agent> = NewChatHomeFixtures.agents,
        onArrange: ((ProjectArrangement) -> Unit)? = null,
    ) {
        list = NewChatHomeFixtures.list(agents, local)
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
                    onArrangeProjects = { arrangement ->
                        arrangements += arrangement
                        reorders += arrangement.order
                        when {
                            onArrange != null -> onArrange(arrangement)
                            followOrder -> list = NewChatHomeFixtures.list(agents, LocalAgentState(projectOrder = arrangement.order, hiddenProjectIds = arrangement.hidden))
                        }
                    },
                )
            }
        }
        val shown = agents.count { it.isProject } - local.hiddenProjectIds.size
        compose.waitUntil(30_000) { if (shown == 0) allHiddenShown() else shortcuts() == shown }
        compose.mainClock.autoAdvance = false
    }

    private fun allHiddenShown() = compose.onAllNodes(hasTestTag(NewChatHomeTags.ALL_HIDDEN)).fetchSemanticsNodes().isNotEmpty()

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
        compose.onAllNodes(hasTestTag(NewChatHomeTags.HIDDEN_LINE)).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT) and SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)).fetchSemanticsNodes().isEmpty()

    /** The shortcuts on the page, outside arranging, by name in their order; the hidden ones are not among them. */
    private fun names(state: String): List<String> =
        compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)).fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.center.y.roundToInt() }, { it.boundsInRoot.center.x }))
            .map { it.config[SemanticsProperties.Text].first().text }

    /** While arranging: the shortcuts above the "Hidden" line, and those below it. */
    private fun shownNames() = names(ProjectShortcutCopy.ARRANGING)
    private fun hiddenNames() = names(ProjectShortcutCopy.HIDDEN)

    /** A point [below] the "Hidden" line's foot, in the page's coordinates: half a shortcut down lands in its first row. */
    private fun underLine(below: Float = shortcutHeight() / 2f, x: Float? = null): Offset {
        val line = compose.onNode(hasTestTag(NewChatHomeTags.HIDDEN_LINE)).fetchSemanticsNode().boundsInRoot
        val page = page().fetchSemanticsNode().boundsInRoot
        return Offset(x ?: (line.left + line.width / 4f - page.left), line.bottom + below - page.top)
    }

    /** A point just above the "Hidden" line, in the page's coordinates. */
    private fun overLine(x: Float): Offset {
        val line = compose.onNode(hasTestTag(NewChatHomeTags.HIDDEN_LINE)).fetchSemanticsNode().boundsInRoot
        return Offset(x, line.top - shortcutHeight() / 3f - page().fetchSemanticsNode().boundsInRoot.top)
    }

    private fun shortcutHeight() = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes().first().boundsInRoot.height

    /**
     * While arranging, drags [name]'s shortcut to [target] at once and lets go, the hand moving a frame at a time; what
     * was felt on the way, each haptic once in the order first felt.
     */
    private fun dragArranged(name: String, target: Offset): List<Int> {
        val start = at(name)
        val felt = LinkedHashSet<Int>()
        page().performTouchInput { down(start) }
        for (step in 1..STEPS) {
            page().performTouchInput { moveTo(lerp(start, target, step / STEPS.toFloat())) }
            hold(FRAME_MILLIS)
            felt += lastHaptic()
        }
        hold(400)
        release()
        hold(1_000)
        return felt.toList()
    }

    /** Ends the arranging as the reader would: a tap on the page around the shortcuts. */
    private fun tapAway() {
        page().performTouchInput { click(Offset(centerX, top + 40f)) }
        hold(1_000)
    }

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
    fun `a lifted shortcut dragged over another takes its place, and the drop keeps the order while the arranging goes on`() {
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
        assertThat(arrangements.single().hidden).isEmpty()
        assertThat(arranging()).isTrue()
        tapAway()
        assertThat(arranging()).isFalse()
        // The list came back in the arranged order, and the page shows it as its own.
        assertThat(list.projectRows.map { it.agent.id }).containsExactly(NewChatHomeFixtures.BILLING, "bc-design", "bc-android", "bc-shipyard", "bc-pipeline").inOrder()
        assertThat(order()).containsExactly(NewChatHomeFixtures.BILLING_NAME, "Design system", "Cursor for Android", NewChatHomeFixtures.SHIPYARD_NAME, NewChatHomeFixtures.PIPELINE_NAME).inOrder()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `while arranging, a drag moves a shortcut at once and its drop leaves the arranging on`() {
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
        assertThat(arranging()).isTrue()
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
        assertThat(actions).containsExactly(ProjectShortcutCopy.MOVE_LATER, ProjectShortcutCopy.HIDE).inOrder()
        compose.runOnUiThread {
            shipyard.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == ProjectShortcutCopy.MOVE_LATER }.action()
        }
        hold(1_000)
        assertThat(reorders).containsExactly(listOf(NewChatHomeFixtures.BILLING, "bc-shipyard", "bc-design", "bc-android", "bc-pipeline"))
        assertThat(order().take(2)).containsExactly(NewChatHomeFixtures.BILLING_NAME, NewChatHomeFixtures.SHIPYARD_NAME).inOrder()
        assertThat(compose.onNodeWithText(NewChatHomeFixtures.SHIPYARD_NAME).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label })
            .containsExactly(ProjectShortcutCopy.MOVE_EARLIER, ProjectShortcutCopy.MOVE_LATER, ProjectShortcutCopy.HIDE).inOrder()

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

    @Test
    fun `a shortcut dragged below the Hidden line is hidden, felt as it crosses, and gone from the page after`() {
        show(followOrder = true)
        enterArranging(NewChatHomeFixtures.SHIPYARD_NAME)
        assertThat(hiddenNames()).isEmpty()
        compose.onAllNodes(hasTestTag(NewChatHomeTags.HIDDEN_DROP_TARGET), useUnmergedTree = true).assertCountEquals(1)

        val felt = dragArranged(NewChatHomeFixtures.PIPELINE_NAME, underLine())
        assertThat(felt).contains(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        assertThat(lastHaptic()).isEqualTo(HapticFeedbackConstants.GESTURE_END)
        val saved = arrangements.single()
        assertThat(saved.hidden).containsExactly("bc-pipeline")
        // Hiding leaves the whole order — the sidebar's — as it was.
        assertThat(saved.order).containsExactly(*IDS.toTypedArray()).inOrder()
        assertThat(arranging()).isTrue()
        assertThat(shownNames()).containsExactly(*NAMES.dropLast(1).toTypedArray()).inOrder()
        assertThat(hiddenNames()).containsExactly(NewChatHomeFixtures.PIPELINE_NAME)
        compose.onAllNodes(hasTestTag(NewChatHomeTags.HIDDEN_DROP_TARGET), useUnmergedTree = true).assertCountEquals(0)

        tapAway()
        assertThat(arranging()).isFalse()
        assertThat(order()).containsExactly(*NAMES.dropLast(1).toTypedArray()).inOrder()
        compose.onAllNodes(hasText(NewChatHomeFixtures.PIPELINE_NAME), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(NewChatHomeTags.HIDDEN_LINE)).assertCountEquals(0)
        // Only this page leaves it out: the list's Projects, which the sidebar and search draw, still have it.
        assertThat(list.projectRows.map { it.agent.id }).contains("bc-pipeline")
        assertThat(opened).isEmpty()
    }

    @Test
    fun `a hidden shortcut dragged back above the line is shown again where it is dropped`() {
        show(followOrder = true, local = LocalAgentState(hiddenProjectIds = setOf("bc-design", "bc-pipeline")))
        assertThat(order()).containsExactly(NewChatHomeFixtures.SHIPYARD_NAME, NewChatHomeFixtures.BILLING_NAME, "Cursor for Android").inOrder()

        enterArranging(NewChatHomeFixtures.SHIPYARD_NAME)
        assertThat(hiddenNames()).containsExactly("Design system", NewChatHomeFixtures.PIPELINE_NAME).inOrder()
        val felt = dragArranged("Design system", overLine(x = at(NewChatHomeFixtures.BILLING_NAME).x))
        assertThat(felt).contains(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
        assertThat(shownNames()).containsExactly(NewChatHomeFixtures.SHIPYARD_NAME, NewChatHomeFixtures.BILLING_NAME, "Cursor for Android", "Design system").inOrder()
        assertThat(hiddenNames()).containsExactly(NewChatHomeFixtures.PIPELINE_NAME)
        assertThat(arrangements.last().hidden).containsExactly("bc-pipeline")

        tapAway()
        assertThat(order()).containsExactly(NewChatHomeFixtures.SHIPYARD_NAME, NewChatHomeFixtures.BILLING_NAME, "Cursor for Android", "Design system").inOrder()
    }

    @Test
    fun `shortcuts reorder above the line, below it, and land in the slot they are dropped on across it`() {
        show(followOrder = true, local = LocalAgentState(hiddenProjectIds = setOf("bc-pipeline")))
        enterArranging(NewChatHomeFixtures.SHIPYARD_NAME)

        dragArranged(NewChatHomeFixtures.BILLING_NAME, at(NewChatHomeFixtures.PIPELINE_NAME))
        assertThat(shownNames()).containsExactly(NewChatHomeFixtures.SHIPYARD_NAME, "Design system", "Cursor for Android").inOrder()
        assertThat(hiddenNames()).containsExactly(NewChatHomeFixtures.BILLING_NAME, NewChatHomeFixtures.PIPELINE_NAME).inOrder()

        val felt = dragArranged(NewChatHomeFixtures.PIPELINE_NAME, at(NewChatHomeFixtures.BILLING_NAME))
        assertThat(felt).doesNotContain(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        assertThat(felt).contains(HapticFeedbackConstants.SEGMENT_TICK)
        assertThat(hiddenNames()).containsExactly(NewChatHomeFixtures.PIPELINE_NAME, NewChatHomeFixtures.BILLING_NAME).inOrder()

        dragArranged("Cursor for Android", at(NewChatHomeFixtures.SHIPYARD_NAME))
        assertThat(shownNames()).containsExactly("Cursor for Android", NewChatHomeFixtures.SHIPYARD_NAME, "Design system").inOrder()
        assertThat(hiddenNames()).containsExactly(NewChatHomeFixtures.PIPELINE_NAME, NewChatHomeFixtures.BILLING_NAME).inOrder()

        val saved = arrangements.last()
        assertThat(saved.hidden).containsExactly("bc-pipeline", NewChatHomeFixtures.BILLING)
        assertThat(saved.order.filter { it in saved.hidden }).containsExactly("bc-pipeline", NewChatHomeFixtures.BILLING).inOrder()
        assertThat(saved.order.filterNot { it in saved.hidden }).containsExactly("bc-android", "bc-shipyard", "bc-design").inOrder()

        tapAway()
        assertThat(order()).containsExactly("Cursor for Android", NewChatHomeFixtures.SHIPYARD_NAME, "Design system").inOrder()
    }

    @Test
    fun `with every Project hidden the page keeps a quiet way back, which arranges them to be dragged up again`() {
        show(followOrder = true, local = LocalAgentState(hiddenProjectIds = IDS.toSet()))
        assertThat(shortcuts()).isEqualTo(0)
        val way = compose.onNode(hasTestTag(NewChatHomeTags.ALL_HIDDEN))
        assertThat(way.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]).containsExactly(ProjectShortcutCopy.allHidden(5))

        way.performClick()
        hold(1_000)
        assertThat(opened).isEmpty()
        assertThat(arranging()).isTrue()
        assertThat(shownNames()).isEmpty()
        assertThat(hiddenNames()).containsExactly(*NAMES.toTypedArray()).inOrder()

        val room = compose.onNode(hasTestTag(NewChatHomeTags.ALL_HIDDEN)).fetchSemanticsNode().boundsInRoot.center - page().fetchSemanticsNode().boundsInRoot.topLeft
        val felt = dragArranged(NewChatHomeFixtures.BILLING_NAME, room)
        assertThat(felt).contains(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
        assertThat(shownNames()).containsExactly(NewChatHomeFixtures.BILLING_NAME)
        assertThat(arrangements.last().hidden).hasSize(4)

        tapAway()
        assertThat(order()).containsExactly(NewChatHomeFixtures.BILLING_NAME)
        compose.onAllNodes(hasTestTag(NewChatHomeTags.ALL_HIDDEN)).assertCountEquals(0)
    }

    @Test
    fun `accessibility services hide a shortcut, and show it again while the shortcuts are arranged`() {
        show(followOrder = true)
        fun action(name: String, label: String) = compose.runOnUiThread {
            compose.onNodeWithText(name).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }.action()
        }
        action(NewChatHomeFixtures.SHIPYARD_NAME, ProjectShortcutCopy.HIDE)
        hold(1_000)
        assertThat(arrangements.single().hidden).containsExactly("bc-shipyard")
        assertThat(order()).containsExactly(*NAMES.drop(1).toTypedArray()).inOrder()

        enterArranging(NewChatHomeFixtures.BILLING_NAME)
        assertThat(hiddenNames()).containsExactly(NewChatHomeFixtures.SHIPYARD_NAME)
        assertThat(compose.onNodeWithText(NewChatHomeFixtures.SHIPYARD_NAME).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label })
            .containsExactly(ProjectShortcutCopy.SHOW)
        action(NewChatHomeFixtures.SHIPYARD_NAME, ProjectShortcutCopy.SHOW)
        hold(1_000)
        assertThat(arrangements.last().hidden).isEmpty()
        assertThat(hiddenNames()).isEmpty()
        assertThat(shownNames().last()).isEqualTo(NewChatHomeFixtures.SHIPYARD_NAME)
    }

    @Test
    fun `a Project hidden on the page is still hidden after a restart, and only there`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        show(onArrange = { arrangement -> runBlocking { graph.prefs.setProjectArrangement(arrangement) } })
        enterArranging(NewChatHomeFixtures.SHIPYARD_NAME)
        dragArranged("Design system", underLine())
        tapAway()

        // The settings as the next start of the app reads them, from a store opened afresh over the same file.
        val restarted = runBlocking { PreferencesStore(context).localAgentState.first() }
        assertThat(restarted.hiddenProjectIds).containsExactly("bc-design")
        assertThat(restarted.projectOrder).containsExactly(*IDS.toTypedArray()).inOrder()
        list = NewChatHomeFixtures.list(local = restarted)
        hold(1_000)
        assertThat(order()).containsExactly(*NAMES.filterNot { it == "Design system" }.toTypedArray()).inOrder()
        // The sidebar's Projects group, which follows the same settings, still lists it in its place.
        assertThat(list.sections.first().rows.map { it.agent.id }).containsExactly(*IDS.toTypedArray()).inOrder()
    }

    @Test
    fun `a shortcut carried to the page's foot scrolls it to the Hidden line below the fold, and is hidden there`() {
        val more = (1..14).map { "Side project $it" }
        show(followOrder = true, agents = NewChatHomeFixtures.withMoreProjects(more))
        enterArranging(NewChatHomeFixtures.SHIPYARD_NAME)
        val page = page().fetchSemanticsNode().boundsInRoot
        // Unclipped: below the fold the line's bounds in the root are cut away to nothing.
        fun line() = compose.onNode(hasTestTag(NewChatHomeTags.HIDDEN_LINE)).fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }
        assertThat(line().top).isGreaterThan(page.bottom)

        val start = at(NewChatHomeFixtures.SHIPYARD_NAME)
        val foot = Offset(start.x, page.height - 8f)
        val felt = LinkedHashSet<Int>()
        page().performTouchInput { down(start) }
        for (step in 1..STEPS) {
            page().performTouchInput { moveTo(lerp(start, foot, step / STEPS.toFloat())) }
            hold(FRAME_MILLIS)
        }
        // Held still at the foot, the page goes on scrolling, the shortcut under the finger passing each slot to the line.
        repeat(250) {
            hold(FRAME_MILLIS)
            felt += lastHaptic()
        }
        assertThat(line().bottom).isLessThan(page.bottom)
        release()
        hold(1_000)

        assertThat(felt).contains(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        assertThat(arrangements.last().hidden).containsExactly("bc-shipyard")
        assertThat(hiddenNames()).containsExactly(NewChatHomeFixtures.SHIPYARD_NAME)
        assertThat(shownNames().size).isEqualTo(NAMES.size + more.size - 1)

        // Carried to the page's head, it scrolls back up the same way, and is shown again above the line.
        val back = at(NewChatHomeFixtures.SHIPYARD_NAME)
        val head = Offset(back.x, 8f)
        felt.clear()
        page().performTouchInput { down(back) }
        for (step in 1..STEPS) {
            page().performTouchInput { moveTo(lerp(back, head, step / STEPS.toFloat())) }
            hold(FRAME_MILLIS)
            felt += lastHaptic()
        }
        repeat(250) {
            hold(FRAME_MILLIS)
            felt += lastHaptic()
        }
        release()
        hold(1_000)
        assertThat(felt).contains(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
        assertThat(hiddenNames()).isEmpty()
        assertThat(arrangements.last().hidden).isEmpty()
    }

    private companion object {
        val IDS = listOf("bc-shipyard", NewChatHomeFixtures.BILLING, "bc-design", "bc-android", "bc-pipeline")
        const val EDIT_PROJECT = "Edit Project"
        const val FRAME_MILLIS = 16L
        const val STEPS = 10
        val NAMES = listOf(NewChatHomeFixtures.SHIPYARD_NAME, NewChatHomeFixtures.BILLING_NAME, "Design system", "Cursor for Android", NewChatHomeFixtures.PIPELINE_NAME)
    }
}
