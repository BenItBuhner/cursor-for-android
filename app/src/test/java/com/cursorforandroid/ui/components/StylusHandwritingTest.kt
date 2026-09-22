package com.cursorforandroid.ui.components

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.conversation.AccountQueueRows
import com.cursorforandroid.ui.panel.SidePanelHost
import com.cursorforandroid.ui.panel.SidePanelState
import com.cursorforandroid.ui.panel.SidePanelValue
import com.cursorforandroid.ui.projects.ProjectEditorSheet
import com.cursorforandroid.ui.projects.ProjectEditorTarget
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * An S Pen writes into the text fields: a stroke that starts over one — or just off it, as Android allows around an
 * `EditText` — is handwriting, handed to the IME through `InputMethodManager.startStylusHandwriting`, and never a drag
 * for the right panel, the sidebar drawer, a sheet or a list. The scene is the chat's: the drawer around the panel host
 * around a transcript, the account's queue and the composer, the sidebar with its search and a chat inside the drawer;
 * and the Project editor's sheet. A finger over the same places still pulls the panel and the drawer, and a pen still
 * taps buttons.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi", shadows = [ShadowHandwritingInputMethodManager::class])
class StylusHandwritingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var scope: CoroutineScope
    private val opened = mutableListOf<String>()
    private var sent = 0

    @Before
    @After
    fun resetIme() = ShadowHandwritingInputMethodManager.started.clear()

    private fun show(panel: SidePanelState = SidePanelState(SidePanelValue.Closed), drawer: CursorDrawerState = CursorDrawerState(DrawerValue.Closed)) {
        compose.setContent {
            scope = rememberCoroutineScope()
            var draft by remember { mutableStateOf("") }
            CursorTheme(mode = ThemeMode.Dark) {
                CursorDrawer(state = drawer, drawerWidth = 300.dp, drawerContent = { SidebarUnderTest() }) {
                    SidePanelHost(state = panel, panelWidth = 360.dp, panelContent = { Box(Modifier.fillMaxSize().testTag("panel-body")) }) {
                        Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("transcript")) {
                                items(12) { Text("Reply $it", Modifier.fillMaxWidth().height(64.dp).padding(16.dp)) }
                            }
                            AccountQueueRows(
                                queue = listOf(PendingFollowup("fu-1", "Then add a test for the light theme")),
                                inFlightIds = emptySet(),
                                onSendNow = {},
                                onRemove = {},
                                onUpdate = { _, _ -> },
                                onEditing = { _, _ -> },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            ComposerBox(
                                value = draft,
                                onValueChange = { draft = it },
                                placeholder = "Ask anything",
                                onSend = { sent++ },
                                canSend = true,
                                plusMenu = ComposerMenuActions(onPickMedia = {}),
                                modelLabel = "Composer 2",
                                onModel = {},
                                modifier = Modifier.padding(12.dp).testTag("composer"),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun SidebarUnderTest() {
        Sidebar(
            state = AgentListUiState(sections = listOf(AgentSection("date:Today", "Today", listOf(row("today", "Morning standup")))), hasLoaded = true),
            user = CursorUser("key", "a@b.com", "Demo", "User", 1),
            isDemo = true,
            selectedAgentId = null,
            selectedDestination = null,
            onQueryChange = {},
            callbacks = SidebarCallbacks(
                onNewChat = {},
                onSettings = {},
                onCustomize = {},
                onToggleSidebar = null,
                onRefresh = {},
                rowActions = AgentRowActions({ opened += it.agent.id }, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun row(id: String, name: String) = AgentRow(
        agent = Agent(
            id = id,
            name = name,
            lifecycle = AgentLifecycle.IDLE,
            runStatus = RunStatus.FINISHED,
            envType = EnvType.CLOUD,
            envName = null,
            url = "https://cursor.com/agents/$id",
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
            latestRunId = null,
            repoUrl = null,
            startingRef = null,
        ),
        indicator = AgentIndicator.Read,
        isPinned = false,
        isUnread = false,
        launchedFromThisDevice = false,
    )

    private val composerField: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer")))
    private val searchField: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sidebar-search")))

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInWindow
    private fun px(dp: Float): Float = with(compose.density) { dp.dp.toPx() }

    private val window: View get() = compose.activity.window.decorView
    private val dialog: View get() = ShadowDialog.getLatestDialog().window!!.decorView

    private fun stylus(on: View = window) = PointerStroke.stylus(compose, on)
    private fun finger(on: View = window) = PointerStroke.finger(compose, on)

    private fun assertHandwritingStarted() {
        compose.waitForIdle()
        assertThat(ShadowHandwritingInputMethodManager.started).isNotEmpty()
    }

    private fun assertNoHandwriting() {
        compose.waitForIdle()
        assertThat(ShadowHandwritingInputMethodManager.started).isEmpty()
    }

    private fun openSearch() {
        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.waitForIdle()
        searchField.assertIsFocused()
    }

    @Test
    fun `a pen stroke on the composer's line of text is handwriting, and pulls nothing`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(panel, drawer)
        val line = bounds(composerField)

        stylus().down(line.center).moveBy(Offset(-px(120f), px(6f))).up()

        composerField.assertIsFocused()
        assertHandwritingStarted()
        assertThat(panel.fraction).isEqualTo(0f)
        assertThat(drawer.fraction).isEqualTo(0f)
    }

    @Test
    fun `a pen stroke begun just above the composer's line writes into it instead of pulling the panel`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        val line = bounds(composerField)

        // Over the composer's top padding: a letter's first stroke is rarely begun on the baseline.
        stylus().down(Offset(line.center.x, line.top - px(10f))).moveBy(Offset(-px(120f), px(8f))).up()

        compose.waitForIdle()
        assertThat(panel.targetValue).isEqualTo(SidePanelValue.Closed)
        assertThat(panel.fraction).isEqualTo(0f)
        composerField.assertIsFocused()
        assertHandwritingStarted()
    }

    @Test
    fun `a pen stroke begun below the composer's line, over the footer's gap, never opens the drawer`() {
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(drawer = drawer)
        val line = bounds(composerField)

        stylus().down(Offset(line.center.x, line.bottom + px(18f))).moveBy(Offset(px(140f), -px(6f))).up()

        compose.waitForIdle()
        assertThat(drawer.isOpen).isFalse()
        assertThat(drawer.fraction).isEqualTo(0f)
        composerField.assertIsFocused()
        assertHandwritingStarted()
    }

    @Test
    fun `a pen stroke over the composer while the drawer is still sliding shut writes, and the drawer shuts`() {
        val drawer = CursorDrawerState(DrawerValue.Open)
        show(drawer = drawer)
        val line = bounds(composerField)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { scope.launch { drawer.close() } }
        compose.mainClock.advanceTimeBy(150)
        assertThat(drawer.isAnimating).isTrue()

        // The sliding drawer takes the down to stop itself; the stroke is still the composer's.
        stylus().down(Offset(line.right - px(60f), line.center.y)).moveBy(Offset(px(120f), px(4f))).up()
        compose.mainClock.autoAdvance = true

        assertHandwritingStarted()
        composerField.assertIsFocused()
        compose.waitForIdle()
        assertThat(drawer.isOpen).isFalse()
        assertThat(drawer.fraction).isEqualTo(0f)
    }

    @Test
    fun `a pen tap on send still sends`() {
        show()
        val send = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInWindow

        stylus().down(send.center).up()

        compose.runOnIdle { assertThat(sent).isEqualTo(1) }
        assertNoHandwriting()
    }

    @Test
    fun `a finger dragged from just above the composer's line still pulls the panel in`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        val line = bounds(composerField)

        finger().down(Offset(line.center.x, line.top - px(10f))).moveBy(Offset(-px(300f), 0f), steps = 20).up()

        compose.waitForIdle()
        assertThat(panel.targetValue).isEqualTo(SidePanelValue.Open)
        composerField.assertIsNotFocused()
        assertNoHandwriting()
    }

    @Test
    fun `a finger dragged across the composer toward the end edge still opens the drawer`() {
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(drawer = drawer)
        val line = bounds(composerField)

        finger().down(Offset(px(40f), line.bottom + px(18f))).moveBy(Offset(px(260f), 0f), steps = 20).up()

        compose.waitForIdle()
        assertThat(drawer.isOpen).isTrue()
        assertNoHandwriting()
    }

    @Test
    fun `a pen stroke on a queued message being reworded writes into it, and pulls nothing`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        compose.onNodeWithContentDescription("Edit queued follow-up").performClick()
        compose.waitForIdle()
        val edit = compose.onNodeWithTag("account-queue-edit")
        val box = bounds(edit)

        stylus().down(Offset(box.center.x, box.top)).moveBy(Offset(-px(140f), px(10f))).up()

        edit.assertIsFocused()
        assertHandwritingStarted()
        assertThat(panel.fraction).isEqualTo(0f)
    }

    @Test
    fun `a pen stroke begun at the search row's edge writes into the search, and the drawer stays open`() {
        val drawer = CursorDrawerState(DrawerValue.Open)
        show(drawer = drawer)
        openSearch()
        val row = compose.onNodeWithTag("sidebar-search").fetchSemanticsNode().boundsInWindow

        // Toward the start edge, which is the way a drag shuts the drawer.
        stylus().down(Offset(row.center.x, row.top + px(2f))).moveBy(Offset(-px(120f), px(10f))).up()

        assertHandwritingStarted()
        compose.waitForIdle()
        assertThat(drawer.isOpen).isTrue()
        assertThat(drawer.fraction).isEqualTo(1f)
    }

    @Test
    fun `a pen stroke begun a little above the search row writes into the search`() {
        val drawer = CursorDrawerState(DrawerValue.Open)
        show(drawer = drawer)
        openSearch()
        val row = compose.onNodeWithTag("sidebar-search").fetchSemanticsNode().boundsInWindow

        stylus().down(Offset(row.center.x, row.top - px(8f))).moveBy(Offset(px(100f), px(12f))).up()

        searchField.assertIsFocused()
        assertHandwritingStarted()
        assertThat(drawer.fraction).isEqualTo(1f)
    }

    @Test
    fun `a pen stroke begun just below the search row, over the list, writes into the search rather than folding or scrolling it`() {
        val drawer = CursorDrawerState(DrawerValue.Open)
        show(drawer = drawer)
        openSearch()
        val row = compose.onNodeWithTag("sidebar-search").fetchSemanticsNode().boundsInWindow

        // Over the list's first line, the Today header, which folds its group on a tap; downward, the way the list scrolls.
        stylus().down(Offset(row.center.x, row.bottom + px(20f))).moveBy(Offset(-px(60f), px(80f))).up()

        searchField.assertIsFocused()
        assertHandwritingStarted()
        compose.onNodeWithText("Morning standup").assertExists()
        compose.runOnIdle { assertThat(opened).isEmpty() }
        assertThat(drawer.fraction).isEqualTo(1f)
    }

    @Test
    fun `a pen tap on the chat under the search still opens it`() {
        val drawer = CursorDrawerState(DrawerValue.Open)
        show(drawer = drawer)
        openSearch()
        val chat = compose.onNodeWithText("Morning standup").fetchSemanticsNode().boundsInWindow

        stylus().down(chat.center).up()

        compose.runOnIdle { assertThat(opened).containsExactly("today") }
        assertNoHandwriting()
    }

    @Test
    fun `a finger dragged from the search row toward the start edge still shuts the drawer`() {
        val drawer = CursorDrawerState(DrawerValue.Open)
        show(drawer = drawer)
        openSearch()
        val row = compose.onNodeWithTag("sidebar-search").fetchSemanticsNode().boundsInWindow

        finger().down(row.center).moveBy(Offset(-px(240f), 0f), steps = 20).up()

        compose.waitForIdle()
        assertThat(drawer.isOpen).isFalse()
        assertNoHandwriting()
    }

    @Test
    fun `a pen stroke down the Project name writes into it rather than scrolling or dragging the sheet`() {
        var dismissed = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ProjectEditorSheet(
                    target = ProjectEditorTarget.Create,
                    initialName = "",
                    initialAppearance = null,
                    repositories = listOf(Repository("https://github.com/acme/billing")),
                    ownedRepoUrls = emptyList(),
                    repositoriesLoading = false,
                    busy = false,
                    error = null,
                    onRefreshRepositories = {},
                    onConfirm = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("project-name")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val name = compose.onNodeWithTag("project-name")
        val box = bounds(name)
        name.assertIsNotFocused()

        // Downward, the way a sheet is dragged shut and its steps scrolled.
        stylus(on = dialog).down(Offset(box.left + px(30f), box.center.y)).moveBy(Offset(px(20f), px(160f))).up()

        name.assertIsFocused()
        assertHandwritingStarted()
        compose.runOnIdle { assertThat(dismissed).isFalse() }
        compose.onNodeWithText("New Project").assertExists()
    }
}
