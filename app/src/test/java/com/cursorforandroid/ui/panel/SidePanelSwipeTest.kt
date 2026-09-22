package com.cursorforandroid.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.CodeBlock
import com.cursorforandroid.ui.components.CursorDrawer
import com.cursorforandroid.ui.components.CursorDrawerState
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The right panel's drag, the sidebar drawer's model from the other side: the sheet follows the finger, and letting go
 * commits on a fling or on having passed half-way and otherwise settles back. Closed, a drag toward the start edge
 * anywhere on the conversation pulls it in — unless something inside took the drag first: a code block scrolls until
 * it reaches its end, a held finger is a selection, and a drag toward the end edge is still the drawer's.
 *
 * The scene is the chat's shape: the drawer around a panel host around a transcript list with a real code block in it.
 * Every drag is stepped at the test clock's 16 ms frame; 300 px/s is 114 dp/s, under the 400 dp/s fling threshold, and
 * 3 000 px/s is well over it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidePanelSwipeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The furthest open the sheet has been, frame by frame, since the scene was shown: a twitch is caught even once it has settled back. */
    private var peak = 0f

    private fun show(panel: SidePanelState, drawer: CursorDrawerState = CursorDrawerState(DrawerValue.Closed), direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            LaunchedEffect(panel) { snapshotFlow { panel.fraction }.collect { peak = maxOf(peak, it) } }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    CursorDrawer(state = drawer, drawerWidth = 300.dp, drawerContent = { Box(Modifier.fillMaxSize().testTag("drawer-body")) }) {
                        SidePanelHost(
                            state = panel,
                            panelWidth = PanelWidth,
                            panelContent = {
                                Column(Modifier.fillMaxSize().testTag("panel-body")) {
                                    Text("Panel", Modifier.padding(16.dp))
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("panel-row")) {
                                        repeat(24) { Text("Pill $it", Modifier.padding(12.dp)) }
                                    }
                                }
                            },
                        ) {
                            LazyColumn(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag("transcript")) {
                                items(6) { Text("Reply $it", Modifier.fillMaxWidth().height(64.dp).padding(16.dp).testTag("reply-$it")) }
                                item { CodeBlock(LongLine, "kotlin", Modifier.padding(16.dp).testTag("code-block")) }
                                items(6) { Text("Later reply $it", Modifier.fillMaxWidth().height(64.dp).padding(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** A finger down at [from] that travels [dx] at [pxPerSecond], one move a frame, and lifts unless [lift] is off. */
    private fun TouchInjectionScope.drag(from: Offset, dx: Float, pxPerSecond: Float, lift: Boolean = true) {
        val frames = (kotlin.math.abs(dx) / (pxPerSecond * FrameMillis / 1000f)).roundToInt().coerceAtLeast(1)
        down(from)
        repeat(frames) { moveBy(Offset(dx / frames, 0f), delayMillis = FrameMillis) }
        if (lift) up()
    }

    private val panelWidthPx: Float get() = with(compose.density) { PanelWidth.toPx() }

    private fun dragPanel(dx: Float, pxPerSecond: Float, lift: Boolean = true) =
        compose.onNodeWithTag("panel-body").performTouchInput { drag(Offset(width * 0.1f, centerY), dx, pxPerSecond, lift) }

    private fun dragReply(dx: Float, pxPerSecond: Float, lift: Boolean = true) =
        compose.onNodeWithTag("reply-2").performTouchInput { drag(Offset(if (dx < 0) width * 0.8f else width * 0.2f, centerY), dx, pxPerSecond, lift) }

    private fun scroller(within: String) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and (hasTestTag(within) or hasAnyAncestor(hasTestTag(within))))

    private fun scrolled(within: String): Float = scroller(within).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun codeScroll(): Float = scrolled("code-block")

    private fun assertRestsAt(panel: SidePanelState, value: SidePanelValue) {
        compose.waitForIdle()
        assertThat(panel.targetValue).isEqualTo(value)
        assertThat(panel.fraction).isWithin(0.001f).of(if (value == SidePanelValue.Open) 1f else 0f)
    }

    @Test
    fun `a slow drag past half-way closes the open panel, the sheet following the finger`() {
        val panel = SidePanelState(SidePanelValue.Open)
        show(panel)
        val width = panelWidthPx
        dragPanel(dx = width * 0.6f, pxPerSecond = 300f, lift = false)
        compose.waitForIdle()
        // Down and moving: the sheet is where the finger put it, the slop aside.
        assertThat(panel.fraction).isWithin(0.05f).of(0.4f)
        compose.onNodeWithTag("panel-body").performTouchInput { up() }
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    @Test
    fun `a fling closes the open panel on speed alone`() {
        val panel = SidePanelState(SidePanelValue.Open)
        show(panel)
        // A fifth of the sheet in a few frames: nowhere near half-way, so only the fling can close it.
        dragPanel(dx = panelWidthPx * 0.2f, pxPerSecond = 3_000f)
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    @Test
    fun `a short slow drag settles back open`() {
        val panel = SidePanelState(SidePanelValue.Open)
        show(panel)
        dragPanel(dx = panelWidthPx * 0.3f, pxPerSecond = 300f)
        assertRestsAt(panel, SidePanelValue.Open)
    }

    @Test
    fun `a drag on the scrim beside the sheet closes it too`() {
        val panel = SidePanelState(SidePanelValue.Open)
        show(panel)
        compose.onNodeWithTag("transcript").performTouchInput { drag(Offset(width * 0.02f, centerY), dx = width * 0.5f, pxPerSecond = 1_500f) }
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    @Test
    fun `a right-to-left drag on the conversation opens the panel, following the finger`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        val width = compose.onNodeWithTag("transcript").fetchSemanticsNode().size.width.toFloat()
        dragReply(dx = -width * 0.5f, pxPerSecond = 800f, lift = false)
        compose.waitForIdle()
        assertThat(panel.fraction).isWithin(0.05f).of(width * 0.5f / panelWidthPx)
        compose.onNodeWithTag("reply-2").performTouchInput { up() }
        assertRestsAt(panel, SidePanelValue.Open)
    }

    @Test
    fun `a short right-to-left drag on the conversation settles back closed`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        dragReply(dx = -panelWidthPx * 0.25f, pxPerSecond = 300f)
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    @Test
    fun `a code block scrolls horizontally without moving the panel`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        assertThat(codeScroll()).isEqualTo(0f)
        compose.onNodeWithTag("code-block").performTouchInput { drag(Offset(width * 0.8f, height * 0.75f), dx = -width * 0.6f, pxPerSecond = 800f, lift = false) }
        compose.waitForIdle()
        assertThat(codeScroll()).isGreaterThan(0f)
        compose.onNodeWithTag("code-block").performTouchInput { up() }
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(peak).isEqualTo(0f)
    }

    @Test
    fun `a code block at its end hands the rest of the drag to the panel`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        scroller("code-block").performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
        compose.waitForIdle()
        val end = codeScroll()
        assertThat(end).isGreaterThan(0f)
        compose.onNodeWithTag("code-block").performTouchInput { drag(Offset(width * 0.8f, height * 0.75f), dx = -width * 0.6f, pxPerSecond = 800f) }
        assertRestsAt(panel, SidePanelValue.Open)
        assertThat(codeScroll()).isEqualTo(end)
    }

    @Test
    fun `a scroller inside the open sheet keeps its drag until it is back at its start, then the sheet closes`() {
        val panel = SidePanelState(SidePanelValue.Open)
        show(panel)
        compose.onNodeWithTag("panel-row").performTouchInput { drag(Offset(width * 0.8f, centerY), dx = -width * 0.25f, pxPerSecond = 800f) }
        compose.waitForIdle()
        val scrolledBy = scrolled("panel-row")
        assertThat(scrolledBy).isGreaterThan(0f)
        assertRestsAt(panel, SidePanelValue.Open)
        // Back the other way, further than the row has to give: it scrolls back to its start, then the sheet goes.
        compose.onNodeWithTag("panel-row").performTouchInput { drag(Offset(width * 0.03f, centerY), dx = scrolledBy + panelWidthPx * 0.65f, pxPerSecond = 800f) }
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    @Test
    fun `a left-to-right drag on the conversation is still the sidebar drawer's`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(panel, drawer)
        compose.onNodeWithTag("reply-2").performTouchInput { drag(Offset(width * 0.05f, centerY), dx = width * 0.6f, pxPerSecond = 1_500f) }
        compose.waitForIdle()
        assertThat(drawer.targetValue).isEqualTo(DrawerValue.Open)
        assertThat(drawer.fraction).isWithin(0.001f).of(1f)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(peak).isEqualTo(0f)
    }

    @Test
    fun `a finger held before it moves is never the panel's`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        compose.onNodeWithTag("reply-2").performTouchInput {
            down(Offset(width * 0.8f, centerY))
            // Past the long-press timeout (400 ms by default): what follows is a selection or a message's menu.
            advanceEventTime(700)
            repeat(20) { moveBy(Offset(-width * 0.03f, 0f), delayMillis = FrameMillis) }
        }
        compose.onNodeWithTag("reply-2").performTouchInput { up() }
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(peak).isEqualTo(0f)
    }

    @Test
    fun `right to left, the panel opens with a drag toward the start and flings shut toward the end`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel, direction = LayoutDirection.Rtl)
        dragReply(dx = panelWidthPx * 0.6f, pxPerSecond = 800f)
        assertRestsAt(panel, SidePanelValue.Open)
        compose.onNodeWithTag("panel-body").performTouchInput { drag(Offset(width * 0.9f, centerY), dx = -width * 0.2f, pxPerSecond = 3_000f) }
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    private companion object {
        const val FrameMillis = 16L
        val PanelWidth = 360.dp
        val LongLine = "val route = listOf(" + (1..60).joinToString(", ") { "\"segment-$it\"" } + ")"
    }
}
