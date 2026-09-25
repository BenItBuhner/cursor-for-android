package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Color as AndroidColor
import android.view.PointerIcon as AndroidPointerIcon

/**
 * The boundary between two panes as a resize edge: nothing drawn on it, a strip either side of it that takes a drag
 * across from anywhere along its length and leaves everything else to the panes under it. The scene is the wide
 * window's shape: a list on each side of the edge, the start one the pane it resizes, at mdpi so a pixel is a dp.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-night-mdpi")
class PaneResizeEdgeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var paneWidth by mutableStateOf(300.dp)
    private val kept = mutableListOf<Dp>()
    private val tapped = mutableListOf<String>()
    private val startList = LazyListState()
    private val endList = LazyListState()

    private fun show(width: Dp = 300.dp) {
        paneWidth = width
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val edges = rememberBackGestureEdges()
                Box(Modifier.fillMaxSize().backGestureEdges(edges)) {
                    Row(Modifier.fillMaxSize()) {
                        Rows("start", startList, StartColor, Modifier.width(paneWidth))
                        Rows("end", endList, EndColor, Modifier.weight(1f))
                    }
                    PaneResizeEdge(
                        side = PaneSide.Start,
                        paneWidth = { paneWidth },
                        onResize = { paneWidth = it.coerceIn(MinWidth, MaxWidth) },
                        onResizeDone = { kept += paneWidth },
                        contentDescription = RESIZE,
                        edges = edges,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(paneWidth.roundToPx() - PaneResizeEdgeWidth.roundToPx() / 2, 0) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Rows(name: String, state: LazyListState, color: Color, modifier: Modifier) {
        LazyColumn(modifier.fillMaxHeight().background(color), state = state) {
            items(40) { i -> Box(Modifier.fillMaxWidth().height(RowHeight).clickable { tapped += "$name-$i" }) }
        }
    }

    private val edgeX: Float get() = paneWidth.value

    private fun rowY(index: Int): Float = RowHeight.value * index + RowHeight.value / 2

    /** A finger down at [from] (the window's coordinates), [steps] moves along ([dx], [dy]) a frame apart, then [finish]. */
    private fun gesture(from: Offset, dx: Float, dy: Float = 0f, steps: Int = 6, finish: TouchInjectionScope.() -> Unit = { up() }) {
        compose.onRoot().performTouchInput {
            down(from)
            repeat(steps) { moveBy(Offset(dx / steps, dy / steps), delayMillis = 16) }
            finish()
        }
        compose.waitForIdle()
    }

    private fun tap(at: Offset) {
        compose.onRoot().performTouchInput { click(at) }
        compose.waitForIdle()
    }

    private fun scrolled(state: LazyListState) = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0

    private fun assertWidth(expected: Dp) = assertThat(paneWidth.value).isWithin(0.01f).of(expected.value)

    private fun pixel(image: Bitmap, x: Float, y: Float): Int = image.getPixel(x.toInt(), y.toInt())

    /** The Compose view drawn by hand: the harness's capture times out waiting on a redraw while a finger is down. */
    private fun screen(): Bitmap {
        var image: Bitmap? = null
        compose.runOnUiThread { image = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).drawToBitmap() }
        return checkNotNull(image)
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    @Test
    fun `a drag across from anywhere along the edge, either side of it, resizes the pane and the edge stays under the finger`() {
        show()
        // Near the top on the boundary, halfway down just inside the pane, near the bottom just past it.
        for ((across, y) in listOf(0f to 6f, -6f to 300f, 6f to 594f)) {
            val before = paneWidth
            gesture(Offset(edgeX + across, y), dx = 60f)
            assertWidth(before + 60.dp)
        }
        gesture(Offset(edgeX, 300f), dx = -150f)
        assertWidth(330.dp)
        assertThat(kept.map { it.value }).containsExactly(360f, 420f, 480f, 330f).inOrder()
        assertThat(tapped).isEmpty()
    }

    @Test
    fun `a tap in the strip is the pane's under it, on either side of the edge`() {
        show()
        tap(Offset(edgeX - 5f, rowY(3)))
        tap(Offset(edgeX + 5f, rowY(5)))
        assertThat(tapped).containsExactly("start-3", "end-5").inOrder()
        assertWidth(300.dp)
        assertThat(kept).isEmpty()
    }

    @Test
    fun `a scroll up or down that starts in the strip scrolls the list under it, a little sideways drift and all`() {
        show()
        gesture(Offset(edgeX - 4f, 500f), dx = 0f, dy = -300f, steps = 30)
        assertThat(scrolled(startList)).isTrue()
        gesture(Offset(edgeX + 4f, 500f), dx = 24f, dy = -300f, steps = 30)
        assertThat(scrolled(endList)).isTrue()
        assertWidth(300.dp)
        assertThat(kept).isEmpty()
        assertThat(tapped).isEmpty()
    }

    @Test
    fun `a drag that begins in the window's back-gesture strip is left to back, lifted or taken away`() {
        // The edge a few dp from the window's left side, where a pane mid-slide can bring it: inside the strip.
        show(width = 8.dp)
        assertThat(edgeX).isLessThan(BackEdgeMinWidth.value)
        gesture(Offset(edgeX, 300f), dx = 200f)
        gesture(Offset(edgeX, 300f), dx = 200f) { cancel() }
        assertWidth(8.dp)
        assertThat(kept).isEmpty()
    }

    @Test
    fun `a drag cancelled from outside puts the pane back as the drag found it and keeps nothing`() {
        show()
        compose.onRoot().performTouchInput {
            down(Offset(edgeX, 300f))
            repeat(4) { moveBy(Offset(20f, 0f), delayMillis = 16) }
        }
        compose.waitForIdle()
        assertWidth(380.dp)
        compose.onRoot().performTouchInput { cancel() }
        compose.waitForIdle()
        assertWidth(300.dp)
        assertThat(kept).isEmpty()
    }

    @Test
    fun `nothing marks the edge at rest, and it is lit only while a drag is under way`() {
        show()
        val y = 300f
        val rest = screen()
        assertThat(pixel(rest, edgeX - 1f, y)).isEqualTo(StartColor.toArgb())
        assertThat(pixel(rest, edgeX, y)).isEqualTo(EndColor.toArgb())
        assertThat(pixel(rest, edgeX - PaneResizeEdgeWidth.value / 2, y)).isEqualTo(StartColor.toArgb())
        assertThat(pixel(rest, edgeX + PaneResizeEdgeWidth.value / 2 - 1f, y)).isEqualTo(EndColor.toArgb())

        compose.onRoot().performTouchInput {
            down(Offset(edgeX, y))
            repeat(4) { moveBy(Offset(10f, 0f), delayMillis = 16) }
        }
        compose.waitForIdle()
        assertWidth(340.dp)
        val lit = screen()
        for (x in listOf(edgeX - 1f, edgeX)) {
            val px = pixel(lit, x, y)
            // The accent over either pane: bluer than the grey under it, and not the grey.
            assertThat(px).isNotEqualTo(StartColor.toArgb())
            assertThat(px).isNotEqualTo(EndColor.toArgb())
            assertThat(AndroidColor.blue(px)).isGreaterThan(AndroidColor.red(px))
        }
        // Only the line: a few dp off it the panes are as they were.
        assertThat(pixel(lit, edgeX - 4f, y)).isEqualTo(StartColor.toArgb())
        assertThat(pixel(lit, edgeX + 3f, y)).isEqualTo(EndColor.toArgb())

        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        val after = screen()
        assertThat(pixel(after, edgeX - 1f, y)).isEqualTo(StartColor.toArgb())
        assertThat(pixel(after, edgeX, y)).isEqualTo(EndColor.toArgb())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a mouse over the strip shows the resize cursor and keeps it through a drag that starts at once and runs past the clamp`() {
        show()
        val resizeCursor = AndroidPointerIcon.getSystemIcon(compose.activity, AndroidPointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
        compose.onRoot().performMouseInput { moveTo(Offset(edgeX - 150f, 300f)) }
        compose.waitForIdle()
        assertThat(composeView().pointerIcon).isNotEqualTo(resizeCursor)

        compose.onRoot().performMouseInput { moveTo(Offset(edgeX + 5f, rowY(6))) }
        compose.waitForIdle()
        assertThat(composeView().pointerIcon).isEqualTo(resizeCursor)

        // A click there is still the row's under it.
        compose.onRoot().performMouseInput { click(Offset(edgeX + 5f, rowY(6))) }
        compose.waitForIdle()
        assertThat(tapped).containsExactly("end-6")

        // No slop to clear and no call on direction: a mouse drags nothing else here, so its first move across is the edge's.
        compose.onRoot().performMouseInput {
            press()
            moveBy(Offset(0f, 3f))
            moveBy(Offset(-4f, 0f))
        }
        compose.waitForIdle()
        assertWidth(296.dp)
        assertThat(composeView().pointerIcon).isEqualTo(resizeCursor)

        // One move far faster than the edge can follow, and on past where the pane stops: still the resize cursor.
        compose.onRoot().performMouseInput { moveBy(Offset(480f, 0f)) }
        compose.waitForIdle()
        assertWidth(MaxWidth)
        assertThat(composeView().pointerIcon).isEqualTo(resizeCursor)

        // Let go out there: the width is kept, and the next move off the strip shows the ordinary cursor again.
        compose.onRoot().performMouseInput { release() }
        compose.waitForIdle()
        assertThat(kept.map { it.value }).containsExactly(600f)
        assertThat(tapped).containsExactly("end-6")
        compose.onRoot().performMouseInput { moveBy(Offset(0f, 10f)) }
        compose.waitForIdle()
        assertThat(composeView().pointerIcon).isNotEqualTo(resizeCursor)
    }

    @Test
    fun `TalkBack reads how wide the pane is and widens or narrows it a step at a time`() {
        show()
        val edge = compose.onNodeWithContentDescription(RESIZE)
        assertThat(edge.fetchSemanticsNode().config[SemanticsProperties.StateDescription]).isEqualTo("300 dp wide")
        fun act(label: String) {
            val action = edge.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
            compose.runOnIdle { action.action() }
            compose.waitForIdle()
        }
        act("Widen")
        assertWidth(340.dp)
        act("Narrow")
        act("Narrow")
        assertWidth(260.dp)
        assertThat(edge.fetchSemanticsNode().config[SemanticsProperties.StateDescription]).isEqualTo("260 dp wide")
        assertThat(kept.map { it.value }).containsExactly(340f, 300f, 260f).inOrder()
    }

    @Test
    @Config(shadows = [ShadowHapticLog::class])
    fun `a finger is felt once where the pane stops at either end of its range, however far on it pushes, and again only once back off it`() {
        show()
        HapticLog.clear()
        val stop = Haptic.SlotTick.constant()
        fun moves(vararg dx: Float) {
            compose.onRoot().performTouchInput { dx.forEach { moveBy(Offset(it, 0f), delayMillis = 16) } }
            compose.waitForIdle()
        }
        compose.onRoot().performTouchInput { down(Offset(edgeX, 300f)) }
        // To 500: the pane follows, and nothing is felt.
        moves(200f)
        assertWidth(500.dp)
        assertThat(HapticLog.played).isEmpty()
        // Asked for 650, then 800: it stops at 600, felt the once.
        moves(150f, 150f)
        assertWidth(MaxWidth)
        assertThat(HapticLog.played).containsExactly(stop)
        // Back to 596 and out again, as a finger held at the stop wobbles: not felt again.
        moves(-204f, 10f)
        assertThat(HapticLog.played).containsExactly(stop)
        // Back to 576, well off it, and out again: felt again.
        moves(-30f, 40f)
        assertThat(HapticLog.played).containsExactly(stop, stop)
        // Down past the narrowest: that end, felt the once.
        moves(-700f, -50f)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertWidth(MinWidth)
        assertThat(HapticLog.played).containsExactly(stop, stop, stop)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    @Config(shadows = [ShadowHapticLog::class])
    fun `a mouse's drag past the end of the range and TalkBack's step past it are not felt`() {
        show()
        HapticLog.clear()
        compose.onRoot().performMouseInput {
            moveTo(Offset(edgeX, 300f))
            press()
            moveBy(Offset(480f, 0f))
            release()
        }
        compose.waitForIdle()
        assertWidth(MaxWidth)
        val widen = compose.onNodeWithContentDescription(RESIZE).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Widen" }
        compose.runOnIdle { widen.action() }
        compose.waitForIdle()
        assertWidth(MaxWidth)
        assertThat(HapticLog.played).isEmpty()
    }

    private companion object {
        const val RESIZE = "Resize pane"
        val RowHeight = 48.dp
        val MinWidth = 4.dp
        val MaxWidth = 600.dp
        val StartColor = Color(0xFF202020)
        val EndColor = Color(0xFF303030)
    }
}
