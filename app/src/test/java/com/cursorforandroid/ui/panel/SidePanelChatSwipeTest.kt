package com.cursorforandroid.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.ContextState
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.ProjectWorker
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.cursorforandroid.ui.components.AttachmentCarousel
import com.cursorforandroid.ui.components.CodeBlock
import com.cursorforandroid.ui.components.CursorDrawer
import com.cursorforandroid.ui.components.CursorDrawerState
import com.cursorforandroid.ui.components.MdBlock
import com.cursorforandroid.ui.components.TableAlign
import com.cursorforandroid.ui.components.TableBlock
import com.cursorforandroid.ui.components.stylusWriting
import com.cursorforandroid.ui.conversation.MessageActions
import com.cursorforandroid.ui.conversation.QueuedFollowUps
import com.cursorforandroid.ui.projects.ProjectActions
import com.cursorforandroid.ui.projects.projectSection
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The right panel's swipe over what a chat is made of — a reply and a message with their long-press menus, a
 * picture, a code block, a table, a row of attachments, the queue's card above the composer and the composer itself
 * — and with the chat changing under the finger: a turn streaming in, new turns landing, a Project's primaries
 * refreshed. It opens from anywhere on any of it and closes from the scrim beside any of it; the scrollers scroll
 * first and hand over at their ends. Beside a pinned panel the same drag moves nothing, and is still no message's menu.
 *
 * A heavy panel — a Project coordinator's, hundreds of primaries — makes the sheet's first frame slow to come, and
 * the samples the finger made meanwhile arrive in one move's history ([stalledMove]): the flick still reads as a
 * flick. The panel lays such a section's rows out as its list's own items, so opening it composes only a screenful.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidePanelChatSwipeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The furthest open the sheet has been, frame by frame, since the scene was shown. */
    private var peak = 0f

    // What streams in while a finger is on the chat.
    private var streamed by mutableStateOf("")
    private var laterTurns by mutableIntStateOf(0)
    private var refreshes by mutableIntStateOf(0)
    private var composerText by mutableStateOf("")

    private class Scene(
        val panel: SidePanelState,
        val drawer: CursorDrawerState,
        val pinned: PinnedPanel?,
        val panelContent: @Composable (generation: Int) -> Unit,
    )

    /** The shell's side of a panel pinned beside the chat, standing [open] or shut at [PanelWidth]. */
    private class Pinned(open: Boolean) : PinnedPanel {
        override var open: Boolean? by mutableStateOf(open)
            private set
        override val width: Dp = PanelWidth
        override val splits = false
        override fun setOpen(open: Boolean) {
            this.open = open
        }
        override fun slideOpen(open: Boolean, keyed: Boolean) = setOpen(open)
        override fun resize(width: Dp) = Unit
        override fun resizeDone() = Unit
        override fun resizeCancelled() = Unit
    }

    private var scene by mutableStateOf<Scene?>(null)

    /** A fresh chat screen with [panel] and [drawer]; a test that loops over the contents shows one for each. */
    private fun show(
        panel: SidePanelState,
        drawer: CursorDrawerState = CursorDrawerState(DrawerValue.Closed),
        pinned: PinnedPanel? = null,
        panelContent: @Composable (generation: Int) -> Unit = { PlainPanel(it) },
    ) {
        val first = scene == null
        scene = Scene(panel, drawer, pinned, panelContent)
        peak = 0f
        if (first) compose.setContent { scene?.let { key(it) { Screen(it) } } }
        compose.waitForIdle()
    }

    @Composable
    private fun Screen(scene: Scene) {
        val panel = scene.panel
        LaunchedEffect(panel) { snapshotFlow { panel.fraction }.collect { peak = maxOf(peak, it) } }
        CursorTheme(mode = ThemeMode.Dark) {
            // Where the panel pins, the drawer is the wide shell's rail flyout, which is never dragged out.
            CursorDrawer(
                state = scene.drawer,
                drawerWidth = 300.dp,
                gesturesEnabled = scene.pinned == null,
                drawerContent = { Box(Modifier.fillMaxSize().testTag("drawer-body")) },
            ) {
                // Read here, at the top: a refresh recomposes the whole screen around the panel, and hands the
                // host a new panel and a new chat, as a chat screen's live state does.
                val generation = refreshes
                SidePanelHost(state = panel, panelWidth = PanelWidth, pinned = scene.pinned, panelContent = { scene.panelContent(generation) }) {
                    Chat(generation)
                }
            }
        }
    }

    /** The chat as the recording shows it, top to bottom, each kind of content tagged by what it is. */
    @Composable
    private fun Chat(generation: Int) {
        val colors = CursorTheme.colors
        Column(Modifier.fillMaxSize().background(colors.canvas).testTag("chat")) {
            Text("Revenue Scaling Pipeline · $generation", Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("transcript")) {
                item {
                    MessageActions(Reply + streamed, Modifier.fillMaxWidth().testTag(Content.Reply.tag)) {
                        Text(Reply + streamed, Modifier.padding(16.dp), maxLines = 3)
                    }
                }
                item {
                    MessageActions(Question, Modifier.padding(start = 64.dp, end = 16.dp).fillMaxWidth().testTag(Content.Message.tag)) {
                        Text(Question, Modifier.background(colors.fill).padding(12.dp))
                    }
                }
                item { Box(Modifier.padding(16.dp).fillMaxWidth().height(96.dp).background(colors.fill).clickable {}.testTag(Content.Picture.tag)) }
                item { CodeBlock(LongLine, "kotlin", Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(Content.Code.tag)) }
                item { TableBlock(WideTable, CursorTheme.typography.small, colors.textPrimary, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(Content.Table.tag)) }
                item {
                    AttachmentCarousel(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        items(16) { Box(Modifier.size(64.dp).background(colors.fill).clickable {}) }
                    }
                }
                items(laterTurns) { Text("Later turn $it", Modifier.fillMaxWidth().padding(16.dp)) }
            }
            QueuedFollowUps(
                queue = listOf(QueuedFollowUp(id = "queued", text = "And check the pilot's framing too", queuedAtMillis = 0L)),
                thumbnails = emptyMap(),
                onEdit = {},
                onSteer = {},
                onRemove = {},
                modifier = Modifier.padding(horizontal = 16.dp).testTag(Content.Queue.tag),
            )
            BasicTextField(
                composerText,
                { composerText = it },
                Modifier.padding(16.dp).fillMaxWidth().stylusWriting().background(colors.fill).padding(12.dp).testTag(Content.Composer.tag),
            )
        }
    }

    @Composable
    private fun PlainPanel(generation: Int) {
        Column(Modifier.fillMaxSize().testTag("panel-body")) {
            Text("Panel · refresh $generation", Modifier.padding(16.dp))
        }
    }

    // -- the gestures -------------------------------------------------------------------------------------------------

    private val panelWidthPx: Float get() = with(compose.density) { PanelWidth.toPx() }

    /** A finger down at [from] that travels [dx] at [pxPerSecond], one move a frame, and lifts unless [lift] is off. */
    private fun TouchInjectionScope.drag(from: Offset, dx: Float, pxPerSecond: Float, lift: Boolean = true) {
        val frames = (abs(dx) / (pxPerSecond * FrameMillis / 1000f)).roundToInt().coerceAtLeast(1)
        down(from)
        repeat(frames) { moveBy(Offset(dx / frames, 0f), delayMillis = FrameMillis) }
        if (lift) up()
    }

    /**
     * What Android delivers for a stretch of a drag the app was too busy to keep up with — the sheet's first frame,
     * composing a heavy panel: nothing while the frame lasts, then one move that carries every sample the finger made
     * meanwhile as its history, [SampleMillis] apart, the last of them the move itself. [origin] is the touched node's
     * top-left in the root: `moveWithHistory` takes the history in the root's coordinates, not the node's as the rest
     * of the injection scope does.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun TouchInjectionScope.stalledMove(origin: Offset, dx: Float, pxPerSecond: Float) {
        val samples = (abs(dx) / pxPerSecond * 1000f / SampleMillis).roundToInt().coerceAtLeast(2)
        val from = currentPosition()!!
        val step = dx / samples
        val times = (1 until samples).map { -(samples - it) * SampleMillis }
        val positions = (1 until samples).map { origin + from + Offset(step * it, 0f) }
        updatePointerBy(0, Offset(dx, 0f))
        moveWithHistory(times, positions, delayMillis = samples * SampleMillis)
    }

    private fun origin(content: Content): Offset = compose.onNodeWithTag(content.tag).fetchSemanticsNode().boundsInRoot.topLeft

    /** Where on a piece of content a swipe toward the start edge begins: toward its far end. */
    private val TouchInjectionScope.start: Offset get() = Offset(width * 0.85f, centerY)

    private fun flickOpen(content: Content) = compose.onNodeWithTag(content.tag).performTouchInput { drag(start, dx = -panelWidthPx * 0.25f, pxPerSecond = 3_000f) }

    private fun assertRestsAt(panel: SidePanelState, value: SidePanelValue) {
        compose.waitForIdle()
        assertThat(panel.targetValue).isEqualTo(value)
        assertThat(panel.fraction).isWithin(0.001f).of(if (value == SidePanelValue.Open) 1f else 0f)
    }

    private fun scroller(within: String) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and (hasTestTag(within) or hasAnyAncestor(hasTestTag(within))))

    private fun scrolled(within: String): Float = scroller(within).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    // -- over each kind of content ------------------------------------------------------------------------------------

    @Test
    fun `a flick toward the start opens the panel from every kind of content that has no horizontal scroll of its own`() {
        for (content in listOf(Content.Reply, Content.Message, Content.Picture, Content.Queue, Content.Composer)) {
            val panel = SidePanelState(SidePanelValue.Closed)
            show(panel)
            flickOpen(content)
            assertWithMessage(content) { assertRestsAt(panel, SidePanelValue.Open) }
        }
    }

    @Test
    fun `a slow drag past half-way opens the panel from every kind of content, and a short one settles back`() {
        for (content in listOf(Content.Reply, Content.Message, Content.Picture, Content.Queue, Content.Composer)) {
            val panel = SidePanelState(SidePanelValue.Closed)
            show(panel)
            compose.onNodeWithTag(content.tag).performTouchInput { drag(start, dx = -panelWidthPx * 0.2f, pxPerSecond = 300f) }
            assertWithMessage(content) { assertRestsAt(panel, SidePanelValue.Closed) }
            compose.onNodeWithTag(content.tag).performTouchInput { drag(start, dx = -panelWidthPx * 0.65f, pxPerSecond = 300f) }
            assertWithMessage(content) { assertRestsAt(panel, SidePanelValue.Open) }
        }
    }

    @Test
    fun `the chat's horizontal scrollers scroll first and hand the drag to the panel at their ends`() {
        for (content in listOf(Content.Code, Content.Table, Content.Attachments)) {
            val panel = SidePanelState(SidePanelValue.Closed)
            show(panel)
            peak = 0f
            compose.onNodeWithTag(content.tag).performTouchInput { drag(Offset(width * 0.8f, centerY), dx = -width * 0.4f, pxPerSecond = 800f) }
            assertWithMessage(content) {
                assertRestsAt(panel, SidePanelValue.Closed)
                assertThat(peak).isEqualTo(0f)
                assertThat(scrolled(content.tag)).isGreaterThan(0f)
            }
            scroller(content.tag).performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
            compose.waitForIdle()
            val end = scrolled(content.tag)
            compose.onNodeWithTag(content.tag).performTouchInput { drag(Offset(width * 0.8f, centerY), dx = -panelWidthPx * 0.3f, pxPerSecond = 3_000f) }
            assertWithMessage(content) {
                assertRestsAt(panel, SidePanelValue.Open)
                assertThat(scrolled(content.tag)).isEqualTo(end)
            }
        }
    }

    @Test
    fun `the open panel closes with a flick from the scrim beside every kind of content`() {
        for (content in Content.entries) {
            val panel = SidePanelState(SidePanelValue.Open)
            show(panel)
            // The scrim's strip is what the sheet leaves of the screen, at the content's height.
            val chat = compose.onNodeWithTag("chat").fetchSemanticsNode().boundsInRoot
            val y = compose.onNodeWithTag(content.tag).fetchSemanticsNode().boundsInRoot.center.y - chat.top
            compose.onNodeWithTag("chat").performTouchInput {
                val strip = width - panelWidthPx
                drag(Offset(strip * 0.4f, y), dx = panelWidthPx * 0.25f, pxPerSecond = 3_000f)
            }
            assertWithMessage(content) { assertRestsAt(panel, SidePanelValue.Closed) }
        }
    }

    @Test
    fun `a finger held on a message before it moves is its menu, never the panel`() {
        for (content in listOf(Content.Reply, Content.Message)) {
            val panel = SidePanelState(SidePanelValue.Closed)
            show(panel)
            peak = 0f
            compose.onNodeWithTag(content.tag).performTouchInput {
                down(start)
                advanceEventTime(700)
                repeat(20) { moveBy(Offset(-width * 0.03f, 0f), delayMillis = FrameMillis) }
                up()
            }
            assertWithMessage(content) {
                assertRestsAt(panel, SidePanelValue.Closed)
                assertThat(peak).isEqualTo(0f)
            }
        }
    }

    private fun menuShown() = compose.onAllNodesWithText(CopyMessage).fetchSemanticsNodes().isNotEmpty()

    @Test
    @Config(qualifiers = "w841dp-h701dp-land-night-420dpi")
    fun `beside a pinned panel a slow drag toward the start across a message moves nothing and is not its menu, and a hold still is`() {
        for (open in listOf(true, false)) {
            for (content in listOf(Content.Reply, Content.Message)) {
                val value = if (open) SidePanelValue.Open else SidePanelValue.Closed
                val panel = SidePanelState(value)
                show(panel, pinned = Pinned(open))
                // Slower than the long-press timeout to cover, but across the touch slop well inside it.
                compose.onNodeWithTag(content.tag).performTouchInput { drag(start, dx = -width * 0.4f, pxPerSecond = 300f) }
                assertWithMessage(content) {
                    assertRestsAt(panel, value)
                    assertThat(menuShown()).isFalse()
                }
                compose.onNodeWithTag(content.tag).performTouchInput {
                    down(start)
                    advanceEventTime(700)
                    repeat(20) { moveBy(Offset(-width * 0.03f, 0f), delayMillis = FrameMillis) }
                    up()
                }
                assertWithMessage(content) {
                    assertRestsAt(panel, value)
                    assertThat(menuShown()).isTrue()
                }
            }
        }
    }

    @Test
    fun `a left-to-right drag on any of it is still the sidebar drawer's`() {
        for (content in listOf(Content.Reply, Content.Picture, Content.Queue)) {
            val panel = SidePanelState(SidePanelValue.Closed)
            val drawer = CursorDrawerState(DrawerValue.Closed)
            show(panel, drawer)
            peak = 0f
            compose.onNodeWithTag(content.tag).performTouchInput { drag(Offset(width * 0.1f, centerY), dx = width * 0.6f, pxPerSecond = 1_500f) }
            assertWithMessage(content) {
                compose.waitForIdle()
                assertThat(drawer.targetValue).isEqualTo(DrawerValue.Open)
                assertRestsAt(panel, SidePanelValue.Closed)
                assertThat(peak).isEqualTo(0f)
            }
        }
    }

    // -- a slow first frame ---------------------------------------------------------------------------------------------

    @Test
    fun `a flick the app only catches up with after a slow frame still opens the panel`() {
        for (content in listOf(Content.Reply, Content.Message, Content.Picture)) {
            val panel = SidePanelState(SidePanelValue.Closed)
            show(panel)
            val origin = origin(content)
            compose.onNodeWithTag(content.tag).performTouchInput {
                down(start)
                // Past the slop in a frame: the sheet starts in, and the frame that composes it is a long one.
                moveBy(Offset(-width * 0.04f, 0f), delayMillis = FrameMillis)
                // Meanwhile the finger flicks on across a third of the sheet and lifts; the app gets it all at once.
                stalledMove(origin, dx = -panelWidthPx * 0.3f, pxPerSecond = 2_000f)
                up()
            }
            assertWithMessage(content) { assertRestsAt(panel, SidePanelValue.Open) }
        }
    }

    @Test
    fun `a slow drag the app only catches up with after a slow frame still settles back short of half-way`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        val origin = origin(Content.Reply)
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput {
            down(start)
            moveBy(Offset(-width * 0.04f, 0f), delayMillis = FrameMillis)
            stalledMove(origin, dx = -panelWidthPx * 0.3f, pxPerSecond = 250f)
            up()
        }
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    @Test
    fun `a finger that stopped before the slow frame ended and then lifted settles by how far it went`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        val origin = origin(Content.Reply)
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput {
            down(start)
            moveBy(Offset(-width * 0.04f, 0f), delayMillis = FrameMillis)
            stalledMove(origin, dx = -panelWidthPx * 0.3f, pxPerSecond = 2_000f)
            // Held still a moment before letting go: no fling left in it, and a third of the way is not half.
            advanceEventTime(120)
            up()
        }
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    // -- the chat changing under the finger -------------------------------------------------------------------------------

    @Test
    fun `a turn streaming in, new turns and a refreshed panel mid-drag neither cancel the drag nor move the sheet`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput { drag(start, dx = -panelWidthPx * 0.3f, pxPerSecond = 600f, lift = false) }
        compose.waitForIdle()
        val held = panel.fraction
        assertThat(held).isGreaterThan(0.2f)
        streamed += " And the next take is rendering now.".repeat(4)
        laterTurns += 3
        refreshes++
        compose.waitForIdle()
        assertThat(panel.fraction).isEqualTo(held)
        assertThat(panel.isAnimating).isFalse()
        // The rest of the flick: still the same drag, and it opens.
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput {
            repeat(4) { moveBy(Offset(-panelWidthPx * 0.05f, 0f), delayMillis = 8) }
            up()
        }
        assertRestsAt(panel, SidePanelValue.Open)
    }

    @Test
    fun `updates every frame of the drag and of the settle still land the sheet open`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput { down(start) }
        repeat(8) {
            streamed += " word"
            refreshes++
            compose.onNodeWithTag(Content.Reply.tag).performTouchInput { moveBy(Offset(-panelWidthPx * 0.04f, 0f), delayMillis = 8) }
        }
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput { up() }
        // The settle, a frame at a time, with the chat and the panel changing on every one of them.
        compose.mainClock.autoAdvance = false
        try {
            var sawItMoving = false
            repeat(12) {
                streamed += " word"
                laterTurns++
                refreshes++
                compose.mainClock.advanceTimeByFrame()
                if (panel.fraction > 0f && panel.fraction < 1f) sawItMoving = true
            }
            assertThat(sawItMoving).isTrue()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertRestsAt(panel, SidePanelValue.Open)
    }

    // -- a Project coordinator's panel ------------------------------------------------------------------------------------

    private fun projectPanel(workers: Int, generation: Int): ProjectViewState {
        val parent = AgentParent(ProjectId, AgentParentKind.PROJECT_WORKER)
        return ProjectViewState(
            projectId = ProjectId,
            root = agent(ProjectId, "Revenue Scaling Pipeline", running = true).copy(isProject = true, projectAppearance = ProjectAppearance("rocket", "purple")),
            workers = (0 until workers).map { i ->
                val id = "bc-w$i"
                ProjectWorker(agent(id, "Primary $i · pass $generation", running = i % 40 == 0).copy(parent = parent), WorkerMembership(id, ProjectId, if (i % 3 == 0) WorkerSpawnKind.ADOPTED else WorkerSpawnKind.CREATED))
            },
            hasSynced = true,
            context = ContextState.NoStore,
            actionsAvailable = true,
        )
    }

    private fun agent(id: String, name: String, running: Boolean) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = Now - 3_600_000L,
        updatedAtMillis = Now - 600_000L,
        latestRunId = "run-$id",
        repoUrl = "https://github.com/benitbuhner/account-cooking-and-campaigning",
        startingRef = "main",
    )

    private val projectActions = ProjectActions(
        onOpenAgent = {}, onSteer = {}, onPause = {}, onResume = {}, onStop = {}, onRelease = {}, onMove = {},
        onNewWorker = {}, onAdopt = {}, onEditAppearance = {}, onLoadContext = {}, onContextUp = {}, onOpenContextFile = {}, onRefresh = {},
    )

    /**
     * The panel itself, as a coordinator's chat has it left on its Details tab (the view model keeps the tab while the
     * panel is shut): the Overview, then the Project with [workers] primaries, then the rest.
     */
    @Composable
    private fun CoordinatorPanel(workers: Int, generation: Int) {
        val project = DefaultPanelSections.project
        val registry = PanelRegistry.default().with(
            PanelSection(
                id = project.id,
                icon = project.icon,
                visible = project.visible,
                availability = project.availability,
                hint = project.hint,
                expandedByDefault = project.expandedByDefault,
                items = { _, _ -> { projectSection(projectPanel(workers, generation), LocalAgentState(), busy = false, actions = projectActions, nowMillis = Now) } },
            ),
        )
        ConversationPanel(PanelFixtures.projectRoot().copy(tabs = PanelTabsState(selectedKey = PanelTab.Details.key)), PanelActions.None, onClose = {}, registry = registry)
    }

    private fun composedPrimaries(): Int = compose.onAllNodesWithTag("project-primary").fetchSemanticsNodes().size

    @Test
    fun `a Project panel with hundreds of primaries composes a screenful of them as it opens, and opens on the flick`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel, panelContent = { CoordinatorPanel(workers = 276, generation = it) })
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput { drag(start, dx = -panelWidthPx * 0.2f, pxPerSecond = 600f, lift = false) }
        compose.waitForIdle()
        assertThat(panel.fraction).isGreaterThan(0f)
        // The sheet's first frames: the rows on screen, not all 276 of them.
        assertThat(composedPrimaries()).isIn(com.google.common.collect.Range.closed(1, 30))
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput {
            repeat(3) { moveBy(Offset(-panelWidthPx * 0.05f, 0f), delayMillis = 8) }
            up()
        }
        assertRestsAt(panel, SidePanelValue.Open)
        assertThat(composedPrimaries()).isIn(com.google.common.collect.Range.closed(1, 30))
    }

    @Test
    fun `the Project's primaries refreshed mid-drag and mid-settle leave the sheet on its way open`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel, panelContent = { CoordinatorPanel(workers = 276, generation = it) })
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput { drag(start, dx = -panelWidthPx * 0.3f, pxPerSecond = 600f, lift = false) }
        compose.waitForIdle()
        val held = panel.fraction
        refreshes++
        compose.waitForIdle()
        assertThat(panel.fraction).isEqualTo(held)
        compose.onNodeWithTag(Content.Reply.tag).performTouchInput {
            repeat(4) { moveBy(Offset(-panelWidthPx * 0.05f, 0f), delayMillis = 8) }
            up()
        }
        compose.mainClock.autoAdvance = false
        try {
            repeat(6) {
                refreshes++
                compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertRestsAt(panel, SidePanelValue.Open)
    }

    @Test
    fun `the open Project panel scrolls through its primaries, and closes with a flick that starts on one of them`() {
        val panel = SidePanelState(SidePanelValue.Open)
        show(panel, panelContent = { CoordinatorPanel(workers = 276, generation = it) })
        fun listScroll() = compose.onNodeWithTag("panel-sections").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertThat(listScroll()).isEqualTo(0f)
        compose.onNodeWithTag("panel-sections").performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.2f) }
        compose.waitForIdle()
        assertThat(listScroll()).isGreaterThan(0f)
        assertRestsAt(panel, SidePanelValue.Open)
        compose.onAllNodesWithTag("project-primary")[3].performTouchInput { drag(Offset(width * 0.3f, centerY), dx = panelWidthPx * 0.25f, pxPerSecond = 3_000f) }
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    private inline fun assertWithMessage(content: Content, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("over the ${content.name}: ${e.message}", e)
        }
    }

    private enum class Content(val tag: String) {
        Reply("reply"),
        Message("user-message"),
        Picture("picture"),
        Code("code-block"),
        Table("table"),
        Attachments("attachment-row"),
        Queue("queue"),
        Composer("composer"),
    }

    private companion object {
        const val FrameMillis = 16L
        const val CopyMessage = "Copy message"
        /** A touchscreen's report rate: 120 Hz, give or take. */
        const val SampleMillis = 8L
        const val Now = 1_800_000_000_000L
        const val ProjectId = "bc-project"
        val PanelWidth = 360.dp
        const val Reply = "One realism milestone worth a line on its own: the litter mat is the first product to reach industry parity."
        const val Question = "Is the updated video for the body cam footage channel idea back yet or is it actively working on that right now?"
        val LongLine = "val route = listOf(" + (1..60).joinToString(", ") { "\"segment-$it\"" } + ")"
        /** Words too long to wrap, as a table of branch names has: it scrolls rather than fitting the screen. */
        val WideTable = MdBlock.Table(
            header = (1..6).map { "Column $it" },
            alignments = List(6) { TableAlign.Start },
            rows = (1..2).map { row -> (1..6).map { "account_cooking_and_campaigning_${row}_$it" } },
        )
    }
}
