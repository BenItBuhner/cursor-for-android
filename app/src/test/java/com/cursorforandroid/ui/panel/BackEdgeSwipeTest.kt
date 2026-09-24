package com.cursorforandroid.ui.panel

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.BackEdgeMinWidth
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bennett's recording: a predictive-back swipe from the right edge of a chat sometimes opened the side panel instead of
 * going back. Android hands the app the down and first moves of an edge swipe before it takes the pointer for back
 * (the app then sees a cancel), and a quick swipe's first move is already past touch slop: the panel's open drag had
 * started following the finger by the time the cancel came, and settled on the finger's speed as though it had lifted.
 *
 * Neither sheet opens for a swipe that starts in a back-gesture strip — the right edge's for the panel, the left edge's
 * for the sidebar drawer, mirrored in RTL — and a swipe from just clear of a strip opens them as before. The strips are
 * the window's system gesture insets, never narrower than [BackEdgeMinWidth]; a finger in one still scrolls the
 * transcript and a code block, and a drag cancelled mid-way settles by where it is, not by the finger's speed.
 *
 * The scene is the chat's shape, as in [SidePanelSwipeTest]: the drawer around a panel host around a transcript with a
 * real code block. Every swipe starts from the window's coordinates and moves once a 16 ms frame; 3 000 px/s is well
 * over the 400 dp/s fling threshold, as a back swipe is.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class BackEdgeSwipeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The furthest open each sheet has been, frame by frame, since the scene was shown: a twitch counts. */
    private var panelPeak = 0f
    private var drawerPeak = 0f

    private val transcript = LazyListState()

    private fun show(
        panel: SidePanelState = SidePanelState(SidePanelValue.Closed),
        drawer: CursorDrawerState = CursorDrawerState(DrawerValue.Closed),
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        compose.setContent {
            LaunchedEffect(panel) { snapshotFlow { panel.fraction }.collect { panelPeak = maxOf(panelPeak, it) } }
            LaunchedEffect(drawer) { snapshotFlow { drawer.fraction }.collect { drawerPeak = maxOf(drawerPeak, it) } }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    CursorDrawer(state = drawer, drawerWidth = 300.dp, drawerContent = { Box(Modifier.fillMaxSize().testTag("drawer-body")) }) {
                        SidePanelHost(state = panel, panelWidth = PanelWidth, panelContent = { Box(Modifier.fillMaxSize().testTag("panel-body")) }) {
                            LazyColumn(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag("transcript"), state = transcript) {
                                items(4) { Text("Reply $it", Modifier.fillMaxWidth().height(64.dp).padding(16.dp).testTag("reply-$it")) }
                                item { CodeBlock(LongLine, "kotlin", Modifier.padding(16.dp).testTag("code-block")) }
                                items(30) { Text("Later reply $it", Modifier.fillMaxWidth().height(64.dp).padding(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun px(dp: Dp): Int = with(compose.density) { dp.roundToPx() }

    private val windowWidth: Float get() = compose.onRoot().fetchSemanticsNode().size.width.toFloat()

    /** Halfway down a reply, in the window's coordinates. */
    private val replyY: Float get() = compose.onNodeWithTag("reply-1").fetchSemanticsNode().boundsInRoot.center.y

    /**
     * A finger down at [from] (in the window's coordinates) that travels [dx] at [pxPerSecond], one move a frame, and
     * then does [finish]: lifts, unless told otherwise.
     */
    private fun swipe(from: Offset, dx: Float, pxPerSecond: Float = 3_000f, finish: TouchInjectionScope.() -> Unit = { up() }) {
        compose.onRoot().performTouchInput {
            val frames = (abs(dx) / (pxPerSecond * FrameMillis / 1000f)).roundToInt().coerceAtLeast(1)
            down(from)
            repeat(frames) { moveBy(Offset(dx / frames, 0f), delayMillis = FrameMillis) }
            finish()
        }
        compose.waitForIdle()
    }

    /** The system's back swipe as the app sees it: the down, a few quick moves toward [dx]'s side, then the cancel as it takes the pointer. */
    private fun backSwipe(from: Offset, dx: Float) = swipe(from, dx) { cancel() }

    private fun assertRestsAt(panel: SidePanelState, value: SidePanelValue) {
        compose.waitForIdle()
        assertThat(panel.targetValue).isEqualTo(value)
        assertThat(panel.fraction).isWithin(0.001f).of(if (value == SidePanelValue.Open) 1f else 0f)
    }

    private fun assertRestsAt(drawer: CursorDrawerState, value: DrawerValue) {
        compose.waitForIdle()
        assertThat(drawer.targetValue).isEqualTo(value)
        assertThat(drawer.fraction).isWithin(0.001f).of(if (value == DrawerValue.Open) 1f else 0f)
    }

    /** The system gesture insets the window reports, [left] and [right] wide, dispatched to the Compose view as the window dispatches them. */
    private fun dispatchGestureInsets(left: Dp, right: Dp) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(px(left), 0, px(right), 0))
            .build()
        val target = composeView()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(target, insets) }
        compose.waitForIdle()
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun codeScroller() = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and (hasTestTag("code-block") or hasAnyAncestor(hasTestTag("code-block"))),
    )

    private fun codeScroll(): Float = codeScroller().fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    // -- the side panel, from the end edge ---------------------------------------------------------------------------

    @Test
    fun `the system's back swipe from the right edge never opens the panel`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(panel, drawer)
        val strip = px(BackEdgeMinWidth)
        backSwipe(Offset(windowWidth - strip / 2f, replyY), dx = -px(PanelWidth) * 0.3f)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(panelPeak).isEqualTo(0f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertThat(drawerPeak).isEqualTo(0f)
    }

    @Test
    fun `a swipe from inside the right edge's back strip does not open the panel, even when nothing takes it away`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        val strip = px(BackEdgeMinWidth)
        // The strip's innermost pixel: 3-button navigation, where nothing takes the swipe, still leaves it be.
        swipe(Offset(windowWidth - strip + 1f, replyY), dx = -px(PanelWidth) * 0.6f)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(panelPeak).isEqualTo(0f)
    }

    @Test
    fun `a swipe from just clear of the right edge's back strip opens the panel`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        val strip = px(BackEdgeMinWidth)
        swipe(Offset(windowWidth - strip - 1f, replyY), dx = -px(PanelWidth) * 0.3f)
        assertRestsAt(panel, SidePanelValue.Open)
    }

    @Test
    fun `a drag taken away mid-way settles by where the sheet is, not by the finger's speed`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        // Well clear of the strip and a fling's speed: lifted, this opens the panel. Cancelled, it has no fling.
        backSwipe(Offset(windowWidth * 0.7f, replyY), dx = -px(PanelWidth) * 0.3f)
        assertThat(panelPeak).isGreaterThan(0.1f)
        assertRestsAt(panel, SidePanelValue.Closed)
    }

    @Test
    fun `the panel's strip is as wide as the window's system gesture inset on the right`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        dispatchGestureInsets(left = WideInset, right = WideInset)
        val inset = px(WideInset)
        assertThat(inset).isGreaterThan(px(BackEdgeMinWidth) + 4)

        // Inside the reported strip, though clear of the narrowest one: the panel stays shut.
        swipe(Offset(windowWidth - inset + 2f, replyY), dx = -px(PanelWidth) * 0.6f)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(panelPeak).isEqualTo(0f)

        swipe(Offset(windowWidth - inset - 2f, replyY), dx = -px(PanelWidth) * 0.3f)
        assertRestsAt(panel, SidePanelValue.Open)
    }

    @Test
    fun `a code block under the right edge's strip still scrolls, and at its end hands the back swipe to nothing`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        // Wide enough that the strip reaches over the code block's end, as the system's does on a phone.
        dispatchGestureInsets(left = WideInset, right = WideInset)
        val block = compose.onNodeWithTag("code-block").fetchSemanticsNode().boundsInRoot
        val from = Offset(block.right - px(8.dp), block.top + block.height * 0.75f)
        assertThat(from.x).isAtLeast(windowWidth - px(WideInset))

        assertThat(codeScroll()).isEqualTo(0f)
        swipe(from, dx = -px(PanelWidth) * 0.3f, pxPerSecond = 800f)
        assertThat(codeScroll()).isGreaterThan(0f)

        codeScroller().performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
        compose.waitForIdle()
        val end = codeScroll()
        swipe(from, dx = -px(PanelWidth) * 0.6f)
        backSwipe(from, dx = -px(PanelWidth) * 0.3f)
        assertThat(codeScroll()).isEqualTo(end)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(panelPeak).isEqualTo(0f)
    }

    @Test
    fun `a finger in the strip that goes up the screen still scrolls the transcript`() {
        show()
        val strip = px(BackEdgeMinWidth)
        compose.onRoot().performTouchInput {
            down(Offset(windowWidth - strip / 2f, height * 0.7f))
            repeat(20) { moveBy(Offset(0f, -height * 0.02f), delayMillis = FrameMillis) }
            up()
        }
        compose.runOnIdle { assertThat(transcript.firstVisibleItemIndex to transcript.firstVisibleItemScrollOffset).isNotEqualTo(0 to 0) }
    }

    // -- the sidebar drawer, from the start edge ---------------------------------------------------------------------

    @Test
    fun `the left rail is the mirror image - the back swipe from the left edge never opens the drawer, a swipe from just clear of it does`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(panel, drawer)
        val strip = px(BackEdgeMinWidth)

        backSwipe(Offset(strip / 2f, replyY), dx = windowWidth * 0.3f)
        swipe(Offset(strip - 1f, replyY), dx = windowWidth * 0.6f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertThat(drawerPeak).isEqualTo(0f)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(panelPeak).isEqualTo(0f)

        swipe(Offset(strip + 1f, replyY), dx = windowWidth * 0.6f)
        assertRestsAt(drawer, DrawerValue.Open)
    }

    @Test
    fun `the drawer's strip is as wide as the window's system gesture inset on the left`() {
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(drawer = drawer)
        dispatchGestureInsets(left = WideInset, right = WideInset)
        val inset = px(WideInset)

        swipe(Offset(inset - 2f, replyY), dx = windowWidth * 0.6f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertThat(drawerPeak).isEqualTo(0f)

        swipe(Offset(inset + 2f, replyY), dx = windowWidth * 0.6f)
        assertRestsAt(drawer, DrawerValue.Open)
    }

    @Test
    fun `the open drawer still closes by a drag from the scrim, its strip included`() {
        val drawer = CursorDrawerState(DrawerValue.Open)
        show(drawer = drawer)
        val strip = px(BackEdgeMinWidth)
        swipe(Offset(windowWidth - strip / 2f, replyY), dx = -windowWidth * 0.5f)
        assertRestsAt(drawer, DrawerValue.Closed)
    }

    @Test
    fun `a drawer drag taken away mid-way settles by where the sheet is, as the panel's does`() {
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(drawer = drawer)
        backSwipe(Offset(windowWidth * 0.2f, replyY), dx = windowWidth * 0.2f)
        assertThat(drawerPeak).isGreaterThan(0.1f)
        assertRestsAt(drawer, DrawerValue.Closed)
    }

    // -- right to left ------------------------------------------------------------------------------------------------

    @Test
    fun `right to left the strips swap sheets - the left edge's is the panel's, the right edge's the drawer's`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(panel, drawer, direction = LayoutDirection.Rtl)
        val strip = px(BackEdgeMinWidth)

        backSwipe(Offset(strip / 2f, replyY), dx = px(PanelWidth) * 0.3f)
        swipe(Offset(strip - 1f, replyY), dx = px(PanelWidth) * 0.6f)
        backSwipe(Offset(windowWidth - strip / 2f, replyY), dx = -windowWidth * 0.3f)
        swipe(Offset(windowWidth - strip + 1f, replyY), dx = -windowWidth * 0.6f)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(panelPeak).isEqualTo(0f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertThat(drawerPeak).isEqualTo(0f)

        swipe(Offset(strip + 1f, replyY), dx = px(PanelWidth) * 0.3f)
        assertRestsAt(panel, SidePanelValue.Open)
    }

    private companion object {
        const val FrameMillis = 16L
        val PanelWidth = 360.dp
        /** A wide back sensitivity: wider than the floor, and reaching over a code block's end on this 411 dp screen. */
        val WideInset = 40.dp
        val LongLine = "val route = listOf(" + (1..60).joinToString(", ") { "\"segment-$it\"" } + ")"
    }
}
