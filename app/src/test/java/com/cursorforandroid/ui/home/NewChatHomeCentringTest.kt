package com.cursorforandroid.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
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
 * The New Chat pane's composer and what it lists as one block: in the middle of the pane while shorter than it, from
 * the top once taller; drawn there from the first frame; gliding to its new place as the list comes in, with what came
 * in already under the composer and never over it; and, as the keyboard cuts the pane short, kept in view and in the
 * middle of what is left, frame by frame.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NewChatHomeCentringTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var listed by mutableStateOf(AgentListUiState())
    private var paneHeight by mutableStateOf<Dp?>(null)
    private var withHeader = true

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

    private fun setPane(home: NewChatHome, list: AgentListUiState, withHeader: Boolean) {
        listed = list
        this.withHeader = withHeader
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val height = paneHeight
                Box(if (height != null) Modifier.fillMaxWidth().height(height) else Modifier.fillMaxSize()) {
                    HomeScreen(
                        graph = graph,
                        listState = listed,
                        onOpenSidebar = if (withHeader) ({}) else null,
                        onOpenAgent = {},
                        onLaunchOpen = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                        home = home,
                        projectsAvailable = true,
                    )
                }
            }
        }
    }

    private fun show(home: NewChatHome, list: AgentListUiState = NewChatHomeFixtures.list(), withHeader: Boolean = true) {
        setPane(home, list, withHeader)
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(NewChatHomeCopy.PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun node(tag: String): SemanticsNode = compose.onNode(hasTestTag(tag)).fetchSemanticsNode()

    private val SemanticsNode.top: Float get() = positionInRoot.y
    private val SemanticsNode.bottom: Float get() = positionInRoot.y + size.height

    private fun composer(): SemanticsNode = node(NewChatHomeTags.COMPOSER)

    private fun page(): SemanticsNode = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()

    private fun tiles(): List<SemanticsNode> = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes()

    private fun px(dp: Dp): Float = with(compose.density) { dp.toPx() }

    /** The room the block is centred in: the list, inside its content padding. */
    private fun room(): ClosedFloatingPointRange<Float> {
        val page = page()
        return page.top + px(pageTopPadding(withHeader))..page.bottom - px(PageBottomPadding)
    }

    /** The composer's top as far below the room's top as the block's foot is above the room's foot. */
    private fun assertCentred(blockBottom: Float = maxOf(composer().bottom, tiles().maxOfOrNull { it.bottom } ?: 0f)) {
        val room = room()
        val above = composer().top - room.start
        val below = room.endInclusive - blockBottom
        assertWithMessage("room above the block").that(above).isGreaterThan(px(40.dp))
        assertWithMessage("room above the block against room below it").that(above).isWithin(1.5f).of(below)
    }

    private fun frame() {
        compose.mainClock.advanceTimeBy(FRAME_MILLIS)
        compose.waitForIdle()
    }

    @Test
    fun `Composer only sits in the middle of a phone's pane`() {
        show(NewChatHome.COMPOSER)
        assertCentred()
    }

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `Composer only sits in the middle of a tablet's pane`() {
        show(NewChatHome.COMPOSER, withHeader = false)
        assertCentred()
    }

    @Test
    fun `Projects that fit a phone's pane are one block with the composer, in its middle`() {
        show(NewChatHome.PROJECTS)
        assertThat(tiles()).hasSize(5)
        assertCentred()
    }

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `Projects on a tablet are one block with the composer, in the pane's middle rather than at its top`() {
        show(NewChatHome.PROJECTS, withHeader = false)
        assertThat(tiles()).hasSize(5)
        assertCentred()
    }

    @Test
    fun `recent chats taller than the pane start at its top and scroll from there`() {
        show(NewChatHome.RECENT)
        assertThat(composer().top).isWithin(1.5f).of(room().start)
        assertThat(page().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()).isGreaterThan(0f)
    }

    @Test
    fun `the first frame draws the block where it stays`() {
        compose.mainClock.autoAdvance = false
        setPane(NewChatHome.COMPOSER, NewChatHomeFixtures.list(), withHeader = true)
        compose.waitForIdle()
        val first = composer().top
        repeat(30) {
            frame()
            assertThat(composer().top).isWithin(1f).of(first)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertCentred()
    }

    @Test
    fun `a list coming in glides the composer and what came in as one block, without a jump or an overlap`() {
        show(NewChatHome.PROJECTS, list = AgentListUiState())
        assertThat(tiles()).isEmpty()
        assertCentred()
        val alone = composer().top

        compose.mainClock.autoAdvance = false
        listed = NewChatHomeFixtures.list()
        val tops = mutableListOf<Float>()
        repeat(60) {
            frame()
            val composer = composer()
            tops += composer.top
            tiles().forEach { assertThat(it.top).isAtLeast(composer.bottom + px(ComposerGap) - 1.5f) }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(tiles()).hasSize(5)
        assertCentred()
        val settled = composer().top

        assertWithMessage("the frame the list came in").that(tops.first()).isWithin(1.5f).of(alone)
        assertThat(tops).isInOrder(Comparator.reverseOrder<Float>())
        assertWithMessage("frames on the way").that(tops.count { it < alone - 1.5f && it > settled + 1.5f }).isAtLeast(4)
    }

    @Test
    fun `as the keyboard rises the composer stays in view, in the middle of what is left, frame by frame`() {
        show(NewChatHome.COMPOSER)
        val full = with(compose.density) { compose.onRoot().fetchSemanticsNode().size.height.toDp() }
        compose.mainClock.autoAdvance = false
        for (step in 1..KEYBOARD_FRAMES) {
            paneHeight = full - KEYBOARD * step / KEYBOARD_FRAMES
            frame()
            val page = page()
            assertThat(composer().top).isAtLeast(page.top)
            assertThat(composer().bottom).isAtMost(page.bottom)
            frame()
            assertCentred()
        }
    }

    @Test
    fun `where the keyboard leaves too little room for the block, the composer tops it, in view`() {
        show(NewChatHome.PROJECTS)
        assertCentred()
        val full = with(compose.density) { compose.onRoot().fetchSemanticsNode().size.height.toDp() }
        compose.mainClock.autoAdvance = false
        for (step in 1..KEYBOARD_FRAMES) {
            paneHeight = full - KEYBOARD * step / KEYBOARD_FRAMES
            frame()
            val page = page()
            assertThat(composer().top).isAtLeast(page.top)
            assertThat(composer().bottom).isAtMost(page.bottom)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(composer().top).isWithin(1.5f).of(room().start)
    }

    private companion object {
        const val TABLET = "w1000dp-h720dp-night-320dpi"
        const val FRAME_MILLIS = 16L
        const val KEYBOARD_FRAMES = 12
        val KEYBOARD = 380.dp
    }
}
